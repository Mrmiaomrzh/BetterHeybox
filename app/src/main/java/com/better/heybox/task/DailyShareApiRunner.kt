package com.better.heybox.task

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.better.heybox.MainModule
import com.better.heybox.hooks.DailyTaskHook
import com.better.heybox.watch.HttpBridge
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

                                             
object DailyShareApiRunner {

    private val TASK_IDS = arrayOf("1", "19", "31")
    private val TASK_NAMES = mapOf(
        "1" to "分享帖子",
        "19" to "分享游戏",
        "31" to "分享游戏评价"
    )
    private val POLL_DELAYS_MS = longArrayOf(2000L, 3000L, 5000L, 10000L)
    private const val READY_TIMEOUT_MS = 45_000L
    private const val READY_POLL_MS = 2_000L
    private const val TAP_SLEEP_MS = 2_000L
    private const val DATA_REPORT_URL = "https://data.xiaoheihe.cn/account/data_report/"
    private const val REPORT_ENDPOINT = "/account/data_report/"

    private val JSON_HEADERS: Map<String, String> =
        java.util.Collections.unmodifiableMap(
            linkedMapOf(
                "Accept" to "application/json",
                "Referer" to "https://www.xiaoheihe.cn/"
            )
        )

    private val sMainHandler = Handler(Looper.getMainLooper())
    private val sRunning = AtomicBoolean(false)
    private val sExecutorLock = Any()

    @Volatile
    private var sExecutor: ExecutorService? = null

    private class GameRef(val appid: String, val hSrc: String)

    @JvmStatic
    fun maybeStart(module: MainModule, context: Context): Boolean {
        synchronized(this) {
            if (sRunning.get()) {
                return false
            }
            if (DailyTaskHook.isAutoActive()) {
                return false
            }
            if (!DailyTaskHook.tryAcquireBusy()) {
                return false
            }
            sRunning.set(true)
        }
        try {
            executor().execute { run(module, context.applicationContext) }
            return true
        } catch (t: Throwable) {
            sRunning.set(false)
            DailyTaskHook.releaseBusy()
            module.logd(Log.ERROR, MainModule.TAG, "✘ 静默分享提交失败", t)
            return false
        }
    }

    private fun run(module: MainModule, context: Context) {
        try {
            runInner(module, context)
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 静默分享执行异常: " + t, t)
            notify(context, "静默分享执行异常：" + clip(t.message ?: t.javaClass.simpleName, 120))
        } finally {
            DailyTaskHook.releaseBusy()
            sRunning.set(false)
        }
    }

