package com.better.heybox.hooks

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.better.heybox.App
import com.better.heybox.Checkpoint
import com.better.heybox.HeyboxTargets
import com.better.heybox.MainModule
import com.better.heybox.yuki.YukiChain
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class PostFilterHook(module: MainModule) {

    private val module: MainModule

    private val boundPostKeys = WeakHashMap<View, String>()

    private val keywordLock = Any()

    private var keywordRaw: String? = null
    private var keywordMatchers: List<Any?>? = null

    private val aiCallback: AIClickbaitChecker.VerdictCallback

    @Volatile
    private var sFlowControllerRef: WeakReference<Any?>? = null

    private val sMain = Handler(Looper.getMainLooper())

    private val listAiCallback = AIClickbaitChecker.VerdictCallback { verdicts ->
        for (e in verdicts.entries) {
            if (e.value) {
                requestFlowRebuild()
                break
            }
        }
    }

    init {
        this.module = module
        sInstance = this
        this.aiCallback = AIClickbaitChecker.VerdictCallback { verdicts ->
            for (e in verdicts.entries) {
                if (!e.value) {
                    continue
                }
                val bound = findBoundView(e.key)
                if (bound != null && e.key.equals(boundPostKeys[bound])
                    && bound.isAttachedToWindow()
                ) {
                    module.logd(Log.INFO, MainModule.TAG, "AI 判定标题党，延迟隐藏")
                    FeedItemHider.hide(bound)
                }
            }
        }
    }

    private fun requestFlowRebuild() {
        val ref = sFlowControllerRef
        val controller = if (ref != null) ref.get() else null
        if (controller == null) {
            return
        }
        module.logd(Log.INFO, MainModule.TAG, "AI 判定标题党，触发首页流重建")
        sMain.post {
            try {
                val data = controller.javaClass.getMethod("getCurrentData").invoke(controller)
                controller.javaClass.getMethod("setData", Any::class.java).invoke(controller, data)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "首页流重建失败: " + t)
            }
        }
    }

    fun install(cl: ClassLoader) {
        hookWaterfallCard(cl)
        hookWaterfallRowContainer(cl)
        hookNewsLinkCard(cl)
        hookConfigStyleCard(cl)
        hookNewsListAdapter(cl)
        hookBbsLinkListAdapter(cl)
        hookBbsLinkListGetter(cl)
        hookFeedsModelDeserializer(cl)
        hookRecommendFlowController(cl)
    }


    private fun hookBbsLinkListGetter(cl: ClassLoader) {
        try {
            HeyboxTargets.install(PromoteDetector.TARGET_BBS_LINKS_GETTER) { method ->
                module.hook(method).intercept { chain ->
                    val raw = chain.proceed()
                    if (raw !is List<*> || !hasSyncRule() || isExcludedListCaller()) {
                        return@intercept raw
                    }
                    val filtered = filterBbsLinks(raw as List<*>)
                    return@intercept filtered ?: raw
                }
                module.logd(
                    Log.INFO, MainModule.TAG, "✔ 帖子列表数据层 Hook 已安装: "
                            + method.declaringClass.name + "#" + method.name
                )
            }
            Checkpoint.mark("发帖过滤列表数据层安装: ok")
        } catch (t: Throwable) {
            Checkpoint.mark("发帖过滤列表数据层安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 发帖过滤列表数据层 Hook 失败: " + t)
        }
    }

    private fun hookBbsLinkListAdapter(cl: ClassLoader) {
        try {
            val installed = intArrayOf(0)
            HeyboxTargets.installGroup(PromoteDetector.TARGET_BBS_LIST_BIND) { method ->
                module.hook(method).intercept(this::onBbsListBind)
                installed[0]++
                module.logd(
                    Log.INFO, MainModule.TAG, "✔ 帖子列表 Hook 已安装: "
                            + method.declaringClass.name + "#" + method.name
                )
            }
            Checkpoint.mark("发帖过滤帖子列表安装: %d 处（异步补挂见 report）", installed[0])
        } catch (t: Throwable) {
            Checkpoint.mark("发帖过滤帖子列表安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 发帖过滤帖子列表 Hook 失败: " + t)
        }
    }

    @Throws(Throwable::class)
    private fun onBbsListBind(chain: YukiChain): Any? {
        val itemView = FeedItemHider.getItemView(chain.getArg(0))
        if (itemView != null) {
            FeedItemHider.restore(itemView)
        }
        val data = chain.getArg(1)
        try {
            if (isPostLike(data) && !isExcludedPageContext(itemView)) {
                if (applySyncFilters(data, "社区列表")) {
                    FeedItemHider.hide(itemView)
                    return null
                }
                aiCheck(data, postCacheKey(data), itemView)
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "社区列表过滤异常，放行: " + t)
        }
        return chain.proceed()
    }

    private fun filterBbsLinks(raw: List<*>): List<*>? {
        val keep: MutableList<Any?> = ArrayList<Any?>(raw.size)
        var blocked = 0
        for (item in raw) {
            if (!isPostLike(item)) {
                keep.add(item)
                continue
            }
            val reason = blockReason(item, false)
            if (reason == null) {
                keep.add(item)
                continue
            }
            blocked++
            logBlocked("社区列表(数据层)", item, reason)
        }
        if (blocked == 0) {
            return null
        }
        module.logd(Log.INFO, MainModule.TAG, "屏蔽内容[社区列表] 本页共屏蔽 " + blocked + " 条")
        return keep
    }

    private fun isPostLike(item: Any?): Boolean {
        return item != null && safeInvoke(item, "getUser") != null
    }

    private fun isExcludedListCaller(): Boolean {
        try {
            for (frame in Thread.currentThread().stackTrace) {
                if (isExcludedPageName(frame.className)) {
                    return true
                }
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun isExcludedPageContext(itemView: View?): Boolean {
        if (itemView == null) {
            return false
        }
        try {
            var context: Context? = itemView.context
            var depth = 0
            while (depth < 8 && context != null) {
                if (isExcludedPageName(context.javaClass.name)) {
                    return true
                }
                if (!(context is ContextWrapper)) {
                    break
                }
                val base = (context as ContextWrapper).baseContext
                if (base == null || base == context) {
                    break
                }
                context = base
                depth++
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun isExcludedPageName(name: String?): Boolean {
        return name != null && (name.startsWith("com.max.xiaoheihe.module.favour.")
                || name.startsWith("com.max.xiaoheihe.module.bbs.DraftListActivity")
                || name.startsWith("com.max.xiaoheihe.module.account.specificsearch."))
    }

    private fun hasSyncRule(): Boolean {
        if (module.isEnabled(App.KEY_PROMOTE_AD, true)
            || module.isEnabled(App.KEY_BLOCK_VIDEO_POST, false)
            || module.isEnabled(App.KEY_POST_NO_LEVEL, false)
        ) {
            return true
        }
        return parseIntSafe(module.getString(App.KEY_POST_MIN_LEVEL, "0")) > 0
                || hasEngagementRule()
                || !keywordMatchers().isEmpty()
    }

    private fun hookRecommendFlowController(cl: ClassLoader) {
        try {
            val c = Class.forName(
                "com.max.feature.feeds.view.RecommendFlowRVController", false, cl
            )
            val listCls = Class.forName("java.util.List", false, cl)
            var m: Method? = null
            for (mm in c.declaredMethods) {
                if ("buildModels".equals(mm.name)
                    && mm.parameterCount == 1 && mm.parameterTypes[0] == listCls
                ) {
                    m = mm
                    break
                }
            }
            if (m == null) {
                Checkpoint.mark("发帖过滤列表层安装: 未找到 buildModels")
                return
            }
            module.hook(m).intercept { chain ->
                val ctrl = chain.getThisObject()
                sFlowControllerRef = if (ctrl == null) null else WeakReference(ctrl)
                var replacement: List<*>? = null
                try {
                    replacement = filterFlowList(chain.getArg(0))
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "列表过滤异常，放行: " + t)
                }
                if (replacement != null) {
                    chain.proceed(arrayOf<Any?>(replacement))
                } else {
                    chain.proceed()
                }
            }
            Checkpoint.mark("发帖过滤列表层 Hook 安装: ok")
        } catch (t: Throwable) {
            Checkpoint.mark("发帖过滤列表层 Hook 安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 发帖过滤列表层 Hook 失败: " + t)
        }
    }

    private fun filterFlowList(listObj: Any?): List<*>? {
        if (listObj !is List<*>) {
            return null
        }
        if (!hasSyncRule()) {
            clearBlockedIndex()
            return null
        }
        for (item in listObj as List<*>) {
            if (item == null) {
                continue
            }
            try {
                val reason = blockReason(item, true)
                if (reason != null) {
                    logBlocked("首页流列表", item, reason)
                    probe("登记", "reason=" + reason + " 键=" + cut(PromoteDetector.title(item)))
                    markBlocked(item)
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "首页流列表判定异常，忽略该条: " + t)
            }
        }
        return null
    }

    private val blockedTitles = ConcurrentHashMap<String, Long>()

    private fun markBlocked(item: Any?) {
        try {
            if (putBlockedKey(PromoteDetector.title(item))) {
                return
            }
            val link = safeInvoke(item, "getLinkContent")
            if (link != null) {
                putBlockedKey(safeGet(link, "getDescription"))
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun putBlockedKey(title: String?): Boolean {
        if (title == null) {
            return false
        }
        val key = title.trim()
        if (key.length < 4) {
            return false
        }
        if (blockedTitles.size >= BLOCK_INDEX_MAX) {
            purgeBlockedIndex()
        }
        blockedTitles[key] = System.currentTimeMillis()
        return true
    }

    private fun isBlockedTitle(title: String?): Boolean {
        if (title == null) {
            return false
        }
        val key = title.trim()
        if (key.length < 4) {
            return false
        }
        val at = blockedTitles[key]
        if (at == null) {
            return false
        }
        if (System.currentTimeMillis() - at > BLOCK_TTL_MS) {
            blockedTitles.remove(key)
            return false
        }
        return true
    }

    private fun purgeBlockedIndex() {
        val now = System.currentTimeMillis()
        val it = blockedTitles.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > BLOCK_TTL_MS) {
                it.remove()
            }
        }
        if (blockedTitles.size >= BLOCK_INDEX_MAX) {
            blockedTitles.clear()
        }
    }

    private fun clearBlockedIndex() {
        if (!blockedTitles.isEmpty()) {
            blockedTitles.clear()
        }
    }

    private val probeCount = AtomicInteger()

    private fun probe(where: String, detail: String) {
        try {
            if (probeCount.get() >= PROBE_LIMIT || !module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                return
            }
            val n = probeCount.incrementAndGet()
            if (n <= PROBE_LIMIT) {
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "[视图层探针 " + n + "] " + where + " " + detail
                )
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun cut(s: String?): String {
        if (s == null) {
            return "null"
        }
        return if (s.length <= 40) s else s.substring(0, 40)
    }

    fun onDeserialized(result: Any?): Any? {
        try {
            if (applySyncFilters(result)) {
                return emptyFeedObj(result)
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "数据层过滤异常，放行: " + t)
        }
        return null
    }

    private fun emptyFeedObj(sample: Any?): Any? {
        try {
            val cl = if (sample != null) sample.javaClass.classLoader else javaClass.classLoader
            val base = Class.forName("com.max.xiaoheihe.bean.news.FeedsContentBaseObj", false, cl)
            val empty = base.getDeclaredConstructor().newInstance()
            base.getMethod("setContent_type", String::class.java).invoke(empty, "0")
            try {
                base.getMethod("setShowDivider", Boolean::class.javaPrimitiveType).invoke(empty, false)
            } catch (ignored: Throwable) {
            }
            return empty
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "创建空 FeedsContentBaseObj 失败: " + t)
            return null
        }
    }

    private fun hookFeedsModelDeserializer(cl: ClassLoader) {
        try {
            val d = Class.forName(
                "com.max.data.deserializer.FeedsFlowItemModelDeserializer", false, cl
            )
            val jsonElement = Class.forName("com.google.gson.JsonElement", false, cl)
            val type = Class.forName("java.lang.reflect.Type", false, cl)
            val ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl)
            var installed = 0
            for (name in arrayOf("a", "deserialize")) {
                try {
                    val m = d.getDeclaredMethod(name, jsonElement, type, ctx)
                    module.hook(m).intercept { chain -> filterFlowModel(chain) }
                    installed++
                } catch (ignored: NoSuchMethodException) {
                }
            }
            Checkpoint.mark("发帖过滤流模型 Hook 安装: %d 处", installed)
        } catch (t: Throwable) {
            Checkpoint.mark("发帖过滤流模型 Hook 安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 流模型数据层 Hook 失败: " + t)
        }
    }

    @Throws(Throwable::class)
    private fun filterFlowModel(chain: YukiChain): Any? {
        val result = chain.proceed()
        try {
            if (result == null) {
                return result
            }
            if (module.isEnabled(App.KEY_FLOW_DIAGNOSE, false)) {
                module.logd(Log.INFO, MainModule.TAG, "首页流条目 " + PromoteDetector.describe(result))
            }
            if (!hasSyncRule()) {
                clearBlockedIndex()
                return result
            }
            val reason = blockReason(result, true)
            if (reason != null) {
                logBlocked("首页流", result, reason)
                markBlocked(result)
                return result
            }
            if (!isPostFlowModel(result)) {
                return result
            }
            val link = safeInvoke(result, "getLinkContent")
            val title = if (link == null) "" else safeGet(link, "getTitle")
            if (module.isEnabled(App.KEY_POST_AI_ENABLED, false) && !title.isEmpty()) {
                val verdict: Boolean? = AIClickbaitChecker.getCached(title)
                if (verdict != null && verdict) {
                    module.logd(Log.INFO, MainModule.TAG, "AI 判定标题党（缓存）: " + abbreviate(title))
                    markBlocked(result)
                } else if (verdict == null) {
                    AIClickbaitChecker.requestVerdicts(module, title, title, listAiCallback)
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "流模型过滤异常，放行: " + t)
        }
        return result
    }

    private val postModelCache = ConcurrentHashMap<Class<*>, Boolean>()

    private val hasVideoCache = ConcurrentHashMap<Class<*>, Method>()

    private val hasVideoMiss: MutableSet<Class<*>> =
        Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    private fun isPostFlowModel(result: Any?): Boolean {
        val c = result!!.javaClass
        val cached = postModelCache[c]
        if (cached != null) {
            return cached
        }
        var isPost = false
        var walk: Class<*>? = c
        while (walk != null && walk != Any::class.java) {
            if ("com.max.data.model.feeds.LinkFeedsFlowItemModel".equals(walk.name)) {
                isPost = true
                break
            }
            walk = walk.superclass
        }
        postModelCache[c] = isPost
        return isPost
    }


    private fun videoBlocked(item: Any?): Boolean {
        if (item == null || !module.isEnabled(App.KEY_BLOCK_VIDEO_POST, false)) {
            return false
        }
        return if (isPostFlowModel(item)) isVideoModel(item) else isVideoLegacy(item)
    }

    private fun isVideoModel(model: Any?): Boolean {
        try {
            val m = hasVideoMethod(model!!.javaClass)
            if (m != null) {
                val v = m.invoke(model)
                return v is Boolean && v
            }
        } catch (ignored: Throwable) {
        }
        val style = safeInvoke(model, "getLinkStyle")
        return style != null && "VIDEO_LINK".equals(stringify(style))
    }

    private fun isVideoLegacy(item: Any?): Boolean {
        if (isTruthy(safeGet(item, "getHas_video"))) {
            return true
        }
        if (!safeGet(item, "getVideo_url").isEmpty()) {
            return true
        }
        return safeInvoke(item, "getVideo_info") != null
    }

    private fun hasVideoMethod(c: Class<*>): Method? {
        val cached = hasVideoCache[c]
        if (cached != null) {
            return cached
        }
        if (hasVideoMiss.contains(c)) {
            return null
        }
        var found: Method? = null
        var walk: Class<*>? = c
        while (walk != null && walk != Any::class.java) {
            try {
                val m = walk.getDeclaredMethod("hasVideo")
                if (m.parameterCount == 0
                    && (m.returnType == Boolean::class.javaPrimitiveType
                    || m.returnType == java.lang.Boolean::class.java)
                ) {
                    m.isAccessible = true
                    found = m
                    break
                }
            } catch (ignored: Throwable) {
            }
            walk = walk.superclass
        }
        if (found == null) {
            hasVideoMiss.add(c)
        } else {
            hasVideoCache[c] = found
        }
        return found
    }

    fun onRenderBind(bbsLink: Any?, viewHolder: Any?): Boolean {
        try {
            if (applySyncFilters(bbsLink)) {
                FeedItemHider.hide(FeedItemHider.getItemView(viewHolder))
                return true
            }
            val view = FeedItemHider.getItemView(viewHolder)
            FeedItemHider.restore(view)
            aiCheck(bbsLink, postCacheKey(bbsLink), view)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "发帖过滤判断异常，放行: " + t)
        }
        return false
    }

    private fun hookWaterfallCard(cl: ClassLoader) {
        try {
            val model = Class.forName(
                "com.max.data.model.feeds.WaterfallLinkFeedsFlowItemModel", false, cl
            )
            var installed = 0
            for (name in arrayOf(
                    "com.max.feature.feeds.view.itemview.WaterfallFeedsFlowItemViewV2",
                    "com.max.feature.feeds.view.itemview.WaterfallFeedsFlowItemView")) {
                try {
                    val card = Class.forName(name, false, cl)
                    val setData = card.getDeclaredMethod("setData", model)
                    module.hook(setData).intercept { chain -> onCardBind(chain) }
                    installed++
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "瀑布流卡片类不可用: " + name)
                }
            }
            Checkpoint.mark("发帖过滤瀑布流卡片安装: %d 处", installed)
        } catch (t: Throwable) {
            Checkpoint.mark("发帖过滤瀑布流卡片安装异常: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 发帖过滤首页卡片 Hook 失败: " + t)
        }
    }

    @Throws(Throwable::class)
    private fun onCardBind(chain: YukiChain): Any? {
        val cardView: View? =
            if (chain.getThisObject() is View) chain.getThisObject() as View else null
        if (cardView != null) {
            FeedItemHider.restore(cardView)
        }
        val result = chain.proceed()
        try {
            if (cardView == null) {
                return result
            }
            val model = chain.getArg(0)
            if (model == null) {
                return result
            }
            val link = safeInvoke(model, "getLinkContent")
            val title = if (link == null) "" else safeGet(link, "getTitle")
            val reason = blockReason(model, false)
            probe(
                "瀑布卡", "model=" + model.javaClass.simpleName + " 判定=" + reason
                        + " 索引=" + blockedTitles.size + " title=" + cut(title)
            )
            if (reason != null) {
                logBlocked("首页卡片", model, reason)
                markBlocked(model)
                FeedItemHider.hide(cardView)
                return result
            }
            if (isBlockedTitle(title)) {
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "屏蔽内容[首页卡片] 标题命中屏蔽索引: " + abbreviate(title)
                )
                FeedItemHider.hide(cardView)
                return result
            }
            if (!module.isEnabled(App.KEY_POST_AI_ENABLED, false) || title.isEmpty()) {
                return result
            }
            val verdict: Boolean? = AIClickbaitChecker.getCached(title)
            if (verdict != null) {
                if (verdict) {
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "AI 判定标题党（缓存）: " + abbreviate(title)
                    )
                    FeedItemHider.hide(cardView)
                }
                return result
            }
            boundPostKeys[cardView] = title
            AIClickbaitChecker.requestVerdicts(module, title, title, aiCallback)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "卡片过滤异常，放行: " + t)
        }
        return result
    }

    private var pairCardInterface: Class<*>? = null

    private fun hookWaterfallRowContainer(cl: ClassLoader) {
        try {
            val container = Class.forName(
                "com.max.feature.feeds.view.itemview.WaterfallPairGroupContainer", false, cl
            )
            try {
                pairCardInterface = Class.forName(
                    "com.max.feature.feeds.view.itemview.t0", false, cl
                )
            } catch (ignored: Throwable) {
                pairCardInterface = null
            }
            val onMeasure = container.getDeclaredMethod(
                "onMeasure", Integer.TYPE, Integer.TYPE
            )
            module.hook(onMeasure).intercept { chain ->
                try {
                    fixWaterfallRow(chain.getThisObject())
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "首页成对行排版修正异常: " + t)
                }
                chain.proceed()
            }
            Checkpoint.mark("发帖过滤首页成对行安装: ok")
        } catch (t: Throwable) {
            Checkpoint.mark("发帖过滤首页成对行安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 发帖过滤首页成对行 Hook 失败: " + t)
        }
    }

    private fun fixWaterfallRow(obj: Any?) {
        if (obj !is ViewGroup) {
            return
        }
        if (module.isEnabled(App.KEY_SINGLE_COLUMN_FEED, false)) {
            return
        }
        val row = obj as ViewGroup
        val childCount = row.childCount
        if (childCount == 0) {
            return
        }
        val rowItem = FeedItemHider.topLevel(row)
        var visibleCards = 0
        var anyHiddenCard = false
        for (i in 0 until childCount) {
            val child = row.getChildAt(i)
            if (!isPairCard(child)) {
                continue
            }
            if (child.visibility == View.GONE) {
                anyHiddenCard = true
            } else {
                visibleCards++
            }
        }
        if (!anyHiddenCard) {
            FeedItemHider.restore(rowItem)
            if (row is LinearLayout && row.orientation != LinearLayout.HORIZONTAL) {
                row.orientation = LinearLayout.HORIZONTAL
                for (i in 0 until childCount) {
                    resetPairChildLayout(row.getChildAt(i))
                }
            }
            return
        }
        if (visibleCards == 0) {
            FeedItemHider.hide(rowItem)
            return
        }
        FeedItemHider.restore(rowItem)
        if (row is LinearLayout) {
            row.orientation = LinearLayout.VERTICAL
        }
        for (i in 0 until childCount) {
            val child = row.getChildAt(i)
            if (isPairCard(child) && child.visibility != View.GONE) {
                expandPairChild(child)
            }
        }
    }

    private fun isPairCard(child: View): Boolean {
        if (child.javaClass.name.endsWith("WaterfallPairEmptyItemView")) {
            return false
        }
        val iface = pairCardInterface
        return iface == null || iface.isInstance(child)
    }

    private fun expandPairChild(child: View) {
        val lp = child.layoutParams
        if (lp is LinearLayout.LayoutParams) {
            val p = lp
            if (p.width == ViewGroup.LayoutParams.MATCH_PARENT && p.weight == 0f) {
                return
            }
        }
        child.setLayoutParams(
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun resetPairChildLayout(child: View) {
        val lp = child.layoutParams
        if (lp is LinearLayout.LayoutParams) {
            val p = lp
            if (p.width == 0 && p.weight == 1f) {
                return
            }
        }
        child.setLayoutParams(
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun hookNewsLinkCard(cl: ClassLoader) {
        try {
            val card = Class.forName(
                "com.max.feature.feeds.view.itemview.NewsLinkFeedsFlowItemView", false, cl
            )
            val setTitle = card.getDeclaredMethod("setTitle", String::class.java)
            module.hook(setTitle).intercept(this::onNewsLinkTitle)
            Checkpoint.mark("发帖过滤首页全宽卡安装: ok")
        } catch (t: Throwable) {
            Checkpoint.mark("发帖过滤首页全宽卡安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 发帖过滤首页全宽卡 Hook 失败: " + t)
        }
    }

    @Throws(Throwable::class)
    private fun onNewsLinkTitle(chain: YukiChain): Any? {
        val itemView: View? =
            if (chain.getThisObject() is View)
                FeedItemHider.topLevel(chain.getThisObject() as View) else null
        if (itemView != null) {
            FeedItemHider.restore(itemView)
        }
        val result = chain.proceed()
        try {
            val raw = chain.getArg(0)
            val title = if (raw is String) raw as String else null
            probe(
                "全宽卡", "view=" + (if (itemView == null) "null" else itemView.javaClass.simpleName)
                        + " 索引=" + blockedTitles.size + " 命中=" + isBlockedTitle(title)
                        + " title=" + cut(title)
            )
            if (itemView == null || title == null || title.trim().length < 4) {
                return result
            }
            if (isBlockedTitle(title)) {
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "屏蔽内容[首页全宽卡] 标题命中屏蔽索引: " + abbreviate(title)
                )
                FeedItemHider.hide(itemView)
                return result
            }
            if (module.isEnabled(App.KEY_POST_AI_ENABLED, false)
                && AIClickbaitChecker.getCached(title) == null
            ) {
                boundPostKeys[itemView] = title
                AIClickbaitChecker.requestVerdicts(module, title, title, aiCallback)
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "全宽卡过滤异常，放行: " + t)
        }
        return result
    }

    private fun hookConfigStyleCard(cl: ClassLoader) {
        val modelName = "com.max.data.model.feeds.ConfigStyleLinkFeedsFlowItemModel"
        var installed = 0
        var classes = 0
        for (name in CONFIG_CARD_VIEWS) {
            try {
                val card = Class.forName(name, false, cl)
                val before = installed
                for (m in card.declaredMethods) {
                    if (!m.name.startsWith("set") || m.parameterCount != 1) {
                        continue
                    }
                    if (!modelName.equals(m.parameterTypes[0].name)) {
                        continue
                    }
                    module.hook(m).intercept(this::onConfigStyleBind)
                    installed++
                }
                if (installed > before) {
                    classes++
                }
            } catch (t: Throwable) {
                module.logd(Log.INFO, MainModule.TAG, "配置样式卡类不可用: " + name)
            }
        }
        Checkpoint.mark("发帖过滤首页配置样式卡安装: %d 处 / %d 类", installed, classes)
    }

    @Throws(Throwable::class)
    private fun onConfigStyleBind(chain: YukiChain): Any? {
        val itemView: View? =
            if (chain.getThisObject() is View)
                FeedItemHider.topLevel(chain.getThisObject() as View) else null
        if (itemView != null) {
            FeedItemHider.restore(itemView)
        }
        val result = chain.proceed()
        try {
            val model = chain.getArg(0)
            if (itemView == null || model == null) {
                return result
            }
            val reason = blockReason(model, true)
            probe(
                "配置卡", "model=" + model.javaClass.simpleName + " 判定=" + reason
                        + " 行=" + itemView.javaClass.simpleName
                        + " title=" + cut(PromoteDetector.title(model))
            )
            if (reason != null) {
                logBlocked("首页配置卡", model, reason)
                markBlocked(model)
                FeedItemHider.hide(itemView)
                return result
            }
            if (isBlockedTitle(PromoteDetector.title(model))) {
                module.logd(Log.INFO, MainModule.TAG, "屏蔽内容[首页配置卡] 标题命中屏蔽索引")
                FeedItemHider.hide(itemView)
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "配置样式卡过滤异常，放行: " + t)
        }
        return result
    }

    private fun hookNewsListAdapter(cl: ClassLoader) {
        try {
            HeyboxTargets.install(PromoteDetector.TARGET_FEEDS_BIND) { method ->
                module.hook(method).intercept { chain ->
                    val itemView = FeedItemHider.getItemView(chain.getArg(0))
                    if (itemView != null) {
                        FeedItemHider.restore(itemView)
                    }
                    val data = chain.getArg(1)
                    try {
                        if (applySyncFilters(data)) {
                            FeedItemHider.hide(itemView)
                            return@intercept null
                        }
                        aiCheck(data, postCacheKey(data), itemView)
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "列表过滤异常，放行: " + t)
                    }
                    chain.proceed()
                }
            }
            Checkpoint.mark("发帖过滤列表 Hook 安装: ok")
        } catch (t: Throwable) {
            Checkpoint.mark("发帖过滤列表 Hook 安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 发帖过滤列表 Hook 失败: " + t)
        }
    }


    private fun applySyncFilters(item: Any?): Boolean {
        return applySyncFilters(item, "列表绑定")
    }

    private fun applySyncFilters(item: Any?, where: String): Boolean {
        val reason = blockReason(item, false)
        if (reason == null) {
            return false
        }
        logBlocked(where, item, reason)
        return true
    }

    private fun aiCheck(bbsLink: Any?, cacheKey: String?, boundView: View?) {
        if (!module.isEnabled(App.KEY_POST_AI_ENABLED, false) || cacheKey == null) {
            return
        }
        val baseUrl = module.getString(App.KEY_AI_BASE_URL, "")!!.trim()
        val model = module.getString(App.KEY_AI_MODEL, "")!!.trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            return
        }
        val verdict: Boolean? = AIClickbaitChecker.getCached(cacheKey)
        if (verdict != null) {
            if (verdict && boundView != null) {
                module.logd(
                    Log.INFO, MainModule.TAG, "AI 判定标题党（缓存）: " + abbreviate(cacheKey)
                )
                FeedItemHider.hide(boundView)
            }
            return
        }
        val title = safeTitle(bbsLink)
        if (title.isEmpty() || boundView == null) {
            return
        }
        boundPostKeys[boundView] = cacheKey
        AIClickbaitChecker.requestVerdicts(module, cacheKey, title, aiCallback)
    }

    private fun findBoundView(cacheKey: String): View? {
        for (e in boundPostKeys.entries) {
            if (cacheKey == e.value) {
                return e.key
            }
        }
        return null
    }

    private fun levelBlocked(item: Any?): Boolean {
        val min = parseIntSafe(module.getString(App.KEY_POST_MIN_LEVEL, "0"))
        if (min <= 0) {
            return false
        }
        val level = readUserLevel(item)
        if (level == null) {
            if (!module.isEnabled(App.KEY_POST_NO_LEVEL, false)) {
                return false
            }
            if (isPostFlowModel(item)) {
                return true
            }
            return safeInvoke(item, "getUser") != null
        }
        return level < min
    }

    private fun readUserLevel(item: Any?): Int? {
        try {
            val user = safeInvoke(item, "getUser")
            if (user == null) {
                return null
            }
            val info = safeInvoke(user, "getLevel_info")
            if (info == null) {
                return null
            }
            val lv = safeInvoke(info, "getLevel")
            if (lv == null) {
                return null
            }
            return Integer.parseInt(stringify(lv).trim())
        } catch (t: Throwable) {
            return null
        }
    }


    private fun keywordHit(item: Any?): String? {
        if (item == null) {
            return null
        }
        val matchers = keywordMatchers()
        if (matchers.isEmpty()) {
            return null
        }
        val hit = matchAny(matchers, safeTitle(item), safeText(item), safeGet(item, "getDescription"))
        if (hit != null) {
            return hit
        }
        val link = safeInvoke(item, "getLinkContent")
        if (link != null) {
            return matchAny(matchers, safeGet(link, "getTitle"), safeGet(link, "getDescription"))
        }
        return null
    }

    private fun keywordHitText(title: String?, text: String?): String? {
        val matchers = keywordMatchers()
        if (matchers.isEmpty()) {
            return null
        }
        return matchAny(matchers, title, text)
    }

    private fun matchAny(matchers: List<Any?>, vararg fields: String?): String? {
        var hasText = false
        val lower = arrayOfNulls<String>(fields.size)
        for (i in fields.indices) {
            val value = fields[i]
            val lowered = value?.lowercase(Locale.getDefault()) ?: ""
            lower[i] = lowered
            if (!lowered.isEmpty()) {
                hasText = true
            }
        }
        if (!hasText) {
            return null
        }
        for (m in matchers) {
            if (m is Pattern) {
                val pattern = m
                for (value in lower) {
                    if (pattern.matcher(value!!).find()) {
                        return "regex:" + pattern.pattern()
                    }
                }
            } else {
                val kw = m as String
                for (value in lower) {
                    if (value!!.contains(kw)) {
                        return kw
                    }
                }
            }
        }
        return null
    }

    private fun promoteBlocked(item: Any?): Boolean {
        return module.isEnabled(App.KEY_PROMOTE_AD, true) && PromoteDetector.isPromote(item)
    }

    private fun blockReason(item: Any?, postOnly: Boolean): String? {
        if (item == null) {
            return null
        }
        if (promoteBlocked(item)) {
            val reason = PromoteDetector.matchReason(item)
            return reason ?: "推广内容"
        }
        if (postOnly && !isPostFlowModel(item)) {
            return null
        }
        if (videoBlocked(item)) {
            return videoReason(item)
        }
        if (levelBlocked(item)) {
            return levelReason(item)
        }
        val engagement = engagementReason(item)
        if (engagement != null) {
            return engagement
        }
        val keyword = keywordHit(item)
        return if (keyword == null) null else "命中关键词 " + keyword
    }

    private fun logBlocked(where: String, item: Any?, reason: String) {
        val detail = if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            " | " + describeItem(item)
        } else {
            ""
        }
        module.logd(
            Log.INFO, MainModule.TAG,
            "屏蔽内容[" + where + "] 原因=" + reason + detail
        )
    }

    private fun describeItem(item: Any?): String {
        val sb = StringBuilder()
        sb.append("标题=").append(PromoteDetector.abbreviate(PromoteDetector.title(item)))
        val author: String? = PromoteDetector.author(item)
        sb.append(", 作者=").append(if (author == null) "?" else author)
        val level: String? = PromoteDetector.level(item)
        if (level != null) {
            sb.append(", 等级=").append(level)
        }
        val like: Int? = PromoteDetector.likeCount(item)
        sb.append(", 赞=").append(if (like == null) "-" else like)
        val comment: Int? = PromoteDetector.commentCount(item)
        sb.append(", 评=").append(if (comment == null) "-" else comment)
        val favour: Int? = PromoteDetector.favourCount(item)
        sb.append(", 藏=").append(if (favour == null) "-" else favour)
        sb.append(", ct=").append(PromoteDetector.contentType(item))
        return sb.toString()
    }

    private fun videoReason(item: Any?): String? {
        if (isPostFlowModel(item)) {
            val hasVideo = hasVideoMethod(item!!.javaClass)
            if (hasVideo != null) {
                try {
                    val value = hasVideo.invoke(item)
                    if (value is Boolean && value) {
                        return "视频帖（hasVideo()=true）"
                    }
                } catch (ignored: Throwable) {
                }
            }
            val style = safeInvoke(item, "getLinkStyle")
            return "视频帖（link_style=" + (if (style == null) "?" else stringify(style)) + "）"
        }
        if (isTruthy(safeGet(item, "getHas_video"))) {
            return "视频帖（has_video=1）"
        }
        if (!safeGet(item, "getVideo_url").isEmpty()) {
            return "视频帖（video_url 非空）"
        }
        return "视频帖（video_info 非空）"
    }

    private fun levelReason(item: Any?): String {
        val min = parseIntSafe(module.getString(App.KEY_POST_MIN_LEVEL, "0"))
        val level = readUserLevel(item)
        if (level == null) {
            return "无等级数据 < 阈值 Lv" + min
        }
        return "等级 Lv" + level + " < 阈值 Lv" + min
    }

    @Volatile
    private var thresholdsRaw: String? = null

    @Volatile
    private var minLike = 0

    @Volatile
    private var minComment = 0

    @Volatile
    private var minFavour = 0

    private fun refreshThresholds() {
        val raw = module.getString(App.KEY_POST_MIN_LIKE, "0") + "|" +
                module.getString(App.KEY_POST_MIN_COMMENT, "0") + "|" +
                module.getString(App.KEY_POST_MIN_FAVOUR, "0")
        if (raw.equals(thresholdsRaw)) {
            return
        }
        val parts = raw.split(Regex("\\|"), -1)
        minLike = parseIntSafe(if (parts.size > 0) parts[0] else "0")
        minComment = parseIntSafe(if (parts.size > 1) parts[1] else "0")
        minFavour = parseIntSafe(if (parts.size > 2) parts[2] else "0")
        thresholdsRaw = raw
    }

    private fun hasEngagementRule(): Boolean {
        refreshThresholds()
        return minLike > 0 || minComment > 0 || minFavour > 0
    }

    private fun engagementReason(item: Any?): String? {
        if (item == null || !hasEngagementRule()) {
            return null
        }
        if (!isPostFlowModel(item) && safeInvoke(item, "getUser") == null) {
            return null
        }
        if (minLike > 0) {
            val value: Int? = PromoteDetector.likeCount(item)
            if (value != null && value < minLike) {
                return "点赞 " + value + " < 阈值 " + minLike
            }
        }
        if (minComment > 0) {
            val value: Int? = PromoteDetector.commentCount(item)
            if (value != null && value < minComment) {
                return "评论 " + value + " < 阈值 " + minComment
            }
        }
        if (minFavour > 0) {
            val value: Int? = PromoteDetector.favourCount(item)
            if (value != null && value < minFavour) {
                return "收藏 " + value + " < 阈值 " + minFavour
            }
        }
        return null
    }

    private fun keywordMatchers(): List<Any?> {
        val raw: String = module.getString(App.KEY_POST_KEYWORDS, "")!!
        synchronized(keywordLock) {
            val cached = keywordMatchers
            if (cached != null && raw == keywordRaw) {
                return cached
            }
        }
        val list: MutableList<Any?> = ArrayList()
        for (line in raw.split("\n")) {
            val kw = line.trim()
            if (kw.isEmpty()) {
                continue
            }
            if (kw.startsWith("regex:")) {
                try {
                    list.add(Pattern.compile(kw.substring(6).trim(), Pattern.CASE_INSENSITIVE))
                    continue
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "无效正则已忽略: " + kw)
                    continue
                }
            }
            list.add(kw.lowercase(Locale.getDefault()))
        }
        synchronized(keywordLock) {
            keywordRaw = raw
            keywordMatchers = list
        }
        return list
    }


    private fun postCacheKey(bbsLink: Any?): String? {
        val title = safeTitle(bbsLink)
        if (!title.isEmpty()) {
            return title
        }
        val text = safeText(bbsLink)
        if (!text.isEmpty()) {
            return text.substring(0, Math.min(text.length, 64))
        }
        return null
    }

    private fun safeTitle(item: Any?): String {
        return safeGet(item, "getTitle")
    }

    private fun safeText(item: Any?): String {
        return safeGet(item, "getText")
    }

    private val getterCache =
        ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Any?>>()

    private fun findGetter(cls: Class<*>, name: String): Method? {
        var byName = getterCache[cls]
        if (byName == null) {
            val created = ConcurrentHashMap<String, Any?>()
            val prev = getterCache.putIfAbsent(cls, created)
            byName = prev ?: created
        }
        val cached = byName[name]
        if (cached != null) {
            return if (cached === NO_METHOD) null else cached as Method
        }
        var found: Method? = null
        try {
            found = cls.getMethod(name)
        } catch (ignored: Throwable) {
        }
        byName[name] = found ?: NO_METHOD
        return found
    }

    private fun safeGet(item: Any?, getter: String): String {
        try {
            if (item == null) {
                return ""
            }
            val method = findGetter(item.javaClass, getter)
            if (method == null) {
                return ""
            }
            val v = method.invoke(item)
            return if (v == null) "" else stringify(v).trim()
        } catch (t: Throwable) {
            return ""
        }
    }

    private fun safeInvoke(item: Any?, getter: String): Any? {
        try {
            if (item == null) {
                return null
            }
            val method = findGetter(item.javaClass, getter)
            return if (method == null) null else method.invoke(item)
        } catch (t: Throwable) {
            return null
        }
    }

    private fun abbreviate(s: String?): String {
        if (s == null || s.isEmpty()) {
            return "-"
        }
        return if (s.length <= 16) s else s.substring(0, 16)
    }

    private fun parseIntSafe(s: String?): Int {
        try {
            return Integer.parseInt(if (s == null) "0" else s.trim())
        } catch (t: Throwable) {
            return 0
        }
    }

    companion object {

        private const val BLOCK_TTL_MS = 10 * 60 * 1000L
        private const val BLOCK_INDEX_MAX = 2048
        private const val PROBE_LIMIT = 150

        private val CONFIG_CARD_VIEWS = arrayOf(
            "com.max.feature.feeds.view.itemview.ModularPostBodyDefaultItemView",
            "com.max.feature.feeds.view.itemview.ModularPostBody4ItemView",
            "com.max.feature.feeds.view.itemview.ModularPostBody6ItemView",
            "com.max.feature.feeds.view.itemview.ModularPostBody7ItemView",
            "com.max.feature.feeds.view.itemview.ModularPostHeader4ItemView",
            "com.max.feature.feeds.view.itemview.ModularPostHeader5ItemView",
            "com.max.feature.feeds.view.itemview.ModularPostFooter6ItemView",
            "com.max.feature.feeds.view.itemview.ConfigStyleLinkFeedsFlowItemView",
        )

        private val NO_METHOD = Any()

        @Volatile
        private var sInstance: PostFilterHook? = null

        @JvmStatic
        fun get(): PostFilterHook? = sInstance

        private fun isTruthy(s: String?): Boolean {
            if (s == null) {
                return false
            }
            val v = s.trim()
            return "1" == v || "true".equals(v, ignoreCase = true) || "yes".equals(v, ignoreCase = true)
        }
    }
}

private fun stringify(value: Any?): String = if (value == null) "null" else value.toString()
