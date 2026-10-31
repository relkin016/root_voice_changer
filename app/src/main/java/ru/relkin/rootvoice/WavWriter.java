package ru.relkin.rootvoice;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 16-битный PCM WAV: заголовок пишется сразу, размеры дописываются при закрытии. */
final class WavWriter implements AutoCloseable {
    private final RandomAccessFile file;
    private final int sampleRate, channels;
    private final ByteBuffer bytes = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
    private long dataBytes;

    WavWriter(File f, int sampleRate, int channels) throws IOException {
        this.sampleRate = sampleRate;
        this.channels = channels;
        file = new RandomAccessFile(f, "rw");
        file.setLength(0);
        file.write(header(0));
    }

    void write(short[] pcm, int count) throws IOException {
        for (int off = 0; off < count; ) {
            bytes.clear();
            int n = Math.min(count - off, bytes.capacity() / 2);
            for (int i = 0; i < n; i++) bytes.putShort(pcm[off + i]);
            file.write(bytes.array(), 0, n * 2);
            off += n;
            dataBytes += n * 2L;
        }
    }

    /** Длительность записанного, мс. */
    long durationMs() {
        return dataBytes * 1000 / (2L * channels * sampleRate);
    }

    @Override
    public void close() throws IOException {
        try {
            file.seek(0);
            file.write(header(dataBytes));
        } finally {
            file.close();
        }
    }

    private byte[] header(long data) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + data)).put(new byte[]{'W', 'A', 'V', 'E'});
        h.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) channels);
        h.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) data);
        return h.array();
    }

    /** Длительность готового WAV по размеру файла (формат — как пишет этот класс), мс. */
    static long durationMs(File f, int sampleRate, int channels) {
        return Math.max(0, f.length() - 44) * 1000 / (2L * channels * sampleRate);
    }
}
