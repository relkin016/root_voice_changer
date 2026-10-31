#!/usr/bin/env bash
# Собирает подписанный release APK (R8 + shrinkResources, без x86), гоняет юнит-тесты и тест DSP.
# Запускается из корня проекта на сборочной ноде.
# Результат: dist/RootVoiceChanger-X.Y.Z.apk + dist/RootVoiceChanger-X.Y.Z.apk.sha256
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLS="${TOOLS:-$HOME/tools}"
export JAVA_HOME="$TOOLS/jdk" ANDROID_HOME="$TOOLS/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
echo "sdk.dir=$ANDROID_HOME" > local.properties

[ -f keystore/keystore.properties ] || { echo "нет keystore/keystore.properties — APK будет неподписанным" >&2; exit 1; }

# DSP: статический бинарник NDK запускается прямо на ноде.
ndk="$(ls -d "$ANDROID_HOME"/ndk/*/ | sort -V | tail -1)"
clang="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
"$clang" --target=x86_64-linux-android26 -static -O2 -ffast-math -fno-finite-math-only -Wall -Wextra -Werror \
  -I app/src/main/cpp tools/dsp_test.c app/src/main/cpp/rvc_dsp.c -lm -o build-dsp-test
./build-dsp-test
rm -f build-dsp-test

# Нода: 16 потоков / 32 ГБ — нагружаем полностью.
workers="${GRADLE_WORKERS:-$(nproc)}"
tasks=("$@")
[ ${#tasks[@]} -gt 0 ] || tasks=(:app:testReleaseUnitTest :app:assembleRelease)
./gradlew --no-daemon --max-workers="$workers" \
  -Dorg.gradle.jvmargs="-Xmx${GRADLE_XMX:-12g} -XX:+UseParallelGC -Dfile.encoding=UTF-8" \
  "${tasks[@]}"

version="$(bash ci/version.sh)"
src=app/build/outputs/apk/release/app-release.apk
mkdir -p dist
apk="dist/RootVoiceChanger-$version.apk"
cp "$src" "$apk"

apksigner="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner"
"$apksigner" verify --print-certs "$apk" | grep -m1 'certificate SHA-256'

listing="$(jar tf "$apk")"
if grep -q '^lib/x86' <<<"$listing"; then
  echo "в release попали x86-библиотеки" >&2; exit 1
fi
for abi in arm64-v8a armeabi-v7a; do
  for lib in librootvoice.so librvcjni.so; do
    grep -qx "lib/$abi/$lib" <<<"$listing" || { echo "нет lib/$abi/$lib" >&2; exit 1; }
  done
done
# Библиотека эффекта должна экспортировать AELI и зависеть только от libc/libm/libdl (их видит вендорный HAL).
readelf="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
tmp="$(mktemp -d)"
(cd "$tmp" && jar xf "$OLDPWD/$apk" lib/arm64-v8a/librootvoice.so lib/armeabi-v7a/librootvoice.so)
for so in "$tmp"/lib/*/librootvoice.so; do
  "$readelf" --dyn-syms "$so" | grep -q ' AELI$' || { echo "$so: нет символа AELI" >&2; exit 1; }
  bad="$("$readelf" -d "$so" | sed -nE 's/.*NEEDED.*\[(.*)\]/\1/p' | grep -vxE 'libc.so|libm.so|libdl.so' || true)"
  [ -z "$bad" ] || { echo "$so: лишние зависимости: $bad" >&2; exit 1; }
done
rm -rf "$tmp"

(cd dist && sha256sum "$(basename "$apk")" > "$(basename "$apk").sha256")
cat "$apk.sha256"
ls -l "$apk"
