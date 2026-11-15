// Обёртка над аудио-HAL (audio.primary.<платформа>.so, legacy HAL API 3.0+).
//
// Зачем: эффекты типа pre-processing из audio_effects.xml Android на записи не обрабатывает сам —
// он передаёт их драйверу (add_audio_effect), а драйверы Qualcomm (CAF) понимают только свои
// AEC/NS и остальные эффекты молча игнорируют. Поэтому модуль подкладывает эту библиотеку под
// именем оригинального драйвера, а оригинал — рядом как <имя>.rvcorig.so. Обёртка загружает
// оригинал, отдаёт системе его HMI с подменённым open() и на каждом открытом входном потоке
// подменяет read(): всё, что драйвер прочитал с микрофона, проходит через DSP.
//
// Структуры повторяют hardware/hardware.h и hardware/audio.h AOSP (двоичный расклад стабилен
// с Android 5). Зависимости — только libc/libm/libdl/liblog (LL-NDK): библиотеку грузит вендорный процесс HAL.

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <time.h>

#include "rvc_dsp.h"

#define EXPORT __attribute__((visibility("default")))
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RootVoiceShim", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "RootVoiceShim", __VA_ARGS__)

// Метка, по которой установщик отличает обёртку от настоящего драйвера.
EXPORT const char rvc_shim_marker[] = "RVC_SHIM_MARKER_v1";

// ---- hardware/hardware.h ---------------------------------------------------

struct hw_module_t;
struct hw_device_t;

typedef struct hw_module_methods_t {
    int (*open)(const struct hw_module_t *module, const char *id, struct hw_device_t **device);
} hw_module_methods_t;

typedef struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    hw_module_methods_t *methods;
    void *dso;
#ifdef __LP64__
    uint64_t reserved[32 - 7];
#else
    uint32_t reserved[32 - 7];
#endif
} hw_module_t;

typedef struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t *module;
#ifdef __LP64__
    uint64_t reserved[12];
#else
    uint32_t reserved[12];
#endif
    int (*close)(struct hw_device_t *device);
} hw_device_t;

_Static_assert(sizeof(hw_module_t) == (sizeof(void *) == 8 ? 248 : 128), "hw_module_t ABI");
_Static_assert(sizeof(hw_device_t) == (sizeof(void *) == 8 ? 120 : 64), "hw_device_t ABI");

// ---- hardware/audio.h (только используемая часть) --------------------------

typedef int audio_io_handle_t;
typedef uint32_t audio_devices_t;
typedef uint32_t audio_channel_mask_t;
typedef uint32_t audio_format_t;
typedef uint32_t audio_input_flags_t;
typedef int audio_source_t;

struct audio_config {
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
    // дальше offload_info и frame_count — не трогаем
};

struct audio_stream {
    uint32_t (*get_sample_rate)(const struct audio_stream *stream);
    int (*set_sample_rate)(struct audio_stream *stream, uint32_t rate);
    size_t (*get_buffer_size)(const struct audio_stream *stream);
    audio_channel_mask_t (*get_channels)(const struct audio_stream *stream);
    audio_format_t (*get_format)(const struct audio_stream *stream);
    int (*set_format)(struct audio_stream *stream, audio_format_t format);
    int (*standby)(struct audio_stream *stream);
    int (*dump)(const struct audio_stream *stream, int fd);
    audio_devices_t (*get_device)(const struct audio_stream *stream);
    int (*set_device)(struct audio_stream *stream, audio_devices_t device);
    int (*set_parameters)(struct audio_stream *stream, const char *kv_pairs);
    char *(*get_parameters)(const struct audio_stream *stream, const char *keys);
    int (*add_audio_effect)(const struct audio_stream *stream, void *effect);
    int (*remove_audio_effect)(const struct audio_stream *stream, void *effect);
};

struct audio_stream_in {
    struct audio_stream common;
    int (*set_gain)(struct audio_stream_in *stream, float gain);
    ssize_t (*read)(struct audio_stream_in *stream, void *buffer, size_t bytes);
    // дальше get_input_frames_lost и др. — не трогаем
};

