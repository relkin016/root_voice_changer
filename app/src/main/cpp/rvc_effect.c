// librootvoice.so — библиотека аудиоэффекта Android (effect HAL, API 3.0) типа pre-processing.
// Модуль Magisk/KernelSU прописывает её в audio_effects.xml для источников mic,
// voice_communication, camcorder и voice_recognition, поэтому её применяет система
// к записи с микрофона в любом приложении. Параметры читаются из свойства debug.rvc.params.
//
// Заголовки AOSP (hardware/audio_effect.h, system/audio_effect.h) в NDK нет, поэтому
// нужные структуры повторены ниже с тем же двоичным раскладом.

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <time.h>

#include "rvc_dsp.h"

#define EXPORT __attribute__((visibility("default")))

// ---- system/audio_effect.h -------------------------------------------------

typedef struct {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t node[6];
} effect_uuid_t;

typedef struct {
    effect_uuid_t type;
    effect_uuid_t uuid;
    uint32_t apiVersion;
    uint32_t flags;
    uint16_t cpuLoad;
    uint16_t memoryUsage;
    char name[64];
    char implementor[64];
} effect_descriptor_t;

typedef struct {
    size_t frameCount;
    union {
        void *raw;
        float *f32;
        int32_t *s32;
        int16_t *s16;
        uint8_t *u8;
    };
} audio_buffer_t;

typedef struct {
    int32_t (*getBuffer)(void *cookie, audio_buffer_t *buffer);
    int32_t (*releaseBuffer)(void *cookie, audio_buffer_t *buffer);
    void *cookie;
} buffer_provider_t;

typedef struct {
    audio_buffer_t buffer;
    uint32_t samplingRate;
    uint32_t channels;
    buffer_provider_t bufferProvider;
    uint8_t format;
    uint8_t accessMode;
    uint16_t mask;
} buffer_config_t;

typedef struct {
    buffer_config_t inputCfg;
    buffer_config_t outputCfg;
} effect_config_t;

typedef struct {
    int32_t status;
    uint32_t psize;
    uint32_t vsize;
    char data[];
} effect_param_t;

_Static_assert(sizeof(effect_descriptor_t) == 172, "effect_descriptor_t ABI");
_Static_assert(sizeof(buffer_config_t) == (sizeof(void *) == 8 ? 56 : 32), "buffer_config_t ABI");

enum {
    EFFECT_CMD_INIT = 0,
    EFFECT_CMD_SET_CONFIG = 1,
    EFFECT_CMD_RESET = 2,
    EFFECT_CMD_ENABLE = 3,
    EFFECT_CMD_DISABLE = 4,
    EFFECT_CMD_SET_PARAM = 5,
    EFFECT_CMD_SET_PARAM_DEFERRED = 6,
    EFFECT_CMD_SET_PARAM_COMMIT = 7,
    EFFECT_CMD_GET_PARAM = 8,
    EFFECT_CMD_SET_DEVICE = 9,
    EFFECT_CMD_SET_VOLUME = 10,
    EFFECT_CMD_SET_AUDIO_MODE = 11,
    EFFECT_CMD_SET_CONFIG_REVERSE = 12,
    EFFECT_CMD_SET_INPUT_DEVICE = 13,
    EFFECT_CMD_GET_CONFIG = 14,
    EFFECT_CMD_GET_CONFIG_REVERSE = 15,
    EFFECT_CMD_SET_AUDIO_SOURCE = 19,
};

#define EFFECT_FLAG_TYPE_PRE_PROC 3u
#define EFFECT_CONTROL_API_VERSION 0x00020000u  // 2.0
#define EFFECT_LIBRARY_API_VERSION 0x00030000u  // 3.0
#define AUDIO_EFFECT_LIBRARY_TAG (('A' << 24) | ('E' << 16) | ('L' << 8) | 'T')

#define AUDIO_FORMAT_DEFAULT 0x0
#define AUDIO_FORMAT_PCM_16_BIT 0x1
#define AUDIO_FORMAT_PCM_FLOAT 0x5
#define EFFECT_BUFFER_ACCESS_ACCUMULATE 2
#define AUDIO_CHANNEL_REPRESENTATION_INDEX 0x80000000u

// ---- hardware/audio_effect.h -----------------------------------------------

struct effect_interface_s;
typedef struct effect_interface_s **effect_handle_t;

struct effect_interface_s {
    int32_t (*process)(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out);
    int32_t (*command)(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize, void *pCmdData,
                       uint32_t *replySize, void *pReplyData);
    int32_t (*get_descriptor)(effect_handle_t self, effect_descriptor_t *pDescriptor);
    int32_t (*process_reverse)(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out);
};

typedef struct {
    uint32_t tag;
    uint32_t version;
    const char *name;
    const char *implementor;
    int32_t (*create_effect)(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId,
                             effect_handle_t *pHandle);
    int32_t (*release_effect)(effect_handle_t handle);
    int32_t (*get_descriptor)(const effect_uuid_t *uuid, effect_descriptor_t *pDescriptor);
} audio_effect_library_t;

