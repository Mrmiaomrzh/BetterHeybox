package com.better.heybox.liquidglass

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object TopBarGlass {

    private const val REFRESH_MS = 200L
    private const val BACKDROP_SCALE = 0.6f

    private const val BACKDROP_ROWS = 260

    private val LIFTED = WeakHashMap<View, LiftState>()

    private var sLastLifted: WeakReference<View>? = null

    private const val SAMPLE_WINDOW_MAX = 420

    private val ACTIVE = WeakHashMap<View, GlassDrawable>()
    private val ORIGINAL = WeakHashMap<View, Drawable>()

    private class LiftState(
        val paddingTop: Int,
        val topMargin: Int,
        val clipToPadding: Boolean,
        val hadMarginParams: Boolean
    ) {
        val shifted: HashMap<View, Int> = HashMap()
        var lift: Int = 0
    }

    fun apply(activity: Activity?, anchor: View?) {
        try {
            if (activity == null) {
                return
            }
            val decor: View = activity.window?.decorView ?: return
            var titleBar = findTitleBarFrom(anchor)
            if (titleBar == null) {
                titleBar = findTitleBar(decor)
            }
            if (titleBar == null) {
                titleBar = findHomeHeader(decor)
            }
            if (titleBar == null) {
                LiquidGlassLog.log(Log.WARN, "topbar feather glass: title bar not found")
                return
            }
            val dark = java.lang.Boolean.TRUE == detectDarkTheme(titleBar)

            liftScrollerIfEnabled(activity)
            liftContent(titleBar)

            alignContentBelowBar(decor, titleBar)

            paintStatusBarGlass(activity, titleBar)

            ensureOverlay(activity, decor, dark)
            if (ACTIVE.containsKey(titleBar)) {
                return
            }

            liftContent(titleBar)
            ORIGINAL[titleBar] = titleBar.background

            if (titleBar.parent is ViewGroup) {
                (titleBar.parent as ViewGroup).clipChildren = false
                (titleBar.parent as ViewGroup).clipToPadding = false
            }
            titleBar.z = 1f
            val glass = GlassDrawable(titleBar)
            ACTIVE[titleBar] = glass
            titleBar.background = glass
            glass.attach()

            for (overlay in OVERLAYS) {
                if (overlay.decorView() === decor) {
                    glass.bindOverlay(overlay)
                    overlay.noteScrolled()
                    break
                }
            }
            LiquidGlassLog.log(Log.INFO, "topbar feather glass applied")
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("topbar feather glass apply failed", t)
        }
    }

    private fun liftContent(titleBar: View?) {
        try {
            if (titleBar == null || titleBar.parent !is View) {
                return
            }
            val barH = titleBar.height
            if (barH <= 0) {
                return
            }
            val root = titleBar.rootView ?: return
            if (root is ViewGroup) {
                root.clipChildren = false
            }
            if (titleBar.parent is ViewGroup) {
                (titleBar.parent as ViewGroup).clipChildren = false
            }
            titleBar.z = 1f
            val barLoc = IntArray(2)
            titleBar.getLocationInWindow(barLoc)
            val barBottom = barLoc[1] + barH

            val scroller = findInnerScroller(root, barLoc[1])
            if (scroller == null) {
                LiquidGlassLog.log(Log.VERBOSE, "topbar lift: no inner scroller")
                return
            }
            if (LIFTED.containsKey(scroller)) {
                reclaimShifts(scroller, barH)
                return
            }
            if (!liftOne(scroller, barH, false)) {
                return
            }
            sLastLifted = WeakReference(scroller)

            val barY = barLoc[1]
            val screenW = root.width
            val all = findContentScrollers(root, screenW)
            var deepest: View? = null
            var deepestDepth = -1
            for (v in all) {
                if (v === scroller || LIFTED.containsKey(v)) {
                    continue
                }
                var depth = 0
                var p: View? = v
                while (p != null) {
                    depth++
                    p = if (p.parent is View) p.parent as View else null
                }
                if (depth > deepestDepth) {
                    deepestDepth = depth
                    deepest = v
                }
            }
            if (deepest != null) {
                val loc = IntArray(2)
                deepest.getLocationInWindow(loc)

                val targetTop = min(barY, barLoc[1])

                var padSum = 0
                var p: View? = if (deepest.parent is View) deepest.parent as View else null
                while (p != null && p !== scroller) {
                    padSum += p.paddingTop
                    p = if (p.parent is View) p.parent as View else null
                }
                val delta = max(0, loc[1] - targetTop - padSum)
                LiquidGlassLog.log(
                    Log.VERBOSE, "topbar lift probe: "
                            + deepest.javaClass.simpleName + " top=" + loc[1]
                            + " padSum=" + padSum + " delta=" + delta
                )

                if (abs(delta) > max(8, barH / 8) && abs(delta) <= barH * 2) {
                    liftOne(deepest, delta, true)
                }
            }
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("topbar lift content failed", t)
        }
    }

    private fun liftOne(scroller: View?, barH: Int, chained: Boolean): Boolean {
        if (scroller == null) {
            return false
        }
        return try {
            val lp = scroller.layoutParams
            if (lp !is ViewGroup.MarginLayoutParams) {
                return false
            }
            val mlp = lp
            val oldMargin = mlp.topMargin
            val oldTop = scroller.top
            val oldH = scroller.height
            if (chained && oldMargin < 0) {
                return false
            }

            val shiftedChildren = HashMap<View, Int>()
            if (scroller is ViewGroup) {
                shiftChildren(scroller, -barH, shiftedChildren)
            }
            LiquidGlassLog.log(
                Log.VERBOSE, "topbar lift adjust pad=" + scroller.paddingTop
                        + " newPad=" + (scroller.paddingTop + barH)
                        + " oldTop=" + oldTop + " oldH=" + oldH
                        + " lp=" + scroller.layoutParams.javaClass.simpleName
            )
            val state = LiftState(
                scroller.paddingTop, oldMargin,
                scroller is ViewGroup && (scroller as ViewGroup).clipToPadding,
                true
            )
            state.shifted.putAll(shiftedChildren)

            state.lift = barH
            LIFTED[scroller] = state
            mlp.topMargin = oldMargin - barH
            scroller.layoutParams = mlp
            scroller.setPadding(
                scroller.paddingLeft,
                scroller.paddingTop + barH,
                scroller.paddingRight, scroller.paddingBottom
            )
            if (scroller is ViewGroup) {
                val group = scroller
                group.clipToPadding = false
                if (group.parent is ViewGroup) {
                    (group.parent as ViewGroup).clipChildren = false
                }
            }
            LiquidGlassLog.log(
                Log.VERBOSE, "topbar lift: scroller="
                        + scroller.javaClass.name + " barH=" + barH
                        + " oldMargin=" + oldMargin + " chained=" + chained
            )
            true
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("topbar lift one failed", t)
            false
        }
    }

    private fun reclaimShifts(scroller: View, barH: Int) {
        val st = LIFTED[scroller] ?: return
        val lift = if (st.lift > 0) st.lift else barH
        for (e in st.shifted.entries) {
            val child = e.key
            if (child.parent !== scroller) {
                continue
            }
            val lp = child.layoutParams
            if (lp is ViewGroup.MarginLayoutParams
                && lp.topMargin == e.value
            ) {
                val mlp = lp
                mlp.topMargin = e.value - lift
                child.layoutParams = mlp
            }
        }
    }

    private fun shiftChildren(group: ViewGroup, dy: Int, out: HashMap<View, Int>?) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child == null || child.layoutParams !is ViewGroup.MarginLayoutParams) {
                continue
            }
            val clp = child.layoutParams as ViewGroup.MarginLayoutParams
            if (out != null && !out.containsKey(child)) {
                out[child] = clp.topMargin
            }
            clp.topMargin += dy
            child.layoutParams = clp
        }
    }

    private fun glassHeight(decor: View, bar: View?): Int {
        val fallback = (22f * decor.resources.displayMetrics.density).roundToInt()
        var statusH = 0
        try {
            val id = decor.resources.getIdentifier(
                "status_bar_height", "dimen", "android"
            )
            if (id > 0) {
                statusH = decor.resources.getDimensionPixelSize(id)
            }
        } catch (ignored: Throwable) {
        }
        if (bar == null || bar.height <= 0) {
            return max(1, fallback)
        }
        val loc = IntArray(2)
        bar.getLocationInWindow(loc)
        val bottom = loc[1] + bar.height
        if (statusH > 0 && bottom > statusH) {
            return max(1, bottom - statusH)
        }
        return max(1, bar.height)
    }

    fun liftScrollerIfEnabled(activity: Activity?) {
        try {
            if (activity == null) {
                return
            }
            val decor: View = activity.window?.decorView ?: return
            val bar = findTitleBar(decor)
            val barH: Int
            if (bar != null && bar.height > 0) {
                barH = bar.height
            } else {
                val fallback = (22f * activity.resources
                    .displayMetrics.density).roundToInt()
                if (fallback <= 0) {
                    return
                }
                barH = fallback
            }

            var barTop = barH
            if (bar != null && bar.isShown) {
                val barLoc = IntArray(2)
                bar.getLocationInWindow(barLoc)
                if (barLoc[1] > 0) {
                    barTop = barLoc[1] + barH
                }
            }
            if (decor is ViewGroup) {
                decor.clipChildren = false
            }

            val glassH = glassHeight(decor, bar)
            val scroller = findInnerScroller(decor, barTop)
            if (scroller == null) {
                LiquidGlassLog.log(Log.VERBOSE, "topbar lift: no scroller for barTop=$barTop")
                return
            }
            if (LIFTED.containsKey(scroller)) {
                reclaimShifts(scroller, glassH)
                return
            }
            LiquidGlassLog.log(
                Log.VERBOSE, "topbar lift target: "
                        + scroller.javaClass.simpleName + " top=" + locOf(scroller)[1]
                        + " h=" + scroller.height + " barH=" + barH
            )

            var liftBy = max(1, min(barH, locOf(scroller)[1] - barTop))

            liftBy = max(liftBy, glassH)

            val curLoc = locOf(scroller)
            val wantedBy = max(glassH, curLoc[1] - barTop)
            if (curLoc[1] > 0 && wantedBy > liftBy) {
                liftBy = min(wantedBy, glassH + barH)
            }
            if (liftOne(scroller, liftBy, true)) {
                sLastLifted = WeakReference(scroller)
            }
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("topbar lift scroller failed", t)
        }
    }

    private fun alignContentBelowBar(decor: View, titleBar: View?) {
        try {
            if (decor !is ViewGroup || titleBar == null) {
                return
            }
            val barLoc = IntArray(2)
            val decoLoc = IntArray(2)
            titleBar.getLocationInWindow(barLoc)
            decor.getLocationInWindow(decoLoc)
            val barBottom = barLoc[1] + titleBar.height - decoLoc[1]
            if (barBottom <= 0) {
                return
            }
            val screenW = decor.width
            var maxH = 0
            val minH = decor.height / 2
            var best: View? = null
            val container = decor
            val queue = ArrayList<View>()
            for (i in 0 until container.childCount) {
                queue.add(container.getChildAt(i))
            }
            var visited = 0
            while (!queue.isEmpty() && visited++ < 120) {
                val v = queue.removeAt(0)
                if (v.visibility != View.VISIBLE) {
                    continue
                }
                val loc = locOf(v)
                val top = loc[1] - decoLoc[1]
                val wide = v.width * 10 >= screenW * 8

                if (wide && v.height >= minH && top >= 0
                    && top < barBottom && v !== titleBar
                ) {
                    if (v.height > maxH) {
                        maxH = v.height
                        best = v
                    }
                    continue
                }
                if (v is ViewGroup && top < barBottom + barBottom) {
                    val g = v
                    for (i in 0 until g.childCount) {
                        queue.add(g.getChildAt(i))
                    }
                }
            }
            val bestView = best ?: return
            val loc = locOf(bestView)
            val top = loc[1] - decoLoc[1]
            if (top <= 0) {
                return
            }
            if (bestView.layoutParams !is ViewGroup.MarginLayoutParams) {
                return
            }
            val lp = bestView.layoutParams as ViewGroup.MarginLayoutParams

            val newMargin = lp.topMargin - top
            if (newMargin >= 0) {
                return
            }
            lp.topMargin = newMargin
            bestView.layoutParams = lp
            if (bestView is ViewGroup) {
                val g = bestView
                g.setPadding(
                    g.paddingLeft, g.paddingTop + top,
                    g.paddingRight, g.paddingBottom
                )
                g.clipToPadding = false
            }
            LiquidGlassLog.log(
                Log.VERBOSE, "topbar align: "
                        + bestView.javaClass.simpleName + " top=" + top
                        + " margin=" + newMargin + " barBottom=" + barBottom
            )
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("topbar align failed", t)
        }
    }

    private fun findInnerScroller(root: View, barTop: Int): View? {
        val hits = ArrayList<View>()
        collectScrollers(root, hits)
        var fallback: View? = null
        var fallbackTop = Int.MAX_VALUE
        val logHits = ArrayList<String>()
        val rootW = if (root.width > 0) root.width
        else root.resources.displayMetrics.widthPixels
        val rootH = if (root.height > 0) root.height
        else root.resources.displayMetrics.heightPixels

        val slack = max(16, rootH / 12)
        for (v in hits) {
            val name = v.javaClass.name
            if (name.contains("HorizontalScrollView")) {
                continue
            }
            if (v.width * 10 < rootW * 8) {
                continue
            }
            val simple = v.javaClass.simpleName
            if (simple.contains("ViewPager")) {
                val inner = findEntryScroller(v, barTop, slack, rootH)
                if (inner == null) {
                    if (logHits.size < 14) {
                        logHits.add("$simple:no-inner")
                    }
                    continue
                }
                val iLoc = locOf(inner)
                if (logHits.size < 14) {
                    logHits.add(
                        simple + ">" + inner.javaClass.simpleName
                                + "@" + iLoc[1] + "/" + inner.height
                    )
                }

                val entry = iLoc[1] >= 0 && iLoc[1] <= barTop + slack
                if (entry && inner.height * 6 >= rootH && inner.height >= 200) {
                    LiquidGlassLog.log(
                        Log.VERBOSE, "topbar lift pick: "
                                + inner.javaClass.name + " top=" + iLoc[1]
                                + " h=" + inner.height + " hits=" + logHits
                    )
                    return inner
                }
                continue
            }
            val loc = locOf(v)
            if (loc[1] + v.height <= barTop + 8) {
                continue
            }
            if (logHits.size < 14) {
                logHits.add(simple + "@" + loc[1] + "/" + v.height)
            }
            if (v.height * 2 >= rootH && v.height >= 200) {
                LiquidGlassLog.log(
                    Log.VERBOSE, "topbar lift pick: " + name + " top=" + loc[1]
                            + " h=" + v.height + " pad=" + v.paddingTop
                            + " hits=" + logHits
                )
                return v
            }

            if (loc[1] > 0 && (fallback == null || loc[1] < fallbackTop)) {
                fallback = v
                fallbackTop = loc[1]
            }
        }
        if (fallback == null) {
            LiquidGlassLog.log(Log.VERBOSE, "topbar lift: no candidate, hits=$logHits")
            return null
        }
        LiquidGlassLog.log(
            Log.VERBOSE, "topbar lift pick: fallback "
                    + fallback!!.javaClass.simpleName + " top=" + fallbackTop
                    + " hits=" + logHits
        )
        return fallback
    }

    private fun findEntryScroller(v: View, barTop: Int, slack: Int, rootH: Int): View? {
        val loc = locOf(v)
        if (loc[1] > 0 && loc[1] <= barTop + slack
            && v.height * 2 >= rootH && v.height >= 200
        ) {
            return v
        }
        return realScroller(v)
    }

    private fun realScroller(v: View?): View? {
        if (v is ViewGroup) {
            val g = v
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c != null && c.isShown && LIFTED.containsKey(c) && c.height >= 200) {
                    return c
                }
            }
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c == null || !c.isShown) {
                    continue
                }
                val n = c.javaClass.name
                if (n.contains("Scroll") || n.contains("RecyclerView")) {
                    val loc = locOf(c)
                    if (loc[1] >= 0 && c.height >= 200) {
                        return c
                    }
                }
            }
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c == null || !c.isShown) {
                    continue
                }
                val r = realScroller(c)
                if (r != null) {
                    return r
                }
            }
        }
        return null
    }

    private fun scrollHolderOf(root: View?): View? {
        if (root == null) {
            return null
        }
        var pager2Sliver: View? = null
        var consecutive: View? = null
        var rv: View? = null
        val stack = ArrayList<View>()
        stack.add(root)
        while (!stack.isEmpty()) {
            
            val v = stack.removeAt(stack.size - 1)
            if (v.visibility != View.VISIBLE) {
                continue
            }
            val name = v.javaClass.name
            if (name.contains("ViewPager2") && name.contains("RecyclerViewImpl")) {
                if (pager2Sliver == null) {
                    pager2Sliver = v
                }
            } else if (name.contains("ConsecutiveScroller")) {
                if (consecutive == null) {
                    consecutive = v
                }
            } else if (name.contains("RecyclerView")) {
                if (rv == null) {
                    rv = v
                }
            }
            if (v is ViewGroup) {
                val g = v
                for (i in 0 until g.childCount) {
                    stack.add(g.getChildAt(i))
                }
            }
        }
        if (pager2Sliver != null) {
            return pager2Sliver
        }
        if (consecutive != null) {
            return consecutive
        }
        return rv
    }

    private fun locOf(v: View): IntArray {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return loc
    }

    private fun collectAbsOffset(ancestor: View, target: View, out: IntArray) {
        out[0] = Int.MIN_VALUE
        out[1] = 0
        var x = target.left
        var y = target.top
        var p: View? = if (target.parent is View) target.parent as View else null
        while (p != null) {
            if (p === ancestor) {
                out[0] = x
                out[1] = y
                return
            }
            x += p.left - p.scrollX
            y += p.top - p.scrollY
            p = if (p.parent is View) p.parent as View else null
        }
    }

    private fun findLiftableAncestor(v: View, barTop: Int, rootW: Int): View? {
        var best: View? = null
        var pager: View? = null
        var p: View? = if (v.parent is View) v.parent as View else null
        while (p != null) {
            val name = p.javaClass.name
            val isPager = name.contains("ViewPager")
            if (isPager) {
                pager = p
            }
            val loc = locOf(p)
            val fullW = p.width * 10 >= rootW * 8
            val tallEnough = p.height >= 200

            if (fullW && tallEnough && loc[1] >= barTop - 16 && !isPager
                && loc[1] <= barTop + 8
            ) {
                return p
            }
            if (isPager && fullW && tallEnough) {
                best = p
            }
            if (pager != null && passedPager(p) > 1) {
                break
            }
            p = if (p.parent is View) p.parent as View else null
        }

        return pager ?: best
    }

    private fun passedPager(v: View): Int =
        if (v.javaClass.name.contains("ViewPager")) 2 else 0

    private fun findContentScrollers(root: View, screenW: Int): ArrayList<View> {
        val all = ArrayList<View>()
        collectScrollers(root, all)
        val pick = ArrayList<View>()
        for (v in all) {
            if (v.width < screenW * 0.9f || v.height < 200) {
                continue
            }
            val name = v.javaClass.name
            if (name.contains("\$RecyclerViewImpl")) {
                continue
            }
            if (name.contains("ViewPager")) {
                continue
            }
            pick.add(v)
        }
        return pick
    }

    private fun collectScrollers(v: View?, out: ArrayList<View>) {
        if (v == null || v.visibility != View.VISIBLE) {
            return
        }
        val name = v.javaClass.name
        val scrollable = v is android.widget.ScrollView
                || v is android.widget.HorizontalScrollView
                || name.contains("RecyclerView")
                || name.contains("ScrollView")
                || name.contains("ViewPager")
        if (scrollable && v.width > 0 && v.height > 0) {
            if (!name.contains("\$RecyclerViewImpl")) {
                out.add(v)
            }
        }
        if (v is ViewGroup) {
            val g = v
            for (i in 0 until g.childCount) {
                collectScrollers(g.getChildAt(i), out)
            }
        }
    }

    private fun releaseLifted() {
        for (v in ArrayList(LIFTED.keys)) {
            val st = LIFTED.remove(v) ?: continue
            try {
                val lp = v.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.topMargin = st.topMargin
                    v.layoutParams = lp
                }
                v.setPadding(v.paddingLeft, st.paddingTop, v.paddingRight, v.paddingBottom)
                if (v is ViewGroup) {
                    v.clipToPadding = st.clipToPadding
                }
                for (e in st.shifted.entries) {
                    val child = e.key
                    val clp = child.layoutParams
                    if (clp is ViewGroup.MarginLayoutParams) {
                        clp.topMargin = e.value
                        child.layoutParams = clp
                    }
                }
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("topbar lift restore failed", t)
            }
        }
    }

    fun releaseAll() {
        try {
            for (v in ArrayList(ACTIVE.keys)) {
                release(v)
            }
            releaseLifted()
            for (overlay in ArrayList(OVERLAYS)) {
                overlay.detach()
            }
            OVERLAYS.clear()
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("topbar feather glass release failed", t)
        }
    }

    private fun release(v: View) {
        val glass = ACTIVE.remove(v)
        glass?.detach()

        if (!ORIGINAL.containsKey(v)) {
            return
        }
        val original = ORIGINAL.remove(v)
        v.background = original
    }

    private fun paintStatusBarGlass(activity: Activity?, titleBar: View?) {
        try {
            if (activity == null || activity.window == null) {
                return
            }
            val window = activity.window

            window.statusBarColor = android.graphics.Color.TRANSPARENT

            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("topbar status bar glass failed", t)
        }
    }

    private val OVERLAYS: MutableSet<Overlay> =
        java.util.Collections.newSetFromMap(WeakHashMap<Overlay, Boolean>())

    private fun ensureOverlay(activity: Activity?, decor: View, dark: Boolean): Overlay {
        for (overlay in OVERLAYS) {
            if (overlay.decorView() === decor) {
                overlay.setDark(dark)
                return overlay
            }
        }
        val overlay = newOverlay(decor, dark)
        OVERLAYS.add(overlay)
        overlay.attach()

        for (bar in ArrayList(ACTIVE.keys)) {
            if (bar != null && bar.rootView === decor) {
                ACTIVE[bar]?.bindOverlay(overlay)
                break
            }
        }
        return overlay
    }

    fun sync(activity: Activity?) {
        try {
            if (activity == null) {
                return
            }
            if (activity.window == null) {
                return
            }
            val decor: View = activity.window!!.decorView ?: return
            val bar = findTitleBar(decor) ?: return

            apply(activity, null)
            ensureEveryWindow(activity)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("topbar feather glass sync failed", t)
        }
    }

    private fun ensureEveryWindow(activity: Activity?) {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val current = activityThreadClass.getMethod("currentActivityThread")
            val thread = current.invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val map = activitiesField.get(thread)
            if (map !is Map<*, *>) {
                return
            }
            for (record in map.values) {
                if (record == null) {
                    continue
                }
                val activityField = record.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                val obj = activityField.get(record)
                if (obj !is Activity) {
                    continue
                }
                val act = obj
                if (act.isFinishing || act.window == null) {
                    continue
                }
                val decor: View = act.window!!.decorView
                val bar = findTitleBar(decor) ?: continue
                val dark = java.lang.Boolean.TRUE == detectDarkTheme(bar)
                val overlay = ensureOverlay(act, decor, dark)
                overlay.invalidate()
            }
        } catch (ignored: Throwable) {
        }
    }


    private fun isHomeWindow(decor: View): Boolean {
        return try {
            val context = decor.context
            val pkg = context.packageName
            for (name in arrayOf("tb_home", "fl_tb_home_container")) {
                val id = context.resources.getIdentifier(name, "id", pkg)
                if (id != 0 && decor.findViewById<View>(id) != null) {
                    return true
                }
            }
            false
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun newOverlay(decor: View, dark: Boolean): Overlay =
        Overlay(decor, dark)

    private fun findTitleBarFrom(anchor: View?): View? {
        var current = anchor
        var i = 0
        while (current != null && i < 16) {
            if (current.javaClass.simpleName.contains("TitleBar")) {
                return current
            }
            val parent: ViewParent? = current.parent
            current = parent as? View
            i++
        }
        return null
    }

    fun findTitleBar(root: View?): View? {
        if (root == null) {
            return null
        }
        try {
            val context = root.context
            val pkg = context.packageName
            for (name in arrayOf("tb_home", "fl_tb_home_container")) {
                val id = context.resources.getIdentifier(name, "id", pkg)
                if (id != 0) {
                    val view = root.findViewById<View>(id)
                    if (view != null && view.height > 0) {
                        return view
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        return findTitleBarByClass(root)
    }

    private fun findHomeHeader(decor: View?): View? {
        if (decor == null) {
            return null
        }
        return try {
            val pkgId = decor.context.resources
                .getIdentifier("fl_tb_home_container", "id", decor.context.packageName)
            if (pkgId != 0) {
                val v = decor.findViewById<View>(pkgId)
                if (v != null && v.isShown && v.height > 0) {
                    return v
                }
            }
            null
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun findTitleBarByClass(root: View?): View? {
        if (root == null) {
            return null
        }
        if (root.javaClass.simpleName.contains("TitleBar")) {
            return root
        }
        if (root is ViewGroup) {
            val group = root
            for (i in 0 until group.childCount) {
                val found = findTitleBarByClass(group.getChildAt(i))
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun detectDarkTheme(bar: View): Boolean? {
        val textColor = findTextColor(bar) ?: return null
        return luminance(textColor) > 140
    }

    private fun findTextColor(root: View?): Int? {
        if (root == null) {
            return null
        }
        if (root is TextView) {
            val color = root.currentTextColor
            if ((color ushr 24) != 0) {
                return color
            }
        }
        if (root is ViewGroup) {
            val group = root
            for (i in 0 until group.childCount) {
                val found = findTextColor(group.getChildAt(i))
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun luminance(color: Int): Int =
        (((color shr 16) and 0xFF) * 30 + ((color shr 8) and 0xFF) * 59
                + (color and 0xFF) * 11) / 100

    private const val OVERLAY_REFRESH_MS = 200L

    private const val DEBUG_DUMP_BACKDROP = false

    private const val SCROLL_KICK_WINDOW_MS = 260L

    private const val OVERLAY_ALIGN_TOLERANCE_PX = 8

    private const val OVERLAY_EXTRA = 1.6f

    private const val mStrongBlurDp = 34f

    private class GlassDrawable(private val mBar: View) : Drawable() {

        private val mDensity: Float
        private var mDark = false
        private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val mMatrix = Matrix()

        private var mBackdrop: Bitmap? = null
        private var mLastCaptureAt = 0L
        private var mCapturing = false
        private var mLogged = false
        private var mScrollListener: ViewTreeObserver.OnScrollChangedListener? = null

        private var mOverlayRef: WeakReference<Overlay>? = null

        private var mOverlayBound = false

        fun bindOverlay(overlay: Overlay) {
            mOverlayRef = WeakReference(overlay)
            mOverlayBound = true
        }

        init {
            val context = mBar.context
            mDensity = context.resources.displayMetrics.density
            val themeDark = TopBarGlass.detectDarkTheme(mBar)
            mDark = themeDark
                ?: (context.resources.configuration.uiMode
                        and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        fun attach() {
            if (mScrollListener != null) {
                return
            }
            mScrollListener = ViewTreeObserver.OnScrollChangedListener {
                val ov = mOverlayRef?.get()
                if (ov != null) {
                    ov.noteScrolled()
                    ov.refreshAfterIdle()
                    return@OnScrollChangedListener
                }
                invalidateSelf()
            }
            try {
                mBar.viewTreeObserver.addOnScrollChangedListener(mScrollListener)
            } catch (ignored: Throwable) {
            }
        }

        fun detach() {
            if (mScrollListener != null) {
                try {
                    mBar.viewTreeObserver.removeOnScrollChangedListener(mScrollListener)
                } catch (ignored: Throwable) {
                }
                mScrollListener = null
            }
            recycle()
        }

        fun refresh() {
            mLastCaptureAt = 0
            invalidateSelf()
        }

        fun refreshAppearance() {
            val themeDark = TopBarGlass.detectDarkTheme(mBar)
            if (themeDark != null) {
                mDark = themeDark
            }
            mLastCaptureAt = 0
            recycle()
            invalidateSelf()
        }

        private fun recycle() {
            if (mBackdrop != null && !mBackdrop!!.isRecycled) {
                mBackdrop!!.recycle()
            }
            mBackdrop = null
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val width = bounds.width()
            val height = bounds.height()
            if (width <= 0 || height <= 0) {
                return
            }
            maybeCapture(width, height)
            if (mBackdrop == null || mBackdrop!!.isRecycled) {
                return
            }
            val bmp = mBackdrop!!
            val shader = BitmapShader(
                bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
            )
            mMatrix.setScale(
                width / bmp.width.toFloat(),
                height / bmp.height.toFloat()
            )
            shader.setLocalMatrix(mMatrix)
            mPaint.shader = shader
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mPaint)
            canvas.restore()
            mPaint.shader = null
        }

        private fun maybeCapture(width: Int, height: Int) {
            if (mCapturing || !mOverlayBound) {
                return
            }
            if (!mBar.isAttachedToWindow || !mBar.isShown) {
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now - mLastCaptureAt < OVERLAY_REFRESH_MS) {
                return
            }
            mLastCaptureAt = now

            val bw = max(1, (width * BACKDROP_SCALE).roundToInt())
            val bh = max(1, (height * BACKDROP_SCALE).roundToInt())
            if (mBackdrop == null || mBackdrop!!.isRecycled
                || mBackdrop!!.width != bw || mBackdrop!!.height != bh
            ) {
                recycle()
                try {
                    mBackdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                } catch (t: Throwable) {
                    LiquidGlassLog.logErr("topbar glass backdrop alloc failed", t)
                    mBackdrop = null
                    return
                }
            }

            val root = mBar.rootView ?: return
            val barLoc = IntArray(2)
            val rootLoc = IntArray(2)
            mBar.getLocationInWindow(barLoc)
            root.getLocationInWindow(rootLoc)
            val dx = (barLoc[0] - rootLoc[0]).toFloat()

            val barTopInRoot = barLoc[1] - rootLoc[1]
            mCapturing = true
            val capBmp = mBackdrop ?: return
            try {
                val canvas = Canvas(capBmp)
                canvas.save()
                canvas.scale(BACKDROP_SCALE, BACKDROP_SCALE)

                val rowsBelow = max(BACKDROP_ROWS, mBar.height * 2)
                canvas.translate(-dx, -(barTopInRoot + rowsBelow).toFloat())

                root.draw(canvas)
                canvas.restore()
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("topbar glass capture failed", t)
            } finally {
                mCapturing = false
            }

            try {
                val themeDark = TopBarGlass.detectDarkTheme(mBar)
                if (themeDark != null) {
                    mDark = themeDark
                }
                applySurfaceTint()
                applyFeather()
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("topbar glass process failed", t)
            }
            if (!mLogged) {
                mLogged = true
                Log.i("BetterHeybox", "topbar feather glass backdrop " + bw + "x" + bh)
            }
        }

        private fun applySurfaceTint() {
            val bmp = mBackdrop ?: return
            val paint = Paint()
            paint.color = if (mDark) 0x38000000 else 0x3DFFFFFF
            Canvas(bmp).drawRect(
                0f, 0f,
                bmp.width.toFloat(), bmp.height.toFloat(), paint
            )
        }

        private fun applyFeather() {
            val bmp = mBackdrop ?: return
            val w = bmp.width
            val h = bmp.height
            val fade = max(1, min(h / 2, (h * 0.45f).roundToInt()))
            val base = mBackdrop!!.getPixel(w / 2, h - 1)
            val transparentBase = base and 0x00FFFFFF
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, (h - fade).toFloat(), 0f, h.toFloat(),
                transparentBase, base, Shader.TileMode.CLAMP
            )
            Canvas(bmp).drawRect(0f, (h - fade).toFloat(), w.toFloat(), h.toFloat(), paint)
        }

        override fun setAlpha(alpha: Int) {
        }

        @Deprecated("Deprecated in Java")
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    internal class Overlay(private val mDecor: View, dark: Boolean) : View(mDecor.context) {

        fun decorView(): View = mDecor

        private var mDark = dark
        private var mCapturing = false
        private var mBackdrop: Bitmap? = null
        private var mOutput: Bitmap? = null
        private var mLastCaptureAt = 0L
        private var mLogged = false
        private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        init {
            setWillNotDraw(false)
        }

        fun setDark(dark: Boolean) {
            if (mDark != dark) {
                mDark = dark
                mLastCaptureAt = 0
                invalidate()
            }
        }

        private var mTicking = false
        private var mScrollTicker: GlassDrawable? = null

        private fun findTickerIn(root: View): GlassDrawable? {
            for (bar in ArrayList(TopBarGlass.ACTIVE.keys)) {
                if (bar != null && bar.isAttachedToWindow
                    && bar.rootView === root
                ) {
                    return TopBarGlass.ACTIVE[bar]
                }
            }
            return null
        }

        fun kick() {
            if (mTicking || mCapturing) {
                return
            }
            mTicking = true
            try {
                postOnAnimation(mTickRunnable)
            } catch (ignored: Throwable) {
                mTicking = false
            }
        }

        private val mTickRunnable: Runnable = object : Runnable {
            override fun run() {
                mTicking = false
                if (!isAttachedToWindow) {
                    return
                }
                mScrollTicker?.refresh()
                invalidate()

                if (isScrollingNow()) {
                    kick()
                }
            }
        }

        @Volatile
        private var mScrollKickAt = 0L

        fun noteScrolled() {
            mScrollKickAt = SystemClock.uptimeMillis()
            kick()
        }

        private fun isScrollingNow(): Boolean {
            if (mScrollKickAt == 0L) {
                return false
            }
            return SystemClock.uptimeMillis() - mScrollKickAt < SCROLL_KICK_WINDOW_MS
        }

        fun refreshAfterIdle() {
            if (mIdleRefreshPosted) {
                return
            }
            mIdleRefreshPosted = true
            postDelayed({
                mIdleRefreshPosted = false
                if (!isScrollingNow()) {
                    invalidate()
                }
            }, SCROLL_KICK_WINDOW_MS + 40L)
        }

        private var mIdleRefreshPosted = false

        fun attach() {
            if (mDecor !is ViewGroup) {
                return
            }

            post {
                mScrollTicker = findTickerIn(mDecor)
                kick()
            }
            val group = mDecor

            var barH = hostTitleBarHeight()
            if (barH <= 0) {
                barH = (22f * resources.displayMetrics.density).roundToInt()
            }

            val top = hostTitleBarTop()

            val offsetY = top

            val bandH = (barH * 1.6f).roundToInt()
            val lp: ViewGroup.LayoutParams
            if (group is android.widget.FrameLayout) {
                val flp = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, offsetY + barH + bandH
                )
                flp.topMargin = 0
                lp = flp
            } else if (group is android.widget.LinearLayout) {
                val llp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, offsetY + barH + bandH
                )
                llp.topMargin = 0
                lp = llp
            } else {
                lp = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, offsetY + barH + bandH
                )
            }
            try {
                group.addView(this, lp)
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("topbar overlay attach failed", t)
                return
            }

            paintTitleBarSurface(barH)
            LiquidGlassLog.log(
                Log.INFO, "topbar overlay attached top=" + top + " h="
                        + (barH + bandH) + " glass=" + barH + " dark=" + mDark
            )
        }

        private fun hostTitleBarTop(): Int {
            val bar = titleBarView()
            if (bar == null) {
                return statusBarHeight()
            }
            val origin = IntArray(2)
            val loc = IntArray(2)
            mDecor.getLocationInWindow(origin)
            bar.getLocationInWindow(loc)
            val top = loc[1] - origin[1]
            if (top > 0) {
                return top
            }
            return 0
        }

        private fun paintTitleBarSurface(glassH: Int) {
            val bar = titleBarView() ?: return
            val themeDark = TopBarGlass.detectDarkTheme(bar)
            if (themeDark != null) {
                mDark = themeDark
            }

            val alpha = if (mDark) 0x3A else 0x42
            val rgb = if (mDark) 0x181A1C else 0xF7F8FA
            val surface = object : Drawable() {
                override fun draw(canvas: Canvas) {
                    val b = bounds
                    val bw = b.width()
                    val bh = b.height()
                    if (bw <= 0 || bh <= 0) {
                        return
                    }

                    val tb = mBackdrop
                    if (tb != null && !tb.isRecycled) {
                        val m = Matrix()
                        m.setScale(
                            bw / tb.width.toFloat(),
                            bh / tb.height.toFloat()
                        )
                        val shader = BitmapShader(
                            tb,
                            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
                        )
                        shader.setLocalMatrix(m)
                        val p = Paint(Paint.FILTER_BITMAP_FLAG)
                        p.shader = shader
                        canvas.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)

                        p.shader = null
                        p.color = (alpha shl 24) or rgb
                        canvas.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
                    } else {
                        val p = Paint(Paint.ANTI_ALIAS_FLAG)

                        p.color = (0xF0 shl 24) or rgb
                        canvas.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
                    }
                }

                override fun setAlpha(a: Int) {
                }

                override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                }

                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
            TopBarGlass.ORIGINAL[bar] = bar.background
            bar.background = surface
        }

        fun detach() {
            try {
                if (parent is ViewGroup) {
                    (parent as ViewGroup).removeView(this)
                }
            } catch (ignored: Throwable) {
            }
            recycle()
        }

        private fun statusBarHeight(): Int {
            return try {
                val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id != 0) {
                    resources.getDimensionPixelSize(id)
                } else {
                    (24f * resources.displayMetrics.density).roundToInt()
                }
            } catch (ignored: Throwable) {
                (24f * resources.displayMetrics.density).roundToInt()
            }
        }

        private fun titleBarHeight(): Int = hostTitleBarHeight() + top

        private fun hostTitleBarHeight(): Int {
            val bar = titleBarView()
            if (bar != null && bar.height > 0) {
                return bar.height
            }

            for (known in ArrayList(TopBarGlass.ORIGINAL.keys)) {
                if (known != null && known.height > 0) {
                    return known.height
                }
            }
            var barH = 0
            val decor = mDecor
            val context = context
            for (name in arrayOf("tb_home", "fl_tb_home_container")) {
                try {
                    val id = context.resources.getIdentifier(name, "id", context.packageName)
                    if (id != 0) {
                        val view = decor.findViewById<View>(id)
                        if (view != null && view.height > 0) {
                            val origin = IntArray(2)
                            val loc = IntArray(2)
                            decor.getLocationInWindow(origin)
                            view.getLocationInWindow(loc)
                            val bottom = loc[1] - origin[1] + view.height
                            barH = if (bottom > view.height) bottom else view.height
                            break
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
            if (barH <= 0) {
                barH = (22f * resources.displayMetrics.density).roundToInt()
            }
            return barH
        }

        private fun overlayTotalHeight(): Int {
            val barH = titleBarHeight()
            return barH + (barH * OVERLAY_EXTRA).roundToInt()
        }

        private fun maxDrawableRegionH(): Int {
            val decor = mDecor
            var limit = 0
            val screenW = if (decor.width > 0) decor.width
            else resources.displayMetrics.widthPixels
            val screenH = if (decor.height > 0) decor.height
            else resources.displayMetrics.heightPixels
            for (v in TopBarGlass.findContentScrollers(decor, screenW)) {
                if (!v.isShown || v.height < 200) {
                    continue
                }
                val loc = IntArray(2)
                v.getLocationInWindow(loc)

                if (loc[1] < 0 || loc[1] >= screenH - 64) {
                    continue
                }
                limit = max(limit, loc[1] + titleBarHeight())
            }
            if (limit <= 0) {
                return titleBarHeight() + (titleBarHeight() * OVERLAY_EXTRA).roundToInt()
            }
            return limit
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0 || mCapturing) {
                return
            }

            val offsetY = top
            maybeCapture(w, h)

            buildProgressiveFade(w, h, titleBarHeight(), offsetY)

            if (offsetY > 0 && mOutput != null && !mOutput!!.isRecycled) {
                val mode = if (mPaint.xfermode == null) -1 else 0
                mPaint.xfermode = null
                val srcTop = Rect(0, 0, mOutput!!.width, min(mOutput!!.height, offsetY))
                val dstTop = Rect(0, 0, w, min(h, offsetY))
                canvas.drawBitmap(mOutput!!, srcTop, dstTop, mPaint)
                if (mode != 0) {
                    mPaint.xfermode = null
                }
            }

            if (mOutput != null && !mOutput!!.isRecycled) {
                val dstTop = max(0, min(height, offsetY + titleBarHeight()))

                val dstBottom = height
                if (dstBottom > dstTop) {
                    val dst = Rect(0, dstTop, w, dstBottom)
                    val src = Rect(
                        0, min(mOutput!!.height, dstTop), mOutput!!.width,
                        min(mOutput!!.height, dstBottom)
                    )
                    canvas.drawBitmap(mOutput!!, src, dst, mPaint)
                }
            }
        }

        private fun isContentFlowing(): Boolean {
            val scroller = TopBarGlass.sLastLiftedPublic()?.get() ?: return false
            if (!scroller.isShown) {
                return false
            }
            val probe = scroller
            if (probe is ViewGroup) {
                val g = probe
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c != null && c.visibility != View.VISIBLE) {
                        continue
                    }
                    if (TopBarGlass.isLifted(c)) {
                        continue
                    }
                    val loc = TopBarGlass.locOfPublic(c)
                    return loc[1] < 0
                }
                return false
            }
            return false
        }

        fun renderedHeight(): Int {
            if (mOutput == null || mOutput!!.isRecycled) {
                return 0
            }
            return (mOutput!!.height / BACKDROP_SCALE).roundToInt()
        }

        private fun buildProgressiveFade(w: Int, h: Int, glassHIn: Int, offsetYIn: Int) {
            var glassH = glassHIn
            var offsetY = offsetYIn
            if (mOutput == null || mOutput!!.isRecycled
                || mOutput!!.width != w || mOutput!!.height != h
            ) {
                if (mOutput != null && !mOutput!!.isRecycled) {
                    mOutput!!.recycle()
                }
                try {
                    mOutput = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                } catch (t: Throwable) {
                    LiquidGlassLog.logErr("topbar overlay alloc failed", t)
                    mOutput = null
                    return
                }
            }
            val outBmp = mOutput ?: return
            val c = Canvas(outBmp)
            c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

            glassH = max(1, (glassH / max(0.001f, BACKDROP_SCALE)).roundToInt())
            glassH = min(glassH, h)
            offsetY = max(0, min(offsetY, glassH))

            val stops = floatArrayOf(0.00f, 0.25f, 0.50f, 0.75f, 1.00f)
            val factors = floatArrayOf(1.0f, 232f / 255f, 176f / 255f, 96f / 255f, 0f)

            val bandTop = max(0, min(glassH, h))
            val bandH = max(0, h - bandTop)
            val baseRgb = if (mDark) 0x101214 else 0xFFFFFF

            if (mBackdrop != null && !mBackdrop!!.isRecycled) {
                val glassAlpha = if (mDark) 198 else 200

                val flat = if (bandH > 0) bandTop / h.toFloat() else 1f

                val mStops = floatArrayOf(
                    0f, max(0f, flat), max(flat, 0.25f + flat * 0.75f),
                    max(flat, 0.50f + flat * 0.50f), max(flat, 0.75f + flat * 0.25f), 1f
                )
                val mAlphas = intArrayOf(
                    glassAlpha, glassAlpha,
                    (glassAlpha * 232f / 255f).roundToInt(),
                    (glassAlpha * 176f / 255f).roundToInt(),
                    (glassAlpha * 96f / 255f).roundToInt(), 0
                )
                for (i in mStops.indices) {
                    if (i > 1 && mStops[i] <= mStops[i - 1]) {
                        mStops[i] = min(1f, mStops[i - 1] + 0.0001f)
                    }
                    mAlphas[i] = (mAlphas[i] shl 24) or 0xFFFFFF
                }
                c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
                val src = Rect(
                    0, 0, mBackdrop!!.width,
                    min(mBackdrop!!.height, h)
                )
                val dst = Rect(0, 0, w, h)
                c.drawBitmap(mBackdrop!!, src, dst, mPaint)
                val mask = Paint(Paint.ANTI_ALIAS_FLAG)
                mask.shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(), mAlphas, mStops,
                    Shader.TileMode.CLAMP
                )
                mask.xfermode = android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_IN
                )
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
                c.restore()
            }

            val veilTop = if (mDark) 48 else 58
            val veil = IntArray(stops.size)
            for (i in stops.indices) {
                veil[i] = ((veilTop * factors[i]).roundToInt() shl 24) or baseRgb
            }
            if (bandH > 0) {
                val flat = if (bandH > 0) bandTop / h.toFloat() else 1f
                val vStops = floatArrayOf(
                    0f, max(0f, flat), max(flat, 0.25f + flat * 0.75f),
                    max(flat, 0.50f + flat * 0.50f), max(flat, 0.75f + flat * 0.25f), 1f
                )
                val vColors = intArrayOf(veilTop, veilTop, veil[1], veil[2], veil[3], veil[4])
                for (i in vColors.indices) {
                    vColors[i] = (vColors[i] shl 24) or baseRgb
                }
                for (i in 1 until vStops.size) {
                    if (vStops[i] <= vStops[i - 1]) {
                        vStops[i] = min(1f, vStops[i - 1] + 0.0001f)
                    }
                }
                val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                veilPaint.shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(), vColors, vStops,
                    Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), veilPaint)
            }
            val solid = Paint(Paint.ANTI_ALIAS_FLAG)
            solid.color = (veilTop shl 24) or baseRgb
            c.drawRect(0f, 0f, w.toFloat(), bandTop.toFloat(), solid)

            if (!isContentFlowing()) {
                val hideTop = (bandTop + bandH * 0.30f).roundToInt()
                val fadeEnd = max(hideTop + 1, h)
                val pageRgb = if (mDark) 0x000000 else 0xFFFFFF
                val cover = Paint(Paint.ANTI_ALIAS_FLAG)
                cover.shader = LinearGradient(
                    0f, hideTop.toFloat(), 0f, fadeEnd.toFloat(),
                    intArrayOf((0xFF shl 24) or pageRgb, (0x00 shl 24) or pageRgb),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                c.drawRect(0f, hideTop.toFloat(), w.toFloat(), h.toFloat(), cover)
            }

            if (bandH > 4) {
                val cutTop = bandTop + (bandH * 0.45f).roundToInt()
                val cut = Paint(Paint.ANTI_ALIAS_FLAG)
                cut.xfermode = android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_OUT
                )
                cut.shader = LinearGradient(
                    0f, cutTop.toFloat(), 0f, (bandTop + bandH).toFloat(),
                    intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt()), floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawRect(0f, cutTop.toFloat(), w.toFloat(), (bandTop + bandH).toFloat(), cut)
            }

            val edge = Paint(Paint.ANTI_ALIAS_FLAG)
            edge.color = if (mDark) 0x30FFFFFF else 0x8CFFFFFF.toInt()
            val edgeH = max(1f, 0.8f * resources.displayMetrics.density * BACKDROP_SCALE)
            c.drawRect(0f, max(0f, bandTop - edgeH), w.toFloat(), bandTop.toFloat(), edge)

            val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
            highlight.color = if (mDark) 0x1FFFFFFF else 0x66FFFFFF
            c.drawRect(
                0f, 0f, w.toFloat(),
                max(1f, 1f * resources.displayMetrics.density * BACKDROP_SCALE), highlight
            )
        }

        private fun drawFeatheredBand(canvas: Canvas, w: Int, top: Int, bandH: Int) {
            if (bandH <= 0) {
                return
            }
            val bh = mBackdrop!!.height
            val bw = mBackdrop!!.width

            for (y in 0 until bandH) {
                var t = 1f - (y / bandH.toFloat())
                t = t * t * t
                val srcRow = min(bh - 1, (y * BACKDROP_SCALE).roundToInt())
                mPaint.shader = null
                mPaint.color = rowAverageColor(srcRow)
                mPaint.alpha = (255 * t * 0.98f).roundToInt()
                canvas.drawRect(0f, (top + y).toFloat(), w.toFloat(), (top + y + 1).toFloat(), mPaint)
            }
            mPaint.alpha = 255
        }

        private fun rowAverageColor(row: Int): Int {
            val w = mBackdrop!!.width
            val step = max(1, w / 24)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            var x = 0
            while (x < w) {
                val c = mBackdrop!!.getPixel(x, row)
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
                x += step
            }
            if (n == 0) {
                return 0xFFFFFFFF.toInt()
            }
            return 0xFF000000.toInt() or (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or (b / n).toInt()
        }

        private fun maybeCapture(w: Int, regionH: Int) {
            val now = SystemClock.uptimeMillis()
            if (now - mLastCaptureAt < OVERLAY_REFRESH_MS) {
                return
            }
            mLastCaptureAt = now

            val bw = max(1, (w * BACKDROP_SCALE).roundToInt())
            val bh = max(1, (regionH * BACKDROP_SCALE).roundToInt())
            if (mBackdrop == null || mBackdrop!!.isRecycled
                || mBackdrop!!.width != bw || mBackdrop!!.height != bh
            ) {
                recycle()
                try {
                    mBackdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                } catch (t: Throwable) {
                    LiquidGlassLog.logErr("topbar overlay alloc failed", t)
                    mBackdrop = null
                    return
                }
            }

            mCapturing = true

            val oldVis = visibility
            setVisibility(GONE)
            val bar = titleBarView()
            val barVisible = bar != null && bar.isShown
            val barVis = bar?.visibility ?: View.VISIBLE
            val hideBar = bar != null && bar !== mDecor
                    && bar.visibility == View.VISIBLE
            if (hideBar) {
                bar!!.visibility = INVISIBLE
            }

            val srcRoot = captureSource()
            var scroller = TopBarGlass.sLastLiftedPublic()?.get()
            if (scroller != null && (scroller.width <= 0 || scroller.height <= 0)) {
                scroller = null
            }

            if (scroller != null && !scroller.javaClass.name.contains("Scroll")
                && !scroller.javaClass.name.contains("RecyclerView")
            ) {
                val inner = TopBarGlass.realScrollerPublic(scroller)
                if (inner != null) {
                    scroller = inner
                } else {
                    LiquidGlassLog.log(
                        Log.VERBOSE, "topbar src: no inner scroller in "
                                + scroller.javaClass.name
                    )
                }
            }
            val capBmp = mBackdrop ?: return
            try {
                val canvas = Canvas(capBmp)
                canvas.scale(BACKDROP_SCALE, BACKDROP_SCALE)
                if (!barVisible) {
                    for (y in 0 until bh) {
                        for (x in 0 until bw) {
                            mBackdrop!!.setPixel(x, y, 0xFFF7F8FA.toInt())
                        }
                    }
                } else if (scroller != null) {
                    val glassRegionH = max(1, titleBarHeight())
                    val locRootStart = IntArray(2)
                    var locScroller: IntArray
                    mDecor.getLocationInWindow(locRootStart)
                    scroller.getLocationInWindow(IntArray(2).also { locScroller = it })
                    var located = false
                    try {
                        val curLoc = IntArray(2)
                        mDecor.getLocationInWindow(curLoc)

                        val abs = IntArray(2)
                        TopBarGlass.collectAbsOffsetPublic(mDecor, scroller, abs)
                        if (abs[0] != Int.MIN_VALUE) {
                            locScroller = intArrayOf(curLoc[0] + abs[0], curLoc[1] + abs[1])
                            located = true
                        }
                    } catch (ignored: Throwable) {
                    }

                    var dy = (locScroller[1] - locRootStart[1] - top).toFloat()

                    val listScroll = scrollOffsetOf(scroller)
                    if (hideBar) {
                        bar!!.visibility = barVis
                    }

                    if (abs(dy) > OVERLAY_ALIGN_TOLERANCE_PX) {
                        dy = 0f
                    }

                    mBackdrop!!.eraseColor(0x00000000)
                    canvas.translate(0f, -dy - listScroll * BACKDROP_SCALE)

                    canvas.translate(0f, -scroller.paddingTop * BACKDROP_SCALE)

                    canvas.translate(0f, -glassRegionH * BACKDROP_SCALE)

                    canvas.translate(0f, top * BACKDROP_SCALE)
                    canvas.save()
                    try {
                        scroller.draw(canvas)
                    } catch (ignored: Throwable) {
                    }
                    canvas.restore()
                } else {
                    mBackdrop!!.eraseColor(0x00000000)
                    val locRoot = IntArray(2)
                    val locOnScreen = IntArray(2)
                    mDecor.getLocationInWindow(locRoot)
                    mDecor.getLocationOnScreen(locOnScreen)
                    val rootTop = locRoot[1]
                    val offsetY = locOnScreen[1] - locRoot[1] + barOffsetY()
                    canvas.translate(0f, -offsetY.toFloat())

                    for (child in captureChain()) {
                        canvas.save()
                        canvas.translate(child.left.toFloat(), (child.top - rootTop).toFloat())
                        try {
                            child.draw(canvas)
                        } catch (ignored: Throwable) {
                        }
                        canvas.restore()
                    }
                }
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("topbar overlay capture failed", t)
            } finally {
                if (hideBar) {
                    bar!!.visibility = barVis
                }
                setVisibility(oldVis)
                mCapturing = false
            }

            try {
                fillMissingRows()

                MiuixGlassPipeline.process(mBackdrop, mStrongBlurDp, mDark)
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("topbar overlay process failed", t)
            }
            try {
                buildProgressiveFade(
                    bw, bh,
                    (titleBarHeight() * BACKDROP_SCALE).roundToInt(),
                    (top * BACKDROP_SCALE).roundToInt()
                )
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("topbar fade build failed", t)
            }


            if (DEBUG_DUMP_BACKDROP) {
                try {
                    val dir = context.getExternalFilesDir(null)
                    if (dir != null) {
                        if (!dir.exists()) {
                            dir.mkdirs()
                        }
                        val fos = java.io.FileOutputStream(
                            java.io.File(dir, "topbar_backdrop.png")
                        )
                        mBackdrop!!.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        fos.close()
                    }
                } catch (t: Throwable) {
                    LiquidGlassLog.logErr("topbar dump failed", t)
                }
            }
            if (!mLogged) {
                mLogged = true
                Log.i("BetterHeybox", "topbar overlay backdrop " + bw + "x" + bh)
            }
        }

        private fun fillMissingRows() {
            if (mBackdrop == null || mBackdrop!!.isRecycled) {
                return
            }
            val w = mBackdrop!!.width
            val h = mBackdrop!!.height
            val row = IntArray(w)
            var first: IntArray? = null
            var lastFilled = -1
            for (y in 0 until h) {
                mBackdrop!!.getPixels(row, 0, w, 0, y, w, 1)
                var has = false
                var i = 0
                while (i < w) {
                    if ((row[i] ushr 24) != 0) {
                        has = true
                        break
                    }
                    i += 4
                }
                if (!has) {
                    continue
                }
                if (first == null) {
                    first = row.clone()

                    for (fill in 0 until y) {
                        mBackdrop!!.setPixels(first, 0, w, 0, fill, w, 1)
                    }
                } else if (lastFilled >= 0 && y > lastFilled + 1) {
                    for (fill in lastFilled + 1 until y) {
                        mBackdrop!!.setPixels(first, 0, w, 0, fill, w, 1)
                    }
                }
                first = row.clone()
                lastFilled = y
            }
            if (first == null) {
                mBackdrop!!.eraseColor(if (mDark) 0xFF181A1C.toInt() else 0xFFF7F8FA.toInt())
                return
            }
            for (fill in lastFilled + 1 until h) {
                mBackdrop!!.setPixels(first, 0, w, 0, fill, w, 1)
            }
        }

        private fun scrollOffsetOf(scroller: View?): Int {
            if (scroller == null) {
                return 0
            }
            return try {
                val getLm = scroller.javaClass.getMethod("getLayoutManager")
                val lm = getLm.invoke(scroller)
                if (lm != null) {
                    val m = lm.javaClass.getMethod("computeVerticalScrollOffset")
                    val v = m.invoke(lm)
                    if (v is Int) {
                        return max(0, v)
                    }
                }
                max(0, scroller.scrollY)
            } catch (ignored: Throwable) {
                try {
                    max(0, scroller.scrollY)
                } catch (t: Throwable) {
                    0
                }
            }
        }

        private fun barOffsetY(): Int {
            val bar = titleBarView()
            val barH: Int
            if (bar != null && bar.height > 0) {
                barH = bar.height
            } else {
                barH = (22f * resources.displayMetrics.density).roundToInt()
            }
            return max(BACKDROP_ROWS, barH * 2)
        }

        private fun titleBarView(): View? {
            for (v in TopBarGlass.ACTIVE.keys) {
                if (v != null && v.isAttachedToWindow) {
                    return v
                }
            }
            return null
        }

        private fun captureSource(): View {
            return try {
                val content = mDecor.findViewById<View>(android.R.id.content)
                if (content != null && content.visibility == View.VISIBLE) {
                    content
                } else {
                    mDecor
                }
            } catch (ignored: Throwable) {
                mDecor
            }
        }

        private fun captureRoot(): View? {
            var content: View? = null
            try {
                content = mDecor.findViewById<View>(android.R.id.content)
            } catch (ignored: Throwable) {
            }
            if (content == null || content.visibility != View.VISIBLE) {
                return content ?: mDecor
            }
            if (content !is ViewGroup) {
                return content
            }
            val group = content
            var best: View? = null
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child == null || child === this || child.visibility != View.VISIBLE
                    || child.top > 8 || child.width <= 0
                ) {
                    continue
                }
                if (best == null || child.width * child.height
                    > best.width * best.height
                ) {
                    best = child
                }
            }
            return best ?: content
        }

        private fun captureChain(): ArrayList<View> {
            val out = ArrayList<View>()
            val content = captureRoot()
            if (content !is ViewGroup) {
                out.add(content!!)
                return out
            }
            val group = content
            val rootW = if (mDecor.width > 0) mDecor.width
            else resources.displayMetrics.widthPixels
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child == null || child === this || child.visibility != View.VISIBLE) {
                    continue
                }
                if (child.width * 10 < rootW * 9 || child.height < 200) {
                    continue
                }
                out.add(child)
            }
            if (out.isEmpty()) {
                out.add(content)
            }
            return out
        }

        private fun recycle() {
            if (mBackdrop != null && !mBackdrop!!.isRecycled) {
                mBackdrop!!.recycle()
            }
            mBackdrop = null
            if (mOutput != null && !mOutput!!.isRecycled) {
                mOutput!!.recycle()
            }
            mOutput = null
        }
    }

    fun sLastLiftedPublic(): WeakReference<View>? = sLastLifted

    fun isLifted(view: View): Boolean = LIFTED.containsKey(view)

    fun locOfPublic(v: View): IntArray = locOf(v)

    fun realScrollerPublic(v: View): View? = realScroller(v)

    fun collectAbsOffsetPublic(ancestor: View, target: View, out: IntArray) {
        collectAbsOffset(ancestor, target, out)
    }
}