struct audio_hw_device {
    struct hw_device_t common;
    uint32_t (*get_supported_devices)(const struct audio_hw_device *dev);
    int (*init_check)(const struct audio_hw_device *dev);
    int (*set_voice_volume)(struct audio_hw_device *dev, float volume);
    int (*set_master_volume)(struct audio_hw_device *dev, float volume);
    int (*get_master_volume)(struct audio_hw_device *dev, float *volume);
    int (*set_mode)(struct audio_hw_device *dev, int mode);
    int (*set_mic_mute)(struct audio_hw_device *dev, _Bool state);
    int (*get_mic_mute)(const struct audio_hw_device *dev, _Bool *state);
    int (*set_parameters)(struct audio_hw_device *dev, const char *kv_pairs);
    char *(*get_parameters)(const struct audio_hw_device *dev, const char *keys);
    size_t (*get_input_buffer_size)(const struct audio_hw_device *dev, const struct audio_config *config);
    int (*open_output_stream)(struct audio_hw_device *dev, audio_io_handle_t handle, audio_devices_t devices,
                              uint32_t flags, struct audio_config *config, void **stream_out,
                              const char *address);
    void (*close_output_stream)(struct audio_hw_device *dev, void *stream_out);
    int (*open_input_stream)(struct audio_hw_device *dev, audio_io_handle_t handle, audio_devices_t devices,
                             struct audio_config *config, struct audio_stream_in **stream_in,
                             audio_input_flags_t flags, const char *address, audio_source_t source);
    void (*close_input_stream)(struct audio_hw_device *dev, struct audio_stream_in *stream_in);
    // дальше get_microphones, dump и др. — не трогаем
};

#define HARDWARE_MODULE_TAG (('H' << 24) | ('W' << 16) | ('M' << 8) | 'T')
#define AUDIO_HARDWARE_INTERFACE "audio_hw_if"
// Драйверы Qualcomm CAF объявляют 2.0, но собраны с тем же audio.h, что и 3.0 (open_input_stream
// с address/source — с Android 5), поэтому достаточно 2.0. Android 8+ других структур не знает.
#define AUDIO_DEVICE_API_VERSION_2_0 0x0200

#define AUDIO_FORMAT_PCM_16_BIT 0x1u
#define AUDIO_FORMAT_PCM_32_BIT 0x3u
#define AUDIO_FORMAT_PCM_8_24_BIT 0x4u
#define AUDIO_FORMAT_PCM_FLOAT 0x5u
#define AUDIO_FORMAT_PCM_24_BIT_PACKED 0x6u  // 3 байта, little-endian (так Qualcomm отдаёт CAMCORDER)

#define AUDIO_CHANNEL_REPRESENTATION_INDEX 0x80000000u

// Не трогаем: горячее слово, MMAP (read не используется) и прямые (сжатые) потоки.
#define AUDIO_INPUT_FLAG_HW_HOTWORD 0x2u
#define AUDIO_INPUT_FLAG_MMAP_NOIRQ 0x10u
#define AUDIO_INPUT_FLAG_DIRECT 0x80u
#define SKIP_FLAGS (AUDIO_INPUT_FLAG_HW_HOTWORD | AUDIO_INPUT_FLAG_MMAP_NOIRQ | AUDIO_INPUT_FLAG_DIRECT)

// Не микрофон: приём из сети при звонке, remote submix, FM, эхо-референс.
#define AUDIO_DEVICE_IN_TELEPHONY_RX 0x80000040u
#define AUDIO_DEVICE_IN_REMOTE_SUBMIX 0x80000100u
#define AUDIO_DEVICE_IN_FM_TUNER 0x80002000u
#define AUDIO_DEVICE_IN_ECHO_REFERENCE 0x90000000u

static int source_is_voice(audio_source_t s) {
    switch (s) {
    case 0:   // DEFAULT
    case 1:   // MIC
    case 5:   // CAMCORDER
    case 6:   // VOICE_RECOGNITION
    case 7:   // VOICE_COMMUNICATION
    case 9:   // UNPROCESSED
    case 10:  // VOICE_PERFORMANCE
        return 1;
    default:  // VOICE_UPLINK/DOWNLINK/CALL, REMOTE_SUBMIX, ECHO_REFERENCE, FM_TUNER, HOTWORD
        return 0;
    }
}

