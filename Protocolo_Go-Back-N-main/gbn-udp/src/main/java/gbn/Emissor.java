package gbn;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Emissor GBN.
 *
 * Uso:
 *   java gbn.Emissor <arquivo_origem> <IP_destino>:<path_destino> <tamanho_janela> <prob_perda> [porta]
 *
 * Exemplo:
 *   java gbn.Emissor /home/alice/foto.jpg 192.168.0.10:/tmp/foto_recebida.jpg 8 0.10
 *
 * Suposição assumida (documentada no README): a porta do Receptor é fixa
 * (padrão 5000), podendo ser sobrescrita por um 5º argumento opcional, já
 * que o enunciado não especifica como a porta é passada ao Emissor.
 */
public class Emissor {

    // Porta padrão do receptor quando não especificada na linha de comando
    private static final int PORTA_PADRAO = 5000;

    // Tempo máximo de espera por um ACK antes de retransmitir toda a janela (em ms)
    private static final long TIMEOUT_MS = 500;

    // Estado compartilhado entre a thread de envio e a thread de recepção de ACKs
    private final Object lock = new Object();

    // base: índice do pacote mais antigo ainda não confirmado (início da janela)
    private int base = 0;

    // nextSeqNum: índice do próximo pacote a ser enviado (fim da janela)
    private int nextSeqNum = 0;

    private List<byte[]> segmentos;    // Lista de todos os segmentos do arquivo a transmitir
    private DatagramSocket socket;     // Socket UDP usado para envio e recepção
    private InetAddress receptorAddr;  // Endereço IP do receptor
    private int receptorPort;          // Porta UDP do receptor
    private Timer timer;               // Timer único associado ao pacote mais antigo sem ACK

    // Contadores para as estatísticas exibidas ao final da transferência
    private int pacotesEnviados = 0;
    private int retransmissoes = 0;
    private int acksRecebidos = 0;

    // Flag volátil lida pela thread de ACKs para saber quando encerrar
    private volatile boolean finalizado = false;

    public static void main(String[] args) throws Exception {
        // Valida o número mínimo de argumentos obrigatórios
        if (args.length < 4) {
            System.err.println("Uso: java gbn.Emissor <arquivo_origem> <IP_destino>:<path_destino> "
                    + "<tamanho_janela> <prob_perda> [porta]");
            System.exit(1);
        }

        String arquivoOrigem = args[0];

        // Separa o IP do path de destino usando ":" como delimitador (limite 2 partes)
        String[] destino = args[1].split(":", 2);
        if (destino.length != 2) {
            System.err.println("Formato inválido para destino. Use IP_destino:path_destino");
            System.exit(1);
        }
        String ipDestino = destino[0];
        String pathDestino = destino[1];
        int windowSize = Integer.parseInt(args[2]);
        double probPerda = Double.parseDouble(args[3]);

        // Porta é opcional; usa o padrão se não fornecida
        int porta = args.length >= 5 ? Integer.parseInt(args[4]) : PORTA_PADRAO;

        new Emissor().run(arquivoOrigem, ipDestino, pathDestino, windowSize, probPerda, porta);
    }

