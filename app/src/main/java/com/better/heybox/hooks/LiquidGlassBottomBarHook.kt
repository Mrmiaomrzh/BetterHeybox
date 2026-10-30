package com.better.heybox.hooks

import android.app.Activity
import android.util.Log
import com.better.heybox.MainModule
import com.better.heybox.liquidglass.BottomToastLifter
import com.better.heybox.liquidglass.LiquidGlassInstaller

class LiquidGlassBottomBarHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        BottomToastLifter.install()
        LiquidGlassInstaller.installSettingsEntries(cl)

        val callback: (Activity) -> Unit = { activity ->
            try {
                LiquidGlassInstaller.scheduleInstall(activity)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "液态玻璃安装调度失败", t)
            }
        }
        MainActivityHookDispatcher.addOnCreate(module, cl, "液态玻璃", callback)
        MainActivityHookDispatcher.addOnResume(module, cl, "液态玻璃", callback)
        MainActivityHookDispatcher.addTabObserver(module, cl, "液态玻璃", callback)
        module.logd(Log.INFO, MainModule.TAG, "✔ 液态玻璃生命周期 Hook 已安装")
    }
}
