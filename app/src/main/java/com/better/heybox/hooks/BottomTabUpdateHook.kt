package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.App
import com.better.heybox.MainModule
import com.better.heybox.VersionUtils
import java.lang.reflect.Method

/** Freeze the last cached bottom-tab configuration on the analyzed host build. */
class BottomTabUpdateHook(private val module: MainModule) {
    fun install(cl: ClassLoader) {
        val context = App.resolveAppContext()
        if (!VersionUtils.isHeyboxBuild(context, "1.3.400", 1149L) &&
            !VersionUtils.isHeyboxBuild(context, "1.3.401", 1150L)
        ) {
            module.logd(Log.WARN, MainModule.TAG, "禁止底栏热更新：仅支持小黑盒 1.3.400 (1149) 和 1.3.401 (1150)，已跳过")
            return
        }
        val targets = try {
            val tabs = Class.forName("com.max.xiaoheihe.utils.z0", false, cl)
            val tips = Class.forName("com.max.xiaoheihe.module.account.utils.TipsStateManager", false, cl)
            listOf(
                find(tabs, "W", listOf("java.lang.String", "java.lang.String"), "TabListObj"),
                find(tips, "k", emptyList(), "AppTabsObj"),
                find(tips, "l", emptyList(), "AppTabsObj")
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "禁止底栏热更新：定位目标失败", t)
            return
        }
        if (targets.any { it == null }) {
            module.logd(Log.WARN, MainModule.TAG, "禁止底栏热更新：目标方法签名不匹配，已跳过")
            return
        }
        for (method in targets.filterNotNull()) {
            module.hook(method).intercept { chain ->
                if (module.isEnabled(App.KEY_BLOCK_BOTTOM_TAB_UPDATE, false)) null else chain.proceed()
            }
        }
        module.logd(Log.INFO, MainModule.TAG, "✔ 底栏策略和图标更新拦截已安装")
    }

    private fun find(clazz: Class<*>, name: String, prefix: List<String>, lastType: String): Method? {
        val matches = clazz.declaredMethods.filter { method ->
            val types = method.parameterTypes
            method.name == name && method.returnType == Void.TYPE &&
                types.size == prefix.size + 1 &&
                prefix.indices.all { types[it].name == prefix[it] } &&
                types.last().simpleName == lastType
        }
        return matches.singleOrNull()?.apply { isAccessible = true }
    }
}
