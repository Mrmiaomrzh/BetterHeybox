package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.App
import com.better.heybox.HeyboxTargets
import com.better.heybox.MainModule
import java.lang.reflect.Method

class FakeUsageAccessHook(private val module: MainModule) {

    init {
        sInstance = this
    }

    fun install(cl: ClassLoader) {
        refresh()
        var hooked = 0
        hooked += hookGrant(cl)
        hooked += hookUsageManager(cl)
        if (hooked == 0) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "伪装使用情况权限：未挂上任何宿主方法，功能不生效（可等待 DexKit 兜底）"
            )
        } else {
            module.logd(
                Log.INFO, MainModule.TAG, "✔ 伪装使用情况权限 Hook 已安装（$hooked 处）"
                        + " | 伪装权限=" + (if (sFakePermission) "开" else "关")
                        + " 空数据=" + (if (sEmptyData) "开" else "关")
            )
        }
    }

    private fun hookGrant(cl: ClassLoader): Int {
        val clazz: Class<*>
        try {
            clazz = Class.forName(GRANT_CLASS, false, cl)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "伪装使用情况权限：未找到 $GRANT_CLASS")
            return 0
        }
        var count = 0
        for (name in arrayOf("i", "a")) {
            val method = findBooleanContextMethod(clazz, name) ?: continue
            try {
                module.hook(method).intercept { chain ->
                    if (!sFakePermission) {
                        return@intercept chain.proceed()
                    }
                    val hits = ++sGrantHits
                    if (hits <= 3) {
                        module.logd(
                            Log.INFO, MainModule.TAG,
                            "伪装使用情况权限：判定 $name(Context) 已按「已授权」放行"
                        )
                    }
                    java.lang.Boolean.TRUE
                }
                count++
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "伪装使用情况权限：$name Hook 失败: $t")
            }
        }
        return count
    }

    private fun hookUsageManager(cl: ClassLoader): Int {
        val clazz: Class<*>
        try {
            clazz = Class.forName(USAGE_CLASS, false, cl)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "伪装使用情况权限：未找到 $USAGE_CLASS")
            return 0
        }
        var count = 0
        val listGetter = findListContextMethod(clazz, "r")
        if (listGetter != null) {
            try {
                module.hook(listGetter).intercept { chain ->
                    if (!sFakePermission || !sEmptyData) {
                        return@intercept chain.proceed()
                    }
                    java.util.Collections.emptyList<Any>()
                }
                count++
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "伪装使用情况权限：UsageManager.r Hook 失败: $t")
            }
        }
        val upload = findUploadMethod(clazz)
        if (upload != null) {
            try {
                module.hook(upload).intercept { chain ->
                    if (!sFakePermission || !sEmptyData) {
                        return@intercept chain.proceed()
                    }
                    null
                }
                count++
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "伪装使用情况权限：UsageManager.h Hook 失败: $t")
            }
        }
        return count
    }

    companion object {

        const val GRANT_CLASS = "com.max.xiaoheihe.module.account.utils.f"

        const val USAGE_CLASS = "com.max.xiaoheihe.module.account.utils.UsageManager"

        const val TARGET_FAKE_USAGE_GRANTED = "fake.usage.granted"

        const val TARGET_FAKE_USAGE_LIST = "fake.usage.list"

        const val TARGET_FAKE_USAGE_UPLOAD = "fake.usage.upload"

        @Volatile
        private var sInstance: FakeUsageAccessHook? = null

        @Volatile
        private var sFakePermission = false

        @Volatile
        private var sEmptyData = false

        @Volatile
        private var sGrantHits = 0L

        @JvmStatic
        fun refresh() {
            val instance = sInstance ?: return
            sFakePermission = instance.module.isEnabled(App.KEY_FAKE_USAGE_ACCESS, false)
            sEmptyData = instance.module.isEnabled(App.KEY_FAKE_USAGE_EMPTY_DATA, false)
        }

        private fun findBooleanContextMethod(clazz: Class<*>, name: String): Method? {
            return try {
                val method = clazz.getDeclaredMethod(name, android.content.Context::class.java)
                if (method.returnType != java.lang.Boolean.TYPE) {
                    return null
                }
                method.isAccessible = true
                method
            } catch (t: Throwable) {
                null
            }
        }

        private fun findListContextMethod(clazz: Class<*>, name: String): Method? {
            return try {
                val method = clazz.getDeclaredMethod(name, android.content.Context::class.java)
                if (!MutableList::class.java.isAssignableFrom(method.returnType)) {
                    return null
                }
                method.isAccessible = true
                method
            } catch (t: Throwable) {
                null
            }
        }

        private fun findUploadMethod(clazz: Class<*>): Method? {
            for (method in clazz.declaredMethods) {
                if (method.parameterCount != 5 || method.returnType !== Void.TYPE) {
                    continue
                }
                val types = method.parameterTypes
                if (types[0] !== android.content.Context::class.java
                    || types[2] !== java.lang.Boolean.TYPE
                    || types[3] !== java.lang.Boolean.TYPE
                ) {
                    continue
                }
                method.isAccessible = true
                return method
            }
            return null
        }

        @JvmStatic
        fun diagnostics(): String {
            val sb = StringBuilder()
            sb.append("伪装使用情况权限: ").append(if (sFakePermission) "开" else "关").append('\n')
            sb.append("返回空数据: ").append(if (sEmptyData) "开" else "关").append('\n')
            sb.append("判定类: ").append(GRANT_CLASS).append('\n')
            sb.append("数据类: ").append(USAGE_CLASS).append('\n')
            sb.append("命中次数: ").append(sGrantHits).append('\n')
            sb.append("DexKit 解析:\n")
            for (key in arrayOf(
                TARGET_FAKE_USAGE_GRANTED,
                TARGET_FAKE_USAGE_LIST,
                TARGET_FAKE_USAGE_UPLOAD
            )) {
                sb.append("  ").append(key).append(" = ")
                    .append(HeyboxTargets.sourceOf(key)).append('\n')
            }
            return sb.toString()
        }
    }
}
