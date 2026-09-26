package com.better.heybox.watch

import android.util.Log
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.util.regex.Matcher
import java.util.regex.Pattern

object HttpBridge {

    @JvmField
    val CLIENT_HOLDERS = arrayOf(
        "okhttp3.internal.connection.RealCall",
        "okhttp3.RealCall"
    )

    @Volatile private var sClient: Any? = null
    @Volatile private var sNewCall: Method? = null
    @Volatile private var sExecute: Method? = null
    @Volatile private var sCode: Method? = null
    @Volatile private var sBody: Method? = null
    @Volatile private var sString: Method? = null
    @Volatile private var sNewBuilder: Method? = null
    @Volatile private var sUrl: Method? = null
    @Volatile private var sBuild: Method? = null
    @Volatile private var sMethod: Method? = null
    @Volatile private var sTemplate: Any? = null

    @Volatile private var sDescribe = "未捕获"
    @Volatile private var sModule: MainModule? = null
    @Volatile private var sDiagLogged = false

    @Volatile private var sLogFlagAt = 0L
    @Volatile private var sLogFlag = false

    @Volatile private var sHostUserId: String? = null

    private val HOST_UID: Pattern = Pattern.compile("user_id=(\\d{5,20})")
    private val ANY_UID: Pattern = Pattern.compile("userid=(\\d{5,20})")
    private val TOPIC_ID: Pattern = Pattern.compile("topic_id=(\\d{1,20})")

    private val sRecentTopics = LinkedHashSet<String>()
    private const val RECENT_TOPIC_LIMIT = 20

    private val URL_IN_TOSTRING: Pattern = Pattern.compile("url=([^,\\s]+)")

    @JvmStatic
    fun init(module: MainModule?) {
        sModule = module
    }

    @JvmStatic
    fun ready(): Boolean = sClient != null && sNewCall != null && sExecute != null

    private fun rememberTopic(id: String?) {
        if (id.isNullOrEmpty()) {
            return
        }
        var changed = false
        synchronized(sRecentTopics) {
            if (!sRecentTopics.contains(id)) {
                sRecentTopics.add(id)
                while (sRecentTopics.size > RECENT_TOPIC_LIMIT) {
                    val it = sRecentTopics.iterator()
                    it.next()
                    it.remove()
                }
                changed = true
            }
        }
        if (changed) {
            try {
                val sb = StringBuilder()
                synchronized(sRecentTopics) {
                    for (t in sRecentTopics) {
                        sb.append(t).append(',')
                    }
                }
                HeyboxPrefs.init(App.resolveAppContext())
                HeyboxPrefs.setString(App.KEY_WATCH_RECENT_TOPICS, sb.toString())
            } catch (ignored: Throwable) {
            }
        }
    }

    @JvmStatic
    fun recentTopicIds(): MutableList<String> {
        val out = ArrayList<String>()
        synchronized(sRecentTopics) {
            val arr = sRecentTopics.toTypedArray()
            for (i in arr.indices.reversed()) {
                out.add(arr[i])
            }
        }
        return out
    }

    @JvmStatic
    fun hostUserId(): String? = sHostUserId

    @JvmStatic
    fun describe(): String = sDescribe

    @JvmStatic
    fun captureIfClient(client: Any?, request: Any?, cl: ClassLoader?): Boolean {
        logHostRequest(request)
        if (client == null) {
            return false
        }
        if (ready()) {
            return true
        }
        synchronized(HttpBridge) {
            if (ready()) {
                return true
            }
            try {
                if (request == null) {
                    return false
                }
                val reqCls = request.javaClass
                if (!resolveRequestBuilder(reqCls) || !resolveCallChain(client.javaClass, reqCls)) {
                    dumpDiagnostics(client, request)
                    return false
                }
                sTemplate = request
                sClient = client
                sDescribe = "client=" + client.javaClass.name +
                        "#" + sNewCall!!.name +
                        " request=" + reqCls.name + "#" + sNewBuilder!!.name +
                        " builder=" + sBuild!!.declaringClass.name + "#" + sBuild!!.name +
                        " call=" + sExecute!!.name +
                        " code=" + sCode!!.name +
                        " body=" + sBody!!.name + "/" + sString!!.name
                log(Log.INFO, "已捕获宿主 HTTP 客户端：$sDescribe")
                WatchEngine.onNetworkCaptured()
                return true
            } catch (t: Throwable) {
                log(Log.WARN, "识别客户端失败：$t")
                return false
            }
        }
    }

