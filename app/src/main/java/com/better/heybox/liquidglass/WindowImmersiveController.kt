package com.better.heybox.liquidglass

import android.app.Activity
import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import java.util.Collections
import java.util.WeakHashMap

internal object WindowImmersiveController {

    private val STATES: MutableMap<Activity, State> =
        Collections.synchronizedMap(WeakHashMap<Activity, State>())
    private val ACTIVITIES: MutableMap<Activity, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    @JvmStatic
    fun refresh() {
        val activities: ArrayList<Activity> =
            synchronized(ACTIVITIES) { ArrayList(ACTIVITIES.keys) }
        for (activity in activities) {
            if (activity != null && !activity.isFinishing()
                && !activity.isDestroyed()
            ) {
                apply(activity)
            }
        }
    }

    @JvmStatic
    fun apply(activity: Activity) {
        if (activity == null) {
            return
        }
        ACTIVITIES[activity] = true
        try {
            val window = activity.getWindow()
            if (window == null) {
                return
            }
            if (!GlassConfig.immersiveGestureNavigation) {
                restore(activity, window)
                return
            }
            val existing = STATES[activity]
            val fresh = existing == null
            val state = existing ?: State(window).also { STATES[activity] = it }
            val decor = window.getDecorView()
            if (decor != null) {
                decor.setSystemUiVisibility(
                    state.systemUiVisibility or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }
            val content = if (decor == null) {
                null
            } else {
                decor.findViewById<View>(android.R.id.content)
            }
            if (content != null) {
                content.setOnApplyWindowInsetsListener(DROP_NAV_INSET)
                if (fresh) {
                    content.requestApplyInsets()
                }
            }
            window.setNavigationBarColor(Color.TRANSPARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(Color.TRANSPARENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false)
            }
            window.setFlags(
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            )
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr(
                WindowImmersiveController::class.java.simpleName, t
            )
        }
    }

    private fun restore(activity: Activity, window: Window) {
        val state = STATES.remove(activity) ?: return
        try {
            val decor = window.getDecorView()
            if (decor != null) {
                decor.setSystemUiVisibility(state.systemUiVisibility)
                val content = decor.findViewById<View>(android.R.id.content)
                if (content != null) {
                    content.setOnApplyWindowInsetsListener(null)
                    content.requestApplyInsets()
                }
            }
            if (state.drawsSystemBarBackgrounds) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
            if (state.translucentNavigation) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            }
            window.setNavigationBarColor(state.navigationBarColor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(state.navigationBarDividerColor)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(state.navigationBarContrastEnforced)
            }
        } catch (t: Throwable) {
            LiquidGlassLog.logErr(
                WindowImmersiveController::class.java.simpleName, t
            )
        }
    }

    private val DROP_NAV_INSET = View.OnApplyWindowInsetsListener { v, insets ->
        v.onApplyWindowInsets(withoutNavInset(insets))
    }

    private fun withoutNavInset(insets: WindowInsets): WindowInsets {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return withoutNavInsetApi30(insets)
            }
            return insets.replaceSystemWindowInsets(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), 0
            )
        } catch (t: Throwable) {
            return insets
        }
    }

    private fun withoutNavInsetApi30(insets: WindowInsets): WindowInsets {
        val nav = insets.getInsets(WindowInsets.Type.navigationBars())
        return WindowInsets.Builder(insets)
            .setInsets(
                WindowInsets.Type.navigationBars(),
                Insets.of(nav.left, nav.top, nav.right, 0)
            )
            .build()
    }

    private class State(window: Window) {
        val systemUiVisibility: Int =
            window.getDecorView()?.getSystemUiVisibility() ?: 0
        val navigationBarColor: Int = window.getNavigationBarColor()
        val navigationBarDividerColor: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.getNavigationBarDividerColor()
            } else {
                Color.TRANSPARENT
            }
        val navigationBarContrastEnforced: Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && window.isNavigationBarContrastEnforced()
        private val flags = window.getAttributes().flags
        val drawsSystemBarBackgrounds: Boolean =
            (flags and WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
        val translucentNavigation: Boolean =
            (flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION) != 0
    }
}
