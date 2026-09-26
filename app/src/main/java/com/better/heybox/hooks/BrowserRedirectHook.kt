package com.better.heybox.hooks

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.HashSet
import java.util.Locale

class BrowserRedirectHook(private val module: MainModule) {

    private var hostClassLoader: ClassLoader? = null

    private val hookedClients = HashSet<Class<*>>()
    private val hookedMethods = HashSet<Method>()

    @Volatile private var cachedTarget: String? = null

    @Volatile private var cachedTargetUsable = false

    fun install(cl: ClassLoader) {
        hostClassLoader = cl
        var installed = 0
        installed += hookActivityStart()
        for (className in ENTRY_ACTIVITIES) {
            val activity = try {
                Class.forName(className, false, cl)
            } catch (t: Throwable) {
                continue
            }
            try {
                val onCreate = findMethodInHierarchy(activity, "onCreate", Bundle::class.java)
                    ?: throw NoSuchMethodException("onCreate(Bundle) not declared")
                module.hook(onCreate).intercept { chain ->
                    chain.proceed()
                    try {
                        val self = chain.getThisObject()
                        if (self is Activity) {
                            handleEntry(self)
                        }
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "浏览器重定向入口处理失败", t)
                    }
                    null
                }
                installed++
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 浏览器重定向入口 Hook 失败: $className", t)
            }
        }

        var loads = 0
        try {
            for (method in WebView::class.java.declaredMethods) {
                if ("loadUrl" != method.name) {
                    continue
                }
                val params = method.parameterTypes
                if (params.size < 1 || params[0] != String::class.java) {
                    continue
                }
                module.hook(method).intercept { chain ->
                    val arg = chain.getArg(0)
                    if (arg is String && shouldRedirect(arg)) {
                        val url = arg
                        val self = chain.getThisObject()
                        val webView = self as? WebView
                        val context = webView?.context
                        if (!canCloseContainer(context)) {
                            module.logd(
                                Log.WARN, MainModule.TAG,
                                "跳过重定向(容器不可关闭): " +
                                        (context?.javaClass?.name ?: "unknown") + " " + url
                            )
                        } else if (isMiniProgramContainer(context) && !forcedByUser(url)) {
                            module.logd(Log.INFO, MainModule.TAG, "跳过重定向(小程序页面): $url")
                        } else {
                            redirectLoadedPage(webView!!, url)
                            return@intercept null
                        }
                    }
                    chain.proceed()
                }
                loads++
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ loadUrl 拦截 Hook 失败", t)
        }

        var clients = 0
        try {
            val setter = WebView::class.java.getDeclaredMethod(
                "setWebViewClient", WebViewClient::class.java
            )
            module.hook(setter).intercept { chain ->
                val client = chain.getArg(0)
                if (client != null) {
                    hookClientClass(client.javaClass)
                }
                chain.proceed()
            }
            clients = 1
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 浏览器重定向页内跳转 Hook 失败", t)
        }

        var chrome = 0
        try {
            val setter = WebView::class.java.getDeclaredMethod(
                "setWebChromeClient", WebChromeClient::class.java
            )
            module.hook(setter).intercept { chain ->
                val client = chain.getArg(0)
                if (client != null) {
                    hookChromeClientClass(client.javaClass)
                }
                chain.proceed()
            }
            chrome = 1
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 网页日志标题 Hook 失败", t)
        }

        if (installed > 0 || clients > 0 || loads > 0) {
            module.logd(
                Log.INFO, MainModule.TAG,
                "✔ 浏览器重定向 Hook 已安装（启动拦截 $installed" +
                        "，页内跳转 " + (if (clients > 0) "已挂" else "未挂") +
                        "，loadUrl $loads" +
                        "，标题 " + (if (chrome > 0) "已挂" else "未挂") + "）"
            )
        } else {
            module.logd(Log.WARN, MainModule.TAG, "✘ 浏览器重定向 Hook 未命中任何拦截点")
        }
    }

