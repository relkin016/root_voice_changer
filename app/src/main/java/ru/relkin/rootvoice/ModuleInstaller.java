package ru.relkin.rootvoice;

import android.content.Context;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Собирает на устройстве модуль Magisk/KernelSU/APatch: библиотека эффекта из APK +
 * пропатченные копии audio_effects.xml этого устройства, и устанавливает его через root.
 */
final class ModuleInstaller {
    static final String MODULE_ID = "rootvoice";
    static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    static final UUID EFFECT_UUID = UUID.fromString("f6f24dc9-fd4d-4a3c-845b-581ddf0637b6");
    static final String PARAMS_PROP = "debug.rvc.params";
    static final String PARAMS_FILE = "/data/adb/rootvoice/params";
    static final String PUBLIC_ZIP = "/sdcard/Download/RootVoice-module.zip";

    /** Где загрузчик эффектов ищет конфигурацию (берётся первая найденная; патчим все). */
    private static final String CONFIG_GLOB = "/odm/etc/audio_effects.xml /vendor/etc/audio_effects.xml "
            + "/vendor/etc/audio/audio_effects.xml /vendor/etc/audio/sku_*/audio_effects.xml "
            + "/system/etc/audio_effects.xml";

    enum State { NOT_INSTALLED, PENDING_INSTALL, INSTALLED, DISABLED, PENDING_REMOVE }

    private ModuleInstaller() {
    }

    /** Загружен ли эффект аудиосистемой прямо сейчас (т. е. модуль работает). */
    static boolean effectLoaded() {
        try {
            AudioEffect.Descriptor[] all = AudioEffect.queryEffects();
            if (all == null) return false;
            for (AudioEffect.Descriptor d : all) if (EFFECT_UUID.equals(d.uuid)) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    static State state() throws IOException {
        String out = RootShell.run("m=" + MODULE_DIR + "\n"
                + "if [ -f $m/remove ]; then echo remove\n"
                + "elif [ -d /data/adb/modules_update/" + MODULE_ID + " ] || [ -f $m/update ]; then echo update\n"
                + "elif [ -f $m/disable ]; then echo disable\n"
                + "elif [ -f $m/module.prop ]; then echo installed\n"
                + "else echo none; fi").out.trim();
        switch (out) {
            case "remove": return State.PENDING_REMOVE;
            case "update": return State.PENDING_INSTALL;
            case "disable": return State.DISABLED;
            case "installed": return State.INSTALLED;
            default: return State.NOT_INSTALLED;
        }
    }

    /** Передаёт параметры работающему эффекту и сохраняет их для восстановления после перезагрузки. */
    static void applyParams(String params) throws IOException {
        String q = RootShell.quote(params);
        RootShell.run("setprop " + PARAMS_PROP + " " + q + "\nmkdir -p /data/adb/rootvoice\n"
                + "echo " + q + " > " + PARAMS_FILE);
    }

    /** Собирает и устанавливает модуль. Возвращает журнал установки. */
    static String install(Context ctx, String params) throws IOException {
        StringBuilder log = new StringBuilder();
        List<String> configs = new ArrayList<>();
        for (String line : RootShell.run("for f in " + CONFIG_GLOB + "; do [ -f \"$f\" ] && echo \"$f\"; done; true")
                .out.split("\n")) {
            if (line.startsWith("/")) configs.add(line.trim());
        }
        if (configs.isEmpty()) {
            throw new IOException("На устройстве не найден audio_effects.xml. Вероятно, прошивка использует "
                    + "AIDL-HAL эффектов или старый audio_effects.conf — такой вариант пока не поддерживается.");
        }

        File zipFile = new File(ctx.getCacheDir(), "rootvoice-module.zip");
        StringBuilder targets = new StringBuilder();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile));
             ZipFile apk = new ZipFile(ctx.getApplicationInfo().sourceDir)) {
            for (String path : configs) {
                RootShell.Result r = RootShell.run("cat " + RootShell.quote(path));
                if (!r.ok()) throw new IOException("Не удалось прочитать " + path + ": " + r.out);
                String patched = EffectsXmlPatcher.patch(r.out, EFFECT_UUID.toString());
                String rel = moduleRelPath(path);
                put(zip, rel, patched);
                targets.append(rel).append('|').append(path).append('\n');
                log.append("Конфигурация: ").append(path).append('\n');
            }

            List<String> abis = Arrays.asList(Build.SUPPORTED_ABIS);
            int libs = 0;
            if (abis.contains("arm64-v8a")) libs += putLib(zip, apk, "arm64-v8a", "lib64");
            if (abis.contains("armeabi-v7a")) libs += putLib(zip, apk, "armeabi-v7a", "lib");
            if (libs == 0) throw new IOException("В APK нет библиотеки эффекта для " + abis);

            put(zip, "targets.txt", targets.toString());
            put(zip, "module.prop", "id=" + MODULE_ID + "\nname=Root Voice Changer\nversion=v"
                    + BuildConfigCompat.versionName(ctx) + "\nversionCode=" + BuildConfigCompat.versionCode(ctx)
                    + "\nauthor=relkin\ndescription=Изменение голоса с микрофона для всех приложений. "
                    + "Настройка — в приложении Root Voice Changer.\n");
            put(zip, "customize.sh", asset(ctx, "module/customize.sh"));
            put(zip, "post-fs-data.sh", asset(ctx, "module/post-fs-data.sh"));
            put(zip, "service.sh", asset(ctx, "module/service.sh"));
            put(zip, "uninstall.sh", asset(ctx, "module/uninstall.sh"));
            put(zip, "sepolicy.rule", asset(ctx, "module/sepolicy.rule"));
            put(zip, "META-INF/com/google/android/update-binary", asset(ctx, "module/update-binary"));
            put(zip, "META-INF/com/google/android/updater-script", "#MAGISK\n");
        }

