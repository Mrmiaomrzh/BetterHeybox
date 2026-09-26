@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "DEPRECATION")

package com.better.heybox.liquidglass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RelativeLayout
import android.widget.Space
import android.widget.TextView
import com.better.heybox.App
import com.better.heybox.GlassProvider
import com.better.heybox.HeyboxPrefs
import com.better.heybox.hooks.BottomTabHook
import com.example.liquidglass.BackdropLuminanceMeter
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassTabBar
import com.example.liquidglass.LiquidGlassView
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

object LiquidGlassInstaller {

    private const val ID_ROOT = "vg_main_root"
    private const val ID_BAR = "rg_main"
    private const val ID_TIPS = "vg_tips"
    private const val ID_MID_TAB = "vg_mid_tab"
    private const val ID_CONTENT = "fl_container"
    private const val ID_VIDEO_FULL = "vg_fullscreen_video_container"

    @JvmStatic
    fun isGlassEnabled(activity: Activity): Boolean {
        try {
            HeyboxPrefs.init(activity)
            return HeyboxPrefs.getBoolean(
                App.KEY_LIQUID_GLASS, true)
        } catch (t: Throwable) {
            return true
        }
    }

    @JvmStatic
    fun isGlassBarActive(): Boolean {
        try {
            val bar: View? = sTabBarRef.get()
            return bar != null && bar.isAttachedToWindow()
        } catch (t: Throwable) {
            return false
        }
    }

    private fun readPublishHidden(context: Context): Boolean {
        try {
            HeyboxPrefs.init(context)
            return HeyboxPrefs.getBoolean(
                App.KEY_HIDE_ADD, false)
        } catch (ignored: Throwable) {
            return false
        }
    }

    @JvmStatic
    fun applyImmersive(activity: Activity) {
        try {
            GlassConfig.load(activity)
            WindowImmersiveController.apply(activity)
        } catch (ignored: Throwable) {
        }
    }

    @Volatile
    private var sMainRef: WeakReference<Activity>? = null

    private fun mainActivity(): Activity? {
        val ref = sMainRef
        val main = ref?.get()
        return if (main != null && !main.isFinishing() && !main.isDestroyed()) main else null
    }

    @JvmStatic
    fun applyGlassEnabled(activity: Activity) {
        try {
            if (GlassProvider.prefersHbmod(activity)) {
                return
            }
            val main = mainActivity()
            if (main == null) {
                return
            }
            if (isGlassEnabled(activity)) {
                scheduleInstall(main)
            } else {
                uninstallGlass(main)
                applyImmersive(main)
                applyClassicImmersive(main)
            }
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("apply glass enabled failed", t)
        }
    }

