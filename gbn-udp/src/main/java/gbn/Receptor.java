package gbn;

import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

/**
 * Receptor GBN.
 *
 * Uso: java gbn.Receptor <porta>
 *
 * Fica aguardando um pacote HANDSHAKE contendo "path|tamanhoArquivo|probPerda|md5Origem".
 * Em seguida recebe os pacotes DATA em ordem, simula perda, grava o arquivo e, ao final,
 * compara o MD5 do que foi gravado com o md5Origem recebido no handshake.
 */
public class Receptor {

    public static void main(String[] args) throws Exception {
        // Verifica se o argumento obrigatório (porta) foi fornecido
        if (args.length < 1) {
            System.err.println("Uso: java gbn.Receptor <porta>");
            System.exit(1);
        }
        int porta = Integer.parseInt(args[0]);
        new Receptor().run(porta);
    }

    public void run(int porta) throws Exception {
        // Abre o socket UDP na porta especificada; o bloco try-with-resources garante o fechamento automático
        try (DatagramSocket socket = new DatagramSocket(porta)) {
            System.out.println("[Receptor] Aguardando handshake na porta " + porta + "...");

            // Buffer de recepção dimensionado para caber o maior pacote possível (cabeçalho + payload máximo)
            byte[] buf = new byte[Packet.HEADER_SIZE + Packet.MAX_PAYLOAD];
            DatagramPacket udpPacket = new DatagramPacket(buf, buf.length);

            // 1) Aguarda HANDSHAKE
            // O receptor fica em loop até receber um pacote do tipo HANDSHAKE,
            // ignorando qualquer outro tipo que possa chegar antes
            Packet handshake;
            while (true) {
                socket.receive(udpPacket);
                Packet p = Packet.deserialize(udpPacket.getData(), udpPacket.getLength());
                if (p.tipo == Packet.TIPO_HANDSHAKE) {
                    handshake = p;
                    break;
                }
            }

            // Registra o endereço e a porta do emissor para enviar os ACKs de volta
            InetAddress emissorAddr = udpPacket.getAddress();
            int emissorPort = udpPacket.getPort();

            // Extrai os parâmetros da sessão enviados no payload do HANDSHAKE
            // (formato: "path|tamanho|probPerda|md5Origem")
            String[] partes = handshake.payloadAsString().split("\\|");
            String pathDestino = partes[0];
            long tamanhoArquivo = Long.parseLong(partes[1]);
            double probPerda = Double.parseDouble(partes[2]);
            String md5Origem = partes[3];

            System.out.println("[Receptor] Handshake recebido de " + emissorAddr + ":" + emissorPort);
            System.out.println("[Receptor] Destino: " + pathDestino + " | tamanho: " + tamanhoArquivo
                    + " bytes | probPerda: " + probPerda);
            System.out.println("[Receptor] MD5 esperado (do arquivo original): " + md5Origem);

            // Digest incremental: acumula o hash MD5 dos dados gravados, sem precisar reler o arquivo
            MessageDigest md5Receptor = MessageDigest.getInstance("MD5");

            // 2) Recebe os dados
            int expectedSeqNum = 0;          // Próximo número de sequência esperado (FSM do receptor GBN)
            long totalRecebido = 0;          // Total de bytes gravados no arquivo de destino
            int pacotesRecebidos = 0;        // Contador de pacotes aceitos em ordem
            int pacotesDescartadosSimulados = 0; // Contador de pacotes descartados pela simulação de perda
            Random random = new Random();    // Gerador de números aleatórios para a simulação estocástica

            // Abre o arquivo de destino para escrita; try-with-resources garante fechamento ao final
            try (FileOutputStream fos = new FileOutputStream(pathDestino)) {
                boolean transferenciaFinalizada = false;
                while (!transferenciaFinalizada) {
                    // Bloqueia até receber o próximo datagrama UDP
                    socket.receive(udpPacket);
                    Packet p = Packet.deserialize(udpPacket.getData(), udpPacket.getLength());

                    // Pacote FIN: o emissor sinalizou fim da transferência
                    if (p.tipo == Packet.TIPO_FIN) {
                        transferenciaFinalizada = true;
                        continue;
                    }

                    // Ignora pacotes que não sejam do tipo DATA (ex: retransmissão de HANDSHAKE)
                    if (p.tipo != Packet.TIPO_DATA) {
                        continue;
                    }

                    // Comportamento da FSM GBN para pacotes fora de ordem:
                    // descarta o pacote e reenvia o ACK do último segmento aceito com sucesso
                    if (p.numSeq != expectedSeqNum) {
                        // Fora de ordem: descarta e reenvia o último ACK (não conta como perda simulada)
                        enviarAck(socket, emissorAddr, emissorPort, expectedSeqNum - 1);
                        continue;
                    }

                    // O pacote chegou na ordem correta; conta para as estatísticas
                    pacotesRecebidos++;

                    // Simulação estocástica de perda de pacote:
                    // sorteia um número entre 0.0 e 1.0; se menor que probPerda, descarta sem enviar ACK
                    double r = random.nextDouble();
                    if (r < probPerda) {
                        // Perda simulada: descarta silenciosamente, sem enviar ACK
                        pacotesDescartadosSimulados++;
                        continue;
                    }

                    // Pacote aceito: grava os dados no arquivo, atualiza o hash incremental,
                    // envia ACK e avança o número esperado
                    fos.write(p.dados);
                    md5Receptor.update(p.dados);
                    totalRecebido += p.dados.length;
                    enviarAck(socket, emissorAddr, emissorPort, expectedSeqNum);
                    expectedSeqNum++;
                }
            }

            // Calcula a taxa de perda efetiva observada durante a transferência
            double taxaPerdaEfetiva = pacotesRecebidos == 0 ? 0.0
                    : (double) pacotesDescartadosSimulados / pacotesRecebidos;

            // Finaliza o cálculo do hash MD5 do arquivo recebido e compara com o hash de origem
            String md5Recebido = HexFormat.of().formatHex(md5Receptor.digest());
            boolean integridadeOk = md5Recebido.equalsIgnoreCase(md5Origem);

            // Exibe as estatísticas finais da sessão
            System.out.println("\n[Receptor] Transferência concluída.");
            System.out.println("[Receptor] Bytes gravados: " + totalRecebido + " / " + tamanhoArquivo);
            System.out.println("[Receptor] Pacotes recebidos em ordem: " + pacotesRecebidos);
            System.out.println("[Receptor] Pacotes descartados (perda simulada): " + pacotesDescartadosSimulados);
            System.out.printf("[Receptor] Taxa de perda efetiva: %.4f (configurada: %.4f)%n",
                    taxaPerdaEfetiva, probPerda);
            System.out.println("[Receptor] MD5 do arquivo recebido: " + md5Recebido);
            System.out.println("[Receptor] Verificação de integridade: "
                    + (integridadeOk ? "OK (hashes idênticos)" : "FALHA (hashes diferentes)"));
        }
    }

    /**
     * Serializa e envia um pacote ACK pelo socket UDP.
     *
     * @param socket  socket UDP do receptor
     * @param addr    endereço IP do emissor
     * @param port    porta UDP do emissor
     * @param ackNum  número do último segmento recebido corretamente (ACK cumulativo)
     */
    private void enviarAck(DatagramSocket socket, InetAddress addr, int port, int ackNum) throws Exception {
        byte[] payload = Packet.ack(ackNum).serialize();
        socket.send(new DatagramPacket(payload, payload.length, addr, port));
    }
}