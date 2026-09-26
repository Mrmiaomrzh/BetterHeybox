package com.better.heybox.watch

import android.util.Log
import com.better.heybox.MainModule
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern

object WatchFetcher {

    private const val BASE = "https://api.xiaoheihe.cn/"

    const val FETCH_LIMIT = 10

    const val FOLLOW_IMPORT_LIMIT = 100

    private val USER_POSTS_PATHS = arrayOf(
        "bbs/app/profile/user/link/list",
        "bbs/app/profile/award/link",
    )
    private val FOLLOWING_PATHS = arrayOf(
        "bbs/app/profile/following/list",
        "bbs/app/profile/following/simple_list",
        "bbs/app/profile/inter_follow/list",
        "bbs/app/profile/follower/list",
    )

    private val TOPIC_LIST_PATHS = arrayOf(
        "bbs/app/profile/preference_v5/topic_list",
        "bbs/app/profile/topic/settings",
    )

    private const val TOPIC_INFO_PATH = "bbs/app/topic/list_infos"

    private val TOPIC_SEARCH_PATHS = arrayOf(
        "bbs/app/api/search/topic",
        "bbs/app/topic/search",
        "bbs/app/hashtag/search",
    )

    private val TOPIC_FEED_PATHS = arrayOf(
        "bbs/app/topic/feeds",
        "bbs/app/topic/max/feeds",
        "bbs/app/hashtag/concept/feeds",
    )

    private val SEARCH_PATHS = arrayOf(
        "bbs/app/api/general/search/v1",
        "bbs/app/hashtag/search",
        "bbs/app/topic/search",
    )

    private val HOT_WORD_PATHS = arrayOf(
        "bbs/app/api/search/hot_words",
    )

    private val SUGGEST_PATHS = arrayOf(
        "bbs/app/api/search/suggestion/v2",
    )

    @Volatile
    private var sModule: MainModule? = null

    @JvmStatic
    fun init(module: MainModule) {
        sModule = module
    }


    @JvmStatic
    fun userPostsUrl(userId: String?, limit: Int): String =
        BASE + USER_POSTS_PATHS[0] + "?userid=" + userId + "&offset=0&limit=" + limit

    @JvmStatic
    fun fetchUserPosts(userId: String?, limit: Int): MutableList<WatchItem> {
        val out = ArrayList<WatchItem>()
        if (userId == null || userId.isEmpty()) {
            return out
        }
        val headers = baseHeaders()
        var body: String? = null
        for (path in USER_POSTS_PATHS) {
            body = HttpBridge.get(BASE + path + "?userid=" + userId + "&offset=0&limit=" + limit, headers)
            if (body != null && body.isNotEmpty() && body.contains("link")) {
                break
            }
        }
        if (body == null || body.isEmpty()) {
            log(Log.WARN, "user_posts 无响应 userid=" + userId)
            return out
        }
        try {
            collect(JSONObject(body), out, 0)
        } catch (t: Throwable) {
            log(Log.WARN, "user_posts 解析失败 userid=" + userId + " : " + t)
        }
        log(Log.INFO, "user_posts userid=" + userId + " 解析出 " + out.size + " 条")
        return out
    }


    @JvmStatic
    fun fetchFollowing(limit: Int): MutableList<Array<String>> {
        val out = ArrayList<Array<String>>()
        val headers = baseHeaders()
        var body: String? = null
        var usedPath: String? = null
        for (path in FOLLOWING_PATHS) {
            body = HttpBridge.get(BASE + path + "?offset=0&limit=50", headers)
            if (body != null && body.length > 20) {
                usedPath = path
                break
            }
        }
        if (body == null || body.isEmpty()) {
            log(Log.WARN, "关注列表无响应（可能未登录或端点变化）")
            return out
        }
        try {
            val users = ArrayList<JSONObject>()
            collectUserObjects(JSONObject(body), users, 0)
            val seen = LinkedHashMap<String, String>()
            for (u in users) {
                val id = str(u, "userid", "user_id", "heybox_id")
                if (id == null || id.isEmpty() || "0".equals(id)) {
                    continue
                }
                val name = str(u, "username", "nickname", "user_name", "name")
                if (!seen.containsKey(id)) {
                    seen[id] = if (name == null) "" else name
                }
                if (seen.size >= limit) {
                    break
                }
            }
            for (e in seen.entries) {
                out.add(stringArrayOf(e.key, e.value))
            }
            log(Log.INFO, "关注列表导入：" + usedPath + " 解析出 " + out.size + " 个用户")
        } catch (t: Throwable) {
            log(Log.WARN, "关注列表解析失败: " + t)
        }
        return out
    }