static int device_is_mic(audio_devices_t d) {
    const audio_devices_t not_mic[] = {AUDIO_DEVICE_IN_TELEPHONY_RX, AUDIO_DEVICE_IN_REMOTE_SUBMIX,
                                       AUDIO_DEVICE_IN_FM_TUNER, AUDIO_DEVICE_IN_ECHO_REFERENCE};
    for (size_t i = 0; i < sizeof(not_mic) / sizeof(not_mic[0]); i++)
        if ((d & not_mic[i]) == not_mic[i]) return 0;
    return 1;
}

// ---- состояние -------------------------------------------------------------

#define CHUNK 256
#define MAX_CH 8
#define MAX_STREAMS 32
#define PARAM_POLL_NS 150000000LL

typedef struct {
    struct audio_stream_in *stream;
    ssize_t (*orig_read)(struct audio_stream_in *, void *, size_t);
    int (*orig_dump)(const struct audio_stream *, int);
    rvc_dsp_t *dsp;
    int process;  // 0 — поток не наш (не микрофон/формат), только счётчики
    audio_source_t source;
    audio_devices_t device;
    audio_input_flags_t flags;
    audio_format_t format;
    uint32_t rate;
    int channels;
    const prop_info *prop;
    uint32_t prop_serial;
    int have_params;
    int64_t next_poll_ns;
    uint64_t reads, frames;
    float peak_in, peak_out;
    float scratch[CHUNK * MAX_CH];
} shim_stream_t;

static hw_module_t *g_orig_hmi;
static void *g_orig_dso;
static char g_orig_path[512];

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static shim_stream_t *g_streams[MAX_STREAMS];
static int (*g_orig_open_input)(struct audio_hw_device *, audio_io_handle_t, audio_devices_t, struct audio_config *,
                                struct audio_stream_in **, audio_input_flags_t, const char *, audio_source_t);
