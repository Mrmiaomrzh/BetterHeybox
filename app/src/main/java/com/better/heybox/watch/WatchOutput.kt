package com.better.heybox.watch

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.better.heybox.MainModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.HashMap

object WatchOutput {

    const val CHANNEL_ID = "betterheybox_watch"
    private const val CHANNEL_NAME = "动态推送"

    private const val HOST_ROUTER = "com.max.xiaoheihe.RouterActivity"

    private const val HOST_POST_PAGE =
        "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity"

    @Volatile private var sModule: MainModule? = null

    @JvmStatic
    fun init(module: MainModule?) {
        sModule = module
    }


    private fun ensureChannel(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
                return
            }
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            )
            ch.description = "关注的作者发布新动态 / 关键词命中提醒"
            nm.createNotificationChannel(ch)
        } catch (t: Throwable) {
            log(Log.WARN, "创建通知渠道失败: $t")
        }
    }

    @JvmStatic
    fun notifyPost(ctx: Context?, item: WatchItem): Boolean {
        if (ctx == null) return false
        return try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            var intent = buildPostIntent(ctx, item)
            if (intent == null) {
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl()))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val code = item.linkId?.hashCode() ?: 0
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            val pi = PendingIntent.getActivity(ctx, code, intent, flags)
            val b = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(item.displayTitle())
                .setContentText(item.displayText().replace('\n', ' '))
                .setStyle(Notification.BigTextStyle().bigText(item.displayText()))
                .setAutoCancel(true)
                .setContentIntent(pi)
            nm.notify(code, b.build())
            log(Log.INFO, "已发通知：" + item.displayTitle())
            true
        } catch (t: Throwable) {
            log(Log.WARN, "发通知失败: $t")
            false
        }
    }


    @JvmStatic
    fun bannerOrToast(activity: Activity?, item: WatchItem) {
        if (activity == null) {
            return
        }
        try {
            WatchBanner.show(activity, item, Runnable { openPost(activity, item) })
        } catch (t: Throwable) {
            try {
                Toast.makeText(activity, "🔔 " + item.displayTitle(), Toast.LENGTH_LONG).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    @JvmStatic
    fun bannerSummary(activity: Activity?, title: String, sub: String?, onClick: Runnable?) {
        if (activity == null) {
            return
        }
        try {
            WatchBanner.showSummary(activity, title, sub, onClick)
        } catch (t: Throwable) {
            log(Log.WARN, "汇总横幅失败: $t")
        }
    }

    @JvmStatic
    fun openPost(ctx: Context?, item: WatchItem?) {
        if (ctx == null || item == null) {
            return
        }
        val i = buildPostIntent(ctx, item)
        if (i != null) {
            try {
                startActivity(ctx, i)
                return
            } catch (t: Throwable) {
                log(Log.WARN, "应用内打开帖子失败，回退浏览器: $t")
            }
        }
        try {
            startActivity(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl())))
        } catch (t: Throwable) {
            log(Log.WARN, "打开帖子失败: $t")
        }
    }

    private fun startActivity(ctx: Context, intent: Intent) {
        if (ctx !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    private fun buildPostIntent(ctx: Context?, item: WatchItem?): Intent? {
        if (ctx == null || item == null || item.linkId.isNullOrEmpty()) {
            return null
        }
        val cl = ctx.classLoader
        val router = loadHostClass(cl, HOST_ROUTER)
        if (router != null) {
            return Intent(ctx, router).setData(Uri.parse(item.webUrl()))
        }
        val postPage = loadHostClass(cl, HOST_POST_PAGE)
        if (postPage != null) {
            return Intent(ctx, postPage).putExtra("link_id", item.linkId)
        }
        log(Log.WARN, "宿主路由页与帖子详情页均未找到，本次改走浏览器: " + item.linkId)
        return null
    }

    private fun loadHostClass(cl: ClassLoader?, name: String): Class<*>? {
        if (cl != null) {
            try {
                return Class.forName(name, false, cl)
            } catch (ignored: Throwable) {
            }
        }
        return try {
            Class.forName(name)
        } catch (ignored: Throwable) {
            null
        }
    }


    @JvmStatic
    fun testBanner(activity: Activity?, item: WatchItem?): Boolean {
        if (activity == null || item == null) {
            return false
        }
        return try {
            WatchBanner.show(activity, item, Runnable {
                try {
                    Toast.makeText(activity, "横幅点击回调正常", Toast.LENGTH_SHORT).show()
                } catch (ignored: Throwable) {
                }
            })
            true
        } catch (t: Throwable) {
            log(Log.WARN, "测试横幅失败: $t")
            false
        }
    }

    @JvmStatic
    fun pushAll(cfg: WatchConfig, item: WatchItem): Int {
        if (!cfg.pushEnabled) {
            return 0
        }
        var ok = 0
        val title = "【小黑盒】" + item.displayTitle()
        val body = item.displayText() + "\n" + item.webUrl()
        if (cfg.dingtalk.isNotEmpty() && sendDingtalk(cfg.dingtalk, title, body)) {
            ok++
        }
        if (cfg.wxpusher.isNotEmpty() && sendWxPusher(cfg.wxpusher, title, body)) {
            ok++
        }
        if (cfg.onebot.isNotEmpty() && sendOneBot(cfg.onebot, item)) {
            ok++
        }
        if (cfg.custom.isNotEmpty() && sendCustom(cfg.custom, item)) {
            ok++
        }
        return ok
    }

    private fun sendDingtalk(cfg: String, title: String, text: String): Boolean {
        val url =
            if (cfg.startsWith("http")) cfg
            else "https://oapi.dingtalk.com/robot/send?access_token=$cfg"
        return try {
            val o = JSONObject()
            o.put("msgtype", "markdown")
            val md = JSONObject()
            md.put("title", title)
            md.put("text", "### $title\n\n$text")
            o.put("markdown", md)
            postJson(url, o.toString()) != null
        } catch (t: Throwable) {
            log(Log.WARN, "钉钉推送失败: $t")
            false
        }
    }

    private fun sendWxPusher(cfg: String, title: String, text: String): Boolean {
        return try {
            val parts = cfg.split(Regex("\\|"))
            val appToken = parts[0].trim()
            val o = JSONObject()
            o.put("appToken", appToken)
            o.put("content", text)
            o.put("summary", title)
            o.put("contentType", 1)
            if (parts.size > 1) {
                val second = parts[1].trim()
                if (second.startsWith("uid:") || second.startsWith("UID:")) {
                    val uids = JSONArray()
                    uids.put(second.substring(4).trim())
                    o.put("uids", uids)
                } else {
                    val topics = JSONArray()
                    topics.put(second.toInt())
                    o.put("topicIds", topics)
                }
            }
            postJson("https://wxpusher.zjiecode.com/api/send/message", o.toString()) != null
        } catch (t: Throwable) {
            log(Log.WARN, "WxPusher 推送失败: $t")
            false
        }
    }

    private fun sendOneBot(cfg: String, item: WatchItem): Boolean {
        return try {
            val parts = cfg.split(Regex("\\|"))
            val base = parts[0].trim().replace(Regex("/+$"), "")
            val target = if (parts.size > 1) parts[1].trim() else ""
            val token = if (parts.size > 2) parts[2].trim() else ""
            val o = JSONObject()
            val action: String
            if (target.startsWith("private:")) {
                action = "/send_private_msg"
                o.put("user_id", target.substring(8).trim().toLong())
            } else {
                action = "/send_group_msg"
                o.put("group_id", (if (target.isEmpty()) "0" else target).toLong())
            }
            o.put("message", "【小黑盒】" + item.displayTitle() + "\n" + item.webUrl())
            o.put("auto_escape", true)
            val headers = HashMap<String, String>()
            if (token.isNotEmpty()) {
                headers["Authorization"] = "Bearer $token"
            }
            postJson(base + action, o.toString(), headers) != null
        } catch (t: Throwable) {
            log(Log.WARN, "AstrBot / OneBot 推送失败: $t")
            false
        }
    }

    private fun sendCustom(cfg: String, item: WatchItem): Boolean {
        return try {
            val url = cfg
                .replace("{title}", enc(item.displayTitle()))
                .replace("{author}", enc(item.authorName))
                .replace("{link}", enc(item.webUrl()))
                .replace("{desc}", enc(item.desc))
                .replace("{id}", enc(item.linkId))
            if (url.startsWith("http")) {
                postJson(url, buildDefaultPayload(item)) != null
            } else {
                false
            }
        } catch (t: Throwable) {
            log(Log.WARN, "自定义推送失败: $t")
            false
        }
    }

    private fun buildDefaultPayload(item: WatchItem): String {
        val o = JSONObject()
        o.put("title", item.displayTitle())
        o.put("author", item.authorName)
        o.put("link", item.webUrl())
        o.put("desc", item.desc)
        o.put("link_id", item.linkId)
        o.put("hit", item.hit)
        return o.toString()
    }

    private fun enc(s: String?): String {
        return try {
            URLEncoder.encode(s ?: "", "UTF-8")
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun postJson(url: String, json: String): String? = postJson(url, json, null)

    private fun postJson(url: String, json: String, headers: Map<String, String>?): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (headers != null) {
                for ((key, value) in headers) {
                    c.setRequestProperty(key, value)
                }
            }
            c.outputStream.use { os: OutputStream ->
                os.write(json.toByteArray(StandardCharsets.UTF_8))
            }
            val code = c.responseCode
            if (code in 200..299) {
                "ok"
            } else {
                log(Log.WARN, "推送 HTTP $code $url")
                null
            }
        } catch (t: Throwable) {
            log(Log.WARN, "推送请求异常 $t")
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun log(level: Int, msg: String) {
        sModule?.logd(level, MainModule.TAG, "[动态推送] $msg")
    }

    @JvmStatic
    fun testPayload(): MutableMap<String, String> {
        val m = HashMap<String, String>()
        m["title"] = "测试消息"
        m["text"] = "BetterHeybox 动态推送测试"
        return m
    }
}