    private fun logHostRequest(request: Any?) {
        try {
            if (request == null) {
                return
            }
            val now = System.currentTimeMillis()
            if (now - sLogFlagAt > 30000L) {
                sLogFlagAt = now
                sLogFlag = sModule?.isEnabled(App.KEY_LOG, false) == true
            }
            if (!sLogFlag) {
                return
            }
            val mt: Matcher = URL_IN_TOSTRING.matcher(request.toString())
            if (!mt.find()) {
                return
            }
            val url = mt.group(1)
            if (sHostUserId == null) {
                var um: Matcher = HOST_UID.matcher(url)
                if (!um.find() && url.contains("ws.xiaoheihe.cn")) {
                    um = ANY_UID.matcher(url)
                }
                if (um.find()) {
                    sHostUserId = um.group(1)
                    log(Log.INFO, "宿主登录 userid = $sHostUserId")
                }
            }
            if (url.contains("topic") || url.contains("hashtag") || url.contains("/search")) {
                log(Log.INFO, "宿主请求 $url")
            }
            val tm = TOPIC_ID.matcher(url)
            if (tm.find()) {
                rememberTopic(tm.group(1))
            }
        } catch (ignored: Throwable) {
        }
    }


    private fun resolveRequestBuilder(reqCls: Class<*>): Boolean {
        for (nb in reqCls.methods) {
            if (nb.parameterCount != 0) {
                continue
            }
            val t = nb.returnType
            if (t == reqCls || t.isPrimitive || t == Void.TYPE || t == String::class.java) {
                continue
            }
            var urlSetter: Method? = null
            var build: Method? = null
            for (m in t.methods) {
                val ps = m.parameterTypes
                if (ps.size == 1 && ps[0] == String::class.java &&
                    m.returnType == t && urlSetter == null
                ) {
                    urlSetter = m
                }
                if (ps.size == 0 && m.returnType == reqCls && build == null) {
                    build = m
                }
            }
            if (urlSetter == null || build == null) {
                continue
            }
            var methodSetter: Method? = null
            for (m in t.methods) {
                val ps = m.parameterTypes
                if (ps.size == 2 && ps[0] == String::class.java && ps[1] != String::class.java &&
                    !ps[1].isPrimitive && m.returnType == t
                ) {
                    methodSetter = m
                    break
                }
            }
            sNewBuilder = nb
            sUrl = urlSetter
            sBuild = build
            sMethod = methodSetter
            return true
        }
        return false
    }

    private fun resolveCallChain(clientCls: Class<*>, reqCls: Class<*>): Boolean {
        for (nc in clientCls.methods) {
            val ps = nc.parameterTypes
            if (ps.size != 1 || !ps[0].isAssignableFrom(reqCls)) {
                continue
            }
            val callCls = nc.returnType
            if (callCls.isPrimitive || callCls == Void.TYPE) {
                continue
            }
            for (ex in callCls.methods) {
                if (ex.parameterCount != 0) {
                    continue
                }
                val respCls = ex.returnType
                if (respCls.isPrimitive || respCls == Void.TYPE || respCls == String::class.java) {
                    continue
                }
                var code: Method? = null
                var body: Method? = null
                var string: Method? = null
                for (rm in respCls.methods) {
                    if (rm.parameterCount != 0 || isObjectMethod(rm)) {
                        continue
                    }
                    if (rm.returnType == Int::class.javaPrimitiveType) {
                        if (code == null) {
                            code = rm
                        }
                        continue
                    }
                    if (body != null || rm.returnType.isPrimitive ||
                        rm.returnType == String::class.java
                    ) {
                        continue
                    }
                    val s = findStringMethod(rm.returnType)
                    if (s != null) {
                        body = rm
                        string = s
                    }
                }
                if (code == null || body == null || string == null) {
                    continue
                }
                sNewCall = nc
                sExecute = ex
                sCode = code
                sBody = body
                sString = string
                return true
            }
        }
        return false
    }

