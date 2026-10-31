# Root Voice Changer

Изменение голоса с микрофона в реальном времени для всех приложений Android (нужен root:
Magisk, KernelSU или APatch). Пресеты (в том числе свои — сохраняются в приложении), ползунок «грубости», тонкая настройка, прослушка себя и запись голоса с эффектом.
Сайт с актуальной версией: https://relkin016.github.io/root_voice_changer/

## Как это работает

- `app/src/main/cpp/rvc_dsp.c` — DSP на C: питч-шифтер (две головки задержки с окнами Ханна),
  «грубость» (сдвиг тона, сатурация с гейтом, рык 52 Гц, полки/фильтры), робот, эхо, рация, лимитер.
- `rvc_effect.c` → `librootvoice.so` — аудиоэффект Android (effect HAL API 3.0, тип pre-processing).
  Зависит только от libc/libm/libdl, поэтому его может загрузить вендорный аудио-HAL.
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
- Приложения, пишущие звук через MMAP/AAudio в обход эффектов, получают исходный голос.

## Сборка и релиз

Компиляция только на сборочной ноде: `ci/remote_build.sh` (архив и APK сверяются по SHA-256).
Полный релиз: `ci/release.sh "что нового"` — сборка → `~/Downloads` → MEGA (2 версии) →
`site/release.json` → коммит/push → Discord. Версия — `appVersion` в `app/build.gradle`.
Тест DSP: `tools/dsp_test.c` (гоняется в `ci/build.sh`).
