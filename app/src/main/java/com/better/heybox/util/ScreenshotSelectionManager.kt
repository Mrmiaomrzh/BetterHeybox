package com.better.heybox.util

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.widget.TextView
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.lang.reflect.Method
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

class ScreenshotSelectionManager private constructor() {

    private val selected = LinkedHashMap<View, Selection>()
    private val originalForegrounds = WeakHashMap<View, Drawable?>()

    private val ignored: MutableSet<View> =
        java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    private var activityRef: WeakReference<Activity?> = WeakReference(null)
    private var postBodyRef: WeakReference<View?> = WeakReference(null)
    private var listener: Listener? = null
    private var active = false
    private var lastInvalidCount = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun isActive(): Boolean = active

    fun count(): Int = selected.size

    fun isSelected(unitView: View): Boolean = selected.containsKey(unitView)

    fun ignore(view: View?) {
        if (view != null) {
            ignored.add(view)
        }
    }

    fun unignore(view: View?) {
        if (view != null) {
            ignored.remove(view)
        }
    }

    fun enter(activity: Activity?): Boolean {
        if (activity == null || activity.isFinishing) {
            return false
        }
        refreshDebug()
        if (active) {
            exit()
        }
        activityRef = WeakReference(activity)
        postBodyRef = WeakReference(null)
        active = true
        clearSelectionInternal()

        val body = findPostBody(activity)
        if (body != null) {
            postBodyRef = WeakReference(body)
            applySelection(ScreenshotUnit.postBody(body), true)
        }
        notifyMode(true)
        notifyCount()
        return true
    }

    fun exit() {
        if (!active && originalForegrounds.isEmpty()) {
            return
        }
        active = false
        for (view in ArrayList(originalForegrounds.keys)) {
            restoreForeground(view)
        }
        clearSelectionInternal()
        activityRef = WeakReference(null)
        postBodyRef = WeakReference(null)
        notifyMode(false)
        notifyCount()
    }

    fun toggle(anyViewInsideUnit: View?) {
        val unit = ScreenshotUnit.resolve(anyViewInsideUnit)
        if (unit == null) {
            val body = postBodyOf()
            if (body != null && anyViewInsideUnit != null && isDescendant(anyViewInsideUnit, body)) {
                togglePostBody(body)
            }
            return
        }
        applySelection(unit, !selected.containsKey(unit.view))
    }

    fun togglePostBody(body: View?) {
        if (body != null) {
            applySelection(ScreenshotUnit.postBody(body), !selected.containsKey(body))
        }
    }

    fun toggleUnit(unit: ScreenshotUnit?) {
        if (unit != null) {
            val want = !selected.containsKey(unit.view)
            if (DEBUG) {
                Log.i(TAG, "截图触控 切换 ${unit.idName} -> $want")
            }
            applySelection(unit, want)
        }
    }

    fun setSelected(unit: ScreenshotUnit?, wantSelected: Boolean) {
        if (unit != null) {
            applySelection(unit, wantSelected)
        }
    }

