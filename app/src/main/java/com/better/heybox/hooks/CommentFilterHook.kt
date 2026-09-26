package com.better.heybox.hooks

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.better.heybox.App
import com.better.heybox.Checkpoint
import com.better.heybox.MainModule
import com.better.heybox.yuki.YukiChain
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

class CommentFilterHook(module: MainModule) {

    private val module: MainModule

    private val adapterRefs = WeakHashMap<Any, Boolean>()

    private val autoLoadCounts: MutableMap<Any?, Int> =
        Collections.synchronizedMap(WeakHashMap<Any?, Int>())

    private val subScans: MutableMap<Any?, SubScan> =
        Collections.synchronizedMap(WeakHashMap<Any?, SubScan>())

    private class SubScan(val size: Int, val lastId: String, val hidden: Int)

    @Volatile
    private var lastAutoLoadAt = 0L

    private val main: Handler? = createMainHandler()

    private val keywordLock = Any()
    private var keywordRaw: String? = null
    private var keywordMatchers: List<Any?>? = null

    private val getterCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Any?>>()

    private val dataHits = AtomicInteger()
    private val listHits = AtomicInteger()
    private val subListHits = AtomicInteger()
    private val subRowHits = AtomicInteger()
    private val gameHits = AtomicInteger()
    private val gameProbes = AtomicInteger()
    private val gameMissProbes = AtomicInteger()
    private val gameSkipProbes = AtomicInteger()
    private val rowProbes = AtomicInteger()
    private val budgetHits = AtomicInteger()

    private val touchWatched: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    @Volatile
    private var lastTouchAt = 0L

    init {
        this.module = module
        sInstance = this
    }

    fun install(cl: ClassLoader) {
        val data = hookPostCommentsGetter(cl)
        val broad = hookBaseAdapterBind(cl)
        val legacy = if (broad) 0 else hookCommentAdapterBinds(cl)
        val subList = hookSubCommentListFilter(cl)
        val subRows = hookSubCommentRowBinds(cl)
        val subRowViews = hookSubCommentRowViews(cl)
        val epoxy = hookEpoxyItemRows(cl)
        module.logd(
            Log.WARN, MainModule.TAG, "[评论过滤] Hook 安装结果：数据层="
                    + (if (data) "✔" else "✘") + " / 通用列表=" + (if (broad) "✔" else "✘")
                    + " / 指定适配器=" + legacy + " 处"
                    + " / 楼中楼列表=" + (if (subList) "✔" else "✘")
                    + " / 楼中楼行=" + subRows + " 处"
                    + " / 楼中楼视图行=" + (if (subRowViews) "✔" else "✘")
                    + " / 新评论行=" + epoxy + " 处"
                    + " / 兜底过滤=" + (if (isEnabled()) "开启" else "关闭")
                    + " / 屏蔽插眼=" + (if (isHostHideCyEnabled()) "开启" else "关闭")
                    + " / 游戏名接龙=" + (if (gameRelayEnabled()) "开启" else "关闭")
        )
    }


