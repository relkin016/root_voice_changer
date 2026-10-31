#include "rvc_dsp.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define PBUF 8192           // линия задержки питч-шифтера (степень двойки)
#define EBUF 32768          // линия задержки эха (степень двойки)
#define TWO_PI 6.28318530717958647692f
#define ANTI_DENORMAL 1e-18f

typedef struct { float b0, b1, b2, a1, a2; } bq_coef;
typedef struct { float z1, z2; } bq_state;

typedef struct {
    float pbuf[PBUF];
    float ebuf[EBUF];
    bq_state low_shelf, high_shelf, hp, lp, radio_hp, radio_lp;
    float env;
} chan_t;

struct rvc_dsp {
    int sr, ch;
    rvc_params_t p;

    // питч-шифтер: две считывающие головки, разнесённые на пол-окна, с окнами Ханна
    float ratio, phase, phase_inc, win;
    int pw;

    // грубость
    int use_low_shelf, use_high_shelf, use_hp, use_lp;
    bq_coef low_shelf, high_shelf, hp, lp;
    float drive, dist_mix, growl_depth, growl_ph, growl_inc;

    // робот, рация, эхо, выход
    float robot_ph, robot_inc;
    bq_coef radio_hp, radio_lp;
    int ew, echo_len;
    float echo_mix, echo_fb;
    float gain, env_rel;
    uint32_t noise;

    chan_t c[RVC_MAX_CH];
};

static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

static bq_coef bq_norm(float b0, float b1, float b2, float a0, float a1, float a2) {
    bq_coef k = { b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0 };
    return k;
}

static float bq_w0(int sr, float f) { return TWO_PI * clampf(f, 10.f, 0.45f * sr) / sr; }

static bq_coef bq_lowpass(int sr, float f, float q) {
    float w = bq_w0(sr, f), c = cosf(w), a = sinf(w) / (2 * q);
    return bq_norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + a, -2 * c, 1 - a);
}

static bq_coef bq_highpass(int sr, float f, float q) {
    float w = bq_w0(sr, f), c = cosf(w), a = sinf(w) / (2 * q);
    return bq_norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a);
}

static bq_coef bq_shelf(int sr, float f, float db, int high) {
    float A = powf(10.f, db / 40.f), w = bq_w0(sr, f), c = cosf(w);
    float a = sinf(w) / 2 * sqrtf(2.f), s = 2 * sqrtf(A) * a;
    if (high)
        return bq_norm(A * ((A + 1) + (A - 1) * c + s), -2 * A * ((A - 1) + (A + 1) * c),
                       A * ((A + 1) + (A - 1) * c - s), (A + 1) - (A - 1) * c + s,
                       2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - s);
    return bq_norm(A * ((A + 1) - (A - 1) * c + s), 2 * A * ((A - 1) - (A + 1) * c),
                   A * ((A + 1) - (A - 1) * c - s), (A + 1) + (A - 1) * c + s,
                   -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - s);
}

static inline float bq_run(const bq_coef *k, bq_state *s, float x) {
    float y = k->b0 * x + s->z1;
    s->z1 = k->b1 * x - k->a1 * y + s->z2 + ANTI_DENORMAL;
    s->z2 = k->b2 * x - k->a2 * y;
    return y;
}

rvc_dsp_t *rvc_create(void) {
    rvc_dsp_t *d = calloc(1, sizeof(*d));
    if (!d) return NULL;
    rvc_params_t p = { 1, 0, 0, 0, 0, 0, 0 };
    d->p = p;
    d->noise = 0x12345678u;
    rvc_configure(d, 48000, 1);
    return d;
}

void rvc_destroy(rvc_dsp_t *d) { free(d); }

void rvc_reset(rvc_dsp_t *d) {
    memset(d->c, 0, sizeof(d->c));
    d->pw = d->ew = 0;
    d->phase = d->growl_ph = d->robot_ph = 0;
}