    @JvmStatic
    fun fetchFollowedTopics(limit: Int): MutableList<Array<String>> {
        val headers = baseHeaders()
        val body = HttpBridge.get(BASE + TOPIC_LIST_PATHS[0] + "?offset=0&limit=50", headers)
        val out = ArrayList<Array<String>>()
        if (body != null && body.length > 20) {
            out.addAll(parseTopicPairs(body, limit))
            if (out.isEmpty()) {
                logBody("关注话题 " + TOPIC_LIST_PATHS[0], body)
            }
        }
        if (out.isEmpty()) {
            out.addAll(fetchRecentTopics(limit))
        }
        if (out.isEmpty()) {
            log(Log.WARN, "没有可导入的话题：关注话题接口不可用，最近也没浏览过话题")
        } else {
            log(Log.INFO, "可导入话题共 " + out.size + " 个")
        }
        return out
    }

    @JvmStatic
    fun fetchRecentTopics(limit: Int): MutableList<Array<String>> {
        val ids = HttpBridge.recentTopicIds()
        if (ids.isEmpty()) {
            log(Log.INFO, "最近浏览话题为空（在小黑盒里点开任意话题页即可记录）")
            return ArrayList()
        }
        return fetchTopicInfos(ids, limit)
    }

    @JvmStatic
    fun fetchTopicInfos(ids: List<String>?, limit: Int): MutableList<Array<String>> {
        val out = ArrayList<Array<String>>()
        if (ids == null || ids.isEmpty()) {
            return out
        }
        val end = Math.min(ids.size, Math.max(1, limit))
        val sb = StringBuilder()
        for (i in 0 until end) {
            if (i > 0) {
                sb.append(',')
            }
            sb.append(ids[i])
        }
        val body = HttpBridge.get(BASE + TOPIC_INFO_PATH + "?topic_ids=" + sb, baseHeaders())
        if (body == null || body.isEmpty()) {
            log(Log.WARN, "话题名解析无响应")
            return out
        }
        try {
            val objs = ArrayList<JSONObject>()
            collectTopicObjects(JSONObject(body), objs, 0)
            val seen = LinkedHashMap<String, String>()
            for (o in objs) {
                val name = str(o, "name", "topic_name", "topicName", "title")
                val id = str(o, "topic_id", "topicId", "id")
                if (name == null || name.trim().isEmpty() || id == null) {
                    continue
                }
                if (!seen.containsKey(id)) {
                    seen[id] = name.trim()
                }
                if (seen.size >= limit) {
                    break
                }
            }
            for (e in seen.entries) {
                out.add(stringArrayOf(e.key, e.value))
            }
            log(Log.INFO, "话题名解析出 " + out.size + " 个")
        } catch (t: Throwable) {
            log(Log.WARN, "话题名解析异常: " + t)
        }
        return out
    }

    @JvmStatic
    fun fetchTopicSearch(keyword: String?, limit: Int): MutableList<Array<String>> {
        val out = ArrayList<Array<String>>()
        if (keyword == null || keyword.trim().isEmpty()) {
            return out
        }
        val q = URLEncoder.encode(keyword.trim())
        for (path in TOPIC_SEARCH_PATHS) {
            val body = HttpBridge.get(
                BASE + path + "?q=" + q + "&keyword=" + q + "&offset=0&limit=" + limit,
                baseHeaders()
            )
            if (body != null && body.length > 20) {
                out.addAll(parseTopicPairs(body, limit))
            }
            if (!out.isEmpty()) {
                log(Log.INFO, "话题搜索 " + path + " [" + keyword + "] → " + out.size + " 个")
                return out
            }
            sleepQuiet(300)
        }
        log(Log.INFO, "话题搜索无结果 [" + keyword + "]")
        return out
    }

    private fun parseTopicPairs(body: String, limit: Int): MutableList<Array<String>> {
        val out = ArrayList<Array<String>>()
        try {
            val objs = ArrayList<JSONObject>()
            collectTopicObjects(JSONObject(body), objs, 0)
            val seen = LinkedHashMap<String, String>()
            for (o in objs) {
                val name = str(
                    o, "name", "topic_name", "topicName", "title",
                    "tag_name", "display_name"
                )
                if (name == null || name.trim().isEmpty()) {
                    continue
                }
                var id = str(o, "topic_id", "topicId", "tag_id", "hashtag_id", "cid", "id")
                if (id != null && !id.matches(Regex("\\d{1,20}"))) {
                    id = null
                }
                if (!seen.containsKey(name.trim())) {
                    seen[name.trim()] = if (id == null) "" else id
                }
                if (seen.size >= limit) {
                    break
                }
            }
            for (e in seen.entries) {
                out.add(stringArrayOf(if (e.value.isEmpty()) null else e.value, e.key))
            }
        } catch (t: Throwable) {
            log(Log.WARN, "话题解析失败: " + t)
        }
        return out
    }

