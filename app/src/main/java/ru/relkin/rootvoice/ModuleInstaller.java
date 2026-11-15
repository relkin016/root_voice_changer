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

    /** Обёртка драйвера (librvcshim.so): метка внутри и суффикс копии оригинала рядом с ней. */
    static final String SHIM_LIB = "librvcshim.so";
    static final String SHIM_MARKER = "RVC_SHIM_MARKER_v1";
    static final String ORIG_SUFFIX = ".rvcorig.so";

    /** Файл в модуле с отпечатком прошивки, для которой он собран (сверяет post-fs-data.sh). */
    static final String FINGERPRINT_FILE = "fingerprint.txt";
    private static final String FINGERPRINT_CMD =
            "echo \"$(getprop ro.build.fingerprint)|$(getprop ro.vendor.build.fingerprint)\"";

    /**
     * Скрипт печатает файл $f без подмен модулями (в т. ч. старой версией нашего): в отдельном
     * пространстве имён монтирования снимает монтирования над файлом, пока в нём есть метка $mark.
     * Нужен после обновления прошивки — иначе в новый модуль попали бы файлы от старой.
     */
    private static final String CLEAN_CAT = "bb=\n"
            + "for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do\n"
            + "  [ -x $b ] && { bb=$b; break; }; done\n"
            + "if [ -n \"$bb\" ]; then u=\"$bb unshare -m\"; else u=\"unshare -m\"; fi\n"
            + "f=\"$f\" mark=\"$mark\" bb=\"$bb\" $u sh -c '\n"
            + "  $bb mount --make-rprivate / || exit 1\n"
            + "  ok() { [ -s \"$f\" ] && ! grep -q \"$mark\" \"$f\"; }\n"
            + "  p=$f\n"
            + "  while ! ok; do\n"
            + "    $bb umount -l \"$p\" 2>/dev/null && continue\n"
            + "    p=${p%/*}; [ -n \"$p\" ] || exit 1\n"
            + "  done\n"
            + "  cat \"$f\"' 2>/dev/null\n";

    enum State { NOT_INSTALLED, PENDING_INSTALL, INSTALLED, STALE, DISABLED, PENDING_REMOVE }

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

    /** Загружена ли обёртка драйвера каким-нибудь процессом (виден её спутник *.rvcorig.so). */
    static boolean shimLoaded() throws IOException {
        return RootShell.run("grep -l '" + ORIG_SUFFIX.replace(".", "\\.") + "' /proc/[0-9]*/maps 2>/dev/null | head -1")
                .out.trim().startsWith("/proc/");
    }

    static State state() throws IOException {
        String out = RootShell.run("m=" + MODULE_DIR + "\n"
                + "if [ -f $m/remove ]; then echo remove\n"
                + "elif [ -d /data/adb/modules_update/" + MODULE_ID + " ] || [ -f $m/update ]; then echo update\n"
                + "elif [ -f $m/disable ]; then echo disable\n"
                + "elif [ -f $m/module.prop ] && [ \"$(cat $m/" + FINGERPRINT_FILE + " 2>/dev/null)\" != \"$("
                + FINGERPRINT_CMD + ")\" ]; then echo stale\n"
                + "elif [ -f $m/module.prop ]; then echo installed\n"
                + "else echo none; fi").out.trim();
        switch (out) {
            case "remove": return State.PENDING_REMOVE;
            case "update": return State.PENDING_INSTALL;
            case "disable": return State.DISABLED;
            case "installed": return State.INSTALLED;
            case "stale": return State.STALE;
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
                RootShell.Result r = RootShell.run("f=" + RootShell.quote(path) + " mark=" + EffectsXmlPatcher.LIB_FILE
                        + "\n" + CLEAN_CAT);
                if (!r.ok() || EffectsXmlPatcher.isPatched(r.out)) {
                    // Нет unshare: берём как есть (если файл уже подменён нашим модулем — он от этой же прошивки).
                    r = RootShell.run("cat " + RootShell.quote(path));
                }
                if (!r.ok()) throw new IOException("Не удалось прочитать " + path + ": " + r.out);
                String patched = EffectsXmlPatcher.patch(r.out, EFFECT_UUID.toString());
                String rel = moduleRelPath(path);
                put(zip, rel, patched);
                targets.append(rel).append('|').append(path).append('\n');
                log.append("Конфигурация: ").append(path).append('\n');
            }

            addHalShim(ctx, zip, apk, targets, log);

            List<String> abis = Arrays.asList(Build.SUPPORTED_ABIS);
            int libs = 0;
            if (abis.contains("arm64-v8a")) libs += putLib(zip, apk, "arm64-v8a", "lib64");
            if (abis.contains("armeabi-v7a")) libs += putLib(zip, apk, "armeabi-v7a", "lib");
            if (libs == 0) throw new IOException("В APK нет библиотеки эффекта для " + abis);

            put(zip, "targets.txt", targets.toString());
            put(zip, FINGERPRINT_FILE, RootShell.run(FINGERPRINT_CMD).out.trim() + "\n");
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

    /**
     * Находит загруженный аудио-HAL (audio.primary.*.so в картах памяти процессов) и кладёт в модуль
     * обёртку под его именем, а сам драйвер — рядом как *.rvcorig.so. Если обёртка уже стоит
     * (переустановка), оригинал берётся из её спутника. Без найденного HAL модуль ставится без обёртки.
     */
    private static void addHalShim(Context ctx, ZipOutputStream zip, ZipFile apk, StringBuilder targets,
                                   StringBuilder log) throws IOException {
        List<String> hals = new ArrayList<>();
        for (String line : RootShell.run("grep -ho '/[^ ]*/hw/audio\\.primary\\.[^ /]*\\.so$' /proc/[0-9]*/maps "
                + "2>/dev/null | sort -u; true").out.split("\n")) {
            String path = line.trim();
            if ((path.startsWith("/vendor/") || path.startsWith("/system/")) && !path.endsWith(ORIG_SUFFIX)
                    && !hals.contains(path)) hals.add(path);
        }
        if (hals.isEmpty()) {
            log.append("Обёртка драйвера: загруженный audio.primary.*.so не найден — ставлю только эффект\n");
            return;
        }
        for (String hal : hals) {
            String abi = hal.contains("/lib64/") ? "arm64-v8a" : "armeabi-v7a";
            ZipEntry e = apk.getEntry("lib/" + abi + "/" + SHIM_LIB);
            if (e == null) {
                log.append("Обёртка драйвера: нет ").append(SHIM_LIB).append(" для ").append(abi).append('\n');
                continue;
            }
            String base = hal.substring(0, hal.length() - 3);
            String origCopy = base + ORIG_SUFFIX;
            // Сначала настоящий драйвер прошивки из-под монтирований модуля; если не вышло и на месте
            // драйвера наша обёртка — копия оригинала рядом с ней (верна, пока прошивка не обновлялась).
            byte[] orig = readAsRoot(ctx, "f=" + RootShell.quote(hal) + " mark=" + SHIM_MARKER + "\n" + CLEAN_CAT);
            if (!isElf(orig) && RootShell.run("grep -q " + SHIM_MARKER + " " + RootShell.quote(hal)).ok()) {
                orig = readAsRoot(ctx, "cat " + RootShell.quote(origCopy));
            } else if (!isElf(orig)) {
                orig = readAsRoot(ctx, "cat " + RootShell.quote(hal));
            }
            if (!isElf(orig) || indexOf(orig, SHIM_MARKER.getBytes(StandardCharsets.US_ASCII)) >= 0) {
                log.append("Обёртка драйвера: не удалось получить оригинал ").append(hal).append(" — пропускаю\n");
                continue;
            }
            byte[] shim;
            try (InputStream is = apk.getInputStream(e)) {
                shim = readAll(is);
            }
            String rel = moduleRelPath(hal);
            String origRel = rel.substring(0, rel.length() - 3) + ORIG_SUFFIX;
            put(zip, rel, shim);
            put(zip, origRel, orig);
            // Контекст SELinux обоих файлов post-fs-data.sh копирует с оригинального драйвера.
            targets.append(rel).append('|').append(hal).append('\n');
            targets.append(origRel).append('|').append(hal).append('\n');
            log.append("Обёртка драйвера: ").append(hal).append('\n');
        }
    }

    private static boolean isElf(byte[] b) {
        return b != null && b.length >= 4 && b[0] == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F';
    }

    /**
     * Выполняет скрипт от root и возвращает его stdout как байты — через пустой файл приложения
     * (так у копии остаётся контекст приложения). null, если скрипт завершился с ошибкой.
     */
    private static byte[] readAsRoot(Context ctx, String script) throws IOException {
        File tmp = new File(ctx.getCacheDir(), "hal-orig.bin");
        try {
            new FileOutputStream(tmp).close();
            RootShell.Result r = RootShell.run("{\n" + script + "\n} > " + RootShell.quote(tmp.getAbsolutePath()));
            if (!r.ok()) return null;
            try (InputStream is = new java.io.FileInputStream(tmp)) {
                return readAll(is);
            }
        } finally {
            tmp.delete();
        }
    }

    static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
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
