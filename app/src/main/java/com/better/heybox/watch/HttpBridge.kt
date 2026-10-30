package com.better.heybox.watch

import android.util.Log
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import com.better.heybox.ModuleStats
import java.lang.reflect.Method
import java.util.regex.Pattern

object HttpBridge {

    const val BASE = "https://api.xiaoheihe.cn/"

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
    @Volatile private var sAddHeader: Method? = null

    @Volatile private var sDescribe = "未捕获"
    @Volatile private var sModule: MainModule? = null
    @Volatile private var sDiagLogged = false
    @Volatile private var sCaptureInstalled = false

    @Volatile private var sLogFlagAt = 0L
    @Volatile private var sLogFlag = false

    @Volatile private var sHostUserId: String? = null
    @Volatile private var sHeyboxId: String? = null
    @Volatile private var sTemplateUserId: String? = null
    @Volatile private var sClientVersion: String? = null

    private val sRecentTopics = LinkedHashSet<String>()
    private const val RECENT_TOPIC_LIMIT = 20

    private val URL_IN_TOSTRING: Pattern = Pattern.compile("url=([^,\\s]+)")

    private val sOwnRequest = ThreadLocal<Boolean?>()
    private const val LOG_VALUE_MAX = 256
    private const val LOG_URL_SAMPLE_MAX = 1600
    private const val LOG_PARSE_SLOW_MS = 50L

    @JvmStatic
    fun init(module: MainModule?) {
        sModule = module
    }

    @JvmStatic
    fun installCapture(module: MainModule?, cl: ClassLoader?): Boolean {
        if (module == null || cl == null) {
            return false
        }
        init(module)
        if (sCaptureInstalled) {
            return true
        }
        synchronized(HttpBridge::class.java) {
            if (sCaptureInstalled) {
                return true
            }
            for (cn in CLIENT_HOLDERS) {
                try {
                    val holder = Class.forName(cn, false, cl)
                    for (ctor in holder.declaredConstructors) {
                        if (ctor.parameterTypes.size < 2) {
                            continue
                        }
                        module.hook(ctor).intercept { chain ->
                            val result = chain.proceed()
                            try {
                                captureIfClient(chain.arg(0), chain.arg(1), cl)
                            } catch (ignored: Throwable) {
                            }
                            result
                        }
                        sCaptureInstalled = true
                        log(Log.INFO, "宿主 HTTP 客户端捕获 Hook 已安装 ($cn)")
                        return true
                    }
                } catch (ignored: Throwable) {
                }
            }
            log(
                Log.WARN, "未找到可用的 OkHttp 载体类（候选 " + CLIENT_HOLDERS.joinToString(" / ")
                        + " 全部落空），主动拉取不可用"
            )
            return false
        }
    }

