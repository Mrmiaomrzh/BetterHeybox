package com.better.heybox.hooks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.better.heybox.MainModule
import com.better.heybox.watch.HttpBridge
import com.better.heybox.watch.WatchEngine
import com.better.heybox.watch.WatchFetcher
import com.better.heybox.watch.WatchOutput
import com.better.heybox.watch.WatchSeen
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class WatchHook(private val module: MainModule) {

    private val hits = StringBuilder()

    fun install(cl: ClassLoader) {
        WatchEngine.init(module)
        WatchFetcher.init(module)
        WatchOutput.init(module)
        HttpBridge.init(module)
        WatchSeen.init(module)
        hookAppOpen(cl)
        hookPushArrive(cl)
        hookOkHttp(cl)
        hookFeedJson(cl)
        module.logd(
            Log.INFO, MainModule.TAG,
            "✔ 动态推送 Hook 安装完成：" + (if (hits.length == 0) "无命中" else hits.toString())
        )
    }

    private fun hit(label: String, target: String) {
        if (hits.length > 0) {
            hits.append(" / ")
        }
        hits.append(label).append('=').append(target)
    }


    private fun hookAppOpen(cl: ClassLoader) {
        for (cn in OPEN_HOLDERS) {
            try {
                val main = Class.forName(cn, false, cl)
                if (installOpenHooks(main)) {
                    hit("打开检查", cn)
                    module.logd(Log.INFO, MainModule.TAG, "✔ 动态推送：打开检查 Hook 已安装 ($cn)")
                    return
                }
            } catch (ignored: Throwable) {
            }
        }
        module.logd(Log.WARN, MainModule.TAG, "✘ 动态推送：打开检查 Hook 失败（候选全部落空）")
    }

    private fun installOpenHooks(main: Class<*>): Boolean {
        var any = false
        try {
            val onCreate = findMethod(main, "onCreate", Bundle::class.java)
            if (onCreate != null) {
                module.hook(onCreate).intercept { chain ->
                    val result = chain.proceed()
                    notifyOpen(chain.getThisObject())
                    result
                }
            }
            val onResume = findMethod(main, "onResume")
            if (onResume != null) {
                module.hook(onResume).intercept { chain ->
                    val result = chain.proceed()
                    notifyOpen(chain.getThisObject())
                    result
                }
                any = true
            }
        } catch (ignored: Throwable) {
        }
        return any
    }

    private fun notifyOpen(self: Any?) {
        try {
            val activity = self as? Activity
            if (activity != null) {
                WatchEngine.onAppOpen(activity)
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "动态推送：打开检查异常 $t")
        }
    }


    private fun hookPushArrive(cl: ClassLoader) {
        for (cn in PUSH_HOLDERS) {
            if (installPushHooks(cl, cn)) {
                return
            }
        }
        module.logd(Log.WARN, MainModule.TAG, "✘ 动态推送：推送搭便车 Hook 失败（候选全部落空）")
    }

    private fun installPushHooks(cl: ClassLoader, cn: String): Boolean {
        try {
            val svc = Class.forName(cn, false, cl)
            var installed = 0
            for (m in svc.declaredMethods) {
                val n = m.name
                if (n != "onReceiveMessageData" && n != "onNotificationMessageArrived"
                    && n != "onNotificationMessageClicked"
                ) {
                    continue
                }
                module.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val ctx = chain.getArg(0)
                        if (ctx is Context) {
                            WatchEngine.onPushArrived(ctx)
                        }
                    } catch (ignored: Throwable) {
                    }
                    result
                }
                installed++
            }
            if (installed > 0) {
                hit("推送搭便车", cn + "×" + installed)
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "✔ 动态推送：推送搭便车 Hook 已安装 ($cn, $installed 处)"
                )
                return true
            }
        } catch (ignored: Throwable) {
        }
        return false
    }


    private fun hookOkHttp(cl: ClassLoader) {
        var installed = 0
        for (cn in HttpBridge.CLIENT_HOLDERS) {
            try {
                val holder = Class.forName(cn, false, cl)
                for (ctor: Constructor<*> in holder.declaredConstructors) {
                    if (ctor.parameterTypes.size < 2) {
                        continue
                    }
                    module.hook(ctor).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            HttpBridge.captureIfClient(chain.getArg(0), chain.getArg(1), cl)
                        } catch (ignored: Throwable) {
                        }
                        result
                    }
                    installed++
                    break
                }
                if (installed > 0) {
                    hit("网络栈", cn)
                    module.logd(Log.INFO, MainModule.TAG, "✔ 动态推送：HTTP 客户端捕获 Hook 已安装 ($cn)")
                    break
                }
            } catch (ignored: Throwable) {
            }
        }
        if (installed == 0) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 动态推送：未找到可用的 OkHttp 载体类，主动拉取将不可用")
        }
    }


    private fun hookFeedJson(cl: ClassLoader) {
        var installed = 0
        for (cn in FEED_DESERIALIZERS) {
            try {
                val d = Class.forName(cn, false, cl)
                val jsonElement = Class.forName("com.google.gson.JsonElement", false, cl)
                val type = Class.forName("java.lang.reflect.Type", false, cl)
                val ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl)
                for (name in arrayOf("a", "deserialize")) {
                    try {
                        val m = d.getDeclaredMethod(name, jsonElement, type, ctx)
                        module.hook(m).intercept { chain ->
                            val result = chain.proceed()
                            try {
                                WatchEngine.onFeedJson(chain.getArg(0))
                            } catch (ignored: Throwable) {
                            }
                            result
                        }
                        installed++
                    } catch (ignored: NoSuchMethodException) {
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        if (installed > 0) {
            hit("信息流", "$installed 处")
        }
        module.logd(Log.INFO, MainModule.TAG, "✔ 动态推送：信息流命中 Hook 已安装 ($installed 处)")
    }


    companion object {
        private val OPEN_HOLDERS = arrayOf(
            "com.max.xiaoheihe.MainActivity",
            "com.max.hbcommon.base.BaseActivity",
        )

        private val PUSH_HOLDERS = arrayOf(
            "com.max.hbcommon.push.HBGTIntentService",
            "com.igexin.sdk.GTIntentService",
        )

        private val FEED_DESERIALIZERS = arrayOf(
            "com.max.data.deserializer.FeedsFlowItemModelDeserializer",
            "com.max.xiaoheihe.network.gson.FeedsContentDeserializer",
        )

        private fun findMethod(c: Class<*>, name: String, vararg params: Class<*>): Method? {
            var walk: Class<*>? = c
            while (walk != null && walk != Any::class.java) {
                try {
                    val m = walk.getDeclaredMethod(name, *params)
                    if (m != null) {
                        return m
                    }
                } catch (ignored: Throwable) {
                }
                walk = walk.superclass
            }
            return null
        }
    }
}
