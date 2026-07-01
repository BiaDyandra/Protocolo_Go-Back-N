package gbn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmissorTest {

    @Test
    void divideArquivoExatoEmSegmentosCompletos() {
        byte[] conteudo = new byte[2048];
        List<byte[]> segmentos = Emissor.dividirEmSegmentos(conteudo, 1024);

        assertEquals(2, segmentos.size());
        assertEquals(1024, segmentos.get(0).length);
        assertEquals(1024, segmentos.get(1).length);
    }

    @Test
    void divideArquivoComResto() {
        byte[] conteudo = new byte[2500];
        List<byte[]> segmentos = Emissor.dividirEmSegmentos(conteudo, 1024);

        assertEquals(3, segmentos.size());
        assertEquals(452, segmentos.get(2).length);
    }

    @Test
    void arquivoVazioGeraUmSegmentoVazio() {
        List<byte[]> segmentos = Emissor.dividirEmSegmentos(new byte[0], 1024);

        assertEquals(1, segmentos.size());
        assertEquals(0, segmentos.get(0).length);
    }
}
