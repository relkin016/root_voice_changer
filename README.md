# Root Voice Changer

Изменение голоса с микрофона в реальном времени для всех приложений Android (нужен root:
Magisk, KernelSU или APatch). Пресеты (в том числе свои — сохраняются в приложении), ползунок «грубости», тонкая настройка, прослушка себя и запись голоса с эффектом.
Сайт с актуальной версией: https://relkin016.github.io/root_voice_changer/

## Как это работает

- `app/src/main/cpp/rvc_dsp.c` — DSP на C: питч-шифтер (две головки задержки с окнами Ханна),
  «грубость» (сдвиг тона, сатурация с гейтом, рык 52 Гц, полки/фильтры), робот, эхо, рация, лимитер.
- `rvc_effect.c` → `librootvoice.so` — аудиоэффект Android (effect HAL API 3.0, тип pre-processing).
  Зависит только от libc/libm/libdl, поэтому его может загрузить вендорный аудио-HAL.
- `rvc_shim.c` → `librvcshim.so` — обёртка над аудио-HAL. Эффекты записи Android сам не обрабатывает,
  а отдаёт драйверу (`add_audio_effect`); драйверы Qualcomm (CAF) понимают только свои AEC/NS и прочие
  эффекты молча игнорируют. Поэтому модуль кладёт обёртку под именем загруженного драйвера
  (`audio.primary.<платформа>.so`, ищется в `/proc/*/maps`), а оригинал — рядом как `*.rvcorig.so`.
  Обёртка подменяет `read()` входных потоков с микрофона (кроме звонка по сотовой сети, submix, FM,
  hotword и MMAP) и пропускает звук через тот же DSP: 16/24 packed/8_24/32 бит и float, любой режим,
  включая FAST/VOIP (Discord, WebRTC). Если обёртка активна, эффект звук не трогает (без двойной обработки).
  Диагностика: `logcat -s RootVoiceShim` и строки `RootVoice shim:` / `RootVoice:` в
  `dumpsys media.audio_flinger`.
- Модуль хранит копии файлов прошивки, поэтому запоминает её отпечаток (`fingerprint.txt`). После
  обновления прошивки `post-fs-data.sh` ставит `skip_mount` и ничего не подменяет, приложение пишет
  «прошивка обновилась» — нужно переустановить модуль. Оригиналы при сборке читаются из-под монтирований
  модуля (`unshare -m` + `umount`), так что в новый модуль не попадут файлы от старой прошивки.
- Приложение собирает на устройстве модуль: копирует `librootvoice.so` в `/vendor/lib*/soundfx`
  (и `/system/lib*/soundfx`), патчит **местные** `audio_effects.xml` (`EffectsXmlPatcher`) —
  добавляет эффект в `<preprocess>` для `mic`, `voice_communication`, `camcorder`, `voice_recognition` —
  и ставит модуль через `magisk --install-module` / `ksud` / `apd`. Копия: `/sdcard/Download/RootVoice-module.zip`.
- Параметры передаются на лету через свойство `debug.rvc.params` (`setprop` от root); эффект
  перечитывает его каждые 150 мс. После перезагрузки параметры восстанавливает `service.sh`
  из `/data/adb/rootvoice/params`.
- «Слушать себя»: если эффект загружен (`AudioEffect.queryEffects()` видит его UUID), звук с MIC
  уже обработан системой; иначе обрабатывается в приложении тем же DSP через JNI (`librvcjni.so`).
- «Записать» (`VoiceEngine` + `WavWriter`): тот же поток микрофона пишется в WAV 48 кГц моно
  в `Android/data/ru.relkin.rootvoice/files/Music/`; можно одновременно слушать себя. В приложении
  есть список записей: воспроизведение, «Поделиться» (FileProvider), удаление. При сворачивании
  приложения запись останавливается и сохраняется.

## Ограничения

- Android 8+ с классическим effect HAL и `audio_effects.xml`. На прошивках с AIDL-HAL эффектов
  (часть устройств на Android 14+) или только с `audio_effects.conf` эффект не загрузится —
  приложение это покажет в статусе.
- Приложения, пишущие звук через MMAP/AAudio (MMAP_NOIRQ), получают исходный голос: такие потоки
  читаются в обход `read()` драйвера.
- Обёртка рассчитана на классический HAL (`audio.primary.*.so` в `/vendor` или `/system`). Если звук
  после установки пропал — отключите модуль в root-менеджере (или безопасный режим Magisk).

## Сборка и релиз

Компиляция только на сборочной ноде: `ci/remote_build.sh` (архив и APK сверяются по SHA-256).
Полный релиз: `ci/release.sh "что нового"` — сборка → `~/Downloads` → MEGA (2 версии) →
`site/release.json` → коммит/push → Discord. Версия — `appVersion` в `app/build.gradle`.
Тест DSP: `tools/dsp_test.c` (гоняется в `ci/build.sh`).
