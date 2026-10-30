package com.better.heybox.task

import android.util.Log
import com.better.heybox.watch.HttpBridge
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

object OwnPostClient {

    private val USER_POST_PATHS = arrayOf(
        "bbs/app/profile/user/link/list",
        "bbs/app/profile/award/link",
    )

    private val BASE_HEADERS: Map<String, String> =
        java.util.Collections.unmodifiableMap(
            linkedMapOf(
                "Accept" to "application/json",
                "Referer" to "https://www.xiaoheihe.cn/"
            )
        )

    private class Item {
        var linkId: String = ""
        var text: String = ""
        var createdAt: Long = 0
    }

    @JvmStatic
    fun hasPost(linkId: String?): Boolean {
        if (linkId == null || linkId.trim().isEmpty()) {
            return false
        }
        val uid = HttpBridge.hostUserId()
        if (uid.isNullOrEmpty()) {
            return false
        }
        val id = linkId.trim()
        for (path in USER_POST_PATHS) {
            val url = HttpBridge.BASE + path + "?userid=" + uid + "&offset=0&limit=30"
            var body: String?
            try {
                body = HttpBridge.get(url, BASE_HEADERS)
            } catch (t: Throwable) {
                Log.w("BetterHeybox", "[OwnPostClient] hasPost 请求异常 $path : $t")
                body = null
            }
            if (body.isNullOrEmpty()) {
                continue
            }
            try {
                if (containsId(JSONObject(body), id)) {
                    return true
                }
            } catch (ignored: Throwable) {
            }
        }
        return false
    }

    private fun containsId(node: Any, id: String): Boolean =
        findItem(node, id) != null

    @JvmStatic
    fun findRecent(keyword: String?, sinceMillis: Long): String {
        if (keyword == null || keyword.trim().isEmpty()) {
            return ""
        }
        val uid = HttpBridge.hostUserId()
        if (uid.isNullOrEmpty()) {
            return ""
        }
        val want = keyword.trim()
        for (path in USER_POST_PATHS) {
            val url = HttpBridge.BASE + path + "?userid=" + uid + "&offset=0&limit=30"
            var body: String?
            try {
                body = HttpBridge.get(url, BASE_HEADERS)
            } catch (t: Throwable) {
                Log.w("BetterHeybox", "[OwnPostClient] findRecent 请求异常 $path : $t")
                body = null
            }
            if (body.isNullOrEmpty()) {
                continue
            }
            try {
                val hit = pickNewest(JSONObject(body), want, sinceMillis)
                if (hit.isNotEmpty()) {
                    return hit
                }
            } catch (ignored: Throwable) {
            }
        }
        return ""
    }

    private fun pickNewest(node: Any, keyword: String, sinceMillis: Long): String {
        var best = ""
        var bestAt = 0L
        for (it in collect(node)) {
            if (it.linkId.isEmpty() || !it.text.contains(keyword)) {
                continue
            }
            if (it.createdAt > 0L && it.createdAt < sinceMillis) {
                continue
            }
            if (best.isEmpty() || it.createdAt >= bestAt) {
                best = it.linkId
                bestAt = it.createdAt
            }
        }
        return best
    }

    private fun collect(node: Any?): List<Item> {
        val out = ArrayList<Item>()
        gather(node, out, 0)
        return out
    }

    private fun gather(node: Any?, out: MutableList<Item>, depth: Int) {
        if (node == null || depth > 10) {
            return
        }
        if (node is JSONObject) {
            val o = node
            var id = str(o, "link_id", "linkid", "linkId")
            if (id.isEmpty()) {
                val link = o.opt("link")
                if (link is JSONObject) {
                    id = str(link, "link_id", "linkid")
                }
            }
            if (id.isNotEmpty()) {
                val it = Item()
                it.linkId = id
                it.text = (o.optString("description", "") + " " + o.optString("title", "")
                        + " " + o.optString("text", "") + " " + o.optString("content", ""))
                it.createdAt = parseTime(o.optString("create_at", ""))
                out.add(it)
                return
            }
            val it2 = o.keys()
            while (it2.hasNext()) {
                gather(o.opt(it2.next()), out, depth + 1)
            }
            return
        }
        if (node is JSONArray) {
            val arr = node
            for (i in 0 until arr.length()) {
                gather(arr.opt(i), out, depth + 1)
            }
        }
    }

    private fun str(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k)
            if (v != null && v.toString().trim().isNotEmpty()) {
                return v.toString().trim()
            }
        }
        return ""
    }

    private fun parseTime(raw: String?): Long {
        if (raw == null || raw.trim().isEmpty()) {
            return 0L
        }
        val s = raw.trim()
        try {
            val n = s.toLong()
            return if (n > 1000000000000L) n else n * 1000L
        } catch (ignored: Throwable) {
        }
        for (pattern in arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(s)!!.time
            } catch (ignored: Throwable) {
            }
        }
        return 0L
    }

    private fun findItem(node: Any?, id: String): Item? {
        for (it in collect(node)) {
            if (it.linkId == id) {
                return it
            }
        }
        return null
    }
}
