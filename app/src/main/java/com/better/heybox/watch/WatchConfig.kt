package com.better.heybox.watch

import com.better.heybox.App
import com.better.heybox.MainModule

class WatchConfig private constructor(
    @JvmField val enabled: Boolean,
    @JvmField val banner: Boolean,
    @JvmField val notify: Boolean,
    @JvmField val pushEnabled: Boolean,
    @JvmField val windowDays: Int,
    @JvmField val windowMin: Int,
    @JvmField val intervalMin: Int,
    @JvmField val users: List<String>,
    @JvmField val keywords: List<String>,
    @JvmField val topics: List<String>,
    @JvmField val titleOnly: Boolean,
    @JvmField val streamFetch: Boolean,
    @JvmField val dingtalk: String,
    @JvmField val wxpusher: String,
    @JvmField val onebot: String,
    @JvmField val custom: String
) {

    fun hasTargets(): Boolean {
        return users.isNotEmpty() || keywords.isNotEmpty() || topics.isNotEmpty()
    }

    fun hasAnyChannel(): Boolean {
        return banner || notify || (pushEnabled && (!dingtalk.isEmpty() || !wxpusher.isEmpty()
                || !onebot.isEmpty() || !custom.isEmpty()))
    }

    fun windowSeconds(): Long {
        return windowMin * 60L
    }

    fun windowText(): String {
        if (windowMin % (24 * 60) == 0) {
            return "${windowMin / (24 * 60)} 天"
        }
        if (windowMin % 60 == 0) {
            return "${windowMin / 60} 小时"
        }
        return "$windowMin 分钟"
    }

    companion object {

        const val DEFAULT_WINDOW_DAYS = 3
        const val DEFAULT_WINDOW_MIN = DEFAULT_WINDOW_DAYS * 24 * 60
        const val DEFAULT_INTERVAL_MIN = 10
        const val MAX_USERS = 30
        const val MAX_KEYWORDS = 20
        const val MAX_TOPICS = 20
        const val MAX_LIMIT_PER_USER = 20

        const val SEEN_LIMIT = 400

        private const val CACHE_TTL_MS = 1000L

        @Volatile
        private var sCached: WatchConfig? = null

        @Volatile
        private var sCachedAt: Long = 0L

        @Volatile
        private var sCachedModule: MainModule? = null

        @JvmStatic
        fun invalidate() {
            sCached = null
            sCachedModule = null
            sCachedAt = 0L
        }

        @JvmStatic
        fun load(module: MainModule): WatchConfig {
            val cached = sCached
            val now = android.os.SystemClock.elapsedRealtime()
            if (cached != null && sCachedModule === module && now - sCachedAt < CACHE_TTL_MS) {
                return cached
            }
            val built = build(module)
            sCached = built
            sCachedModule = module
            sCachedAt = now
            return built
        }

        private fun build(module: MainModule): WatchConfig {
            val win = parseInt(
                module.getString(App.KEY_WATCH_WINDOW_DAYS, stringify(DEFAULT_WINDOW_DAYS)),
                DEFAULT_WINDOW_DAYS
            )
            var winMin = parseInt(module.getString(App.KEY_WATCH_WINDOW_MIN, ""), 0)
            if (winMin <= 0) {
                winMin = clamp(win, 1, 30) * 24 * 60
            }
            val inter = parseInt(
                module.getString(App.KEY_WATCH_INTERVAL_MIN, stringify(DEFAULT_INTERVAL_MIN)),
                DEFAULT_INTERVAL_MIN
            )
            return WatchConfig(
                module.isEnabled(App.KEY_WATCH_ENABLED, false),
                module.isEnabled(App.KEY_WATCH_BANNER, true),
                module.isEnabled(App.KEY_WATCH_NOTIFY, true),
                module.isEnabled(App.KEY_WATCH_PUSH_ENABLED, false),
                clamp(win, 1, 30),
                clamp(winMin, 5, 30 * 24 * 60),
                clamp(inter, 3, 720),
                splitLines(module.getString(App.KEY_WATCH_USERS, ""), MAX_USERS),
                splitLines(module.getString(App.KEY_WATCH_KEYWORDS, ""), MAX_KEYWORDS),
                splitLines(module.getString(App.KEY_WATCH_TOPICS, ""), MAX_TOPICS),
                module.isEnabled(App.KEY_WATCH_TITLE_ONLY, false),
                module.isEnabled(App.KEY_WATCH_STREAM_FETCH, false),
                module.getString(App.KEY_WATCH_PUSH_DINGTALK, "")!!.trim(),
                module.getString(App.KEY_WATCH_PUSH_WXPUSHER, "")!!.trim(),
                module.getString(App.KEY_WATCH_PUSH_ONEBOT, "")!!.trim(),
                module.getString(App.KEY_WATCH_PUSH_CUSTOM, "")!!.trim()
            )
        }

        @JvmStatic
        fun formatTopic(id: String?, name: String?): String {
            val n = if (name == null) "" else name.trim()
            val i = if (id == null) "" else id.trim()
            return if (i.isEmpty()) n else i + "|" + n
        }

        @JvmStatic
        fun parseTopicId(raw: String?): String? {
            if (raw == null) {
                return null
            }
            val s = raw.trim()
            val bar = s.indexOf('|')
            if (bar > 0) {
                val head = s.substring(0, bar).trim()
                return if (head.matches(Regex("\\d{1,20}"))) head else null
            }
            return if (s.matches(Regex("\\d{5,20}"))) s else null
        }

        @JvmStatic
        fun topicName(raw: String?): String {
            if (raw == null) {
                return ""
            }
            val s = raw.trim()
            val bar = s.indexOf('|')
            val name = if (bar >= 0) s.substring(bar + 1).trim() else s
            return if (name.isEmpty()) s else name
        }

        @JvmStatic
        fun splitLines(raw: String?, max: Int): List<String> {
            val out = ArrayList<String>()
            if (raw == null) {
                return out
            }
            for (line in raw.split(Regex("\r?\n"))) {
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) {
                    continue
                }
                out.add(s)
                if (out.size >= max) {
                    break
                }
            }
            return out
        }

        @JvmStatic
        fun parseUserId(raw: String?): String? {
            if (raw == null) {
                return null
            }
            var s = raw.trim()
            if (s.isEmpty()) {
                return null
            }
            var head = s
            for (i in s.indices) {
                val c = s[i]
                if (c == '#' || c == ' ' || c == '\t' || c == '|' || c == ',') {
                    head = s.substring(0, i).trim()
                    break
                }
            }
            if (head.matches(Regex("\\d{5,20}"))) {
                return head
            }
            s = head
            var best: String? = null
            val m = java.util.regex.Pattern.compile("(\\d{5,20})").matcher(s)
            while (m.find()) {
                val g = m.group(1)
                val cur = best
                if (cur == null || g.length > cur.length) {
                    best = g
                }
            }
            return best
        }

        private fun parseInt(s: String?, def: Int): Int {
            try {
                return Integer.parseInt(s!!.trim())
            } catch (ignored: Throwable) {
                return def
            }
        }

        private fun clamp(v: Int, lo: Int, hi: Int): Int {
            return Math.max(lo, Math.min(hi, v))
        }
    }
}

private fun stringify(v: Any?): String = if (v == null) "null" else v.toString()