// ---- реализация ------------------------------------------------------------

// type 08255bd4-0dc9-4343-933a-51ce56e4761e, uuid f6f24dc9-fd4d-4a3c-845b-581ddf0637b6
// (uuid продублирован в ModuleInstaller.EFFECT_UUID).
static const effect_descriptor_t kDescriptor = {
    .type = { 0x08255bd4, 0x0dc9, 0x4343, 0x933a, { 0x51, 0xce, 0x56, 0xe4, 0x76, 0x1e } },
    .uuid = { 0xf6f24dc9, 0xfd4d, 0x4a3c, 0x845b, { 0x58, 0x1d, 0xdf, 0x06, 0x37, 0xb6 } },
    .apiVersion = EFFECT_CONTROL_API_VERSION,
    .flags = EFFECT_FLAG_TYPE_PRE_PROC,
    .cpuLoad = 10,
    .memoryUsage = 1,
    .name = "Root Voice Changer",
    .implementor = "relkin",
};

#define CHUNK 256
#define PARAM_POLL_NS 150000000LL  // как часто перечитывать свойство с параметрами

typedef struct {
    const struct effect_interface_s *itf;  // должно быть первым полем
    rvc_dsp_t *dsp;
    effect_config_t config;
    int channels;
    int active;
    int64_t next_poll_ns;
    uint32_t prop_serial;
    const prop_info *prop;
    float scratch[CHUNK * 8];
} rvc_effect_t;

static int uuid_eq(const effect_uuid_t *a, const effect_uuid_t *b) {
    return memcmp(a, b, sizeof(*a)) == 0;
}

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int channel_count(uint32_t mask) {
    if (mask & AUDIO_CHANNEL_REPRESENTATION_INDEX) mask &= 0x3fffffffu;
    int n = __builtin_popcount(mask);
    return n > 0 ? n : 1;
}

static void on_prop(void *cookie, const char *name, const char *value, uint32_t serial) {
    (void)name;
    rvc_effect_t *e = cookie;
    if (serial == e->prop_serial) return;
    e->prop_serial = serial;
    rvc_params_t p;
    if (value && rvc_parse_params(value, &p) == 0) rvc_set_params(e->dsp, &p);
}

static void poll_params(rvc_effect_t *e) {
    int64_t t = now_ns();
    if (t < e->next_poll_ns) return;
    e->next_poll_ns = t + PARAM_POLL_NS;
    if (!e->prop) e->prop = __system_property_find(RVC_PARAMS_PROP);
    if (e->prop) __system_property_read_callback(e->prop, on_prop, e);
}

static int32_t fx_process(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out) {
    rvc_effect_t *e = (rvc_effect_t *)self;
    if (!e || !in || !out || !in->raw || !out->raw) return -EINVAL;
    if (!e->active) return -ENODATA;
    poll_params(e);

    int ch = e->channels;
    int fmt = e->config.inputCfg.format;
    int accumulate = e->config.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE;
    size_t frames = in->frameCount < out->frameCount ? in->frameCount : out->frameCount;
    int chunk = CHUNK * 8 / ch;

    for (size_t done = 0; done < frames;) {
        int n = (int)(frames - done < (size_t)chunk ? frames - done : (size_t)chunk);
        size_t off = done * ch, cnt = (size_t)n * ch;
        float *s = e->scratch;
        if (fmt == AUDIO_FORMAT_PCM_FLOAT) {
            memcpy(s, in->f32 + off, cnt * sizeof(float));
        } else {
            for (size_t i = 0; i < cnt; i++) s[i] = in->s16[off + i] * (1.f / 32768.f);
        }
        rvc_process(e->dsp, s, n);
        if (fmt == AUDIO_FORMAT_PCM_FLOAT) {
            for (size_t i = 0; i < cnt; i++)
                out->f32[off + i] = accumulate ? out->f32[off + i] + s[i] : s[i];
        } else {
            for (size_t i = 0; i < cnt; i++) {
                float v = s[i] * 32768.f + (accumulate ? out->s16[off + i] : 0);
                out->s16[off + i] = (int16_t)(v > 32767.f ? 32767 : v < -32768.f ? -32768 : v);
            }
        }
        done += n;
    }
    return 0;
}

static int set_config(rvc_effect_t *e, const effect_config_t *cfg) {
    const buffer_config_t *ic = &cfg->inputCfg;
    int fmt = ic->format == AUDIO_FORMAT_DEFAULT ? AUDIO_FORMAT_PCM_16_BIT : ic->format;
    if (fmt != AUDIO_FORMAT_PCM_16_BIT && fmt != AUDIO_FORMAT_PCM_FLOAT) return -EINVAL;
    if (ic->samplingRate && cfg->outputCfg.samplingRate && ic->samplingRate != cfg->outputCfg.samplingRate)
        return -EINVAL;
    int ch = channel_count(ic->channels);
    if (ch > 8 || channel_count(cfg->outputCfg.channels) != ch) return -EINVAL;
    e->config = *cfg;
    e->config.inputCfg.format = (uint8_t)fmt;
    e->channels = ch;
    rvc_configure(e->dsp, ic->samplingRate ? (int)ic->samplingRate : 48000, ch);
    return 0;
}

