package com.better.heybox.yukiprobe

import android.util.Log
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.factory.configure
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule
import java.lang.reflect.Method

@YukiHookLibXposedEntry(
    minApiVersion = 101,
    targetApiVersion = 102,
    staticScope = true,
    scope = ["com.max.xiaoheihe"]
)
object ProbeEntry : YukiHookXposedModule {

    override fun onInit() = configure {
        isDebug = true
    }

    override fun onHook() = encase {
        loadApp("com.max.xiaoheihe") {
            installProbe()
        }
    }

    private fun PackageParam.installProbe() {
        val activity = Class.forName("android.app.Activity", false, hostClassLoader)
        val onResume: Method = activity.getDeclaredMethod("onResume")
        onResume.intercept {
            val result = proceed()
            Log.i(TAG, "probe: onResume on ${instanceOrNull?.javaClass?.name}")
            result
        }
        Log.i(TAG, "probe: hooked $onResume")
    }

    private const val TAG = "YukiProbe"
}
