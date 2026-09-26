package com.better.heybox.hooks

import android.net.Uri
import android.util.Log
import com.better.heybox.App
import com.better.heybox.MainModule

class ShareLinkPurifyHook(private val module: MainModule?) {

    fun install(cl: ClassLoader) {
        var installed = 0
        val names = StringBuilder()
        for (className in TARGET_CLASSES) {
            val clazz = try {
                Class.forName(className, false, cl)
            } catch (t: Throwable) {
                continue
            }
            var hooked = false
            for (methodName in arrayOf("getShareUrl", "getShare_url", "getUrl")) {
                try {
                    val method = clazz.getDeclaredMethod(methodName)
                    if (method.returnType != String::class.java) {
                        continue
                    }
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (module.isEnabled(App.KEY_PURIFY_SHARE_LINK, true) && result is String) {
                            return@intercept purify(result)
                        }
                        result
                    }
                    hooked = true
                } catch (ignored: NoSuchMethodException) {
                }
            }
            if (hooked) {
                installed++
                if (names.isNotEmpty()) {
                    names.append(", ")
                }
                names.append(className.substring(className.lastIndexOf('.') + 1))
            }
        }
        if (installed > 0) {
            module?.logd(
                Log.INFO, MainModule.TAG,
                "✔ 分享链接净化 Hook 已安装: $installed 个出口 [$names]"
            )
        } else {
            module?.logd(Log.WARN, MainModule.TAG, "✘ 分享链接净化 Hook 未命中任何分享出口")
        }
    }

    fun purify(url: String?): String? {
        if (url == null) {
            return url
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url
        }
        if (!isHeyboxHost(url)) {
            return url
        }
        val queryStart = url.indexOf('?')
        if (queryStart < 0) {
            return url
        }
        val fragmentStart = url.indexOf('#', queryStart)
        val query = if (fragmentStart < 0) {
            url.substring(queryStart + 1)
        } else {
            url.substring(queryStart + 1, fragmentStart)
        }
        if (query.isEmpty()) {
            return url
        }

        val kept = StringBuilder()
        val removed = ArrayList<String>()
        for (pair in query.split("&")) {
            if (pair.isEmpty()) {
                continue
            }
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            val normalized = Uri.decode(name).lowercase()
            if (isTrackingParam(normalized)) {
                removed.add(normalized)
            } else {
                if (kept.isNotEmpty()) {
                    kept.append('&')
                }
                kept.append(pair)
            }
        }
        if (removed.isEmpty()) {
            return url
        }

        val out = StringBuilder()
        if (kept.isNotEmpty()) {
            out.append(url, 0, queryStart + 1).append(kept)
        } else {
            out.append(url, 0, queryStart)
        }
        if (fragmentStart >= 0) {
            out.append(url, fragmentStart, url.length)
        }
        module?.logd(Log.INFO, MainModule.TAG, "净化分享链接: 已去除 $removed ← $url")
        return out.toString()
    }

    private fun isTrackingParam(name: String): Boolean =
        name.startsWith("utm_") || BLACKLIST.contains(name)

    private fun isHeyboxHost(url: String): Boolean {
        try {
            val host = Uri.parse(url).host ?: return false
            val lower = host.lowercase()
            return lower == HEYBOX_HOST_SUFFIX || lower.endsWith("." + HEYBOX_HOST_SUFFIX)
        } catch (t: Throwable) {
            return false
        }
    }

    companion object {
        private val TARGET_CLASSES = arrayOf(
            "com.max.data.model.community.LinkPostForwardModel",
            "com.max.data.model.community.CommentForwardModel",
            "com.max.data.model.community.GameCommentForwardModel",
            "com.max.data.model.community.PostShareContentModel",
            "com.max.data.model.game.GameDetailModel",
            "com.max.data.model.game.GameDetailScreenShotModel",
            "com.max.data.model.game.GameReviewShareContentModel",
            "com.max.data.model.common.CommonContentModel",
            "com.max.data.model.share.ArticleModel",
            "com.max.data.model.share.MeModel",
            "com.max.data.model.share.WebViewModel",
            "com.max.data.bean.community.LinkShareInfoDto",
            "com.max.data.model.share.IAction\$CopyAction",
            "com.max.hbshare.bean.HBShareProtocolData"
        )

        private const val HEYBOX_HOST_SUFFIX = "xiaoheihe.cn"

        private val BLACKLIST = listOf(
            "h_camp",
            "h_session_id",
            "new_post_share_style",
            "h_src",
            "sid",
            "share_app_id",
            "share_strategys",
            "share_xy_from", "sh_from", "share_from", "share_channel", "share_xy",
            "web_sign",
            "identify",
            "heybox_id", "user_id", "userid",
            "did", "device_id",
            "from", "spm", "traceid", "request_id",
            "gclid", "fbclid"
        )
    }
}
