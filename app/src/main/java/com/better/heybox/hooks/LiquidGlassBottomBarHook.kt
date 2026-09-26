package com.better.heybox.hooks

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.better.heybox.MainModule
import com.better.heybox.ViewUtils
import com.better.heybox.liquidglass.BottomToastLifter
import com.better.heybox.liquidglass.LiquidGlassInstaller
import java.lang.reflect.Method

class LiquidGlassBottomBarHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        BottomToastLifter.install()
        LiquidGlassInstaller.installSettingsEntries(cl)
        try {
            val main = Class.forName("com.max.xiaoheihe.MainActivity", false, cl)
            hook(main.getDeclaredMethod("onCreate", Bundle::class.java))
            hook(main.getDeclaredMethod("onResume"))
            try {
                val observer = Class.forName("com.max.xiaoheihe.MainActivity\$j", false, cl)
                val b = ViewUtils.findMethod(observer, "b", Boolean::class.java)
                if (b != null) {
                    hook(b, true, main)
                }
            } catch (ignored: Throwable) {
            }
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "液态玻璃生命周期 Hook 安装失败", t)
        }
    }

    private fun hook(method: Method) = hook(method, false, null)

    private fun hook(method: Method, inner: Boolean, main: Class<*>?) {
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                val self = chain.getThisObject()
                val activity = if (inner) {
                    ViewUtils.findOuter(self, main) as? Activity
                } else {
                    self as? Activity
                }
                if (activity != null) {
                    LiquidGlassInstaller.scheduleInstall(activity)
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "液态玻璃安装调度失败", t)
            }
            result
        }
    }
}
