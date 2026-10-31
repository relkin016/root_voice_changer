// Проверка DSP на хосте: сигнал-«голос» (пила 140 Гц) → пресеты → проверка конечности,
// уровня и сдвига основного тона (автокорреляция). Также пишет WAV для прослушивания.
// gcc -O2 -I app/src/main/cpp tools/dsp_test.c app/src/main/cpp/rvc_dsp.c -lm -o /tmp/dsp_test && /tmp/dsp_test [outdir]
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "rvc_dsp.h"

#define SR 48000
#define N (SR * 2)

static float pitch_hz(const float *x, int n) {
    // Максимум автокорреляции после её первого ухода в минус (иначе выигрывает лаг ~0).
    int best = 0, dipped = 0;
    double bestv = -1e9;
    for (int lag = 1; lag < SR / 50; lag++) {
        double s = 0;
        for (int i = 0; i < n - lag; i++) s += x[i] * x[i + lag];
        if (s < 0) dipped = 1;
        if (dipped && s > bestv) { bestv = s; best = lag; }
    }
    if (!best) return 0;
    return (float)SR / best;
}

static void write_wav(const char *path, const float *x, int n) {
    FILE *f = fopen(path, "wb");
    if (!f) return;
    int data = n * 2, v;
    fwrite("RIFF", 1, 4, f); v = 36 + data; fwrite(&v, 4, 1, f);
    fwrite("WAVEfmt ", 1, 8, f); v = 16; fwrite(&v, 4, 1, f);
    short s = 1; fwrite(&s, 2, 1, f); fwrite(&s, 2, 1, f);
    v = SR; fwrite(&v, 4, 1, f); v = SR * 2; fwrite(&v, 4, 1, f);
    s = 2; fwrite(&s, 2, 1, f); s = 16; fwrite(&s, 2, 1, f);
    fwrite("data", 1, 4, f); fwrite(&data, 4, 1, f);
    for (int i = 0; i < n; i++) { short q = (short)(x[i] * 32767); fwrite(&q, 2, 1, f); }
    fclose(f);
}

int main(int argc, char **argv) {
    static float in[N], out[N];
    double ph = 0;
    for (int i = 0; i < N; i++) {
        double f0 = 140 * (1 + 0.02 * sin(2 * M_PI * 3 * i / SR));
        ph += f0 / SR; if (ph >= 1) ph -= 1;
        double env = 0.5 + 0.5 * sin(2 * M_PI * 1.5 * i / SR);
        in[i] = (float)((2 * ph - 1) * 0.25 * env);
    }
    struct { const char *name, *params; float expect_ratio; } cases[] = {
        { "off", "1 0 0 0 0 0 0 0", 1 },
        { "neutral", "1 1 0 0 0 0 0 0", 1 },
        { "up12", "1 1 12 0 0 0 0 0", 2 },
        { "down5", "1 1 -5 0 0 0 0 0", 0.7492f },
        { "rough", "1 1 0 1 0 0 0 0", 0.7716f },
        { "soft", "1 1 0 -1 0 0 0 0", 1.2968f },
        { "robot", "1 1 0 0 0.8 0 0 0", 0 },
        { "echo", "1 1 0 0 0 1 0 0", 0 },
        { "radio", "1 1 0 0 0 0 1 6", 0 },
        { "monster", "1 1 -5 0.8 0 0.15 0 0", 0 },
    };
    int fail = 0;
    rvc_params_t bad;
    if (rvc_parse_params("2 1 0 0 0 0 0 0", &bad) == 0 || rvc_parse_params("1 1 x", &bad) == 0) {
        puts("FAIL parse should reject"); fail = 1;
    }
    float base = pitch_hz(in + SR / 2, 4096);
    for (size_t c = 0; c < sizeof cases / sizeof *cases; c++) {
        rvc_dsp_t *d = rvc_create();
        rvc_configure(d, SR, 1);
        rvc_params_t p;
        if (rvc_parse_params(cases[c].params, &p)) { printf("FAIL parse %s\n", cases[c].name); fail = 1; continue; }
        rvc_set_params(d, &p);
        memcpy(out, in, sizeof in);
        for (int i = 0; i < N; i += 480) rvc_process(d, out + i, 480);
        double peak = 0, rms = 0;
        int finite = 1;
        for (int i = 0; i < N; i++) {
            if (!isfinite(out[i])) finite = 0;
            peak = fmax(peak, fabs(out[i])); rms += out[i] * out[i];
        }
        rms = sqrt(rms / N);
        float hz = pitch_hz(out + SR / 2, 4096);
        int ok = finite && peak <= 1.0 && rms > 0.01;
        if (cases[c].expect_ratio > 0) ok = ok && fabsf(hz / base / cases[c].expect_ratio - 1) < 0.06f;
        printf("%-8s %s peak=%.3f rms=%.3f f0=%.1f Hz (base %.1f)\n", cases[c].name, ok ? "ok  " : "FAIL", peak, rms, hz, base);
        fail |= !ok;
        if (argc > 1) { char path[512]; snprintf(path, sizeof path, "%s/%s.wav", argv[1], cases[c].name); write_wav(path, out, N); }
        rvc_destroy(d);
    }
    return fail;
}