static void update(rvc_dsp_t *d) {
    const rvc_params_t *p = &d->p;
    int sr = d->sr;
    float r = p->rough;

    // Грубость тоже двигает высоту: грубее — ниже, мягче — выше.
    float semis = clampf(p->pitch - 4.5f * r, -24.f, 24.f);
    d->ratio = powf(2.f, semis / 12.f);
    d->win = clampf(0.035f * sr, 64.f, PBUF - 4.f);
    d->phase_inc = (1.f - d->ratio) / d->win;

    d->use_low_shelf = d->use_lp = d->use_high_shelf = d->use_hp = 0;
    d->drive = 1.f;
    d->dist_mix = d->growl_depth = 0;
    if (r > 0.01f) {
        d->use_low_shelf = 1;
        d->low_shelf = bq_shelf(sr, 220.f, 7.f * r, 0);
        d->use_lp = 1;
        d->lp = bq_lowpass(sr, 7500.f - 4200.f * r, 0.707f);
        d->drive = 3.f + 17.f * r;
        d->dist_mix = 0.65f * r;
        d->growl_depth = 0.45f * r;
    } else if (r < -0.01f) {
        float s = -r;
        d->use_hp = 1;
        d->hp = bq_highpass(sr, 90.f + 160.f * s, 0.707f);
        d->use_high_shelf = 1;
        d->high_shelf = bq_shelf(sr, 3000.f, 5.f * s, 1);
    }
    d->growl_inc = TWO_PI * 52.f / sr;

    d->robot_inc = TWO_PI * (45.f + 40.f * p->robot) / sr;
    d->radio_hp = bq_highpass(sr, 400.f, 0.9f);
    d->radio_lp = bq_lowpass(sr, 2800.f, 0.9f);

    d->echo_len = (int)clampf(0.22f * sr, 1.f, EBUF - 1.f);
    d->echo_mix = 0.6f * p->echo;
    d->echo_fb = 0.55f * p->echo;

    d->gain = powf(10.f, clampf(p->gain_db, -24.f, 24.f) / 20.f);
    d->env_rel = expf(-1.f / (0.05f * sr));
}

void rvc_configure(rvc_dsp_t *d, int sample_rate, int channels) {
    d->sr = sample_rate >= 8000 && sample_rate <= 192000 ? sample_rate : 48000;
    d->ch = channels >= 1 ? channels : 1;
    rvc_reset(d);
    update(d);
}

void rvc_set_params(rvc_dsp_t *d, const rvc_params_t *p) {
    rvc_params_t q = *p;
    q.enabled = q.enabled ? 1 : 0;
    q.pitch = clampf(q.pitch, -12.f, 12.f);
    q.rough = clampf(q.rough, -1.f, 1.f);
    q.robot = clampf(q.robot, 0.f, 1.f);
    q.echo = clampf(q.echo, 0.f, 1.f);
    q.radio = q.radio ? 1 : 0;
    q.gain_db = clampf(q.gain_db, -12.f, 12.f);
    // Выключенное эхо не должно «доигрывать» хвост при следующем включении.
    if (q.echo <= 0.f && d->p.echo > 0.f)
        for (int c = 0; c < RVC_MAX_CH; c++) memset(d->c[c].ebuf, 0, sizeof(d->c[c].ebuf));
    d->p = q;
    update(d);
}

const rvc_params_t *rvc_get_params(const rvc_dsp_t *d) { return &d->p; }

int rvc_parse_params(const char *s, rvc_params_t *out) {
    float v[8];
    char *end;
    for (int i = 0; i < 8; i++) {
        v[i] = strtof(s, &end);
        if (end == s || !isfinite(v[i])) return -1;
        s = end;
    }
    if ((int)v[0] != 1) return -1;
    rvc_params_t p = { v[1] != 0, v[2], v[3], v[4], v[5], v[6] != 0, v[7] };
    *out = p;
    return 0;
}

static inline float frand(uint32_t *s) {
    *s = *s * 1664525u + 1013904223u;
    return (float)(int32_t)*s * (1.f / 2147483648.f);
}

