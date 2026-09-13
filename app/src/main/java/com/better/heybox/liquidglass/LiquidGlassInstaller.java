package com.better.heybox.liquidglass;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.better.heybox.GlassProvider;

public final class LiquidGlassInstaller {

    private static final String ID_ROOT = "vg_main_root";
    private static final String ID_BAR = "rg_main";
    private static final String ID_TIPS = "vg_tips";
    private static final String ID_MID_TAB = "vg_mid_tab";
    private static final String ID_CONTENT = "fl_container";
    private static final String ID_VIDEO_FULL = "vg_fullscreen_video_container";

    private LiquidGlassInstaller() {
    }

    public static boolean isGlassEnabled(Activity activity) {
        try {
            com.better.heybox.HeyboxPrefs.init(activity);
            return com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_LIQUID_GLASS, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isGlassBarActive() {
        try {
            View bar = sTabBarRef != null ? sTabBarRef.get() : null;
            return bar != null && bar.isAttachedToWindow();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean readPublishHidden(Context context) {        try {
            com.better.heybox.HeyboxPrefs.init(context);
            return com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_HIDE_ADD, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 重新断言窗口级小白条沉浸（宿主 onResume 可能重设导航栏颜色，需覆盖回去） */
    static void applyImmersive(Activity activity) {
        try {
            GlassConfig.load(activity);
            WindowImmersiveController.apply(activity);
        } catch (Throwable ignored) {
        }
    }

    /** 底栏只存在于 MainActivity；设置入口运行在宿主设置页，操作必须落到这个引用上 */
    private static volatile java.lang.ref.WeakReference<Activity> sMainRef;

    private static Activity mainActivity() {
        java.lang.ref.WeakReference<Activity> ref = sMainRef;
        Activity main = ref != null ? ref.get() : null;
        return main != null && !main.isFinishing() && !main.isDestroyed() ? main : null;
    }

    /**
     * 切换液态玻璃开关后立即生效：开=安装玻璃，关=卸载玻璃恢复经典底栏。
     * 沉浸式小白条独立于玻璃开关，两条路径都会按配置应用/还原
     */
    public static void applyGlassEnabled(Activity activity) {
        try {
            if (GlassProvider.prefersHbmod(activity)) {
                return;
            }
            Activity main = mainActivity();
            if (main == null) {
                return;
            }
            if (isGlassEnabled(activity)) {
                scheduleInstall(main);
            } else {
                uninstallGlass(main);
                applyImmersive(main);
                applyClassicImmersive(main);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("apply glass enabled failed", t);
        }
    }

    public static void scheduleInstall(Activity activity) {
        sMainRef = new java.lang.ref.WeakReference<>(activity);
        if (GlassProvider.prefersHbmod(activity)) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        decor.post(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                applyImmersive(activity);
                if (!isGlassEnabled(activity)) {
                    applyClassicImmersive(activity);
                    return;
                }
                ViewGroup root = findViewByName(activity, ID_ROOT);
                if (root == null) {
                    LiquidGlassLog.log(android.util.Log.WARN,
                            "root view " + ID_ROOT + " not found, retry in 200ms");
                    final int gen = sSyncGen;
                    decor.postDelayed(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()
                                && gen == sSyncGen && isGlassEnabled(activity)) {
                            ViewGroup r = findViewByName(activity, ID_ROOT);
                            if (r != null) {
                                install(activity, r);
                            }
                        }
                    }, 200L);
                    return;
                }
                if (root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG) != null) {
                    // 已安装：onResume / 底栏回调重入，无需重复安装
                    return;
                }
                install(activity, root);
            } catch (Throwable t) {
                LiquidGlassLog.logErr("scheduleInstall failed", t);
            }
        });
    }

    /** 安装前宿主底栏原始状态，运行时卸载玻璃按此还原 */
    private static final class BarSnapshot {
        int barIndex = -1;
        int tipsIndex = -1;
        int midIndex = -1;
        ViewGroup.LayoutParams barLp;
        ViewGroup.LayoutParams tipsLp;
        ViewGroup.LayoutParams midLp;
        android.graphics.drawable.Drawable barBackground;
        int barPadLeft, barPadTop, barPadRight, barPadBottom;
        int contentAboveRule;
        View legacyShadow;
    }

    private static volatile BarSnapshot sSnapshot;
    /** 递增使旧的 500ms 轮询自行终止，避免卸载后继续驱动玻璃逻辑 */
    private static volatile int sSyncGen;

    /** 运行时卸载玻璃底栏并把宿主原始底栏装回原位（关闭液态玻璃免重启） */
    private static void uninstallGlass(Activity activity) {
        try {
            ViewGroup root = findViewByName(activity, ID_ROOT);
            if (root == null || root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG) == null) {
                return;
            }
            BarSnapshot snap = sSnapshot;
            sSyncGen++;
            cancelGlassAnimators();
            // 摘视图要在 host 还挂在窗口树上时做，findViewById 才找得到
            ViewGroup bar = findViewByName(activity, ID_BAR);
            ViewGroup tips = findViewByName(activity, ID_TIPS);
            View midTab = findViewByName(activity, ID_MID_TAB);
            View content = findViewByName(activity, ID_CONTENT);
            unwrapCheckedListener(bar);
            removeMidPreDraw(midTab);
            if (content != null && snap != null
                    && content.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams clp =
                        (RelativeLayout.LayoutParams) content.getLayoutParams();
                clp.getRules()[RelativeLayout.ABOVE] = snap.contentAboveRule;
                content.setLayoutParams(clp);
            }
            if (snap != null && snap.legacyShadow != null) {
                snap.legacyShadow.setVisibility(View.VISIBLE);
            }
            View host = root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG);
            // 必须先把三件套从 host / center 包装里摘除，否则 addView 会因
            // "child already has a parent" 抛异常，底栏就永远装不回去了
            removeFromParent(bar);
            removeFromParent(tips);
            removeFromParent(midTab);
            if (host != null) {
                root.removeView(host);
            }
            // 按原始索引升序装回：宿主其余子视图都在原相对位置，直接用原索引即可还原 z 序
            if (bar != null && snap != null) {
                bar.setBackground(snap.barBackground);
                bar.setPadding(snap.barPadLeft, snap.barPadTop,
                        snap.barPadRight, snap.barPadBottom);
                bar.setVisibility(View.VISIBLE);
                root.addView(bar, readdIndex(snap.barIndex, root), snap.barLp);
            }
            if (tips != null && snap != null && snap.tipsLp != null) {
                root.addView(tips, readdIndex(snap.tipsIndex, root), snap.tipsLp);
            }
            if (midTab != null && snap != null && snap.midLp != null) {
                midTab.setTranslationX(0);
                midTab.setVisibility(View.VISIBLE);
                restoreChildren(midTab);
                root.addView(midTab, readdIndex(snap.midIndex, root), snap.midLp);
            }
            resetGlassState();
            root.requestLayout();
            LiquidGlassLog.log(android.util.Log.INFO, "liquid glass uninstalled");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("uninstall glass failed", t);
        }
    }

    private static int readdIndex(int originalIndex, ViewGroup root) {
        return Math.max(0, Math.min(originalIndex, root.getChildCount()));
    }

