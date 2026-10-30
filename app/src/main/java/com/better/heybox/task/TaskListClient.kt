package com.better.heybox.task

import android.text.format.DateFormat
import android.util.Log
import com.better.heybox.MainModule
import com.better.heybox.watch.HttpBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashSet

object TaskListClient {

    const val TASK_LIST_PATH = "task/list_v2/"

    private val BASE_HEADERS: Map<String, String> =
        java.util.Collections.unmodifiableMap(
            linkedMapOf(
                "Accept" to "application/json",
                "Referer" to "https://www.xiaoheihe.cn/"
            )
        )

    @Volatile
    private var sModule: MainModule? = null

    @Volatile
    private var sLastSummary = "尚未读取"

    @Volatile
    private var sLastError = ""

    @Volatile
    private var sLastAt = 0L

    @Volatile
    private var sLastCount = 0

    @Volatile
    private var sLastLimited = 0

    private val sSeenFingerprints = HashSet<String>()

    @JvmStatic
    fun init(module: MainModule?) {
        sModule = module
    }

    @JvmStatic
    fun fetch(): List<TaskItem>? {
        var body: String? = null
        var err = ""
        try {
            if (!HttpBridge.ready()) {
                err = "宿主 HTTP 客户端尚未捕获（打开「记录日志」或先让 App 发一次网络请求）"
            } else {
                body = HttpBridge.get(HttpBridge.BASE + TASK_LIST_PATH, BASE_HEADERS)
                if (body == null || body.trim().isEmpty()) {
                    err = "任务列表无响应"
                }
            }
        } catch (t: Throwable) {
            err = "任务列表请求异常: $t"
        }
        if (body == null || body.trim().isEmpty()) {
            record(null, err)
            return null
        }
        return try {
            val out = ArrayList<TaskItem>()
            sSeenFingerprints.clear()
            walk(JSONObject(body), "", false, out, 0)
            if (out.isEmpty()) {
                walkAny(body, out)
            }
            record(out, "")
            out
        } catch (t: Throwable) {
            record(null, "任务列表解析失败: $t")
            null
        }
    }

    private fun walk(node: Any?, group: String, fromLine: Boolean, out: MutableList<TaskItem>, depth: Int) {
        if (node == null || depth > 12 || out.size > 400) {
            return
        }
        if (node is JSONArray) {
            val arr = node
            for (i in 0 until arr.length()) {
                walk(arr.opt(i), group, fromLine, out, depth + 1)
            }
            return
        }
        if (node !is JSONObject) {
            return
        }
        val o = node
        val tasks = o.optJSONArray("tasks")
        if (tasks != null) {
            val g = o.optString("title", group)
            for (i in 0 until tasks.length()) {
                walk(tasks.opt(i), g, fromLine, out, depth + 1)
            }
            return
        }
        val lines = o.optJSONArray("task_line_items")
        if (lines != null) {
            for (i in 0 until lines.length()) {
                val line = lines.opt(i)
                if (line !is JSONObject) {
                    continue
                }
                val lo = line
                val label = lo.optString("title", "")
                val cnt = lo.optString("finish_cnt", "")
                val total = lo.optString("total_cnt", "")
                val progress = if (cnt.isEmpty() && total.isEmpty()) ""
                else (if (cnt.isEmpty()) "0" else cnt) + "/" + (if (total.isEmpty()) "?" else total)
                val lineTasks = lo.optJSONArray("tasks") ?: continue
                for (j in 0 until lineTasks.length()) {
                    walk(lineTasks.opt(j), if (label.isEmpty()) group else label, true, out, depth + 1)
                }
            }
            return
        }
        if (looksLikeTask(o)) {
            add(out, o, group, fromLine, "")
            return
        }
        val it = o.keys()
        while (it.hasNext()) {
            val key = it.next()
            if ("report_extra" == key) {
                continue
            }
            walk(o.opt(key), group, fromLine, out, depth + 1)
        }
    }

