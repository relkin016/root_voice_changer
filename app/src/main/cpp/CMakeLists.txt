cmake_minimum_required(VERSION 3.22.1)
project(rootvoice C)

set(CMAKE_C_STANDARD 11)
add_compile_options(-O3 -ffast-math -fno-finite-math-only -Wall -Wextra -fvisibility=hidden)
# 16 КБ страницы (Android 15+), без лишних зависимостей: только libc/libm (доступны вендорному HAL).
add_link_options(-Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--hash-style=both)

# Системный аудиоэффект: модуль кладёт его в /vendor/lib*/soundfx.
add_library(rootvoice SHARED rvc_effect.c rvc_dsp.c)
target_link_libraries(rootvoice m)

# JNI для прослушки в приложении.
add_library(rvcjni SHARED rvc_jni.c rvc_dsp.c)
target_link_libraries(rvcjni m)
