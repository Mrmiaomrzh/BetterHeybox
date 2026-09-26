package com.better.heybox.yuki

import android.content.SharedPreferences
import com.highcapable.yukihookapi.hook.core.api.priority.YukiHookPriority
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.lang.reflect.Member

class YukiHookBridge(private val param: PackageParam) {

    fun hook(member: Member): HookHandle = HookHandle(member)

    fun frameworkLog(level: Int, tag: String, msg: String, tr: Throwable?) {
        when (level) {
            android.util.Log.DEBUG -> YLog.debug(msg, tr, tag, YLog.EnvType.BOTH)
            android.util.Log.INFO -> YLog.info(msg, tr, tag, YLog.EnvType.BOTH)
            android.util.Log.WARN -> YLog.warn(msg, tr, tag, YLog.EnvType.BOTH)
            else -> YLog.error(msg, tr, tag, YLog.EnvType.BOTH)
        }
    }

    fun remotePreferences(group: String): SharedPreferences? = try {
        val prefs = param.preferences(group)
        prefs.javaClass.getMethod("getCurrent\$yukihook_core").invoke(prefs) as SharedPreferences
    } catch (t: Throwable) {
        null
    }

    inner class HookHandle(private val member: Member) {

        fun intercept(body: YukiChainFunction) {
            with(param) {
                member.intercept(YukiHookPriority.DEFAULT) { chain ->
                    body.apply(YukiChainView(chain))
                }
            }
        }
    }
}
