package com.better.heybox

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

object GlassProvider {

    const val HBMOD_PACKAGE = "com.hbmod.liquidglass"
    const val PROVIDER_OWN = "betterheybox"
    const val PROVIDER_HBMOD = "hbmod"

    @JvmStatic
    fun isHbmodInstalled(context: Context): Boolean {
        return try {
            val pm: PackageManager = context.packageManager
            val info: PackageInfo = pm.getPackageInfo(HBMOD_PACKAGE, 0)
            info != null
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun prefersHbmod(module: MainModule): Boolean {
        return try {
            PROVIDER_HBMOD == module.getString(App.KEY_GLASS_PROVIDER, "")
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun prefersHbmod(context: Context): Boolean {
        return try {
            HeyboxPrefs.init(context)
            PROVIDER_HBMOD == HeyboxPrefs.getString(App.KEY_GLASS_PROVIDER, "")
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun providerLabel(provider: String?): String =
        if (PROVIDER_HBMOD == provider) "小黑盒液态玻璃模块" else "BetterHeybox"
}
