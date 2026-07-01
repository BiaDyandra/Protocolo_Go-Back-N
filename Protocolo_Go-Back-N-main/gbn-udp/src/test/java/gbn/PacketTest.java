package gbn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketTest {

    @Test
    void serializaEDesserializaPacoteData() {
        byte[] dados = "ola mundo".getBytes();
        Packet original = Packet.data(42, dados);

        byte[] raw = original.serialize();
        Packet copia = Packet.deserialize(raw, raw.length);

        assertEquals(Packet.TIPO_DATA, copia.tipo);
        assertEquals(42, copia.numSeq);
        assertArrayEquals(dados, copia.dados);
    }

    @Test
    void serializaEDesserializaAck() {
        Packet ack = Packet.ack(7);
        byte[] raw = ack.serialize();
        Packet copia = Packet.deserialize(raw, raw.length);

        assertEquals(Packet.TIPO_ACK, copia.tipo);
        assertEquals(7, copia.numAck);
        assertEquals(0, copia.dados.length);
    }

    @Test
    void serializaEDesserializaHandshake() {
        Packet hs = Packet.handshake("/tmp/destino.bin|1024|0.1");
        byte[] raw = hs.serialize();
        Packet copia = Packet.deserialize(raw, raw.length);

        assertEquals(Packet.TIPO_HANDSHAKE, copia.tipo);
        assertEquals("/tmp/destino.bin|1024|0.1", copia.payloadAsString());
    }

    @Test
    void serializaEDesserializaFin() {
        Packet fin = Packet.fin(10);
        byte[] raw = fin.serialize();
        Packet copia = Packet.deserialize(raw, raw.length);

        assertEquals(Packet.TIPO_FIN, copia.tipo);
        assertEquals(10, copia.numSeq);
    }
}