        String z = "/data/local/tmp/rootvoice-module.zip";
        RootShell.Result r = RootShell.run("set -e\n"
                + "cp " + RootShell.quote(zipFile.getAbsolutePath()) + " " + z + "\nchmod 644 " + z + "\n"
                + "mkdir -p /data/adb/rootvoice\n"
                + "echo " + RootShell.quote(params) + " > " + PARAMS_FILE + "\n"
                + "setprop " + PARAMS_PROP + " " + RootShell.quote(params) + "\n"
                + "mkdir -p /sdcard/Download && cp " + z + " " + PUBLIC_ZIP + " || true\n"
                + "set +e\n"
                + "if command -v magisk >/dev/null 2>&1; then magisk --install-module " + z + "\n"
                + "elif [ -x /data/adb/ksud ]; then /data/adb/ksud module install " + z + "\n"
                + "elif [ -x /data/adb/apd ]; then /data/adb/apd module install " + z + "\n"
                + "else echo 'Не найден Magisk / KernelSU / APatch'; exit 3; fi");
        zipFile.delete();
        log.append(r.out.trim()).append('\n');
        if (!r.ok()) {
            throw new IOException(log + "\nУстановка не удалась (код " + r.code + "). Модуль сохранён в "
                    + PUBLIC_ZIP + " — его можно поставить вручную через root-менеджер.");
        }
        log.append("\nКопия модуля: ").append(PUBLIC_ZIP);
        return log.toString();
    }

    static void remove() throws IOException {
        RootShell.run("rm -rf /data/adb/modules_update/" + MODULE_ID + "\n"
                + "[ -d " + MODULE_DIR + " ] && touch " + MODULE_DIR + "/remove; true");
    }

    static void reboot() throws IOException {
        RootShell.run("svc power reboot || reboot");
    }

    /** /vendor/etc/x → system/vendor/etc/x (магическое монтирование), /odm/... монтирует post-fs-data.sh. */
    static String moduleRelPath(String path) {
        if (path.startsWith("/system/")) return path.substring(1);
        if (path.startsWith("/vendor/")) return "system" + path;
        if (path.startsWith("/odm/")) return path.substring(1);
        throw new IllegalArgumentException(path);
    }

    private static int putLib(ZipOutputStream zip, ZipFile apk, String abi, String libDir) throws IOException {
        ZipEntry e = apk.getEntry("lib/" + abi + "/" + EffectsXmlPatcher.LIB_FILE);
        if (e == null) return 0;
        byte[] data;
        try (InputStream is = apk.getInputStream(e)) {
            data = readAll(is);
        }
        // vendor — для HAL эффектов (Treble), system — для прошивок, где эффекты грузит audioserver.
        put(zip, "system/vendor/" + libDir + "/soundfx/" + EffectsXmlPatcher.LIB_FILE, data);
        put(zip, "system/" + libDir + "/soundfx/" + EffectsXmlPatcher.LIB_FILE, data);
        return 1;
    }

    private static String asset(Context ctx, String name) throws IOException {
        try (InputStream is = ctx.getAssets().open(name)) {
            return new String(readAll(is), StandardCharsets.UTF_8);
        }
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        put(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        for (int n; (n = is.read(b)) > 0; ) bo.write(b, 0, n);
        return bo.toByteArray();
    }
}