static inline float read_frac(const float *buf, float pos) {
    int i = (int)floorf(pos);
    float f = pos - i;
    float a = buf[i & (PBUF - 1)], b = buf[(i + 1) & (PBUF - 1)];
    return a + (b - a) * f;
}

void rvc_process(rvc_dsp_t *d, float *buf, int frames) {
    const rvc_params_t *p = &d->p;
    if (!p->enabled) return;

    int nch = d->ch < RVC_MAX_CH ? d->ch : RVC_MAX_CH;
    int shift = fabsf(d->ratio - 1.f) > 1e-4f;
    int rough = d->dist_mix > 0;

    for (int n = 0; n < frames; n++) {
        // Общие для всех каналов значения на этот кадр.
        float g1 = 0, g2 = 0, d1 = 0, d2 = 0;
        if (shift) {
            float ph2 = d->phase + 0.5f;
            if (ph2 >= 1.f) ph2 -= 1.f;
            d1 = 1.f + d->phase * d->win;
            d2 = 1.f + ph2 * d->win;
            g1 = 0.5f - 0.5f * cosf(TWO_PI * d->phase);
            g2 = 1.f - g1;
        }
        float growl = 1.f - d->growl_depth * (0.5f + 0.5f * sinf(d->growl_ph));
        float robot = sinf(d->robot_ph);

        for (int c = 0; c < nch; c++) {
            chan_t *s = &d->c[c];
            float *io = &buf[n * d->ch + c];
            float x = *io;

            if (shift) {
                s->pbuf[d->pw] = x;
                x = g1 * read_frac(s->pbuf, (float)d->pw - d1) + g2 * read_frac(s->pbuf, (float)d->pw - d2);
            }

            float ax = fabsf(x);
            s->env = ax > s->env ? ax : s->env * d->env_rel;

            if (d->use_hp) x = bq_run(&d->hp, &s->hp, x);
            if (d->use_high_shelf) x = bq_run(&d->high_shelf, &s->high_shelf, x);
            if (d->use_low_shelf) x = bq_run(&d->low_shelf, &s->low_shelf, x);
            if (rough) {
                // Сатурация только на голосе: шумовой фон (< ~-46 дБ) не раскачивается.
                float gate = clampf((s->env - 0.005f) / 0.02f, 0.f, 1.f);
                float wet = tanhf(d->drive * x) * 0.3f;
                x += d->dist_mix * gate * (wet - x);
                x *= growl;
            }
            if (d->use_lp) x = bq_run(&d->lp, &s->lp, x);

            if (p->robot > 0) x = x * (1.f - p->robot) + p->robot * 1.6f * x * robot;

            if (p->radio) {
                x = bq_run(&d->radio_hp, &s->radio_hp, x);
                x = bq_run(&d->radio_lp, &s->radio_lp, x);
                x = tanhf(4.f * x) * 0.35f + 0.004f * frand(&d->noise);
            }

            if (d->echo_mix > 0) {
                float e = s->ebuf[(d->ew - d->echo_len) & (EBUF - 1)];
                s->ebuf[d->ew] = x + d->echo_fb * e + ANTI_DENORMAL;
                x += d->echo_mix * e;
            }

            x *= d->gain;
            // Мягкий лимитер выше 0.8, чтобы не было цифрового клиппинга.
            float m = fabsf(x);
            if (m > 0.8f) x = copysignf(0.8f + 0.2f * tanhf((m - 0.8f) / 0.2f), x);
            *io = x;
        }

        if (shift) {
            d->pw = (d->pw + 1) & (PBUF - 1);
            d->phase += d->phase_inc;
            if (d->phase >= 1.f) d->phase -= 1.f;
            else if (d->phase < 0.f) d->phase += 1.f;
        }
        d->ew = (d->ew + 1) & (EBUF - 1);
        d->growl_ph += d->growl_inc;
        if (d->growl_ph > TWO_PI) d->growl_ph -= TWO_PI;
        d->robot_ph += d->robot_inc;
        if (d->robot_ph > TWO_PI) d->robot_ph -= TWO_PI;
    }
}