    public void run(String arquivoOrigem, String ipDestino, String pathDestino,
                    int windowSize, double probPerda, int porta) throws Exception {

        // Lê todo o conteúdo do arquivo de uma vez e divide em segmentos de MAX_PAYLOAD bytes
        byte[] conteudo = Files.readAllBytes(new File(arquivoOrigem).toPath());
        segmentos = dividirEmSegmentos(conteudo, Packet.MAX_PAYLOAD);

        // Abre um socket UDP em uma porta aleatória disponível (o SO escolhe automaticamente)
        socket = new DatagramSocket();
        receptorAddr = InetAddress.getByName(ipDestino);
        receptorPort = porta;
        timer = new Timer(true); // Timer em modo daemon: não impede o encerramento da JVM

        System.out.println("[Emissor] Arquivo: " + arquivoOrigem + " (" + conteudo.length + " bytes, "
                + segmentos.size() + " segmentos)");
        System.out.println("[Emissor] Destino: " + ipDestino + ":" + porta + " -> " + pathDestino);
        System.out.println("[Emissor] Janela N=" + windowSize + " | probPerda=" + probPerda);

        // 1) Handshake
        // Calcula o MD5 do arquivo original para permitir a verificação de integridade pelo Receptor
        String md5Origem = calcularMD5(conteudo);
        System.out.println("[Emissor] MD5 do arquivo original: " + md5Origem);

        // Envia ao receptor o caminho de destino, o tamanho do arquivo, a probabilidade de perda
        // e o hash MD5 do conteúdo original (para verificação de integridade fim-a-fim)
        String payload = pathDestino + "|" + conteudo.length + "|" + probPerda + "|" + md5Origem;
        enviarPacote(Packet.handshake(payload));

        // Marca o início da transferência para calcular o throughput ao final
        long inicio = System.currentTimeMillis();

        // 2) Thread que escuta ACKs
        // Roda em paralelo ao loop de envio para processar confirmações sem bloquear a janela
        Thread ackListener = new Thread(() -> escutarAcks(windowSize));
        ackListener.start();

        // 3) Loop principal: envia enquanto houver espaço na janela
        // O monitor "lock" sincroniza o acesso às variáveis base e nextSeqNum com a thread de ACKs
        synchronized (lock) {
            while (base < segmentos.size()) {
                // Preenche a janela: envia todos os segmentos disponíveis dentro do limite N
                while (nextSeqNum < base + windowSize && nextSeqNum < segmentos.size()) {
                    enviarSegmento(nextSeqNum);
                    // Inicia o timer apenas ao enviar o primeiro pacote da janela (o mais antigo)
                    if (base == nextSeqNum) {
                        reiniciarTimer(windowSize);
                    }
                    nextSeqNum++;
                }
                // Bloqueia até que um ACK seja recebido e a thread de ACKs chame notifyAll()
                lock.wait();
            }
        }

        // Sinaliza para a thread de ACKs que a transferência terminou
        finalizado = true;
        timer.cancel();
        ackListener.interrupt();

        // 4) Encerramento
        // Envia o pacote FIN para que o receptor saiba que não há mais segmentos
        enviarPacote(Packet.fin(segmentos.size()));

        // Calcula e exibe as estatísticas finais da transferência
        long duracaoMs = System.currentTimeMillis() - inicio;
        double throughputKBs = duracaoMs == 0 ? 0 : (conteudo.length / 1024.0) / (duracaoMs / 1000.0);

        System.out.println("\n[Emissor] Transferência concluída em " + duracaoMs + " ms.");
        System.out.println("[Emissor] Pacotes enviados (incluindo retransmissões): " + pacotesEnviados);
        System.out.println("[Emissor] Retransmissões: " + retransmissoes);
        System.out.println("[Emissor] ACKs recebidos: " + acksRecebidos);
        System.out.printf("[Emissor] Throughput estimado: %.2f KB/s%n", throughputKBs);

        socket.close();
    }