    private fun hookPostCommentsGetter(cl: ClassLoader): Boolean {
        try {
            val model = Class.forName(POST_COMMENT_SECTION_CLASS, false, cl)
            val getter = model.getDeclaredMethod("getPostComments")
            module.hook(getter).intercept { chain ->
                val raw = chain.proceed()
                try {
                    if (!commentFilterActive() || raw !is List<*>) {
                        return@intercept raw
                    }
                    val filtered = filterFloors(raw as List<*>)
                    return@intercept filtered ?: raw
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "评论数据层过滤异常，放行: " + t)
                    return@intercept raw
                }
            }
            Checkpoint.mark("评论过滤数据层安装: ok")
            return true
        } catch (t: Throwable) {
            Checkpoint.mark("评论过滤数据层安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 评论过滤数据层 Hook 失败: " + t)
            return false
        }
    }

    private fun filterFloors(raw: List<*>): List<*>? {
        val keep: MutableList<Any?> = ArrayList<Any?>(raw.size)
        var blocked = 0
        val started = SystemClock.elapsedRealtime()
        var budgetLogged = false
        for (item in raw) {
            if (SystemClock.elapsedRealtime() - started > PASS_BUDGET_MS) {
                budgetHits.incrementAndGet()
                if (!budgetLogged) {
                    budgetLogged = true
                    module.logd(
                        Log.INFO, MainModule.TAG, "[评论过滤] 单次过滤超过 "
                                + PASS_BUDGET_MS + "ms，剩余评论放行（防卡死）"
                    )
                }
                keep.add(item)
                continue
            }
            val reason = floorReason(item)
            if (reason == null) {
                keep.add(item)
                continue
            }
            blocked++
            countHit("数据层", reason)
            logBlocked("数据层", item, reason)
        }
        if (blocked == 0) {
            return null
        }
        module.logd(Log.INFO, MainModule.TAG, "屏蔽评论[数据层] 本页共屏蔽 " + blocked + " 层")
        return keep
    }


    private fun hookSubCommentRowViews(cl: ClassLoader): Boolean {
        try {
            val cls = Class.forName(SUB_COMMENT_VIEW_CLASS, false, cl)
            val bind = cls.getDeclaredMethod("l", Integer.TYPE)
            module.hook(bind).intercept(this::onSubCommentRowView)
            Checkpoint.mark("评论过滤楼中楼视图行安装: ok")
            module.logd(
                Log.INFO, MainModule.TAG, "✔ 楼中楼视图行 Hook: "
                        + SUB_COMMENT_VIEW_CLASS + "#l(int)"
            )
            return true
        } catch (t: Throwable) {
            Checkpoint.mark("评论过滤楼中楼视图行安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 楼中楼视图行 Hook 失败: " + t)
            return false
        }
    }

    @Throws(Throwable::class)
    private fun onSubCommentRowView(chain: YukiChain): Any? {
        val result = chain.proceed()
        try {
            if (!commentFilterActive() || result !is View) {
                return result
            }
            val row = result as View
            val self = chain.getThisObject()
            val index = (chain.getArg(0) as java.lang.Number).intValue()
            val comment = rowItem(self, index)
            if (comment == null) {
                return result
            }
            probeRow(comment, safeGet(comment, "getText"))
            val reason = commentReason(comment)
            if (reason == null) {
                restoreCyView(row)
                return result
            }
            countHit("楼中楼行", reason)
            hideCyView(row)
            logBlocked("楼中楼行", comment, reason)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "楼中楼视图行过滤异常，放行: " + t)
        }
        return result
    }

    private fun rowItem(rowView: Any?, index: Int): Any? {
        try {
            val getter = findGetter(rowView!!.javaClass, "g", Integer.TYPE)
            return if (getter == null) null else getter.invoke(rowView, index)
        } catch (t: Throwable) {
            return null
        }
    }


    private fun hookCommentAdapterBinds(cl: ClassLoader): Int {
        var installed = 0
        for (name in ADAPTER_CLASSES) {
            try {
                val cls = Class.forName(name, false, cl)
                installed += hookAdapterClass(cls)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "评论列表适配器不可用: " + name + " (" + t + ")")
            }
        }
        Checkpoint.mark("评论过滤列表绑定安装: %d 处", installed)
        return installed
    }

    private fun hookAdapterClass(cls: Class<*>): Int {
        val real: MutableList<Method> = ArrayList()
        val bridge: MutableList<Method> = ArrayList()
        for (method in cls.declaredMethods) {
            if (!isCommentBinder(method)) {
                continue
            }
            if (method.isBridge || method.isSynthetic) {
                bridge.add(method)
            } else {
                real.add(method)
            }
        }
        val targets = if (real.isEmpty()) bridge else real
        for (method in targets) {
            module.hook(method).intercept(this::onAdapterBind)
            module.logd(
                Log.INFO, MainModule.TAG, "✔ 评论列表绑定 Hook: "
                        + cls.name + "#" + method.name
            )
        }
        return targets.size
    }

    private fun isCommentBinder(method: Method): Boolean {
        if (method.returnType !== Void.TYPE || method.parameterCount != 2) {
            return false
        }
        val types = method.parameterTypes
        if (COMMENTS_OBJ_CLASS != types[1].name) {
            return false
        }
        val holderName = types[0].name
        if (VIEW_HOLDER_CLASS == holderName || holderName.startsWith(HOLDER_PREFIX)) {
            return true
        }
        try {
            val holder = Class.forName(VIEW_HOLDER_CLASS, false, types[0].classLoader)
            return holder.isAssignableFrom(types[0])
        } catch (ignored: Throwable) {
            return false
        }
    }

    @Throws(Throwable::class)
    private fun onAdapterBind(chain: YukiChain): Any? {
        val holder = chain.getArg(0)
        val itemView = holderView(holder)
        if (itemView != null) {
            restoreCyView(itemView)
        }
        val result = chain.proceed()
        try {
            val self = chain.getThisObject()
            if (self != null) {
                registerAdapter(self)
            }
            if (itemView == null) {
                return result
            }
            if (commentFilterActive()) {
                var reason: String? = if (isEnabled()) spamReason(chain.getArg(1)) else null
                if (reason == null) {
                    reason = gameFloorReason(chain.getArg(1))
                }
                if (reason != null) {
                    countHit("列表", reason)
                    logBlocked("列表", chain.getArg(1), reason)
                    hideCyView(itemView)
                    return result
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "评论列表过滤异常，放行: " + t)
        }
        return result
    }

    private fun holderView(holder: Any?): View? {
        if (holder == null) {
            return null
        }
        try {
            val field: Field = holder.javaClass.getField("itemView")
            val value = field.get(holder)
            return if (value is View) value else null
        } catch (t: Throwable) {
            return FeedItemHider.getItemView(holder)
        }
    }


    private fun hookBaseAdapterBind(cl: ClassLoader): Boolean {
        try {
            val base = Class.forName(BASE_ADAPTER_CLASS, false, cl)
            val holder = Class.forName(BASE_ADAPTER_HOLDER_CLASS, false, cl)
            val bind = base.getDeclaredMethod("onBindViewHolder", holder, Integer.TYPE)
            module.hook(bind).intercept(this::onBaseAdapterBind)
            Checkpoint.mark("评论过滤通用列表安装: ok")
            return true
        } catch (t: Throwable) {
            Checkpoint.mark("评论过滤通用列表安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 评论过滤通用列表 Hook 失败: " + t)
            return false
        }
    }

    @Throws(Throwable::class)
    private fun onBaseAdapterBind(chain: YukiChain): Any? {
        val holder = chain.getArg(0)
        val position = (chain.getArg(1) as java.lang.Number).intValue()
        val itemView = holderView(holder)
        if (itemView != null) {
            restoreCyView(itemView)
        }
        val result = chain.proceed()
        try {
            val adapter = chain.getThisObject()
            if (adapter != null) {
                registerAdapter(adapter)
            }
            if (itemView == null) {
                return result
            }
            val data = itemData(adapter, position)
            if (!isCommentFloor(data)) {
                return result
            }
            val fallback = isEnabled()
            if (fallback) {
                diagnose("列表", data)
            }
            val reason = floorReason(data)
            if (reason != null) {
                if (!fallback) {
                    diagnose("列表", data)
                }
                countHit("列表", reason)
                logBlocked("列表", data, reason)
                hideCyView(itemView)
                return result
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "评论列表过滤异常，放行: " + t)
        }
        return result
    }


    private fun hookSubCommentRowBinds(cl: ClassLoader): Int {
        var installed = 0
        for (name in ADAPTER_CLASSES) {
            try {
                val cls = Class.forName(name, false, cl)
                for (method in cls.declaredMethods) {
                    if (method.returnType !== Void.TYPE || method.parameterCount != 5) {
                        continue
                    }
                    val types = method.parameterTypes
                    if (COMMENT_OBJ_CLASS != types[2].name
                        || COMMENT_OBJ_CLASS != types[3].name
                        || COMMENTS_OBJ_CLASS != types[4].name
                    ) {
                        continue
                    }
                    if (!hasRowViewGetter(types[1])) {
                        continue
                    }
                    module.hook(method).intercept(this::onSubCommentRowBind)
                    installed++
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "楼中楼行挂点跳过 " + name + ": " + t)
            }
        }
        Checkpoint.mark("评论过滤楼中楼行安装: %d 处", installed)
        return installed
    }

    private fun hasRowViewGetter(holder: Class<*>): Boolean {
        try {
            return View::class.java.isAssignableFrom(holder.getMethod("c").returnType)
        } catch (t: Throwable) {
            return false
        }
    }

    private fun holderRowView(holder: Any?): View? {
        if (holder == null) {
            return null
        }
        try {
            val value = holder.javaClass.getMethod("c").invoke(holder)
            return if (value is View) value else null
        } catch (t: Throwable) {
            return null
        }
    }

    @Throws(Throwable::class)
    private fun onSubCommentRowBind(chain: YukiChain): Any? {
        val result = chain.proceed()
        try {
            if (!commentFilterActive()) {
                return result
            }
            val row = holderRowView(chain.getArg(1))
            if (row == null) {
                return result
            }
            var comment = chain.getArg(2)
            if (safeGet(comment, "getText").isEmpty()) {
                val alt = chain.getArg(3)
                if (!safeGet(alt, "getText").isEmpty()) {
                    comment = alt
                }
            }
            val reason = commentReason(comment)
            if (reason != null) {
                countHit("楼中楼行", reason)
                hideCyView(row)
                module.logd(
                    Log.INFO, MainModule.TAG, "屏蔽评论[楼中楼行] 原因=" + reason
                            + ", commentid=" + safeGet(comment, "getCommentid")
                )
            } else {
                restoreCyView(row)
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "楼中楼行过滤异常，放行: " + t)
        }
        return result
    }


    private fun hookSubCommentListFilter(cl: ClassLoader): Boolean {
        try {
            val cls = Class.forName(SUB_COMMENT_VIEW_CLASS, false, cl)
            module.hook(cls.getDeclaredMethod("setTotalList", List::class.java))
                .intercept(this::onSetTotalList)
            Checkpoint.mark("评论过滤楼中楼列表安装: ok")
            return true
        } catch (t: Throwable) {
            Checkpoint.mark("评论过滤楼中楼列表安装失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 评论过滤楼中楼列表 Hook 失败: " + t)
            return false
        }
    }


    private fun hookEpoxyItemRows(cl: ClassLoader): Int {
        var installed = 0
        for (name in EPOXY_ITEM_CLASSES) {
            try {
                val cls = Class.forName(name, false, cl)
                for (method in cls.declaredMethods) {
                    if ("setItem" != method.name || method.parameterCount != 1) {
                        continue
                    }
                    module.hook(method).intercept(this::onEpoxyItemBind)
                    installed++
                    module.logd(Log.INFO, MainModule.TAG, "✔ 新评论行 Hook: " + name + "#setItem")
                }
            } catch (t: Throwable) {
                module.logd(Log.INFO, MainModule.TAG, "新评论行挂点跳过 " + name)
            }
        }
        Checkpoint.mark("评论过滤新评论行安装: %d 处", installed)
        return installed
    }

    @Throws(Throwable::class)
    private fun onEpoxyItemBind(chain: YukiChain): Any? {
        val result = chain.proceed()
        try {
            if (!gameRelayEnabled()) {
                return result
            }
            val view = chain.getThisObject()
            if (view !is View) {
                return result
            }
            val item = chain.getArg(0)
            val text = itemRichText(item)
            probeRow(item, text)
            if (text.isEmpty() || gameLinkReasonForText(text) == null) {
                restoreCyView(view as View)
                return result
            }
            countHit("列表", "游戏名接龙（正文仅游戏链接）")
            module.logd(
                Log.INFO, MainModule.TAG, "屏蔽评论[新评论行] 原因=游戏名接龙, commentid="
                        + safeGet(item, "Y") + ", 正文=" + abbreviate(text)
            )
            hideCyView(view as View)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "新评论行过滤异常，放行: " + t)
        }
        return result
    }

    private fun probeRow(item: Any?, text: String) {
        if (!module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            return
        }
        if (rowProbes.incrementAndGet() > ROW_PROBE_LIMIT) {
            return
        }
        module.logd(
            Log.INFO, MainModule.TAG, "[评论过滤] 诊断[新评论行] class="
                    + (if (item == null) "null" else item.javaClass.simpleName)
                    + ", 取到正文字节=" + text.length + ", 正文=" + abbreviate(text, 80)
        )
    }

    private fun itemRichText(item: Any?): String {
        if (item == null) {
            return ""
        }
        val direct = safeGet(item, "c0")
        if (looksLikeMarkup(direct)) {
            return direct
        }
        for (method in item.javaClass.methods) {
            if (method.parameterCount != 0 || method.returnType !== String::class.java) {
                continue
            }
            if ("toString" == method.name || "getClass" == method.name) {
                continue
            }
            try {
                val value = method.invoke(item)
                if (value is String && looksLikeMarkup(value)) {
                    return value
                }
            } catch (ignored: Throwable) {
            }
        }
        return direct
    }

    @Throws(Throwable::class)
    private fun onSetTotalList(chain: YukiChain): Any? {
        val arg = chain.getArg(0)
        var hidden = 0
        var floorKey: Any? = null
        try {
            if (commentFilterActive() && arg is List<*>) {
                val list = arg as List<*>
                if (list.size > 1) {
                    floorKey = list[0]
                    if (autoLoadDone(floorKey) < MAX_AUTO_LOADS) {
                        hidden = countFilteredSubComments(floorKey, list)
                    }
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "楼中楼预览检查异常，放行: " + t)
        }
        val result = chain.proceed()
        try {
            if (hidden > 0 && arg is List<*>) {
                val list = arg as List<*>
                if (list.size > 1 && floorKey != null) {
                    val loaded = list.size - 1
                    val total = parseInt(safeGet(floorKey, "getChildNum"), 0)
                    val needMore = if (total > 0) loaded < total else loaded - hidden < VISIBLE_TARGET
                    if (needMore) {
                        module.logd(
                            Log.INFO, MainModule.TAG, "楼中楼自动补数据：已加载 " + loaded + "/"
                                    + (if (total > 0) stringify(total) else "?") + " 条、隐藏 " + hidden
                                    + " 条，下一页游标=" + safeGet(list[list.size - 1], "getCommentid")
                        )
                        scheduleAutoLoadMore(chain.getThisObject(), floorKey)
                    }
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "楼中楼自动补数据异常，放行: " + t)
        }
        return result
    }

    private fun countFilteredSubComments(floor: Any?, list: List<*>): Int {
        val lastId = stringify(safeGet(list[list.size - 1], "getCommentid"))
        synchronized(subScans) {
            val prev = subScans[floor]
            if (prev != null && prev.size == list.size && lastId == prev.lastId) {
                return prev.hidden
            }
        }
        var hidden = 0
        for (i in 1 until list.size) {
            if (commentReason(list[i]) != null) {
                hidden++
            }
        }
        synchronized(subScans) {
            subScans[floor] = SubScan(list.size, lastId, hidden)
        }
        return hidden
    }

    private fun autoLoadDone(floorKey: Any?): Int {
        synchronized(autoLoadCounts) {
            val done = autoLoadCounts[floorKey]
            return if (done == null) 0 else done
        }
    }

    private fun scheduleAutoLoadMore(sub: Any?, floorKey: Any?) {
        if (main == null || sub !is ViewGroup || floorKey == null) {
            return
        }
        val group = sub as ViewGroup
        watchTouch(group)
        main.post { tryAutoLoad(group, floorKey, 0) }
    }

    private fun watchTouch(view: View) {
        try {
            val target = listAncestor(view)
            if (target == null) {
                return
            }
            synchronized(touchWatched) {
                if (!touchWatched.add(target)) {
                    return
                }
            }
            target.setOnTouchListener { _, _ ->
                lastTouchAt = SystemClock.elapsedRealtime()
                false
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "评论列表触摸监听失败: " + t)
        }
    }

    private fun tryAutoLoad(group: ViewGroup, floorKey: Any?, retry: Int) {
        try {
            if (SystemClock.elapsedRealtime() - lastAutoLoadAt < AUTO_LOAD_GAP_MS) {
                if (retry < 2) {
                    main!!.postDelayed({ tryAutoLoad(group, floorKey, retry + 1) }, AUTO_LOAD_GAP_MS)
                }
                return
            }
            if (autoLoadDone(floorKey) >= MAX_AUTO_LOADS) {
                return
            }
            val quiet = SystemClock.elapsedRealtime() - lastTouchAt
            if (lastTouchAt > 0L && quiet < TOUCH_SETTLE_MS) {
                if (retry < 3) {
                    main!!.postDelayed({ tryAutoLoad(group, floorKey, retry + 1) }, TOUCH_SETTLE_MS)
                } else {
                    module.logd(Log.INFO, MainModule.TAG, "楼中楼自动补数据：手指仍在操作，本轮放弃")
                }
                return
            }
            val footer = findLoadMoreFooter(group)
            if (footer == null || !isOnScreen(footer)) {
                return
            }
            synchronized(autoLoadCounts) {
                val done = autoLoadCounts[floorKey]
                autoLoadCounts[floorKey] = if (done == null) 1 else done + 1
            }
            lastAutoLoadAt = SystemClock.elapsedRealtime()
            footer.performClick()
            module.logd(Log.INFO, MainModule.TAG, "楼中楼可显示评论不足，已自动加载下一页")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "自动加载更多回复失败: " + t)
        }
    }

    private fun findLoadMoreFooter(group: ViewGroup): View? {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) {
                continue
            }
            val text = firstText(child, 0)
            if (text.contains("回复") && !text.contains("收起")) {
                return child
            }
        }
        return null
    }

    private fun firstText(view: View?, depth: Int): String {
        if (view == null || depth > 6) {
            return ""
        }
        if (view is TextView) {
            val text: CharSequence? = view.text
            return if (text == null) "" else text.toString()
        }
        if (view is ViewGroup) {
            val group = view as ViewGroup
            for (i in 0 until group.childCount) {
                val text = firstText(group.getChildAt(i), depth + 1)
                if (!text.isEmpty()) {
                    return text
                }
            }
        }
        return ""
    }

    private fun hideCyView(view: View?) {
        if (view == null) {
            return
        }
        FeedItemHider.hide(view)
    }

    private fun restoreCyView(view: View?) {
        if (view == null) {
            return
        }
        FeedItemHider.restore(view)
    }


    private fun isEnabled(): Boolean {
        return module.isEnabled(App.KEY_BLOCK_CY_COMMENT, false)
    }

    private fun isHostHideCyEnabled(): Boolean {
        return module.isEnabled(App.KEY_HOST_HIDE_CY, true)
    }

    private fun cyMarkFilterActive(): Boolean {
        return isEnabled() || isHostHideCyEnabled()
    }

    private fun gameRelayEnabled(): Boolean {
        return module.isEnabled(App.KEY_BLOCK_GAME_RELAY, false)
    }

    private fun commentFilterActive(): Boolean {
        return cyMarkFilterActive() || gameRelayEnabled()
    }

    private fun commentReason(comment: Any?): String? {
        if (comment == null) {
            return null
        }
        if (isEnabled()) {
            val reason = singleReason(comment)
            if (reason != null) {
                return reason
            }
        } else if (isHostHideCyEnabled()) {
            val reason = cyMarkReason(comment)
            if (reason != null) {
                return reason
            }
        }
        return gameLinkReason(comment)
    }

    private fun floorReason(floor: Any?): String? {
        if (floor == null) {
            return null
        }
        if (isEnabled() && isMeaninglessModel(floor)) {
            return "无意义评论折叠行"
        }
        val comments = commentList(floor)
        if (comments == null || comments.isEmpty()) {
            return null
        }
        return commentReason(comments[0])
    }

    private fun gameFloorReason(floor: Any?): String? {
        if (floor == null || !gameRelayEnabled()) {
            return null
        }
        val comments = commentList(floor)
        if (comments == null || comments.isEmpty()) {
            return null
        }
        return gameLinkReason(comments[0])
    }

    private fun gameLinkReason(comment: Any?): String? {
        if (comment == null || !gameRelayEnabled()) {
            return null
        }
        try {
            return relayReason(safeGet(comment, "getText"), comment)
        } catch (t: Throwable) {
            return null
        }
    }

    private fun gameLinkReasonForText(text: String): String? {
        return relayReason(text, null)
    }

    private fun relayReason(text: String?, comment: Any?): String? {
        if (text == null || text.length == 0) {
            return null
        }
        if (text.length > MAX_GAME_SCAN_LENGTH) {
            probeSkip(comment, text, "超长")
            return null
        }
        if (!GAME_LINK.matcher(text).find()) {
            probeMiss(comment, text)
            return null
        }
        var links = 0
        var visible = 0
        var cursor = 0
        val names = StringBuilder()
        val anchor: Matcher = ANCHOR_BLOCK.matcher(text)
        while (anchor.find()) {
            links++
            if (links <= 3) {
                if (names.length > 0) {
                    names.append('/')
                }
                names.append(abbreviate(INVISIBLE.matcher(anchor.group(1)).replaceAll(""), 20))
            }
            visible += relayVisibleCount(text, cursor, anchor.start())
            if (visible > MAX_RELAY_VISIBLE_CHARS) {
                probeSkip(comment, text, "锚点外可见字已超 " + MAX_RELAY_VISIBLE_CHARS)
                return null
            }
            cursor = anchor.end()
        }
        visible += relayVisibleCount(text, cursor, text.length)
        probeGameLink(comment, text, visible, links, names.toString())
        if (visible > MAX_RELAY_VISIBLE_CHARS) {
            probeSkip(
                comment, text, "残留 " + visible + " 字: "
                        + abbreviate(relayVisibleText(text, cursor, text.length), 40)
            )
            return null
        }
        return "游戏名接龙（正文仅游戏链接）"
    }

    private fun relayVisibleCount(text: String, from: Int, to: Int): Int {
        return relayVisibleText(text, from, to).length
    }

    private fun relayVisibleText(text: String, from: Int, to: Int): String {
        if (from >= to) {
            return ""
        }
        var segment = text.substring(from, to)
        segment = ANY_TAG.matcher(segment).replaceAll("")
        if (module.isEnabled(App.KEY_RELAY_IGNORE_EMOJI, false)) {
            segment = EMOJI_TOKEN.matcher(segment).replaceAll("")
        }
        return INVISIBLE.matcher(segment).replaceAll("")
    }

    private fun probeGameLink(comment: Any?, text: String, visible: Int, links: Int, names: String) {
        if (!module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            return
        }
        if (gameProbes.incrementAndGet() > GAME_PROBE_LIMIT) {
            return
        }
        module.logd(
            Log.INFO, MainModule.TAG, "[评论过滤] 诊断[游戏链接] commentid="
                    + safeGet(comment, "getCommentid") + ", 作者=" + commentAuthor(comment)
                    + ", 长度=" + text.length + ", 链接数=" + links
                    + ", 残留可见字=" + visible + ", 链接文字="
                    + (if (names.isEmpty()) "-" else names.toString())
        )
    }

    private fun probeSkip(comment: Any?, text: String, why: String) {
        if (!module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            return
        }
        if (gameSkipProbes.incrementAndGet() > GAME_PROBE_LIMIT) {
            return
        }
        module.logd(
            Log.INFO, MainModule.TAG, "[评论过滤] 诊断[放行] commentid="
                    + safeGet(comment, "getCommentid") + ", 作者=" + commentAuthor(comment)
                    + ", 长度=" + text.length + ", 原因=" + why
        )
    }

    private fun commentAuthor(comment: Any?): String {
        val user = safeInvoke(comment, "getUser")
        val name = if (user == null) "" else safeGet(user, "getUsername")
        return if (name.isEmpty()) "-" else name
    }

    private fun probeMiss(comment: Any?, text: String) {
        if (!module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            return
        }
        if (!text.contains("<a") && !text.contains("game")) {
            return
        }
        if (gameMissProbes.incrementAndGet() > GAME_PROBE_LIMIT) {
            return
        }
        module.logd(
            Log.INFO, MainModule.TAG, "[评论过滤] 诊断[未匹配锚点] commentid="
                    + safeGet(comment, "getCommentid") + ", 作者=" + commentAuthor(comment)
                    + ", 长度=" + text.length + ", 正文=" + abbreviate(text, 120)
        )
    }

    private fun countHit(where: String, reason: String?) {
        if (reason != null && reason.startsWith("游戏名接龙")) {
            gameHits.incrementAndGet()
        }
        if ("数据层" == where) {
            dataHits.incrementAndGet()
        } else if ("楼中楼列表" == where) {
            subListHits.incrementAndGet()
        } else if ("楼中楼行" == where) {
            subRowHits.incrementAndGet()
        } else {
            listHits.incrementAndGet()
        }
    }

    private fun spamReason(floor: Any?): String? {
        if (floor == null) {
            return null
        }
        if (isMeaninglessModel(floor)) {
            return "无意义评论折叠行"
        }
        val comments = commentList(floor)
        if (comments == null || comments.isEmpty()) {
            return null
        }
        val first = comments[0]
        val reason = singleReason(first)
        if (reason != null) {
            return reason
        }
        return null
    }

    private fun cyMarkReason(comment: Any?): String? {
        if (comment == null) {
            return null
        }
        if (isTruthy(safeGet(comment, "getIs_cy"))) {
            return "cy 评论"
        }
        if (isCyText(safeGet(comment, "getText"))) {
            return "cy 评论（纯插眼文本）"
        }
        return null
    }

    private fun singleReason(comment: Any?): String? {
        if (comment == null) {
            return null
        }
        if (isTruthy(safeGet(comment, "getIs_cy"))) {
            return "cy 评论"
        }
        if (isTruthy(safeGet(comment, "getIs_meaningless"))) {
            return "无意义评论"
        }
        val text = safeGet(comment, "getText")
        if (isCyText(text)) {
            return "cy 评论（纯插眼文本）"
        }
        val keyword = keywordHit(text)
        return if (keyword == null) null else "命中关键词 " + keyword
    }

    private fun isMeaninglessModel(obj: Any?): Boolean {
        return hasClassNamed(obj, MEANINGLESS_MODEL_CLASS)
    }

    private fun isCommentObj(data: Any?): Boolean {
        return hasClassNamed(data, COMMENT_OBJ_CLASS)
    }

    private fun isCommentFloor(data: Any?): Boolean {
        return hasClassNamed(data, COMMENTS_OBJ_CLASS)
    }

    private fun hasClassNamed(obj: Any?, name: String): Boolean {
        if (obj == null) {
            return false
        }
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            if (name == cls.name) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    private fun diagnose(where: String, item: Any?) {
        if (!module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            return
        }
        val comments = commentList(item)
        val comment = if (comments != null && !comments.isEmpty()) comments[0]
        else (if (isCommentObj(item)) item else null)
        if (comment == null) {
            return
        }
        module.logd(
            Log.INFO, MainModule.TAG, "[评论过滤] 诊断[" + where + "] cy="
                    + safeGet(comment, "getIs_cy") + " meaningless="
                    + safeGet(comment, "getIs_meaningless") + " 正文="
                    + abbreviate(safeGet(comment, "getText"))
        )
    }

    private fun commentList(floor: Any?): List<*>? {
        val value = safeInvoke(floor, "getComment")
        return if (value is List<*>) value else null
    }

    private fun isCyText(raw: String?): Boolean {
        if (raw == null || raw.isEmpty()) {
            return false
        }
        val text = INVISIBLE.matcher(raw).replaceAll("").lowercase(Locale.ROOT)
        return "cy" == text || "插眼" == text
    }


    private fun keywordHit(text: String?): String? {
        if (text == null || text.isEmpty()) {
            return null
        }
        val matchers = keywordMatchers()
        if (matchers.isEmpty()) {
            return null
        }
        val lower = text.lowercase(Locale.ROOT)
        for (matcher in matchers) {
            if (matcher is Pattern) {
                val pattern = matcher
                if (pattern.matcher(lower).find()) {
                    return "regex:" + pattern.pattern()
                }
            } else if (lower.contains(matcher as String)) {
                return matcher as String
            }
        }
        return null
    }

    private fun keywordMatchers(): List<Any?> {
        val raw: String = module.getString(App.KEY_COMMENT_KEYWORDS, "")!!
        synchronized(keywordLock) {
            val cached = keywordMatchers
            if (cached != null && raw == keywordRaw) {
                return cached
            }
        }
        val list: MutableList<Any?> = ArrayList()
        for (line in raw.split("\n")) {
            val keyword = line.trim()
            if (keyword.isEmpty()) {
                continue
            }
            if (keyword.startsWith("regex:")) {
                try {
                    list.add(Pattern.compile(keyword.substring(6).trim(), Pattern.CASE_INSENSITIVE))
                    continue
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "评论关键词无效正则已忽略: " + keyword)
                    continue
                }
            }
            list.add(keyword.lowercase(Locale.ROOT))
        }
        synchronized(keywordLock) {
            keywordRaw = raw
            keywordMatchers = list
        }
        return list
    }


    private fun registerAdapter(adapter: Any) {
        synchronized(adapterRefs) {
            adapterRefs[adapter] = true
        }
    }

    private fun requestRebind() {
        val snapshot: Array<Any> = synchronized(adapterRefs) {
            adapterRefs.keys.toTypedArray()
        }
        if (snapshot.size == 0 || main == null) {
            return
        }
        main.post {
            var rebound = 0
            for (adapter in snapshot) {
                try {
                    adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
                    rebound++
                } catch (ignored: Throwable) {
                }
            }
            module.logd(
                Log.INFO, MainModule.TAG,
                "[评论过滤] 设置已变更，已请求 " + rebound + " 个评论列表重绑"
            )
        }
    }


    private fun safeGet(item: Any?, getter: String): String {
        val value = safeInvoke(item, getter)
        return if (value == null) "" else stringify(value).trim()
    }

    private fun safeInvoke(item: Any?, getter: String): Any? {
        if (item == null) {
            return null
        }
        try {
            val method = findGetter(item.javaClass, getter)
            return if (method == null) null else method.invoke(item)
        } catch (t: Throwable) {
            return null
        }
    }

    private fun findGetter(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        val key = if (params.size == 0) name else name + ":" + params[0].name
        var byName = getterCache[cls]
        if (byName == null) {
            val created = ConcurrentHashMap<String, Any?>()
            val prev = getterCache.putIfAbsent(cls, created)
            byName = if (prev == null) created else prev
        }
        val cached = byName[key]
        if (cached != null) {
            return if (cached === NO_METHOD) null else cached as Method
        }
        var found: Method? = null
        try {
            found = cls.getMethod(name, *params)
        } catch (ignored: Throwable) {
        }
        byName[key] = if (found == null) NO_METHOD else found
        return found
    }

    private fun itemData(adapter: Any?, position: Int): Any? {
        if (adapter == null) {
            return null
        }
        try {
            val method = findGetter(adapter.javaClass, "getItemData", Integer.TYPE)
            return if (method == null) null else method.invoke(adapter, position)
        } catch (t: Throwable) {
            return null
        }
    }

    private fun logBlocked(where: String, item: Any?, reason: String) {
        val sb = StringBuilder()
        sb.append("屏蔽评论[").append(where).append("] 原因=").append(reason)
        val comments = commentList(item)
        val first = if (comments != null && !comments.isEmpty()) comments[0]
        else (if (isCommentObj(item)) item else null)
        if (first != null) {
            val id = safeGet(first, "getCommentid")
            if (!id.isEmpty()) {
                sb.append(", commentid=").append(id)
            }
            val user = safeInvoke(first, "getUser")
            val author = if (user == null) "" else safeGet(user, "getUsername")
            if (!author.isEmpty()) {
                sb.append(", 作者=").append(author)
            }
            if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                var text = safeGet(first, "getText")
                if (text.length > 24) {
                    text = text.substring(0, 24)
                }
                sb.append(", 正文=").append(text)
            }
        }
        module.logd(Log.INFO, MainModule.TAG, sb.toString())
    }

    companion object {

        private const val POST_COMMENT_SECTION_CLASS =
            "com.max.data.model.community.PostCommentSectionModel"
        private const val MEANINGLESS_MODEL_CLASS =
            "com.max.data.model.community.MeaninglessCommentModel"
        private const val COMMENTS_OBJ_CLASS =
            "com.max.basebbs.bean.BBSCommentsObj"
        private const val COMMENT_OBJ_CLASS =
            "com.max.basebbs.bean.BBSCommentObj"
        private const val BASE_ADAPTER_CLASS =
            "com.max.hbcommon.base.adapter.s"
        private const val BASE_ADAPTER_HOLDER_CLASS =
            "com.max.hbcommon.base.adapter.s\$e"
        private const val SUB_COMMENT_VIEW_CLASS = "com.max.xiaoheihe.view.SubCommentView"

        private val EPOXY_ITEM_CLASSES = arrayOf(
            "com.max.feature.community.view.itemview.MainCommentItemView",
            "com.max.feature.community.view.itemview.SubCommentItemView",
        )

        private const val HOLDER_PREFIX = "com.max.hbcommon.base.adapter.s\$"
        private const val VIEW_HOLDER_CLASS =
            "androidx.recyclerview.widget.RecyclerView\$ViewHolder"

        private val ADAPTER_CLASSES = arrayOf(
            "com.max.xiaoheihe.module.bbs.adapter.CommentAdapterV2",
            "com.max.xiaoheihe.module.bbs.adapter.n",
        )

        private val INVISIBLE: Pattern =
            Pattern.compile("[\\s\\u200b-\\u200f\\u202a-\\u202e\\ufeff]+")

        private val GAME_LINK: Pattern = Pattern.compile(
            "<a\\b(?=[^>]*(?:"
                    + "\\bdata-link-type\\s*=\\s*[\"']?game"
                    + "|\\bdata-game-id\\s*="
                    + "|openGameDetail"
                    + "))[^>]*>",
            Pattern.CASE_INSENSITIVE
        )

        private val ANCHOR_BLOCK: Pattern =
            Pattern.compile("<a\\b[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

        private val ANY_TAG: Pattern = Pattern.compile("<[^>]*>")

        private val EMOJI_TOKEN: Pattern = Pattern.compile("\\[cube_[^\\[\\]]*\\]")

        private const val MAX_RELAY_VISIBLE_CHARS = 6

        private const val MAX_GAME_SCAN_LENGTH = 60000

        private const val PASS_BUDGET_MS = 120L

        private const val GAME_PROBE_LIMIT = 40

        private const val SUB_PROBE_LIMIT = 12

        private const val ROW_PROBE_LIMIT = 30

        private const val VISIBLE_TARGET = 6
        private const val MAX_AUTO_LOADS = 4
        private const val AUTO_LOAD_GAP_MS = 2500L

        private const val TOUCH_SETTLE_MS = 1200L

        private val NO_METHOD = Any()

        @Volatile
        private var sInstance: CommentFilterHook? = null

        @JvmStatic
        fun refresh() {
            val instance = sInstance
            if (instance != null) {
                instance.requestRebind()
            }
        }

        @JvmStatic
        fun diagnostics(): String {
            val instance = sInstance
            if (instance == null) {
                return "评论过滤未安装"
            }
            return "评论过滤\n" +
                    "屏蔽插眼：" + (if (instance.isHostHideCyEnabled()) "开启" else "关闭") + "\n" +
                    "关键词 / 无意义评论：" + (if (instance.isEnabled()) "开启" else "关闭") +
                    "（已配置 " + instance.keywordMatchers().size + " 条）\n" +
                    "屏蔽游戏名接龙：" + (if (instance.gameRelayEnabled()) "开启" else "关闭") +
                    "（残留可见字 ≤ " + MAX_RELAY_VISIBLE_CHARS + "）\n" +
                    "命中计数：数据层 " + instance.dataHits.get() +
                    " / 列表 " + instance.listHits.get() +
                    " / 楼中楼列表 " + instance.subListHits.get() +
                    " / 楼中楼行 " + instance.subRowHits.get() + "\n" +
                    "其中游戏名接龙 " + instance.gameHits.get() + " 条" +
                    "（主动探测 " + instance.gameProbes.get() + "/" + GAME_PROBE_LIMIT + " 次" +
                    " / 未匹配锚点 " + instance.gameMissProbes.get() + " 次" +
                    " / 放行 " + instance.gameSkipProbes.get() + " 次" +
                    " / 新评论行 " + instance.rowProbes.get() + "/" + ROW_PROBE_LIMIT + " 次" +
                    " / 超预算 " + instance.budgetHits.get() + " 次）"
        }

        private fun createMainHandler(): Handler? {
            return try {
                Handler(Looper.getMainLooper())
            } catch (t: Throwable) {
                null
            }
        }

        private fun listAncestor(view: View): View? {
            try {
                var p: View? = view
                while (p != null) {
                    val name = p.javaClass.name
                    if (name.endsWith("RecyclerView") || name.endsWith("NestedScrollView")
                        || name.endsWith("ScrollView")
                    ) {
                        return p
                    }
                    p = if (p.parent is View) p.parent as View else null
                }
            } catch (ignored: Throwable) {
                return null
            }
            return null
        }

        private fun isOnScreen(view: View): Boolean {
            return try {
                view.getGlobalVisibleRect(android.graphics.Rect())
            } catch (t: Throwable) {
                false
            }
        }

        private fun parseInt(text: String, def: Int): Int {
            return try {
                Integer.parseInt(text.trim())
            } catch (t: Throwable) {
                def
            }
        }

        private fun isTruthy(value: String?): Boolean {
            if (value == null) {
                return false
            }
            val v = value.trim()
            return "1" == v || "true".equals(v, ignoreCase = true)
        }

        private fun looksLikeMarkup(text: String?): Boolean {
            return text != null && (text.contains("<a") || text.contains("heybox://"))
        }

        private fun abbreviate(text: String?): String {
            return abbreviate(text, 16)
        }

        private fun abbreviate(text: String?, max: Int): String {
            if (text == null || text.isEmpty()) {
                return "-"
            }
            return if (text.length <= max) text else text.substring(0, max)
        }
    }
}

private fun stringify(value: Any?): String = if (value == null) "null" else value.toString()