    fun onActivityTouch(activity: Activity?, event: MotionEvent?): Boolean {
        if (!active || activity == null || event == null) {
            return false
        }
        val current = activityRef.get()
        if (current != null && current !== activity) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                moved = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved) {
                    val slop = touchSlop(activity)
                    if (abs(event.rawX - downRawX) > slop
                        || abs(event.rawY - downRawY) > slop
                    ) {
                        moved = true
                    }
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                val tap = !moved
                moved = false
                if (!tap) {
                    return false
                }
                if (isOverlayOnTop(activity, downRawX, downRawY)) {
                    if (DEBUG) {
                        Log.i(TAG, "截图触控 轻点 浮层 -> null")
                    }
                    return false
                }
                val hit = deepestPoint(activity, downRawX, downRawY)
                var unit = pickUnit(activity, downRawX, downRawY)
                if (unit == null) {
                    val body = postBodyOf()
                    if (body != null && containsPoint(body, downRawX, downRawY)) {
                        unit = ScreenshotUnit.postBody(body)
                    }
                }
                if (DEBUG) {
                    Log.i(
                        TAG, "截图触控 轻点 " + describe(hit) + " -> "
                                + (if (unit == null) "null" else unit.idName + " comment=" + unit.comment)
                    )
                }
                if (unit == null) {
                    return false
                }
                toggleUnit(unit)
                cancelHostGesture(activity, event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moved = false
                return false
            }
            else -> return false
        }
    }

    fun selectAll() {
        val activity = activityRef.get() ?: return
        val screenHeight = activity.resources.displayMetrics.heightPixels
        val location = IntArray(2)
        for (unit in visibleUnits(activity)) {
            try {
                unit.view.getLocationOnScreen(location)
            } catch (ignored: Throwable) {
                continue
            }
            if (location[1] > screenHeight || location[1] + unit.view.height < 0) {
                continue
            }
            applySelection(unit, true)
        }
    }

    fun clearSelection() {
        for (view in ArrayList(selected.keys)) {
            restoreForeground(view)
        }
        clearSelectionInternal()
        notifyCount()
    }

    fun orderedValidSelections(): List<Selection> {
        val valid = ArrayList<Selection>()
        val activity = activityRef.get()
        var recovered = 0
        var dropped = 0
        for (selection in ArrayList(selected.values)) {
            if (stillValid(selection)) {
                valid.add(selection)
                continue
            }
            val replacement = recoverSelection(activity, selection)
            if (replacement != null) {
                valid.add(replacement)
                recovered++
            } else {
                dropped++
            }
        }
        lastInvalidCount = dropped
        if (DEBUG && (recovered > 0 || dropped > 0)) {
            Log.i(
                TAG, "截图诊断 选区校验 恢复=$recovered 丢弃=$dropped"
                        + " 有效=${valid.size} 已选=${selected.size}"
            )
        }
        val location = IntArray(2)
        valid.sortBy { screenTop(it.view, location) }
        return valid
    }

    fun lastInvalidCount(): Int = lastInvalidCount

    private fun stillValid(selection: Selection): Boolean {
        val view = selection.view
        if (!view.isAttachedToWindow || view.width <= 0
            || view.parent == null
        ) {
            return false
        }
        val position = adapterPositionOf(view)
        if (position >= 0 && selection.adapterPosition >= 0) {
            if (position != selection.adapterPosition) {
                if (DEBUG) {
                    Log.i(
                        TAG, "截图诊断 选区位置已变 " + describe(view)
                                + " 选中=${selection.adapterPosition} 当前=$position"
                    )
                }
                return false
            }
            return true
        }
        val now = signature(view)
        val same = selection.signature.isEmpty() || selection.signature == now
        if (!same && DEBUG) {
            Log.i(
                TAG, "截图诊断 选区签名已变 " + describe(view)
                        + " old=${selection.signature} now=$now"
            )
        }
        return same
    }

    private fun recoverSelection(activity: Activity?, stale: Selection?): Selection? {
        if (activity == null || stale == null) {
            return null
        }
        val units = visibleUnits(activity)
        if (stale.adapterPosition >= 0) {
            for (unit in units) {
                if (unit.view === stale.view || unit.comment != stale.comment) {
                    continue
                }
                if (adapterPositionOf(unit.view) == stale.adapterPosition) {
                    return Selection(unit.view, unit.comment, stale.signature, stale.adapterPosition)
                }
            }
        }
        if (stale.signature.isEmpty()) {
            return null
        }
        for (unit in units) {
            if (unit.view === stale.view || unit.comment != stale.comment) {
                continue
            }
            if (stale.signature == signature(unit.view)) {
                return Selection(unit.view, unit.comment, stale.signature, adapterPositionOf(unit.view))
            }
        }
        return null
    }

    fun visibleUnits(activity: Activity?): List<ScreenshotUnit> {
        val units = ArrayList<ScreenshotUnit>()
        if (activity == null || activity.window == null) {
            return units
        }
        val seen = ArrayList<View>()
        try {
            collectUnits(activity.window.decorView, units, seen, 0, intArrayOf(0))
        } catch (throwable: Throwable) {
            Log.i(TAG, "截图触控 收集单元失败: $throwable")
        }
        val body = findPostBody(activity)
        if (body != null && !seen.contains(body)) {
            units.add(0, ScreenshotUnit.postBody(body)!!)
        }
        val location = IntArray(2)
        units.sortBy { screenTop(it.view, location) }
        return units
    }

    fun findPostBody(activity: Activity?): View? {
        if (activity == null) {
            return null
        }
        return try {
            val window = activity.window ?: return null
            val decor = window.decorView
            val bodyId = ScreenshotIds.id(activity, ScreenshotIds.POST_BODY)
            val byId = if (bodyId == 0) null else ScreenshotIds.findDescendantById(decor, bodyId)
            if (byId != null) {
                return byId
            }
            val derived = derivePostBody(activity, decor)
            if (derived != null) {
                return derived
            }
            findArticleWebView(decor)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun findArticleWebView(decor: View): View? {
        val minHeight = (decor.resources.displayMetrics.heightPixels * 0.5f).toInt()
        val queue = ArrayDeque<View>()
        queue.add(decor)
        var visited = 0
        while (!queue.isEmpty() && visited++ < 3000) {
            val current = queue.poll()
            if (isWebView(current) && current.isShown
                && current.width > 200 && current.height >= minHeight
            ) {
                return current
            }
            if (current is ViewGroup) {
                val group = current
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
        return null
    }

    fun logBodySearch(activity: Activity?) {
        if (!DEBUG || activity == null) {
            return
        }
        try {
            val window = activity.window
            val decor = window?.decorView ?: return
            val bodyId = ScreenshotIds.id(activity, ScreenshotIds.POST_BODY)
            val textId = ScreenshotIds.id(activity, ScreenshotIds.POST_TEXT)
            val titleId = ScreenshotIds.id(activity, ScreenshotIds.POST_TITLE)
            val byId = if (bodyId == 0) null else ScreenshotIds.findDescendantById(decor, bodyId)
            val text = if (textId == 0) null else ScreenshotIds.findDescendantById(decor, textId)
            val title = if (titleId == 0) null else ScreenshotIds.findDescendantById(decor, titleId)
            Log.i(
                TAG, "截图诊断 post_content_container($bodyId)=" + describe(byId)
                        + " tv_content($textId)=" + describe(text)
                        + " tv_title($titleId)=" + describe(title)
            )
            if (text != null) {
                Log.i(TAG, "截图诊断 tv_content 祖先=" + ancestorChain(text))
            }
            if (title != null) {
                Log.i(TAG, "截图诊断 tv_title 祖先=" + ancestorChain(title))
            }
            Log.i(TAG, "截图诊断 滚动容器=" + scrollerList(decor))
            Log.i(TAG, "截图诊断 分页子块=" + childList(decor))
            Log.i(TAG, "截图诊断 网页视图=" + webViewList(decor))
        } catch (throwable: Throwable) {
            Log.i(TAG, "截图诊断失败: $throwable")
        }
    }

    private fun webViewList(root: View): String {
        val builder = StringBuilder()
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        var found = 0
        while (!queue.isEmpty() && visited++ < 3000 && found < 4) {
            val current = queue.poll()
            if (isWebView(current)) {
                found++
                val parent: ViewParent? = current.parent
                val parentHeight = if (parent is View) parent.height else -1
                builder.append(describe(current))
                    .append(" shown=").append(current.isShown)
                    .append(" w=").append(current.width)
                    .append(" h=").append(current.height)
                    .append(" 父h=").append(parentHeight)
                    .append(" | ")
            }
            if (current is ViewGroup) {
                val group = current
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
        return if (builder.isEmpty()) "无" else builder.toString()
    }

    private fun childList(root: View): String {
        val builder = StringBuilder()
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        var sections = 0
        while (!queue.isEmpty() && visited++ < 3000 && sections < 4) {
            val current = queue.poll()
            if (current is ViewGroup && isScrollerLike(current)
                && current.childCount > 0
                && current.height > 400
            ) {
                sections++
                builder.append('[').append(describe(current)).append("] ")
                val group = current
                var i = 0
                while (i < group.childCount && i < 14) {
                    val child = group.getChildAt(i)
                    builder.append(describe(child)).append("(h=").append(child.height)
                        .append(",kids=")
                        .append(if (child is ViewGroup) child.childCount else 0)
                        .append(") ")
                    i++
                }
                builder.append(" | ")
            }
            if (current is ViewGroup) {
                val group = current
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
        return builder.toString()
    }

    private fun ancestorChain(view: View): String {
        val builder = StringBuilder()
        var current: View? = view
        var i = 0
        while (i < 14 && current != null) {
            if (i > 0) {
                builder.append(" < ")
            }
            builder.append(describe(current))
            val parent: ViewParent? = current.parent
            current = parent as? View
            i++
        }
        return builder.toString()
    }

    private fun scrollerList(root: View): String {
        val builder = StringBuilder()
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        var count = 0
        while (!queue.isEmpty() && visited++ < 3000 && count < 12) {
            val current = queue.poll()
            if (isScrollerLike(current)) {
                var adapter = "?"
                try {
                    val value = current.javaClass.getMethod("getAdapter").invoke(current)
                    adapter = value?.javaClass?.simpleName ?: "null"
                } catch (ignored: Throwable) {
                    adapter = "-"
                }
                builder.append(describe(current)).append("[adapter=").append(adapter)
                    .append(" children=")
                    .append(if (current is ViewGroup) current.childCount else -1)
                    .append(" h=").append(current.height).append("] ")
                count++
            }
            if (current is ViewGroup) {
                val group = current
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
        return builder.toString()
    }

    private fun postBodyOf(): View? {
        var body = postBodyRef.get()
        if (body == null) {
            val activity = activityRef.get()
            if (activity != null) {
                body = findPostBody(activity)
                if (body != null) {
                    postBodyRef = WeakReference(body)
                }
            }
        }
        return body
    }

    private fun derivePostBody(activity: Activity, decor: View): View? {
        val textId = ScreenshotIds.id(activity, ScreenshotIds.POST_TEXT)
        var anchor = if (textId == 0) null else ScreenshotIds.findDescendantById(decor, textId)
        if (anchor == null) {
            val titleId = ScreenshotIds.id(activity, ScreenshotIds.POST_TITLE)
            anchor = if (titleId == 0) null else ScreenshotIds.findDescendantById(decor, titleId)
        }
        if (anchor == null) {
            return null
        }

        if (ScreenshotIds.findAncestorByName(anchor, ScreenshotIds.FEED_CARD) != null) {
            return null
        }
        val commentId = ScreenshotIds.id(activity, ScreenshotIds.COMMENT_BLOCK)
        var best: View? = null
        var current = anchor
        for (i in 0 until 12) {
            val parent: ViewParent? = current!!.parent
            if (parent !is View) {
                break
            }
            val candidate = parent as View
            if (candidate.id == android.R.id.content
                || isScrollerLike(candidate)
                || hasDescendantId(candidate, commentId)
            ) {
                break
            }
            best = candidate
            current = candidate
        }

        if (best != null) {
            val minHeight = (decor.resources.displayMetrics.heightPixels * 0.35f).toInt()
            if (best.height < minHeight || hasRecyclerAncestor(best)) {
                if (DEBUG) {
                    Log.i(TAG, "截图诊断 正文派生被否 h=${best.height} 阈值=$minHeight")
                }
                return null
            }
        }
        return best
    }

    private fun applySelection(unit: ScreenshotUnit?, wantSelected: Boolean) {
        if (unit == null) {
            return
        }
        if (wantSelected) {
            if (!selected.containsKey(unit.view)) {
                selected[unit.view] = Selection(
                    unit.view, unit.comment,
                    signature(unit.view), adapterPositionOf(unit.view)
                )
                applyHighlight(unit.view, true)
                notifyCount()
            }
        } else if (selected.remove(unit.view) != null) {
            restoreForeground(unit.view)
            notifyCount()
        }
    }

    private fun clearSelectionInternal() {
        selected.clear()
    }

    private fun notifyCount() {
        listener?.onSelectionChanged(selected.size)
    }

    private fun notifyMode(isActive: Boolean) {
        listener?.onModeChanged(isActive)
    }

    private fun applyHighlight(view: View, highlight: Boolean) {
        try {
            if (highlight) {
                if (!originalForegrounds.containsKey(view)) {
                    originalForegrounds[view] = view.foreground
                }
                view.foreground = buildHighlight(view)
            } else {
                restoreForeground(view)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun restoreForeground(view: View?) {
        if (view == null) {
            return
        }
        if (!originalForegrounds.containsKey(view)) {
            return
        }
        val original = originalForegrounds.remove(view)
        try {
            view.foreground = original
        } catch (ignored: Throwable) {
        }
    }

    private fun buildHighlight(view: View): Drawable {
        val density = view.resources.displayMetrics.density
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(0x223B82F6)
        drawable.setStroke(maxOf(2, (2 * density).toInt()), 0xFF3B82F6.toInt())
        drawable.cornerRadius = 6f * density
        return HighlightDrawable(drawable, density, longLabel(view))
    }

    private fun longLabel(view: View): String? {
        if (!isWebView(view)) {
            return null
        }
        val content = webContentHeight(view)
        val height = view.height
        if (content <= 0 || height <= 0 || content <= height + 32) {
            return null
        }
        val screens = ceil(content / height.toDouble()).toInt()
        return "长图 · 共 " + maxOf(2, screens) + " 屏"
    }

    private fun screenTop(view: View, location: IntArray): Int {
        return try {
            view.getLocationOnScreen(location)
            location[1]
        } catch (ignored: Throwable) {
            Int.MAX_VALUE
        }
    }

    private fun cancelHostGesture(activity: Activity, event: MotionEvent) {
        try {
            val window = activity.window ?: return
            val decor = window.decorView ?: return
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            decor.dispatchTouchEvent(cancel)
            cancel.recycle()
        } catch (ignored: Throwable) {
        }
    }

    private fun deepestPoint(activity: Activity, rawX: Float, rawY: Float): View? {
        return try {
            val window = activity.window ?: return null
            descendDeepest(window.decorView, rawX, rawY, 0)
        } catch (throwable: Throwable) {
            null
        }
    }

    private fun pickUnit(activity: Activity, rawX: Float, rawY: Float): ScreenshotUnit? {
        return try {
            val window = activity.window ?: return null
            pickIn(window.decorView, rawX, rawY, 0)
        } catch (throwable: Throwable) {
            null
        }
    }

    fun isDebug(): Boolean = DEBUG

    private fun isOverlayOnTop(activity: Activity, rawX: Float, rawY: Float): Boolean =
        overlayContains(rawX, rawY)

    fun overlayContains(rawX: Float, rawY: Float): Boolean {
        for (view in ArrayList(ignored)) {
            if (view.visibility != View.VISIBLE
                || view.width <= 0 || view.height <= 0
            ) {
                continue
            }
            if (containsPoint(view, rawX, rawY)) {
                return true
            }
        }
        return false
    }

    private fun pickIn(node: View?, rawX: Float, rawY: Float, depth: Int): ScreenshotUnit? {
        if (node == null || depth > MAX_HIT_DEPTH) {
            return null
        }
        if (node !is ViewGroup) {
            return ScreenshotUnit.resolve(node)
        }
        val group = node
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (!isHitCandidate(child) || !containsPoint(child, rawX, rawY)) {
                continue
            }
            if (ignored.contains(child)) {
                return null
            }
            var unit = pickIn(child, rawX, rawY, depth + 1)
            if (unit == null) {
                unit = ScreenshotUnit.resolveAncestors(child)
            }
            if (unit != null) {
                if (DEBUG) {
                    Log.i(TAG, "截图触控 命中 " + describe(child) + " -> " + unit.idName)
                }
                return unit
            }
            if (DEBUG) {
                Log.i(TAG, "截图触控 跳过 " + describe(child))
            }
        }
        return null
    }

    private fun descendDeepest(node: View?, rawX: Float, rawY: Float, depth: Int): View? {
        if (node == null || depth > MAX_HIT_DEPTH || node.visibility != View.VISIBLE
            || ignored.contains(node)
        ) {
            return null
        }
        if (node !is ViewGroup) {
            return node
        }
        val group = node
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (!isHitCandidate(child) || !containsPoint(child, rawX, rawY)
                || ignored.contains(child)
            ) {
                continue
            }
            val inner = descendDeepest(child, rawX, rawY, depth + 1)
            return inner ?: child
        }
        return node
    }

    private fun isHitCandidate(view: View?): Boolean =
        view != null && view.visibility == View.VISIBLE
                && view.width > 0 && view.height > 0

    private fun containsPoint(view: View?, rawX: Float, rawY: Float): Boolean {
        if (view == null) {
            return false
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX < location[0] + view.width
                && rawY >= location[1] && rawY < location[1] + view.height
    }

    private fun collectUnits(
        node: View?, out: MutableList<ScreenshotUnit>, seen: MutableList<View>,
        depth: Int, visited: IntArray
    ) {
        if (node == null || depth > MAX_WALK_DEPTH || visited[0] > MAX_WALK_NODES
            || out.size >= MAX_UNITS
        ) {
            return
        }
        visited[0]++
        if (ignored.contains(node)) {
            return
        }
        if (node.isShown && node.width > 0) {
            val unit = ScreenshotUnit.resolve(node)
            if (unit != null && unit.view.isShown && !seen.contains(unit.view)) {
                seen.add(unit.view)
                out.add(unit)
            }
        }
        if (node is ViewGroup) {
            val group = node
            for (i in 0 until group.childCount) {
                collectUnits(group.getChildAt(i), out, seen, depth + 1, visited)
            }
        }
    }

    private fun isDescendant(node: View, ancestor: View): Boolean {
        var current: View? = node
        var i = 0
        while (i < 24 && current != null) {
            if (current === ancestor) {
                return true
            }
            val parent: ViewParent? = current.parent
            current = parent as? View
            i++
        }
        return false
    }

    private fun hasDescendantId(root: View, viewId: Int): Boolean {
        if (viewId == 0) {
            return false
        }
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (!queue.isEmpty() && visited++ < 4000) {
            val current = queue.poll()
            if (current !== root && current.id == viewId) {
                return true
            }
            if (current is ViewGroup) {
                val group = current
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
        return false
    }

    private fun describe(view: View?): String {
        if (view == null) {
            return "null"
        }
        return view.javaClass.simpleName + "#" + ScreenshotIds.entryName(view)
    }

    private class HighlightDrawable(
        private val inner: Drawable,
        private val density: Float,
        private val label: String?
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(2f, 2f * density)
            paint.color = 0xFF3B82F6.toInt()
            labelPaint.color = Color.WHITE
            labelPaint.textSize = 11f * density
            labelBg.color = 0xE63B82F6.toInt()
        }

        override fun draw(canvas: Canvas) {
            inner.bounds = bounds
            inner.draw(canvas)
            canvas.drawRect(bounds, paint)
            if (label.isNullOrEmpty()) {
                return
            }
            val bounds = bounds
            val pad = 6f * density
            val textWidth = labelPaint.measureText(label)
            val metrics = labelPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val right = bounds.right - pad
            val bottom = bounds.bottom - pad
            if (right - textWidth - pad * 2 < bounds.left || bottom - textHeight - pad < bounds.top) {
                return
            }
            val box = RectF(
                right - textWidth - pad * 2, bottom - textHeight - pad,
                right, bottom
            )
            val radius = 4f * density
            canvas.drawRoundRect(box, radius, radius, labelBg)
            canvas.drawText(label, box.left + pad, box.bottom - pad - metrics.descent, labelPaint)
        }

        override fun setAlpha(alpha: Int) {
            inner.alpha = alpha
        }

        @Deprecated("Deprecated in Java")
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            inner.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    class Selection(
        val view: View,
        val comment: Boolean,
        val signature: String,
        val adapterPosition: Int = -1
    )

    interface Listener {
        fun onSelectionChanged(count: Int)

        fun onModeChanged(active: Boolean)
    }

    companion object {

        private const val TAG = "BetterHeybox"

        private const val MAX_HIT_DEPTH = 32

        private const val MAX_WALK_DEPTH = 24

        private const val MAX_WALK_NODES = 3000

        private const val MAX_UNITS = 60

        private const val SIGNATURE_NODES = 400

        private const val SIGNATURE_CHARS = 400

        @JvmField
        @Volatile
        var DEBUG = false

        @JvmStatic
        fun refreshDebug() {
            DEBUG = HeyboxPrefs.getBoolean(App.KEY_VERBOSE_LOG, false)
            Log.i("BetterHeybox", "截图诊断 调试开关=$DEBUG")
        }

        @JvmStatic
        fun isWebView(view: View?): Boolean {
            if (view == null) {
                return false
            }
            var type: Class<*>? = view.javaClass
            while (type != null && type !== View::class.java) {
                if ("WebView" == type.simpleName) {
                    return true
                }
                type = type.superclass
            }
            return false
        }

        internal fun adapterPositionOf(view: View?): Int {
            if (view == null) {
                return -1
            }
            var child = view
            var parent: ViewParent? = view.parent
            var depth = 0
            while (parent is View && depth++ < 24) {
                val current = parent as View
                if (isRecyclerLike(current)) {
                    return try {
                        val holderMethod: Method = current.javaClass
                            .getMethod("getChildViewHolder", View::class.java)
                        val holder = holderMethod.invoke(current, child)
                        if (holder != null) {
                            val position = holder.javaClass
                                .getMethod("getBindingAdapterPosition").invoke(holder)
                            if (position is Int) {
                                return position
                            }
                        }
                        -1
                    } catch (ignored: Throwable) {
                        -1
                    }
                }
                child = current
                parent = current.parent
            }
            return -1
        }

        private fun hasRecyclerAncestor(view: View): Boolean {
            var parent: ViewParent? = view.parent
            var depth = 0
            while (parent is View && depth++ < 20) {
                val ancestor = parent as View
                if (isRecyclerLike(ancestor)) {
                    return true
                }
                parent = ancestor.parent
            }
            return false
        }

        private fun isRecyclerLike(view: View): Boolean {
            var type: Class<*>? = view.javaClass
            while (type != null && type !== View::class.java) {
                if ("RecyclerView" == type.simpleName) {
                    return true
                }
                type = type.superclass
            }
            return false
        }

        private fun isScrollerLike(view: View): Boolean {
            val name = view.javaClass.simpleName
            return name.contains("Scroller") || name.contains("RecyclerView")
                    || name.contains("ViewPager") || name.contains("ScrollView")
        }

        private fun signature(root: View): String {
            val builder = StringBuilder(64)
            val queue = ArrayDeque<View>()
            queue.add(root)
            var visited = 0
            while (!queue.isEmpty() && visited++ < SIGNATURE_NODES
                && builder.length < SIGNATURE_CHARS
            ) {
                val current = queue.poll()
                if (current is TextView) {
                    val text: CharSequence? = current.text
                    if (text != null && text.isNotEmpty()) {
                        builder.append(text.subSequence(0, minOf(text.length, 32))).append('|')
                    }
                }
                if (current is ViewGroup) {
                    val group = current
                    for (i in 0 until group.childCount) {
                        queue.add(group.getChildAt(i))
                    }
                }
            }
            return if (builder.length > SIGNATURE_CHARS)
                builder.substring(0, SIGNATURE_CHARS)
            else
                builder.toString()
        }

        private fun webContentHeight(view: View): Int {
            return try {
                val raw = view.javaClass.getMethod("getContentHeight").invoke(view)
                var height = if (raw is Int) raw else 0
                if (height <= 0) {
                    return 0
                }
                try {
                    val scale = view.javaClass.getMethod("getScale").invoke(view)
                    if (scale is Float) {
                        height = (height * scale).toInt()
                    }
                } catch (ignored: Throwable) {
                }
                height
            } catch (ignored: Throwable) {
                0
            }
        }

        private fun touchSlop(activity: Activity): Float {
            return try {
                ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
            } catch (ignored: Throwable) {
                12f
            }
        }

        @Volatile
        private var instance: ScreenshotSelectionManager? = null

        @JvmStatic
        @Synchronized
        fun get(): ScreenshotSelectionManager {
            if (instance == null) {
                instance = ScreenshotSelectionManager()
            }
            return instance!!
        }
    }
}