    private fun walkAny(node: Any?, out: MutableList<TaskItem>) {
        try {
            if (node is JSONObject) {
                val o = node
                if (looksLikeTask(o)) {
                    add(out, o, "", false, "")
                    return
                }
                val it = o.keys()
                while (it.hasNext()) {
                    walkAny(o.opt(it.next()), out)
                }
            } else if (node is JSONArray) {
                val arr = node
                for (i in 0 until arr.length()) {
                    walkAny(arr.opt(i), out)
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun looksLikeTask(o: JSONObject): Boolean =
        o.has("type") && o.has("state") && o.has("title") && !o.has("tasks")

    private fun add(out: MutableList<TaskItem>, o: JSONObject, group: String, fromLine: Boolean, progress: String) {
        val item = parse(o, group, fromLine, progress) ?: return
        if (item.title.isEmpty()) {
            return
        }
        val fp = item.fingerprint() + "|" + item.state
        if (!sSeenFingerprints.contains(fp)) {
            sSeenFingerprints.add(fp)
            out.add(item)
        }
    }

    private fun parse(o: JSONObject, group: String, fromLine: Boolean, progress: String): TaskItem? {
        var end = 0L
        try {
            val raw = o.optString("task_end_time", "")
            if (raw.isNotEmpty()) {
                end = raw.toDouble().toLong()
            }
        } catch (ignored: Throwable) {
        }
        return TaskItem(
            group, fromLine,
            o.optString("title", ""), o.optString("desc", ""),
            o.optString("type", ""), o.optString("state", ""),
            o.optString("state_desc", ""), o.optString("maxjia", ""),
            o.optString("url", ""), o.optString("tab_id", ""),
            end, progress
        )
    }

    @Synchronized
    private fun record(items: List<TaskItem>?, err: String?) {
        sLastAt = System.currentTimeMillis()
        sLastError = err ?: ""
        if (items == null) {
            sLastCount = 0
            sLastLimited = 0
            sLastSummary = "读取失败：$sLastError"
            return
        }
        var limited = 0
        for (t in items) {
            if (t.limited()) {
                limited++
            }
        }
        sLastCount = items.size
        sLastLimited = limited
        val sb = StringBuilder(512)
        sb.append("任务 ").append(items.size).append(" 条，其中限时 ").append(limited).append(" 条")
        val show = minOf(items.size, 12)
        for (i in 0 until show) {
            sb.append('\n').append(items[i].summary())
        }
        if (items.size > show) {
            sb.append("\n… 还有 ").append(items.size - show).append(" 条（见日志）")
        }
        sLastSummary = sb.toString()
    }

    @JvmStatic
    fun report(): String {
        val sb = StringBuilder(1024)
        sb.append("接口：GET /task/list_v2/（零参数，路径跨版本稳定）\n")
        sb.append("宿主客户端：").append(
            if (HttpBridge.ready())
                "已捕获 " + HttpBridge.describe()
            else if (HttpBridge.captureInstalled())
                "已挂捕获 Hook，等宿主第一次发请求"
            else
                "未挂捕获 Hook"
        )
        val uid = HttpBridge.hostUserId()
        sb.append("\n宿主账号：").append(if (uid.isNullOrEmpty()) "未登录" else uid)
        sb.append("\n最近读取：").append(
            if (sLastAt == 0L) "从未"
            else DateFormat.format("MM-dd HH:mm:ss", sLastAt)
        )
        sb.append("\n最近结果：").append(sLastSummary)
        if (sLastError.isNotEmpty()) {
            sb.append("\n错误：").append(sLastError)
        }
        return sb.toString()
    }

    @JvmStatic
    fun lastError(): String = sLastError

    @JvmStatic
    fun logAll(module: MainModule?, items: List<TaskItem>?) {
        if (module == null || items.isNullOrEmpty()) {
            return
        }
        val seen = HashSet<String>()
        for (t in items) {
            if (seen.add(t.fingerprint() + t.state)) {
                module.logd(Log.INFO, MainModule.TAG, "我的任务：" + t.summary())
            }
        }
    }
}