    @JvmStatic
    fun captureInstalled(): Boolean = sCaptureInstalled

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
                val joined = sb.toString()
                prefsExecutor().execute {
                    try {
                        HeyboxPrefs.init(App.resolveAppContext())
                        HeyboxPrefs.setString(App.KEY_WATCH_RECENT_TOPICS, joined)
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    @Volatile
    private var sPrefsExecutor: java.util.concurrent.Executor? = null

    private fun prefsExecutor(): java.util.concurrent.Executor {
        var e = sPrefsExecutor
        if (e != null) {
            return e
        }
        synchronized(HttpBridge::class.java) {
            if (sPrefsExecutor == null) {
                sPrefsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                    val t = Thread(r, "betterheybox-prefs")
                    t.isDaemon = true
                    t
                }
            }
            return sPrefsExecutor!!
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
    fun heyboxId(): String? = sHeyboxId

    @JvmStatic
    fun clientVersion(): String? = sClientVersion

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
                captureAccountFromTemplate(request)
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
            if (java.lang.Boolean.TRUE == sOwnRequest.get()) {
                return
            }
            val now = System.currentTimeMillis()
            if (now - sLogFlagAt > 30000L) {
                sLogFlagAt = now
                sLogFlag = sModule?.isEnabled(App.KEY_LOG, false) == true
            }
            if (!sLogFlag) {
                captureAccountIfNeeded(request)
                return
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val raw = request.toString()
            val mt = URL_IN_TOSTRING.matcher(raw)
            if (!mt.find()) {
                return
            }
            val from = mt.start(1)
            val to = mt.end(1)
            if (sHostUserId == null) {
                var uid = firstDigitsParam(raw, from, to, "user_id=")
                if (uid != null && uid.length < 5) {
                    uid = null
                }
                if (uid == null && contains(raw, from, to, "ws.xiaoheihe.cn")) {
                    uid = firstDigitsParam(raw, from, to, "userid=")
                    if (uid != null && uid.length < 5) {
                        uid = null
                    }
                }
                if (uid != null) {
                    sHostUserId = uid
                    log(Log.INFO, "宿主登录 userid = $sHostUserId")
                }
            }
            val topicId = firstDigitsParam(raw, from, to, "topic_id=")
            if (contains(raw, from, to, "topic") || contains(raw, from, to, "hashtag")
                || contains(raw, from, to, "/search")
            ) {
                val cost = android.os.SystemClock.elapsedRealtime() - t0
                if (cost > LOG_PARSE_SLOW_MS) {
                    ModuleStats.slow("宿主请求解析", cost)
                }
                log(
                    Log.INFO, "宿主请求 " + compactUrl(raw, from, to)
                            + " (len=" + (to - from) + ", " + cost + "ms, "
                            + Thread.currentThread().name + ")"
                )
                noteTopicFeedRequest(raw, from, to, topicId)
            }
            if (topicId != null) {
                rememberTopic(topicId)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun contains(raw: String, from: Int, to: Int, needle: String): Boolean {
        val at = raw.indexOf(needle, from)
        return at >= 0 && at < to
    }

    private fun firstDigitsParam(raw: String, from: Int, to: Int, key: String): String? {
        val at = raw.indexOf(key, from)
        if (at < 0 || at >= to) {
            return null
        }
        val begin = at + key.length
        var end = begin
        while (end < to && end - begin < 20) {
            val c = raw[end]
            if (c < '0' || c > '9') {
                break
            }
            end++
        }
        return if (end > begin) raw.substring(begin, end) else null
    }

    private fun intParam(raw: String, from: Int, to: Int, key: String, def: Int): Int {
        val value = firstDigitsParam(raw, from, to, key) ?: return def
        return try {
            Integer.parseInt(value)
        } catch (ignored: Throwable) {
            def
        }
    }

    private fun compactUrl(raw: String, from: Int, to: Int): String {
        val sb = StringBuilder(minOf(to - from, 640) + 32)
        var i = from
        while (i < to && sb.length < LOG_URL_SAMPLE_MAX) {
            var amp = raw.indexOf('&', i)
            if (amp < 0 || amp >= to) {
                amp = to
            }
            val eq = raw.indexOf('=', i)
            if (eq >= 0 && eq < amp) {
                val valueLen = amp - eq - 1
                if (valueLen > LOG_VALUE_MAX) {
                    sb.append(raw, i, eq + 1).append('<').append(valueLen).append("字符>")
                } else {
                    sb.append(raw, i, amp)
                }
            } else {
                sb.append(raw, i, amp)
            }
            if (amp < to) {
                sb.append('&')
            }
            i = amp + 1
        }
        if (i < to) {
            sb.append("…(剩余").append(to - i).append("字符)")
        }
        return sb.toString()
    }

    private const val TOPIC_FEED_TRACK_MAX = 8
    private const val TOPIC_FEED_WINDOW_MS = 15_000L
    private const val TOPIC_FEED_BURST = 5
    private const val TOPIC_FEED_WARN_INTERVAL_MS = 60_000L

    private class FeedTrace {
        val recent = ArrayDeque<Long>()
        var lastOffset = Int.MIN_VALUE
    }

    private val sTopicFeed = LinkedHashMap<String, FeedTrace>()

    @Volatile
    private var sTopicFeedWarnAt = 0L

    private fun noteTopicFeedRequest(raw: String, from: Int, to: Int, topicId: String?) {
        if (topicId == null || !contains(raw, from, to, "bbs/app/topic/feeds")) {
            return
        }
        val offset = intParam(raw, from, to, "offset=", -1)
        ModuleStats.topicFeedRequests.incrementAndGet()
        val now = System.currentTimeMillis()
        val inWindow: Int
        synchronized(sTopicFeed) {
            var trace = sTopicFeed[topicId]
            if (trace == null) {
                trace = FeedTrace()
                sTopicFeed[topicId] = trace
                while (sTopicFeed.size > TOPIC_FEED_TRACK_MAX) {
                    val it = sTopicFeed.keys.iterator()
                    it.next()
                    it.remove()
                }
            }
            if (offset > trace.lastOffset) {
                trace.recent.addLast(now)
            }
            trace.lastOffset = offset
            while (!trace.recent.isEmpty() && now - trace.recent.first() > TOPIC_FEED_WINDOW_MS) {
                trace.recent.removeFirst()
            }
            inWindow = trace.recent.size
        }
        if (inWindow < TOPIC_FEED_BURST) {
            return
        }
        if (now - sTopicFeedWarnAt < TOPIC_FEED_WARN_INTERVAL_MS) {
            return
        }
        sTopicFeedWarnAt = now
        ModuleStats.topicFeedStallRuns.incrementAndGet()
        log(
            Log.WARN, "[#41] 话题信息流疑似分页空转：topic_id=" + topicId
                    + " " + (TOPIC_FEED_WINDOW_MS / 1000) + "s 内翻页请求=" + inWindow + " 次"
                    + "（最新 offset=" + offset
                    + "，数据层条目=" + ModuleStats.bbsListItemsSeen.get()
                    + " 删除=" + ModuleStats.bbsListItemsDropped.get()
                    + " 视图隐藏=" + ModuleStats.bbsListItemsHidden.get() + "）"
        )
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
            for (m in t.methods) {
                val ps = m.parameterTypes
                if (ps.size == 2 && ps[0] == String::class.java && ps[1] == String::class.java
                    && m.returnType == t
                ) {
                    sAddHeader = m
                    break
                }
            }
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
    fun postForm(url: String, headers: Map<String, String>?, fields: Map<String, String>?): String? {
        if (fields.isNullOrEmpty()) {
            log(Log.WARN, "postForm 缺少表单字段，跳过 " + shortUrl(url))
            return null
        }
        return request("POST", url, headers) { createFormBody(fields) }
    }

    @JvmStatic
    fun postJson(url: String, headers: Map<String, String>?, json: String?): String? {
        if (json == null) {
            return null
        }
        return request("POST", url, headers) { createStringBody("application/json; charset=utf-8", json) }
    }

    private fun request(
        method: String, url: String, headers: Map<String, String>?, bodyFactory: (() -> Any?)?
    ): String? {
        if (!ready()) {
            log(Log.WARN, "HTTP 客户端尚未捕获，跳过请求 " + shortUrl(url))
            return null
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            log(
                Log.WARN, "【严重】HttpBridge.request() 在主线程被调用，会卡死 UI！"
                        + " url=" + shortUrl(url) + " 调用栈见日志"
            )
            try {
                throw IllegalStateException("HttpBridge.request on main thread")
            } catch (e: IllegalStateException) {
                log(Log.WARN, "主线程调用栈：" + android.util.Log.getStackTraceString(e))
            }
        }
        val client = sClient
        val template = sTemplate
        val req: Any?
        try {
            val builder = sNewBuilder!!.invoke(template)
            if (bodyFactory != null) {
                if (sMethod == null) {
                    log(Log.WARN, "未解析到 Request.Builder.method，无法发起 $method " + shortUrl(url))
                    return null
                }
                val body = try {
                    bodyFactory()
                } catch (t: Throwable) {
                    log(Log.WARN, "构造请求体失败: $t")
                    null
                }
                if (body == null) {
                    log(Log.WARN, "请求体不可用，跳过 $method " + shortUrl(url))
                    return null
                }
                sMethod!!.invoke(builder, method, body)
            } else if (sMethod != null) {
                try {
                    sMethod!!.invoke(builder, "GET", null)
                } catch (ignored: Throwable) {
                }
            }
            sUrl!!.invoke(builder, url)
            if (headers != null && sAddHeader != null) {
                for ((key, value) in headers) {
                    sAddHeader!!.invoke(builder, key, value)
                }
            }
            req = sBuild!!.invoke(builder)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            log(Log.WARN, "构造请求失败: $t / cause=$cause")
            return null
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val ownPrev = sOwnRequest.get()
        sOwnRequest.set(java.lang.Boolean.TRUE)
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
        } finally {
            if (ownPrev == null) {
                sOwnRequest.remove()
            } else {
                sOwnRequest.set(ownPrev)
            }
        }
    }


    private fun captureAccountIfNeeded(request: Any?) {
        if (request == null || (sHeyboxId != null && sClientVersion != null)) {
            return
        }
        captureAccountFromTemplate(request)
    }

    private fun captureAccountFromTemplate(request: Any) {
        try {
            if (sHeyboxId != null && sClientVersion != null) {
                return
            }
            val raw = request.toString()
            val mt = URL_IN_TOSTRING.matcher(raw)
            if (!mt.find()) {
                return
            }
            val from = mt.start(1)
            val to = mt.end(1)
            if (sHeyboxId == null) {
                val id = firstDigitsParam(raw, from, to, "heybox_id=")
                if (id != null && id.length >= 4) {
                    sHeyboxId = id
                    log(Log.INFO, "宿主 heybox_id = $id")
                }
            }
            if (sTemplateUserId == null) {
                var uid = firstDigitsParam(raw, from, to, "user_id=")
                if (uid != null && uid.length < 5) {
                    uid = null
                }
                if (uid != null) {
                    sTemplateUserId = uid
                }
            }
            if (sClientVersion == null) {
                val v = firstParam(raw, from, to, "x_client_version=")
                if (!v.isNullOrEmpty()) {
                    sClientVersion = v
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun firstParam(raw: String, from: Int, to: Int, key: String): String? {
        val at = raw.indexOf(key, from)
        if (at < 0 || at >= to) {
            return null
        }
        val begin = at + key.length
        var end = begin
        while (end < to && raw[end] != '&') {
            end++
        }
        return if (end > begin) raw.substring(begin, end) else null
    }

    private fun createFormBody(fields: Map<String, String>): Any? {
        val cl = sClient?.javaClass?.classLoader ?: return null
        try {
            val builderCls = Class.forName("okhttp3.FormBody\$Builder", false, cl)
            val builder = builderCls.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.newInstance() ?: return null
            val add = builderCls.getMethod("add", String::class.java, String::class.java)
            for ((key, value) in fields) {
                add.invoke(builder, key, value)
            }
            return builderCls.getMethod("build").invoke(builder)
        } catch (t: Throwable) {
            log(Log.WARN, "FormBody 反射构造失败，退回字符串请求体: $t")
        }
        val sb = StringBuilder()
        for ((key, value) in fields) {
            if (sb.isNotEmpty()) {
                sb.append('&')
            }
            try {
                sb.append(java.net.URLEncoder.encode(key, "UTF-8")).append('=')
                    .append(java.net.URLEncoder.encode(value, "UTF-8"))
            } catch (ignored: Throwable) {
                return null
            }
        }
        return createStringBody("application/x-www-form-urlencoded", sb.toString())
    }

    private fun createStringBody(contentType: String, content: String): Any? {
        val cl = sClient?.javaClass?.classLoader ?: return null
        return try {
            val mediaType = Class.forName("okhttp3.MediaType", false, cl)
                .getMethod("parse", String::class.java).invoke(null, contentType)
            val bodyCls = Class.forName("okhttp3.RequestBody", false, cl)
            for (m in bodyCls.methods) {
                val ps = m.parameterTypes
                if ("create" == m.name && java.lang.reflect.Modifier.isStatic(m.modifiers)
                    && ps.size == 2 && ps[0] == mediaType.javaClass && ps[1] == String::class.java
                ) {
                    return m.invoke(null, mediaType, content)
                }
            }
            null
        } catch (t: Throwable) {
            log(Log.WARN, "RequestBody 反射构造失败: $t")
            null
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
