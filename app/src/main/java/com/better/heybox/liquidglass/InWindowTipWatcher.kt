package com.better.heybox.liquidglass

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

internal object InWindowTipWatcher {

    private const val TIP_ID_NAME = "vg_update_tips"
    private const val SCAN_INTERVAL_MS = 2000L

    private const val RESCAN_MIN_INTERVAL_MS = 1000L

    private const val SCAN_NODE_BUDGET = 2000

    @Volatile
    private var sLastScanAt = 0L

    private val EMPTY_DECOR_REF: WeakReference<View> = WeakReference<View>(null)

    @Volatile
    private var sWatchedDecor: WeakReference<View> = EMPTY_DECOR_REF

    @Volatile
    private var sTipId: Int = 0
    private val EMPTY_TIP_REF: WeakReference<View> = WeakReference<View>(null)

    @Volatile
    private var sTipRef: WeakReference<View> = EMPTY_TIP_REF
    private val sWatched: MutableMap<View, Boolean> =
        Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var sGlobalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    @Volatile
    private var sScanRunnable: Runnable? = null

    @JvmStatic
    fun start(activity: Activity?) {
        if (activity == null) {
            return
        }
        try {
            val decor = activity.getWindow().getDecorView()
            if (decor == null || sWatchedDecor.get() == decor) {
                return
            }
            stop()
            sWatchedDecor = WeakReference<View>(decor)
            sTipId = activity.getResources().getIdentifier(
                TIP_ID_NAME, "id", activity.getPackageName()
            )
            if (sTipId == 0) {
                LiquidGlassLog.log(
                    Log.WARN, "in-window tip id not found: " + TIP_ID_NAME
                )
                return
            }
            sTipRef = EMPTY_TIP_REF
            sLastScanAt = 0L
            scan(decor)
            lateinit var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener
            globalLayoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        try {
                            if (activity.isFinishing()
                                || activity.isDestroyed()
                            ) {
                                stop()
                                return
                            }
                            val known = sTipRef.get()
                            if (known != null && known.isAttachedToWindow()) {
                                return
                            }
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - sLastScanAt < RESCAN_MIN_INTERVAL_MS) {
                                return
                            }
                            sLastScanAt = now
                            scan(decor)
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            sGlobalLayoutListener = globalLayoutListener
            decor.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener)
            val scanRunnable = object : Runnable {
                    override fun run() {
                        try {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                stop()
                                return
                            }
                            sLastScanAt = android.os.SystemClock.uptimeMillis()
                            scan(decor)
                        } catch (ignored: Throwable) {
                        }
                        if (sScanRunnable === this) {
                            decor.postDelayed(this, SCAN_INTERVAL_MS)
                        }
                    }
                }
            sScanRunnable = scanRunnable
            decor.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
            LiquidGlassLog.log(
                Log.INFO, "in-window tip watcher started id=" + sTipId
            )
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("in-window tip watcher failed", t)
        }
    }

    @JvmStatic
    fun stop() {
        val decor = sWatchedDecor.get()
        val globalLayoutListener = sGlobalLayoutListener
        val scanRunnable = sScanRunnable
        sGlobalLayoutListener = null
        sScanRunnable = null
        if (decor != null) {
            try {
                if (globalLayoutListener != null) {
                    val observer = decor.viewTreeObserver
                    if (observer.isAlive) {
                        observer.removeOnGlobalLayoutListener(globalLayoutListener)
                    }
                }
                if (scanRunnable != null) {
                    decor.removeCallbacks(scanRunnable)
                }
            } catch (ignored: Throwable) {
            }
        }
        sWatchedDecor = EMPTY_DECOR_REF
        sTipRef = EMPTY_TIP_REF
        sTipId = 0
        sLastScanAt = 0L
        sWatched.clear()
        sLiftState.clear()
    }

    private fun scan(root: View) {
        com.better.heybox.ModuleStats.tipWatcherScans.incrementAndGet()
        scan(root, SCAN_NODE_BUDGET)
    }

    private fun scan(view: View, budget: Int): Int {
        if (budget < 0) {
            return budget
        }
        if (view.getId() == sTipId) {
            watch(view)
        }
        if (view !is ViewGroup) {
            return budget - 1
        }
        val group: ViewGroup = view
        var left = budget - 1
        var i = 0
        while (i < group.getChildCount() && left >= 0) {
            left = scan(group.getChildAt(i), left)
            i++
        }
        return left
    }

    private fun watch(tip: View) {
        sTipRef = WeakReference<View>(tip)
        if (sWatched.put(tip, true) != null) {
            return
        }
        tip.getViewTreeObserver().addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    try {
                        if (tip.getVisibility() == View.VISIBLE
                            && tip.isAttachedToWindow()
                            && tip.getWidth() > 0
                            && tip.getHeight() > 0
                        ) {
                            liftAboveGlass(tip)
                        }
                    } catch (ignored: Throwable) {
                    }
                    return true
                }
            }
        )
        LiquidGlassLog.log(
            Log.INFO,
            "update tips view watched: " + tip.getResources()
                .getResourceName(sTipId)
        )
    }

    private val sLiftState: MutableMap<View, FloatArray> =
        Collections.synchronizedMap(WeakHashMap<View, FloatArray>())

    private fun liftAboveGlass(tip: View) {
        val host = LiquidGlassInstaller.activeGlassHost()
        if (host == null) {
            return
        }
        val bottom = layoutBottomOnScreen(tip)
        if (bottom <= 0) {
            return
        }
        val hostLoc = IntArray(2)
        host.getLocationOnScreen(hostLoc)
        val target = hostLoc[1] - Math.round(
            host.getResources().getDisplayMetrics().density * 12f
        )
        val lift = Math.min((target - bottom).toFloat(), 0f)
        var st = sLiftState[tip]
        if (st == null) {
            st = floatArrayOf(Float.NaN)
            sLiftState[tip] = st
        }
        val current = tip.getTranslationY()
        val want: Float
        if (!st[0].isNaN() && Math.abs(current - st[0]) < 0.5f) {
            want = if (Math.abs(st[0] - lift) >= 0.5f) lift else st[0]
        } else {
            want = current + lift
        }
        st[0] = want
        if (Math.abs(want - current) >= 0.5f) {
            tip.setTranslationY(want)
        }
    }

    private fun layoutBottomOnScreen(view: View): Int {
        val root = rootOf(view)
        if (root == null) {
            return 0
        }
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        var top = 0
        var cur = view
        var p = view.getParent()
        var hops = 0
        while (cur != root && p is View && hops < 60) {
            top += cur.getTop()
            cur = p
            p = cur.getParent()
            hops++
        }
        if (cur != root) {
            return 0
        }
        return loc[1] + top + view.getHeight()
    }

    private fun rootOf(view: View): View? {
        var cur = view
        var p = view.getParent()
        var hops = 0
        while (p is View && hops < 60) {
            cur = p
            p = cur.getParent()
            hops++
        }
        return cur
    }
}