    private fun isObjectMethod(m: Method): Boolean {
        if (m.declaringClass == Any::class.java) {
            return true
        }
        val n = m.name
        return "toString" == n || "hashCode" == n || "getClass" == n
    }

    private fun findStringMethod(c: Class<*>): Method? {
        for (m in c.methods) {
            if (m.parameterCount == 0 && m.returnType == String::class.java && !isObjectMethod(m)) {
                return m
            }
        }
        return null
    }

    private fun dumpDiagnostics(client: Any, request: Any?) {
        if (sDiagLogged) {
            return
        }
        sDiagLogged = true
        try {
            log(
                Log.WARN,
                "结构识别失败 client=" + client.javaClass.name +
                        " request=" + (request?.javaClass?.name ?: "null")
            )
            var n = 0
            for (m in client.javaClass.methods) {
                if (m.parameterCount > 1) {
                    continue
                }
                val sb = StringBuilder("  client#")
                sb.append(m.name).append('(')
                val ps = m.parameterTypes
                for (i in ps.indices) {
                    sb.append(if (i > 0) ", " else "").append(ps[i].simpleName)
                }
                sb.append(") -> ").append(m.returnType.simpleName)
                log(Log.WARN, sb.toString())
                if (++n >= 15) {
                    break
                }
            }
        } catch (ignored: Throwable) {
        }
    }


    @JvmStatic
    fun get(url: String, headers: Map<String, String>?): String? =
        request("GET", url, headers, null)

    @JvmStatic
    fun postJson(url: String, headers: Map<String, String>?, json: String?): String? =
        request("POST", url, headers, json)

    private fun request(
        method: String, url: String, headers: Map<String, String>?, body: String?
    ): String? {
        if (!ready()) {
            log(Log.WARN, "HTTP 客户端尚未捕获，跳过请求 " + shortUrl(url))
            return null
        }
        val client = sClient
        val template = sTemplate
        val req: Any?
        try {
            val builder = sNewBuilder!!.invoke(template)
            if (sMethod != null) {
                try {
                    sMethod!!.invoke(builder, "GET", null)
                } catch (ignored: Throwable) {
                }
            }
            sUrl!!.invoke(builder, url)
            if (headers != null) {
                var addHeader: Method? = null
                for (m in builder.javaClass.methods) {
                    val ps = m.parameterTypes
                    if (ps.size == 2 && ps[0] == String::class.java && ps[1] == String::class.java &&
                        m.returnType == builder.javaClass
                    ) {
                        addHeader = m
                        break
                    }
                }
                if (addHeader != null) {
                    for ((key, value) in headers) {
                        addHeader.invoke(builder, key, value)
                    }
                }
            }
            req = sBuild!!.invoke(builder)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            log(Log.WARN, "构造请求失败: $t / cause=$cause")
            return null
        }
        if ("GET" != method && body != null) {
            log(Log.WARN, "暂不支持 $method 请求，已跳过 " + shortUrl(url))
            return null
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val call = sNewCall!!.invoke(client, req)
            val resp = sExecute!!.invoke(call)
            val code = sCode!!.invoke(resp) as Int
            val respBody = sBody!!.invoke(resp)
            val text = if (respBody == null) null else sString!!.invoke(respBody) as String
            val cost = android.os.SystemClock.elapsedRealtime() - t0
            log(
                Log.INFO,
                "$method " + shortUrl(url) + " → HTTP " + code +
                        " (" + cost + "ms, " + (text?.length ?: 0) + " 字符)"
            )
            if (text != null && text.isNotEmpty() && (code < 200 || code >= 300)) {
                log(Log.WARN, "响应片段：" + clip(text, 180))
            }
            return text
        } catch (t: Throwable) {
            log(Log.WARN, "请求失败 " + shortUrl(url) + " : " + t)
            return null
        }
    }

    private fun shortUrl(url: String): String {
        val q = url.indexOf('?')
        return if (q > 0) url.substring(0, q) else url
    }

    private fun clip(s: String, n: Int): String =
        if (s.length <= n) s else s.substring(0, n) + "…"

    private fun log(level: Int, msg: String) {
        sModule?.logd(level, MainModule.TAG, msg)
    }
}
