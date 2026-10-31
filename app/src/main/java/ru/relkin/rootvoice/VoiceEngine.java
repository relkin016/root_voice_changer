package ru.relkin.rootvoice;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;

/**
 * Микрофон → (DSP) → наушники и/или WAV-файл.
 * Если системный эффект загружен, запись с MIC уже обработана им — так проверяется сам модуль;
 * иначе звук обрабатывается внутри приложения тем же DSP.
 */
final class VoiceEngine {
    interface Listener {
        /** Поток остановился; error == null — штатно. Вызывается из фонового потока. */
        void onStopped(String error);
    }

    static final int SR = 48000;
    private static final int FRAMES = 480;  // 10 мс

    private final Object writerLock = new Object();
    private volatile boolean running;
    private volatile boolean monitor;
    private volatile String params;
    private WavWriter writer;   // под writerLock
    private File recordFile;    // под writerLock
    private Thread thread;

    boolean isRunning() {
        return running;
    }

    boolean isMonitoring() {
        return monitor;
    }

    void setMonitor(boolean on) {
        monitor = on;
    }

    void setParams(String p) {
        params = p;
    }

    boolean isRecording() {
        synchronized (writerLock) {
            return writer != null;
        }
    }

    long recordedMs() {
        synchronized (writerLock) {
            return writer != null ? writer.durationMs() : 0;
        }
    }

    void startRecording(File f) throws IOException {
        synchronized (writerLock) {
            if (writer != null) return;
            writer = new WavWriter(f, SR, 1);
            recordFile = f;
        }
    }

    /** Закрывает файл записи и возвращает его (null, если запись не шла). */
    File stopRecording() {
        synchronized (writerLock) {
            if (writer == null) return null;
            File f = recordFile;
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
            recordFile = null;
            return f;
        }
    }

    void start(boolean processInApp, String initialParams, Listener listener) {
        if (running) return;
        params = initialParams;
        running = true;
        thread = new Thread(() -> listener.onStopped(loop(processInApp)), "rvc-engine");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    /** Останавливает поток; незавершённая запись сохраняется. */
    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressLint("MissingPermission")  // разрешение проверяет MainActivity перед стартом
    private String loop(boolean processInApp) {
        AudioRecord rec = null;
        AudioTrack track = null;
        long dsp = 0;
        try {
            int minIn = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minOut = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minIn, FRAMES * 2 * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return "Не удалось открыть микрофон";
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(minOut, FRAMES * 2 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            if (processInApp) dsp = NativeDsp.nativeCreate(SR, 1);

            short[] buf = new short[FRAMES];
            String applied = null;
            boolean playing = false;
            rec.startRecording();
            while (running) {
                int n = rec.read(buf, 0, FRAMES);
                if (n < 0) return "Ошибка чтения микрофона: " + n;
                if (dsp != 0) {
                    String p = params;
                    if (p != null && !p.equals(applied)) {
                        NativeDsp.nativeSetParams(dsp, p);
                        applied = p;
                    }
                    NativeDsp.nativeProcess(dsp, buf, n, 1);
                }
                if (monitor) {
                    if (!playing) {
                        track.play();
                        playing = true;
                    }
                    track.write(buf, 0, n);
                } else if (playing) {
                    track.pause();
                    track.flush();
                    playing = false;
                }
                synchronized (writerLock) {
                    if (writer != null) {
                        try {
                            writer.write(buf, n);
                        } catch (IOException e) {
                            return "Не удалось записать файл: " + e.getMessage();
                        }
                    }
                }
            }
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            running = false;
            if (rec != null) {
                try {
                    rec.stop();
                } catch (IllegalStateException ignored) {
                }
                rec.release();
            }
            if (track != null) {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                }
                track.release();
            }
            if (dsp != 0) NativeDsp.nativeDestroy(dsp);
        }
    }
}
