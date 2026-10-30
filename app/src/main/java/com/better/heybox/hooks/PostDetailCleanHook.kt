package com.better.heybox.hooks

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import com.better.heybox.App
import com.better.heybox.MainModule
import com.better.heybox.VersionUtils
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class PostDetailCleanHook(private val module: MainModule) {

    init {
        sInstance = this
    }

    fun install(cl: ClassLoader) {
        refresh()
        var hooked = 0
        hooked += hookOnCreateView(cl)
        hooked += hookOnResume(cl)
        hooked += hookSetVisibility(cl)
        hooked += hookClickListener(cl)
        hooked += hookSetText(cl)
        hooked += hookWebViewClient()
        module.logd(
            Log.INFO, MainModule.TAG, "✔ 帖子详情精简 Hook 已安装（$hooked 处）" +
                    " | 屏蔽=" + onOff(sClean) +
                    " 话题防误触=" + onOff(sTopicNoClick) +
                    " 话题隐藏=" + onOff(sTopicHide) +
                    " 自定义=" + sCustomIds.size + " 个"

        )
        if (hooked <= 1) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "帖子详情精简：只有 $hooked 个挂点成功，功能可能不完整"
            )
        }
    }

    private fun hookWebViewClient(): Int {
        return try {
            val setter = WebView::class.java.getDeclaredMethod(
                "setWebViewClient", WebViewClient::class.java
            )
            module.hook(setter).intercept { chain ->
                val client = chain.arg(0)
                if (client != null) {
                    try {
                        hookPageFinished(client.javaClass)
                    } catch (ignored: Throwable) {
                    }
                }
                chain.proceed()
            }
            1
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "帖子详情精简：WebView 注入 Hook 失败: $t")
            0
        }
    }

    private fun hookPageFinished(clientClass: Class<*>?) {
        if (clientClass == null || !clientClass.name.startsWith("com.max.")
            || !sHookedWebClients.add(clientClass)
        ) {
            return
        }
        var c: Class<*>? = clientClass
        while (c != null && c !== WebViewClient::class.java) {
            val target: Method
            try {
                target = c.getDeclaredMethod("onPageFinished", WebView::class.java, String::class.java)
            } catch (e: NoSuchMethodException) {
                c = c.superclass
                continue
            }
            if (!sHookedWebClients.add(c) && c !== clientClass) {
                return
            }
            try {
                target.isAccessible = true
                module.hook(target).intercept { chain ->
                    val result = chain.proceed()
                    val web = chain.arg(0)
                    if (web is WebView) {
                        injectWebClean(web)
                    }
                    result
                }
                module.logd(Log.INFO, MainModule.TAG, "✔ 帖子详情精简 WebView 注入 @ " + c.name)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "帖子详情精简：onPageFinished 挂载失败: $t")
            }
            return
        }
    }

    private fun hookSetVisibility(cl: ClassLoader): Int {
        return try {
            val viewClass = Class.forName("android.view.View", false, cl)
            val setVisibility = viewClass.getDeclaredMethod(
                "setVisibility", Int::class.javaPrimitiveType
            )
            setVisibility.isAccessible = true
            module.hook(setVisibility).intercept { chain ->
                val result = chain.proceed()
                try {
                    val target = chain.instanceOrNull
                    val arg = chain.arg(0)
                    if (target !is View || arg !is Int
                        || (!sClean && !sTopicHide)
                    ) {
                        return@intercept result
                    }
                    val visibility = arg
                    if (visibility == View.GONE) {
                        return@intercept result
                    }
                    val view = target
                    val topicId = sTopicRowId
                    if (sTopicHide && topicId != 0 && view.id == topicId) {
                        val topicRoot = view.rootView
                        if (sSeen.containsKey(topicRoot)
                            || (sInDetailPage && !view.isAttachedToWindow)
                        ) {
                            val flexHost = flexboxRecyclerParent(view)
                            val hideTarget = flexHost ?: view
                            try {
                                hideTarget.visibility = View.GONE
                            } catch (ignored: Throwable) {
                            }
                            sTopicSuppressed.add(hideTarget)
                        }
                        return@intercept null
                    }
                    if (!sClean || !isSuppressedId(view)) {
                        return@intercept result
                    }
                    val root = view.rootView
                    if (!sSeen.containsKey(root)
                        && !(sInDetailPage && !view.isAttachedToWindow)
                    ) {
                        return@intercept result
                    }
                    try {
                        view.visibility = View.GONE
                    } catch (ignored: Throwable) {
                    }
                    sSuppressed.add(view)
                    val hits = sSuppressHits.incrementAndGet()
                    if (hits <= 5) {
                        module.logd(
                            Log.INFO, MainModule.TAG,
                            "帖子详情精简：兜底拦下 " + sNameById[view.id] +
                                    " 的显示请求（第 $hits 次）"

                        )
                    }
                } catch (ignored: Throwable) {
                }
                null
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 帖子详情精简 Hook：View.setVisibility 兜底")
            sHookSetVisibility = 1
            1
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "帖子详情精简：setVisibility 兜底挂载失败: $t")
            0
        }
    }

    private fun hookClickListener(cl: ClassLoader): Int {
        return try {
            val viewClass = Class.forName("android.view.View", false, cl)
            val listenerClass = Class.forName("android.view.View\$OnClickListener", false, cl)
            val setOnClickListener = viewClass.getDeclaredMethod("setOnClickListener", listenerClass)
            setOnClickListener.isAccessible = true
            module.hook(setOnClickListener).intercept { chain ->
                val target = chain.instanceOrNull
                val listener = chain.arg(0)
                if (target !is View || listener == null || !sTopicNoClick) {
                    return@intercept chain.proceed()
                }
                val view = target
                if (!isInTopicRow(view)) {
                    return@intercept chain.proceed()
                }
                val hits = sTopicClickBlocked.incrementAndGet()
                if (hits <= 5) {
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "帖子详情精简：拦下话题行子 View 的点击监听注册（第 $hits 次）"
                    )
                }
                null
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 帖子详情精简 Hook：话题行点击监听拦截")
            sHookClickListener = 1
            1
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "帖子详情精简：点击监听拦截挂载失败: $t")
            0
        }
    }

    private fun hookSetText(cl: ClassLoader): Int {
        return try {
            val textViewClass = Class.forName("android.widget.TextView", false, cl)
            val setText = textViewClass.getDeclaredMethod("setText", CharSequence::class.java)
            setText.isAccessible = true
            module.hook(setText).intercept { chain ->
                val result = chain.proceed()
                try {
                    val target = chain.instanceOrNull
                    if (target !is View || !sClean || !sInDetailPage) {
                        return@intercept result
                    }
                    val view = target
                    if (view.visibility == View.GONE) {
                        return@intercept result
                    }
                    val text = textOf(view)
                    if (!looksLikeRelevantSearchTitle(text)) {
                        return@intercept result
                    }
                    val row = findTextRowContainer(view) ?: return@intercept result
                    val hits = sTextHideHits.incrementAndGet()
                    if (hits <= 8) {
                        module.logd(
                            Log.INFO, MainModule.TAG,
                            "帖子详情精简：按文本拦下「$text」所在行（第 $hits 次）" +
                                    " class=" + row.javaClass.name +
                                    " id=" + idLabel(row) +
                                    " 路径=" + ancestorPath(row)

                        )
                    }
                    hide(row, "相关搜索(文本)", "文本:$text")
                } catch (ignored: Throwable) {
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 帖子详情精简 Hook：TextView.setText 文本兜底")
            sHookSetText = 1
            1
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "帖子详情精简：setText 文本兜底挂载失败: $t")
            0
        }
    }

    private fun hookOnCreateView(cl: ClassLoader): Int {
        var count = 0
        for (className in FRAGMENT_FALLBACK_CLASSES) {
            val fragment: Class<*>
            try {
                fragment = Class.forName(className, false, cl)
            } catch (t: Throwable) {
                continue
            }
            try {
                val onCreateView = fragment.getDeclaredMethod(
                    "onCreateView",
                    LayoutInflater::class.java, ViewGroup::class.java,
                    android.os.Bundle::class.java
                )
                onCreateView.isAccessible = true
                module.hook(onCreateView).intercept { chain ->
                    val result = chain.proceed()
                    if (result is View) {
                        safeApply(result)
                    }
                    result
                }
                count++
                sHookCreateView = count
                module.logd(Log.INFO, MainModule.TAG, "✔ 帖子详情精简 Hook：onCreateView @ $className")
            } catch (t: Throwable) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "帖子详情精简：$className onCreateView 挂载失败: $t"
                )
            }
        }
        return count
    }

    private fun hookOnResume(cl: ClassLoader): Int {
        val getView: Method
        try {
            val fragmentBase = Class.forName("androidx.fragment.app.Fragment", false, cl)
            getView = fragmentBase.getMethod("getView")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "帖子详情精简：取不到 Fragment#getView: $t")
            return 0
        }
        val getViewRef = getView
        var count = 0
        for (className in FRAGMENT_FALLBACK_CLASSES) {
            val fragment: Class<*>
            try {
                fragment = Class.forName(className, false, cl)
            } catch (t: Throwable) {
                continue
            }
            try {
                val onResume = fragment.getDeclaredMethod("onResume")
                onResume.isAccessible = true
                module.hook(onResume).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val view = getViewRef.invoke(chain.instanceOrNull)
                        if (view is View) {
                            safeApply(view)
                        }
                    } catch (ignored: Throwable) {
                    }
                    result
                }
                count++
                sHookResume = count
                module.logd(Log.INFO, MainModule.TAG, "✔ 帖子详情精简 Hook：onResume @ $className")
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "帖子详情精简：$className onResume 挂载失败: $t")
            }
        }
        return count
    }

    companion object {

        private const val FRAGMENT_BASE_CLASS = "com.max.hbcommon.base.e"

        private val FRAGMENT_FALLBACK_CLASSES = arrayOf(
            "com.max.hbcommon.base.e",
            "com.max.hbcommon.base.BaseFragment",
            "androidx.fragment.app.Fragment",
        )

        private val PAGE_ANCHORS = arrayOf(
            "vg_relevant_search",
            "insert_fragment_container",
            "rv_post_guide",
            "vg_hashtag",
            "ll_article_collection",
        )

        private val IDS_RELEVANT_SEARCH = arrayOf(
            "vg_relevant_search",
            "line_relevant_search",
        )

        private val IDS_POST_GUIDE = arrayOf(
            "rv_post_guide",
        )

        private val IDS_MINI_APP = arrayOf(
            "vp_hor_mini_program",
            "rv_mini_program_v3",
        )

        private val IDS_COLLECTION = arrayOf(
            "ll_article_collection",
        )

        private val IDS_EVENT = arrayOf(
            "rv_event_entry",
        )

        private val IDS_RELEVANT_SEARCH_BODY = arrayOf(
            "rv_search_query",
            "relevant_search_divider",
        )

        private val RELEVANT_SEARCH_TEXTS = arrayOf(
            "相关搜索", "都在搜", "大家还在搜", "相似搜索",
        )

        private const val MAX_TEXT_CONTAINER_UP = 6

        private const val IDS_INSERT_CONTAINER = "insert_fragment_container"

        private const val IDS_TOPIC_ROW = "vg_hashtag"

        private val HIDE_GROUPS = arrayOf(
            IDS_RELEVANT_SEARCH,
            IDS_RELEVANT_SEARCH_BODY,
            IDS_POST_GUIDE,
            IDS_MINI_APP,
            IDS_COLLECTION,
            IDS_EVENT,
        )

        private val GROUP_LABELS = arrayOf(
            "相关搜索(评论tab)",
            "相关搜索(正文tab)",
            "文字配图横幅",
            "小程序推荐",
            "合集",
            "活动",
        )

        private val CANDIDATE_KEYWORDS = arrayOf(
            "workshop", "gongfang", "factory", "mini_app", "mini_program",
            "activity", "event", "collection", "compilation",
        )

        private val CANDIDATE_TEXTS = arrayOf(
            "工坊",
        )

        private const val MAX_WALK_DEPTH = 24
        private const val MAX_WALK_NODES = 3000
        private const val MAX_CANDIDATES = 40

        const val BUILD_MARKER = "r7-topichide"

        @Volatile
        private var sInstance: PostDetailCleanHook? = null

        @Volatile
        private var sClean = false

        @Volatile
        private var sTopicNoClick = false

        @Volatile
        private var sTopicHide = false

        @Volatile
        private var sTopicRowId = 0

        private val sTopicHiddenOriginal = WeakHashMap<View, Int>()

        private val sTopicSuppressed: MutableSet<View> =
            Collections.newSetFromMap(WeakHashMap<View, Boolean>())

        @Volatile
        private var sCustomIds: Set<String> = emptySet()

        private val sSeen = WeakHashMap<View, Boolean>()

        private val sWatching = WeakHashMap<View, Boolean>()

        private val sHiddenOriginal = WeakHashMap<View, Int>()

        private val sClickOriginal = WeakHashMap<View, BooleanArray>()

        private val sIds = ConcurrentHashMap<String, Int>()

        private val sIdMisses = ConcurrentHashMap<String, Int>()

        private val sSeenCustom: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val sProcessed = WeakHashMap<View, Boolean>()

        private val sCheckedAt = WeakHashMap<View, Long>()

        private const val RESHOW_CHECK_INTERVAL_MS = 150L

        private val sCandidates = LinkedHashMap<String, String>()

        private val sDetailHits: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        private var sHookCreateView = 0

        @Volatile
        private var sHookResume = 0

        @Volatile
        private var sHookSetVisibility = 0

        @Volatile
        private var sSuppressedIds: Set<Int>? = null

        private val S_ID_LOCK = Any()

        private val sIdByName = ConcurrentHashMap<String, Int>()

        private val sNameById = ConcurrentHashMap<Int, String>()

        private val sSuppressed: MutableSet<View> =
            Collections.newSetFromMap(WeakHashMap<View, Boolean>())

        private val sSuppressHits = AtomicLong()

        @Volatile
        private var sTopicRow: View? = null

        private val sTopicRows: MutableSet<View> =
            Collections.newSetFromMap(WeakHashMap<View, Boolean>())

        @Volatile
        private var sInDetailPage = false

        private val sTextHideHits = AtomicLong()

        @Volatile
        private var sHookSetText = 0

        private val sTopicClickBlocked = AtomicLong()

        @Volatile
        private var sHookClickListener = 0

        @Volatile
        private var sActivityName = "未知"

        @Volatile
        private var sLastApply = "还没跑过"

        private const val WEB_HIDE_SELECTORS =
            ".bbs-link-section-article-collection," +
                    ".bbs-link-section-event-entry," +
                    ".bbs-link-section-event-tip," +
                    ".bbs-link-section-lottery," +
                    ".bbs-link-view-story-related"

        private const val WEB_TOPIC_SELECTORS =
            ".bbs-link-section-tags,.bbs-link-section-tags-item"

        private val sHookedWebClients: MutableSet<Class<*>> =
            Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

        private val sWebInjected = AtomicLong()

        private val sForensicsDone = AtomicBoolean()

        @Volatile
        private var sVisibleSearchDump = "还没有抓过"

        private val sTextScanBudget = AtomicInteger(4000)

        private const val MAX_ID_RESOLVE_ATTEMPTS = 200

        @JvmStatic
        fun refresh() {
            val instance = sInstance ?: return
            val m = instance.module
            sClean = m.isEnabled(App.KEY_POST_DETAIL_CLEAN, false)
            sTopicNoClick = m.isEnabled(App.KEY_POST_DETAIL_TOPIC_NO_CLICK, false)
            sTopicHide = m.isEnabled(App.KEY_POST_DETAIL_TOPIC_HIDE, false)
            sCustomIds = parseIds(m.getString(App.KEY_POST_DETAIL_HIDE_IDS, ""))
            clearSuppressedIds()
            sProcessed.clear()
            for (root in ArrayList(sSeen.keys)) {
                try {
                    apply(root, true)
                } catch (ignored: Throwable) {
                }
            }
            if (!sClean) {
                restoreSuppressed()
            }
            if (!sTopicHide) {
                for (view in ArrayList(sTopicSuppressed)) {
                    try {
                        view.visibility = View.VISIBLE
                    } catch (ignored: Throwable) {
                    }
                }
                sTopicSuppressed.clear()
            }
        }

        private fun clearSuppressedIds() {
            synchronized(S_ID_LOCK) {
                sSuppressedIds = null
                sIdByName.clear()
                sNameById.clear()
            }
        }

        private fun restoreSuppressed() {
            for (view in ArrayList(sSuppressed)) {
                try {
                    view.visibility = View.VISIBLE
                } catch (ignored: Throwable) {
                }
            }
            sSuppressed.clear()
        }

        private fun injectWebClean(web: WebView) {
            try {
                if (!anyEnabled() || !isDetailWebView(web)) {
                    return
                }
                web.evaluateJavascript(buildWebCleanJs(sClean, sTopicNoClick, sTopicHide), null)
                sWebInjected.incrementAndGet()
            } catch (ignored: Throwable) {
            }
        }

        private fun isDetailWebView(web: WebView): Boolean {
            if (sSeen.containsKey(web.rootView) || sInDetailPage) {
                return true
            }
            var ctx: Context? = web.context
            while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
                ctx = ctx.baseContext
            }
            return ctx is android.app.Activity
                    && ctx.javaClass.name.contains("PostPage")
        }

        @JvmStatic
        fun buildWebCleanJs(clean: Boolean, topicNoClick: Boolean, topicHide: Boolean): String {
            val css = StringBuilder()
            if (clean) {
                css.append(WEB_HIDE_SELECTORS).append("{display:none!important}")
            }
            if (topicNoClick) {
                css.append(WEB_TOPIC_SELECTORS).append("{pointer-events:none!important}")
            }
            if (topicHide) {
                css.append(WEB_TOPIC_SELECTORS).append("{display:none!important}")
            }
            return "(function(){try{" +
                    "var d=document,s=d.getElementById('bh-post-clean');" +
                    "if(!s){s=d.createElement('style');s.id='bh-post-clean';" +
                    "(d.head||d.documentElement).appendChild(s);}" +
                    "s.textContent='" + css + "';" +
                    "window.__bhTopicNoClick=" + topicNoClick + ";" +
                    "if(!window.__bhTopicStop){window.__bhTopicStop=1;" +
                    "d.addEventListener('click',function(e){" +
                    "if(window.__bhTopicNoClick&&e.target&&e.target.closest" +
                    "&&e.target.closest('" + WEB_TOPIC_SELECTORS + "')){" +
                    "e.stopPropagation();e.preventDefault();}},true);}" +
                    "if(!window.__bhObs&&window.MutationObserver){" +
                    "window.__bhObs=new MutationObserver(function(){" +
                    "if(!d.getElementById('bh-post-clean')&&s){(d.head||d.documentElement).appendChild(s);}});" +
                    "window.__bhObs.observe(d.documentElement,{childList:true,subtree:true});" +
                    "}catch(e){}})();"

        }

        private fun isInTopicRow(view: View): Boolean {
            if (sTopicRows.isEmpty()) {
                return false
            }
            var node: View? = view
            var i = 0
            while (i < 16 && node != null) {
                if (sTopicRows.contains(node)) {
                    return true
                }
                val parent: ViewParent? = node.parent
                node = parent as? View
                i++
            }
            return false
        }

        private fun isSuppressedId(view: View): Boolean {
            return try {
                val id = view.id
                if (id == View.NO_ID || id == 0) {
                    return false
                }
                val ids = ensureSuppressedIds(view)
                if (ids != null && ids.contains(id)) {
                    resolveNamesFor(view, id)
                    return true
                }
                false
            } catch (ignored: Throwable) {
                false
            }
        }

        private fun ensureSuppressedIds(view: View): Set<Int>? {
            val cached = sSuppressedIds
            if (cached != null) {
                return cached
            }
            synchronized(S_ID_LOCK) {
                if (sSuppressedIds != null) {
                    return sSuppressedIds
                }
                val context = contextOf(view) ?: return null
                val resources: Resources = context.resources ?: return null
                val ids = HashSet<Int>()
                for (group in HIDE_GROUPS) {
                    for (name in group) {
                        var id = resources.getIdentifier(name, "id", context.packageName)
                        if (id == 0) {
                            id = resources.getIdentifier(name, "id", MainModule.TARGET_PKG)
                        }
                        if (id != 0) {
                            ids.add(id)
                            sIdByName[name] = id
                            sNameById[id] = name
                        }
                    }
                }
                for (name in sCustomIds) {
                    var id = resources.getIdentifier(name, "id", context.packageName)
                    if (id == 0) {
                        id = resources.getIdentifier(name, "id", MainModule.TARGET_PKG)
                    }
                    if (id != 0) {
                        ids.add(id)
                        sIdByName[name] = id
                        sNameById[id] = name
                    }
                }
                if (ids.isEmpty()) {
                    return null
                }
                sSuppressedIds = ids
                return ids
            }
        }

        private fun resolveNamesFor(view: View, id: Int) {
            val name = sNameById[id]
            if (name != null) {
                sDetailHits.add(name)
            }
        }

        private fun contextOf(view: View): Context? {
            return try {
                val context = view.context
                if (context != null) {
                    return context
                }
                val root = view.rootView
                root?.context
            } catch (t: Throwable) {
                null
            }
        }

        private fun isPostDetailView(view: View?): Boolean {
            if (view == null) {
                return false
            }
            var hit = false
            for (anchor in PAGE_ANCHORS) {
                val id = idOf(view, anchor)
                if (id != 0 && view.findViewById<View>(id) != null) {
                    sDetailHits.add(anchor)
                    hit = true
                }
            }
            return hit
        }

        private fun safeApply(root: View?) {
            try {
                if (root == null || !anyEnabled()) {
                    return
                }
                if (!isPostDetailView(root)) {
                    sInDetailPage = false
                    return
                }
                sInDetailPage = true
                var scope = root.rootView
                if (scope == null) {
                    scope = root
                }
                apply(scope, true)
            } catch (t: Throwable) {
                val instance = sInstance
                instance?.module?.logd(
                    Log.WARN, MainModule.TAG,
                    "帖子详情精简：apply 异常: $t"
                )
            }
        }

        private fun anyEnabled(): Boolean {
            return sClean || sTopicNoClick || sTopicHide
        }

        private fun onOff(enabled: Boolean): String =
            if (enabled) "开" else "关"

        private fun apply(root: View?, force: Boolean): Boolean {
            if (root == null) {
                return false
            }
            if (!force && sProcessed.containsKey(root)) {
                return false
            }
            val firstSeen = sSeen.put(root, java.lang.Boolean.TRUE) == null
            var changed = false
            var hit = 0
            rememberActivity(root)
            if (sClean) {
                changed = changed or applyHideGroups(root)
                changed = changed or applyCustomIds(root)
                changed = changed or hideRelevantSearchByText(root)
                observeCandidates(root)
                hit = countFoundTargets(root)
            } else {
                changed = changed or restoreHidden()
            }
            if (sTopicNoClick) {
                rememberTopicRow(root)
                changed = changed or applyTopicNoClick(root)
            } else {
                changed = changed or restoreClicks()
            }
            if (sTopicHide) {
                changed = changed or applyTopicHide(root)
            } else {
                changed = changed or restoreTopicHidden()
            }
            sProcessed[root] = java.lang.Boolean.TRUE
            if (anyEnabled()) {
                watch(root)
            }
            if (changed) {
                sLastApply = nowText() + " 改动生效"
            }
            if (firstSeen && anyEnabled()) {
                if (sForensicsDone.compareAndSet(false, true)) {
                    logRootReady(root, hit)
                    logTargetStates(root)
                    sTextScanBudget.set(5000)
                    logVisibleSearchNodes(root)
                }
                retryLater(root, 250L)
                retryLater(root, 900L)
            }
            return changed
        }

        private fun retryLater(root: View, delayMs: Long) {
            try {
                root.postDelayed({
                    try {
                        if (!anyEnabled() || root.windowToken == null) {
                            return@postDelayed
                        }
                        sProcessed.remove(root)
                        apply(root, true)
                    } catch (ignored: Throwable) {
                    }
                }, delayMs)
            } catch (ignored: Throwable) {
            }
        }

        private fun rememberActivity(root: View) {
            try {
                var context: Context? = root.context
                var i = 0
                while (i < 8 && context is android.content.ContextWrapper) {
                    if (context is android.app.Activity) {
                        val name = context.javaClass.name
                        if (name != sActivityName) {
                            sActivityName = name
                        }
                        return
                    }
                    val base = (context as android.content.ContextWrapper).baseContext
                    if (base == null || base === context) {
                        return
                    }
                    context = base
                    i++
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun applyTopicHide(root: View): Boolean {
            return try {
                val id = idOf(root, IDS_TOPIC_ROW)
                if (id == 0) {
                    return false
                }
                sTopicRowId = id
                var view = root.findViewById<View>(id) ?: return false
                val flexHost = flexboxRecyclerParent(view)
                if (flexHost != null) {
                    view = flexHost
                }
                if (view.visibility == View.GONE) {
                    return false
                }
                if (!sTopicHiddenOriginal.containsKey(view)) {
                    sTopicHiddenOriginal[view] = view.visibility
                }
                view.visibility = View.GONE
                true
            } catch (ignored: Throwable) {
                false
            }
        }

        private fun restoreTopicHidden(): Boolean {
            var changed = false
            for (entry in ArrayList(sTopicHiddenOriginal.entries)) {
                val view = entry.key
                val original = sTopicHiddenOriginal.remove(view) ?: continue
                if (view.visibility != original) {
                    view.visibility = original
                    changed = true
                }
            }
            return changed
        }

        private fun rememberTopicRow(root: View) {
            try {
                val id = idOf(root, IDS_TOPIC_ROW)
                if (id != 0) {
                    val topicRow = root.findViewById<View>(id)
                    if (topicRow != null) {
                        sTopicRow = topicRow
                        sTopicRows.add(topicRow)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun hideRelevantSearchByText(root: View): Boolean {
            sTextScanBudget.set(4000)
            val titleNodes = ArrayList<View>()
            collectRelevantSearchTitles(root, 0, intArrayOf(0), titleNodes)
            var changed = false
            for (title in titleNodes) {
                val container = findTextRowContainer(title) ?: continue
                if (container.width > 0 && container.height > 0
                    && container.height > title.height * 12
                ) {
                    continue
                }
                changed = changed or hide(container, "相关搜索(文本)", "文本:" + textOf(title))
            }
            return changed
        }

        private fun logVisibleSearchNodes(root: View) {
            val instance = sInstance ?: return
            val found = ArrayList<View>()
            collectVisibleSearchNodes(root, 0, intArrayOf(0), found)
            if (found.isEmpty()) {
                instance.module.logd(
                    Log.INFO, MainModule.TAG,
                    "帖子详情精简：正文区没有可见的「搜」类文本节点"
                )
                return
            }
            val sb = StringBuilder()
            for (view in found) {
                sb.append('\n').append("  ").append(idLabel(view)).append('=')
                    .append(visibilityName(view.visibility))
                    .append(" 文本=").append(abbreviate(textOf(view)))
                    .append(" 路径=").append(ancestorPath(view))
            }
            sVisibleSearchDump = sb.toString()
            instance.module.logd(
                Log.INFO, MainModule.TAG,
                "帖子详情精简：可见的「搜」类节点（" + found.size + " 个）$sb"
            )
        }

        private fun collectVisibleSearchNodes(
            view: View?, depth: Int, visited: IntArray,
            out: MutableList<View>
        ) {
            if (view == null || depth > 20 || visited[0] > 5000 || out.size >= 25
                || sTextScanBudget.get() <= 0
            ) {
                return
            }
            visited[0]++
            sTextScanBudget.decrementAndGet()
            if (view is TextView && view.visibility == View.VISIBLE) {
                val text = textOf(view)
                if (text != null && text.contains("搜")) {
                    out.add(view)
                }
            }
            if (view is ViewGroup) {
                val group = view
                val count = min(group.childCount, 60)
                for (i in 0 until count) {
                    collectVisibleSearchNodes(group.getChildAt(i), depth + 1, visited, out)
                }
            }
        }

        private fun abbreviate(text: String?): String {
            if (text == null) {
                return ""
            }
            return if (text.length <= 40) text else text.substring(0, 40) + "…"
        }

        private fun collectRelevantSearchTitles(
            view: View?, depth: Int, visited: IntArray,
            out: MutableList<View>
        ) {
            if (view == null || depth > 16 || visited[0] > 4000
                || sTextScanBudget.get() <= 0
            ) {
                return
            }
            visited[0]++
            sTextScanBudget.decrementAndGet()
            if (view is TextView) {
                val text = textOf(view)
                if (looksLikeRelevantSearchTitle(text)) {
                    out.add(view)
                }
            }
            if (view is ViewGroup) {
                val group = view
                val count = min(group.childCount, 60)
                for (i in 0 until count) {
                    collectRelevantSearchTitles(group.getChildAt(i), depth + 1, visited, out)
                }
            }
        }

        private fun looksLikeRelevantSearchTitle(text: String?): Boolean {
            if (text == null) {
                return false
            }
            val value = text.trim()
            if (value.isEmpty() || value.length > 12) {
                return false
            }
            for (keyword in RELEVANT_SEARCH_TEXTS) {
                if (value.startsWith(keyword)) {
                    return true
                }
            }
            return false
        }

        private fun findTextRowContainer(title: View): View {
            var node = title
            var candidate: View? = null
            for (i in 0 until MAX_TEXT_CONTAINER_UP) {
                val parent: ViewParent? = node.parent
                if (parent !is ViewGroup) {
                    break
                }
                val group = parent
                if (group.childCount >= 2) {
                    candidate = group
                    break
                }
                node = group
            }
            if (candidate == null) {
                val parent: ViewParent? = title.parent
                candidate = if (parent is View) parent else title
            }
            return candidate
        }

        private fun countFoundTargets(root: View): Int {
            var n = 0
            for (group in HIDE_GROUPS) {
                for (name in group) {
                    val id = idOf(root, name)
                    if (id != 0 && root.findViewById<View>(id) != null) {
                        n++
                    }
                }
            }
            return n
        }

        private fun applyHideGroups(root: View): Boolean {
            var changed = false
            for (i in HIDE_GROUPS.indices) {
                changed = changed or applyGroup(root, GROUP_LABELS[i], HIDE_GROUPS[i])
            }
            return changed
        }

        private fun applyGroup(root: View, label: String, names: Array<String>): Boolean {
            var changed = false
            for (name in names) {
                val id = idOf(root, name)
                if (id == 0) {
                    continue
                }
                val view = root.findViewById<View>(id) ?: continue
                changed = changed or hide(view, label, name)
            }
            return changed
        }

        private fun applyCustomIds(root: View): Boolean {
            if (sCustomIds.isEmpty()) {
                return false
            }
            var changed = false
            for (name in sCustomIds) {
                val id = idOf(root, name)
                var view = if (id == 0) null else root.findViewById<View>(id)
                if (view == null) {
                    view = findByIdName(scopeOf(root), name)
                }
                if (view == null) {
                    if (sSeenCustom.add(name)) {
                        val instance = sInstance
                        instance?.module?.logd(
                            Log.WARN, MainModule.TAG,
                            "帖子详情精简：本页未找到自定义视图 $name，跳过"
                        )
                    }
                    continue
                }
                changed = changed or hide(view, "自定义", name)
            }
            return changed
        }

        private fun hide(viewIn: View, label: String, name: String): Boolean {
            var view = viewIn
            val flexHost = flexboxRecyclerParent(view)
            if (flexHost != null) {
                view = flexHost
            }
            if (view.visibility == View.GONE) {
                return false
            }
            if (!sHiddenOriginal.containsKey(view)) {
                sHiddenOriginal[view] = view.visibility
            }
            view.visibility = View.GONE
            val instance = sInstance
            if (instance != null && instance.module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                instance.module.logd(
                    Log.INFO, MainModule.TAG,
                    "帖子详情精简：隐藏 $label ($name)"
                )
            }
            return true
        }

        private fun restoreHidden(): Boolean {
            var changed = false
            for (entry in ArrayList(sHiddenOriginal.entries)) {
                val view = entry.key
                val original = sHiddenOriginal.remove(view) ?: continue
                if (view.visibility != original) {
                    view.visibility = original
                    changed = true
                }
            }
            return changed
        }

        private fun flexboxRecyclerParent(view: View): View? {
            var child: View? = view
            var depth = 0
            while (depth < 4 && child != null) {
                val parent: ViewParent? = child.parent
                if (parent !is View) {
                    return null
                }
                val parentView = parent as View
                if (isRecyclerView(parentView)) {
                    return try {
                        val lm = parentView.javaClass.getMethod("getLayoutManager").invoke(parentView)
                        if (lm != null && lm.javaClass.name.contains("Flexbox")) {
                            parentView
                        } else {
                            null
                        }
                    } catch (ignored: Throwable) {
                        null
                    }
                }
                child = parentView
                depth++
            }
            return null
        }

        private fun isRecyclerView(view: View): Boolean {
            var cls: Class<*>? = view.javaClass
            while (cls != null) {
                if ("androidx.recyclerview.widget.RecyclerView" == cls.name) {
                    return true
                }
                cls = cls.superclass
            }
            return false
        }

        private fun applyTopicNoClick(root: View): Boolean {
            val id = idOf(root, IDS_TOPIC_ROW)
            if (id == 0) {
                return false
            }
            val topicRow = root.findViewById<View>(id) ?: return false
            var changed = false
            if (topicRow.isClickable || topicRow.isLongClickable || topicRow.hasOnClickListeners()) {
                noteClickOriginal(topicRow)
                topicRow.setOnClickListener(null)
                topicRow.setOnLongClickListener(null)
                topicRow.isClickable = false
                topicRow.isLongClickable = false
                topicRow.setOnTouchListener(null)
                changed = true
            }
            changed = changed or disableClicksDeep(topicRow, 0, intArrayOf(0))
            return changed
        }

        private fun disableClicksDeep(view: View?, depth: Int, visited: IntArray): Boolean {
            if (view == null || depth > 12 || visited[0] > 400) {
                return false
            }
            visited[0]++
            var changed = false
            if (view.isClickable || view.isLongClickable || view.hasOnClickListeners()) {
                noteClickOriginal(view)
                view.setOnClickListener(null)
                view.setOnLongClickListener(null)
                view.isClickable = false
                view.isLongClickable = false
                changed = true
            }
            if (view is ViewGroup) {
                val group = view
                val count = min(group.childCount, 60)
                for (i in 0 until count) {
                    changed = changed or disableClicksDeep(group.getChildAt(i), depth + 1, visited)
                }
            }
            return changed
        }

        private fun noteClickOriginal(view: View) {
            if (!sClickOriginal.containsKey(view)) {
                sClickOriginal[view] = booleanArrayOf(view.isClickable, view.isLongClickable)
            }
        }

        private fun restoreClicks(): Boolean {
            var changed = false
            for (entry in ArrayList(sClickOriginal.entries)) {
                val view = entry.key
                val original = sClickOriginal.remove(view) ?: continue
                if (view.isClickable != original[0]) {
                    view.isClickable = original[0]
                    changed = true
                }
                if (view.isLongClickable != original[1]) {
                    view.isLongClickable = original[1]
                    changed = true
                }
            }
            return changed
        }

        private fun observeCandidates(root: View) {
            val scope = scopeOf(root) ?: return
            collectCandidates(scope, 0, intArrayOf(0))
        }

        private fun collectCandidates(view: View?, depth: Int, visited: IntArray) {
            if (view == null || depth > 8 || visited[0] > 300 || sCandidates.size >= MAX_CANDIDATES) {
                return
            }
            visited[0]++
            if (looksLikeCandidate(view)) {
                val name = idNameOf(view)
                if (name != null) {
                    val text = textOf(view)
                    val previous = sCandidates[name]
                    if (previous == null) {
                        sCandidates[name] = text ?: ""
                        val instance = sInstance
                        instance?.module?.logd(
                            Log.INFO, MainModule.TAG,
                            "帖子详情精简：观察到候选区块 $name" +
                                    (if (text.isNullOrEmpty()) "" else "（$text）") +
                                    "，可加入自定义隐藏列表"

                        )
                    }
                }
            }
            if (view is ViewGroup) {
                val group = view
                val count = min(group.childCount, 40)
                for (i in 0 until count) {
                    collectCandidates(group.getChildAt(i), depth + 1, visited)
                }
            }
        }

        private fun looksLikeCandidate(view: View): Boolean {
            val name = idNameOf(view)
            if (name != null) {
                val lower = name.lowercase(Locale.ROOT)
                for (keyword in CANDIDATE_KEYWORDS) {
                    if (lower.contains(keyword)) {
                        return true
                    }
                }
            }
            val text = textOf(view)
            if (text != null) {
                for (keyword in CANDIDATE_TEXTS) {
                    if (text.contains(keyword)) {
                        return true
                    }
                }
            }
            return false
        }

        private fun idNameOf(view: View): String? {
            return try {
                val id = view.id
                if (id == View.NO_ID) {
                    return null
                }
                val context = view.context ?: return null
                context.resources.getResourceEntryName(id)
            } catch (t: Throwable) {
                null
            }
        }

        private fun textOf(view: View): String? {
            if (view is TextView) {
                val text: CharSequence? = view.text
                return text?.toString()?.trim()
            }
            return null
        }

        private fun findByIdName(view: View?, name: String?): View? {
            if (view == null || name == null) {
                return null
            }
            return findByViewIdName(view, name, 0, intArrayOf(0))
        }

        private fun findByViewIdName(view: View?, name: String, depth: Int, visited: IntArray): View? {
            if (view == null || depth > MAX_WALK_DEPTH || visited[0] > MAX_WALK_NODES) {
                return null
            }
            visited[0]++
            if (name == idNameOf(view)) {
                return view
            }
            if (view is ViewGroup) {
                val group = view
                val count = min(group.childCount, 60)
                for (i in 0 until count) {
                    val hit = findByViewIdName(group.getChildAt(i), name, depth + 1, visited)
                    if (hit != null) {
                        return hit
                    }
                }
            }
            return null
        }

        private fun shouldCheckReshow(root: View): Boolean {
            val now = android.os.SystemClock.uptimeMillis()
            val last = sCheckedAt[root]
            if (last != null && now - last < RESHOW_CHECK_INTERVAL_MS) {
                return false
            }
            sCheckedAt[root] = now
            return true
        }

        private fun anyReShown(root: View): Boolean {
            if (sClean) {
                for (group in HIDE_GROUPS) {
                    for (name in group) {
                        val id = idOf(root, name)
                        if (id == 0) {
                            continue
                        }
                        val view = root.findViewById<View>(id)
                        if (view != null && view.visibility != View.GONE) {
                            return true
                        }
                    }
                }
            }
            if (sTopicNoClick) {
                val id = idOf(root, IDS_TOPIC_ROW)
                if (id != 0) {
                    val topicRow = root.findViewById<View>(id)
                    if (topicRow != null && topicRow.isClickable) {
                        return true
                    }
                }
            }
            if (sTopicHide) {
                val id = idOf(root, IDS_TOPIC_ROW)
                if (id != 0) {
                    val topicRow = root.findViewById<View>(id)
                    if (topicRow != null && topicRow.visibility != View.GONE) {
                        return true
                    }
                }
            }
            return false
        }

        private fun watch(root: View) {
            if (sWatching.put(root, java.lang.Boolean.TRUE) != null) {
                return
            }
            try {
                root.viewTreeObserver.addOnPreDrawListener(
                    object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            try {
                                if (!anyEnabled() || !root.isAttachedToWindow) {
                                    sWatching.remove(root)
                                    sProcessed.remove(root)
                                    val observer = root.viewTreeObserver
                                    if (observer != null && observer.isAlive) {
                                        observer.removeOnPreDrawListener(this)
                                    }
                                    return true
                                }
                                if (shouldCheckReshow(root) && anyReShown(root)) {
                                    sProcessed.remove(root)
                                }
                                apply(root, false)
                            } catch (ignored: Throwable) {
                            }
                            return true
                        }
                    })
            } catch (t: Throwable) {
                sWatching.remove(root)
                val instance = sInstance
                instance?.module?.logd(
                    Log.WARN, MainModule.TAG,
                    "帖子详情精简：挂绘制监听失败: $t"
                )
            }
        }

        private fun logTargetStates(root: View) {
            val instance = sInstance ?: return
            val sb = StringBuilder()
            var visibleCount = 0
            for (i in HIDE_GROUPS.indices) {
                for (name in HIDE_GROUPS[i]) {
                    val id = idOf(root, name)
                    if (id == 0) {
                        continue
                    }
                    val view = root.findViewById<View>(id) ?: continue
                    if (view.visibility != View.GONE) {
                        visibleCount++
                        sb.append('\n').append("  仍可见 ").append(name).append('=')
                            .append(visibilityName(view.visibility))
                            .append(" 路径=").append(ancestorPath(view))
                    }
                }
            }
            val topicId = idOf(root, IDS_TOPIC_ROW)
            if (topicId != 0) {
                val topicRow = root.findViewById<View>(topicId)
                if (topicRow != null && topicRow.visibility != View.GONE) {
                    visibleCount++
                    sb.append('\n').append("  仍可见 ").append(IDS_TOPIC_ROW)
                        .append(" clickable=").append(topicRow.isClickable)
                        .append(" hasListener=").append(topicRow.hasOnClickListeners())
                        .append(" 子View可点击=").append(countClickableDeep(topicRow, 0, intArrayOf(0)))
                        .append(" 路径=").append(ancestorPath(topicRow))
                }
            }
            instance.module.logd(
                Log.INFO, MainModule.TAG,
                "帖子详情精简：目标状态（仍可见 $visibleCount 个）$sb"
            )
        }

        private fun countClickableDeep(view: View?, depth: Int, visited: IntArray): Int {
            if (view == null || depth > 12 || visited[0] > 400) {
                return 0
            }
            visited[0]++
            var n = if (view.isClickable || view.hasOnClickListeners()) 1 else 0
            if (view is ViewGroup) {
                val group = view
                val count = min(group.childCount, 60)
                for (i in 0 until count) {
                    n += countClickableDeep(group.getChildAt(i), depth + 1, visited)
                }
            }
            return n
        }

        private fun visibilityName(visibility: Int): String {
            if (visibility == View.VISIBLE) {
                return "VISIBLE"
            }
            if (visibility == View.INVISIBLE) {
                return "INVISIBLE"
            }
            if (visibility == View.GONE) {
                return "GONE"
            }
            return visibility.toString()
        }

        private fun ancestorPath(view: View): String {
            val sb = StringBuilder()
            var node: View? = view
            var i = 0
            while (i < 12 && node != null) {
                sb.append('/').append(shortClassName(node)).append(':').append(idLabel(node))
                val parent: ViewParent? = node.parent
                node = parent as? View
                i++
            }
            return sb.toString()
        }

        private fun shortClassName(view: View): String {
            val name = view.javaClass.name
            val dot = name.lastIndexOf('.')
            return if (dot < 0) name else name.substring(dot + 1)
        }

        private fun idLabel(view: View): String {
            return try {
                val id = view.id
                if (id == View.NO_ID || id == 0) {
                    return "-"
                }
                val context = view.context
                context?.resources?.getResourceEntryName(id)
                    ?: ("0x" + Integer.toHexString(id))
            } catch (t: Throwable) {
                "?"
            }
        }

        private fun logRootReady(root: View, hit: Int) {
            val instance = sInstance ?: return
            val sb = StringBuilder()
            for (i in HIDE_GROUPS.indices) {
                var found = false
                for (name in HIDE_GROUPS[i]) {
                    val id = idOf(root, name)
                    if (id != 0 && root.findViewById<View>(id) != null) {
                        found = true
                        break
                    }
                }
                if (sb.isNotEmpty()) {
                    sb.append(" / ")
                }
                sb.append(GROUP_LABELS[i]).append('=').append(if (found) "已找到" else "未找到")
            }
            sb.append(" | 锚点命中=")
            if (sDetailHits.isEmpty()) {
                sb.append("无")
            } else {
                sb.append(sDetailHits)
            }
            instance.module.logd(
                Log.INFO, MainModule.TAG,
                "帖子详情精简：详情页命中 $hit 个目标视图 | $sb" +
                        " | Activity=$sActivityName" +
                        " | 屏蔽=" + onOff(sClean) +
                        " 话题防误触=" + onOff(sTopicNoClick) +
                        " 话题隐藏=" + onOff(sTopicHide)

            )
        }

        private fun scopeOf(root: View): View = root

        private fun idOf(root: View, name: String): Int {
            val cached = sIds[name]
            if (cached != null) {
                return cached
            }
            var id = 0
            try {
                val context: Context? = root.context
                val resources: Resources? = context?.resources
                if (resources != null && context != null) {
                    id = resources.getIdentifier(name, "id", context.packageName)
                    if (id == 0) {
                        id = resources.getIdentifier(name, "id", MainModule.TARGET_PKG)
                    }
                }
            } catch (ignored: Throwable) {
            }
            if (id == 0) {
                val attempts = sIdMisses.merge(name, 1, Int::plus) ?: 1
                if (attempts == 1 || attempts >= MAX_ID_RESOLVE_ATTEMPTS) {
                    val instance = sInstance
                    instance?.module?.logd(
                        Log.WARN, MainModule.TAG,
                        "帖子详情精简：第 $attempts 次解析不到资源 $name" +
                                "，跳过（会继续重试）"

                    )
                }
                return 0
            }
            sIds[name] = id
            return id
        }

        private fun parseIds(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) {
                return emptySet()
            }
            val out = LinkedHashSet<String>()
            for (line in raw.split("\n".toRegex())) {
                var name = line.trim()
                if (name.isEmpty() || name.startsWith("#")) {
                    continue
                }
                val space = name.indexOf(' ')
                if (space > 0) {
                    name = name.substring(0, space).trim()
                }
                if (name.isNotEmpty()) {
                    out.add(name)
                }
            }
            return out
        }

        @JvmStatic
        fun diagnostics(): String {
            val sb = StringBuilder()
            sb.append("宿主版本码: ").append(hostVersionCode()).append('\n')
            sb.append("屏蔽总开关: ").append(onOff(sClean))
                .append(" / 话题防误触: ").append(onOff(sTopicNoClick))
                .append(" / 话题隐藏: ").append(onOff(sTopicHide))
                .append(" / 自定义: ").append(sCustomIds.size).append(" 个\n")
            sb.append("精确版本: ").append(
                VersionUtils.getVersionName(App.resolveAppContext())
            ).append("\n")
            sb.append("挂点: onCreateView=").append(sHookCreateView).append(" 处")
                .append(" / onResume=").append(sHookResume).append(" 处")
                .append(" / setVisibility兜底=").append(sHookSetVisibility).append(" 处")
                .append(" / 点击拦截=").append(sHookClickListener).append(" 处")
                .append(" / 文本兜底=").append(sHookSetText).append(" 处\n")
            sb.append("构建标记: ").append(BUILD_MARKER).append('\n')
            sb.append("最近 Activity: ").append(sActivityName).append('\n')
            sb.append("setVisibility 兜底拦截次数: ").append(sSuppressHits.get())
                .append(" / 话题点击拦截次数: ").append(sTopicClickBlocked.get())
                .append(" / 文本兜底命中: ").append(sTextHideHits.get()).append('\n')
            sb.append("当前是否详情页: ").append(sInDetailPage).append('\n')
            sb.append("兜底名单解析: ")
                .append(
                    if (sSuppressedIds == null)
                        "还没解析成功（没拿到宿主 Context？）"
                    else
                        sSuppressedIds!!.size.toString() + " 个 id"
                )
                .append('\n')
            sb.append("详情页锚点解析:\n")
            for (anchor in PAGE_ANCHORS) {
                sb.append("  ").append(anchor).append(" = ").append(describeId(anchor)).append('\n')
            }
            sb.append("资源解析:\n")
            for (name in allTargetNames()) {
                sb.append("  ").append(name).append(" = ").append(describeId(name)).append('\n')
            }
            sb.append("最近一次生效: ").append(sLastApply).append('\n')
            sb.append("屏幕上可见的「搜」类节点:").append(sVisibleSearchDump).append('\n')
            sb.append("观察到的候选区块（可加入自定义隐藏列表）:\n")
            synchronized(sCandidates) {
                if (sCandidates.isEmpty()) {
                    sb.append("  —\n")
                } else {
                    for (entry in sCandidates.entries) {
                        sb.append("  ").append(entry.key)
                        if (entry.value.isNotEmpty()) {
                            sb.append("（").append(entry.value).append("）")
                        }
                        sb.append('\n')
                    }
                }
            }
            return sb.toString()
        }

        private fun describeId(name: String): String {
            val id = sIds[name]
            if (id != null) {
                return "OK(id=0x" + Integer.toHexString(id) + ", 详情页命中=" +
                        (if (sDetailHits.contains(name)) "是" else "否") + ")"

            }
            val misses = sIdMisses[name]
            if (misses == null) {
                return "未查"
            }
            return "未解析（已试 $misses 次）"
        }

        private fun allTargetNames(): List<String> {
            val out = ArrayList<String>()
            for (group in HIDE_GROUPS) {
                for (name in group) {
                    out.add(name)
                }
            }
            out.add(IDS_INSERT_CONTAINER)
            out.add(IDS_TOPIC_ROW)
            out.addAll(sCustomIds)
            return out
        }

        private fun hostVersionCode(): Long {
            return try {
                val context = App.resolveAppContext() ?: return -1L
                val info = context.packageManager
                    .getPackageInfo(MainModule.TARGET_PKG, 0)
                if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
                else info.versionCode.toLong()
            } catch (t: Throwable) {
                -1L
            }
        }

        private fun nowText(): String =
            SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
    }
}