    private fun findMethodInHierarchy(start: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *paramTypes)
            } catch (ignored: NoSuchMethodException) {
            }
            c = c.superclass
        }
        return null
    }

    private fun redirectLoadedPage(webView: WebView, url: String) {
        openExternal(webView.context, url)
        try {
            val context = webView.context
            if (context is Activity && isWebContainer(context.javaClass)) {
                context.finish()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun handleEntry(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (!isWebContainer(activity.javaClass)) {
            return
        }
        val url = activity.intent?.let { entryUrl(it) }
        if (!shouldRedirect(url)) {
            return
        }
        if (isMiniProgramContainer(activity.javaClass) && !forcedByUser(url!!)) {
            module.logd(Log.INFO, MainModule.TAG, "跳过重定向(小程序页面·入口): $url")
            return
        }
        openExternal(activity, url!!)
        try {
            activity.overridePendingTransition(0, 0)
        } catch (ignored: Throwable) {
        }
        activity.finish()
    }

    private fun hookActivityStart(): Int {
        var installed = 0
        try {
            for (method in android.app.Instrumentation::class.java.declaredMethods) {
                if ("execStartActivity" != method.name) {
                    continue
                }
                val params = method.parameterTypes
                if (params.size < 7 || params[4] != Intent::class.java) {
                    continue
                }
                module.hook(method).intercept { chain ->
                    try {
                        val args = chain.getArgs()
                        if (args.size > 4 && args[4] is Intent) {
                            redirectEntryIntent(args[4] as Intent)
                        }
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "浏览器重定向启动拦截失败", t)
                    }
                    chain.proceed()
                }
                installed++
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ Instrumentation 启动拦截 Hook 失败", t)
        }
        return installed
    }

    private fun redirectEntryIntent(intent: Intent) {
        val component: ComponentName = intent.component ?: return
        val className = component.className
        val target = resolveTargetClass(className)
        val container = if (target != null) {
            isWebContainer(target)
        } else {
            ENTRY_ACTIVITY_CLASSES.contains(className)
        }
        if (!container) {
            return
        }
        val url = entryUrl(intent)
        if (!shouldRedirect(url)) {
            return
        }
        if (target != null && isMiniProgramContainer(target) && !forcedByUser(url!!)) {
            module.logd(Log.INFO, MainModule.TAG, "跳过重定向(小程序页面·启动): $url")
            return
        }
        intent.setAction(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), null)
            .setComponent(null)
            .setPackage(null)
            .replaceExtras(null as Bundle?)
        applyTargetPackage(intent)
        module.logd(Log.INFO, MainModule.TAG, "浏览器重定向(启动): $url")
    }

    private fun applyTargetPackage(intent: Intent) {
        try {
            val target = module.getString(App.KEY_BROWSER_TARGET, "")
            if (target.isNullOrEmpty()) {
                return
            }
            if (target != cachedTarget) {
                cachedTarget = target
                cachedTargetUsable = isBrowserResolvable(target)
                if (!cachedTargetUsable) {
                    module.logd(Log.WARN, MainModule.TAG, "指定浏览器不可用: $target，回退系统解析")
                }
            }
            if (cachedTargetUsable) {
                intent.setPackage(target)
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "指定浏览器失败", t)
        }
    }

    private fun isBrowserResolvable(pkg: String): Boolean {
        return try {
            val context = App.resolveAppContext() ?: return false
            val pm = context.packageManager
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/"))
            probe.setPackage(pkg)
            pm.resolveActivity(probe, 0) != null
        } catch (t: Throwable) {
            false
        }
    }

    private fun hookClientClass(clientClass: Class<*>?) {
        if (clientClass == null || clientClass == WebViewClient::class.java) {
            return
        }
        val name = clientClass.name
        if (isFrameworkClass(name)) {
            return
        }
        synchronized(hookedClients) {
            if (!hookedClients.add(clientClass)) {
                return
            }
        }
        var hooked = 0
        for (method in findHookTargets(clientClass, "shouldOverrideUrlLoading")) {
            val params = method.parameterTypes
            if (params.size != 2 || params[0] != WebView::class.java ||
                method.returnType != Boolean::class.javaPrimitiveType
            ) {
                continue
            }
            val isRequestVariant = params[1] == WebResourceRequest::class.java
            if (!isRequestVariant && params[1] != String::class.java) {
                continue
            }
            try {
                module.hook(method).intercept { chain ->
                    val args = chain.getArgs()
                    val webView = if (args.isNotEmpty() && args[0] is WebView) {
                        args[0] as WebView
                    } else {
                        null
                    }
                    val url = extractUrl(args)
                    if (webView != null && isMainFrameRequest(isRequestVariant, args) &&
                        shouldRedirect(url)
                    ) {
                        openExternal(webView.context, url!!)
                        module.logd(Log.INFO, MainModule.TAG, "浏览器重定向(页内): $url")
                        return@intercept true
                    }
                    chain.proceed()
                }
                hooked++
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "shouldOverrideUrlLoading Hook 失败: $name", t)
            }
        }
        if (name.startsWith("com.max.")) {
            for (method in findHookTargets(clientClass, "onPageStarted")) {
                val params = method.parameterTypes
                if (params.size != 3 || params[0] != WebView::class.java ||
                    params[1] != String::class.java
                ) {
                    continue
                }
                try {
                    module.hook(method).intercept { chain ->
                        val args = chain.getArgs()
                        if (args.size >= 2 && args[0] is WebView && args[1] is String) {
                            recordPageStart(args[0] as WebView, args[1] as String)
                        }
                        chain.proceed()
                    }
                    hooked++
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "onPageStarted Hook 失败: $name", t)
                }
            }
        }
        if (hooked > 0) {
            module.logd(Log.INFO, MainModule.TAG, "✔ 浏览器重定向已代理 WebViewClient: $name")
        }
    }

    private fun hookChromeClientClass(clientClass: Class<*>?) {
        if (clientClass == null || clientClass == WebChromeClient::class.java ||
            isFrameworkClass(clientClass.name) || !clientClass.name.startsWith("com.max.")
        ) {
            return
        }
        for (method in findHookTargets(clientClass, "onReceivedTitle")) {
            val params = method.parameterTypes
            if (params.size != 2 || params[0] != WebView::class.java ||
                params[1] != String::class.java
            ) {
                continue
            }
            try {
                module.hook(method).intercept { chain ->
                    val args = chain.getArgs()
                    if (args.size >= 2 && args[0] is WebView && args[1] is String) {
                        recordTitle(args[0] as WebView, args[1] as String)
                    }
                    chain.proceed()
                }
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "✔ 网页日志已代理 WebChromeClient: " + clientClass.name
                )
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "onReceivedTitle Hook 失败: " + clientClass.name, t)
            }
        }
    }

    private fun findHookTargets(start: Class<*>, name: String): MutableList<Method> {
        val out = ArrayList<Method>()
        var c: Class<*>? = start
        while (c != null && !isFrameworkClass(c.name)) {
            var declared = false
            for (method in c.declaredMethods) {
                if (name != method.name) {
                    continue
                }
                declared = true
                if (hookedMethods.add(method)) {
                    out.add(method)
                }
            }
            if (declared) {
                break
            }
            c = c.superclass
        }
        return out
    }

    private fun extractUrl(args: List<Any?>): String? {
        for (i in 1 until args.size) {
            val arg = args[i]
            if (arg is String) {
                return arg
            }
            if (arg != null) {
                try {
                    val getUrl = arg.javaClass.getMethod("getUrl")
                    val url = getUrl.invoke(arg)
                    if (url is String) {
                        return url
                    }
                } catch (ignored: Throwable) {
                }
            }
        }
        return null
    }

    private fun isMainFrameRequest(isRequestVariant: Boolean, args: List<Any?>): Boolean {
        if (!isRequestVariant) {
            return true
        }
        for (arg in args) {
            if (arg != null) {
                try {
                    val isForMainFrame = arg.javaClass.getMethod("isForMainFrame")
                    val flag = isForMainFrame.invoke(arg)
                    if (flag is Boolean) {
                        return flag
                    }
                } catch (ignored: Throwable) {
                }
            }
        }
        return true
    }

    private fun resolveTargetClass(className: String): Class<*>? {
        return try {
            val cl = hostClassLoader
            if (cl == null) Class.forName(className) else Class.forName(className, false, cl)
        } catch (t: Throwable) {
            null
        }
    }

    private fun entryUrl(intent: Intent): String? {
        val url = intent.getStringExtra("pageurl")
        if (!url.isNullOrBlank()) {
            return url
        }
        return try {
            val protocol = intent.getSerializableExtra("web_protocol") ?: return null
            val cfg = invokeNoArg(protocol, "getWebview")
            val value = if (cfg == null) null else invokeNoArg(cfg, "getUrl")
            value as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        return try {
            target.javaClass.getMethod(name).invoke(target)
        } catch (t: Throwable) {
            null
        }
    }

    private fun openExternal(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applyTargetPackage(intent)
            context.startActivity(intent)
            module.logd(Log.INFO, MainModule.TAG, "浏览器重定向(打开): $url")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "外部浏览器打开失败: $url", t)
        }
    }

    private fun forcedByUser(url: String?): Boolean =
        matchesDomain(hostOf(url), parseDomains(module.getString(App.KEY_BROWSER_REDIRECT_FORCE, "")))

    fun shouldRedirect(url: String?): Boolean {
        if (url == null) {
            return false
        }
        if (!module.isEnabled(App.KEY_BROWSER_REDIRECT, false)) {
            return false
        }
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false
        }
        val uri = Uri.parse(trimmed)
        val host = hostOf(trimmed)
        val path = (uri.path?.lowercase() ?: "") + "?" + (uri.query?.lowercase() ?: "")

        val blocked = parseDomains(module.getString(App.KEY_BROWSER_REDIRECT_BLOCK, ""))
        if (matchesDomain(host, blocked)) {
            return false
        }
        val forced = parseDomains(module.getString(App.KEY_BROWSER_REDIRECT_FORCE, ""))
        if (matchesDomain(host, forced)) {
            return true
        }
        if (isHostAppPage(host)) {
            return false
        }
        var knownHost = false
        for (suffix in KNOWN_HOST_SUFFIXES) {
            if (host == suffix || host.endsWith(".$suffix")) {
                knownHost = true
                break
            }
        }
        if (knownHost && !module.isEnabled(App.KEY_BROWSER_REDIRECT_KNOWN, false)) {
            return false
        }
        if (path.contains(".apk")) {
            return false
        }
        for (keyword in SENSITIVE_KEYWORDS) {
            if (path.contains(keyword)) {
                return false
            }
        }
        return true
    }


    private class LogEntry(val at: Long, val url: String, var title: String?)

    private fun recordPageStart(webView: WebView?, url: String?) {
        if (!module.isEnabled(App.KEY_WEB_LOG, false) || url.isNullOrEmpty()) {
            return
        }
        synchronized(logLock) {
            logEntries.addFirst(LogEntry(System.currentTimeMillis(), url, ""))
            while (logEntries.size > LOG_MAX_ENTRIES) {
                logEntries.removeLast()
            }
        }
        persistLog()
    }

    private fun recordTitle(webView: WebView, title: String?) {
        if (!module.isEnabled(App.KEY_WEB_LOG, false) || title.isNullOrEmpty()) {
            return
        }
        var url: String? = null
        try {
            url = webView.url
        } catch (ignored: Throwable) {
        }
        if (url == null) {
            return
        }
        synchronized(logLock) {
            for (entry in logEntries) {
                if (url == entry.url) {
                    if (title == entry.title) {
                        return
                    }
                    entry.title = title
                    break
                }
            }
        }
        persistLog()
    }

    private fun persistLog() {
        try {
            HeyboxPrefs.setString(App.KEY_WEB_LOG_DATA, serializeLog())
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "网页日志写入失败", t)
        }
    }

    private fun serializeLog(): String {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        synchronized(logLock) {
            for (entry in logEntries) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                }
                sb.append('[').append(fmt.format(Date(entry.at))).append("] ")
                    .append(if (entry.title.isNullOrEmpty()) "（无标题）" else entry.title)
                    .append('\n').append(entry.url)
            }
        }
        return sb.toString()
    }

    companion object {
        private val ENTRY_ACTIVITIES = arrayOf(
            "com.max.xiaoheihe.module.webview.WebActionActivity",
            "com.max.xiaoheihe.module.webview.NativeWebActionActivity"
        )

        private val ENTRY_ACTIVITY_CLASSES: Set<String> = HashSet(
            listOf(
                "com.max.xiaoheihe.module.webview.WebActionActivity",
                "com.max.xiaoheihe.module.webview.NativeWebActionActivity",
                "com.max.xiaoheihe.module.webview.TransparentWebActionActivity"
            )
        )

        private val CONTAINER_ROOT_CLASSES = arrayOf(
            "com.max.xiaoheihe.module.webview.WebActionActivity",
            "com.max.xiaoheihe.module.webview.NativeWebActionActivity"
        )

        private fun isWebContainer(cls: Class<*>): Boolean {
            var c: Class<*>? = cls
            while (c != null) {
                val name = c.name
                for (root in CONTAINER_ROOT_CLASSES) {
                    if (root == name) {
                        return true
                    }
                }
                c = c.superclass
            }
            return false
        }

        private fun canCloseContainer(context: Context?): Boolean {
            if (context !is Activity) {
                return false
            }
            return !context.isFinishing && !context.isDestroyed &&
                    isWebContainer(context.javaClass)
        }

        private val KNOWN_HOST_SUFFIXES = arrayOf(
            "xiaoheihe.cn", "maxjia.com", "max-c.com", "dotamax.com", "debugmode.cn", "heybox.hk"
        )

        private val HOST_APP_PAGE_HOSTS = arrayOf(
            "web.xiaoheihe.cn",
            "web.debugmode.cn"
        )

        private fun isHostAppPage(host: String): Boolean {
            for (h in HOST_APP_PAGE_HOSTS) {
                if (host == h || host.endsWith(".$h")) {
                    return true
                }
            }
            return false
        }

        private fun hostOf(url: String?): String {
            if (url == null) return ""
            return try {
                val host = Uri.parse(url).host
                host?.lowercase() ?: ""
            } catch (t: Throwable) {
                ""
            }
        }

        private fun isMiniProgramContainer(context: Context?): Boolean =
            context != null && isMiniProgramContainer(context.javaClass)

        private fun isMiniProgramContainer(cls: Class<*>): Boolean {
            var c: Class<*>? = cls
            while (c != null) {
                val name = c.name
                if (name.contains(".miniprogram.") || name.contains(".littleprogram.") ||
                    name.contains("MiniProgram") || name.contains("LittleProgram")
                ) {
                    return true
                }
                c = c.superclass
            }
            return false
        }

        private val SENSITIVE_KEYWORDS = arrayOf(
            "login", "logon", "signin", "signup", "register", "oauth", "passport", "auth",
            "account", "realname", "real_name", "bind", "verify",
            "wallet", "pay", "cashier", "checkout", "recharge", "trade", "order"
        )

        private fun isFrameworkClass(name: String): Boolean =
            name.startsWith("android.") || name.startsWith("com.android.") || name.startsWith("java.")

        private fun parseDomains(raw: String?): MutableList<String> {
            val out = ArrayList<String>()
            if (raw.isNullOrEmpty()) {
                return out
            }
            for (line in raw.split("\n")) {
                var d = line.trim().lowercase()
                if (d.isEmpty()) {
                    continue
                }
                if (d.startsWith("http://")) {
                    d = d.substring(7)
                } else if (d.startsWith("https://")) {
                    d = d.substring(8)
                }
                val slash = d.indexOf('/')
                if (slash >= 0) {
                    d = d.substring(0, slash)
                }
                if (d.isNotEmpty()) {
                    out.add(d)
                }
            }
            return out
        }

        private fun matchesDomain(host: String, domains: List<String>): Boolean {
            for (d in domains) {
                if (host == d || host.endsWith(".$d")) {
                    return true
                }
            }
            return false
        }

        private const val LOG_MAX_ENTRIES = 80

        private val logLock = Any()
        private val logEntries = ArrayDeque<LogEntry>()

        @JvmStatic
        fun clearLog() {
            synchronized(logLock) {
                logEntries.clear()
            }
            HeyboxPrefs.setString(App.KEY_WEB_LOG_DATA, "")
        }
    }
}