static void (*g_orig_close_input)(struct audio_hw_device *, struct audio_stream_in *);

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static shim_stream_t *find_stream(const void *stream) {
    shim_stream_t *r = NULL;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_STREAMS; i++) {
        if (g_streams[i] && g_streams[i]->stream == stream) {
            r = g_streams[i];
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return r;
}

static int add_stream(shim_stream_t *st) {
    int ok = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_STREAMS; i++) {
        if (!g_streams[i]) {
            g_streams[i] = st;
            ok = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return ok;
}

static shim_stream_t *take_stream(const void *stream) {
    shim_stream_t *r = NULL;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_STREAMS; i++) {
        if (g_streams[i] && g_streams[i]->stream == stream) {
            r = g_streams[i];
            g_streams[i] = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return r;
}

// ---- параметры -------------------------------------------------------------

static void on_prop(void *cookie, const char *name, const char *value, uint32_t serial) {
    (void)name;
    shim_stream_t *st = cookie;
    if (serial == st->prop_serial) return;
    st->prop_serial = serial;
    rvc_params_t p;
    if (value && rvc_parse_params(value, &p) == 0) {
        rvc_set_params(st->dsp, &p);
        st->have_params = 1;
    }
}

static void poll_params(shim_stream_t *st) {
    int64_t t = now_ns();
    if (t < st->next_poll_ns) return;
    st->next_poll_ns = t + PARAM_POLL_NS;
    if (!st->prop) st->prop = __system_property_find(RVC_PARAMS_PROP);
    if (st->prop) __system_property_read_callback(st->prop, on_prop, st);
}

// ---- обработка -------------------------------------------------------------

static int bytes_per_sample(audio_format_t f) {
    switch (f) {
    case AUDIO_FORMAT_PCM_16_BIT: return 2;
    case AUDIO_FORMAT_PCM_24_BIT_PACKED: return 3;
    case AUDIO_FORMAT_PCM_32_BIT:
    case AUDIO_FORMAT_PCM_8_24_BIT:
    case AUDIO_FORMAT_PCM_FLOAT: return 4;
    default: return 0;
    }
}

static void process_buffer(shim_stream_t *st, void *buf, size_t bytes) {
    int ch = st->channels;
    size_t frames = bytes / ((size_t)bytes_per_sample(st->format) * ch);
    st->frames += frames;
    poll_params(st);
    // Без параметров от приложения или при выключенном эффекте звук не трогаем вовсе.
    if (!st->have_params || !rvc_get_params(st->dsp)->enabled) {
        st->peak_in = st->peak_out = 0;
        return;
    }
    int chunk = CHUNK * MAX_CH / ch;
    float pin = 0, pout = 0;
    for (size_t done = 0; done < frames;) {
        int n = (int)(frames - done < (size_t)chunk ? frames - done : (size_t)chunk);
        size_t off = done * ch, cnt = (size_t)n * ch;
        float *s = st->scratch;
        switch (st->format) {
        case AUDIO_FORMAT_PCM_16_BIT: {
            const int16_t *p = (const int16_t *)buf + off;
            for (size_t i = 0; i < cnt; i++) s[i] = p[i] * (1.f / 32768.f);
            break;
        }
        case AUDIO_FORMAT_PCM_32_BIT: {
            const int32_t *p = (const int32_t *)buf + off;
            for (size_t i = 0; i < cnt; i++) s[i] = (float)p[i] * (1.f / 2147483648.f);
            break;
        }
        case AUDIO_FORMAT_PCM_8_24_BIT: {
            const int32_t *p = (const int32_t *)buf + off;
            for (size_t i = 0; i < cnt; i++) s[i] = (float)p[i] * (1.f / 8388608.f);
            break;
        }
        case AUDIO_FORMAT_PCM_24_BIT_PACKED: {
            const uint8_t *p = (const uint8_t *)buf + off * 3;
            for (size_t i = 0; i < cnt; i++, p += 3) {
                int32_t v = (int32_t)((uint32_t)p[0] << 8 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 24) >> 8;
                s[i] = (float)v * (1.f / 8388608.f);
            }
            break;
        }
        default:
            memcpy(s, (const float *)buf + off, cnt * sizeof(float));
        }
        for (size_t i = 0; i < cnt; i++) pin = fmaxf(pin, fabsf(s[i]));
        rvc_process(st->dsp, s, n);
        for (size_t i = 0; i < cnt; i++) {
            float v = s[i] > 1.f ? 1.f : s[i] < -1.f ? -1.f : s[i];
            s[i] = v;
            pout = fmaxf(pout, fabsf(v));
        }
        switch (st->format) {
        case AUDIO_FORMAT_PCM_16_BIT: {
            int16_t *p = (int16_t *)buf + off;
            for (size_t i = 0; i < cnt; i++) {
                float v = s[i] * 32768.f;
                p[i] = (int16_t)(v > 32767.f ? 32767 : v);
            }
            break;
        }
        case AUDIO_FORMAT_PCM_32_BIT: {
            int32_t *p = (int32_t *)buf + off;
            for (size_t i = 0; i < cnt; i++) {
                double v = (double)s[i] * 2147483648.0;
                p[i] = (int32_t)(v > 2147483647.0 ? 2147483647.0 : v);
            }
            break;
        }
        case AUDIO_FORMAT_PCM_8_24_BIT: {
            int32_t *p = (int32_t *)buf + off;
            for (size_t i = 0; i < cnt; i++) {
                float v = s[i] * 8388608.f;
                p[i] = (int32_t)(v > 8388607.f ? 8388607.f : v);
            }
            break;
        }
        case AUDIO_FORMAT_PCM_24_BIT_PACKED: {
            uint8_t *p = (uint8_t *)buf + off * 3;
            for (size_t i = 0; i < cnt; i++, p += 3) {
                float f = s[i] * 8388608.f;
                int32_t v = (int32_t)(f > 8388607.f ? 8388607.f : f);
                p[0] = (uint8_t)v;
                p[1] = (uint8_t)(v >> 8);
                p[2] = (uint8_t)(v >> 16);
            }
            break;
        }
        default:
            memcpy((float *)buf + off, s, cnt * sizeof(float));
        }
        done += n;
    }
    st->peak_in = pin;
    st->peak_out = pout;
}

static ssize_t shim_read(struct audio_stream_in *stream, void *buffer, size_t bytes) {
    shim_stream_t *st = find_stream(stream);
    if (!st) return -ENODEV;  // не бывает: read подменяется только у зарегистрированных потоков
    ssize_t r = st->orig_read(stream, buffer, bytes);
    st->reads++;
    if (r > 0 && st->process) process_buffer(st, buffer, (size_t)r);
    return r;
}

static float to_db(float v) { return v > 1e-9f ? 20.f * log10f(v) : -180.f; }

// Дописывает состояние обёртки в «Hal stream dump» (dumpsys media.audio_flinger).
static int shim_dump(const struct audio_stream *stream, int fd) {
    shim_stream_t *st = find_stream(stream);
    int r = st && st->orig_dump ? st->orig_dump(stream, fd) : 0;
    if (st) {
        const rvc_params_t *p = rvc_get_params(st->dsp);
        dprintf(fd, "      RootVoice shim: %s source %d device 0x%x flags 0x%x format 0x%x rate %u ch %d\n",
                st->process ? "processing" : "bypass", st->source, st->device, st->flags, st->format, st->rate,
                st->channels);
        dprintf(fd, "      RootVoice shim: reads %llu frames %llu peak in %.1f dB out %.1f dB params %s "
                "(enabled %d pitch %.2f rough %.2f robot %.2f echo %.2f radio %d gain %.1f)\n",
                (unsigned long long)st->reads, (unsigned long long)st->frames, to_db(st->peak_in),
                to_db(st->peak_out), st->have_params ? "ok" : (st->prop ? "unparsed" : "NO PROP"), p->enabled,
                p->pitch, p->rough, p->robot, p->echo, p->radio, p->gain_db);
    }
    return r;
}

// ---- перехват устройства ---------------------------------------------------

static int channel_count(audio_channel_mask_t mask) {
    if (mask & AUDIO_CHANNEL_REPRESENTATION_INDEX) mask &= 0x3fffffffu;
    return __builtin_popcount(mask);
}

static int shim_open_input(struct audio_hw_device *dev, audio_io_handle_t handle, audio_devices_t devices,
                           struct audio_config *config, struct audio_stream_in **stream_in,
                           audio_input_flags_t flags, const char *address, audio_source_t source) {
    int r = g_orig_open_input(dev, handle, devices, config, stream_in, flags, address, source);
    if (r != 0 || !stream_in || !*stream_in) return r;
    struct audio_stream_in *in = *stream_in;
    if (!in->read) return r;

    shim_stream_t *st = calloc(1, sizeof(*st));
    if (!st) return r;
    st->stream = in;
    st->orig_read = in->read;
    st->orig_dump = in->common.dump;
    st->source = source;
    st->device = devices;
    st->flags = flags;
    st->format = in->common.get_format ? in->common.get_format(&in->common) : config->format;
    st->rate = in->common.get_sample_rate ? in->common.get_sample_rate(&in->common) : config->sample_rate;
    st->channels = channel_count(in->common.get_channels ? in->common.get_channels(&in->common)
                                                           : config->channel_mask);
    st->dsp = rvc_create();
    st->process = st->dsp && source_is_voice(source) && device_is_mic(devices) && !(flags & SKIP_FLAGS) &&
                  bytes_per_sample(st->format) > 0 && st->channels >= 1 && st->channels <= MAX_CH &&
                  st->rate >= 8000 && st->rate <= 192000;
    if (st->dsp) rvc_configure(st->dsp, st->rate >= 8000 ? (int)st->rate : 48000, st->channels >= 1 ? st->channels : 1);
    if (!st->dsp || !add_stream(st)) {
        if (st->dsp) rvc_destroy(st->dsp);
        free(st);
        return r;
    }
    in->read = shim_read;
    in->common.dump = shim_dump;
    LOGI("input stream %p: source %d device 0x%x flags 0x%x format 0x%x rate %u ch %d -> %s", (void *)in,
         source, devices, flags, st->format, st->rate, st->channels, st->process ? "processing" : "bypass");
    return r;
}

static void shim_close_input(struct audio_hw_device *dev, struct audio_stream_in *stream_in) {
    shim_stream_t *st = take_stream(stream_in);
    if (st) {
        // Возвращаем драйверу его функции: он может сверять их при закрытии.
        stream_in->read = st->orig_read;
        stream_in->common.dump = st->orig_dump;
    }
    g_orig_close_input(dev, stream_in);
    if (st) {
        rvc_destroy(st->dsp);
        free(st);
    }
}

static int shim_open(const hw_module_t *module, const char *id, hw_device_t **device) {
    (void)module;
    if (!g_orig_hmi || !g_orig_hmi->methods || !g_orig_hmi->methods->open) return -ENODEV;
    int r = g_orig_hmi->methods->open(g_orig_hmi, id, device);
    if (r != 0 || !device || !*device || !id || strcmp(id, AUDIO_HARDWARE_INTERFACE) != 0) return r;
    struct audio_hw_device *dev = (struct audio_hw_device *)*device;
    if (dev->common.version < AUDIO_DEVICE_API_VERSION_2_0) {
        LOGW("device API 0x%x is too old, not wrapping", dev->common.version);
        return r;
    }
    if (!dev->open_input_stream || !dev->close_input_stream) return r;
    if (dev->open_input_stream == shim_open_input) return r;  // уже обёрнуто
    pthread_mutex_lock(&g_lock);
    g_orig_open_input = dev->open_input_stream;
    g_orig_close_input = dev->close_input_stream;
    pthread_mutex_unlock(&g_lock);
    dev->open_input_stream = shim_open_input;
    dev->close_input_stream = shim_close_input;
    LOGI("wrapped audio device (API 0x%x)", dev->common.version);
    return r;
}

static hw_module_methods_t g_methods = {.open = shim_open};

// HMI заполняется в конструкторе копией HMI оригинала (hw_get_module сверяет id сразу после dlopen).
EXPORT hw_module_t HMI = {
    .tag = HARDWARE_MODULE_TAG,
    .id = "audio",
    .name = "Root Voice Changer HAL shim (original not loaded)",
    .author = "relkin",
    .methods = &g_methods,
};

__attribute__((constructor)) static void shim_init(void) {
    Dl_info info;
    if (!dladdr((void *)&HMI, &info) || !info.dli_fname) {
        LOGW("dladdr failed");
        return;
    }
    // /vendor/lib/hw/audio.primary.X.so → /vendor/lib/hw/audio.primary.X.rvcorig.so
    size_t len = strlen(info.dli_fname);
    if (len < 4 || len + 16 > sizeof(g_orig_path) || strcmp(info.dli_fname + len - 3, ".so") != 0) {
        LOGW("unexpected library path %s", info.dli_fname);
        return;
    }
    memcpy(g_orig_path, info.dli_fname, len - 3);
    strcpy(g_orig_path + len - 3, ".rvcorig.so");

    g_orig_dso = dlopen(g_orig_path, RTLD_NOW | RTLD_LOCAL);
    if (!g_orig_dso) {
        LOGW("dlopen %s failed: %s", g_orig_path, dlerror());
        return;
    }
    hw_module_t *orig = dlsym(g_orig_dso, "HMI");
    if (!orig || orig == &HMI || dlsym(g_orig_dso, "rvc_shim_marker")) {
        LOGW("%s has no HMI or is the shim itself", g_orig_path);
        return;
    }
    g_orig_hmi = orig;
    orig->dso = g_orig_dso;
    HMI = *orig;
    HMI.methods = &g_methods;
    HMI.dso = NULL;  // заполнит hw_get_module
    // Эффект librootvoice.so в этом же процессе видит это и не обрабатывает звук второй раз.
    setenv("RVC_SHIM_ACTIVE", "1", 1);
    LOGI("loaded original %s (%s)", g_orig_path, orig->name ? orig->name : "?");
}
