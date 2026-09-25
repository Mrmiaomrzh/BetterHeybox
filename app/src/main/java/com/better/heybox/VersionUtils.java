package com.better.heybox;

import android.content.Context;
import android.content.pm.PackageInfo;

/** 从已安装 APK 的 Manifest 读取工作流注入的 versionName。 */
public final class VersionUtils {

    private static final String MODULE_PACKAGE = "com.better.heybox";

    private VersionUtils() {
    }

    public static String getVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(MODULE_PACKAGE, 0);
            if (info.versionName != null && !info.versionName.isEmpty()) {
                return info.versionName;
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    public static long getHeyboxVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(MainModule.TARGET_PKG, 0);
            return android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable ignored) {
            return -1L;
        }
    }
    
    public static boolean isHeyboxBuild(Context context, String versionName, long versionCode) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(MainModule.TARGET_PKG, 0);
            return versionName.equals(info.versionName)
                    && versionCode == getHeyboxVersionCode(context);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isHeyboxBuildAtLeast(Context context, String versionName,
                                               long minVersionCode) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(MainModule.TARGET_PKG, 0);
            if (!versionName.equals(info.versionName)) {
                return false;
            }
            long code = getHeyboxVersionCode(context);
            return code >= 0 && code >= minVersionCode;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