    @JvmStatic
    fun fetchTopicPosts(topicId: String?, topicName: String?, limit: Int): MutableList<WatchItem> {
        val out = ArrayList<WatchItem>()
        val headers = baseHeaders()
        if (topicId != null && topicId.isNotEmpty()) {
            val queries = arrayOf(
                "?topic_id=" + topicId + "&offset=0&limit=" + limit,
                "?topic_ids=" + topicId + "&offset=0&limit=" + limit,
                "?id=" + topicId + "&offset=0&limit=" + limit,
                "?concept_id=" + topicId + "&offset=0&limit=" + limit,
                "?hashtag_id=" + topicId + "&offset=0&limit=" + limit,
            )
            for (path in TOPIC_FEED_PATHS) {
                for (q in queries) {
                    val body = HttpBridge.get(BASE + path + q, headers)
                    if (body != null && body.length > 50) {
                        try {
                            collect(JSONObject(body), out, 0)
                        } catch (t: Throwable) {
                            log(Log.WARN, "话题帖解析失败 " + path + " : " + t)
                        }
                        if (!out.isEmpty()) {
                            log(Log.INFO, "话题帖 " + path + q.split("&")[0] + " → " + out.size + " 条")
                            return out
                        }
                    }
                    sleepQuiet(400)
                }
            }
        }
        if (topicName != null && topicName.isNotEmpty()) {
            log(Log.INFO, "话题无 id，退化为关键词搜索：" + topicName)
            return fetchKeywordPosts(topicName, limit)
        }
        log(Log.WARN, "话题取数失败 topicId=" + topicId + " name=" + topicName)
        return out
    }

    @JvmStatic
    fun fetchKeywordPosts(keyword: String?, limit: Int): MutableList<WatchItem> {
        val out = ArrayList<WatchItem>()
        if (keyword == null || keyword.trim().isEmpty()) {
            return out
        }
        val q = URLEncoder.encode(keyword.trim())
        val headers = baseHeaders()
        for (path in SEARCH_PATHS) {
            val body = HttpBridge.get(
                BASE + path + "?q=" + q + "&query=" + q + "&offset=0&limit=" + limit +
                    "&search_type=link&type=link", headers
            )
            if (body == null || body.length < 50) {
                sleepQuiet(400)
                continue
            }
            try {
                collect(JSONObject(body), out, 0)
            } catch (t: Throwable) {
                log(Log.WARN, "关键词搜索解析失败 " + path + " : " + t)
            }
            if (!out.isEmpty()) {
                log(Log.INFO, "关键词搜索 " + path + " [" + keyword + "] → " + out.size + " 条")
                return out
            }
            sleepQuiet(400)
        }
        log(Log.INFO, "关键词搜索无结果 [" + keyword + "]")
        return out
    }

    @JvmStatic
    fun fetchHotWords(seed: String?, limit: Int): MutableList<String> {
        val out = ArrayList<String>()
        val headers = baseHeaders()
        for (path in HOT_WORD_PATHS) {
            val body = HttpBridge.get(BASE + path, headers)
            if (body != null && body.length > 10) {
                collectWords(body, out, limit)
            }
            if (out.size >= limit) {
                break
            }
        }
        if (seed != null && seed.trim().isNotEmpty() && out.size < limit) {
            val q = URLEncoder.encode(seed.trim())
            for (path in SUGGEST_PATHS) {
                val body = HttpBridge.get(BASE + path + "?q=" + q + "&query=" + q, headers)
                if (body != null && body.length > 10) {
                    collectWords(body, out, limit)
                }
                if (out.size >= limit) {
                    break
                }
            }
        }
        log(Log.INFO, "推荐关键词候选 " + out.size + " 个")
        return out
    }