    /**
     * Thread dedicada à recepção de ACKs.
     * Roda em loop até que "finalizado" seja verdadeiro, processando cada ACK recebido:
     * avança a base da janela e notifica o loop principal para continuar o envio.
     */
    private void escutarAcks(int windowSize) {
        byte[] buf = new byte[Packet.HEADER_SIZE + Packet.MAX_PAYLOAD];
        while (!finalizado) {
            try {
                DatagramPacket udpPacket = new DatagramPacket(buf, buf.length);
                socket.receive(udpPacket);
                Packet p = Packet.deserialize(udpPacket.getData(), udpPacket.getLength());

                // Ignora qualquer pacote que não seja ACK
                if (p.tipo != Packet.TIPO_ACK) {
                    continue;
                }

                // Atualiza o estado da janela dentro do bloco sincronizado
                synchronized (lock) {
                    acksRecebidos++;

                    // ACK cumulativo: confirma todos os segmentos até p.numAck inclusive
                    if (p.numAck >= base) {
                        base = p.numAck + 1; // Avança a base da janela

                        if (base == nextSeqNum) {
                            // Todos os pacotes enviados foram confirmados: cancela o timer
                            timer.cancel();
                            timer = new Timer(true);
                        } else {
                            // Ainda há pacotes sem ACK: reinicia o timer para o mais antigo
                            reiniciarTimer(windowSize);
                        }

                        // Notifica o loop principal que há espaço na janela para novos envios
                        lock.notifyAll();
                    }
                }
            } catch (Exception e) {
                // Exceção esperada quando o socket é fechado ao término da transferência
                if (!finalizado) {
                    System.err.println("[Emissor] Erro ao receber ACK: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Cancela o timer atual e agenda um novo com o mesmo timeout.
     * Chamado sempre que a base da janela avança ou um novo segmento é enviado como o mais antigo.
     */
    private void reiniciarTimer(int windowSize) {
        timer.cancel();
        timer = new Timer(true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                onTimeout(windowSize);
            }
        }, TIMEOUT_MS);
    }

    /**
     * Callback acionado quando o timer expira (timeout do GBN).
     * Retransmite todos os pacotes da janela atual (de base até nextSeqNum-1) e reinicia o timer.
     */
    private void onTimeout(int windowSize) {
        synchronized (lock) {
            if (finalizado) return;
            System.out.println("[Emissor] Timeout! Retransmitindo pacotes " + base + " até " + (nextSeqNum - 1));
            // Retransmite toda a janela, conforme exige a FSM do emissor GBN
            for (int seq = base; seq < nextSeqNum; seq++) {
                enviarSegmento(seq);
                retransmissoes++;
            }
            reiniciarTimer(windowSize);
        }
    }

    /**
     * Constrói um pacote DATA com o segmento de índice seq e o envia.
     */
    private void enviarSegmento(int seq) {
        Packet p = Packet.data(seq, segmentos.get(seq));
        enviarPacote(p);
        pacotesEnviados++;
    }

    /**
     * Serializa um pacote e o envia via UDP ao receptor.
     */
    private void enviarPacote(Packet p) {
        try {
            byte[] raw = p.serialize();
            socket.send(new DatagramPacket(raw, raw.length, receptorAddr, receptorPort));
        } catch (Exception e) {
            System.err.println("[Emissor] Erro ao enviar pacote: " + e.getMessage());
        }
    }

    /**
     * Calcula o hash MD5 do conteúdo do arquivo original, em formato hexadecimal.
     * Usado para verificação de integridade fim-a-fim: o Receptor recalcula o MD5
     * do arquivo gravado e compara com este valor, enviado no HANDSHAKE.
     */
    private static String calcularMD5(byte[] conteudo) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hash = md.digest(conteudo);
        return HexFormat.of().formatHex(hash);
    }

    /**
     * Divide o conteúdo do arquivo em segmentos de no máximo tamanhoMax bytes.
     * O último segmento pode ser menor que tamanhoMax se o arquivo não for múltiplo exato.
     * Se o arquivo estiver vazio, retorna uma lista com um segmento vazio para manter o fluxo do protocolo.
     */
    static List<byte[]> dividirEmSegmentos(byte[] conteudo, int tamanhoMax) {
        List<byte[]> segmentos = new ArrayList<>();
        for (int offset = 0; offset < conteudo.length; offset += tamanhoMax) {
            int fim = Math.min(offset + tamanhoMax, conteudo.length);
            byte[] segmento = new byte[fim - offset];
            System.arraycopy(conteudo, offset, segmento, 0, segmento.length);
            segmentos.add(segmento);
        }
        if (segmentos.isEmpty()) {
            segmentos.add(new byte[0]); // arquivo vazio ainda gera 1 segmento
        }
        return segmentos;
    }
}