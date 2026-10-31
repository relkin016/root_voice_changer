package ru.relkin.rootvoice;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/**
 * Прослушка себя: микрофон → (DSP) → наушники.
 * Если системный эффект загружен, запись с MIC уже обработана им — так проверяется сам модуль;
 * иначе звук обрабатывается внутри приложения тем же DSP.
 */
final class Preview {
    interface Listener {
        void onStopped(String error);
    }

    private static final int SR = 48000;
    private static final int FRAMES = 480;  // 10 мс

    private volatile boolean running;
    private volatile String params;
    private Thread thread;

    boolean isRunning() {
        return running;
    }

    void setParams(String p) {
        params = p;
    }

    void start(boolean processInApp, String initialParams, Listener listener) {
        if (running) return;
        params = initialParams;
        running = true;
        thread = new Thread(() -> listener.onStopped(loop(processInApp)), "rvc-preview");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

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
            rec.startRecording();
            track.play();
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
                track.write(buf, 0, n);
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
