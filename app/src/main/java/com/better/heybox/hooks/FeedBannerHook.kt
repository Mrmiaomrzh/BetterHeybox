package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.App
import com.better.heybox.MainModule

class FeedBannerHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        try {
            val clazz = Class.forName("com.max.data.model.feeds.FeedsBannerModel", false, cl)
            val getter = clazz.getDeclaredMethod("getAdBannerList")
            module.hook(getter).intercept { chain ->
                val result = chain.proceed()
                if (!module.isEnabled(App.KEY_PROMOTE_AD, true) || result !is List<*>) {
                    return@intercept result
                }
                if (result.isEmpty()) {
                    return@intercept result
                }
                val detail = if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                    ", 条目=" + describeBanners(result)
                } else {
                    ""
                }
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "屏蔽首页广告横幅 原因=FeedsBannerModel.adBannerList 非空 | 数量=" +
                            result.size + detail
                )
                emptyList<Any>()
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 首页广告横幅 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 首页广告横幅 Hook 失败: $t")
        }
    }

    private fun describeBanners(list: List<*>): String {
        val sb = StringBuilder()
        val limit = minOf(list.size, 3)
        for (i in 0 until limit) {
            val item = list[i] ?: continue
            if (sb.isNotEmpty()) {
                sb.append(" / ")
            }
            sb.append(item.javaClass.simpleName)
            sb.append("(ads_id=").append(readString(item, "getAds_id"))
            sb.append(", 标题=").append(readString(item, "getTitle")).append(')')
        }
        if (list.size > limit) {
            sb.append(" 等 ").append(list.size).append(" 条")
        }
        return sb.toString()
    }

    private fun readString(item: Any, name: String): String {
        try {
            return item.javaClass.getMethod(name).invoke(item)?.toString() ?: "-"
        } catch (t: Throwable) {
            return "-"
        }
    }
}