static int32_t fx_command(effect_handle_t self, uint32_t cmd, uint32_t cmdSize, void *cmdData,
                          uint32_t *replySize, void *replyData) {
    rvc_effect_t *e = (rvc_effect_t *)self;
    if (!e) return -EINVAL;
    int32_t *reply = (int32_t *)replyData;
    int want_status = replySize && replyData && *replySize >= sizeof(int32_t);

    switch (cmd) {
    case EFFECT_CMD_INIT:
        if (!want_status) return -EINVAL;
        rvc_reset(e->dsp);
        *reply = 0;
        return 0;
    case EFFECT_CMD_SET_CONFIG:
        if (!cmdData || cmdSize < sizeof(effect_config_t) || !want_status) return -EINVAL;
        *reply = set_config(e, (const effect_config_t *)cmdData);
        return 0;
    case EFFECT_CMD_GET_CONFIG:
        if (!replyData || !replySize || *replySize < sizeof(effect_config_t)) return -EINVAL;
        memcpy(replyData, &e->config, sizeof(effect_config_t));
        return 0;
    case EFFECT_CMD_RESET:
        rvc_reset(e->dsp);
        return 0;
    case EFFECT_CMD_ENABLE:
    case EFFECT_CMD_DISABLE:
        if (!want_status) return -EINVAL;
        e->active = cmd == EFFECT_CMD_ENABLE;
        if (e->active) {
            e->next_poll_ns = 0;
            e->prop_serial = 0;  // перечитать параметры сразу
        }
        *reply = 0;
        return 0;
    case EFFECT_CMD_SET_PARAM:
        if (want_status) *reply = -EINVAL;
        return 0;
    case EFFECT_CMD_GET_PARAM:
        if (cmdData && replyData && replySize && cmdSize >= sizeof(effect_param_t) &&
            *replySize >= sizeof(effect_param_t)) {
            memcpy(replyData, cmdData, sizeof(effect_param_t));
            ((effect_param_t *)replyData)->status = -EINVAL;
            ((effect_param_t *)replyData)->vsize = 0;
            *replySize = sizeof(effect_param_t);
            return 0;
        }
        return -EINVAL;
    case EFFECT_CMD_SET_PARAM_DEFERRED:
    case EFFECT_CMD_SET_PARAM_COMMIT:
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_INPUT_DEVICE:
    case EFFECT_CMD_SET_AUDIO_MODE:
    case EFFECT_CMD_SET_AUDIO_SOURCE:
    case EFFECT_CMD_SET_VOLUME:
        if (want_status) *reply = 0;
        return 0;
    default:
        return -EINVAL;
    }
}

static int32_t fx_get_descriptor(effect_handle_t self, effect_descriptor_t *desc) {
    if (!self || !desc) return -EINVAL;
    *desc = kDescriptor;
    return 0;
}

static int32_t fx_process_reverse(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out) {
    (void)self; (void)in; (void)out;
    return -ENOSYS;
}

static const struct effect_interface_s kInterface = {
    fx_process, fx_command, fx_get_descriptor, fx_process_reverse,
};

static int32_t lib_create(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId, effect_handle_t *pHandle) {
    (void)sessionId; (void)ioId;
    if (!uuid || !pHandle || !uuid_eq(uuid, &kDescriptor.uuid)) return -EINVAL;
    rvc_effect_t *e = calloc(1, sizeof(*e));
    if (!e) return -ENOMEM;
    e->dsp = rvc_create();
    if (!e->dsp) {
        free(e);
        return -ENOMEM;
    }
    e->itf = &kInterface;
    e->channels = 1;
    e->config.inputCfg.samplingRate = e->config.outputCfg.samplingRate = 48000;
    e->config.inputCfg.channels = 0x10;   // AUDIO_CHANNEL_IN_MONO
    e->config.outputCfg.channels = 0x10;
    e->config.inputCfg.format = e->config.outputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    *pHandle = (effect_handle_t)e;
    return 0;
}

static int32_t lib_release(effect_handle_t handle) {
    rvc_effect_t *e = (rvc_effect_t *)handle;
    if (!e) return -EINVAL;
    rvc_destroy(e->dsp);
    free(e);
    return 0;
}

static int32_t lib_get_descriptor(const effect_uuid_t *uuid, effect_descriptor_t *desc) {
    if (!uuid || !desc || !uuid_eq(uuid, &kDescriptor.uuid)) return -EINVAL;
    *desc = kDescriptor;
    return 0;
}

EXPORT audio_effect_library_t AELI = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_LIBRARY_API_VERSION,
    .name = "Root Voice Changer Library",
    .implementor = "relkin",
    .create_effect = lib_create,
    .release_effect = lib_release,
    .get_descriptor = lib_get_descriptor,
};
