package com.better.heybox.hooks

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.better.heybox.App
import com.better.heybox.MainModule
import com.better.heybox.ThemeUtils
import com.better.heybox.util.ScreenshotIds
import com.better.heybox.util.ScreenshotSelectionManager
import java.lang.ref.WeakReference
import org.json.JSONArray

class PostImageSaveButtonHook(private val module: MainModule) {
    private val handler = Handler(Looper.getMainLooper())
    private var activity = WeakReference<Activity>(null)
    private var button: TextView? = null
    private var generation = 0
    private var installed = false
    private var currentUrls: List<String> = emptyList()

    private val poll = object : Runnable {
        override fun run() {
            val host = activity.get() ?: return
            if (host.isFinishing || host.isDestroyed) { clear(); return }
            refresh(host)
            handler.postDelayed(this, 1500L)
        }
    }

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        current = WeakReference(this)
        try {
            module.hook(Activity::class.java.getDeclaredMethod("onResume")).intercept { chain ->
                val result = chain.proceed()
                val host = chain.instanceOrNull as? Activity
                if (host != null && host.packageName == MainModule.TARGET_PKG) {
                    clear()
                    activity = WeakReference(host)
                    handler.post(poll)
                }
                result
            }
            module.hook(Activity::class.java.getDeclaredMethod("onPause")).intercept { chain ->
                val host = chain.instanceOrNull as? Activity
                if (host != null && activity.get() === host) clear()
                chain.proceed()
            }
            loader = classLoader
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "帖子图片保存入口安装失败: $t")
        }
    }

    private var loader: ClassLoader? = null

    private fun clear() {
        generation++
        currentUrls = emptyList()
        handler.removeCallbacks(poll)
        activity.clear()
        removeButton()
    }

    private fun removeButton() {
        button?.let {
            ScreenshotSelectionManager.get().unignore(it)
            (it.parent as? ViewGroup)?.removeView(it)
        }
        button = null
    }

    private fun postWebView(host: Activity): WebView? {
        val decor = host.window?.decorView ?: return null
        val id = ScreenshotIds.id(host, ScreenshotIds.POST_BODY)
        val body = if (id != 0) ScreenshotIds.findDescendantById(decor, id) else null
        // Web-news posts host their body WebView directly in the post scroller.
        val root = body ?: if (host.javaClass.name.endsWith(".WebNewsPostPageActivityV2")) decor else return null
        if (!root.isShown) return null
        val queue = java.util.ArrayDeque<View>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count++ < 1000) {
            val view = queue.removeFirst()
            if (view is WebView && view.isShown) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return null
    }

    private fun nativeImages(host: Activity): List<String> {
        // Read the post-owned view model directly; pager fragments need not be visible.
        try {
            val base = Class.forName("com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.BasePostPageActivityV2", false, host.classLoader)
            if (base.isInstance(host)) {
                fun call(value: Any?, name: String): Any? = value?.javaClass?.getMethod(name)?.invoke(value)
                val vm = base.getMethod("s5").invoke(host)
                val post = call(vm, "Z")
                val section = call(post, "getPostContentSection")
                val content = call(section, "getContent")
                val images = call(content, "getImageTextContentModels") as? List<*>
                val urls = images.orEmpty().mapNotNull { call(it, "getUrl") as? String }
                    .filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
                if (urls.isNotEmpty()) return urls
            }
        } catch (_: Throwable) { }
        fun visit(manager: Any, depth: Int): List<String> {
            if (depth > 8) return emptyList()
            val managerType = Class.forName("androidx.fragment.app.FragmentManager", false, host.classLoader)
            val getFragments = try { managerType.getMethod("getFragments") } catch (_: NoSuchMethodException) { managerType.getMethod("M0") }
            val fragments = getFragments.invoke(manager) as? List<*>
                ?: return emptyList()
            for (fragment in fragments.asReversed()) {
                if (fragment == null) continue
                try {
                    val visible = fragment.javaClass.getMethod("isVisible").invoke(fragment) as? Boolean ?: false
                    if (!visible) continue
                    val child = fragment.javaClass.getMethod("getChildFragmentManager").invoke(fragment)
                    val nested = visit(child, depth + 1)
                    if (nested.isNotEmpty()) return nested
                    var type: Class<*>? = fragment.javaClass
                    while (type != null && type.name != "com.max.xiaoheihe.module.bbs.post.ui.fragments.v2.PostCommentFragmentV2") {
                        type = type.superclass
                    }
                    if (type == null) continue
                    val info = type.getMethod("F2").invoke(fragment) ?: continue
                    val thumbs = info.javaClass.getMethod("getThumbs").invoke(info) as? List<*> ?: continue
                    val urls = thumbs.filterIsInstance<String>().filter {
                        it.startsWith("https://") || it.startsWith("http://")
                    }.distinct()
                    if (urls.isNotEmpty()) return urls
                } catch (_: Throwable) { }
            }
            return emptyList()
        }
        return try {
            val manager = host.javaClass.getMethod("getSupportFragmentManager").invoke(host)
            visit(manager, 0)
        } catch (_: Throwable) { emptyList() }
    }

    private fun refresh(host: Activity) {
        generation++
        val native = nativeImages(host)
        val web = postWebView(host)
        if (web == null && native.isNotEmpty() && !ScreenshotSelectionManager.get().isActive()) {
            currentUrls = native
            ensureButton(host)
            return
        }
        if (web == null || ScreenshotSelectionManager.get().isActive()) {
            generation++
            currentUrls = emptyList()
            removeButton()
            return
        }
        val token = generation
        val pageUrl = web.url
        extract(web) { urls ->
            if (token != generation || web.url != pageUrl || activity.get() !== host || postWebView(host) !== web ||
                ScreenshotSelectionManager.get().isActive()) return@extract
            currentUrls = (native + urls).distinct()
            if (currentUrls.isEmpty()) removeButton() else ensureButton(host)
        }
    }

    private fun extract(web: WebView, callback: (List<String>) -> Unit) {
        try {
            val id = web.resources.getIdentifier("rb_1", "id", web.context.packageName)
            val raw = if (id != 0) web.getTag(id) as? String else null
            val blocks = try {
                val response = org.json.JSONObject(raw ?: "{}")
                response.optJSONObject("result")?.optJSONObject("link")?.optJSONArray("content")
                    ?: response.optJSONObject("body")?.optString("text")
                        ?.takeIf { it.isNotBlank() }?.let { JSONArray(it) }
            } catch (_: org.json.JSONException) { null }
            val script = if (blocks != null) "($EXTRACT_BODY_JS)(${blocks})" else EXTRACT_JS
            web.evaluateJavascript(script) { result ->
                val urls = LinkedHashSet<String>()
                try {
                    val array = JSONArray(result ?: "[]")
                    for (i in 0 until array.length()) {
                        val url = array.optString(i, "")
                        if (url.startsWith("https://") || url.startsWith("http://")) urls.add(url)
                    }
                } catch (_: Throwable) { }
                callback(urls.toList())
            }
        } catch (_: Throwable) { callback(emptyList()) }
    }

    private fun ensureButton(host: Activity) {
        if (!module.isEnabled(App.KEY_POST_IMAGE_SAVE_BUTTON, false)) {
            removeButton()
            return
        }
        val content = host.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        if (button?.parent === content) return
        removeButton()
        val view = TextView(host).apply {
            text = "保存原图"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(ThemeUtils.dp(host, 12f), ThemeUtils.dp(host, 8f),
                ThemeUtils.dp(host, 12f), ThemeUtils.dp(host, 8f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ThemeUtils.dp(host, 20f).toFloat()
                setColor(if (ThemeUtils.isDarkMode(host)) 0xE6333338.toInt() else 0xE61F2937.toInt())
                setStroke(ThemeUtils.dp(host, 1f), 0x55FFFFFF)
            }
            alpha = 0.92f
            elevation = ThemeUtils.dp(host, 4f).toFloat()
            setOnClickListener {
                val cl = loader
                if (currentUrls.isNotEmpty() && cl != null) {
                    BatchImageSaveHook.start(host, currentUrls, cl)
                    return@setOnClickListener
                }
                val web = postWebView(host) ?: return@setOnClickListener
                val token = generation
                val pageUrl = web.url
                extract(web) { urls ->
                    val cl = loader
                    if (token == generation && web.url == pageUrl && activity.get() === host && postWebView(host) === web &&
                        urls.isNotEmpty() && cl != null) BatchImageSaveHook.start(host, urls, cl)
                }
            }
        }
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = ThemeUtils.dp(host, 12f)
            bottomMargin = ThemeUtils.dp(host, 190f)
        }
        content.addView(view, params)
        button = view
        ScreenshotSelectionManager.get().ignore(view)
    }

    companion object {
        private var current = WeakReference<PostImageSaveButtonHook>(null)
        internal fun currentImages(context: android.content.Context, linkId: String?): List<String> {
            val hook = current.get() ?: return emptyList()
            val host = hook.activity.get() ?: return emptyList()
            var owner = context
            while (owner is android.content.ContextWrapper && owner !is Activity) owner = owner.baseContext
            if (owner !== host || linkId.isNullOrBlank()) return emptyList()
            val currentId = try {
                val base = Class.forName("com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.BasePostPageActivityV2", false, host.classLoader)
                if (!base.isInstance(host)) return emptyList()
                val vm = base.getMethod("s5").invoke(host)
                val post = vm.javaClass.getMethod("Z").invoke(vm)
                val meta = post.javaClass.getMethod("getMetaInfo").invoke(post)
                meta.javaClass.getMethod("getLinkId").invoke(meta)?.toString()
            } catch (_: Throwable) { null }
            return if (currentId == linkId) hook.currentUrls.toList() else emptyList()
        }
        private const val EXTRACT_BODY_JS = """function(blocks){try{var a=[],seen={};function add(s){if(!s)return;try{var u=new URL(s,document.baseURI);if(!/^https?:$/.test(u.protocol))return;var cdn=/(^|\.)max-c\.com$/i.test(u.hostname);var key=cdn?u.pathname:u.href;if(!seen[key]){seen[key]=1;a.push(u.href)}}catch(e){}}blocks.forEach(function(b){if(b.type!=='html'||!b.text)return;var doc=new DOMParser().parseFromString(b.text,'text/html');doc.querySelectorAll('img').forEach(function(i){add(i.getAttribute('data-original')||i.getAttribute('data-src')||i.getAttribute('data-lazy-src')||i.getAttribute('src'))})});blocks.forEach(function(b){if(b.type==='img'||b.type==='image')add(b.url)});return a}catch(e){return []}}"""
        private const val EXTRACT_JS = """(function(){try{var a=[],seen={};document.querySelectorAll('img').forEach(function(i){if(i.closest('[class*=avatar],[class*=comment],[class*=recommend],[class*=emoji],[class*=icon]'))return;var s=i.getAttribute('data-original')||i.getAttribute('data-src')||i.getAttribute('data-lazy-src')||i.getAttribute('src')||'';if(!s)return;try{s=new URL(s,document.baseURI).href}catch(e){return}if(/^https?:\/\//i.test(s)&&!seen[s]){seen[s]=1;a.push(s)}});return a}catch(e){return []}})()"""
    }
}
