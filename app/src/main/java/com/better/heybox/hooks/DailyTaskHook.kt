package com.better.heybox.hooks

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import com.better.heybox.ModuleStats
import com.better.heybox.ViewUtils
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyTaskHook(private val module: MainModule) {

    @Volatile
    private var targetCl: ClassLoader? = null

    @Volatile
    private var autoActive = false

    @Volatile
    private var currentStep = -1

    @Volatile
    private var stepTriggered = false

    @Volatile
    private var completedStep = -1

    @Volatile
    private var stepToken = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var autoContext: Context? = null

    @Volatile
    private var lastNoLinkLogAt = 0L

    private val actionIcons: MutableMap<Any, String> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())

    private val sharePanelClassCache =
        java.util.concurrent.atomic.AtomicReference<Class<*>?>()

    private val sharedShapeWarned = java.util.concurrent.atomic.AtomicBoolean()

    private val hookedWebClients: MutableSet<Class<*>> =
        java.util.Collections.synchronizedSet(java.util.HashSet<Class<*>>())

    fun install(cl: ClassLoader) {
        this.targetCl = cl
        hookShareUtils(cl)
        hookSharePanel(cl)
        hookTencentShareToQQ(cl)
        hookTencentShareToQzone(cl)
        hookShareActionEntry(cl)
        hookUmengShareHandlers(cl)
        hookExternalJumps()
        hookWebViewNavigations()
        hookMainResume(cl)
        hookSharePages(cl)
        module.logd(Log.INFO, MainModule.TAG, "✔ 每日任务 Hook 安装完成")
    }

    private fun hookShareUtils(cl: ClassLoader) {
        try {
            val shareUtils = Class.forName("com.max.hbshare.ShareUtils", false, cl)
            val hbShareData = Class.forName("com.max.hbshare.bean.HBShareData", false, cl)
            var hooked = false
            for (m in shareUtils.declaredMethods) {
                if ("P" != m.name && "y" != m.name) {
                    continue
                }
                val pts = m.parameterTypes
                if (pts.size != 2 || pts[0] != Context::class.java || pts[1] != hbShareData) {
                    continue
                }
                module.hook(m).intercept { chain ->
                    if (!autoActive) {
                        return@intercept chain.proceed()
                    }
                    try {
                        val data = chain.arg(1)
                        if (data != null) {
                            val ctx = chain.arg(0)
                            completeShare(data, ctx, cl)
                        }
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "每日任务完成回调异常: " + t)
                    }
                    null
                }
                hooked = true
                module.logd(Log.INFO, MainModule.TAG, "✔ 分享完成 Hook 已安装: ShareUtils." + m.name)
            }
            if (!hooked) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 未找到 ShareUtils.P/y(Context,HBShareData)")
            }
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 分享完成 Hook 失败", t)
        }
    }

    @Throws(Throwable::class)
    private fun completeShare(hbShareData: Any, ctx: Any?, cl: ClassLoader) {
        val shareMedia = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl)
        val qq: Any = enumValueOf(shareMedia, "QQ")
        val listenerField = hbShareData.javaClass.getDeclaredField("shareListener")
        listenerField.isAccessible = true
        val listener = listenerField.get(hbShareData)
        if (listener == null) {
            module.logd(Log.WARN, MainModule.TAG, "HBShareData.shareListener 为 null，跳过")
            return
        }
        val onResult = listener.javaClass.getMethod("onResult", shareMedia)
        onResult.invoke(listener, qq)
        module.logd(
            Log.INFO, MainModule.TAG,
            "✔ 每日任务：分享成功回调已触发 (步骤 " + (currentStep + 1) + "/" + STEP_COUNT + ")"
        )

        val context = if (ctx is Context) ctx else null
        mainHandler.post { onStepCompleted(context) }
    }

    private fun hookSharePanel(cl: ClassLoader) {
        for (panel in collectSharePanelCandidates(cl)) {
            val show: Method? = try {
                panel.getMethod("show")
            } catch (e: NoSuchMethodException) {
                module.logd(
                    Log.WARN, MainModule.TAG, "分享面板候选 " + panel.name
                            + " 无 show() 方法：类名被占用"
                )
                null
            } catch (t: Throwable) {
                module.logd(
                    Log.WARN, MainModule.TAG, "分享面板候选 " + panel.name
                            + " 解析失败: " + t
                )
                null
            }
            if (show == null) {
                continue
            }
            try {
                module.hook(show).intercept { chain ->
                    val result = chain.proceed()
                    if (!autoActive) {
                        return@intercept result
                    }
                    try {
                        val self = chain.instanceOrNull
                        if (self is Dialog) {
                            autoClickChannel(self)
                        }
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "分享面板自动点渠道异常: " + t)
                    }
                    result
                }
                module.logd(
                    Log.INFO, MainModule.TAG, "✔ 分享面板 Hook 已安装: "
                            + panel.name + ".show()"
                )
                return
            } catch (t: Throwable) {
                module.logd(
                    Log.WARN, MainModule.TAG, "分享面板候选 " + panel.name
                            + " Hook 失败: " + t
                )
            }
        }
        module.logd(
            Log.ERROR, MainModule.TAG,
            "✘ 分享面板 Hook 失败：候选名与结构定位均未命中，每日任务自动分享将不可用" +
                    "（请反馈日志，含 swipebacklayout.a 的子类清单）"
        )
    }

    private fun collectSharePanelCandidates(cl: ClassLoader): MutableList<Class<*>> {
        val out = java.util.LinkedHashSet<Class<*>>()

        val cached = sharePanelClassCache.get()
        if (cached != null) {
            out.add(cached)
        }

        for (name in SHARE_PANEL_CLASSES) {
            try {
                val c = Class.forName(name, false, cl)
                out.add(c)
            } catch (ignored: Throwable) {
            }
        }

        if (out.isEmpty()) {
            for (c in findSharePanelByShape(cl)) {
                out.add(c)
            }
        }

        try {
            out.remove(Class.forName(SHARE_PANEL_SUPER, false, cl))
        } catch (ignored: Throwable) {
        }
        return java.util.ArrayList(out)
    }

    private fun findSharePanelByShape(cl: ClassLoader): MutableList<Class<*>> {
        val out = java.util.ArrayList<Class<*>>()
        val superClass = try {
            Class.forName(SHARE_PANEL_SUPER, false, cl)
        } catch (t: Throwable) {
            if (sharedShapeWarned.compareAndSet(false, true)) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "分享面板结构定位跳过：找不到父类 " + SHARE_PANEL_SUPER
                            + "（已回退到候选类名）: " + t
                )
            }
            return out
        }

        for (name in dexkitSubclassesOfPanel(cl, superClass)) {
            try {
                val c = Class.forName(name, false, cl)
                if (matchesSharePanelShape(c)) {
                    out.add(c)
                    sharePanelClassCache.compareAndSet(null, c)
                    return out
                }
            } catch (ignored: Throwable) {
            }
        }

        var probed = 0
        for (name in probePanelNamesViaDexkit(cl)) {
            probed++
            if (probed > 400) {
                break
            }
            try {
                val c = Class.forName(name, false, cl)
                if (matchesSharePanelShape(c)) {
                    out.add(c)
                    sharePanelClassCache.compareAndSet(null, c)
                    return out
                }
            } catch (ignored: Throwable) {
            }
        }
        if (out.isEmpty() && sharedShapeWarned.compareAndSet(false, true)) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "分享面板结构定位未命中（已回退到候选类名）"
            )
        }
        return out
    }

    private fun apkPathForDexKit(cl: ClassLoader): String {
        for (probe in arrayOf(
            "com.max.xiaoheihe.app.HeyBoxApplication",
            "com.max.hbcommon.base.BaseActivity",
            "com.max.xiaoheihe.MainActivity",
        )) {
            try {
                val c = Class.forName(probe, false, cl)
                val cs = c.protectionDomain.codeSource
                if (cs != null && cs.location != null) {
                    val p = cs.location.path
                    if (p != null && p.endsWith(".apk") && java.io.File(p).exists()) {
                        return p
                    }
                    if (p != null && p.endsWith(".dex")) {
                        val i = p.lastIndexOf('/')
                        if (i > 0) {
                            val apk = p.substring(0, i) + "/base.apk"
                            if (java.io.File(apk).exists()) {
                                return apk
                            }
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        try {
            var app = App.resolveAppContext()
            if (app == null) {
                app = App.getAppContext()
            }
            if (app != null) {
                val ai = app.packageManager.getApplicationInfo(MainModule.TARGET_PKG, 0)
                if (ai != null && ai.sourceDir != null && java.io.File(ai.sourceDir).exists()) {
                    return ai.sourceDir
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val guess = "/data/app/" + MainModule.TARGET_PKG + "/base.apk"
            if (java.io.File(guess).exists()) {
                return guess
            }
        } catch (ignored: Throwable) {
        }

        module.logd(
            Log.WARN, MainModule.TAG,
            "分享面板结构定位：APK 路径推导失败（三级兜底均未命中），DexKit 不可用"
        )
        return MainModule.TARGET_PKG
    }

    private fun dexkitSubclassesOfPanel(cl: ClassLoader, superClass: Class<*>): MutableList<String> {
        val names = java.util.ArrayList<String>()
        var bridge: org.luckypray.dexkit.DexKitBridge? = null
        try {
            System.loadLibrary("dexkit")
            val created = org.luckypray.dexkit.DexKitBridge.create(apkPathForDexKit(cl))
            bridge = created
            val q = org.luckypray.dexkit.query.FindClass.create()
                .matcher(
                    org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                        .superClass(superClass.name)
                )
            for (cd in created.findClass(q)) {
                if (cd.name != null) {
                    names.add(cd.name)
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "分享面板 DexKit 父类查询不可用: " + t)
        } finally {
            if (bridge != null) {
                try {
                    bridge.close()
                } catch (ignored: Throwable) {
                }
            }
        }
        return names
    }

    private fun probePanelNamesViaDexkit(cl: ClassLoader): MutableList<String> {
        val names = java.util.ArrayList<String>()
        var bridge: org.luckypray.dexkit.DexKitBridge? = null
        try {
            System.loadLibrary("dexkit")
            val created = org.luckypray.dexkit.DexKitBridge.create(apkPathForDexKit(cl))
            bridge = created
            val q = org.luckypray.dexkit.query.FindClass.create()
                .searchPackages("com.max.hbcommon.component")
            for (cd in created.findClass(q)) {
                if (cd.name != null) {
                    names.add(cd.name)
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "分享面板 DexKit 包枚举不可用: " + t)
        } finally {
            if (bridge != null) {
                try {
                    bridge.close()
                } catch (ignored: Throwable) {
                }
            }
        }
        return names
    }

    private fun autoClickChannel(dialog: Dialog?) {
        val channel = currentChannel()
        val scheduledStep = currentStep
        val retry = object : Runnable {
            private var attempt = 0

            override fun run() {
                try {
                    if (!autoActive || dialog == null || !dialog.isShowing ||
                        currentStep != scheduledStep
                    ) {
                        return
                    }
                    val win = dialog.window
                    val decor = win?.decorView
                    val root: ViewGroup? = if (win != null && decor is ViewGroup) decor else null
                    val container = findShareContainer(root, dialog.context)
                    if (container == null) {
                        val idResolved = shareContainerId(dialog.context) != 0
                        module.logd(
                            if (idResolved) Log.INFO else Log.WARN, MainModule.TAG,
                            if (idResolved)
                                "每日任务：该弹窗不是分享面板（无 " + SHARE_CONTAINER_ID + "），不点击"
                            else
                                "每日任务：解析不到渠道容器 " + SHARE_CONTAINER_ID
                                        + "，为安全起见不点击，请反馈日志"
                        )
                        return
                    }
                    val target = findChannelTarget(container, channel)
                    val targetView = target.view
                    if (targetView == null) {
                        if (++attempt < CHANNEL_CLICK_ATTEMPTS) {
                            mainHandler.postDelayed(this, CHANNEL_CLICK_RETRY_MS)
                        } else {
                            module.logd(
                                Log.WARN, MainModule.TAG,
                                "分享面板未找到渠道按钮(" + channel + ")，跳过该步"
                            )
                        }
                        return
                    }
                    if (target.text != firstCandidate(channel)) {
                        module.logd(
                            Log.WARN, MainModule.TAG, "每日任务：配置渠道 "
                                    + channel + " 不在面板中，改用「" + target.text + "」完成本步"
                        )
                    }
                    module.logd(
                        Log.INFO, MainModule.TAG, "每日任务：自动点击分享面板「" + target.text
                                + "」渠道 (步骤 " + (currentStep + 1) + "/" + STEP_COUNT + ")"
                    )
                    targetView.performClick()
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "自动点渠道异常: " + t)
                }
            }
        }
        mainHandler.postDelayed(retry, 800L)
    }

    private class ClickTarget(val view: View?, val text: String?)

    private fun findChannelTarget(container: ViewGroup, channel: String): ClickTarget {
        for (text in channelCandidates(channel)) {
            val view = findChannelView(container, text)
            if (view != null) {
                return ClickTarget(view, text)
            }
        }
        return ClickTarget(null, null)
    }

    private fun currentChannel(): String {
        val v = module.getString(App.KEY_SHARE_CHANNEL, "")
        return if (v == null || v.isEmpty()) "QQ" else v
    }

    private fun hookTencentShareToQQ(cl: ClassLoader) {
        try {
            val tencent = Class.forName("com.tencent.tauth.Tencent", false, cl)
            val iUiListener = Class.forName("com.tencent.tauth.IUiListener", false, cl)
            val shareToQQ = ViewUtils.findMethod(
                tencent, "shareToQQ",
                Activity::class.java, android.os.Bundle::class.java, iUiListener
            )
            if (shareToQQ == null) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "✘ 未找到 Tencent.shareToQQ(Activity,Bundle,IUiListener)"
                )
                return
            }
            val onComplete = iUiListener.getMethod("onComplete", Any::class.java)
            hookFakeShareSuccess(shareToQQ, "QQ", "Tencent.shareToQQ", 2) { listener ->
                val ret = org.json.JSONObject()
                ret.put("ret", 0)
                onComplete.invoke(listener, ret)
            }
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ Tencent.shareToQQ Hook 失败", t)
        }
    }

    private fun hookTencentShareToQzone(cl: ClassLoader) {
        try {
            val tencent = Class.forName("com.tencent.tauth.Tencent", false, cl)
            val iUiListener = Class.forName("com.tencent.tauth.IUiListener", false, cl)
            val shareToQzone = ViewUtils.findMethod(
                tencent, "shareToQzone",
                Activity::class.java, android.os.Bundle::class.java, iUiListener
            )
            if (shareToQzone == null) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "✘ 未找到 Tencent.shareToQzone(Activity,Bundle,IUiListener)"
                )
                return
            }
            val onComplete = iUiListener.getMethod("onComplete", Any::class.java)
            hookFakeShareSuccess(shareToQzone, "QQ", "Tencent.shareToQzone", 2) { listener ->
                val ret = org.json.JSONObject()
                ret.put("ret", 0)
                onComplete.invoke(listener, ret)
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ Tencent.shareToQzone Hook 失败", t)
        }
    }

    private fun hookUmengShareHandlers(cl: ClassLoader) {
        val shareContent: Class<*>
        val listener: Class<*>
        val shareMedia: Class<*>
        try {
            shareContent = Class.forName("com.umeng.socialize.ShareContent", false, cl)
            listener = Class.forName("com.umeng.socialize.UMShareListener", false, cl)
            shareMedia = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 未找到 UMeng 分享类，跳过全渠道拦截: " + t)
            return
        }
        var installed = 0
        for (entry in UMENG_SHARE_HANDLERS) {
            val className = entry[0]
            val defaultMedia = entry[1]
            val label = entry[2]
            try {
                val handler = Class.forName(className, false, cl)
                val share = ViewUtils.findMethod(handler, "share", shareContent, listener)
                if (share == null) {
                    module.logd(
                        Log.WARN, MainModule.TAG, "✘ 未找到 " + className
                                + ".share(ShareContent,UMShareListener)"
                    )
                    continue
                }
                module.hook(share).intercept { chain ->
                    if (!autoActive) {
                        return@intercept chain.proceed()
                    }
                    try {
                        fakeUmengShareSuccess(
                            chain.instanceOrNull, chain.arg(1),
                            shareMedia, defaultMedia, label
                        )
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, label + " 伪造回调异常: " + t)
                    }
                    mainHandler.post { onStepCompleted(null) }
                    if (share.returnType == java.lang.Boolean.TYPE) java.lang.Boolean.TRUE else null
                }
                installed++
                module.logd(
                    Log.INFO, MainModule.TAG, "✔ 分享拦截已安装: " + className
                            + ".share (" + label + ")"
                )
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "✘ " + className + " 分享拦截失败: " + t)
            }
        }
        if (installed == 0) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "✘ 未安装任何 UMeng 分享拦截，自动化可能触发真实分享"
            )
        }
    }

    private fun hookShareActionEntry(cl: ClassLoader) {
        try {
            val shareAction = Class.forName("com.umeng.socialize.ShareAction", false, cl)
            val listenerType = Class.forName("com.umeng.socialize.UMShareListener", false, cl)
            val mediaType = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl)
            val share = ViewUtils.findMethod(shareAction, "share")
            if (share == null) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 未找到 ShareAction.share()")
                return
            }
            module.hook(share).intercept { chain ->
                if (!autoActive) {
                    return@intercept chain.proceed()
                }
                val self = chain.instanceOrNull
                val listener = readFieldByType(self, listenerType)
                val media = readFieldByType(self, mediaType)
                if (listener == null || media == null) {
                    return@intercept chain.proceed()
                }
                try {
                    invokeShareSuccess(listener, media, "分享")
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "分享成功回调伪造异常: " + t)
                }
                mainHandler.post { onStepCompleted(null) }
                null
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 分享出口拦截已安装: ShareAction.share()")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ ShareAction.share() 拦截失败: " + t)
        }
    }

    private fun hookExternalJumps() {
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
                    if (!autoActive) {
                        return@intercept chain.proceed()
                    }
                    try {
                        val args = chain.args
                        if (args.size > 4) {
                            val arg4 = args[4]
                            if (arg4 is Intent && isExternalWebJump(arg4)) {
                                val url = stringify(arg4.data)
                                onAuxNavigationBlocked("外部跳转(" + callerHint() + ")", url)
                                return@intercept blockedReturn(method)
                            }
                        }
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "每日任务：外部跳转拦截异常: " + t)
                    }
                    chain.proceed()
                }
                installed++
            }
            module.logd(
                Log.INFO, MainModule.TAG,
                "✔ 外部跳转拦截已安装: " + installed + " 个 execStartActivity"
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ 外部跳转拦截 Hook 失败", t)
        }
    }

    private fun onAuxNavigationBlocked(kind: String, url: String?) {
        val dead = isAppStoreJump(url)
        module.logd(
            Log.WARN, MainModule.TAG, "每日任务：拦下" + kind + " " + url
                    + (if (dead) "（跳下载页，放弃本步）" else "")
        )
        if (dead) {
            abandonStepAsync("的链接跳到了应用商店/下载页")
        }
    }

    private fun abandonStepAsync(reason: String) {
        mainHandler.post {
            if (!autoActive) {
                return@post
            }
            module.logd(
                Log.WARN, MainModule.TAG, "每日任务：步骤 " + (currentStep + 1) + " " + reason
                        + "，跳过该步"
            )
            advance(autoContext)
        }
    }

    private fun hookWebViewNavigations() {
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
                    val arg = chain.arg(0)
                    if (autoActive && arg is String && isBlockedAuxNavigation(arg)) {
                        onAuxNavigationBlocked("网页容器加载", arg)
                        return@intercept null
                    }
                    chain.proceed()
                }
                loads++
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ loadUrl 看门狗 Hook 失败", t)
        }

        var clients = 0
        try {
            val setter = WebView::class.java.getDeclaredMethod(
                "setWebViewClient", WebViewClient::class.java
            )
            module.hook(setter).intercept { chain ->
                val client = chain.arg(0)
                if (client != null) {
                    hookWebClientClass(client.javaClass)
                }
                chain.proceed()
            }
            clients = 1
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ WebViewClient 看门狗 Hook 失败", t)
        }
        module.logd(
            Log.INFO, MainModule.TAG, "✔ 网页跳转看门狗已安装（loadUrl " + loads
                    + "，client " + clients + "）"
        )
    }

    private fun hookWebClientClass(clientClass: Class<*>?) {
        if (clientClass == null || clientClass == WebViewClient::class.java) {
            return
        }
        val name = clientClass.name
        if (name.startsWith("android.") || name.startsWith("androidx.") ||
            name.startsWith("com.android.") || name.startsWith("org.chromium.")
        ) {
            return
        }
        synchronized(hookedWebClients) {
            if (!hookedWebClients.add(clientClass)) {
                return
            }
        }
        var type: Class<*>? = clientClass
        while (type != null && type != Any::class.java) {
            if (type.name.startsWith("android.")) {
                break
            }
            for (method in type.declaredMethods) {
                if ("shouldOverrideUrlLoading" != method.name) {
                    continue
                }
                val params = method.parameterTypes
                if (params.size != 2 || params[0] != WebView::class.java ||
                    method.returnType != java.lang.Boolean.TYPE
                ) {
                    continue
                }
                if (params[1] != String::class.java && params[1] != WebResourceRequest::class.java) {
                    continue
                }
                try {
                    module.hook(method).intercept { chain ->
                        if (!autoActive) {
                            return@intercept chain.proceed()
                        }
                        val url = extractNavigateUrl(chain.args)
                        if (isBlockedAuxNavigation(url)) {
                            onAuxNavigationBlocked("网页跳转", url)
                            return@intercept java.lang.Boolean.TRUE
                        }
                        chain.proceed()
                    }
                } catch (t: Throwable) {
                    module.logd(
                        Log.WARN, MainModule.TAG,
                        "shouldOverrideUrlLoading 看门狗 Hook 失败: " + name, t
                    )
                }
            }
            type = type.superclass
        }
    }

    @Throws(Throwable::class)
    private fun fakeUmengShareSuccess(
        handler: Any?,
        listener: Any?,
        shareMedia: Class<*>?,
        defaultMedia: String?,
        label: String
    ) {
        if (listener == null) {
            module.logd(Log.WARN, MainModule.TAG, label + " 无分享监听，仅跳过真实分享")
            return
        }
        val media = resolveShareMedia(handler, shareMedia, defaultMedia)
        if (media == null) {
            module.logd(Log.WARN, MainModule.TAG, label + " 无法解析 SHARE_MEDIA，仅跳过真实分享")
            return
        }
        invokeShareSuccess(listener, media, label)
    }

    @Throws(Throwable::class)
    private fun invokeShareSuccess(listener: Any, media: Any, label: String) {
        val onResult = findOnResult(listener.javaClass, media.javaClass)
            ?: Class.forName(
                "com.umeng.socialize.UMShareListener", false,
                listener.javaClass.classLoader
            ).getMethod("onResult", media.javaClass)
        onResult.isAccessible = true
        onResult.invoke(listener, media)
        module.logd(
            Log.INFO, MainModule.TAG, "✔ 每日任务：" + label + " 成功回调已触发 (步骤 "
                    + (currentStep + 1) + "/" + STEP_COUNT + ")"
        )
        warnChannelMismatch(enumName(media), label)
    }

    private fun warnChannelMismatch(mediaName: String?, label: String) {
        val configured = currentChannel()
        val actual = channelKeyOf(mediaName)
        if (actual != null && actual != configured) {
            module.logd(
                Log.WARN, MainModule.TAG, "每日任务：实际走的是 " + label + "（设置渠道 "
                        + configured + "），已按成功处理"
            )
        }
    }

    private fun hookFakeShareSuccess(
        share: Method,
        channel: String,
        logLabel: String,
        listenerArg: Int,
        fake: FakeShareInvoker
    ) {
        module.hook(share).intercept { chain ->
            if (!autoActive) {
                return@intercept chain.proceed()
            }
            try {
                val listener = chain.arg(listenerArg)
                if (listener != null) {
                    fake.invoke(listener)
                    module.logd(
                        Log.INFO, MainModule.TAG, "✔ 每日任务：" + logLabel
                                + " 成功回调已触发 (步骤 " + (currentStep + 1) + "/" + STEP_COUNT + ")"
                    )
                    warnChannelMismatch(channel, logLabel)
                }
                val ctx = chain.arg(0)
                val context = if (ctx is Context) ctx else null
                mainHandler.post { onStepCompleted(context) }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, logLabel + " 伪造回调异常: " + t)
            }
            if (share.returnType == java.lang.Boolean.TYPE) java.lang.Boolean.TRUE else null
        }
    }

    private fun interface FakeShareInvoker {
        @Throws(Throwable::class)
        fun invoke(listener: Any?)
    }

    private fun hookMainResume(cl: ClassLoader) {
        try {
            val mainActivity = Class.forName("com.max.xiaoheihe.MainActivity", false, cl)
            val onResume = mainActivity.getMethod("onResume")
            module.hook(onResume).intercept { chain ->
                val result = chain.proceed()
                try {
                    val self = chain.instanceOrNull
                    if (self is Activity) {
                        maybeStartDailyTask(self)
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "每日任务启动检查异常: " + t)
                }
                result
            }
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 每日任务入口 Hook 失败", t)
        }
    }

    private fun maybeStartDailyTask(activity: Activity) {
        if (autoActive) {
            return
        }
        if (!module.isEnabled(App.KEY_DAILY_TASK_ENABLED, false)) {
            return
        }
        ModuleStats.dailyTaskResumeChecks.incrementAndGet()
        handleResetFlag()
        if (isTodayDone()) {
            return
        }
        if (!hasAnyLink()) {
            ModuleStats.dailyTaskNoLink.incrementAndGet()
            val now = SystemClock.uptimeMillis()
            if (now - lastNoLinkLogAt >= NO_LINK_LOG_INTERVAL_MS) {
                lastNoLinkLogAt = now
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "每日任务：未配置分享链接（帖子/游戏详情/游戏评价）"
                )
            }
            return
        }
        autoActive = true
        autoContext = activity.applicationContext
        currentStep = STEP_PICTURE
        stepTriggered = false
        module.logd(Log.INFO, MainModule.TAG, "每日任务启动（3 种分享类型：图片帖→普通帖→频道）")
        openStep(activity, STEP_PICTURE)
    }

    private fun hookSharePages(cl: ClassLoader) {
        try {
            val titleBar = Class.forName("com.max.hbcommon.component.TitleBar", false, cl)
            hookActionIconSetter(cl, titleBar)
            hookTitleBarSetter(
                cl, titleBar, "setActionIconOnClickListener",
                "iv_appbar_action_button", STEP_PICTURE, STEP_NORMAL, STEP_CHANNEL
            )
            hookTitleBarSetter(
                cl, titleBar, "setActionMoreIconOnClickListener",
                "iv_appbar_action_button_more", STEP_NORMAL, STEP_CHANNEL
            )
            module.logd(Log.INFO, MainModule.TAG, "✔ 分享按钮 Hook 已安装（TitleBar setter）")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 分享按钮 Hook 失败", t)
        }
    }

    private fun hookActionIconSetter(cl: ClassLoader, titleBar: Class<*>) {
        try {
            val intSetter = titleBar.getMethod("setActionIcon", java.lang.Integer.TYPE)
            module.hook(intSetter).intercept { chain ->
                try {
                    val self = chain.instanceOrNull
                    val resId = chain.arg(0) as Int
                    if (self is View && resId != 0) {
                        val name = self.resources
                            .getResourceEntryName(resId)
                        actionIcons.put(self, name)
                        if (autoActive) {
                            module.logd(
                                Log.INFO, MainModule.TAG,
                                "每日任务：setActionIcon 记录 " + name
                            )
                        }
                    }
                } catch (ignored: Throwable) {
                }
                chain.proceed()
            }
            for (m in titleBar.declaredMethods) {
                val pts = m.parameterTypes
                if ("setActionIcon" == m.name && pts.size == 1 &&
                    pts[0] != java.lang.Integer.TYPE
                ) {
                    module.hook(m).intercept { chain ->
                        try {
                            val self = chain.instanceOrNull
                            if (self is View) {
                                actionIcons.put(self, "")
                            }
                        } catch (ignored: Throwable) {
                        }
                        chain.proceed()
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ setActionIcon 记录 Hook 失败: " + t)
        }
    }

    private fun hookTitleBarSetter(
        cl: ClassLoader,
        titleBar: Class<*>,
        setterName: String,
        viewName: String,
        vararg allowedSteps: Int
    ) {
        try {
            val setter = titleBar.getMethod(setterName, View.OnClickListener::class.java)
            module.hook(setter).intercept { chain ->
                val result = chain.proceed()
                if (!autoActive) {
                    return@intercept result
                }
                var allowed = false
                for (s in allowedSteps) {
                    if (currentStep == s) {
                        allowed = true
                        break
                    }
                }
                if (!allowed) {
                    return@intercept result
                }
                val selfObj = chain.instanceOrNull
                if ("setActionIconOnClickListener" == setterName &&
                    isMessageIconPage(selfObj)
                ) {
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "每日任务：该页 action 图标是消息入口，跳过不点"
                    )
                    return@intercept result
                }
                try {
                    val self = chain.instanceOrNull
                    if (self == null) {
                        return@intercept result
                    }
                    var ctx: Context? = null
                    if (self is View) {
                        ctx = self.context
                    }
                    if (ctx == null && self is Context) {
                        ctx = self
                    }
                    val act = ctx as? Activity
                    if (act != null) {
                        val titleBarObj: Any = self
                        val listener = chain.arg(0)
                        val scheduledStep = currentStep
                        mainHandler.postDelayed({
                            try {
                                if (!autoActive || listener == null) {
                                    return@postDelayed
                                }

                                if (currentStep != scheduledStep) {
                                    return@postDelayed
                                }

                                if (stepTriggered) {
                                    return@postDelayed
                                }
                                stepTriggered = true

                                val l = listener as View.OnClickListener
                                module.logd(
                                    Log.INFO, MainModule.TAG, "每日任务：自动触发 "
                                            + viewName + " 分享 (步骤 " + (currentStep + 1)
                                            + "/" + STEP_COUNT + ") 页面="
                                            + act.javaClass.simpleName
                                )
                                clickShareButton(act, titleBar, titleBarObj, viewName, l)
                            } catch (t2: Throwable) {
                                module.logd(Log.WARN, MainModule.TAG, "自动触发分享异常: " + t2)
                            }
                        }, 1200L)
                    } else {
                        module.logd(
                            Log.WARN, MainModule.TAG,
                            "TitleBar context 不是 Activity: " +
                                    (if (ctx == null) "null" else ctx.javaClass.name)
                        )
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "分享按钮调度异常: " + t)
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ TitleBar " + setterName + " Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "✘ TitleBar " + setterName + " Hook 失败", t)
        }
    }

    private fun isMessageIconPage(titleBar: Any?): Boolean {
        val act = ViewUtils.findActivity(if (titleBar is View) titleBar else null)
        if (act != null && "com.max.xiaoheihe.module.bbs.ChannelsDetailActivity"
                .equals(act.javaClass.name)
        ) {
            return true
        }
        return titleBar != null && "common_notice" == actionIcons.get(titleBar)
    }

    private fun onStepCompleted(context: Context?) {
        if (!autoActive) {
            return
        }
        val done = currentStep
        if (done == completedStep) {
            module.logd(
                Log.INFO, MainModule.TAG, "每日任务：步骤 " + (done + 1)
                        + " 重复回调，忽略"
            )
            return
        }
        completedStep = done
        module.logd(
            Log.INFO, MainModule.TAG, "每日任务：步骤 " + (done + 1) + "/" + STEP_COUNT + " 完成 ("
                    + stepName(done) + ")"
        )
        advance(context)
    }

    private fun advance(context: Context?) {
        if (!autoActive) {
            return
        }
        val next = currentStep + 1
        if (next < STEP_COUNT) {
            currentStep = next
            stepTriggered = false
            val ctx = context ?: autoContext
            if (ctx != null) {
                openStep(ctx, next)
            }
        } else {
            finishDailyTask(context)
        }
    }

    private fun openStep(context: Context, step: Int) {
        val link = getLinkForStep(step)
        if (link == null || link.isEmpty()) {
            module.logd(
                Log.INFO, MainModule.TAG, "每日任务：步骤 " + (step + 1) + "（"
                        + stepName(step) + "）未配置，跳过"
            )
            advance(context)
            return
        }
        scheduleStepTimeout(step)
        val cl = targetCl ?: context.classLoader
        try {
            val router = Class.forName("com.max.xiaoheihe.RouterActivity", false, cl)
            val intent = Intent(context, router)
                .setData(Uri.parse(link.trim()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            module.logd(
                Log.INFO, MainModule.TAG,
                "每日任务：打开 " + stepName(step) + ": " + link.trim()
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "RouterActivity 打开失败，尝试 link_id 直开: " + t)
            val linkId = extractLinkId(link)
            if (linkId == null) {
                module.logd(Log.WARN, MainModule.TAG, "无法解析 link_id，跳过该步")
                advance(context)
                return
            }
            try {
                val normalPage = Class.forName(
                    "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
                    false, cl
                )
                val intent = Intent(context, normalPage)
                    .putExtra("link_id", linkId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (t2: Throwable) {
                module.logd(Log.ERROR, MainModule.TAG, "帖子打开失败", t2)
                abortDailyTask()
            }
        }
    }

    private fun scheduleStepTimeout(step: Int) {
        val token = ++stepToken
        mainHandler.postDelayed({
            if (!autoActive || stepToken != token || currentStep != step) {
                return@postDelayed
            }
            module.logd(
                Log.WARN, MainModule.TAG, "每日任务：步骤 " + (step + 1) + "（"
                        + stepName(step) + "）" + (STEP_TIMEOUT_MS / 1000) + "s 内没走完，跳过该步"
            )
            advance(autoContext)
        }, STEP_TIMEOUT_MS)
    }

    private fun abortDailyTask() {
        reset()
        module.logd(
            Log.WARN, MainModule.TAG,
            "每日任务：打开帖子失败，已复位（今日未标记完成，下次进入主页将重试）"
        )
    }

    fun clearTodayAndRetry(activity: Activity?) {
        reset()
        clearDoneDate()
        module.logd(Log.INFO, MainModule.TAG, "已清除今日打卡状态，重新尝试每日任务")
        if (activity != null) {
            maybeStartDailyTask(activity)
        }
    }

    private fun handleResetFlag() {
        if (!module.isEnabled(App.KEY_DAILY_TASK_RESET, false)) {
            return
        }
        clearDoneDate()
        HeyboxPrefs.setBoolean(App.KEY_DAILY_TASK_RESET, false)
        try {
            val remote = module.getRemotePreferences(App.PREFS_GROUP)
            if (remote != null) {
                remote.edit().remove(App.KEY_DAILY_TASK_RESET).apply()
            }
        } catch (ignored: Throwable) {
        }
        module.logd(Log.INFO, MainModule.TAG, "检测到清除今日打卡标志，已重置完成状态")
    }

    private fun clearDoneDate() {
        writeDoneDate("")
    }

    private fun reset() {
        autoActive = false
        currentStep = -1
        stepTriggered = false
        completedStep = -1
        stepToken++
    }

    private fun writeDoneDate(value: String?) {
        HeyboxPrefs.setString(App.KEY_DAILY_TASK_DONE_DATE, value)
        try {
            val remote = module.getRemotePreferences(App.PREFS_GROUP)
            if (remote != null) {
                if (value == null || value.isEmpty()) {
                    remote.edit().remove(App.KEY_DAILY_TASK_DONE_DATE).apply()
                } else {
                    remote.edit().putString(App.KEY_DAILY_TASK_DONE_DATE, value).apply()
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun finishDailyTask(context: Context?) {
        reset()
        writeDoneDate(today())
        module.logd(Log.INFO, MainModule.TAG, "每日任务：3 种分享类型全部完成，已记录今日状态")
        val ctx = context ?: autoContext
        if (ctx == null) {
            return
        }
        try {
            Toast.makeText(
                ctx.applicationContext,
                "每日分享任务已完成", Toast.LENGTH_SHORT
            ).show()
        } catch (ignored: Throwable) {
        }
        if (module.isEnabled(App.KEY_DAILY_TASK_BACK_HOME, true)) {
            mainHandler.postDelayed({ backToHome(ctx) }, 800L)
        }
    }

    private fun backToHome(context: Context) {
        try {
            val cl = targetCl ?: context.classLoader
            val main = Class.forName("com.max.xiaoheihe.MainActivity", false, cl)
            val intent = Intent(context, main)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            context.startActivity(intent)
            module.logd(Log.INFO, MainModule.TAG, "每日任务：已自动退回首页")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "每日任务：退回首页失败: " + t)
        }
    }

    private fun getLinkForStep(step: Int): String? {
        if (step < 0 || step >= STEP_KEYS.size) {
            return null
        }
        val value = module.getString(STEP_KEYS[step], "")
        return value?.trim()
    }

    private fun hasAnyLink(): Boolean {
        return !getLinkForStep(STEP_PICTURE)!!.isEmpty() ||
                !getLinkForStep(STEP_NORMAL)!!.isEmpty() ||
                !getLinkForStep(STEP_CHANNEL)!!.isEmpty()
    }

    private fun isTodayDone(): Boolean {
        if (today() == HeyboxPrefs.getString(App.KEY_DAILY_TASK_DONE_DATE, "")) {
            return true
        }
        try {
            val remote = module.getRemotePreferences(App.PREFS_GROUP)
            if (remote != null && today() ==
                remote.getString(App.KEY_DAILY_TASK_DONE_DATE, "")
            ) {
                return true
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    companion object {

        private const val STEP_PICTURE = 0
        private const val STEP_NORMAL = 1
        private const val STEP_CHANNEL = 2
        private val STEP_KEYS = arrayOf(
            App.KEY_DAILY_TASK_PICTURE,
            App.KEY_DAILY_TASK_NORMAL,
            App.KEY_DAILY_TASK_CHANNEL,
        )
        private val STEP_NAMES = arrayOf("分享任意帖子", "分享游戏详情", "分享游戏评价")
        private val STEP_COUNT = STEP_KEYS.size

        private val CHANNEL_VIEW_TEXTS: MutableMap<String, Array<String>> =
            java.util.HashMap<String, Array<String>>().apply {
                put("WECHAT", arrayOf("微信", "朋友圈"))
                put("WEIBO", arrayOf("微博"))
                put("QQ", arrayOf("QQ", "QQ空间"))
            }

        private val CHANNEL_FALLBACK_ORDER = arrayOf("QQ", "WECHAT", "WEIBO")

        private const val SHARE_CONTAINER_ID = "rv_share_container"

        private const val CHANNEL_CLICK_ATTEMPTS = 3
        private const val CHANNEL_CLICK_RETRY_MS = 400L

        private const val STEP_TIMEOUT_MS = 15000L

        private const val NO_LINK_LOG_INTERVAL_MS = 10 * 60_000L

        private val UMENG_SHARE_HANDLERS = arrayOf(
            arrayOf("com.umeng.socialize.handler.UMQQSsoHandler", "QQ", "QQ好友"),
            arrayOf("com.umeng.socialize.handler.QZoneSsoHandler", "QZONE", "QQ空间"),
            arrayOf("com.umeng.socialize.handler.UMWXHandler", "WEIXIN", "微信"),
            arrayOf("com.umeng.socialize.handler.SinaSsoHandler", "SINA", "微博"),
        )

        private val SHARE_PANEL_CLASSES = arrayOf(
            "com.max.hbcommon.component.m",
            "com.max.hbcommon.component.i",
        )

        private const val SHARE_PANEL_SUPER = "com.max.hbcustomview.swipebacklayout.a"

        private val APP_STORE_HOST_MARKERS = arrayOf(
            "a.app.qq.com", "app.qq.com", "sj.qq.com", "android.myapp.com",
            "myapp.com", "log.umsns.com",
        )

        private val BLOCKED_SCHEMES = arrayOf(
            "weixin:", "mqqapi:", "mqqwpa:", "mqq:", "qqmusic:", "qzone:",
            "alipays:", "alipay:", "market:", "tmast:", "snssdk1128:", "snssdk2329:",
            "sinaweibo:", "sinawb:", "bilibili:", "taobao:", "pinduoduo:",
        )

        private val HEYBOX_SCHEMES = arrayOf(
            "heybox:", "xiaoheihe:", "hbox:", "maxjia:",
        )

        private fun stringify(v: Any?): String = if (v == null) "null" else v.toString()

        private enum class EnumTypeToken

        @Suppress("UNCHECKED_CAST")
        private fun enumValueOf(enumType: Class<*>, name: String): Any =
            java.lang.Enum.valueOf(enumType as Class<EnumTypeToken>, name)

        private fun matchesSharePanelShape(c: Class<*>?): Boolean {
            if (c == null || c.isInterface ||
                java.lang.reflect.Modifier.isAbstract(c.modifiers)
            ) {
                return false
            }
            try {
                val sup = c.superclass
                if (sup == null || SHARE_PANEL_SUPER != sup.name) {
                    return false
                }
                var show = false
                for (m in c.declaredMethods) {
                    if ("show" == m.name &&
                        m.parameterTypes.size == 0 &&
                        m.returnType == Void.TYPE &&
                        java.lang.reflect.Modifier.isPublic(m.modifiers)
                    ) {
                        show = true
                        break
                    }
                }
                if (!show) {
                    return false
                }
                for (ctor in c.declaredConstructors) {
                    val ps = ctor.parameterTypes
                    if (ps.size == 3 && ps[0] == Context::class.java &&
                        (ps[1] == java.lang.Integer.TYPE || ps[1] == Integer::class.javaObjectType) &&
                        ps[2] == View::class.java
                    ) {
                        return true
                    }
                }
            } catch (ignored: Throwable) {
            }
            return false
        }

        private fun channelCandidates(channel: String): MutableList<String> {
            val out = java.util.ArrayList<String>()
            appendCandidates(out, channel)
            for (fallback in CHANNEL_FALLBACK_ORDER) {
                appendCandidates(out, fallback)
            }
            return out
        }

        private fun appendCandidates(out: MutableList<String>, channel: String) {
            val texts = CHANNEL_VIEW_TEXTS[channel] ?: CHANNEL_VIEW_TEXTS["QQ"]
            for (text in texts!!) {
                if (!out.contains(text)) {
                    out.add(text)
                }
            }
        }

        private fun firstCandidate(channel: String): String {
            var texts = CHANNEL_VIEW_TEXTS[channel]
            if (texts == null || texts.isEmpty()) {
                texts = CHANNEL_VIEW_TEXTS["QQ"]
            }
            return texts!![0]
        }

        private fun findShareContainer(root: View?, context: Context?): ViewGroup? {
            if (root == null) {
                return null
            }
            val id = shareContainerId(context)
            if (id == 0) {
                return null
            }
            return try {
                val container = root.findViewById<View>(id)
                if (container is ViewGroup) container else null
            } catch (t: Throwable) {
                null
            }
        }

        private fun shareContainerId(context: Context?): Int {
            if (context == null) {
                return 0
            }
            return try {
                context.resources.getIdentifier(
                    SHARE_CONTAINER_ID, "id", MainModule.TARGET_PKG
                )
            } catch (t: Throwable) {
                0
            }
        }

        private fun findChannelView(root: ViewGroup?, targetText: String?): View? {
            if (root == null || targetText == null) {
                return null
            }
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is ViewGroup) {
                    val found = findChannelView(child, targetText)
                    if (found != null) {
                        return found
                    }
                }
                if (child is android.widget.TextView) {
                    val text = child.text
                    if (text != null && targetText == text.toString().trim()) {
                        var v: View? = child
                        while (v != null) {
                            if (v.isClickable) {
                                return v
                            }
                            val parent = v.parent
                            if (parent !is View) {
                                break
                            }
                            v = parent
                        }
                        return child
                    }
                }
            }
            return null
        }

        private fun isExternalWebJump(intent: Intent?): Boolean {
            if (intent == null || intent.component != null) {
                return false
            }
            if (Intent.ACTION_VIEW != intent.action) {
                return false
            }
            val data = intent.data ?: return false
            val scheme = data.scheme
            return "http".equals(scheme, ignoreCase = true) ||
                    "https".equals(scheme, ignoreCase = true)
        }

        private fun blockedReturn(method: Method): Any? {
            val type = method.returnType
            if (type == java.lang.Integer.TYPE) {
                return 0
            }
            if (type == java.lang.Boolean.TYPE) {
                return java.lang.Boolean.FALSE
            }
            return null
        }

        private fun callerHint(): String {
            try {
                for (frame in Thread.currentThread().stackTrace) {
                    val name = frame.className
                    if (name.startsWith("android.") || name.startsWith("java.") ||
                        name.startsWith("com.better.heybox") || name.startsWith("de.robv")
                    ) {
                        continue
                    }
                    return name + "." + frame.methodName + ":" + frame.lineNumber
                }
            } catch (ignored: Throwable) {
            }
            return "unknown"
        }

        private fun extractNavigateUrl(args: List<Any?>): String? {
            if (args.size < 2) {
                return null
            }
            val arg = args[1]
            if (arg is String) {
                return arg
            }
            if (arg is WebResourceRequest) {
                try {
                    val uri = arg.url
                    return uri?.toString()
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        private fun isAppStoreJump(url: String?): Boolean {
            if (url == null || url.isEmpty()) {
                return false
            }
            val lower = url.lowercase(java.util.Locale.US)
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return false
            }
            for (marker in APP_STORE_HOST_MARKERS) {
                if (lower.contains(marker)) {
                    return true
                }
            }
            return lower.contains("xiaoheihe") && lower.contains("download")
        }

        private fun isBlockedAuxNavigation(url: String?): Boolean {
            if (url == null || url.isEmpty()) {
                return false
            }
            val lower = url.lowercase(java.util.Locale.US)
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return isAppStoreJump(lower)
            }
            if (lower.startsWith("intent:")) {
                return !(lower.contains("com.max.xiaoheihe") ||
                        lower.contains("scheme=heybox") || lower.contains("scheme=xiaoheihe"))
            }
            for (scheme in HEYBOX_SCHEMES) {
                if (lower.startsWith(scheme)) {
                    return false
                }
            }
            if (lower.startsWith("about:") || lower.startsWith("data:") ||
                lower.startsWith("javascript:") ||
                lower.startsWith("file:") || lower.startsWith("blob:") ||
                lower.startsWith("content:")
            ) {
                return false
            }
            for (scheme in BLOCKED_SCHEMES) {
                if (lower.startsWith(scheme)) {
                    return true
                }
            }
            return false
        }

        private fun enumName(value: Any?): String {
            return if (value is Enum<*>) value.name else stringify(value)
        }

        private fun readFieldByType(target: Any?, type: Class<*>?): Any? {
            if (target == null || type == null) {
                return null
            }
            var current: Class<*>? = target.javaClass
            while (current != null && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (!type.isAssignableFrom(field.type)) {
                        continue
                    }
                    try {
                        field.isAccessible = true
                        val value = field.get(target)
                        if (value != null) {
                            return value
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                current = current.superclass
            }
            return null
        }

        private fun resolveShareMedia(
            handler: Any?,
            shareMedia: Class<*>?,
            defaultMedia: String?
        ): Any? {
            if (handler != null && shareMedia != null) {
                var type: Class<*>? = handler.javaClass
                while (type != null && type != Any::class.java) {
                    try {
                        val field = type.getDeclaredField("mTarget")
                        field.isAccessible = true
                        val value = field.get(handler)
                        if (shareMedia.isInstance(value)) {
                            return value
                        }
                    } catch (ignored: Throwable) {
                    }
                    type = type.superclass
                }
            }
            if (shareMedia == null || defaultMedia == null) {
                return null
            }
            return try {
                enumValueOf(shareMedia, defaultMedia)
            } catch (t: Throwable) {
                null
            }
        }

        private fun findOnResult(type: Class<*>, mediaType: Class<*>): Method? {
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                for (method in current.declaredMethods) {
                    if ("onResult" == method.name && method.parameterCount == 1 &&
                        method.parameterTypes[0].isAssignableFrom(mediaType)
                    ) {
                        return method
                    }
                }
                for (iface in current.interfaces) {
                    try {
                        return iface.getMethod("onResult", mediaType)
                    } catch (ignored: NoSuchMethodException) {
                    }
                }
                current = current.superclass
            }
            return null
        }

        private fun channelKeyOf(mediaName: String?): String? {
            if (mediaName == null) {
                return null
            }
            if ("QQ" == mediaName || "QZONE" == mediaName) {
                return "QQ"
            }
            if ("WEIXIN" == mediaName || "WEIXIN_CIRCLE" == mediaName) {
                return "WECHAT"
            }
            if ("SINA" == mediaName) {
                return "WEIBO"
            }
            return null
        }

        private fun clickShareButton(
            act: Activity,
            titleBar: Class<*>,
            titleBarObj: Any,
            viewName: String,
            l: View.OnClickListener
        ) {
            val btnId = act.resources.getIdentifier(viewName, "id", MainModule.TARGET_PKG)
            val btn = if (btnId == 0) null else act.findViewById<View>(btnId)
            if (btn != null) {
                l.onClick(btn)
                return
            }
            try {
                val getView = titleBar.getMethod("getAppbarActionButtonView")
                val v = getView.invoke(titleBarObj)
                if (v is View) {
                    l.onClick(v)
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun stepName(step: Int): String {
            return if (step >= 0 && step < STEP_NAMES.size) STEP_NAMES[step] else "未知"
        }

        private fun today(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }

        private fun extractLinkId(link: String): String? {
            try {
                val uri = Uri.parse(link)
                val id = uri.getQueryParameter("link_id")
                if (id != null && !id.isEmpty()) {
                    return id
                }
            } catch (ignored: Throwable) {
            }
            try {
                val idx = link.indexOf("link_id=")
                if (idx >= 0) {
                    var v = link.substring(idx + 8)
                    val end = v.indexOf('&')
                    if (end > 0) {
                        v = v.substring(0, end)
                    }
                    if (!v.isEmpty()) {
                        return v
                    }
                }
            } catch (ignored: Throwable) {
            }
            return null
        }
    }
}
