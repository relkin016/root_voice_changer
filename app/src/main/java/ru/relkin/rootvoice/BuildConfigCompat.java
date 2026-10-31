package ru.relkin.rootvoice;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Версия приложения из PackageManager (BuildConfig отключён в AGP 8 по умолчанию). */
final class BuildConfigCompat {
    private BuildConfigCompat() {
    }

    static String versionName(Context ctx) {
        PackageInfo pi = info(ctx);
        return pi != null && pi.versionName != null ? pi.versionName : "0";
    }

    static long versionCode(Context ctx) {
        PackageInfo pi = info(ctx);
        return pi != null ? pi.getLongVersionCode() : 0;
    }

    private static PackageInfo info(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