    @JvmStatic
    fun scheduleInstall(activity: Activity) {
        sMainRef = WeakReference(activity)
        if (GlassProvider.prefersHbmod(activity)) {
            return
        }
        val decor = activity.getWindow().getDecorView()
        decor.post {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return@post
                }
                applyImmersive(activity)
                if (!isGlassEnabled(activity)) {
                    applyClassicImmersive(activity)
                    return@post
                }
                val root = findViewByName<ViewGroup>(activity, ID_ROOT)
                if (root == null) {
                    LiquidGlassLog.log(Log.WARN,
                        "root view " + ID_ROOT + " not found, retry in 200ms")
                    val gen = sSyncGen
                    decor.postDelayed({
                        if (!activity.isFinishing() && !activity.isDestroyed()
                            && gen == sSyncGen && isGlassEnabled(activity)
                        ) {
                            val r = findViewByName<ViewGroup>(activity, ID_ROOT)
                            if (r != null) {
                                install(activity, r)
                            }
                        }
                    }, 200L)
                    return@post
                }
                if (root.findViewWithTag<View>(LiquidGlassHostLayout.GLASS_TAG) != null) {
                    return@post
                }
                install(activity, root)
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("scheduleInstall failed", t)
            }
        }
    }

    private class BarSnapshot {
        var barIndex = -1
        var tipsIndex = -1
        var midIndex = -1
        var barLp: ViewGroup.LayoutParams? = null
        var tipsLp: ViewGroup.LayoutParams? = null
        var midLp: ViewGroup.LayoutParams? = null
        var barBackground: Drawable? = null
        var barPadLeft = 0
        var barPadTop = 0
        var barPadRight = 0
        var barPadBottom = 0
        var contentAboveRule = 0
        var legacyShadow: View? = null
    }

    @Volatile
    private var sSnapshot: BarSnapshot? = null

    @Volatile
    private var sSyncGen = 0

    private fun uninstallGlass(activity: Activity) {
        try {
            val root = findViewByName<ViewGroup>(activity, ID_ROOT)
            if (root == null
                || root.findViewWithTag<View>(LiquidGlassHostLayout.GLASS_TAG) == null
            ) {
                return
            }
            val snap = sSnapshot
            sSyncGen++
            cancelGlassAnimators()
            val bar = findViewByName<ViewGroup>(activity, ID_BAR)
            val tips = findViewByName<ViewGroup>(activity, ID_TIPS)
            val midTab = findViewByName<View>(activity, ID_MID_TAB)
            val content = findViewByName<View>(activity, ID_CONTENT)
            unwrapCheckedListener(bar)
            removeMidPreDraw(midTab)
            if (content != null && snap != null) {
                val clp = content.getLayoutParams()
                if (clp is RelativeLayout.LayoutParams) {
                    clp.getRules()[RelativeLayout.ABOVE] = snap.contentAboveRule
                    content.setLayoutParams(clp)
                }
            }
            val legacyShadow = snap?.legacyShadow
            if (legacyShadow != null) {
                legacyShadow.setVisibility(View.VISIBLE)
            }
            val host = root.findViewWithTag<View>(LiquidGlassHostLayout.GLASS_TAG)
            removeFromParent(bar)
            removeFromParent(tips)
            removeFromParent(midTab)
            if (host != null) {
                root.removeView(host)
            }
            if (bar != null && snap != null) {
                bar.setBackground(snap.barBackground)
                bar.setPadding(snap.barPadLeft, snap.barPadTop,
                    snap.barPadRight, snap.barPadBottom)
                bar.setVisibility(View.VISIBLE)
                root.addView(bar, readdIndex(snap.barIndex, root), snap.barLp)
            }
            if (tips != null && snap != null && snap.tipsLp != null) {
                root.addView(tips, readdIndex(snap.tipsIndex, root), snap.tipsLp)
            }
            if (midTab != null && snap != null && snap.midLp != null) {
                midTab.setTranslationX(0f)
                midTab.setVisibility(View.VISIBLE)
                restoreChildren(midTab)
                root.addView(midTab, readdIndex(snap.midIndex, root), snap.midLp)
            }
            resetGlassState()
            root.requestLayout()
            LiquidGlassLog.log(Log.INFO, "liquid glass uninstalled")
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("uninstall glass failed", t)
        }
    }

    private fun readdIndex(originalIndex: Int, root: ViewGroup): Int {
        return Math.max(0, Math.min(originalIndex, root.getChildCount()))
    }

    private fun removeFromParent(v: View?) {
        if (v != null) {
            val parent = v.getParent()
            if (parent is ViewGroup) {
                parent.removeView(v)
            }
        }
    }

    private fun cancelGlassAnimators() {
        sTabShiftAnimator?.cancel()
        sBarWidthAnimator?.cancel()
        sDropletSizeAnimator?.cancel()
        sCenterAnimator?.cancel()
    }

    private fun resetGlassState() {
        sTabBarActive = false
        sTabBarRef = EMPTY_BAR_REF
        sRadioBarRef = EMPTY_RADIO_REF
        sGlassCircleRef = null
        sContentViewRef = null
        sHostRef = null
        sCenterRefStatic = null
        sMidTabRef = EMPTY_MID_REF
        sPlusHidden = false
        sCircleMode = false
        sLastTabs = -1
        sStableTabs = -1
        sBuildSig = ""
        sSyncing = false
        sVisibleButtons.clear()
        sBasePadBottom = 0
        sInstallPadBottom = 0
        sNavPad = 0
        sSnapshot = null
        resetWidthAnimState()
    }

    private var sClassicBarHeight = -1
    private var sClassicBarPadBottom = -1
    private var sClassicMidMargin = -1

    @Volatile
    private var sClassicImmersive = false

    private fun applyClassicImmersive(activity: Activity) {
        try {
            val root = findViewByName<ViewGroup>(activity, ID_ROOT)
            val bar = findViewByName<ViewGroup>(activity, ID_BAR)
            if (root == null || bar == null) {
                activity.getWindow().getDecorView().postDelayed({
                    if (!activity.isFinishing() && !activity.isDestroyed()
                        && sHostRef == null
                    ) {
                        applyClassicImmersive(activity)
                    }
                }, 200L)
                return
            }
            val midTab = findViewByName<View>(activity, ID_MID_TAB)
            if (!GlassConfig.immersiveGestureNavigation) {
                restoreClassicImmersive(bar, midTab)
                return
            }
            val navPad = computeClassicNavPad(root)
            if (navPad <= 0) {
                restoreClassicImmersive(bar, midTab)
                return
            }
            if (!sClassicImmersive) {
                sClassicBarHeight = bar.getLayoutParams().height
                sClassicBarPadBottom = bar.getPaddingBottom()
                if (midTab != null) {
                    val mlp0 = midTab.getLayoutParams()
                    if (mlp0 is RelativeLayout.LayoutParams) {
                        sClassicMidMargin = mlp0.bottomMargin
                    }
                }
                sClassicImmersive = true
            }
            val lp = bar.getLayoutParams()
            if (sClassicBarHeight > 0) {
                lp.height = sClassicBarHeight + navPad
                bar.setLayoutParams(lp)
            }
            bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
                bar.getPaddingRight(), sClassicBarPadBottom + navPad)
            if (midTab != null && sClassicMidMargin >= 0) {
                val mlp = midTab.getLayoutParams()
                if (mlp is RelativeLayout.LayoutParams) {
                    mlp.bottomMargin = sClassicMidMargin + navPad
                    midTab.setLayoutParams(mlp)
                }
            }
            bar.requestLayout()
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("classic immersive failed", t)
        }
    }

    private fun restoreClassicImmersive(bar: ViewGroup?, midTab: View?) {
        if (!sClassicImmersive) {
            return
        }
        try {
            if (bar != null) {
                if (sClassicBarHeight > 0) {
                    bar.getLayoutParams().height = sClassicBarHeight
                    bar.setLayoutParams(bar.getLayoutParams())
                }
                bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
                    bar.getPaddingRight(), Math.max(sClassicBarPadBottom, 0))
            }
            if (midTab != null && sClassicMidMargin >= 0) {
                val mlp = midTab.getLayoutParams()
                if (mlp is RelativeLayout.LayoutParams) {
                    mlp.bottomMargin = sClassicMidMargin
                    midTab.setLayoutParams(mlp)
                }
            }
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("restore classic immersive failed", t)
        }
        sClassicImmersive = false
    }

    private fun computeClassicNavPad(root: ViewGroup): Int {
        try {
            val wi = root.getRootWindowInsets()
            if (wi == null) {
                return 0
            }
            val nav = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wi.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                wi.getSystemWindowInsetBottom()
            }
            if (nav <= 0) {
                return 0
            }
            val density = root.getResources().getDisplayMetrics().density
            return Math.min(nav.toFloat(), density * 56f).toInt()
        } catch (t: Throwable) {
            return 0
        }
    }

    private fun install(activity: Activity, root: ViewGroup) {
        if (root.findViewWithTag<View>(LiquidGlassHostLayout.GLASS_TAG) != null) {
            return
        }
        val gen = sSyncGen
        val bar = findViewByName<ViewGroup>(activity, ID_BAR)
        if (bar == null || bar.getParent() !== root) {
            LiquidGlassLog.log(Log.WARN,
                "nav bar " + ID_BAR + " not found (bar=" + (bar != null) +
                        ", parentMatch=" +
                        (bar != null && bar.getParent() === root) + ")")
            return
        }
        val tips = findViewByName<ViewGroup>(activity, ID_TIPS)
        val midTab = findViewByName<View>(activity, ID_MID_TAB)
        val content = findViewByName<ViewGroup>(activity, ID_CONTENT)
        val videoFull = findViewByName<View>(activity, ID_VIDEO_FULL)

        restoreClassicImmersive(bar, midTab)

        val snap = BarSnapshot()
        snap.barIndex = root.indexOfChild(bar)
        snap.tipsIndex = if (tips != null) root.indexOfChild(tips) else -1
        snap.midIndex = if (midTab != null) root.indexOfChild(midTab) else -1
        snap.barLp = bar.getLayoutParams()
        snap.tipsLp = if (tips != null) tips.getLayoutParams() else null
        snap.midLp = if (midTab != null) midTab.getLayoutParams() else null
        snap.barBackground = bar.getBackground()
        snap.barPadLeft = bar.getPaddingLeft()
        snap.barPadTop = bar.getPaddingTop()
        snap.barPadRight = bar.getPaddingRight()
        snap.barPadBottom = bar.getPaddingBottom()
        snap.contentAboveRule = if (content != null
            && content.getLayoutParams() is RelativeLayout.LayoutParams
        ) {
            (content.getLayoutParams() as RelativeLayout.LayoutParams)
                .getRules()[RelativeLayout.ABOVE]
        } else {
            0
        }
        sSnapshot = snap

        val ctx = root.getContext()
        val density = ctx.getResources().getDisplayMetrics().density
        val sideMargin = Math.round(density * 10f)

        val barLp = bar.getLayoutParams() as RelativeLayout.LayoutParams
        val hostLp = RelativeLayout.LayoutParams(
            barLp.width, RelativeLayout.LayoutParams.WRAP_CONTENT)
        copyMargins(barLp, hostLp)
        hostLp.leftMargin += sideMargin
        hostLp.rightMargin += sideMargin
        hostLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        val hostRules = hostLp.getRules()
        for (i in hostRules.indices) {
            if (i != RelativeLayout.ALIGN_PARENT_BOTTOM) {
                hostRules[i] = 0
            }
        }

        snap.legacyShadow = hideLegacyShadow(root, bar.getId())

        val barHeightSpec = bar.getLayoutParams().height
        val navPad = computeNavInsetPadding(activity, root, bar)
        val origBarPadBottom = bar.getPaddingBottom()
        sNavPad = navPad
        sInstallPadBottom = origBarPadBottom + navPad

        val host = LiquidGlassHostLayout(ctx, root, bar)

        root.removeView(bar)
        if (tips != null && tips.getParent() === root) {
            root.removeView(tips)
        }
        if (midTab != null && midTab.getParent() === root) {
            root.removeView(midTab)
        }

        val insertAt = if (videoFull != null && videoFull.getParent() === root) {
            root.indexOfChild(videoFull)
        } else {
            root.getChildCount()
        }
        root.addView(host, insertAt, hostLp)
        sHostRef = host
        applyBarSideMargins(host)

        val barFlp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (barHeightSpec > 0) barHeightSpec else ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.FILL_HORIZONTAL)
        bar.setBackground(null)
        bar.setPadding(
            bar.getPaddingLeft(), bar.getPaddingTop(),
            bar.getPaddingRight(), 0)
        host.addView(bar, 0, barFlp)

        if (tips != null) {
            val tipsFlp = FrameLayout.LayoutParams(
                tips.getLayoutParams().width,
                tips.getLayoutParams().height,
                Gravity.TOP or Gravity.FILL_HORIZONTAL)
            host.addView(tips, 1, tipsFlp)
        }
        if (midTab != null) {
            val midLp = midTab.getLayoutParams() as RelativeLayout.LayoutParams
            val midFlp = FrameLayout.LayoutParams(
                midLp.width, midLp.height,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
            midFlp.leftMargin = midLp.leftMargin
            midFlp.topMargin = midLp.topMargin
            midFlp.rightMargin = midLp.rightMargin
            midFlp.bottomMargin = midLp.bottomMargin
            host.addView(midTab, host.getChildCount(), midFlp)
        }

        host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
            host.getPaddingRight(), origBarPadBottom + navPad)

        attachGlassRenderer(activity, host, bar, tips, midTab, content, barHeightSpec, navPad)

        if (content != null && content.getParent() === root) {
            val clp = content.getLayoutParams() as RelativeLayout.LayoutParams
            clp.getRules()[RelativeLayout.ABOVE] = 0
            content.setLayoutParams(clp)
        }

        root.requestLayout()
        root.invalidate()

        root.getViewTreeObserver().addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                private var attached = false

                override fun onGlobalLayout() {
                    if (attached) {
                        return
                    }
                    attached = true
                    root.getViewTreeObserver()
                        .removeOnGlobalLayoutListener(this)
                    if (gen != sSyncGen) {
                        return
                    }
                    host.attach()
                    applyImmersive(activity)
                    if (!sTabBarActive) {
                        setupTabPopAnimation(bar)
                    }
                    InWindowTipWatcher.start(activity)
                    LiquidGlassLog.log(Log.INFO,
                        "liquid glass installed: hostW=" + host.getWidth() +
                                " hostH=" + host.getHeight() +
                                " navPad=" + navPad +
                                " barH=" + bar.getHeight() +
                                " children=" + host.getChildCount())
                }
            })
    }

    private const val CENTER_GAP_WEIGHT = 1.0f
    private const val FIT_TAB_MAX_WIDTH_DP = 96f
    private const val SELECTED_TAB_WEIGHT = 1.4f
    private const val OTHER_TAB_WEIGHT = 0.9f

    private const val MIN_TAB_WIDTH_DP = 52f

    private const val TAB_CONTENT_PAD_DP = 18f

    private const val FALLBACK_TAB_WIDTH_DP = 54f

    private const val MIN_PLUS_GAP_DP = 64f

    private const val MAX_SIDE_MARGIN_DP = 40
    private const val FIT_ANIM_MS = 380L
    private const val FIT_ANIM_TENSION = 1.1f

    @Volatile
    private var sTabBarActive = false

    private var sInstallPadBottom = 0
    private var sNavPad = 0

    private fun applyNavPadState(host: ViewGroup?) {
        if (host == null || sInstallPadBottom <= 0) {
            return
        }
        val target = if (GlassConfig.immersiveGestureNavigation) {
            Math.max(sInstallPadBottom - sNavPad, 0)
        } else {
            sInstallPadBottom
        }
        host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
            host.getPaddingRight(), target)
        sBasePadBottom = target
    }

    private fun attachGlassRenderer(activity: Activity, host: ViewGroup,
                                    bar: ViewGroup, tips: ViewGroup?, midTab: View?,
                                    content: ViewGroup?, barHeightSpec: Int, navPad: Int) {
        GlassConfig.load(activity)
        applyNavPadState(host)
        if (Build.VERSION.SDK_INT < 33 || content == null) {
            return
        }
        if (bar is RadioGroup) {
            attachQwea0TabBar(activity, host,
                bar, tips, midTab,
                content, barHeightSpec, navPad)
        } else {
            LiquidGlassLog.log(Log.WARN,
                "nav bar is not a RadioGroup, QWEA0 tab bar unavailable")
        }
    }

    @JvmStatic
    fun refreshGlassWith(activity: Activity) {
        GlassConfig.load(activity)
        refreshGlass()
        if (activeGlassHost() == null) {
            val main = mainActivity()
            if (main != null) {
                applyClassicImmersive(main)
            }
        }
    }

    @JvmStatic
    fun refreshGlass() {
        try {
            WindowImmersiveController.refresh()
            applyNavPadState(sHostRef)
            applyBarGeometry()
            val bar = sTabBarRef.get()
            if (bar is ViewGroup) {
                bar.invalidate()
                val droplet = findDroplet(bar)
                if (droplet != null) {
                    droplet.invalidate()
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun findDroplet(tabBar: ViewGroup): View? {
        for (i in 0 until tabBar.getChildCount()) {
            val c = tabBar.getChildAt(i)
            if (c is LiquidGlassView
                && c !is LiquidGlassTabBar
            ) {
                return c
            }
        }
        return null
    }

    private fun applyQwea0Params(glass: LiquidGlassView,
                                 content: ViewGroup, density: Float, adaptiveTint: Boolean) {
        glass.cornerRadius = 999f
        glass.enableDynamicBackground = true
        glass.backdropSource = content
        glass.material = GlassMaterial.REGULAR
        glass.refractionHeight = 60f * density
        glass.bevelWidth = 16f * density
        glass.dispersionStrength = 0.12f
        glass.enableSensorHighlight = true
        glass.enableAdaptiveTint = adaptiveTint
    }

    private fun attachQwea0TabBar(activity: Activity, host: ViewGroup,
                                  bar: RadioGroup,
                                  tips: ViewGroup?, midTab: View?,
                                  content: ViewGroup, barHeightSpec: Int,
                                  navPad: Int) {
        try {
            sHostRef = host
            sDensity = host.getResources().getDisplayMetrics().density
            sRadioBarRef = WeakReference(bar)
            sContentViewRef = WeakReference(content)
            sCenterRefStatic = null
            val tabBar = LiquidGlassTabBar(activity, null, 0)
            sTabBarRef = WeakReference(tabBar)
            resetWidthAnimState()
            sBuildSig = ""
            sLastTabs = -1
            sStableTabs = -1
            val density = host.getResources().getDisplayMetrics().density

            var anyVisible = false
            for (i in 0 until bar.getChildCount()) {
                val c = bar.getChildAt(i)
                if (c is RadioButton
                    && c.getVisibility() == View.VISIBLE
                ) {
                    anyVisible = true
                    break
                }
            }
            if (!anyVisible) {
                LiquidGlassLog.log(Log.WARN,
                    "tabbar: no radio buttons found")
                return
            }
            applyQwea0Params(tabBar, content, density, false)
            installTintOverride()

            host.addView(tabBar, host.getChildCount(), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.FILL_HORIZONTAL))

            tabBar.onTabSelected = handler@{ index ->
                try {
                    if (sSyncing || index < 0
                        || index >= sVisibleButtons.size
                    ) {
                        return@handler
                    }
                    if (applyTabWidths(tabBar.selectedIndex)) {
                        reanimateDroplet(tabBar)
                    }
                    val rb = sVisibleButtons[index]
                    if (rb != null && !rb.isChecked()) {
                        rb.performClick()
                    }
                } catch (t: Throwable) {
                    LiquidGlassLog.logErr("tabbar->app failed", t)
                }
            }
            installRepeatClickRefresh(tabBar)

            val darkSeed = isSystemNight(activity)
            sChromeLight = !darkSeed
            applyTabBarOverLight(tabBar, darkSeed)

            mountCenterButton(activity, host, midTab, tips)
            rebuildTabBar(tabBar)

            placeCenterNow(host, tabBar, sCenterRefStatic, 0)
            applyBarGeometry()

            startBackdropMeter(tabBar)

            bar.setVisibility(View.INVISIBLE)

            setupTabSelectionSync(bar, tabBar)
            startTabVisibilitySync(bar, tabBar)

            (host as LiquidGlassHostLayout).setExternalRendererActive(true)

            LiquidGlassLog.log(Log.INFO,
                "renderer=QWEA0 LiquidGlassTabBar (glass droplet selection)")
            sTabBarActive = true
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("qwea0 tabbar unavailable", t)
        }
    }

    private fun mountCenterButton(activity: Activity, host: ViewGroup,
                                  midTab: View?, tips: ViewGroup?) {
        var centerHost: View? = null
        if (midTab != null && midTab.getParent() === host) {
            host.removeView(midTab)
            midTab.setVisibility(View.VISIBLE)

            val center = FrameLayout(activity)
            center.setClickable(true)
            center.addView(midTab, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
            center.setOnClickListener {
                if (host is LiquidGlassHostLayout) {
                    host.popChild(midTab)
                }
                midTab.performClick()
            }
            host.addView(center, host.getChildCount())
            centerHost = center
        }
        if (tips != null && tips.getParent() === host) {
            host.removeView(tips)
            host.addView(tips, host.getChildCount())
        }
        sCenterRefStatic = centerHost
        sMidTabRef = WeakReference<View>(midTab ?: centerHost)
        if (midTab != null) {
            sMidPreDraw = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    try {
                        if (sPlusHidden) {
                            if (midTab.getVisibility() != View.GONE) {
                                midTab.setVisibility(View.GONE)
                            }
                        } else {
                            if (midTab.getVisibility() != View.VISIBLE) {
                                midTab.setVisibility(View.VISIBLE)
                            }
                            restoreChildren(midTab)
                        }
                    } catch (ignored: Throwable) {
                    }
                    return true
                }
            }
            midTab.getViewTreeObserver().addOnPreDrawListener(sMidPreDraw)
        }
    }

    private fun startBackdropMeter(tabBar: LiquidGlassTabBar) {
        val meterHolder = AtomicReference<BackdropLuminanceMeter>()
        val meter = BackdropLuminanceMeter(tabBar,
            meter@{ luma ->
                try {
                    if (!GlassConfig.adaptiveChrome) {
                        val uiDark = (tabBar.getResources()
                            .getConfiguration().uiMode and
                                Configuration.UI_MODE_NIGHT_MASK) ==
                                Configuration.UI_MODE_NIGHT_YES
                        if (sChromeLight != !uiDark || sChromeForced) {
                            sChromeForced = true
                            sChromeLight = !uiDark
                            val d = uiDark
                            tabBar.post {
                                applyTabBarOverLight(tabBar, d)
                            }
                        }
                        return@meter
                    }
                    sChromeForced = false
                    val m0 = meterHolder.get()
                    val overLight = m0 != null && m0.isOverLight
                    if (overLight != sChromeLight) {
                        sChromeLight = overLight
                        val flip = overLight
                        tabBar.post {
                            applyTabBarOverLight(tabBar,
                                !flip)
                            LiquidGlassLog.log(
                                Log.INFO,
                                "backdrop flip: overLight=" + flip)
                        }
                    }
                } catch (ignored: Throwable) {
                }
            })
        meterHolder.set(meter)
        tabBar.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    try {
                        meter.start()
                    } catch (ignored: Throwable) {
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    try {
                        meter.stop()
                    } catch (ignored: Throwable) {
                    }
                }
            })
        if (tabBar.isAttachedToWindow()) {
            try {
                meter.start()
                LiquidGlassLog.log(Log.INFO,
                    "backdrop meter started (already attached)")
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun rebuildTabBar(tabBar: LiquidGlassTabBar) {
        try {
            val bar = sRadioBarRef.get()
            val host = sHostRef
            if (bar == null || host == null) {
                return
            }
            GlassConfig.load(tabBar.getContext())
            val publishHidden = readPublishHidden(tabBar.getContext())
            val items: MutableList<LiquidGlassTabBar.TabItem> =
                ArrayList()
            val sig = StringBuilder()
            sVisibleButtons.clear()
            for (i in 0 until bar.getChildCount()) {
                val child = bar.getChildAt(i)
                if (child is RadioButton
                    && child.getVisibility() == View.VISIBLE
                ) {
                    sig.append(child.getId()).append(',')
                    val title = child.getText()
                    val icon = child.getCompoundDrawables()[1]
                    if (icon != null) {
                        icon.mutate()
                    }
                    items.add(LiquidGlassTabBar.TabItem(
                        title, icon))
                    sVisibleButtons.add(child)
                }
            }
            sig.append(if (GlassConfig.fitTabs) 'F' else 'f')
                .append(if (publishHidden) 'P' else 'p')
            val nextSig = sig.toString()
            if (nextSig == sBuildSig) {
                return
            }
            if (items.isEmpty()) {
                LiquidGlassLog.log(Log.WARN,
                    "tabbar: no radio buttons found")
                return
            }
            sBuildSig = nextSig
            sSyncing = true
            try {
                tabBar.setTabs(items)
                applyBarMode(bar, tabBar)
                val checked = bar.getCheckedRadioButtonId()
                var selected = 0
                for (i in 0 until sVisibleButtons.size) {
                    if (sVisibleButtons[i].getId() == checked) {
                        selected = i
                        break
                    }
                }
                applyTabWidths(selected)
                tabBar.selectedIndex = selected
                applyTabBarOverLight(tabBar, !sChromeLight)
                tabBar.requestLayout()
            } finally {
                sSyncing = false
            }
            host.post {
                placeCenterNow(host, tabBar, sCenterRefStatic, 0)
            }
            LiquidGlassLog.log(Log.INFO,
                "glass tab bar rebuilt: tabs=" + items.size +
                        " publishHidden=" + publishHidden)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("glass tab bar rebuild failed", t)
        }
    }

    private fun startTabVisibilitySync(bar: RadioGroup,
                                       tabBar: LiquidGlassTabBar) {
        val gen = sSyncGen
        bar.postDelayed(object : Runnable {
            override fun run() {
                if (gen != sSyncGen) {
                    return
                }
                try {
                    if (bar.isAttachedToWindow()) {
                        rebuildTabBar(tabBar)
                        syncPlusButton(bar, tabBar)
                    }
                } catch (t: Throwable) {
                    LiquidGlassLog.logErr("tab visibility sync failed", t)
                }
                bar.postDelayed(this, 500L)
            }
        }, 1500L)
    }

    private fun syncPlusButton(bar: RadioGroup,
                               tabBar: LiquidGlassTabBar): Boolean {
        return applyBarMode(bar, tabBar)
    }

    private fun applyBarMode(bar: RadioGroup,
                             tabBar: LiquidGlassTabBar): Boolean {
        val barV = sTabBarRef.get()
        val host = sHostRef
        val center = sCenterRefStatic
        val mid = sMidTabRef.get()
        if (barV == null || host == null || center == null) {
            return false
        }
        var visibleTabs = 0
        for (i in 0 until bar.getChildCount()) {
            val c = bar.getChildAt(i)
            if (c is RadioButton
                && c.getVisibility() == View.VISIBLE
            ) {
                visibleTabs++
            }
        }

        if (visibleTabs == sLastTabs) {
            sStableTabs = visibleTabs
        }
        sLastTabs = visibleTabs
        val stableTabs = if (sStableTabs >= 0) sStableTabs else visibleTabs
        val publishHidden = readPublishHidden(tabBar.getContext())
        val circle: Boolean
        val wantHidden: Boolean
        if (publishHidden) {
            circle = false
            wantHidden = true
        } else if (BottomTabHook.isAnyTabHidden()
            && GlassConfig.barLayoutMode == 0
        ) {
            circle = false
            wantHidden = false
        } else {
            var mode = GlassConfig.barLayoutMode
            if (mode == 0) {
                mode = if (stableTabs % 2 == 1) 2 else 1
            }
            circle = mode == 2
            wantHidden = !circle && (stableTabs % 2 == 1 || stableTabs == 0)
        }
        sCircleMode = circle
        var changed = false
        if (mid != null) {
            val hiddenNow = mid.getVisibility() == View.GONE
                    || allChildrenGone(mid)
            if (circle || !wantHidden) {
                if (hiddenNow) {
                    mid.setVisibility(View.VISIBLE)
                    restoreChildren(mid)
                    changed = true
                }
            } else if (!hiddenNow) {
                mid.setVisibility(View.GONE)
                changed = true
            }
        }
        sPlusHidden = wantHidden
        applyBarSideMargins(host)
        if (tabBar.getChildCount() == 0
            || tabBar.getChildAt(0) !is ViewGroup
        ) {
            return true
        }
        val row = tabBar.getChildAt(0) as ViewGroup
        var hasGap = false
        for (i in 0 until row.getChildCount()) {
            if (row.getChildAt(i) is Space) {
                hasGap = true
                break
            }
        }
        val wantGap = !circle && !wantHidden
        if (wantGap && !hasGap) {
            insertCenterGap(tabBar.getContext(), tabBar)
            changed = true
        } else if (!wantGap && hasGap) {
            for (i in row.getChildCount() - 1 downTo 0) {
                if (row.getChildAt(i) is Space) {
                    row.removeViewAt(i)
                }
            }
            changed = true
        }
        val den = if (sDensity > 0) sDensity else 3f
        val barH = barV.getHeight()
        val circleSize = if (barH > 0) barH else Math.round(56 * den)
        val circleGap = Math.round(8 * den)
        if (barV.getLayoutParams() is FrameLayout.LayoutParams) {
            val blp = barV.getLayoutParams() as FrameLayout.LayoutParams
            val wantMargin = if (circle) circleSize + circleGap else 0
            if (blp.rightMargin != wantMargin) {
                blp.rightMargin = wantMargin
                barV.setLayoutParams(blp)
                changed = true
            }
        }
        val tabLayoutChanged = applyTabWidths()
        changed = changed or tabLayoutChanged
        if (tabLayoutChanged) {
            reanimateDroplet(tabBar)
        }
        if (circle) {
            var glass = sGlassCircleRef?.get()
            if (glass != null && glass.getParent() !== center) {
                glass = null
            }
            if (glass == null && center is ViewGroup) {
                glass = buildGlassCircle(tabBar.getContext())
                if (glass != null) {
                    sGlassCircleRef = WeakReference(glass)
                    center.addView(glass, 0,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT))
                    changed = true
                }
            }
            if (glass != null && glass.getVisibility() != View.VISIBLE) {
                glass.setVisibility(View.VISIBLE)
                changed = true
            }
            if (center.getLayoutParams() is FrameLayout.LayoutParams) {
                val clp = center.getLayoutParams() as FrameLayout.LayoutParams
                val wantGravity = Gravity.END or
                        Gravity.CENTER_VERTICAL
                if (clp.width != circleSize || clp.height != circleSize
                    || clp.gravity != wantGravity || clp.leftMargin != 0
                    || clp.topMargin != 0 || clp.rightMargin != 0
                    || clp.bottomMargin != 0
                ) {
                    clp.width = circleSize
                    clp.height = circleSize
                    clp.gravity = wantGravity
                    clp.leftMargin = 0
                    clp.topMargin = 0
                    clp.rightMargin = 0
                    clp.bottomMargin = 0
                    center.setLayoutParams(clp)
                    changed = true
                }
            }
            if (center.getVisibility() != View.VISIBLE) {
                center.setVisibility(View.VISIBLE)
                changed = true
            }
        } else {
            val glass = sGlassCircleRef?.get()
            if (glass != null && glass.getVisibility() != View.GONE) {
                glass.setVisibility(View.GONE)
                changed = true
            }
            val wantVis = if (wantHidden) View.GONE else View.VISIBLE
            if (center.getVisibility() != wantVis) {
                center.setVisibility(wantVis)
                changed = true
            }
        }
        if (changed) {
            row.requestLayout()
            host.requestLayout()
        }
        if (!circle) {
            host.post {
                placeCenterNow(host, tabBar, center, 0)
            }
        }
        return changed
    }

    private fun fitVisibleTabsEffective(visibleTabs: Int): Boolean {
        if (!GlassConfig.fitTabs) {
            return false
        }
        try {
            val bar = sRadioBarRef.get()
            var named = 0
            if (bar != null) {
                for (i in 0 until bar.getChildCount()) {
                    val child = bar.getChildAt(i)
                    if (child !is RadioButton) {
                        continue
                    }
                    val title = child.getText()
                    if (title == null || title.toString().trim().isEmpty()) {
                        continue
                    }
                    named++
                }
            }
            return visibleTabs > 0 && visibleTabs < named
        } catch (ignored: Throwable) {
            return false
        }
    }

    private fun buildGlassCircle(context: Context): View? {
        try {
            val content = sContentViewRef?.get()
            val glass = LiquidGlassView(context, null, 0)
            glass.cornerRadius = 999f
            glass.enableDynamicBackground = true
            if (content != null) {
                glass.backdropSource = content
            }
            glass.material = GlassMaterial.REGULAR
            val den = if (sDensity > 0) sDensity else 3f
            glass.refractionHeight = 28f * den
            glass.bevelWidth = 10f * den
            glass.dispersionStrength = 0.12f
            glass.enableSensorHighlight = true
            glass.enableAdaptiveTint = false
            return glass
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("glass circle build failed", t)
            return null
        }
    }

    private fun allChildrenGone(view: View): Boolean {
        if (view !is ViewGroup) {
            return false
        }
        val group = view
        if (group.getChildCount() == 0) {
            return false
        }
        for (i in 0 until group.getChildCount()) {
            if (group.getChildAt(i).getVisibility() != View.GONE) {
                return false
            }
        }
        return true
    }

    private fun restoreChildren(view: View) {
        if (view !is ViewGroup) {
            return
        }
        val group = view
        for (i in 0 until group.getChildCount()) {
            val c = group.getChildAt(i)
            if (c.getVisibility() == View.GONE) {
                c.setVisibility(View.VISIBLE)
            }
        }
    }

    private fun applyTabWidths(): Boolean {
        val barV = sTabBarRef.get()
        val selected = if (barV is LiquidGlassTabBar) {
            barV.selectedIndex
        } else {
            0
        }
        return applyTabWidths(selected)
    }

    private fun applyTabWidths(selectedIndex: Int): Boolean {
        var changed = false
        try {
            val barV = sTabBarRef.get()
            if (barV !is ViewGroup
                || barV.getChildCount() == 0
                || barV.getChildAt(0) !is ViewGroup
            ) {
                return false
            }
            val row = barV.getChildAt(0) as ViewGroup
            var tabs = 0
            var hasGap = false
            for (i in 0 until row.getChildCount()) {
                if (row.getChildAt(i) is LinearLayout) {
                    tabs++
                } else if (row.getChildAt(i) is Space) {
                    hasGap = true
                }
            }
            if (tabs == 0) {
                return false
            }
            val f = Math.max(50, Math.min(GlassConfig.tabWidthPct, 150)) / 100f
            val fit = fitVisibleTabsEffective(tabs)
            val glide = fit || sFitActive
            sFitActive = fit
            val before = if (glide) captureTabCenters(row) else null
            val selected = Math.max(0, Math.min(selectedIndex, tabs - 1))
            val den = if (sDensity > 0) {
                sDensity
            } else {
                barV.getResources().getDisplayMetrics().density
            }
            val metrics = if (!fit && GlassConfig.barWidthMode == 0) {
                adaptiveMetricsDp(den)
            } else {
                null
            }
            val gap = if (fit) {
                CENTER_GAP_WEIGHT
            } else if (metrics != null) {
                Math.max(metrics[1], 0.3f)
            } else {
                Math.max(0.3f, (tabs + CENTER_GAP_WEIGHT) / f - tabs)
            }
            var tabIndex = 0
            for (i in 0 until row.getChildCount()) {
                val c = row.getChildAt(i)
                if (c.getLayoutParams()
                    !is LinearLayout.LayoutParams
                ) {
                    continue
                }
                val lp = c.getLayoutParams() as LinearLayout.LayoutParams
                val w: Float
                if (c is Space) {
                    w = gap
                } else if (c is LinearLayout) {
                    w = if (fit) {
                        if (tabIndex == selected) {
                            SELECTED_TAB_WEIGHT
                        } else {
                            OTHER_TAB_WEIGHT
                        }
                    } else if (metrics != null) {
                        metrics[0]
                    } else {
                        f
                    }
                    tabIndex++
                } else {
                    continue
                }
                if (Math.abs(lp.weight - w) > 0.001f) {
                    lp.weight = w
                    c.setLayoutParams(lp)
                    changed = true
                }
            }
            val totalWeight = if (fit) {
                OTHER_TAB_WEIGHT * tabs +
                        (SELECTED_TAB_WEIGHT - OTHER_TAB_WEIGHT) +
                        (if (hasGap) CENTER_GAP_WEIGHT else 0f)
            } else {
                0f
            }
            changed = changed or applyFitBarWidth(barV, totalWeight, fit, f)
            if (changed && before != null) {
                scheduleTabGlide(row, before)
            }
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("apply tab widths failed", t)
        }
        return changed
    }

    private fun resetWidthAnimState() {
        sTabShiftAnimator?.cancel()
        sTabShiftAnimator = null
        sBarWidthAnimator?.cancel()
        sBarWidthAnimator = null
        sDropletSizeAnimator?.cancel()
        sDropletSizeAnimator = null
        sBarTargetWidth = Int.MIN_VALUE
        sBarTargetLeft = Int.MIN_VALUE
        sBarTargetGravity = Int.MIN_VALUE
        sFitActive = false
    }

    private fun scheduleDropletGrow(
        tabBar: LiquidGlassTabBar,
        fromWidth: Int) {
        try {
            if (fromWidth <= 0) {
                return
            }
            val droplet = findDroplet(tabBar)
            if (droplet == null) {
                return
            }
            val vto = tabBar.getViewTreeObserver()
            if (vto == null || !vto.isAlive()) {
                return
            }
            vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val live = tabBar.getViewTreeObserver()
                    if (live != null && live.isAlive()) {
                        live.removeOnPreDrawListener(this)
                    }
                    return !growDroplet(droplet, fromWidth)
                }
            })
        } catch (ignored: Throwable) {
        }
    }

    private fun growDroplet(droplet: View, fromWidth: Int): Boolean {
        try {
            if (droplet.getLayoutParams() !is FrameLayout.LayoutParams) {
                return false
            }
            val lp = droplet.getLayoutParams() as FrameLayout.LayoutParams
            val toWidth = lp.width
            if (toWidth <= 0 || Math.abs(toWidth - fromWidth) < 2) {
                return false
            }
            sDropletSizeAnimator?.cancel()
            val startWidth = fromWidth
            lp.width = startWidth
            droplet.setLayoutParams(lp)
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.setDuration(Math.max(0L, FIT_ANIM_MS - 40L))
            anim.setInterpolator(
                DecelerateInterpolator(1.6f))
            anim.addUpdateListener { a ->
                val t = a.getAnimatedValue() as Float
                lp.width = Math.round(startWidth + (toWidth - startWidth) * t)
                droplet.setLayoutParams(lp)
            }
            val cancelled = booleanArrayOf(false)
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: Animator) {
                    cancelled[0] = true
                }

                override fun onAnimationEnd(a: Animator) {
                    if (cancelled[0]) {
                        return
                    }
                    lp.width = toWidth
                    droplet.setLayoutParams(lp)
                }
            })
            sDropletSizeAnimator = anim
            anim.start()
            return true
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("droplet grow failed", t)
            return false
        }
    }

    private fun captureTabCenters(row: ViewGroup): FloatArray? {
        if (row.getWidth() <= 0) {
            return null
        }
        val centers = FloatArray(row.getChildCount())
        for (i in 0 until row.getChildCount()) {
            val c = row.getChildAt(i)
            if (c.getWidth() <= 0) {
                return null
            }
            centers[i] = c.getLeft() + c.getWidth() / 2f + c.getTranslationX()
        }
        return centers
    }

    private fun scheduleTabGlide(row: ViewGroup,
                                 before: FloatArray) {
        try {
            val vto = row.getViewTreeObserver()
            if (vto == null || !vto.isAlive()) {
                return
            }
            vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val live = row.getViewTreeObserver()
                    if (live != null && live.isAlive()) {
                        live.removeOnPreDrawListener(this)
                    }
                    glideTabsFrom(row, before)
                    return true
                }
            })
        } catch (ignored: Throwable) {
        }
    }

    private fun glideTabsFrom(row: ViewGroup, before: FloatArray?) {
        try {
            if (before == null || row.getChildCount() != before.size) {
                return
            }
            if (sSnapWidthChanges) {
                return
            }
            if (sBarWidthAnimator?.isRunning() == true) {
                return
            }
            val kids = arrayOfNulls<View>(before.size)
            val shift = FloatArray(before.size)
            var any = false
            for (i in before.indices) {
                val c = row.getChildAt(i)
                if (c.getWidth() <= 0) {
                    return
                }
                kids[i] = c
                shift[i] = before[i] - (c.getLeft() + c.getWidth() / 2f)
                any = any or (Math.abs(shift[i]) > 0.5f)
            }
            if (!any) {
                return
            }
            sTabShiftAnimator?.cancel()
            for (i in kids.indices) {
                kids[i]!!.setTranslationX(shift[i])
            }
            val anim = ValueAnimator.ofFloat(1f, 0f)
            anim.setDuration(FIT_ANIM_MS)
            anim.setInterpolator(
                OvershootInterpolator(
                    FIT_ANIM_TENSION))
            anim.addUpdateListener { a ->
                val t = a.getAnimatedValue() as Float
                for (i in kids.indices) {
                    kids[i]!!.setTranslationX(shift[i] * t)
                }
            }
            val cancelled = booleanArrayOf(false)
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: Animator) {
                    cancelled[0] = true
                }

                override fun onAnimationEnd(a: Animator) {
                    if (cancelled[0]) {
                        return
                    }
                    for (kid in kids) {
                        kid!!.setTranslationX(0f)
                    }
                }
            })
            sTabShiftAnimator = anim
            anim.start()
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("tab glide failed", t)
        }
    }

    private fun applyFitBarWidth(barV: View, totalWeight: Float,
                                 fit: Boolean, widthScale: Float): Boolean {
        if (barV.getLayoutParams() !is FrameLayout.LayoutParams) {
            return false
        }
        val lp = barV.getLayoutParams() as FrameLayout.LayoutParams
        val host = sHostRef
        val den = if (sDensity > 0) {
            sDensity
        } else {
            barV.getResources().getDisplayMetrics().density
        }
        var hostWidth = if (host != null) host.getWidth() else 0
        if (hostWidth <= 0) {
            hostWidth = barV.getResources().getDisplayMetrics().widthPixels -
                    Math.round(20f * den)
        }
        val available = Math.max(0, hostWidth - lp.rightMargin)
        if (!fit) {
            return setBarWidth(barV, lp, ViewGroup.LayoutParams.MATCH_PARENT, 0,
                Gravity.TOP or
                        Gravity.FILL_HORIZONTAL, available)
        }
        if (available <= 0 || totalWeight <= 0f) {
            return false
        }
        val innerAvailable = Math.max(1, available - Math.round(8f * den))
        val perWeight = Math.min(
            Math.round(FIT_TAB_MAX_WIDTH_DP * widthScale * den),
            Math.round(innerAvailable / totalWeight))
        val width = Math.min(available,
            Math.round(totalWeight * perWeight) + Math.round(8f * den))
        val left = Math.max(0, (available - width) / 2)
        return setBarWidth(barV, lp, width, left,
            Gravity.TOP or Gravity.START,
            available)
    }

    private fun setBarWidth(barV: View, lp: FrameLayout.LayoutParams,
                            width: Int, left: Int, gravity: Int, available: Int): Boolean {
        val animating = sBarWidthAnimator?.isRunning() == true
        if (animating && width == sBarTargetWidth && left == sBarTargetLeft
            && gravity == sBarTargetGravity
        ) {
            return false
        }
        if (!animating && lp.width == width && lp.gravity == gravity
            && lp.leftMargin == left
        ) {
            sBarTargetWidth = width
            sBarTargetLeft = left
            sBarTargetGravity = gravity
            return false
        }
        sBarTargetWidth = width
        sBarTargetLeft = left
        sBarTargetGravity = gravity
        val startWidth = barV.getWidth()
        val startLeft = lp.leftMargin
        val endPx = if (width == ViewGroup.LayoutParams.MATCH_PARENT) {
            available
        } else {
            width
        }
        if (animating) {
            sBarWidthAnimator?.cancel()
        }
        if (sSnapWidthChanges || startWidth <= 0 || endPx <= 0
            || startWidth == endPx
        ) {
            lp.width = width
            lp.leftMargin = left
            lp.gravity = gravity
            barV.setLayoutParams(lp)
            return true
        }
        val flp = lp
        val target = barV
        val endWidth = endPx
        val endLeft = left
        val endGravity = gravity
        val finalWidth = width
        flp.gravity = Gravity.TOP or Gravity.START
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.setDuration(FIT_ANIM_MS)
        anim.setInterpolator(
            DecelerateInterpolator(1.6f))
        anim.addUpdateListener { a ->
            val t = a.getAnimatedValue() as Float
            flp.width = Math.round(startWidth + (endWidth - startWidth) * t)
            flp.leftMargin = Math.round(startLeft + (endLeft - startLeft) * t)
            target.setLayoutParams(flp)
        }
        val cancelled = booleanArrayOf(false)
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: Animator) {
                cancelled[0] = true
            }

            override fun onAnimationEnd(a: Animator) {
                if (cancelled[0]) {
                    return
                }
                flp.width = finalWidth
                flp.leftMargin = endLeft
                flp.gravity = endGravity
                target.setLayoutParams(flp)
            }
        })
        sBarWidthAnimator = anim
        anim.start()
        return true
    }

    private fun prepareSelectionLayout(
        tabBar: LiquidGlassTabBar,
        selectedIndex: Int) {
        try {
            val droplet = findDroplet(tabBar)
            val dropletWidth = if (droplet == null) 0 else droplet.getWidth()
            if (!applyTabWidths(selectedIndex)) {
                return
            }
            scheduleDropletGrow(tabBar, dropletWidth)
            val width = tabBar.getMeasuredWidth()
            val height = tabBar.getMeasuredHeight()
            if (width <= 0 || height <= 0) {
                return
            }
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(
                height, View.MeasureSpec.EXACTLY)
            tabBar.measure(widthSpec, heightSpec)
            tabBar.layout(tabBar.getLeft(), tabBar.getTop(),
                tabBar.getRight(), tabBar.getBottom())
            glideCenterTo(tabBar)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("glass selection layout failed", t)
        }
    }

    private fun glideCenterTo(tabBar: LiquidGlassTabBar) {
        try {
            val center = sCenterRefStatic
            if (center == null
                || center.getLayoutParams()
                !is FrameLayout.LayoutParams
            ) {
                return
            }
            if (sPlusHidden || sCircleMode
                || center.getVisibility() != View.VISIBLE
            ) {
                return
            }
            val row = tabBar.getChildAt(0)
            if (row !is LinearLayout) {
                return
            }
            val n = row.getChildCount()
            if (n < 3) {
                return
            }
            val spacer = row.getChildAt(n / 2)
            if (spacer.getWidth() <= 0) {
                return
            }
            val left = tabBar.getLeft() + row.getLeft() + spacer.getLeft()
            val lp = center.getLayoutParams() as FrameLayout.LayoutParams
            if (left == lp.leftMargin) {
                return
            }
            sCenterAnimator?.cancel()
            sCenterTargetLeft = left
            val from = lp.leftMargin
            val to = left
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.setDuration(FIT_ANIM_MS)
            anim.setInterpolator(
                OvershootInterpolator(
                    FIT_ANIM_TENSION))
            anim.addUpdateListener { a ->
                val t = a.getAnimatedValue() as Float
                lp.leftMargin = Math.round(from + (to - from) * t)
                center.setLayoutParams(lp)
            }
            val cancelled = booleanArrayOf(false)
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: Animator) {
                    cancelled[0] = true
                }

                override fun onAnimationEnd(a: Animator) {
                    if (cancelled[0]) {
                        return
                    }
                    lp.leftMargin = to
                    center.setLayoutParams(lp)
                }
            })
            sCenterAnimator = anim
            anim.start()
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("center glide failed", t)
        }
    }

    private fun nearestTabIndex(tabBar: LiquidGlassTabBar): Int {
        try {
            val row = tabBar.getChildAt(0)
            if (row !is LinearLayout) {
                return -1
            }
            val droplet = findDroplet(tabBar)
            if (droplet == null) {
                return -1
            }
            val centerX = droplet.getX() + droplet.getWidth() / 2f
            var best = tabBar.selectedIndex
            var bestDist = Float.MAX_VALUE
            var index = 0
            for (i in 0 until row.getChildCount()) {
                val child = row.getChildAt(i)
                if (child !is LinearLayout) {
                    continue
                }
                val tabCenter = child.getLeft() + child.getWidth() / 2f
                val d = Math.abs(tabCenter - centerX)
                if (d < bestDist) {
                    bestDist = d
                    best = index
                }
                index++
            }
            return best
        } catch (t: Throwable) {
            return -1
        }
    }

    private fun reanimateDroplet(tabBar: LiquidGlassTabBar) {
        try {
            if (sBarWidthAnimator?.isRunning() == true) {
                return
            }
            tabBar.getViewTreeObserver().addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        tabBar.getViewTreeObserver()
                            .removeOnGlobalLayoutListener(this)
                        try {
                            tabBar.selectedIndex = tabBar.selectedIndex
                        } catch (ignored: Throwable) {
                        }
                    }
                })
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun syncTabVisibility() {
        try {
            if (!sTabBarActive) {
                return
            }
            val v = sTabBarRef.get()
            if (v is LiquidGlassTabBar) {
                rebuildTabBar(v)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun applyTabBarOverLight(tabBar: LiquidGlassTabBar, darkBar: Boolean) {
        val selectedColor = if (darkBar) 0xFFFFFFFF.toInt() else 0xE6000000.toInt()
        val normalColor = if (darkBar) 0xB8FFFFFF.toInt() else 0x8C000000.toInt()
        var applied = 0
        try {
            val tf = LiquidGlassTabBar::class.java
                .getDeclaredField("tabs")
            tf.setAccessible(true)
            val tabsObj = tf.get(tabBar)
            if (tabsObj is List<*>) {
                val selIdx = tabBar.selectedIndex
                val tabs = tabsObj
                for (i in tabs.indices) {
                    val holder = tabs[i]
                    if (holder == null) {
                        continue
                    }
                    val color = if (i == selIdx) selectedColor else normalColor
                    for (hf in holder.javaClass.getDeclaredFields()) {
                        hf.setAccessible(true)
                        val value = hf.get(holder)
                        if (value is TextView) {
                            value.setTextColor(color)
                            applied++
                        } else if (value is ImageView) {
                            value.setImageTintList(
                                ColorStateList
                                    .valueOf(color))
                            applied++
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("direct chrome recolor failed", t)
        }
        try {
            val af = LiquidGlassTabBar::class.java
                .getDeclaredField("overLightAppearance")
            af.setAccessible(true)
            af.setBoolean(tabBar, !darkBar)

            val uts = LiquidGlassTabBar::class.java
                .getDeclaredMethod("updateTabStyles")
            uts.setAccessible(true)
            uts.invoke(tabBar)
        } catch (t: Throwable) {
            LiquidGlassLog.log(Log.WARN,
                "updateTabStyles refresh failed: " + t)
        }
        tabBar.invalidate()
        LiquidGlassLog.log(Log.INFO,
            "chrome theme applied: dark=" + darkBar + " targets=" + applied)
    }

    private fun isSystemNight(activity: Activity): Boolean {
        val mode = activity.getResources().getConfiguration().uiMode and
                Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun insertCenterGap(context: Context, tabBar: ViewGroup) {
        try {
            if (tabBar.getChildCount() == 0) {
                return
            }
            val row = tabBar.getChildAt(0)
            if (row !is LinearLayout) {
                return
            }
            val count = row.getChildCount()
            if (count < 2) {
                return
            }
            val spacer = Space(context)
            val lp = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, CENTER_GAP_WEIGHT)
            row.addView(spacer, (count + 1) / 2, lp)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("center gap failed", t)
        }
    }

    private fun placeCenterNow(host: ViewGroup, tabBar: ViewGroup,
                               center: View?, attempt: Int) {
        if (center == null || sPlusHidden || sCircleMode || attempt > 10) {
            return
        }
        try {
            val row = tabBar.getChildAt(0)
            if (row !is LinearLayout) {
                return
            }
            val n = row.getChildCount()
            if (n < 3) {
                return
            }
            val spacer = row.getChildAt(n / 2)
            if (spacer.getWidth() == 0) {
                center.post {
                    placeCenterNow(host, tabBar, center, attempt + 1)
                }
                return
            }
            val left = tabBar.getLeft() + row.getLeft() + spacer.getLeft()
            if (sCenterAnimator?.isRunning() == true
                && left == sCenterTargetLeft
            ) {
                return
            }
            if (center.getWidth() == spacer.getWidth()
                && center.getHeight() == tabBar.getHeight()
                && center.getLeft() == left
                && center.getTop() == tabBar.getTop()
                && center.getVisibility() == View.VISIBLE
            ) {
                return
            }
            val lp = FrameLayout.LayoutParams(
                spacer.getWidth(), tabBar.getHeight(),
                Gravity.TOP or Gravity.START)
            lp.leftMargin = left
            lp.topMargin = tabBar.getTop()
            center.setTranslationX(0f)
            center.setLayoutParams(lp)
        } catch (ignored: Throwable) {
        }
    }

    private fun centerTabsRow(tabBar: ViewGroup) {
        try {
            if (tabBar.getChildCount() == 0) {
                return
            }
            val row: View? = tabBar.getChildAt(0)
            if (row == null || row.getLayoutParams()
                !is FrameLayout.LayoutParams
            ) {
                return
            }
            val rlp = row.getLayoutParams() as FrameLayout.LayoutParams
            val want = Gravity.CENTER_VERTICAL or
                    Gravity.FILL_HORIZONTAL
            if (rlp.gravity == want) {
                return
            }
            rlp.gravity = want
            row.setLayoutParams(rlp)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("center tabs row failed", t)
        }
    }

    private fun applyBarSideMargins(host: ViewGroup) {
        try {
            if (host.getLayoutParams() !is RelativeLayout.LayoutParams) {
                return
            }
            val lp = host.getLayoutParams() as RelativeLayout.LayoutParams
            val parent: View? = if (host.getParent() is View) {
                host.getParent() as View
            } else {
                null
            }
            var parentWidth = if (parent != null) parent.getWidth() else 0
            if (parentWidth <= 0) {
                parentWidth = host.getResources()
                    .getDisplayMetrics().widthPixels
            }
            if (parentWidth <= 0) {
                return
            }
            val den = if (sDensity > 0) {
                sDensity
            } else {
                host.getResources().getDisplayMetrics().density
            }
            val side = sideMarginPx(den)
            val usable = Math.max(0, parentWidth - side * 2)
            val target = when (GlassConfig.barWidthMode) {
                0 -> adaptiveBarWidth(host, parentWidth)
                2 -> {
                    val pct = Math.max(40,
                        Math.min(GlassConfig.barWidthPct, 100))
                    Math.round(usable * pct / 100f)
                }
                else -> usable
            }
            val clamped = Math.max(0, Math.min(target, usable))
            val margin = side + Math.max(0, (usable - clamped) / 2)
            if (lp.leftMargin == margin && lp.rightMargin == margin) {
                return
            }
            lp.leftMargin = margin
            lp.rightMargin = margin
            host.setLayoutParams(lp)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("apply bar side margins failed", t)
        }
    }

    private fun adaptiveBarWidth(host: View, parentWidth: Int): Int {
        val den = if (sDensity > 0) {
            sDensity
        } else {
            host.getResources().getDisplayMetrics().density
        }
        val radio = sRadioBarRef.get()
        var tabs = 0
        if (radio != null) {
            for (i in 0 until radio.getChildCount()) {
                val c = radio.getChildAt(i)
                if (c is RadioButton
                    && c.getVisibility() == View.VISIBLE
                ) {
                    tabs++
                }
            }
        }
        val max = Math.max(0, parentWidth - sideMarginPx(den) * 2)
        if (tabs == 0) {
            return max
        }
        val metrics = adaptiveMetricsDp(den)
        val w = (tabs * metrics[0] + metrics[1]) * den + Math.round(8f * den)
        return Math.max(Math.round(MIN_TAB_WIDTH_DP * den),
            Math.min(Math.round(w), max))
    }

    private fun sideMarginPx(den: Float): Int {
        val dp = Math.max(0, Math.min(
            GlassConfig.barSideMarginDp, MAX_SIDE_MARGIN_DP))
        return Math.round(dp * den)
    }

    private fun adaptiveMetricsDp(den: Float): FloatArray {
        val scale = Math.max(50, Math.min(GlassConfig.tabWidthPct, 150)) / 100f
        val content = measureTabContentDp(den)
        var tab = (if (content > 0f) {
            content + TAB_CONTENT_PAD_DP
        } else {
            FALLBACK_TAB_WIDTH_DP
        }) * scale
        tab = Math.max(MIN_TAB_WIDTH_DP * scale,
            Math.min(tab, FIT_TAB_MAX_WIDTH_DP * scale))
        if (content > 0f) {
            tab = Math.max(tab, content)
        }
        val gap = if (sCircleMode || sPlusHidden) 0f else plusGapDp(den, tab)
        return floatArrayOf(tab, gap)
    }

    private fun plusGapDp(den: Float, tabDp: Float): Float {
        var gap = MIN_PLUS_GAP_DP
        try {
            val mid = sMidTabRef.get()
            val w = if (mid == null) 0 else mid.getMeasuredWidth()
            if (w > 0) {
                gap = Math.max(gap, w / den + 8f)
            }
        } catch (ignored: Throwable) {
        }
        return Math.max(gap, tabDp)
    }

    private fun measureTabContentDp(den: Float): Float {
        try {
            val barV = sTabBarRef.get()
            if (barV !is ViewGroup || barV.getChildCount() == 0
                || barV.getChildAt(0) !is ViewGroup
            ) {
                return 0f
            }
            val row = barV.getChildAt(0) as ViewGroup
            var best = 0
            for (i in 0 until row.getChildCount()) {
                val tab = row.getChildAt(i)
                if (tab !is ViewGroup
                    || tab is Space
                ) {
                    continue
                }
                val w = tabContentPx(tab, den)
                if (w > best) {
                    best = w
                }
            }
            return if (best > 0) best / den else 0f
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("tab content measure failed", t)
            return 0f
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun tabContentPx(tab: ViewGroup, den: Float): Int {
        var best = 0
        for (i in 0 until tab.getChildCount()) {
            val c = tab.getChildAt(i)
            if (c.getVisibility() == View.GONE) {
                continue
            }
            var w = 0
            if (c is TextView) {
                val text = c.getText()
                if (text != null && text.length > 0) {
                    w = Math.round(c.getPaint()
                        .measureText(text.toString()))
                }
            } else if (c is ImageView) {
                val icon = c.getDrawable()
                if (icon != null) {
                    w = icon.getIntrinsicWidth()
                }
                if (w <= 0) {
                    w = c.getMeasuredWidth()
                }
            } else {
                w = c.getMeasuredWidth()
            }
            if (w > best) {
                best = w
            }
        }
        if (best <= 0) {
            return 0
        }
        return best + tab.getPaddingLeft() + tab.getPaddingRight()
    }

    @JvmStatic
    fun applyBarGeometry() {
        sSnapWidthChanges = true
        try {
            val barV = sTabBarRef.get()
            val host = sHostRef
            val center = sCenterRefStatic
            if (barV !is ViewGroup || host == null
                || barV.getLayoutParams()
                !is FrameLayout.LayoutParams
            ) {
                return
            }
            val den = if (sDensity > 0) sDensity else 3f
            val hDp = GlassConfig.barHeightDp
            val blp = barV.getLayoutParams() as FrameLayout.LayoutParams
            blp.height = if (hDp <= 0) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                Math.round(hDp * den)
            }
            barV.setLayoutParams(blp)

            centerTabsRow(barV)

            val off = Math.max(0, GlassConfig.barOffsetDp)
            host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                host.getPaddingRight(), sBasePadBottom +
                        Math.round(off * den))

            applyBarSideMargins(host)
            val tabLayoutChanged = applyTabWidths()

            host.requestLayout()
            if (tabLayoutChanged
                && barV is LiquidGlassTabBar
            ) {
                reanimateDroplet(barV)
            }
            host.post {
                sSnapWidthChanges = true
                try {
                    applyTabWidths()
                } finally {
                    sSnapWidthChanges = false
                }
                placeCenterNow(host, barV, center, 0)
            }
            val radio = sRadioBarRef.get()
            if (radio != null
                && barV is LiquidGlassTabBar
            ) {
                applyBarMode(radio, barV)
            }
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("applyBarGeometry failed", t)
        } finally {
            sSnapWidthChanges = false
        }
    }

    private fun installRepeatClickRefresh(tabBar: LiquidGlassTabBar?) {
        if (tabBar == null) {
            return
        }
        val down = FloatArray(2)
        val selectedBefore = IntArray(1)
        val moved = booleanArrayOf(false)
        tabBar.setOnTouchListener { view, event ->
            try {
                val action = event.getActionMasked()
                if (action == MotionEvent.ACTION_DOWN) {
                    down[0] = event.getX()
                    down[1] = event.getY()
                    selectedBefore[0] = tabBar.selectedIndex
                    moved[0] = false
                } else if (action == MotionEvent.ACTION_MOVE) {
                    val slop: Float = ViewConfiguration.get(view.getContext())
                        .getScaledTouchSlop().toFloat()
                    val dx = event.getX() - down[0]
                    val dy = event.getY() - down[1]
                    moved[0] = moved[0] || dx * dx + dy * dy > slop * slop
                } else if (action == MotionEvent.ACTION_UP) {
                    if (moved[0]) {
                        val near = nearestTabIndex(tabBar)
                        if (near >= 0) {
                            prepareSelectionLayout(tabBar, near)
                        }
                    } else {
                        val target = findTabIndexAt(tabBar, event.getX())
                        if (target >= 0) {
                            prepareSelectionLayout(tabBar, target)
                        }
                        val before = selectedBefore[0]
                        tabBar.post {
                            try {
                                if (target != before || target != tabBar.selectedIndex
                                    || target < 0 || target >= sVisibleButtons.size
                                ) {
                                    return@post
                                }
                                val button = sVisibleButtons[target]
                                if (button != null) {
                                    button.performClick()
                                }
                            } catch (t: Throwable) {
                                LiquidGlassLog.logErr(
                                    "repeat tab refresh failed", t)
                            }
                        }
                    }
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    moved[0] = false
                }
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("repeat tab touch failed", t)
            }
            false
        }
    }

    private fun findTabIndexAt(
        tabBar: LiquidGlassTabBar?, x: Float): Int {
        if (tabBar == null || tabBar.getChildCount() <= 0
            || tabBar.getChildAt(0) !is ViewGroup
        ) {
            return -1
        }
        val row = tabBar.getChildAt(0) as ViewGroup
        val localX = x - row.getLeft()
        var index = 0
        for (childIndex in 0 until row.getChildCount()) {
            val child = row.getChildAt(childIndex)
            if (child !is LinearLayout) {
                continue
            }
            if (localX >= child.getLeft() && localX <= child.getRight()) {
                return index
            }
            index++
        }
        return -1
    }

    private fun interface OnCheckedExtra {
        fun onChecked(group: RadioGroup, checkedId: Int)
    }

    private var sOriginalCheckedListener: RadioGroup.OnCheckedChangeListener? = null
    private var sWrappedListener: RadioGroup.OnCheckedChangeListener? = null
    private var sMidPreDraw: ViewTreeObserver.OnPreDrawListener? = null

    private fun wrapCheckedListener(bar: RadioGroup,
                                    extra: OnCheckedExtra) {
        try {
            val f = RadioGroup::class.java
                .getDeclaredField("mOnCheckedChangeListener")
            f.setAccessible(true)
            val original = f.get(bar)
            sOriginalCheckedListener =
                if (original is RadioGroup.OnCheckedChangeListener) {
                    original
                } else {
                    null
                }
            val wrapper = RadioGroup.OnCheckedChangeListener { group, checkedId ->
                sOriginalCheckedListener?.onCheckedChanged(group, checkedId)
                try {
                    extra.onChecked(group, checkedId)
                } catch (ignored: Throwable) {
                }
            }
            sWrappedListener = wrapper
            bar.setOnCheckedChangeListener(wrapper)
        } catch (t: Throwable) {
            LiquidGlassLog.log(Log.WARN,
                "checked listener wrap unavailable: " + t)
        }
    }

    private fun unwrapCheckedListener(barView: ViewGroup?) {
        if (barView !is RadioGroup
            || sWrappedListener == null
        ) {
            return
        }
        val bar = barView
        try {
            val f = RadioGroup::class.java
                .getDeclaredField("mOnCheckedChangeListener")
            f.setAccessible(true)
            if (f.get(bar) === sWrappedListener) {
                bar.setOnCheckedChangeListener(sOriginalCheckedListener)
            }
        } catch (ignored: Throwable) {
        }
        sWrappedListener = null
        sOriginalCheckedListener = null
    }

    private fun removeMidPreDraw(midTab: View?) {
        val listener = sMidPreDraw
        if (midTab == null || listener == null) {
            return
        }
        try {
            midTab.getViewTreeObserver().removeOnPreDrawListener(listener)
        } catch (ignored: Throwable) {
        }
        sMidPreDraw = null
    }

    private fun setupTabSelectionSync(bar: RadioGroup,
                                      tabBar: LiquidGlassTabBar) {
        wrapCheckedListener(bar) { _, checkedId ->
            for (i in 0 until sVisibleButtons.size) {
                if (sVisibleButtons[i].getId() == checkedId
                    && tabBar.selectedIndex != i
                ) {
                    prepareSelectionLayout(tabBar, i)
                    tabBar.selectedIndex = i
                    break
                }
            }
        }
    }

    @Volatile
    private var sTintHookInstalled = false

    @Volatile
    private var sChromeLight = false

    @Volatile
    private var sChromeForced = false

    @Volatile
    private var sHostRef: ViewGroup? = null

    @Volatile
    private var sCenterRefStatic: View? = null
    private val EMPTY_MID_REF: WeakReference<View> =
        WeakReference<View>(null)

    @Volatile
    private var sMidTabRef: WeakReference<View> =
        EMPTY_MID_REF

    @Volatile
    private var sPlusHidden = false
    private val EMPTY_RADIO_REF: WeakReference<RadioGroup> =
        WeakReference<RadioGroup>(null)

    @Volatile
    private var sRadioBarRef: WeakReference<RadioGroup> =
        EMPTY_RADIO_REF

    @Volatile
    private var sGlassCircleRef: WeakReference<View>? = null

    @Volatile
    private var sContentViewRef: WeakReference<View>? = null

    @Volatile
    private var sCircleMode = false

    @Volatile
    private var sLastTabs = -1

    @Volatile
    private var sStableTabs = -1

    @Volatile
    private var sDensity = 0f
    private var sBasePadBottom = 0
    private val EMPTY_BAR_REF: WeakReference<View> =
        WeakReference<View>(null)

    @Volatile
    private var sTabBarRef: WeakReference<View> = EMPTY_BAR_REF

    private val sVisibleButtons: MutableList<RadioButton> =
        ArrayList()

    @Volatile
    private var sSyncing = false

    @Volatile
    private var sBuildSig = ""
    private var sTabShiftAnimator: ValueAnimator? = null
    private var sBarWidthAnimator: ValueAnimator? = null
    private var sDropletSizeAnimator: ValueAnimator? = null
    private var sBarTargetWidth = Int.MIN_VALUE
    private var sBarTargetLeft = Int.MIN_VALUE
    private var sBarTargetGravity = Int.MIN_VALUE
    private var sFitActive = false
    private var sSnapWidthChanges = false
    private var sCenterAnimator: ValueAnimator? = null
    private var sCenterTargetLeft = Int.MIN_VALUE

    @JvmStatic
    fun activeGlassHost(): View? {
        val host = sHostRef
        if (host == null || !host.isAttachedToWindow() || host.getHeight() <= 0) {
            return null
        }
        return host
    }

    private fun installTintOverride() {
        if (sTintHookInstalled) {
            return
        }
        sTintHookInstalled = true
        try {
            val m = LiquidGlassView::class.java
                .getDeclaredMethod("currentTintColor")
            LiquidGlassHookBridge.hookExecutable(m) { chain ->
                var tint = 0x30FFFFFF
                try {
                    val thiz = chain.getThisObject()
                    var mode = 0
                    var isBarView = false
                    if (thiz is View) {
                        mode = thiz.getResources()
                            .getConfiguration().uiMode and
                                Configuration.UI_MODE_NIGHT_MASK
                        isBarView = thiz === sTabBarRef.get()
                    }
                    val light =
                        mode != Configuration.UI_MODE_NIGHT_YES
                    if (light) {
                        tint = GlassConfig.lightTint()
                    } else {
                        tint = GlassConfig.darkTint()
                    }
                    if (!isBarView) {
                        val a = tint ushr 24
                        val na = Math.round(a * 0.6f)
                        tint = (na shl 24) or (tint and 0x00FFFFFF)
                    }
                } catch (ignored: Throwable) {
                }
                tint
            }
            LiquidGlassLog.log(Log.INFO,
                "currentTintColor override hooked (theme body)")
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("currentTintColor hook failed", t)
        }
    }

    private val sGlassEntries: MutableMap<View, Boolean> =
        Collections.synchronizedMap(
            WeakHashMap<View, Boolean>())

    @JvmStatic
    fun installSettingsEntries(cl: ClassLoader) {
        try {
            val htb = Class.forName(
                "com.max.hbcommon.component.HomeTitleBar", false, cl)
            val g = htb.getMethod("getIv_home_search")
            LiquidGlassHookBridge.hookExecutable(g) { chain ->
                val r = chain.proceed()
                try {
                    if (r is View && !sGlassEntries.containsKey(r)) {
                        val icon = r
                        val cx = icon.getContext()
                        if (cx is Activity && !GlassProvider.prefersHbmod(cx)) {
                            sGlassEntries[icon] = true
                            val a = cx
                            icon.setOnLongClickListener {
                                GlassSettingsSheet.show(a)
                                true
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                }
                r
            }
            LiquidGlassLog.log(Log.INFO,
                "title-bar glass entry hook installed")
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("title-bar entry hook failed", t)
        }
        try {
            val sa = Class.forName(
                "com.max.xiaoheihe.module.account.SettingActivity", false, cl)
            var onResume: java.lang.reflect.Method? = null
            var c: Class<*>? = sa
            while (c != null && c != Any::class.java) {
                try {
                    onResume = c.getDeclaredMethod("onResume")
                    break
                } catch (ignored: NoSuchMethodException) {
                }
                c = c.getSuperclass()
            }
            if (onResume == null) {
                LiquidGlassLog.log(Log.WARN,
                    "SettingActivity onResume not found")
                return
            }
            LiquidGlassHookBridge.hookExecutable(onResume) { chain ->
                val r = chain.proceed()
                try {
                    val self = chain.getThisObject()
                    if (self is Activity) {
                        val a = self
                        a.getWindow().getDecorView().postDelayed(
                            { injectGlassSettingsRow(a, 0) }, 200L)
                    }
                } catch (ignored: Throwable) {
                }
                r
            }
            LiquidGlassLog.log(Log.INFO,
                "SettingActivity glass row hook installed")
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("SettingActivity entry hook failed", t)
        }
    }

    private fun injectGlassSettingsRow(activity: Activity, attempt: Int) {
        try {
            if (activity.isFinishing() || activity.isDestroyed() || attempt > 10) {
                return
            }
            if (GlassProvider.prefersHbmod(activity)) {
                return
            }
            val id = activity.getResources().getIdentifier(
                "vg_general_settings", "id", activity.getPackageName())
            val row = if (id != 0) activity.findViewById<View>(id) else null
            if (row == null) {
                activity.getWindow().getDecorView().postDelayed(
                    { injectGlassSettingsRow(activity, attempt + 1) }, 200L)
                return
            }
            if (sGlassEntries.containsKey(row)) {
                return
            }
            sGlassEntries[row] = true
            row.setOnLongClickListener {
                GlassSettingsSheet.show(activity)
                true
            }
            LiquidGlassLog.log(Log.INFO,
                "glass settings row attached (long-press 通用设置)")
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("glass settings row failed", t)
        }
    }

    private fun setupTabPopAnimation(bar: ViewGroup) {
        if (bar !is RadioGroup) {
            return
        }
        wrapCheckedListener(bar) { group, checkedId ->
            if (group.getParent() is LiquidGlassHostLayout) {
                (group.getParent() as LiquidGlassHostLayout)
                    .popChild(group.findViewById<View>(checkedId))
            }
        }
    }

    private fun hideLegacyShadow(root: ViewGroup, barId: Int): View? {
        for (i in 0 until root.getChildCount()) {
            val child = root.getChildAt(i)
            if (child.javaClass == View::class.java && child.getBackground() != null) {
                val lp = child.getLayoutParams()
                if (lp is RelativeLayout.LayoutParams) {
                    if (lp
                            .getRules()[RelativeLayout.ABOVE] == barId
                    ) {
                        child.setVisibility(View.GONE)
                        return child
                    }
                }
            }
        }
        return null
    }

    private fun copyMargins(src: RelativeLayout.LayoutParams,
                            dst: RelativeLayout.LayoutParams) {
        dst.leftMargin = src.leftMargin
        dst.topMargin = src.topMargin
        dst.rightMargin = src.rightMargin
        dst.bottomMargin = src.bottomMargin
    }

    private fun computeNavInsetPadding(activity: Activity,
                                       root: ViewGroup, bar: ViewGroup): Int {
        try {
            val wi = root.getRootWindowInsets()
            var nav = 0
            if (wi == null) {
                return 0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                nav = wi.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                nav = wi.getSystemWindowInsetBottom()
            }
            if (nav <= 0) {
                return 0
            }
            val decor = activity.getWindow().getDecorView()
            val loc = IntArray(2)
            decor.getLocationOnScreen(loc)
            val decorBottom = loc[1] + decor.getHeight()
            val realBottom = getRealDisplayBottom(activity)
            if (realBottom <= 0) {
                return 0
            }
            val gap = realBottom - decorBottom
            val extra = Math.max(nav - gap, 0)
            val density = root.getResources().getDisplayMetrics().density
            val capped = Math.min(extra.toFloat(), density * 56f).toInt()
            val existing = Math.max(bar.getPaddingBottom(), root.getPaddingBottom())
            return Math.max(capped - existing, 0)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("computeNavInsetPadding failed", t)
            return 0
        }
    }

    private fun getRealDisplayBottom(activity: Activity): Int {
        try {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return 0
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

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T : View> findViewByName(activity: Activity, name: String): T? {
        try {
            val id = activity.getResources()
                .getIdentifier(name, "id", activity.getPackageName())
            if (id == 0) {
                return null
            }
            return activity.findViewById<View>(id) as T
        } catch (t: Throwable) {
            return null
        }
    }
}
