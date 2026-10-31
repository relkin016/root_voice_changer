// librvcjni.so — тот же DSP для прослушки внутри приложения (когда системный модуль не активен).
#include <jni.h>
#include <stdint.h>

#include "rvc_dsp.h"

#define FN(name) Java_ru_relkin_rootvoice_NativeDsp_##name
#define CHUNK 512

JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv *env, jclass cls, jint sampleRate, jint channels) {
    (void)env; (void)cls;
    rvc_dsp_t *d = rvc_create();
    if (d) rvc_configure(d, sampleRate, channels);
    return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL FN(nativeDestroy)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    rvc_destroy((rvc_dsp_t *)(intptr_t)h);
}

JNIEXPORT jboolean JNICALL FN(nativeSetParams)(JNIEnv *env, jclass cls, jlong h, jstring params) {
    (void)cls;
    const char *s = (*env)->GetStringUTFChars(env, params, NULL);
    if (!s) return JNI_FALSE;
    rvc_params_t p;
    int ok = rvc_parse_params(s, &p) == 0;
    (*env)->ReleaseStringUTFChars(env, params, s);
    if (ok) rvc_set_params((rvc_dsp_t *)(intptr_t)h, &p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Обрабатывает frames кадров 16-битного PCM в массиве на месте.
JNIEXPORT void JNICALL FN(nativeProcess)(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint frames, jint channels) {
    (void)cls;
    rvc_dsp_t *d = (rvc_dsp_t *)(intptr_t)h;
    jshort *buf = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (!buf) return;
    float tmp[CHUNK * RVC_MAX_CH];
    int step = CHUNK * RVC_MAX_CH / channels;
    for (int done = 0; done < frames; done += step) {
        int n = frames - done < step ? frames - done : step;
        jshort *p = buf + done * channels;
        for (int i = 0; i < n * channels; i++) tmp[i] = p[i] * (1.f / 32768.f);
        rvc_process(d, tmp, n);
        for (int i = 0; i < n * channels; i++) {
            float v = tmp[i] * 32768.f;
            p[i] = (jshort)(v > 32767.f ? 32767 : v < -32768.f ? -32768 : v);
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, buf, 0);
}