    private fun collectTopicObjects(node: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (node == null || depth > 6 || out.size > 200) {
            return
        }
        if (node is JSONArray) {
            val arr = node
            for (i in 0 until arr.length()) {
                collectTopicObjects(arr.opt(i), out, depth + 1)
            }
            return
        }
        if (node !is JSONObject) {
            return
        }
        val o = node
        val looksLikeUser = str(o, "userid", "user_id", "heybox_id") != null
        val hasName = str(
            o, "name", "topic_name", "topicName", "title",
            "tag_name", "display_name"
        ) != null
        if (!looksLikeUser && hasName) {
            out.add(o)
        }
        val it = o.keys()
        while (it.hasNext()) {
            val v = o.opt(it.next())
            if (v is JSONObject || v is JSONArray) {
                collectTopicObjects(v, out, depth + 1)
            }
        }
    }

    private fun collectWords(body: String, out: MutableList<String>, limit: Int) {
        try {
            val root: Any =
                if (body.trim().startsWith("[")) JSONArray(body) else JSONObject(body)
            collectWords(root, out, limit, 0)
        } catch (t: Throwable) {
            log(Log.WARN, "推荐关键词解析失败: " + t)
        }
    }

    private fun collectWords(node: Any?, out: MutableList<String>, limit: Int, depth: Int) {
        if (node == null || depth > 5 || out.size >= limit) {
            return
        }
        if (node is JSONArray) {
            val arr = node
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                if (v is String) {
                    addWord(out, v, limit)
                } else {
                    collectWords(v, out, limit, depth + 1)
                }
            }
            return
        }
        if (node !is JSONObject) {
            return
        }
        val o = node
        val word = str(o, "word", "keyword", "query", "key", "name", "text", "title", "content")
        if (word != null) {
            addWord(out, word, limit)
        }
        val it = o.keys()
        while (it.hasNext()) {
            val v = o.opt(it.next())
            if (v is JSONObject || v is JSONArray) {
                collectWords(v, out, limit, depth + 1)
            }
        }
    }

    private fun addWord(out: MutableList<String>, raw: String?, limit: Int) {
        val w = if (raw == null) "" else raw.trim()
        if (w.isEmpty() || w.length > 20 || out.size >= limit) {
            return
        }
        if (!out.contains(w)) {
            out.add(w)
        }
    }

    private fun logBody(tag: String, body: String?) {
        if (body == null) {
            log(Log.WARN, tag + " 响应为空")
            return
        }
        val s = body.replace('\n', ' ').replace('\r', ' ').trim()
        log(
            Log.WARN, tag + " body[" + body.length + "]=" +
                (if (s.length > 240) s.substring(0, 240) + "…" else s)
        )
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }


    private fun collect(node: Any?, out: MutableList<WatchItem>, depth: Int) {
        collect(node, out, depth, null)
    }

    private fun collect(
        node: Any?, out: MutableList<WatchItem>, depth: Int, inheritedUser: JSONObject?,
    ) {
        if (node == null || depth > 6 || out.size > 300) {
            return
        }
        if (node is JSONArray) {
            val arr = node
            for (i in 0 until arr.length()) {
                collect(arr.opt(i), out, depth + 1, inheritedUser)
            }
            return
        }
        if (node !is JSONObject) {
            return
        }
        val o = node
        val here = authorObjectOf(o)
        val ctxUser = if (here != null) here else inheritedUser
        val item = extract(o, ctxUser)
        if (item != null) {
            out.add(item)
        }
        val it = o.keys()
        while (it.hasNext()) {
            val v = o.opt(it.next())
            if (v is JSONObject || v is JSONArray) {
                collect(v, out, depth + 1, ctxUser)
            }
        }
    }

    private fun authorObjectOf(o: JSONObject): JSONObject? {
        var u = o.optJSONObject("user")
        if (u == null) {
            u = o.optJSONObject("user_info")
        }
        if (u == null) {
            u = o.optJSONObject("userinfo")
        }
        if (u == null) {
            u = o.optJSONObject("author")
        }
        if (u == null) {
            u = o.optJSONObject("hb_user")
        }
        if (u == null) {
            val link = o.optJSONObject("link")
            if (link != null) {
                u = link.optJSONObject("user")
                if (u == null) {
                    u = link.optJSONObject("user_info")
                }
            }
        }
        return u
    }

    private fun collectUserObjects(node: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (node == null || depth > 6 || out.size > 500) {
            return
        }
        if (node is JSONArray) {
            val arr = node
            for (i in 0 until arr.length()) {
                collectUserObjects(arr.opt(i), out, depth + 1)
            }
            return
        }
        if (node !is JSONObject) {
            return
        }
        val o = node
        if (str(o, "userid", "user_id", "heybox_id") != null &&
            str(o, "username", "nickname", "user_name", "name") != null
        ) {
            out.add(o)
        }
        val it = o.keys()
        while (it.hasNext()) {
            val v = o.opt(it.next())
            if (v is JSONObject || v is JSONArray) {
                collectUserObjects(v, out, depth + 1)
            }
        }
    }

    private fun extract(o: JSONObject, ctxUser: JSONObject?): WatchItem? {
        var linkId = str(o, "link_id", "linkId", "linkid")
        val link = o.optJSONObject("link")
        if ((linkId == null || linkId.isEmpty()) && link != null) {
            linkId = str(link, "link_id", "linkid")
        }
        if (linkId == null || linkId.isEmpty()) {
            return null
        }
        var title = str(o, "title", "link_title")
        var desc = str(o, "description", "desc", "content", "text")
        var authorId = str(o, "userid", "user_id", "author_id")
        var authorName = str(o, "username", "user_name", "nickname", "author")
        if (link != null) {
            if (title == null) {
                title = str(link, "title")
            }
            if (desc == null) {
                desc = str(link, "description", "desc")
            }
        }
        var user = authorObjectOf(o)
        if (user == null) {
            user = ctxUser
        }
        if (user != null) {
            if (authorId == null) {
                authorId = str(user, "userid", "user_id", "heybox_id")
            }
            if (authorName == null) {
                authorName = str(user, "username", "nickname", "user_name", "name")
            }
        }
        val ts = firstTime(o, link)
        return WatchItem(linkId, nz(title), nz(desc), nz(authorId), nz(authorName), ts, "", "")
    }

    private val TIME_KEYS = arrayOf(
        "create_at", "create_time", "createAt", "publish_time", "publish_at",
        "post_time", "timestamp", "time", "ctime", "created_at",
    )

    private fun firstTime(o: JSONObject, link: JSONObject?): Long {
        var v = firstTimeIn(o)
        if (v <= 0 && link != null) {
            v = firstTimeIn(link)
        }
        if (v > 100000000000L) {
            v = v / 1000L
        }
        return v
    }

    private fun firstTimeIn(o: JSONObject): Long {
        for (k in TIME_KEYS) {
            val raw = o.opt(k)
            val v = toEpochSeconds(raw)
            if (v > 0) {
                return v
            }
        }
        return 0L
    }

    private val DIGITS = Pattern.compile("(\\d{9,14})")
    private val DATE_FORMATS = arrayOf(
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA),
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA),
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA),
    )

    private fun toEpochSeconds(raw: Any?): Long {
        if (raw == null || JSONObject.NULL.equals(raw)) {
            return 0L
        }
        if (raw is Number) {
            return raw.toLong()
        }
        val s = stringify(raw).trim()
        if (s.isEmpty()) {
            return 0L
        }
        try {
            return s.toLong()
        } catch (ignored: Throwable) {
        }
        val m = DIGITS.matcher(s)
        if (m.find()) {
            try {
                return m.group(1).toLong()
            } catch (ignored: Throwable) {
            }
        }
        for (f in DATE_FORMATS) {
            try {
                val d = f.parse(s)
                if (d != null) {
                    return d.getTime() / 1000L
                }
            } catch (ignored: Throwable) {
            }
        }
        return 0L
    }

    private fun str(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k, null)
            if (v != null && v.isNotEmpty() && v != "null") {
                return v
            }
        }
        return null
    }

    private fun baseHeaders(): MutableMap<String, String> {
        val h = HashMap<String, String>()
        h["Accept"] = "application/json"
        h["Referer"] = "https://www.xiaoheihe.cn/"
        return h
    }

    private fun nz(s: String?): String = if (s == null) "" else s

    private fun stringify(v: Any?): String = if (v == null) "null" else v.toString()

    @Suppress("UNCHECKED_CAST")
    private fun stringArrayOf(first: String?, second: String?): Array<String> =
        arrayOf(first, second) as Array<String>

    @JvmStatic
    fun dedupe(input: List<WatchItem>): MutableList<WatchItem> {
        val map = LinkedHashMap<String?, WatchItem>()
        for (it in input) {
            if (!map.containsKey(it.linkId)) {
                map[it.linkId] = it
            }
        }
        return ArrayList(map.values)
    }

    private fun log(level: Int, msg: String) {
        val m = sModule
        if (m != null) {
            m.logd(level, MainModule.TAG, "[动态推送] " + msg)
        }
    }
}
