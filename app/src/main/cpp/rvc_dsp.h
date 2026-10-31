// Ядро обработки голоса: общее для системного аудиоэффекта (librootvoice.so)
// и для прослушки внутри приложения (librvcjni.so). Чистый C, без libc++/liblog,
// чтобы библиотеку мог загрузить вендорный аудио-HAL.
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Обрабатываются первые RVC_MAX_CH каналов, остальные проходят без изменений.
#define RVC_MAX_CH 2

// Имя системного свойства, через которое приложение передаёт параметры эффекту.
#define RVC_PARAMS_PROP "debug.rvc.params"

typedef struct {
    int enabled;     // 0 — эффект пропускает звук как есть
    float pitch;     // сдвиг высоты, полутоны [-12..12]
    float rough;     // грубость [-1..1]: <0 мягче/выше, >0 грубее/ниже
    float robot;     // робот (кольцевая модуляция) [0..1]
    float echo;      // эхо [0..1]
    int radio;       // эффект рации
    float gain_db;   // усиление на выходе [-12..12]
} rvc_params_t;

typedef struct rvc_dsp rvc_dsp_t;

rvc_dsp_t *rvc_create(void);
void rvc_destroy(rvc_dsp_t *d);
// Задаёт частоту и число каналов, сбрасывает состояние.
void rvc_configure(rvc_dsp_t *d, int sample_rate, int channels);
void rvc_reset(rvc_dsp_t *d);
void rvc_set_params(rvc_dsp_t *d, const rvc_params_t *p);
const rvc_params_t *rvc_get_params(const rvc_dsp_t *d);

// Строка формата "1 <enabled> <pitch> <rough> <robot> <echo> <radio> <gain_db>".
// Возвращает 0 при успехе; при ошибке *p не меняется.
int rvc_parse_params(const char *s, rvc_params_t *p);

// Обработка на месте: interleaved float, frames кадров по channels отсчётов.
void rvc_process(rvc_dsp_t *d, float *buf, int frames);

#ifdef __cplusplus
}
#endif
