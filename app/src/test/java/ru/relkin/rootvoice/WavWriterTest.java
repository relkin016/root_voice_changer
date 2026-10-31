package ru.relkin.rootvoice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

public class WavWriterTest {
    @Test
    public void writesValidWav() throws Exception {
        File f = File.createTempFile("rvc", ".wav");
        short[] pcm = new short[48000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (i % 200 - 100);
        try (WavWriter w = new WavWriter(f, 48000, 1)) {
            w.write(pcm, 30000);
            w.write(pcm, 18000);   // всего 1 секунда
            assertEquals(1000, w.durationMs());
        }
        assertEquals(44 + 96000, f.length());
        assertEquals(1000, WavWriter.durationMs(f, 48000, 1));

        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(b.array(), 0, 4, "US-ASCII"));
        assertEquals(36 + 96000, b.getInt(4));
        assertEquals("WAVEfmt ", new String(b.array(), 8, 8, "US-ASCII"));
        assertEquals(16, b.getInt(16));
        assertEquals(1, b.getShort(20));        // PCM
        assertEquals(1, b.getShort(22));        // моно
        assertEquals(48000, b.getInt(24));
        assertEquals(96000, b.getInt(28));      // байт в секунду
        assertEquals(2, b.getShort(32));
        assertEquals(16, b.getShort(34));
        assertEquals("data", new String(b.array(), 36, 4, "US-ASCII"));
        assertEquals(96000, b.getInt(40));
        assertEquals((short) -100, b.getShort(44));
        assertEquals((short) -99, b.getShort(44 + 30001 * 2));  // второй кусок начинается с pcm[0]
        f.delete();
    }
}
