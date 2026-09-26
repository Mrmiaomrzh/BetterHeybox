package com.better.heybox

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build

object VersionUtils {

    private const val MODULE_PACKAGE = "com.better.heybox"

    @JvmStatic
    fun getVersionName(context: Context?): String {
        if (context == null) return UNKNOWN
        try {
            val info: PackageInfo =
                context.packageManager.getPackageInfo(MODULE_PACKAGE, 0)
            val name = info.versionName
            if (name != null && name.isNotEmpty()) {
                return name
            }
        } catch (ignored: Throwable) {
        }
        return UNKNOWN
    }

    @JvmStatic
    fun getHeyboxVersionCode(context: Context?): Long {
        if (context == null) return -1L
        try {
            val info: PackageInfo =
                context.packageManager.getPackageInfo(MainModule.TARGET_PKG, 0)
            return if (Build.VERSION.SDK_INT >= 28) info.getLongVersionCode() else info.versionCode.toLong()
        } catch (ignored: Throwable) {
            return -1L
        }
    }

    @JvmStatic
    fun isHeyboxBuild(context: Context?, versionName: String?, versionCode: Long): Boolean {
        if (context == null) return false
        try {
            val info: PackageInfo =
                context.packageManager.getPackageInfo(MainModule.TARGET_PKG, 0)
            return versionName == info.versionName
                    && versionCode == getHeyboxVersionCode(context)
        } catch (ignored: Throwable) {
            return false
        }
    }

    @JvmStatic
    fun isHeyboxBuildAtLeast(
        context: Context?,
        versionName: String?,
        minVersionCode: Long
    ): Boolean {
        if (context == null) return false
        try {
            val info: PackageInfo =
                context.packageManager.getPackageInfo(MainModule.TARGET_PKG, 0)
            if (versionName != info.versionName) {
                return false
            }
            val code = getHeyboxVersionCode(context)
            return code >= 0 && code >= minVersionCode
        } catch (ignored: Throwable) {
            return false
        }
    }

    private const val UNKNOWN = "unknown"
}