    private static void removeFromParent(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private static void cancelGlassAnimators() {
        if (sTabShiftAnimator != null) {
            sTabShiftAnimator.cancel();
        }
        if (sBarWidthAnimator != null) {
            sBarWidthAnimator.cancel();
        }
        if (sDropletSizeAnimator != null) {
            sDropletSizeAnimator.cancel();
        }
        if (sCenterAnimator != null) {
            sCenterAnimator.cancel();
        }
    }

    private static void resetGlassState() {
        sTabBarActive = false;
        sTabBarRef = EMPTY_BAR_REF;
        sRadioBarRef = EMPTY_RADIO_REF;
        sGlassCircleRef = null;
        sContentViewRef = null;
        sHostRef = null;
        sCenterRefStatic = null;
        sMidTabRef = EMPTY_MID_REF;
        sPlusHidden = false;
        sCircleMode = false;
        sLastTabs = -1;
        sStableTabs = -1;
        sBuildSig = "";
        sSyncing = false;
        sVisibleButtons.clear();
        sBasePadBottom = 0;
        sInstallPadBottom = 0;
        sNavPad = 0;
        sSnapshot = null;
        resetWidthAnimState();
    }

    /** 经典底栏沉浸基线（rg_main 原高/原内边距、加号原下边距） */
    private static int sClassicBarHeight = -1;
    private static int sClassicBarPadBottom = -1;
    private static int sClassicMidMargin = -1;
    private static volatile boolean sClassicImmersive;

    /**
     * 无玻璃时的沉浸式小白条：窗口级透明由 apply() 负责，这里把 rg_main 加高
     * navPad 并同步内边距，让底栏背景延伸进手势区而内容仍避开小白条
     */
    private static void applyClassicImmersive(final Activity activity) {
        try {
            ViewGroup root = findViewByName(activity, ID_ROOT);
            ViewGroup bar = findViewByName(activity, ID_BAR);
            if (root == null || bar == null) {
                activity.getWindow().getDecorView().postDelayed(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()
                            && sHostRef == null) {
                        applyClassicImmersive(activity);
                    }
                }, 200L);
                return;
            }
            View midTab = findViewByName(activity, ID_MID_TAB);
            if (!GlassConfig.immersiveGestureNavigation) {
                restoreClassicImmersive(bar, midTab);
                return;
            }
            int navPad = computeClassicNavPad(root);
            if (navPad <= 0) {
                restoreClassicImmersive(bar, midTab);
                return;
            }
            if (!sClassicImmersive) {
                sClassicBarHeight = bar.getLayoutParams().height;
                sClassicBarPadBottom = bar.getPaddingBottom();
                if (midTab != null && midTab.getLayoutParams()
                        instanceof RelativeLayout.LayoutParams) {
                    sClassicMidMargin = ((RelativeLayout.LayoutParams)
                            midTab.getLayoutParams()).bottomMargin;
                }
                sClassicImmersive = true;
            }
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            if (sClassicBarHeight > 0) {
                lp.height = sClassicBarHeight + navPad;
                bar.setLayoutParams(lp);
            }
            bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
                    bar.getPaddingRight(), sClassicBarPadBottom + navPad);
            if (midTab != null && sClassicMidMargin >= 0 && midTab.getLayoutParams()
                    instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams mlp =
                        (RelativeLayout.LayoutParams) midTab.getLayoutParams();
                mlp.bottomMargin = sClassicMidMargin + navPad;
                midTab.setLayoutParams(mlp);
            }
            bar.requestLayout();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("classic immersive failed", t);
        }
    }

    private static void restoreClassicImmersive(ViewGroup bar, View midTab) {
        if (!sClassicImmersive) {
            return;
        }
        try {
            if (bar != null) {
                if (sClassicBarHeight > 0) {
                    bar.getLayoutParams().height = sClassicBarHeight;
                    bar.setLayoutParams(bar.getLayoutParams());
                }
                bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
                        bar.getPaddingRight(), Math.max(sClassicBarPadBottom, 0));
            }
            if (midTab != null && sClassicMidMargin >= 0 && midTab.getLayoutParams()
                    instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams mlp =
                        (RelativeLayout.LayoutParams) midTab.getLayoutParams();
                mlp.bottomMargin = sClassicMidMargin;
                midTab.setLayoutParams(mlp);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("restore classic immersive failed", t);
        }
        sClassicImmersive = false;
    }

    /**
     * 与玻璃路径不同：不扣 decor 底部空隙——apply() 已声明 LAYOUT_HIDE_NAVIGATION，
     * decor 即将延伸到屏幕底，直接按导航栏 inset 取值
     */
    private static int computeClassicNavPad(ViewGroup root) {
        try {
            WindowInsets wi = root.getRootWindowInsets();
            if (wi == null) {
                return 0;
            }
            int nav = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? wi.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : wi.getSystemWindowInsetBottom();
            if (nav <= 0) {
                return 0;
            }
            float density = root.getResources().getDisplayMetrics().density;
            return (int) Math.min(nav, density * 56f);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void install(Activity activity, ViewGroup root) {
        if (root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG) != null) {
            return;
        }
        // 卸载会递增代数：回调/重试与卸载竞态时以代数判废
        final int gen = sSyncGen;
        ViewGroup bar = findViewByName(activity, ID_BAR);
        if (bar == null || bar.getParent() != root) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "nav bar " + ID_BAR + " not found (bar=" + (bar != null)
                            + ", parentMatch="
                            + (bar != null && bar.getParent() == root) + ")");
            return;
        }
        ViewGroup tips = findViewByName(activity, ID_TIPS);
        View midTab = findViewByName(activity, ID_MID_TAB);
        ViewGroup content = findViewByName(activity, ID_CONTENT);
        View videoFull = findViewByName(activity, ID_VIDEO_FULL);

        // 玻璃即将接管手势区避让，先撤销经典避让，避免快照与基准值被污染
        restoreClassicImmersive(bar, midTab);

        BarSnapshot snap = new BarSnapshot();
        snap.barIndex = root.indexOfChild(bar);
        snap.tipsIndex = tips != null ? root.indexOfChild(tips) : -1;
        snap.midIndex = midTab != null ? root.indexOfChild(midTab) : -1;
        snap.barLp = bar.getLayoutParams();
        snap.tipsLp = tips != null ? tips.getLayoutParams() : null;
        snap.midLp = midTab != null ? midTab.getLayoutParams() : null;
        snap.barBackground = bar.getBackground();
        snap.barPadLeft = bar.getPaddingLeft();
        snap.barPadTop = bar.getPaddingTop();
        snap.barPadRight = bar.getPaddingRight();
        snap.barPadBottom = bar.getPaddingBottom();
        snap.contentAboveRule = content != null
                && content.getLayoutParams() instanceof RelativeLayout.LayoutParams
                ? ((RelativeLayout.LayoutParams) content.getLayoutParams())
                        .getRules()[RelativeLayout.ABOVE] : 0;
        sSnapshot = snap;

        Context ctx = root.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int sideMargin = Math.round(density * 10f);

        RelativeLayout.LayoutParams barLp = (RelativeLayout.LayoutParams) bar.getLayoutParams();
        RelativeLayout.LayoutParams hostLp = new RelativeLayout.LayoutParams(
                barLp.width, RelativeLayout.LayoutParams.WRAP_CONTENT);
        copyMargins(barLp, hostLp);
        hostLp.leftMargin += sideMargin;
        hostLp.rightMargin += sideMargin;
        hostLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        int[] hostRules = hostLp.getRules();
        for (int i = 0; i < hostRules.length; i++) {
            if (i != RelativeLayout.ALIGN_PARENT_BOTTOM) {
                hostRules[i] = 0;
            }
        }

        snap.legacyShadow = hideLegacyShadow(root, bar.getId());

        int barHeightSpec = bar.getLayoutParams().height;
        int navPad = computeNavInsetPadding(activity, root, bar);
        int origBarPadBottom = bar.getPaddingBottom();
        sNavPad = navPad;
        sInstallPadBottom = origBarPadBottom + navPad;

        LiquidGlassHostLayout host = new LiquidGlassHostLayout(ctx, root, bar);

        root.removeView(bar);
        if (tips != null && tips.getParent() == root) {
            root.removeView(tips);
        }
        if (midTab != null && midTab.getParent() == root) {
            root.removeView(midTab);
        }

        int insertAt = videoFull != null && videoFull.getParent() == root
                ? root.indexOfChild(videoFull)
                : root.getChildCount();
        root.addView(host, insertAt, hostLp);
        sHostRef = host;
        applyBarSideMargins(host);

        FrameLayout.LayoutParams barFlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeightSpec > 0 ? barHeightSpec : ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);
        bar.setBackground(null);
        bar.setPadding(
                bar.getPaddingLeft(), bar.getPaddingTop(),
                bar.getPaddingRight(), 0);
        host.addView(bar, 0, barFlp);

        if (tips != null) {
            FrameLayout.LayoutParams tipsFlp = new FrameLayout.LayoutParams(
                    tips.getLayoutParams().width,
                    tips.getLayoutParams().height,
                    android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);
            host.addView(tips, 1, tipsFlp);
        }
        if (midTab != null) {
            RelativeLayout.LayoutParams midLp =
                    (RelativeLayout.LayoutParams) midTab.getLayoutParams();
            FrameLayout.LayoutParams midFlp = new FrameLayout.LayoutParams(
                    midLp.width, midLp.height,
                    android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM);
            midFlp.leftMargin = midLp.leftMargin;
            midFlp.topMargin = midLp.topMargin;
            midFlp.rightMargin = midLp.rightMargin;
            midFlp.bottomMargin = midLp.bottomMargin;
            host.addView(midTab, host.getChildCount(), midFlp);
        }

        host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                host.getPaddingRight(), origBarPadBottom + navPad);

        attachGlassRenderer(activity, host, bar, tips, midTab, content, barHeightSpec, navPad);

        if (content != null && content.getParent() == root) {
            RelativeLayout.LayoutParams clp =
                    (RelativeLayout.LayoutParams) content.getLayoutParams();
            clp.getRules()[RelativeLayout.ABOVE] = 0;
            content.setLayoutParams(clp);
        }

        root.requestLayout();
        root.invalidate();

        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean attached;
                    @Override
                    public void onGlobalLayout() {
                        if (attached) {
                            return;
                        }
                        attached = true;
                        root.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        if (gen != sSyncGen) {
                            // 玻璃在首次布局前已被卸载，本轮回调作废
                            return;
                        }
                        host.attach();
                        applyImmersive(activity);
                        if (!sTabBarActive) {
                            setupTabPopAnimation(bar);
                        }
                        InWindowTipWatcher.start(activity);
                        LiquidGlassLog.log(android.util.Log.INFO,
                                "liquid glass installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " navPad=" + navPad
                                        + " barH=" + bar.getHeight()
                                        + " children=" + host.getChildCount());
                    }
                });
    }

    private static final float CENTER_GAP_WEIGHT = 1.0f;
    private static final float FIT_TAB_MAX_WIDTH_DP = 96f;
    private static final float SELECTED_TAB_WEIGHT = 1.4f;
    private static final float OTHER_TAB_WEIGHT = 0.9f;
    private static final long FIT_ANIM_MS = 380L;
    private static final float FIT_ANIM_TENSION = 1.1f;
    private static volatile boolean sTabBarActive;
    /** 安装时的 host 基准 padding 与导航栏 inset（applyBarGeometry 以后者决定冲销量） */
    private static int sInstallPadBottom;
    private static int sNavPad;

    /**
     * 手势区避让由沉浸开关决定：开=冲销 navPad 让玻璃条延伸到手势区，关=保留避让。
     * 必须同时写 sBasePadBottom，applyBarGeometry 会以它为基准重设 padding
     */
    private static void applyNavPadState(ViewGroup host) {
        if (host == null || sInstallPadBottom <= 0) {
            return;
        }
        int target = GlassConfig.immersiveGestureNavigation
                ? Math.max(sInstallPadBottom - sNavPad, 0)
                : sInstallPadBottom;
        host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                host.getPaddingRight(), target);
        sBasePadBottom = target;
    }

    private static void attachGlassRenderer(Activity activity, ViewGroup host,
                                            ViewGroup bar, ViewGroup tips, View midTab,
                                            ViewGroup content, int barHeightSpec, int navPad) {
        GlassConfig.load(activity);
        applyNavPadState(host);
        if (Build.VERSION.SDK_INT < 33 || content == null) {
            return;
        }
        if (bar instanceof android.widget.RadioGroup) {
            attachQwea0TabBar(activity, host,
                    (android.widget.RadioGroup) bar, tips, midTab,
                    content, barHeightSpec, navPad);
        } else {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "nav bar is not a RadioGroup, QWEA0 tab bar unavailable");
        }
    }

    /**
     * 供设置入口在切换沉浸等玻璃参数后调用：先从 prefs 重载配置再刷新，
     * 避免宿主设置页只写 pref、GlassConfig 静态字段滞后的问题
     */
    public static void refreshGlassWith(Activity activity) {
        GlassConfig.load(activity);
        refreshGlass();
        if (activeGlassHost() == null) {
            // 玻璃未安装时沉浸仍需作用于经典底栏（必须落到 MainActivity 的窗口）
            Activity main = mainActivity();
            if (main != null) {
                applyClassicImmersive(main);
            }
        }
    }

    static void refreshGlass() {
        try {
            WindowImmersiveController.refresh();
            applyNavPadState(sHostRef);
            applyBarGeometry();
            View bar = sTabBarRef.get();
            if (bar instanceof ViewGroup) {
                bar.invalidate();
                View droplet = findDroplet((ViewGroup) bar);
                if (droplet != null) {
                    droplet.invalidate();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static View findDroplet(ViewGroup tabBar) {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View c = tabBar.getChildAt(i);
            if (c instanceof com.example.liquidglass.LiquidGlassView
                    && !(c instanceof com.example.liquidglass.LiquidGlassTabBar)) {
                return c;
            }
        }
        return null;
    }

    private static void applyQwea0Params(com.example.liquidglass.LiquidGlassView glass,
                                         ViewGroup content, float density, boolean adaptiveTint) {
        glass.setCornerRadius(999f);
        glass.setEnableDynamicBackground(true);
        glass.setBackdropSource(content);
        glass.setMaterial(com.example.liquidglass.GlassMaterial.REGULAR);
        glass.setRefractionHeight(60f * density);
        glass.setBevelWidth(16f * density);
        glass.setDispersionStrength(0.12f);
        glass.setEnableSensorHighlight(true);
        glass.setEnableAdaptiveTint(adaptiveTint);
    }

    private static void attachQwea0TabBar(Activity activity, ViewGroup host,
                                          android.widget.RadioGroup bar,
                                          ViewGroup tips, View midTab,
                                          ViewGroup content, int barHeightSpec,
                                          int navPad) {
        try {
            sHostRef = host;
            sDensity = host.getResources().getDisplayMetrics().density;
            sRadioBarRef = new java.lang.ref.WeakReference<>(bar);
            sContentViewRef = new java.lang.ref.WeakReference<>(content);
            sCenterRefStatic = null;
            final com.example.liquidglass.LiquidGlassTabBar tabBar =
                    new com.example.liquidglass.LiquidGlassTabBar(activity, null, 0);
            sTabBarRef = new java.lang.ref.WeakReference<>(tabBar);
            resetWidthAnimState();
            sBuildSig = "";
            sLastTabs = -1;
            sStableTabs = -1;
            float density = host.getResources().getDisplayMetrics().density;

            boolean anyVisible = false;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View c = bar.getChildAt(i);
                if (c instanceof android.widget.RadioButton
                        && c.getVisibility() == View.VISIBLE) {
                    anyVisible = true;
                    break;
                }
            }
            if (!anyVisible) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "tabbar: no radio buttons found");
                return;
            }
            applyQwea0Params(tabBar, content, density, false);
            installTintOverride();

            host.addView(tabBar, host.getChildCount(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL));

            tabBar.setOnTabSelected(new kotlin.jvm.functions.Function1<Integer, kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke(Integer index) {
                    try {
                        if (sSyncing || index == null || index < 0
                                || index >= sVisibleButtons.size()) {
                            return kotlin.Unit.INSTANCE;
                        }
                        if (applyTabWidths(tabBar.getSelectedIndex())) {
                            reanimateDroplet(tabBar);
                        }
                        android.widget.RadioButton rb = sVisibleButtons.get(index);
                        if (rb != null && !rb.isChecked()) {
                            rb.performClick();
                        }
                    } catch (Throwable t) {
                        LiquidGlassLog.logErr("tabbar->app failed", t);
                    }
                    return kotlin.Unit.INSTANCE;
                }
            });
            installRepeatClickRefresh(tabBar);

            boolean darkSeed = isSystemNight(activity);
            sChromeLight = !darkSeed;
            applyTabBarOverLight(tabBar, darkSeed);

            mountCenterButton(activity, host, midTab, tips);
            rebuildTabBar(tabBar);

            placeCenterNow(host, tabBar, sCenterRefStatic, 0);
            applyBarGeometry();

            startBackdropMeter(tabBar);

            bar.setVisibility(View.INVISIBLE);

            setupTabSelectionSync(bar, tabBar);
            startTabVisibilitySync(bar, tabBar);

            ((LiquidGlassHostLayout) host).setExternalRendererActive(true);

            LiquidGlassLog.log(android.util.Log.INFO,
                    "renderer=QWEA0 LiquidGlassTabBar (glass droplet selection)");
            sTabBarActive = true;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("qwea0 tabbar unavailable", t);
        }
    }

    private static void mountCenterButton(Activity activity, ViewGroup host,
                                          View midTab, ViewGroup tips) {
        View centerHost = null;
        if (midTab != null && midTab.getParent() == host) {
            host.removeView(midTab);
            midTab.setVisibility(View.VISIBLE);

            final FrameLayout center = new FrameLayout(activity);
            center.setClickable(true);
            center.addView(midTab, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER));
            center.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (host instanceof LiquidGlassHostLayout) {
                        ((LiquidGlassHostLayout) host).popChild(midTab);
                    }
                    midTab.performClick();
                }
            });
            host.addView(center, host.getChildCount());
            centerHost = center;
        }
        if (tips != null && tips.getParent() == host) {
            host.removeView(tips);
            host.addView(tips, host.getChildCount());
        }
        sCenterRefStatic = centerHost;
        sMidTabRef = new java.lang.ref.WeakReference<>(
                midTab != null ? midTab : centerHost);
        if (midTab != null) {
            sMidPreDraw = new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    try {
                        if (sPlusHidden) {
                            if (midTab.getVisibility() != View.GONE) {
                                midTab.setVisibility(View.GONE);
                            }
                        } else {
                            if (midTab.getVisibility() != View.VISIBLE) {
                                midTab.setVisibility(View.VISIBLE);
                            }
                            restoreChildren(midTab);
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            };
            midTab.getViewTreeObserver().addOnPreDrawListener(sMidPreDraw);
        }
    }

    private static void startBackdropMeter(final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        final java.util.concurrent.atomic.AtomicReference<
                com.example.liquidglass.BackdropLuminanceMeter> meterHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.example.liquidglass.BackdropLuminanceMeter meter =
                new com.example.liquidglass.BackdropLuminanceMeter(tabBar,
                        new kotlin.jvm.functions.Function1<Float, kotlin.Unit>() {
                            @Override
                            public kotlin.Unit invoke(Float luma) {
                                try {
                                    if (!GlassConfig.adaptiveChrome) {
                                        boolean uiDark = (tabBar.getResources()
                                                .getConfiguration().uiMode
                                                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                                        if (sChromeLight != !uiDark
                                                || sChromeForced) {
                                            sChromeForced = true;
                                            sChromeLight = !uiDark;
                                            final boolean d = uiDark;
                                            tabBar.post(() ->
                                                    applyTabBarOverLight(tabBar, d));
                                        }
                                        return kotlin.Unit.INSTANCE;
                                    }
                                    sChromeForced = false;
                                    com.example.liquidglass.BackdropLuminanceMeter
                                            m0 = meterHolder.get();
                                    boolean overLight =
                                            m0 != null && m0.isOverLight();
                                    if (overLight != sChromeLight) {
                                        sChromeLight = overLight;
                                        final boolean flip = overLight;
                                        tabBar.post(() -> {
                                            applyTabBarOverLight(tabBar,
                                                    !flip);
                                            LiquidGlassLog.log(
                                                    android.util.Log.INFO,
                                                    "backdrop flip: overLight="
                                                            + flip);
                                        });
                                    }
                                } catch (Throwable ignored) {
                                }
                                return kotlin.Unit.INSTANCE;
                            }
                        });
        meterHolder.set(meter);
        tabBar.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        try {
                            meter.start();
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        try {
                            meter.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                });
        if (tabBar.isAttachedToWindow()) {
            try {
                meter.start();
                LiquidGlassLog.log(android.util.Log.INFO,
                        "backdrop meter started (already attached)");
            } catch (Throwable ignored) {
            }
        }
    }

    private static void rebuildTabBar(
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            android.widget.RadioGroup bar = sRadioBarRef.get();
            ViewGroup host = sHostRef;
            if (bar == null || host == null || tabBar == null) {
                return;
            }
            GlassConfig.load(tabBar.getContext());
            boolean publishHidden = readPublishHidden(tabBar.getContext());
            java.util.List<com.example.liquidglass.LiquidGlassTabBar.TabItem> items =
                    new java.util.ArrayList<>();
            java.lang.StringBuilder sig = new java.lang.StringBuilder();
            sVisibleButtons.clear();
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child instanceof android.widget.RadioButton
                        && child.getVisibility() == View.VISIBLE) {
                    android.widget.RadioButton rb = (android.widget.RadioButton) child;
                    sig.append(rb.getId()).append(',');
                    CharSequence title = rb.getText();
                    Drawable icon = rb.getCompoundDrawables()[1];
                    if (icon != null) {
                        icon.mutate();
                    }
                    items.add(new com.example.liquidglass.LiquidGlassTabBar.TabItem(
                            title, icon));
                    sVisibleButtons.add(rb);
                }
            }
            sig.append(GlassConfig.fitTabs ? 'F' : 'f')
                    .append(publishHidden ? 'P' : 'p');
            String nextSig = sig.toString();
            if (nextSig.equals(sBuildSig)) {
                return;
            }
            if (items.isEmpty()) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "tabbar: no radio buttons found");
                return;
            }
            sBuildSig = nextSig;
            sSyncing = true;
            try {
                tabBar.setTabs(items);
                applyBarMode(bar, tabBar);
                int checked = bar.getCheckedRadioButtonId();
                int selected = 0;
                for (int i = 0; i < sVisibleButtons.size(); i++) {
                    if (sVisibleButtons.get(i).getId() == checked) {
                        selected = i;
                        break;
                    }
                }
                applyTabWidths(selected);
                tabBar.setSelectedIndex(selected);
                applyTabBarOverLight(tabBar, !sChromeLight);
                tabBar.requestLayout();
            } finally {
                sSyncing = false;
            }
            final ViewGroup h2 = host;
            final com.example.liquidglass.LiquidGlassTabBar t2 = tabBar;
            host.post(() -> placeCenterNow(h2, t2, sCenterRefStatic, 0));
            LiquidGlassLog.log(android.util.Log.INFO,
                    "glass tab bar rebuilt: tabs=" + items.size()
                            + " publishHidden=" + publishHidden);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass tab bar rebuild failed", t);
        }
    }

    private static void startTabVisibilitySync(
            final android.widget.RadioGroup bar,
            final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        final int gen = sSyncGen;
        bar.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != sSyncGen) {
                    // 玻璃已卸载/重装，本轮询作废
                    return;
                }
                try {
                    if (bar.isAttachedToWindow()) {
                        rebuildTabBar(tabBar);
                        syncPlusButton(bar, tabBar);
                    }
                } catch (Throwable t) {
                    LiquidGlassLog.logErr("tab visibility sync failed", t);
                }
                bar.postDelayed(this, 500L);
            }
        }, 1500L);
    }

    private static boolean syncPlusButton(android.widget.RadioGroup bar,
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        return applyBarMode(bar, tabBar);
    }

    private static boolean applyBarMode(android.widget.RadioGroup bar,
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        View barV = sTabBarRef.get();
        ViewGroup host = sHostRef;
        View center = sCenterRefStatic;
        View mid = sMidTabRef == null ? null : sMidTabRef.get();
        if (barV == null || host == null || center == null) {
            return false;
        }
        int visibleTabs = 0;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c instanceof android.widget.RadioButton
                    && c.getVisibility() == View.VISIBLE) {
                visibleTabs++;
            }
        }

        if (visibleTabs == sLastTabs) {
            sStableTabs = visibleTabs;
        }
        sLastTabs = visibleTabs;
        int stableTabs = sStableTabs >= 0 ? sStableTabs : visibleTabs;
        boolean publishHidden = readPublishHidden(tabBar.getContext());
        boolean circle;
        boolean wantHidden;
        if (publishHidden) {
            circle = false;
            wantHidden = true;
        } else if (com.better.heybox.hooks.BottomTabHook.isAnyTabHidden()
                && GlassConfig.barLayoutMode == 0) {
            circle = false;
            wantHidden = false;
        } else {
            int mode = GlassConfig.barLayoutMode;
            if (mode == 0) {
                mode = stableTabs % 2 == 1 ? 2 : 1;
            }
            circle = mode == 2;
            wantHidden = !circle && (stableTabs % 2 == 1 || stableTabs == 0);
        }
        sCircleMode = circle;
        boolean changed = false;
        if (mid != null) {
            boolean hiddenNow = mid.getVisibility() == View.GONE
                    || allChildrenGone(mid);
            if (circle || !wantHidden) {
                if (hiddenNow) {
                    mid.setVisibility(View.VISIBLE);
                    restoreChildren(mid);
                    changed = true;
                }
            } else if (!hiddenNow) {
                mid.setVisibility(View.GONE);
                changed = true;
            }
        }
        sPlusHidden = wantHidden;
        applyBarSideMargins(host);
        if (tabBar.getChildCount() == 0
                || !(tabBar.getChildAt(0) instanceof ViewGroup)) {
            return true;
        }
        ViewGroup row = (ViewGroup) tabBar.getChildAt(0);
        boolean hasGap = false;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof android.widget.Space) {
                hasGap = true;
                break;
            }
        }
        boolean wantGap = !circle && !wantHidden;
        if (wantGap && !hasGap) {
            insertCenterGap(tabBar.getContext(), tabBar);
            changed = true;
        } else if (!wantGap && hasGap) {
            for (int i = row.getChildCount() - 1; i >= 0; i--) {
                if (row.getChildAt(i) instanceof android.widget.Space) {
                    row.removeViewAt(i);
                }
            }
            changed = true;
        }
        float den = sDensity > 0 ? sDensity : 3f;
        int barH = barV.getHeight();
        int circleSize = barH > 0 ? barH : Math.round(56 * den);
        int circleGap = Math.round(8 * den);
        if (barV.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams blp =
                    (FrameLayout.LayoutParams) barV.getLayoutParams();
            int wantMargin = circle ? circleSize + circleGap : 0;
            if (blp.rightMargin != wantMargin) {
                blp.rightMargin = wantMargin;
                barV.setLayoutParams(blp);
                changed = true;
            }
        }
        boolean tabLayoutChanged = applyTabWidths();
        changed |= tabLayoutChanged;
        if (tabLayoutChanged) {
            reanimateDroplet(tabBar);
        }
        if (circle) {
            View glass = sGlassCircleRef == null ? null : sGlassCircleRef.get();
            if (glass != null && glass.getParent() != center) {
                glass = null;
            }
            if (glass == null && center instanceof ViewGroup) {
                glass = buildGlassCircle(tabBar.getContext());
                if (glass != null) {
                    sGlassCircleRef = new java.lang.ref.WeakReference<>(glass);
                    ((ViewGroup) center).addView(glass, 0,
                            new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                    changed = true;
                }
            }
            if (glass != null && glass.getVisibility() != View.VISIBLE) {
                glass.setVisibility(View.VISIBLE);
                changed = true;
            }
            if (center.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams clp =
                        (FrameLayout.LayoutParams) center.getLayoutParams();
                int wantGravity = android.view.Gravity.END
                        | android.view.Gravity.CENTER_VERTICAL;
                if (clp.width != circleSize || clp.height != circleSize
                        || clp.gravity != wantGravity || clp.leftMargin != 0
                        || clp.topMargin != 0 || clp.rightMargin != 0
                        || clp.bottomMargin != 0) {
                    clp.width = circleSize;
                    clp.height = circleSize;
                    clp.gravity = wantGravity;
                    clp.leftMargin = 0;
                    clp.topMargin = 0;
                    clp.rightMargin = 0;
                    clp.bottomMargin = 0;
                    center.setLayoutParams(clp);
                    changed = true;
                }
            }
            if (center.getVisibility() != View.VISIBLE) {
                center.setVisibility(View.VISIBLE);
                changed = true;
            }
        } else {
            View glass = sGlassCircleRef == null ? null : sGlassCircleRef.get();
            if (glass != null && glass.getVisibility() != View.GONE) {
                glass.setVisibility(View.GONE);
                changed = true;
            }
            int wantVis = wantHidden ? View.GONE : View.VISIBLE;
            if (center.getVisibility() != wantVis) {
                center.setVisibility(wantVis);
                changed = true;
            }
        }
        if (changed) {
            row.requestLayout();
            host.requestLayout();
        }
        if (!circle) {
            final ViewGroup h2 = host;
            final View c2 = center;
            final com.example.liquidglass.LiquidGlassTabBar t2 = tabBar;
            host.post(() -> placeCenterNow(h2, t2, c2, 0));
        }
        return changed;
    }

    private static boolean fitVisibleTabsEffective(int visibleTabs) {
        if (!GlassConfig.fitTabs) {
            return false;
        }
        try {
            android.widget.RadioGroup bar = sRadioBarRef.get();
            int named = 0;
            if (bar != null) {
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    if (!(child instanceof android.widget.RadioButton)) {
                        continue;
                    }
                    CharSequence title = ((android.widget.RadioButton) child).getText();
                    if (title == null || title.toString().trim().isEmpty()) {
                        continue;
                    }
                    named++;
                }
            }
            return visibleTabs > 0 && visibleTabs < named;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static View buildGlassCircle(Context context) {
        try {
            View content = sContentViewRef == null ? null : sContentViewRef.get();
            com.example.liquidglass.LiquidGlassView glass =
                    new com.example.liquidglass.LiquidGlassView(context, null, 0);
            glass.setCornerRadius(999f);
            glass.setEnableDynamicBackground(true);
            if (content != null) {
                glass.setBackdropSource(content);
            }
            glass.setMaterial(com.example.liquidglass.GlassMaterial.REGULAR);
            float den = sDensity > 0 ? sDensity : 3f;
            glass.setRefractionHeight(28f * den);
            glass.setBevelWidth(10f * den);
            glass.setDispersionStrength(0.12f);
            glass.setEnableSensorHighlight(true);
            glass.setEnableAdaptiveTint(false);
            return glass;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass circle build failed", t);
            return null;
        }
    }

    private static boolean allChildrenGone(View view) {
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() == 0) {
            return false;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() != View.GONE) {
                return false;
            }
        }
        return true;
    }

    private static void restoreChildren(View view) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c.getVisibility() == View.GONE) {
                c.setVisibility(View.VISIBLE);
            }
        }
    }

    private static boolean applyTabWidths() {
        View barV = sTabBarRef.get();
        int selected = barV instanceof com.example.liquidglass.LiquidGlassTabBar
                ? ((com.example.liquidglass.LiquidGlassTabBar) barV).getSelectedIndex()
                : 0;
        return applyTabWidths(selected);
    }

    private static boolean applyTabWidths(int selectedIndex) {
        boolean changed = false;
        try {
            View barV = sTabBarRef.get();
            if (!(barV instanceof ViewGroup)
                    || ((ViewGroup) barV).getChildCount() == 0
                    || !(((ViewGroup) barV).getChildAt(0) instanceof ViewGroup)) {
                return false;
            }
            ViewGroup row = (ViewGroup) ((ViewGroup) barV).getChildAt(0);
            int tabs = 0;
            boolean hasGap = false;
            for (int i = 0; i < row.getChildCount(); i++) {
                if (row.getChildAt(i) instanceof android.widget.LinearLayout) {
                    tabs++;
                } else if (row.getChildAt(i) instanceof android.widget.Space) {
                    hasGap = true;
                }
            }
            if (tabs == 0) {
                return false;
            }
            float f = Math.max(50, Math.min(GlassConfig.tabWidthPct, 150)) / 100f;
            boolean fit = fitVisibleTabsEffective(tabs);
            boolean glide = fit || sFitActive;
            sFitActive = fit;
            float[] before = glide ? captureTabCenters(row) : null;
            int selected = Math.max(0, Math.min(selectedIndex, tabs - 1));
            float gap = fit ? CENTER_GAP_WEIGHT
                    : Math.max(0.3f, (tabs + CENTER_GAP_WEIGHT) / f - tabs);
            int tabIndex = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (!(c.getLayoutParams()
                        instanceof android.widget.LinearLayout.LayoutParams)) {
                    continue;
                }
                android.widget.LinearLayout.LayoutParams lp =
                        (android.widget.LinearLayout.LayoutParams) c.getLayoutParams();
                float w;
                if (c instanceof android.widget.Space) {
                    w = gap;
                } else if (c instanceof android.widget.LinearLayout) {
                    w = fit
                            ? (tabIndex == selected
                            ? SELECTED_TAB_WEIGHT : OTHER_TAB_WEIGHT)
                            : f;
                    tabIndex++;
                } else {
                    continue;
                }
                if (Math.abs(lp.weight - w) > 0.001f) {
                    lp.weight = w;
                    c.setLayoutParams(lp);
                    changed = true;
                }
            }
            float totalWeight = fit
                    ? OTHER_TAB_WEIGHT * tabs
                    + (SELECTED_TAB_WEIGHT - OTHER_TAB_WEIGHT)
                    + (hasGap ? CENTER_GAP_WEIGHT : 0f)
                    : 0f;
            changed |= applyFitBarWidth(barV, totalWeight, fit, f);
            if (changed && before != null) {
                scheduleTabGlide(row, before);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("apply tab widths failed", t);
        }
        return changed;
    }

    private static void resetWidthAnimState() {
        if (sTabShiftAnimator != null) {
            sTabShiftAnimator.cancel();
            sTabShiftAnimator = null;
        }
        if (sBarWidthAnimator != null) {
            sBarWidthAnimator.cancel();
            sBarWidthAnimator = null;
        }
        if (sDropletSizeAnimator != null) {
            sDropletSizeAnimator.cancel();
            sDropletSizeAnimator = null;
        }
        sBarTargetWidth = Integer.MIN_VALUE;
        sBarTargetLeft = Integer.MIN_VALUE;
        sBarTargetGravity = Integer.MIN_VALUE;
        sFitActive = false;
    }

    private static void scheduleDropletGrow(
            final com.example.liquidglass.LiquidGlassTabBar tabBar,
            final int fromWidth) {
        try {
            if (fromWidth <= 0) {
                return;
            }
            final View droplet = findDroplet(tabBar);
            if (droplet == null) {
                return;
            }
            ViewTreeObserver vto = tabBar.getViewTreeObserver();
            if (vto == null || !vto.isAlive()) {
                return;
            }
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver live = tabBar.getViewTreeObserver();
                    if (live != null && live.isAlive()) {
                        live.removeOnPreDrawListener(this);
                    }
                    return !growDroplet(droplet, fromWidth);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean growDroplet(View droplet, int fromWidth) {
        try {
            if (!(droplet.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                return false;
            }
            final FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) droplet.getLayoutParams();
            final int toWidth = lp.width;
            if (toWidth <= 0 || Math.abs(toWidth - fromWidth) < 2) {
                return false;
            }
            if (sDropletSizeAnimator != null) {
                sDropletSizeAnimator.cancel();
            }
            final int startWidth = fromWidth;
            lp.width = startWidth;
            droplet.setLayoutParams(lp);
            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(Math.max(0L, FIT_ANIM_MS - 40L));
            anim.setInterpolator(
                    new android.view.animation.DecelerateInterpolator(1.6f));
            anim.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                lp.width = Math.round(startWidth + (toWidth - startWidth) * t);
                droplet.setLayoutParams(lp);
            });
            final boolean[] cancelled = {false};
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled[0] = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (cancelled[0]) {
                        return;
                    }
                    lp.width = toWidth;
                    droplet.setLayoutParams(lp);
                }
            });
            sDropletSizeAnimator = anim;
            anim.start();
            return true;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("droplet grow failed", t);
            return false;
        }
    }

    private static float[] captureTabCenters(ViewGroup row) {
        if (row.getWidth() <= 0) {
            return null;
        }
        float[] centers = new float[row.getChildCount()];
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getWidth() <= 0) {
                return null;
            }
            centers[i] = c.getLeft() + c.getWidth() / 2f + c.getTranslationX();
        }
        return centers;
    }

    private static void scheduleTabGlide(final ViewGroup row,
            final float[] before) {
        try {
            ViewTreeObserver vto = row.getViewTreeObserver();
            if (vto == null || !vto.isAlive()) {
                return;
            }
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver live = row.getViewTreeObserver();
                    if (live != null && live.isAlive()) {
                        live.removeOnPreDrawListener(this);
                    }
                    glideTabsFrom(row, before);
                    return true;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void glideTabsFrom(ViewGroup row, float[] before) {
        try {
            if (before == null || row.getChildCount() != before.length) {
                return;
            }
            if (sSnapWidthChanges) {
                return;
            }
            if (sBarWidthAnimator != null && sBarWidthAnimator.isRunning()) {
                return;
            }
            final View[] kids = new View[before.length];
            final float[] shift = new float[before.length];
            boolean any = false;
            for (int i = 0; i < before.length; i++) {
                View c = row.getChildAt(i);
                if (c.getWidth() <= 0) {
                    return;
                }
                kids[i] = c;
                shift[i] = before[i] - (c.getLeft() + c.getWidth() / 2f);
                any |= Math.abs(shift[i]) > 0.5f;
            }
            if (!any) {
                return;
            }
            if (sTabShiftAnimator != null) {
                sTabShiftAnimator.cancel();
            }
            for (int i = 0; i < kids.length; i++) {
                kids[i].setTranslationX(shift[i]);
            }
            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(1f, 0f);
            anim.setDuration(FIT_ANIM_MS);
            anim.setInterpolator(
                    new android.view.animation.OvershootInterpolator(
                            FIT_ANIM_TENSION));
            anim.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                for (int i = 0; i < kids.length; i++) {
                    kids[i].setTranslationX(shift[i] * t);
                }
            });
            final boolean[] cancelled = {false};
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled[0] = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (cancelled[0]) {
                        return;
                    }
                    for (View kid : kids) {
                        kid.setTranslationX(0f);
                    }
                }
            });
            sTabShiftAnimator = anim;
            anim.start();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("tab glide failed", t);
        }
    }

    private static boolean applyFitBarWidth(View barV, float totalWeight,
            boolean fit, float widthScale) {
        if (!(barV.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return false;
        }
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) barV.getLayoutParams();
        ViewGroup host = sHostRef;
        float den = sDensity > 0 ? sDensity
                : barV.getResources().getDisplayMetrics().density;
        int hostWidth = host != null ? host.getWidth() : 0;
        if (hostWidth <= 0) {
            hostWidth = barV.getResources().getDisplayMetrics().widthPixels
                    - Math.round(20f * den);
        }
        int available = Math.max(0, hostWidth - lp.rightMargin);
        if (!fit) {
            return setBarWidth(barV, lp, ViewGroup.LayoutParams.MATCH_PARENT, 0,
                    android.view.Gravity.TOP
                            | android.view.Gravity.FILL_HORIZONTAL, available);
        }
        if (available <= 0 || totalWeight <= 0f) {
            return false;
        }
        int innerAvailable = Math.max(1, available - Math.round(8f * den));
        int perWeight = Math.min(
                Math.round(FIT_TAB_MAX_WIDTH_DP * widthScale * den),
                Math.round(innerAvailable / totalWeight));
        int width = Math.min(available,
                Math.round(totalWeight * perWeight) + Math.round(8f * den));
        int left = Math.max(0, (available - width) / 2);
        return setBarWidth(barV, lp, width, left,
                android.view.Gravity.TOP | android.view.Gravity.START,
                available);
    }

    private static boolean setBarWidth(View barV, FrameLayout.LayoutParams lp,
            int width, int left, int gravity, int available) {
        boolean animating = sBarWidthAnimator != null
                && sBarWidthAnimator.isRunning();
        if (animating && width == sBarTargetWidth && left == sBarTargetLeft
                && gravity == sBarTargetGravity) {
            return false;
        }
        if (!animating && lp.width == width && lp.gravity == gravity
                && lp.leftMargin == left) {
            sBarTargetWidth = width;
            sBarTargetLeft = left;
            sBarTargetGravity = gravity;
            return false;
        }
        sBarTargetWidth = width;
        sBarTargetLeft = left;
        sBarTargetGravity = gravity;
        final int startWidth = barV.getWidth();
        final int startLeft = lp.leftMargin;
        int endPx = width == ViewGroup.LayoutParams.MATCH_PARENT
                ? available : width;
        if (animating) {
            sBarWidthAnimator.cancel();
        }
        if (sSnapWidthChanges || startWidth <= 0 || endPx <= 0
                || startWidth == endPx) {
            lp.width = width;
            lp.leftMargin = left;
            lp.gravity = gravity;
            barV.setLayoutParams(lp);
            return true;
        }
        final FrameLayout.LayoutParams flp = lp;
        final View target = barV;
        final int endWidth = endPx;
        final int endLeft = left;
        final int endGravity = gravity;
        final int finalWidth = width;
        flp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(FIT_ANIM_MS);
        anim.setInterpolator(
                new android.view.animation.DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            flp.width = Math.round(startWidth + (endWidth - startWidth) * t);
            flp.leftMargin = Math.round(startLeft + (endLeft - startLeft) * t);
            target.setLayoutParams(flp);
        });
        final boolean[] cancelled = {false};
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled[0]) {
                    return;
                }
                flp.width = finalWidth;
                flp.leftMargin = endLeft;
                flp.gravity = endGravity;
                target.setLayoutParams(flp);
            }
        });
        sBarWidthAnimator = anim;
        anim.start();
        return true;
    }

    private static void prepareSelectionLayout(
            com.example.liquidglass.LiquidGlassTabBar tabBar,
            int selectedIndex) {
        try {
            View droplet = findDroplet(tabBar);
            int dropletWidth = droplet == null ? 0 : droplet.getWidth();
            if (!applyTabWidths(selectedIndex)) {
                return;
            }
            scheduleDropletGrow(tabBar, dropletWidth);
            int width = tabBar.getMeasuredWidth();
            int height = tabBar.getMeasuredHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            int widthSpec = View.MeasureSpec.makeMeasureSpec(
                    width, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(
                    height, View.MeasureSpec.EXACTLY);
            tabBar.measure(widthSpec, heightSpec);
            tabBar.layout(tabBar.getLeft(), tabBar.getTop(),
                    tabBar.getRight(), tabBar.getBottom());
            glideCenterTo(tabBar);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass selection layout failed", t);
        }
    }

    private static void glideCenterTo(
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            View center = sCenterRefStatic;
            if (center == null
                    || !(center.getLayoutParams()
                            instanceof FrameLayout.LayoutParams)) {
                return;
            }
            if (sPlusHidden || sCircleMode
                    || center.getVisibility() != View.VISIBLE) {
                return;
            }
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return;
            }
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            int n = ll.getChildCount();
            if (n < 3) {
                return;
            }
            View spacer = ll.getChildAt(n / 2);
            if (spacer.getWidth() <= 0) {
                return;
            }
            int left = tabBar.getLeft() + row.getLeft() + spacer.getLeft();
            final FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) center.getLayoutParams();
            if (left == lp.leftMargin) {
                return;
            }
            if (sCenterAnimator != null) {
                sCenterAnimator.cancel();
            }
            sCenterTargetLeft = left;
            final int from = lp.leftMargin;
            final int to = left;
            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(FIT_ANIM_MS);
            anim.setInterpolator(
                    new android.view.animation.OvershootInterpolator(
                            FIT_ANIM_TENSION));
            anim.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                lp.leftMargin = Math.round(from + (to - from) * t);
                center.setLayoutParams(lp);
            });
            final boolean[] cancelled = {false};
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled[0] = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (cancelled[0]) {
                        return;
                    }
                    lp.leftMargin = to;
                    center.setLayoutParams(lp);
                }
            });
            sCenterAnimator = anim;
            anim.start();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("center glide failed", t);
        }
    }

    private static int nearestTabIndex(
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return -1;
            }
            View droplet = findDroplet(tabBar);
            if (droplet == null) {
                return -1;
            }
            float centerX = droplet.getX() + droplet.getWidth() / 2f;
            int best = tabBar.getSelectedIndex();
            float bestDist = Float.MAX_VALUE;
            int index = 0;
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            for (int i = 0; i < ll.getChildCount(); i++) {
                View child = ll.getChildAt(i);
                if (!(child instanceof android.widget.LinearLayout)) {
                    continue;
                }
                float tabCenter = child.getLeft() + child.getWidth() / 2f;
                float d = Math.abs(tabCenter - centerX);
                if (d < bestDist) {
                    bestDist = d;
                    best = index;
                }
                index++;
            }
            return best;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void reanimateDroplet(
            final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            if (sBarWidthAnimator != null && sBarWidthAnimator.isRunning()) {
                return;
            }
            tabBar.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            tabBar.getViewTreeObserver()
                                    .removeOnGlobalLayoutListener(this);
                            try {
                                tabBar.setSelectedIndex(tabBar.getSelectedIndex());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    public static void syncTabVisibility() {
        try {
            if (!sTabBarActive) {
                return;
            }
            View v = sTabBarRef.get();
            if (v instanceof com.example.liquidglass.LiquidGlassTabBar) {
                rebuildTabBar((com.example.liquidglass.LiquidGlassTabBar) v);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyTabBarOverLight(
            com.example.liquidglass.LiquidGlassTabBar tabBar, boolean darkBar) {
        int selectedColor = darkBar ? 0xFFFFFFFF : 0xE6000000;
        int normalColor = darkBar ? 0xB8FFFFFF : 0x8C000000;
        int applied = 0;
        try {
            java.lang.reflect.Field tf = com.example.liquidglass.LiquidGlassTabBar.class
                    .getDeclaredField("tabs");
            tf.setAccessible(true);
            Object tabsObj = tf.get(tabBar);
            if (tabsObj instanceof java.util.List) {
                int selIdx = tabBar.getSelectedIndex();
                java.util.List<?> tabs = (java.util.List<?>) tabsObj;
                for (int i = 0; i < tabs.size(); i++) {
                    Object holder = tabs.get(i);
                    if (holder == null) {
                        continue;
                    }
                    int color = i == selIdx ? selectedColor : normalColor;
                    for (java.lang.reflect.Field hf : holder.getClass().getDeclaredFields()) {
                        hf.setAccessible(true);
                        Object val = hf.get(holder);
                        if (val instanceof android.widget.TextView) {
                            ((android.widget.TextView) val)
                                    .setTextColor(color);
                            applied++;
                        } else if (val instanceof android.widget.ImageView) {
                            ((android.widget.ImageView) val)
                                    .setImageTintList(
                                            android.content.res.ColorStateList
                                                    .valueOf(color));
                            applied++;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("direct chrome recolor failed", t);
        }
        try {
            java.lang.reflect.Field af = com.example.liquidglass.LiquidGlassTabBar.class
                    .getDeclaredField("overLightAppearance");
            af.setAccessible(true);
            af.setBoolean(tabBar, !darkBar);

            java.lang.reflect.Method uts = com.example.liquidglass.LiquidGlassTabBar.class
                    .getDeclaredMethod("updateTabStyles");
            uts.setAccessible(true);
            uts.invoke(tabBar);
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "updateTabStyles refresh failed: " + t);
        }
        tabBar.invalidate();
        LiquidGlassLog.log(android.util.Log.INFO,
                "chrome theme applied: dark=" + darkBar + " targets=" + applied);
    }

    private static boolean isSystemNight(Activity activity) {
        int mode = activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static void insertCenterGap(Context context, ViewGroup tabBar) {
        try {
            if (tabBar.getChildCount() == 0) {
                return;
            }
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return;
            }
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            int count = ll.getChildCount();
            if (count < 2) {
                return;
            }
            android.widget.Space spacer = new android.widget.Space(context);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, CENTER_GAP_WEIGHT);
            // (count+1)/2：奇数个 tab 时槽位也落在正中（3 tab → [t,t,槽,t]），
            // 且与 glideCenterTo/placeCenterNow 的 n/2 查找一致
            ll.addView(spacer, (count + 1) / 2, lp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("center gap failed", t);
        }
    }

    /** 发布按钮盖到中央槽位正上方（宽度=槽宽，整槽可点），行未测完时 post 重试 */
    private static void placeCenterNow(ViewGroup host, ViewGroup tabBar,
                                       View center, int attempt) {
        if (center == null || sPlusHidden || sCircleMode || attempt > 10) {
            return;
        }
        try {
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return;
            }
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            int n = ll.getChildCount();
            if (n < 3) {
                return;
            }
            View spacer = ll.getChildAt(n / 2);
            if (spacer.getWidth() == 0) {
                final ViewGroup h2 = host, t2 = tabBar;
                final View c2 = center;
                final int a2 = attempt + 1;
                center.post(() -> placeCenterNow(h2, t2, c2, a2));
                return;
            }
            int left = tabBar.getLeft() + row.getLeft() + spacer.getLeft();
            if (sCenterAnimator != null && sCenterAnimator.isRunning()
                    && left == sCenterTargetLeft) {
                return;
            }
            if (center.getWidth() == spacer.getWidth()
                    && center.getHeight() == tabBar.getHeight()
                    && center.getLeft() == left
                    && center.getTop() == tabBar.getTop()
                    && center.getVisibility() == View.VISIBLE) {
                return;
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    spacer.getWidth(), tabBar.getHeight(),
                    android.view.Gravity.TOP | android.view.Gravity.START);
            lp.leftMargin = left;
            lp.topMargin = tabBar.getTop();
            center.setTranslationX(0);
            center.setLayoutParams(lp);
        } catch (Throwable ignored) {
        }
    }

    private static void centerTabsRow(ViewGroup tabBar) {
        try {
            if (tabBar.getChildCount() == 0) {
                return;
            }
            View row = tabBar.getChildAt(0);
            if (row == null || !(row.getLayoutParams()
                    instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams rlp =
                    (FrameLayout.LayoutParams) row.getLayoutParams();
            int want = android.view.Gravity.CENTER_VERTICAL
                    | android.view.Gravity.FILL_HORIZONTAL;
            if (rlp.gravity == want) {
                return;
            }
            rlp.gravity = want;
            row.setLayoutParams(rlp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("center tabs row failed", t);
        }
    }

    private static void applyBarSideMargins(ViewGroup host) {
        try {
            if (!(host.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
                return;
            }
            RelativeLayout.LayoutParams lp =
                    (RelativeLayout.LayoutParams) host.getLayoutParams();
            View parent = host.getParent() instanceof View
                    ? (View) host.getParent() : null;
            int parentWidth = parent != null ? parent.getWidth() : 0;
            if (parentWidth <= 0) {
                parentWidth = host.getResources()
                        .getDisplayMetrics().widthPixels;
            }
            if (parentWidth <= 0) {
                return;
            }
            int target;
            switch (GlassConfig.barWidthMode) {
                case 0:
                    target = adaptiveBarWidth(host, parentWidth);
                    break;
                case 2:
                    int pct = Math.max(50,
                            Math.min(GlassConfig.barWidthPct, 100));
                    target = Math.round(parentWidth * pct / 100f);
                    break;
                default:
                    target = parentWidth;
                    break;
            }
            int margin = Math.max(0, (parentWidth - target) / 2);
            if (lp.leftMargin == margin && lp.rightMargin == margin) {
                return;
            }
            lp.leftMargin = margin;
            lp.rightMargin = margin;
            host.setLayoutParams(lp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("apply bar side margins failed", t);
        }
    }

    private static int adaptiveBarWidth(View host, int parentWidth) {
        float den = sDensity > 0 ? sDensity
                : host.getResources().getDisplayMetrics().density;
        float scale = Math.max(50, Math.min(GlassConfig.tabWidthPct, 150))
                / 100f;
        android.widget.RadioGroup radio = sRadioBarRef.get();
        int tabs = 0;
        if (radio != null) {
            for (int i = 0; i < radio.getChildCount(); i++) {
                View c = radio.getChildAt(i);
                if (c instanceof android.widget.RadioButton
                        && c.getVisibility() == View.VISIBLE) {
                    tabs++;
                }
            }
        }
        if (tabs == 0) {
            return parentWidth;
        }
        float perTab = FIT_TAB_MAX_WIDTH_DP * den * scale;
        float w = tabs * perTab + Math.round(8f * den);
        if (!sCircleMode && !sPlusHidden) {
            w += CENTER_GAP_WEIGHT * perTab;
        }
        return Math.min(parentWidth, Math.round(w));
    }

    static void applyBarGeometry() {
        sSnapWidthChanges = true;
        try {
            View barV = sTabBarRef.get();
            ViewGroup host = sHostRef;
            View center = sCenterRefStatic;
            if (!(barV instanceof ViewGroup) || host == null
                    || !(barV.getLayoutParams()
                            instanceof FrameLayout.LayoutParams)) {
                return;
            }
            float den = sDensity > 0 ? sDensity : 3f;
            int hDp = GlassConfig.barHeightDp;
            FrameLayout.LayoutParams blp =
                    (FrameLayout.LayoutParams) barV.getLayoutParams();
            blp.height = hDp <= 0
                    ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : Math.round(hDp * den);
            barV.setLayoutParams(blp);

            centerTabsRow((ViewGroup) barV);

            int off = Math.max(0, GlassConfig.barOffsetDp);
            host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                    host.getPaddingRight(), sBasePadBottom
                            + Math.round(off * den));

            applyBarSideMargins(host);
            boolean tabLayoutChanged = applyTabWidths();

            host.requestLayout();
            if (tabLayoutChanged
                    && barV instanceof com.example.liquidglass.LiquidGlassTabBar) {
                reanimateDroplet(
                        (com.example.liquidglass.LiquidGlassTabBar) barV);
            }
            final ViewGroup h2 = host;
            final ViewGroup b2 = (ViewGroup) barV;
            host.post(() -> {
                sSnapWidthChanges = true;
                try {
                    applyTabWidths();
                } finally {
                    sSnapWidthChanges = false;
                }
                placeCenterNow(h2, b2, center, 0);
            });
            android.widget.RadioGroup radio = sRadioBarRef.get();
            if (radio != null
                    && barV instanceof com.example.liquidglass.LiquidGlassTabBar) {
                applyBarMode(radio,
                        (com.example.liquidglass.LiquidGlassTabBar) barV);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("applyBarGeometry failed", t);
        } finally {
            sSnapWidthChanges = false;
        }
    }

    private static void installRepeatClickRefresh(
            final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        if (tabBar == null) {
            return;
        }
        final float[] down = new float[2];
        final int[] selectedBefore = new int[1];
        final boolean[] moved = new boolean[1];
        tabBar.setOnTouchListener((view, event) -> {
            try {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    down[0] = event.getX();
                    down[1] = event.getY();
                    selectedBefore[0] = tabBar.getSelectedIndex();
                    moved[0] = false;
                } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                    float slop = android.view.ViewConfiguration.get(view.getContext())
                            .getScaledTouchSlop();
                    float dx = event.getX() - down[0];
                    float dy = event.getY() - down[1];
                    moved[0] = moved[0] || dx * dx + dy * dy > slop * slop;
                } else if (action == android.view.MotionEvent.ACTION_UP) {
                    if (moved[0]) {
                        int near = nearestTabIndex(tabBar);
                        if (near >= 0) {
                            prepareSelectionLayout(tabBar, near);
                        }
                    } else {
                        int target = findTabIndexAt(tabBar, event.getX());
                        if (target >= 0) {
                            prepareSelectionLayout(tabBar, target);
                        }
                        final int before = selectedBefore[0];
                        tabBar.post(() -> {
                            try {
                                if (target != before || target != tabBar.getSelectedIndex()
                                        || target < 0 || target >= sVisibleButtons.size()) {
                                    return;
                                }
                                android.widget.RadioButton button = sVisibleButtons.get(target);
                                if (button != null) {
                                    button.performClick();
                                }
                            } catch (Throwable t) {
                                LiquidGlassLog.logErr(
                                        "repeat tab refresh failed", t);
                            }
                        });
                    }
                } else if (action == android.view.MotionEvent.ACTION_CANCEL) {
                    moved[0] = false;
                }
            } catch (Throwable t) {
                LiquidGlassLog.logErr("repeat tab touch failed", t);
            }
            return false;
        });
    }

    private static int findTabIndexAt(
            com.example.liquidglass.LiquidGlassTabBar tabBar, float x) {
        if (tabBar == null || tabBar.getChildCount() <= 0
                || !(tabBar.getChildAt(0) instanceof android.view.ViewGroup)) {
            return -1;
        }
        android.view.ViewGroup row = (android.view.ViewGroup) tabBar.getChildAt(0);
        float localX = x - row.getLeft();
        int index = 0;
        for (int childIndex = 0; childIndex < row.getChildCount(); childIndex++) {
            View child = row.getChildAt(childIndex);
            if (!(child instanceof android.widget.LinearLayout)) {
                continue;
            }
            if (localX >= child.getLeft() && localX <= child.getRight()) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private interface OnCheckedExtra {
        void onChecked(android.widget.RadioGroup group, int checkedId);
    }

    private static android.widget.RadioGroup.OnCheckedChangeListener sOriginalCheckedListener;
    private static android.widget.RadioGroup.OnCheckedChangeListener sWrappedListener;
    private static android.view.ViewTreeObserver.OnPreDrawListener sMidPreDraw;

    private static void wrapCheckedListener(final android.widget.RadioGroup bar,
                                            final OnCheckedExtra extra) {
        try {
            java.lang.reflect.Field f = android.widget.RadioGroup.class
                    .getDeclaredField("mOnCheckedChangeListener");
            f.setAccessible(true);
            final Object original = f.get(bar);
            sOriginalCheckedListener =
                    original instanceof android.widget.RadioGroup.OnCheckedChangeListener
                            ? (android.widget.RadioGroup.OnCheckedChangeListener) original
                            : null;
            android.widget.RadioGroup.OnCheckedChangeListener wrapper =
                    new android.widget.RadioGroup.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.RadioGroup group, int checkedId) {
                            if (sOriginalCheckedListener != null) {
                                sOriginalCheckedListener.onCheckedChanged(group, checkedId);
                            }
                            try {
                                extra.onChecked(group, checkedId);
                            } catch (Throwable ignored) {
                            }
                        }
                    };
            sWrappedListener = wrapper;
            bar.setOnCheckedChangeListener(wrapper);
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "checked listener wrap unavailable: " + t);
        }
    }

    /** 卸载玻璃时还原宿主原始的底栏选中监听 */
    private static void unwrapCheckedListener(ViewGroup barView) {
        if (!(barView instanceof android.widget.RadioGroup)
                || sWrappedListener == null) {
            return;
        }
        android.widget.RadioGroup bar = (android.widget.RadioGroup) barView;
        try {
            java.lang.reflect.Field f = android.widget.RadioGroup.class
                    .getDeclaredField("mOnCheckedChangeListener");
            f.setAccessible(true);
            if (f.get(bar) == sWrappedListener) {
                bar.setOnCheckedChangeListener(sOriginalCheckedListener);
            }
        } catch (Throwable ignored) {
        }
        sWrappedListener = null;
        sOriginalCheckedListener = null;
    }

    /** 卸载玻璃时移除加号的 PreDraw 可见性强制，避免与经典路径拉锯 */
    private static void removeMidPreDraw(View midTab) {
        if (midTab == null || sMidPreDraw == null) {
            return;
        }
        try {
            midTab.getViewTreeObserver().removeOnPreDrawListener(sMidPreDraw);
        } catch (Throwable ignored) {
        }
        sMidPreDraw = null;
    }

    private static void setupTabSelectionSync(final android.widget.RadioGroup bar,
                                              final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        wrapCheckedListener(bar, (group, checkedId) -> {
            for (int i = 0; i < sVisibleButtons.size(); i++) {
                if (sVisibleButtons.get(i).getId() == checkedId
                        && tabBar.getSelectedIndex() != i) {
                    prepareSelectionLayout(tabBar, i);
                    tabBar.setSelectedIndex(i);
                    break;
                }
            }
        });
    }

    private static volatile boolean sTintHookInstalled;
    private static volatile boolean sChromeLight;
    private static volatile boolean sChromeForced;
    private static volatile ViewGroup sHostRef;
    private static volatile View sCenterRefStatic;
    private static final java.lang.ref.WeakReference<View> EMPTY_MID_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<View> sMidTabRef =
            EMPTY_MID_REF;
    private static volatile boolean sPlusHidden;
    private static final java.lang.ref.WeakReference<android.widget.RadioGroup>
            EMPTY_RADIO_REF = new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<android.widget.RadioGroup>
            sRadioBarRef = EMPTY_RADIO_REF;
    private static volatile java.lang.ref.WeakReference<View> sGlassCircleRef;
    private static volatile java.lang.ref.WeakReference<View> sContentViewRef;
    private static volatile boolean sCircleMode;
    private static volatile int sLastTabs = -1;
    private static volatile int sStableTabs = -1;
    private static volatile float sDensity;
    private static int sBasePadBottom;
    private static final java.lang.ref.WeakReference<View> EMPTY_BAR_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<View> sTabBarRef = EMPTY_BAR_REF;
    private static final java.util.List<android.widget.RadioButton> sVisibleButtons =
            new java.util.ArrayList<>();
    private static volatile boolean sSyncing;
    private static volatile String sBuildSig = "";
    private static android.animation.ValueAnimator sTabShiftAnimator;
    private static android.animation.ValueAnimator sBarWidthAnimator;
    private static android.animation.ValueAnimator sDropletSizeAnimator;
    private static int sBarTargetWidth = Integer.MIN_VALUE;
    private static int sBarTargetLeft = Integer.MIN_VALUE;
    private static int sBarTargetGravity = Integer.MIN_VALUE;
    private static boolean sFitActive;
    private static boolean sSnapWidthChanges;
    private static android.animation.ValueAnimator sCenterAnimator;
    private static int sCenterTargetLeft = Integer.MIN_VALUE;

    static View activeGlassHost() {
        ViewGroup host = sHostRef;
        if (host == null || !host.isAttachedToWindow() || host.getHeight() <= 0) {
            return null;
        }
        return host;
    }

    private static void installTintOverride() {
        if (sTintHookInstalled) {
            return;
        }
        sTintHookInstalled = true;
        try {
            java.lang.reflect.Method m = com.example.liquidglass.LiquidGlassView.class
                    .getDeclaredMethod("currentTintColor");
            LiquidGlassHookBridge.hookExecutable(m, chain -> {
                int tint = 0x30FFFFFF;
                try {
                    Object thiz = chain.getThisObject();
                    int mode = 0;
                    boolean isBarView = false;
                    if (thiz instanceof View) {
                        mode = ((View) thiz).getResources()
                                .getConfiguration().uiMode
                                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                        isBarView = thiz == sTabBarRef.get();
                    }
                    boolean light =
                            mode != android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    if (light) {
                        tint = GlassConfig.lightTint();
                    } else {
                        tint = GlassConfig.darkTint();
                    }
                    if (!isBarView) {
                        int a = tint >>> 24;
                        int na = Math.round(a * 0.6f);
                        tint = (na << 24) | (tint & 0x00FFFFFF);
                    }
                } catch (Throwable ignored) {
                }
                return tint;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "currentTintColor override hooked (theme body)");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("currentTintColor hook failed", t);
        }
    }

    private static final java.util.Map<View, Boolean> sGlassEntries =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Boolean>());

    public static void installSettingsEntries(ClassLoader cl) {
        try {
            Class<?> htb = Class.forName(
                    "com.max.hbcommon.component.HomeTitleBar", false, cl);
            java.lang.reflect.Method g = htb.getMethod("getIv_home_search");
            LiquidGlassHookBridge.hookExecutable(g, chain -> {
                Object r = chain.proceed();
                try {
                    if (r instanceof View && !sGlassEntries.containsKey(r)) {
                        View icon = (View) r;
                        android.content.Context cx = icon.getContext();
                        if (cx instanceof Activity && !GlassProvider.prefersHbmod(cx)) {
                            sGlassEntries.put(icon, Boolean.TRUE);
                            final Activity a = (Activity) cx;
                            icon.setOnLongClickListener(v -> {
                                GlassSettingsSheet.show(a);
                                return true;
                            });
                        }
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "title-bar glass entry hook installed");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("title-bar entry hook failed", t);
        }
        try {
            Class<?> sa = Class.forName(
                    "com.max.xiaoheihe.module.account.SettingActivity", false, cl);
            java.lang.reflect.Method onResume = null;
            for (Class<?> c = sa; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    onResume = c.getDeclaredMethod("onResume");
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (onResume == null) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "SettingActivity onResume not found");
                return;
            }
            LiquidGlassHookBridge.hookExecutable(onResume, chain -> {
                Object r = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        final Activity a = (Activity) self;
                        a.getWindow().getDecorView().postDelayed(
                                () -> injectGlassSettingsRow(a, 0), 200L);
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "SettingActivity glass row hook installed");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("SettingActivity entry hook failed", t);
        }
    }

    private static void injectGlassSettingsRow(Activity activity, int attempt) {
        try {
            if (activity.isFinishing() || activity.isDestroyed() || attempt > 10) {
                return;
            }
            if (GlassProvider.prefersHbmod(activity)) {
                return;
            }
            int id = activity.getResources().getIdentifier(
                    "vg_general_settings", "id", activity.getPackageName());
            View row = id != 0 ? activity.findViewById(id) : null;
            if (row == null) {
                activity.getWindow().getDecorView().postDelayed(
                        () -> injectGlassSettingsRow(activity, attempt + 1), 200L);
                return;
            }
            if (sGlassEntries.containsKey(row)) {
                return;
            }
            sGlassEntries.put(row, Boolean.TRUE);
            row.setOnLongClickListener(v -> {
                GlassSettingsSheet.show(activity);
                return true;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "glass settings row attached (long-press 通用设置)");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass settings row failed", t);
        }
    }

    private static void setupTabPopAnimation(ViewGroup bar) {
        if (!(bar instanceof android.widget.RadioGroup)) {
            return;
        }
        wrapCheckedListener((android.widget.RadioGroup) bar, (group, checkedId) -> {
            if (group.getParent() instanceof LiquidGlassHostLayout) {
                ((LiquidGlassHostLayout) group.getParent())
                        .popChild(group.findViewById(checkedId));
            }
        });
    }

    /** 隐藏宿主自带的底栏上方渐变阴影，返回被隐藏的视图供卸载时还原 */
    private static View hideLegacyShadow(ViewGroup root, int barId) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getClass() == View.class && child.getBackground() != null) {
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp instanceof RelativeLayout.LayoutParams) {
                    if (((RelativeLayout.LayoutParams) lp)
                            .getRules()[RelativeLayout.ABOVE] == barId) {
                        child.setVisibility(View.GONE);
                        return child;
                    }
                }
            }
        }
        return null;
    }

    private static void copyMargins(RelativeLayout.LayoutParams src,
                                    RelativeLayout.LayoutParams dst) {
        dst.leftMargin = src.leftMargin;
        dst.topMargin = src.topMargin;
        dst.rightMargin = src.rightMargin;
        dst.bottomMargin = src.bottomMargin;
    }

    private static int computeNavInsetPadding(Activity activity,
                                              ViewGroup root, ViewGroup bar) {
        try {
            WindowInsets wi = root.getRootWindowInsets();
            int nav = 0;
            if (wi == null) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                nav = wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                nav = wi.getSystemWindowInsetBottom();
            }
            if (nav <= 0) {
                return 0;
            }
            View decor = activity.getWindow().getDecorView();
            int[] loc = new int[2];
            decor.getLocationOnScreen(loc);
            int decorBottom = loc[1] + decor.getHeight();
            int realBottom = getRealDisplayBottom(activity);
            if (realBottom <= 0) {
                return 0;
            }
            int gap = realBottom - decorBottom;
            int extra = Math.max(nav - gap, 0);
            float density = root.getResources().getDisplayMetrics().density;
            int capped = (int) Math.min(extra, density * 56f);
            int existing = Math.max(bar.getPaddingBottom(), root.getPaddingBottom());
            return Math.max(capped - existing, 0);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("computeNavInsetPadding failed", t);
            return 0;
        }
    }

    private static int getRealDisplayBottom(Activity activity) {
        try {
            WindowManager wm = (WindowManager)
                    activity.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return wm.getMaximumWindowMetrics().getBounds().bottom;
            }
            Display display = wm.getDefaultDisplay();
            if (display == null) {
                return 0;
            }
            DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            return dm.heightPixels;
        } catch (Throwable t) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends View> T findViewByName(Activity activity, String name) {
        try {
            int id = activity.getResources()
                    .getIdentifier(name, "id", activity.getPackageName());
            if (id == 0) {
                return null;
            }
            return (T) activity.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }
}
