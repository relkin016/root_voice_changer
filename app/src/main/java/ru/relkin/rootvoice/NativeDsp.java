package ru.relkin.rootvoice;

/** JNI к тому же DSP, что и в системном эффекте (librvcjni.so). */
final class NativeDsp {
    static {
        System.loadLibrary("rvcjni");
    }

    private NativeDsp() {
    }

    static native long nativeCreate(int sampleRate, int channels);

    static native void nativeDestroy(long handle);

    static native boolean nativeSetParams(long handle, String params);

    static native void nativeProcess(long handle, short[] pcm, int frames, int channels);
}
