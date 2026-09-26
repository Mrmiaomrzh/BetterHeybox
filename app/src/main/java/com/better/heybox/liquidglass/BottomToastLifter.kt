package com.better.heybox.liquidglass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import java.lang.reflect.Method

object BottomToastLifter {

    private const val NOTIFICATION_VIEW =
        "com.max.hbcommon.component.inappnotification.Notification"
    private const val GAP_DP = 12f
    private const val MAX_FIXES = 10

    @JvmStatic
    fun install() {
        var armed = arm("android.view.WindowManagerImpl", false)
        armed = armed or arm("android.view.WindowManagerGlobal", false)
        armed = armed or arm("android.view.ViewRootImpl", true)
        if (!armed) {
            LiquidGlassLog.log(
                Log.WARN, "toast lifter: no addView hook point available"
            )
        }
        armDiagProbes()
        armInflateProbe()
    }

    private fun arm(className: String, setView: Boolean): Boolean {
        try {
            val impl = Class.forName(className)
            var target: Method? = null
            for (m in impl.getDeclaredMethods()) {
                if (setView) {
                    if (!m.getName().equals("setView")
                        || m.getParameterTypes().size < 3
                        || m.getParameterTypes()[0] != View::class.java
                        || m.getParameterTypes()[1] != WindowManager.LayoutParams::class.java
                    ) {
                        continue
                    }
                } else {
                    if (!m.getName().equals("addView")
                        || m.getParameterTypes().size < 2
                        || m.getParameterTypes()[0] != View::class.java
                        || m.getParameterTypes()[1] != ViewGroup.LayoutParams::class.java
                    ) {
                        continue
                    }
                }
                target = m
                break
            }
            val hookTarget = target
                ?: throw NoSuchMethodException(className + " target method")
            LiquidGlassHookBridge.hookExecutable(hookTarget) { chain ->
                try {
                    preLift(chain.getArg(0), chain.getArg(1))
                } catch (ignored: Throwable) {
                }
                val result = chain.proceed()
                try {
                    watchAfterAdd(chain.getArg(0), chain.getArg(1))
                } catch (ignored: Throwable) {
                }
                result
            }
            LiquidGlassLog.log(Log.INFO, "bottom toast lifter armed on " + className)
            return true
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("arm " + className + " failed", t)
            return false
        }
    }

