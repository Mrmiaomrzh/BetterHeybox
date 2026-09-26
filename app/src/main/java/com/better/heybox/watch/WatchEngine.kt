package com.better.heybox.watch

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.MainModule
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

object WatchEngine {

    @Volatile
    private var sModule: MainModule? = null

    private val POOL: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "betterheybox-watch")
        t.isDaemon = true
        t
    }
    private val sRunning = AtomicBoolean(false)

    @Volatile
    private var sActivity: WeakReference<Activity> = WeakReference(null)

    @Volatile
    private var sLastResult: String = "尚未检查"

    @JvmStatic
    fun init(module: MainModule) {
        sModule = module
    }

    @JvmStatic
    fun lastResult(): String = sLastResult


    @JvmStatic
    fun onAppOpen(activity: Activity?) {
        if (activity != null) {
            sActivity = WeakReference(activity)
        }
        maybeCheck(activity, "打开小黑盒", false)
    }

    @JvmStatic
    fun onPushArrived(context: Context?) {
        maybeCheck(context, "收到推送", false)
    }

    @JvmStatic
    fun onNetworkCaptured() {
        val module = sModule ?: return
        try {
            val cfg = WatchConfig.load(module)
            if (!cfg.enabled || !cfg.hasTargets()) {
                return
            }
            val ctx = App.resolveAppContext() ?: return
            log(Log.INFO, "网络栈就绪，补跑一次检查")
            maybeCheck(ctx, "网络就绪", true)
        } catch (t: Throwable) {
            log(Log.WARN, "补跑检查失败: " + t)
        }
    }

    @JvmStatic
    fun checkNow(activity: Activity?, announce: Boolean) {
        maybeCheck(activity, "手动检查", true)
        if (announce) {
            sLastResult = "已触发检查，结果见日志"
        }
    }

    private fun maybeCheck(context: Context?, reason: String, force: Boolean) {
        val module = sModule
        if (module == null || context == null) {
            return
        }
        try {
            val cfg = WatchConfig.load(module)
            if (!cfg.enabled) {
                return
            }
            if (!cfg.hasTargets()) {
                sLastResult = "未配置关注对象或关键词"
                return
            }
            if (!cfg.hasAnyChannel()) {
                sLastResult = "未开启任何提醒方式"
                return
            }
            val now = System.currentTimeMillis()
            val elapsed = now - WatchSeen.lastCheck()
            if (!force && elapsed < cfg.intervalMin * 60000L) {
                return
            }
            if (!sRunning.compareAndSet(false, true)) {
                return
            }
            WatchSeen.touchCheck()
            val appCtx: Context = context.applicationContext ?: context
            POOL.execute {
                try {
                    run(cfg, appCtx, reason)
                } catch (t: Throwable) {
                    log(Log.WARN, "检查异常: " + t)
                    sLastResult = "检查异常：" + t
                } finally {
                    sRunning.set(false)
                }
            }
        } catch (t: Throwable) {
            sRunning.set(false)
            log(Log.WARN, "触发检查失败: " + t)
        }
    }


    private const val MAX_STREAM_KEYWORDS = 5

    private fun run(cfg: WatchConfig, ctx: Context, reason: String) {
        log(
            Log.INFO, "开始检查（" + reason + "）：关注 " + cfg.users.size +
                " 人，关键词 " + cfg.keywords.size + " 个，话题 " + cfg.topics.size +
                " 个，时间窗 " + cfg.windowText() +
                (if (cfg.streamFetch) "，拉流开启" else "")
        )
        val found = ArrayList<WatchItem>()
        if (!HttpBridge.ready()) {
            log(Log.WARN, "尚未捕获宿主网络栈，跳过主动拉取（可先在小黑盒里刷新一次信息流以完成捕获）")
        } else {
            for (raw in cfg.users) {
                val uid = WatchConfig.parseUserId(raw)
                if (uid == null) {
                    log(Log.WARN, "无法解析 userid：" + raw)
                    continue
                }
                val items = WatchFetcher.fetchUserPosts(uid, WatchFetcher.FETCH_LIMIT)
                if (!WatchSeen.isBaselined(uid)) {
                    var recorded = 0
                    for (it in items) {
                        if (WatchSeen.markNew(it.linkId)) {
                            recorded++
                        }
                    }
                    WatchSeen.markBaselined(uid)
                    log(
                        Log.INFO, "首次检查「" + displayName(raw) + "」：登记 " + recorded +
                            " 条历史帖作为基线，不推送（下次起只推新增）"
                    )
                    sleep(800)
                    continue
                }
                for (it in items) {
                    if (uid == it.authorId || it.authorId == null || it.authorId.isEmpty()) {
                        found.add(
                            WatchItem(
                                it.linkId, it.title, it.desc, it.authorId,
                                it.authorName, it.createAt, "user", displayName(raw)
                            )
                        )
                    }
                }
                sleep(800)
            }
        }
        if (cfg.streamFetch) {
            if (!HttpBridge.ready()) {
                log(Log.WARN, "拉流需要宿主网络栈，本轮跳过（先在小黑盒里刷新一次信息流）")
            } else {
                streamFetch(cfg, found)
            }
        } else if (cfg.topics.isNotEmpty()) {
            log(
                Log.INFO, "已配置 " + cfg.topics.size +
                    " 个关注话题，但「话题/关键词拉流」未开启，本轮只在被动命中时匹配"
            )
        }
        deliver(cfg, ctx, WatchFetcher.dedupe(found), "主动拉取")
    }

    private fun streamFetch(cfg: WatchConfig, found: MutableList<WatchItem>) {
        var used = 0
        for (kw in cfg.keywords) {
            if (used >= MAX_STREAM_KEYWORDS) {
                log(Log.INFO, "关键词拉流已达单轮上限 " + MAX_STREAM_KEYWORDS + " 个，其余留到下一轮")
                break
            }
            if (kw.startsWith("regex:")) {
                continue
            }
            used++
            val key = "kw:" + kw.lowercase(Locale.getDefault())
            val items = WatchFetcher.fetchKeywordPosts(kw, WatchFetcher.FETCH_LIMIT)
            if (!WatchSeen.isBaselined(key)) {
                for (it in items) {
                    WatchSeen.markNew(it.linkId)
                }
                WatchSeen.markBaselined(key)
                log(Log.INFO, "关键词「" + kw + "」首轮基线：登记 " + items.size + " 条，不推送（下次起只推新增）")
                sleep(800)
                continue
            }
            for (it in items) {
                found.add(
                    WatchItem(
                        it.linkId, it.title, it.desc, it.authorId, it.authorName,
                        it.createAt, "keyword", kw
                    )
                )
            }
            sleep(800)
        }
        for (raw in cfg.topics) {
            val id = WatchConfig.parseTopicId(raw)
            val name = WatchConfig.topicName(raw)
            val key = "topic:" + (if (id != null) id else name)
            val items = WatchFetcher.fetchTopicPosts(id, name, WatchFetcher.FETCH_LIMIT)
            if (!WatchSeen.isBaselined(key)) {
                for (it in items) {
                    WatchSeen.markNew(it.linkId)
                }
                WatchSeen.markBaselined(key)
                log(Log.INFO, "话题「" + name + "」首轮基线：登记 " + items.size + " 条，不推送（下次起只推新增）")
                sleep(800)
                continue
            }
            for (it in items) {
                found.add(
                    WatchItem(
                        it.linkId, it.title, it.desc, it.authorId, it.authorName,
                        it.createAt, "topic", name
                    )
                )
            }
            sleep(800)
        }
    }

    @JvmStatic
    fun debugPushLatest(activity: Activity?, limit: Int) {
        val module = sModule ?: return
        val ctx: Context? = if (activity != null) activity else App.resolveAppContext()
        POOL.execute { debugPushInternal(module, activity, ctx, limit) }
    }

    private fun debugPushInternal(
        module: MainModule, activity: Activity?, ctx: Context?, limit: Int,
    ) {
        try {
            val cfg = WatchConfig.load(module)
            if (cfg.users.isEmpty()) {
                toast(activity, "请先在「关注对象」里配置至少一个 userid")
                return
            }
            if (!HttpBridge.ready()) {
                log(Log.WARN, "调试推送：HTTP 客户端尚未捕获（先在小黑盒里刷新一次信息流）")
                toast(activity, "网络栈未就绪：先在小黑盒里刷新一次信息流再试")
                return
            }
            var found = ArrayList<WatchItem>()
            for (raw in cfg.users) {
                if (found.size >= limit) {
                    break
                }
                val uid = WatchConfig.parseUserId(raw)
                if (uid == null) {
                    continue
                }
                log(Log.INFO, "调试推送：拉取 userid=" + uid)
                for (it in WatchFetcher.fetchUserPosts(uid, WatchFetcher.FETCH_LIMIT)) {
                    if (it.authorId != null && it.authorId.isNotEmpty() && uid != it.authorId) {
                        continue
                    }
                    found.add(
                        WatchItem(
                            it.linkId, it.title, it.desc, it.authorId,
                            it.authorName, it.createAt, "user", displayName(raw)
                        )
                    )
                    if (found.size >= limit) {
                        break
                    }
                }
                sleep(500)
            }
            if (found.isEmpty()) {
                log(Log.WARN, "调试推送：没取到任何帖子（检查日志里的 HTTP 状态与响应片段）")
                toast(activity, "没取到帖子，详见模块日志")
                return
            }
            found.sortWith(compareByDescending { it.createAt })
            if (found.size > limit) {
                found = ArrayList(found.subList(0, limit))
            }
            for (it in found) {
                log(
                    Log.INFO, "调试推送：" + it.displayTitle() +
                        "（" + (if (it.createAt > 0) crateTime(it.createAt) else "无时间") + "）"
                )
                WatchSeen.markNew(it.linkId)
                if (ctx != null && cfg.notify) {
                    WatchOutput.notifyPost(ctx, it)
                }
                if (cfg.pushEnabled) {
                    WatchOutput.pushAll(cfg, it)
                }
            }
            val a = if (activity != null) activity else sActivity.get()
            if (a != null && cfg.banner) {
                val first = found[0]
                if (found.size > 1) {
                    val sb = StringBuilder()
                    for (i in found.indices) {
                        if (i > 0) {
                            sb.append('\n')
                        }
                        sb.append("· ").append(found[i].displayTitle())
                    }
                    WatchOutput.bannerSummary(
                        a, "🔔 调试推送 " + found.size + " 条", sb.toString()
                    ) { WatchOutput.openPost(a, first) }
                } else {
                    WatchOutput.bannerOrToast(a, first)
                }
            }
            toast(activity, "已推送 " + found.size + " 条（详见模块日志）")
        } catch (t: Throwable) {
            log(Log.WARN, "调试推送失败: " + t)
            toast(activity, "调试推送失败：" + t)
        }
    }

    private fun crateTime(epochSec: Long): String {
        return try {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochSec * 1000L))
        } catch (ignored: Throwable) {
            stringify(epochSec)
        }
    }

    private fun toast(activity: Activity?, msg: String) {
        if (activity == null) {
            return
        }
        activity.runOnUiThread {
            try {
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            } catch (ignored: Throwable) {
            }
        }
    }


    @JvmStatic
    fun onFeedJson(elem: Any?) {
        val module = sModule
        if (module == null || elem == null) {
            return
        }
        try {
            val cfg = WatchConfig.load(module)
            if (!cfg.enabled || !cfg.hasTargets()) {
                return
            }
            val obj = elem.javaClass.getMethod("getAsJsonObject").invoke(elem)
            if (obj == null) {
                return
            }
            var linkId = jsonStr(obj, "link_id", "linkid")
            if (linkId == null || linkId.isEmpty()) {
                val link0 = jsonGet(obj, "link")
                if (link0 != null) {
                    linkId = jsonStr(link0, "link_id", "linkid")
                }
            }
            if (linkId == null || linkId.isEmpty() || WatchSeen.contains(linkId)) {
                return
            }
            val link = jsonGet(obj, "link")
            val title = jsonStr(obj, "title")
            val desc = jsonStr(obj, "description", "desc")
            var authorId = jsonStr(obj, "userid", "user_id", "author_id")
            var authorName = jsonStr(obj, "username", "nickname")
            var user = jsonFirstObject(obj, "user", "user_info", "userinfo", "author", "hb_user")
            if (user == null && link != null) {
                user = jsonFirstObject(link, "user", "user_info", "author")
            }
            if (user != null) {
                if (authorId == null) {
                    authorId = jsonStr(user, "userid", "user_id", "heybox_id")
                }
                if (authorName == null) {
                    authorName = jsonStr(user, "username", "nickname", "user_name", "name")
                }
            }
            val hitUser = matchUser(cfg, authorId)
            val kw = matchKeywords(cfg, title, desc)
            if (hitUser == null && !kw) {
                return
            }
            val ts = jsonTime(obj, link)
            val now = System.currentTimeMillis() / 1000L
            if (ts > 0 && now - ts > cfg.windowSeconds()) {
                WatchSeen.markNew(linkId)
                log(Log.INFO, "跳过过期帖（" + (now - ts) / 86400 + " 天前）：" + nz(title))
                return
            }
            if (ts <= 0) {
                WatchSeen.markNew(linkId)
                log(Log.WARN, "跳过无发布时间的帖子：" + nz(title))
                return
            }
            val feedKey = "feed:" + nz(authorId)
            if (hitUser != null && !WatchSeen.isBaselined(feedKey)) {
                WatchSeen.markBaselined(feedKey)
                WatchSeen.markNew(linkId)
                log(Log.INFO, "「" + hitUser + "」信息流首轮基线，登记不推送")
                return
            }
            if (!allowFeedPush()) {
                WatchSeen.markNew(linkId)
                log(Log.INFO, "被动命中触发频率限制，本条转为静默：" + nz(title))
                return
            }
            if (!WatchSeen.markNew(linkId)) {
                return
            }
            val item = WatchItem(
                linkId, nz(title), nz(desc), nz(authorId), nz(authorName),
                ts, if (hitUser != null) "user" else "keyword", hitUser
            )
            log(Log.INFO, "信息流命中（" + (if (hitUser != null) "关注" else "关键词") + "）：" + item.displayTitle())
            output(cfg, item, true)
        } catch (t: Throwable) {
            log(Log.WARN, "信息流命中检查异常: " + t)
        }
    }


    const val MAX_PUSH_PER_CHECK = 5

    private fun deliver(cfg: WatchConfig, ctx: Context, items: List<WatchItem>, from: String) {
        val now = System.currentTimeMillis() / 1000L
        val window = cfg.windowSeconds()
        var tooOld = 0
        var noTime = 0
        var fresh = ArrayList<WatchItem>()
        for (it in items) {
            if (it.createAt <= 0) {
                noTime++
                continue
            }
            if (now - it.createAt > window) {
                tooOld++
                continue
            }
            if (WatchSeen.contains(it.linkId)) {
                continue
            }
            fresh.add(it)
        }
        fresh.sortWith(compareByDescending { it.createAt })
        val over = Math.max(0, fresh.size - MAX_PUSH_PER_CHECK)
        if (fresh.size > MAX_PUSH_PER_CHECK) {
            fresh = ArrayList(fresh.subList(0, MAX_PUSH_PER_CHECK))
        }
        for (it in fresh) {
            WatchSeen.markNew(it.linkId)
        }
        if (cfg.banner && fresh.isNotEmpty()) {
            val a = sActivity.get()
            if (a != null) {
                if (fresh.size > 1) {
                    val first = fresh[0]
                    val sb = StringBuilder()
                    for (i in 0 until minOf(fresh.size, 3)) {
                        if (i > 0) {
                            sb.append('\n')
                        }
                        sb.append("· ").append(fresh[i].displayTitle())
                    }
                    if (fresh.size > 3) {
                        sb.append("\n· 还有 ").append(fresh.size - 3).append(" 条…")
                    }
                    WatchOutput.bannerSummary(
                        a, "🔔 " + fresh.size + " 条新动态", sb.toString()
                    ) { WatchOutput.openPost(a, first) }
                } else {
                    WatchOutput.bannerOrToast(a, fresh.get(0))
                }
            }
        }
        for (it in fresh) {
            if (cfg.notify) {
                try {
                    WatchOutput.notifyPost(pickContext(), it)
                } catch (t: Throwable) {
                    log(Log.WARN, "通知失败: " + t)
                }
            }
            if (cfg.pushEnabled) {
                val n = WatchOutput.pushAll(cfg, it)
                log(Log.INFO, "第三方推送完成，成功 " + n + " 个渠道：" + it.displayTitle())
            }
        }
        sLastResult = from + "：候选 " + items.size + " 条（超窗 " + tooOld + "、无时间 " + noTime +
            "、未提醒 " + over + "），提醒 " + fresh.size + " 条"
        log(Log.INFO, sLastResult)
    }

    private fun output(cfg: WatchConfig, item: WatchItem, fromFeed: Boolean) {
        if (cfg.notify) {
            try {
                WatchOutput.notifyPost(pickContext(), item)
            } catch (t: Throwable) {
                log(Log.WARN, "通知失败: " + t)
            }
        }
        if (cfg.banner) {
            val a = sActivity.get()
            if (a != null) {
                WatchOutput.bannerOrToast(a, item)
            }
        }
        if (cfg.pushEnabled) {
            val n = WatchOutput.pushAll(cfg, item)
            log(Log.INFO, "第三方推送完成，成功 " + n + " 个渠道")
        }
    }

    private fun pickContext(): Context? {
        val a = sActivity.get()
        if (a != null) {
            return a
        }
        val m = sModule
        return if (m != null) App.resolveAppContext() else null
    }


    private fun matchUser(cfg: WatchConfig, authorId: String?): String? {
        if (authorId == null || authorId.isEmpty()) {
            return null
        }
        for (raw in cfg.users) {
            val uid = WatchConfig.parseUserId(raw)
            if (uid != null && uid == authorId) {
                return displayName(raw)
            }
        }
        return null
    }

    @JvmStatic
    fun matchKeywords(cfg: WatchConfig, title: String?, desc: String?): Boolean {
        if (cfg.keywords.isEmpty() && cfg.topics.isEmpty()) {
            return false
        }
        val text = if (cfg.titleOnly) nz(title) else (nz(title) + "\n" + nz(desc))
        var words: List<String> = cfg.keywords
        if (cfg.topics.isNotEmpty()) {
            val copy = ArrayList(cfg.keywords)
            for (t in cfg.topics) {
                val n = WatchConfig.topicName(t)
                if (n != null && n.isNotEmpty() && !copy.contains(n)) {
                    copy.add(n)
                }
            }
            words = copy
        }
        for (kw in words) {
            try {
                if (kw.startsWith("regex:")) {
                    val p = kw.substring(6).trim()
                    if (p.isNotEmpty() &&
                        Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(text).find()
                    ) {
                        return true
                    }
                } else if (text.lowercase(Locale.getDefault()).contains(kw.lowercase(Locale.getDefault()))) {
                    return true
                }
            } catch (ignored: Throwable) {
            }
        }
        return false
    }

    private fun displayName(raw: String?): String = if (raw == null) "" else raw.trim()


    private fun jsonFirstObject(jsonObj: Any?, vararg keys: String): Any? {
        for (k in keys) {
            val v = jsonGet(jsonObj, k)
            if (v != null) {
                return v
            }
        }
        return null
    }

    private fun jsonGet(jsonObj: Any?, key: String): Any? {
        try {
            return jsonObj!!.javaClass.getMethod("get", String::class.java).invoke(jsonObj, key)
        } catch (ignored: Throwable) {
            return null
        }
    }

    private fun jsonStr(jsonObj: Any?, vararg keys: String): String? {
        for (k in keys) {
            try {
                val v = jsonGet(jsonObj, k) ?: continue
                val s = v.javaClass.getMethod("getAsString").invoke(v)
                if (s != null) {
                    val text = s.toString()
                    if (text.isNotEmpty() && text != "null") {
                        return text
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }


    private const val MAX_FEED_PUSH_PER_MINUTE = 3
    private const val FEED_BURST_WINDOW_MS = 60_000L
    private val FEED_LOCK = Any()
    private var sBurstStart = 0L
    private var sBurstCount = 0

    private fun allowFeedPush(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(FEED_LOCK) {
            if (now - sBurstStart > FEED_BURST_WINDOW_MS) {
                sBurstStart = now
                sBurstCount = 0
            }
            if (sBurstCount >= MAX_FEED_PUSH_PER_MINUTE) {
                return false
            }
            sBurstCount++
            return true
        }
    }


    private val TIME_KEYS = arrayOf(
        "create_at", "create_time", "createAt", "publish_time", "publish_at",
        "post_time", "timestamp", "time", "ctime", "created_at",
    )

    private fun jsonTime(obj: Any?, link: Any?): Long {
        var v = jsonTimeIn(obj)
        if (v <= 0 && link != null) {
            v = jsonTimeIn(link)
        }
        if (v > 100000000000L) {
            v = v / 1000L
        }
        return v
    }

    private fun jsonTimeIn(o: Any?): Long {
        for (k in TIME_KEYS) {
            val j = jsonGet(o, k) ?: continue
            try {
                val l = j.javaClass.getMethod("getAsLong").invoke(j)
                if (l is Long && l > 0) {
                    return l
                }
            } catch (ignored: Throwable) {
            }
            try {
                val s = j.javaClass.getMethod("getAsString").invoke(j)
                val str = stringify(s)
                val m = Pattern.compile("\\d{9,14}").matcher(str)
                if (m.find()) {
                    return m.group().toLong()
                }
            } catch (ignored: Throwable) {
            }
        }
        return 0L
    }

    private fun nz(s: String?): String = if (s == null) "" else s

    private fun stringify(v: Any?): String = if (v == null) "null" else v.toString()

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun log(level: Int, msg: String) {
        val m = sModule
        if (m != null) {
            m.logd(level, MainModule.TAG, "[动态推送] " + msg)
        }
    }

    private val EMPTY: MutableSet<String> = HashSet()
}
