package gbn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Representa um datagrama do protocolo GBN.
 *
 * Cabeçalho (11 bytes):
 *   tipo            1 byte  (0=DATA, 1=ACK, 2=HANDSHAKE, 3=FIN)
 *   num_seq         4 bytes (int)
 *   num_ack         4 bytes (int)
 *   tamanho_dados   2 bytes (short)
 *   dados           até MAX_PAYLOAD bytes
 *
 * Suposição assumida (documentada no README): os números de sequência são
 * inteiros de 32 bits crescentes (não há "wrap around" de k bits). Para um
 * trabalho acadêmico com arquivos de poucos MB isso é equivalente em
 * comportamento ao espaço de numeração do livro, só que sem o módulo 2^k.
 */
public final class Packet {

    // Tamanho máximo do campo de dados de cada pacote (1 KB por segmento)
    public static final int MAX_PAYLOAD = 1024;

    // Tamanho fixo do cabeçalho: 1 (tipo) + 4 (numSeq) + 4 (numAck) + 2 (tamanho) = 11 bytes
    public static final int HEADER_SIZE = 1 + 4 + 4 + 2;

    // Constantes que identificam o tipo do pacote no campo "tipo" do cabeçalho
    public static final byte TIPO_DATA = 0;       // Pacote de dados
    public static final byte TIPO_ACK = 1;        // Confirmação de recebimento
    public static final byte TIPO_HANDSHAKE = 2;  // Negociação inicial da transferência
    public static final byte TIPO_FIN = 3;        // Sinalização de fim da transferência

    // Campos do cabeçalho e do corpo do pacote (imutáveis após criação)
    public final byte tipo;
    public final int numSeq;
    public final int numAck;
    public final byte[] dados;

    // Construtor principal: recebe todos os campos diretamente
    public Packet(byte tipo, int numSeq, int numAck, byte[] dados) {
        this.tipo = tipo;
        this.numSeq = numSeq;
        this.numAck = numAck;
        // Garante que o array de dados nunca seja nulo, facilitando a serialização
        this.dados = dados == null ? new byte[0] : dados;
    }

    // Fábrica de pacote ACK: confirma o recebimento do segmento com número ackNum
    public static Packet ack(int numAck) {
        return new Packet(TIPO_ACK, 0, numAck, new byte[0]);
    }

    // Fábrica de pacote DATA: carrega um segmento de dados com seu número de sequência
    public static Packet data(int numSeq, byte[] dados) {
        return new Packet(TIPO_DATA, numSeq, 0, dados);
    }

    // Fábrica de pacote FIN: sinaliza ao receptor que a transferência foi concluída
    public static Packet fin(int numSeq) {
        return new Packet(TIPO_FIN, numSeq, 0, new byte[0]);
    }

    // Fábrica de pacote HANDSHAKE: codifica os parâmetros da sessão como texto no campo de dados
    public static Packet handshake(String payload) {
        return new Packet(TIPO_HANDSHAKE, 0, 0, payload.getBytes(StandardCharsets.UTF_8));
    }

    // Interpreta o campo de dados como texto UTF-8 (usado no HANDSHAKE)
    public String payloadAsString() {
        return new String(dados, StandardCharsets.UTF_8);
    }

    /**
     * Serializa o pacote em um array de bytes para envio via UDP.
     * A ordem dos campos segue o layout do cabeçalho definido na documentação da classe.
     */
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + dados.length);
        buf.put(tipo);                      // 1 byte: tipo do pacote
        buf.putInt(numSeq);                 // 4 bytes: número de sequência
        buf.putInt(numAck);                 // 4 bytes: número de confirmação
        buf.putShort((short) dados.length); // 2 bytes: tamanho do payload
        buf.put(dados);                     // N bytes: conteúdo do segmento
        return buf.array();
    }

    /**
     * Desserializa um array de bytes recebido via UDP de volta para um objeto Packet.
     *
     * @param raw    bytes brutos recebidos do DatagramPacket
     * @param length quantidade válida de bytes no array (pode ser menor que raw.length)
     */
    public static Packet deserialize(byte[] raw, int length) {
        ByteBuffer buf = ByteBuffer.wrap(raw, 0, length);
        byte tipo = buf.get();           // Lê o tipo do pacote
        int numSeq = buf.getInt();       // Lê o número de sequência
        int numAck = buf.getInt();       // Lê o número de confirmação
        short tamanho = buf.getShort();  // Lê o tamanho do payload
        byte[] dados = new byte[tamanho];
        buf.get(dados);                  // Lê os bytes do payload
        return new Packet(tipo, numSeq, numAck, dados);
    }

    // Representação textual do pacote, útil para logs e depuração
    @Override
    public String toString() {
        return "Packet{tipo=" + tipo + ", numSeq=" + numSeq + ", numAck=" + numAck
                + ", len=" + dados.length + "}";
    }
}