    private fun runInner(module: MainModule, context: Context) {
        if (!awaitBridgeReady(module)) {
            notify(context, "静默分享未就绪：宿主网络客户端尚未捕获，请先使用 App 一段时间后重试")
            return
        }
        module.logd(Log.INFO, MainModule.TAG, "静默分享：开始执行（任务 1/19/31）")
        val states = fetchTaskStates(module)
        if (states == null) {
            notify(context, "静默分享失败：任务列表获取失败")
            return
        }
        val missing = TASK_IDS.filter { !states.containsKey(it) }
        if (missing.isNotEmpty()) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 静默分享：任务列表缺少 " + missing + "，协议可能已变化")
            notify(context, "静默分享失败：任务列表结构异常，协议可能已更新")
            return
        }
        val failures = LinkedHashMap<String, String>()
        for (id in TASK_IDS) {
            if (isSettled(states[id])) {
                continue
            }
            try {
                runOneTask(module, id)
            } catch (t: Throwable) {
                failures[id] = clip(t.message ?: t.javaClass.simpleName, 80)
                module.logd(Log.WARN, MainModule.TAG, "✘ 静默分享[" + name(id) + "]失败: " + t)
            }
        }
        val finalStates = fetchTaskStates(module)
        for (id in TASK_IDS) {
            val settled = finalStates != null && isSettled(finalStates[id])
            if (settled) {
                failures.remove(id)
            } else if (!failures.containsKey(id)) {
                failures[id] = "服务端未确认完成"
            }
        }
        if (failures.isEmpty()) {
            DailyTaskHook.persistDoneDate(module, DailyTaskHook.todayStamp())
            module.logd(Log.INFO, MainModule.TAG, "✔ 静默分享：3 个任务均已结算，已记录今日状态")
            notify(context, "每日分享任务已完成（静默模式）")
        } else {
            val ok = TASK_IDS.size - failures.size
            val detail = failures.entries.joinToString("；") { name(it.key) + "：" + it.value }
            module.logd(Log.WARN, MainModule.TAG, "✘ 静默分享未全部完成: " + detail)
            notify(context, "静默分享未全部完成（" + ok + "/3）：" + detail)
        }
    }

    private fun awaitBridgeReady(module: MainModule): Boolean {
        var waited = 0L
        while (waited < READY_TIMEOUT_MS) {
            if (HttpBridge.ready() && HttpBridge.heyboxId() != null) {
                return true
            }
            sleep(READY_POLL_MS)
            waited += READY_POLL_MS
        }
        module.logd(Log.WARN, MainModule.TAG, "✘ 静默分享：宿主 HTTP 桥未就绪（" + HttpBridge.describe() + "）")
        return false
    }

    private fun runOneTask(module: MainModule, id: String) {
        val addition = selectShareContent(id)
        val sessionId = UUID.randomUUID().toString()
        module.logd(Log.INFO, MainModule.TAG, "静默分享[" + name(id) + "]已选定内容: " + addition)
        for (action in arrayOf("show", "tap", "success")) {
            val timeSec = (System.currentTimeMillis() / 1000).toString()
            val event = buildEvent(action, addition, sessionId, timeSec)
            report(module, event, sessionId, timeSec)
            module.logd(Log.INFO, MainModule.TAG, "静默分享[" + name(id) + "]" + action + " 已上报")
            if (action == "tap") {
                sleep(TAP_SLEEP_MS)
            }
        }
        for (delay in POLL_DELAYS_MS) {
            sleep(delay)
            val states = fetchTaskStates(module)
            if (states != null && isSettled(states[id])) {
                module.logd(Log.INFO, MainModule.TAG, "✔ 静默分享[" + name(id) + "]服务端已结算")
                return
            }
        }
        throw IllegalStateException("上报后服务端未结算")
    }

    private fun buildEvent(action: String, addition: JSONObject, sessionId: String, timeSec: String): JSONObject {
        val extra = JSONObject(addition.toString())
        if (action != "show") {
            extra.put("plat", "WechatSession")
        }
        extra.put("h_session_id", sessionId)
        return JSONObject()
            .put("type", if (action == "tap") "4" else "3")
            .put("path", "/share/behavior/" + action)
            .put("time", timeSec)
            .put("addition", extra)
    }

    private fun report(module: MainModule, event: JSONObject, sessionId: String, timeSec: String) {
        val enc = DailyShareReport.encrypt(event.toString(), timeSec)
        val heyboxId = HttpBridge.heyboxId() ?: throw IllegalStateException("缺少 heybox_id")
        val sig = DailyShareReport.webSignature(REPORT_ENDPOINT, timeSec.toLong())
        val version = HttpBridge.clientVersion() ?: "999.0.4"
        val query = "app=heybox&os_type=web&x_app=heybox&x_client_type=web&x_os_type=Android" +
            "&x_client_version=" + urlencode(version) +
            "&version=999.0.4&heybox_id=" + urlencode(heyboxId) +
            "&type=104&time_=" + urlencode(timeSec) +
            "&session_id=" + urlencode(sessionId) +
            "&hkey=" + urlencode(sig.hkey) +
            "&_time=" + urlencode(sig.time) +
            "&nonce=" + urlencode(sig.nonce)
        val headers = linkedMapOf(
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://web.xiaoheihe.cn",
            "Referer" to "https://web.xiaoheihe.cn/",
            "X-Requested-With" to "com.max.xiaoheihe"
        )
        val fields = linkedMapOf(
            "data" to enc.data,
            "key" to enc.key,
            "sid" to enc.sid
        )
        val body = HttpBridge.postForm(DATA_REPORT_URL + "?" + query, headers, fields)
        if (body == null || body.trim().isEmpty()) {
            throw IllegalStateException("上报无响应")
        }
        val resp = JSONObject(body)
        val status = resp.optString("status")
        if (status.isNotEmpty() && status != "ok") {
            throw IllegalStateException("上报被拒绝: " + status + " " + resp.optString("msg"))
        }
    }

    private fun selectShareContent(id: String): JSONObject {
        return when (id) {
            "1" -> {
                val root = parseResult(
                    getOrThrow(
                        HttpBridge.BASE + "bbs/app/feeds?offset=0&limit=20&pull=1&last_pull=1",
                        "帖子流"
                    ),
                    "帖子流"
                )
                val item = findLinkItem(root, "h_src")
                    ?: throw IllegalStateException("帖子流中未找到可分享内容")
                JSONObject()
                    .put("src", "link")
                    .put("link_id", firstOf(item, "linkid", "link_id"))
                    .put("h_src", item.optString("h_src"))
            }
            "19", "31" -> {
                val games = collectGames()
                if (games.isEmpty()) {
                    throw IllegalStateException("推荐游戏中未找到 appid/h_src")
                }
                if (id == "19") {
                    val game = games[0]
                    JSONObject()
                        .put("src", "game_detail")
                        .put("app_id", game.appid)
                        .put("h_src", game.hSrc)
                } else {
                    selectGameComment(games)
                }
            }
            else -> throw IllegalStateException("未知分享任务类型 " + id)
        }
    }

    private fun collectGames(): List<GameRef> {
        val root = parseResult(
            getOrThrow(HttpBridge.BASE + "game/all_recommend/v2", "游戏推荐"),
            "游戏推荐"
        )
        val out = ArrayList<GameRef>()
        val seen = HashSet<String>()
        walkGames(root, out, seen, 0)
        return out
    }

    private fun walkGames(node: Any?, out: MutableList<GameRef>, seen: MutableSet<String>, depth: Int) {
        if (node == null || depth > 12 || out.size > 100) {
            return
        }
        if (node is JSONArray) {
            for (i in 0 until node.length()) {
                walkGames(node.opt(i), out, seen, depth + 1)
            }
            return
        }
        if (node !is JSONObject) {
            return
        }
        val o = node
        val appid = o.optString("appid")
        val hSrc = o.optString("h_src")
        if (appid.isNotEmpty() && hSrc.isNotEmpty() && seen.add(appid)) {
            out.add(GameRef(appid, hSrc))
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            walkGames(o.opt(keys.next()), out, seen, depth + 1)
        }
    }

    private fun selectGameComment(games: List<GameRef>): JSONObject {
        for (game in games.take(5)) {
            try {
                val commentsRoot = parseResult(
                    getOrThrow(
                        HttpBridge.BASE + "bbs/app/link/game/comments?appid=" + urlencode(game.appid) +
                            "&api_version=4&offset=0&limit=20",
                        "游戏评论"
                    ),
                    "游戏评论"
                )
                val item = findLinkItem(commentsRoot, "userid") ?: continue
                val authorId = item.optString("userid")
                if (authorId.isEmpty()) {
                    continue
                }
                val shareRoot = parseResult(
                    getOrThrow(
                        HttpBridge.BASE + "game/comment/share_data/?appid=" + urlencode(game.appid) +
                            "&userid=" + urlencode(authorId),
                        "评论分享数据"
                    ),
                    "评论分享数据"
                )
                val detail = shareRoot.optJSONObject("result")?.optJSONObject("comment_detail")
                    ?: findObject(shareRoot, "comment_detail")
                val linkId = detail?.optString("linkid") ?: ""
                if (linkId.isNotEmpty()) {
                    return JSONObject()
                        .put("src", "game_comment")
                        .put("link_id", linkId)
                }
            } catch (ignored: Throwable) {
                           
            }
        }
        throw IllegalStateException("前 5 个游戏中未找到可分享的评价")
    }

    private fun fetchTaskStates(module: MainModule): LinkedHashMap<String, String>? {
        val body = try {
            HttpBridge.get(HttpBridge.BASE + TaskListClient.TASK_LIST_PATH, JSON_HEADERS)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 静默分享：任务列表请求异常: " + t)
            null
        } ?: return null
        if (body.trim().isEmpty()) {
            return null
        }
        val out = LinkedHashMap<String, String>()
        try {
            walkStates(JSONObject(body), out, 0)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 静默分享：任务列表解析失败: " + t)
            return null
        }
        return out
    }

    private fun walkStates(node: Any?, out: MutableMap<String, String>, depth: Int) {
        if (node == null || depth > 12 || out.size > 200) {
            return
        }
        if (node is JSONArray) {
            for (i in 0 until node.length()) {
                walkStates(node.opt(i), out, depth + 1)
            }
            return
        }
        if (node !is JSONObject) {
            return
        }
        val o = node
        val fromExtra = o.optJSONObject("report_extra")?.optString("task_id") ?: ""
        val taskId = if (fromExtra.isNotEmpty()) fromExtra else o.optString("task_id")
        val state = o.optString("state")
        if (taskId.isNotEmpty() && state.isNotEmpty()) {
            out[taskId] = state
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            walkStates(o.opt(keys.next()), out, depth + 1)
        }
    }

    private fun getOrThrow(url: String, what: String): String {
        val body = HttpBridge.get(url, JSON_HEADERS)
            ?: throw IllegalStateException(what + "无响应")
        if (body.trim().isEmpty()) {
            throw IllegalStateException(what + "无响应")
        }
        return body
    }

    private fun parseResult(body: String, what: String): JSONObject {
        val root = JSONObject(body)
        val status = root.optString("status")
        if (status.isNotEmpty() && status != "ok") {
            throw IllegalStateException(what + "失败: " + status + " " + root.optString("msg"))
        }
        return root
    }

    private fun findLinkItem(root: JSONObject, requiredKey: String): JSONObject? {
        val result = root.optJSONObject("result") ?: root
        val links = result.optJSONArray("links")
        if (links != null) {
            for (i in 0 until links.length()) {
                val item = links.opt(i) as? JSONObject ?: continue
                if (firstOf(item, "linkid", "link_id").isNotEmpty() &&
                    item.optString(requiredKey).isNotEmpty()
                ) {
                    return item
                }
            }
        }
        return deepFind(root, requiredKey, 0)
    }

    private fun deepFind(node: Any?, requiredKey: String, depth: Int): JSONObject? {
        if (node == null || depth > 8) {
            return null
        }
        if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = deepFind(node.opt(i), requiredKey, depth + 1)
                if (found != null) {
                    return found
                }
            }
            return null
        }
        if (node !is JSONObject) {
            return null
        }
        val o = node
        if (firstOf(o, "linkid", "link_id").isNotEmpty() && o.optString(requiredKey).isNotEmpty()) {
            return o
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val found = deepFind(o.opt(keys.next()), requiredKey, depth + 1)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findObject(node: Any?, key: String, depth: Int = 0): JSONObject? {
        if (node == null || depth > 8) {
            return null
        }
        if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = findObject(node.opt(i), key, depth + 1)
                if (found != null) {
                    return found
                }
            }
            return null
        }
        if (node !is JSONObject) {
            return null
        }
        val o = node
        val direct = o.opt(key)
        if (direct is JSONObject) {
            return direct
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val found = findObject(o.opt(keys.next()), key, depth + 1)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun firstOf(item: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = item.optString(key)
            if (value.isNotEmpty()) {
                return value
            }
        }
        return ""
    }

    private fun isSettled(state: String?): Boolean = state == "finish" || state == "can_reward"

    private fun name(id: String): String = TASK_NAMES[id] ?: id

    private fun urlencode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("静默分享被中断")
        }
    }

    private fun clip(s: String?, n: Int): String {
        val value = s ?: ""
        return if (value.length <= n) value else value.substring(0, n) + "…"
    }

    private fun notify(context: Context, msg: String) {
        sMainHandler.post {
            try {
                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun executor(): ExecutorService {
        synchronized(sExecutorLock) {
            var e = sExecutor
            if (e == null || e.isShutdown) {
                e = Executors.newSingleThreadExecutor { r ->
                    val t = Thread(r, "bhb-daily-api")
                    t.isDaemon = true
                    t
                }
                sExecutor = e
            }
            return e
        }
    }
}
