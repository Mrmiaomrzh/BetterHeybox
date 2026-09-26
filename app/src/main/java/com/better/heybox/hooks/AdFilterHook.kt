package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.App
import com.better.heybox.HeyboxTargets
import com.better.heybox.MainModule
import com.highcapable.yukihookapi.hook.param.HookChain
import java.lang.reflect.Method

class AdFilterHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        hookOpenScreenAd()
        hookFeedAds(cl)
        hookBubbleAd()
        hookCornerAd()
    }

    private fun hookOpenScreenAd() {
        HeyboxTargets.install(PromoteDetector.TARGET_ADS_SPLASH) { method ->
            module.hook(method).intercept { chain ->
                if (module.isEnabled(App.KEY_OPEN_SCREEN, true)) {
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "屏蔽开屏广告 | 目标=" + name(method) +
                                " [来源=" + HeyboxTargets.sourceOf(PromoteDetector.TARGET_ADS_SPLASH) + "]"
                    )
                    return@intercept null
                }
                chain.proceed()
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 开屏广告 Hook 已安装 " + name(method))
        }
    }

    private fun hookBubbleAd() {
        HeyboxTargets.install(PromoteDetector.TARGET_ADS_BUBBLE) { method ->
            module.hook(method).intercept { chain ->
                if (module.isEnabled(App.KEY_BUBBLE_AD, true)) {
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "屏蔽气泡广告 | 目标=" + name(method) +
                                " [来源=" + HeyboxTargets.sourceOf(PromoteDetector.TARGET_ADS_BUBBLE) + "]"
                    )
                    return@intercept null
                }
                chain.proceed()
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 气泡广告 Hook 已安装 " + name(method))
        }
    }

    private fun hookCornerAd() {
        HeyboxTargets.install(PromoteDetector.TARGET_ADS_CORNER) { method ->
            module.hook(method).intercept { chain ->
                if (module.isEnabled(App.KEY_CORNER_AD, true)) {
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "屏蔽角标广告拉取 | 目标=" + name(method) +
                                " [来源=" + HeyboxTargets.sourceOf(PromoteDetector.TARGET_ADS_CORNER) + "]"
                    )
                    return@intercept null
                }
                chain.proceed()
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 角标广告 Hook 已安装 " + name(method))
        }
    }

    private fun hookFeedAds(cl: ClassLoader) {
        try {
            val clazz = Class.forName("com.max.xiaoheihe.network.gson.FeedsContentDeserializer", false, cl)
            val jsonElement = Class.forName("com.google.gson.JsonElement", false, cl)
            val type = Class.forName("java.lang.reflect.Type", false, cl)
            val ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl)
            var installed = 0
            for (methodName in arrayOf("a", "deserialize")) {
                try {
                    val method = clazz.getDeclaredMethod(methodName, jsonElement, type, ctx)
                    module.hook(method).intercept(this::filterFeedAd)
                    installed++
                } catch (ignored: NoSuchMethodException) {
                }
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 信息流广告 Hook 已安装 ($installed 处)")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 信息流广告 Hook 失败", t)
        }
    }

    private fun filterFeedAd(chain: HookChain): Any? {
        if (module.isEnabled(App.KEY_FEED_AD, true)) {
            try {
                val elem = chain.arg(0)
                if (elem != null) {
                    val obj = elem.javaClass.getMethod("getAsJsonObject").invoke(elem)
                    if (obj != null) {
                        val ct = obj.javaClass.getMethod("get", String::class.java).invoke(obj, "content_type")
                        if (ct != null) {
                            val ctStr = ct.javaClass.getMethod("getAsString").invoke(ct) as String
                            if (PromoteDetector.contentTypes().contains(ctStr)) {
                                val detail = if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                                    " | " + describeFeedEntry(obj)
                                } else {
                                    ""
                                }
                                module.logd(
                                    Log.INFO, MainModule.TAG,
                                    "屏蔽信息流广告条目 原因=content_type=$ctStr 属于宿主广告常量表" + detail
                                )
                                return createEmptyFeedObj(chain.instanceOrNull)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "信息流广告判断异常，放行: $t")
            }
        }
        val result = chain.proceed()
        val postFilter = PostFilterHook.get()
        if (postFilter != null && result != null) {
            val replacement = postFilter.onDeserialized(result)
            if (replacement != null) {
                return replacement
            }
        }
        return result
    }

    private fun describeFeedEntry(jsonObject: Any?): String {
        val sb = StringBuilder()
        sb.append("标题=").append(jsonField(jsonObject, "title"))
        sb.append(", 作者=").append(jsonField(jsonObject, "author"))
        sb.append(", link_id=").append(jsonField(jsonObject, "link_id"))
        return sb.toString()
    }

    private fun jsonField(jsonObject: Any?, name: String): String {
        if (jsonObject == null) {
            return "?"
        }
        try {
            val field = jsonObject.javaClass.getMethod("get", String::class.java)
                .invoke(jsonObject, name) ?: return "-"
            val text = field.javaClass.getMethod("getAsString").invoke(field)
            return PromoteDetector.abbreviate(text?.toString())
        } catch (t: Throwable) {
            return "-"
        }
    }

    private fun createEmptyFeedObj(thisObj: Any?): Any? {
        try {
            val cl = thisObj?.javaClass?.classLoader ?: javaClass.classLoader
            val base = Class.forName("com.max.xiaoheihe.bean.news.FeedsContentBaseObj", false, cl)
            val empty = base.getDeclaredConstructor().newInstance()
            base.getMethod("setContent_type", String::class.java).invoke(empty, "0")
            try {
                base.getMethod("setShowDivider", Boolean::class.java).invoke(empty, false)
            } catch (ignored: Throwable) {
            }
            return empty
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "创建空 FeedsContentBaseObj 失败: $t")
            return null
        }
    }

    private fun name(method: Method): String =
        method.declaringClass.name + "#" + method.name + "/" + method.parameterCount
}