    private fun preLift(viewObj: Any?, paramsObj: Any?) {
        if (viewObj !is View
            || paramsObj !is WindowManager.LayoutParams
        ) {
            return
        }
        val view: View = viewObj
        val lp: WindowManager.LayoutParams = paramsObj
        val inApp = isBottomInAppNotification(view, lp)
        val toast = lp.type == WindowManager.LayoutParams.TYPE_TOAST &&
                (lp.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
        if (!inApp && !toast) {
            return
        }
        val lift = windowLift(view, inApp)
        if (lift <= 0) {
            return
        }
        if (lp.y >= lift) {
            return
        }
        lp.y = lift
        LiquidGlassLog.log(Log.INFO, "window notification preLift y=" + lift)
    }

    private fun watchAfterAdd(viewObj: Any?, paramsObj: Any?) {
        if (viewObj !is View
            || paramsObj !is WindowManager.LayoutParams
        ) {
            return
        }
        val view: View = viewObj
        val lp: WindowManager.LayoutParams = paramsObj
        val inApp = isBottomInAppNotification(view, lp)
        val toast = lp.type == WindowManager.LayoutParams.TYPE_TOAST
        if (!inApp && !toast) {
            return
        }
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            private var fixes = 0

            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                         ol: Int, ot: Int, or2: Int, ob: Int) {
                if (fixes >= MAX_FIXES) {
                    v.removeOnLayoutChangeListener(this)
                    return
                }
                try {
                    if (alignAboveGlass(v, inApp)) {
                        fixes++
                    }
                } catch (ignored: Throwable) {
                }
            }
        })
    }

    private fun isBottomInAppNotification(
        view: View, lp: WindowManager.LayoutParams
    ): Boolean {
        return NOTIFICATION_VIEW == view.javaClass.name &&
                lp.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL &&
                (lp.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
    }

    private fun alignAboveGlass(container: View, inApp: Boolean): Boolean {
        val host = LiquidGlassInstaller.activeGlassHost()
        if (host == null || !container.isAttachedToWindow()
            || container.getWidth() <= 0 || container.getHeight() <= 0
        ) {
            return false
        }
        val pill = arrayOfNulls<View>(1)
        val pillTop = IntArray(1)
        val leaf = arrayOfNulls<View>(1)
        val leafTop = IntArray(1)
        findPill(container, 0, pill, pillTop, leaf, leafTop)
        if (pill[0] == null) {
            pill[0] = leaf[0]
            pillTop[0] = leafTop[0]
        }
        val pillView = pill[0]
        if (pillView == null || pillView.getHeight() <= 0) {
            return false
        }
        val containerLoc = IntArray(2)
        container.getLocationOnScreen(containerLoc)
        val pillBottom = containerLoc[1] + pillTop[0] + pillView.getHeight()
        val hostLoc = IntArray(2)
        host.getLocationOnScreen(hostLoc)
        val target = hostLoc[1] - gapPx(host)
        val delta = target - pillBottom
        if (Math.abs(delta) < 1) {
            return false
        }
        if (!inApp && delta >= 0) {
            return false
        }
        if (!applyWindowLift(container, -delta)) {
            return false
        }
        LiquidGlassLog.log(Log.INFO, "bottom notification aligned, delta=" + delta)
        return true
    }

    private fun applyWindowLift(container: View, upwardShift: Int): Boolean {
        try {
            val cur = container.getLayoutParams()
            if (cur !is WindowManager.LayoutParams) {
                return false
            }
            val wlp: WindowManager.LayoutParams = cur
            val newY: Int
            if ((wlp.gravity and Gravity.BOTTOM) == Gravity.BOTTOM) {
                newY = Math.max(wlp.y + upwardShift, 0)
            } else {
                newY = wlp.y - upwardShift
            }
            if (newY == wlp.y) {
                return false
            }
            wlp.y = newY
            val wm: Any? = container.getContext().getSystemService(Context.WINDOW_SERVICE)
            if (wm !is WindowManager) {
                return false
            }
            wm.updateViewLayout(container, wlp)
            return true
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("window lift failed", t)
            return false
        }
    }

    private fun findPill(view: View, top: Int,
                         pill: Array<View?>, pillTop: IntArray,
                         leaf: Array<View?>, leafTop: IntArray) {
        if (view.getVisibility() == View.GONE) {
            return
        }
        val backgrounded = view.getBackground() != null
        val bottom = top + view.getHeight()
        if (backgrounded) {
            val current = pill[0]
            if (current == null || view.getHeight() < current.getHeight()
                || (view.getHeight() == current.getHeight()
                        && bottom > pillTop[0] + current.getHeight())
            ) {
                pill[0] = view
                pillTop[0] = top
            }
        }
        if (view is ViewGroup) {
            val group: ViewGroup = view
            for (i in 0 until group.getChildCount()) {
                val child = group.getChildAt(i)
                findPill(child, top + child.getTop(), pill, pillTop, leaf, leafTop)
            }
        } else {
            val cur = leaf[0]
            if (cur == null || bottom > leafTop[0] + cur.getHeight()) {
                leaf[0] = view
                leafTop[0] = top
            }
        }
    }

    private fun windowLift(view: View, inApp: Boolean): Int {
        val host = LiquidGlassInstaller.activeGlassHost()
        if (host == null) {
            return 0
        }
        val hostLoc = IntArray(2)
        host.getLocationOnScreen(hostLoc)
        val anchorBottom = anchorBottom(view, inApp)
        if (anchorBottom <= 0) {
            return 0
        }
        return Math.max(anchorBottom - hostLoc[1] + gapPx(host), 0)
    }

    private fun anchorBottom(view: View, inApp: Boolean): Int {
        if (inApp) {
            val activity = activityOf(view.getContext())
            if (activity == null || activity.getWindow() == null) {
                return 0
            }
            val decor = activity.getWindow().getDecorView()
            if (decor.getHeight() <= 0) {
                return 0
            }
            val decorLoc = IntArray(2)
            decor.getLocationOnScreen(decorLoc)
            return decorLoc[1] + decor.getHeight()
        }
        return realDisplayBottom(view.getContext())
    }

    @Suppress("DEPRECATION")
    private fun realDisplayBottom(context: Context): Int {
        try {
            val wm: Any? = context.getSystemService(Context.WINDOW_SERVICE)
            if (wm !is WindowManager) {
                return 0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return wm.getMaximumWindowMetrics().getBounds().bottom
            }
            val display: Display? = wm.getDefaultDisplay()
            if (display == null) {
                return 0
            }
            val dm = DisplayMetrics()
            display.getRealMetrics(dm)
            return dm.heightPixels
        } catch (t: Throwable) {
            return 0
        }
    }

    private fun gapPx(host: View): Int {
        return Math.round(host.getResources().getDisplayMetrics().density * GAP_DP)
    }

    private fun activityOf(context: Context): Activity? {
        var cur: Context? = context
        var hops = 0
        while (cur != null && hops < 10) {
            if (cur is Activity) {
                return cur
            }
            if (cur !is ContextWrapper) {
                return null
            }
            cur = cur.baseContext
            hops++
        }
        return null
    }

    private fun armDiagProbes() {
        try {
            val show = Class.forName("android.widget.Toast").getMethod("show")
            LiquidGlassHookBridge.hookExecutable(show) { chain ->
                val result = chain.proceed()
                try {
                    val thiz = chain.getThisObject()
                    if (thiz is Toast) {
                        val v = thiz.getView()
                        LiquidGlassLog.log(
                            Log.INFO,
                            "toast shown: " +
                                    (if (v == null) "text" else v.javaClass.name)
                        )
                    }
                } catch (ignored: Throwable) {
                }
                result
            }
        } catch (ignored: Throwable) {
        }
        try {
            val ntf = Class.forName(NOTIFICATION_VIEW)
            for (c in ntf.getDeclaredConstructors()) {
                val ps = c.getParameterTypes()
                if (ps.size == 3 && ps[0] == Context::class.java) {
                    LiquidGlassHookBridge.hookExecutable(c) { chain ->
                        chain.proceed()
                        try {
                            LiquidGlassLog.log(
                                Log.INFO, "in-app notification created"
                            )
                        } catch (ignored: Throwable) {
                        }
                        null
                    }
                    break
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    @Volatile
    private var sToastLayoutId1: Int = 0

    @Volatile
    private var sToastLayoutId2: Int = 0

    @Volatile
    private var sResolveAttempts: Int = 0
    private val TOAST_LAYOUTS: Array<String> = arrayOf(
        "layout_toast_click_bottom_hint", "toast_bottom_hint"
    )

    private fun armInflateProbe() {
        try {
            val inflate = LayoutInflater::class.java.getMethod(
                "inflate",
                Int::class.javaPrimitiveType,
                ViewGroup::class.java,
                Boolean::class.javaPrimitiveType
            )
            LiquidGlassHookBridge.hookExecutable(inflate) { chain ->
                try {
                    val resId = chain.getArg(0) as Int
                    if (sToastLayoutId1 == 0 && sToastLayoutId2 == 0
                        && sResolveAttempts < 5
                    ) {
                        resolveToastLayoutIds(chain.getThisObject())
                    }
                    if (resId != 0
                        && (resId == sToastLayoutId1
                        || resId == sToastLayoutId2)
                    ) {
                        val result = chain.proceed()
                        try {
                            watchInflatedToast(result)
                        } catch (ignored: Throwable) {
                        }
                        return@hookExecutable result
                    }
                } catch (ignored: Throwable) {
                }
                chain.proceed()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun resolveToastLayoutIds(inflaterObj: Any?) {
        sResolveAttempts++
        try {
            if (inflaterObj !is LayoutInflater) {
                return
            }
            val ctx = inflaterObj.getContext()
            if (ctx == null) {
                return
            }
            for (name in TOAST_LAYOUTS) {
                val id = ctx.getResources().getIdentifier(
                    name, "layout", ctx.getPackageName()
                )
                if (id != 0 && sToastLayoutId1 == 0) {
                    sToastLayoutId1 = id
                } else if (id != 0 && id != sToastLayoutId1 && sToastLayoutId2 == 0) {
                    sToastLayoutId2 = id
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun watchInflatedToast(viewObj: Any?) {
        if (viewObj !is View) {
            return
        }
        val view: View = viewObj
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                         ol: Int, ot: Int, or2: Int, ob: Int) {
                try {
                    if (v.getVisibility() == View.VISIBLE
                        && v.isAttachedToWindow()
                        && v.getWidth() > 0 && v.getHeight() > 0
                    ) {
                        liftInWindow(v)
                    }
                } catch (ignored: Throwable) {
                }
            }
        })
    }

    private fun liftInWindow(container: View) {
        val host = LiquidGlassInstaller.activeGlassHost()
        if (host == null || container.getWidth() <= 0 || container.getHeight() <= 0) {
            return
        }
        val pill = arrayOfNulls<View>(1)
        val pillTop = IntArray(1)
        val leaf = arrayOfNulls<View>(1)
        val leafTop = IntArray(1)
        findPill(container, 0, pill, pillTop, leaf, leafTop)
        if (pill[0] == null) {
            pill[0] = leaf[0]
            pillTop[0] = leafTop[0]
        }
        val pillView = pill[0]
        if (pillView == null || pillView.getHeight() <= 0) {
            return
        }
        val containerLoc = IntArray(2)
        container.getLocationOnScreen(containerLoc)
        val pillBottom = containerLoc[1] + pillTop[0] + pillView.getHeight()
        val hostLoc = IntArray(2)
        host.getLocationOnScreen(hostLoc)
        val target = hostLoc[1] - gapPx(host)
        val delta = target - pillBottom
        if (delta > -1) {
            return
        }
        if (Math.abs(delta - pillView.getTranslationY()) < 1f) {
            return
        }
        pillView.setTranslationY(delta.toFloat())
    }
}
