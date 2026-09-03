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

    /** 检测小黑盒是否为目标构建（versionName 与 versionCode 精确匹配）。 */
    public static boolean isHeyboxBuild(Context context, String versionName, long versionCode) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(MainModule.TARGET_PKG, 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            return versionName.equals(info.versionName) && versionCode == code;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
