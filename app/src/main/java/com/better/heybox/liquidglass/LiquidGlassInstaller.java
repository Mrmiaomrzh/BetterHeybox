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

public final class LiquidGlassInstaller {

    private static final String TAG = "HeyBoxLiquidGlass";

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

    private static boolean isPublishHidden(Context context) {
        try {
            com.better.heybox.HeyboxPrefs.init(context);
            if (com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_HIDE_ADD, false)) {
                return true;
            }
            return com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_HIDE_TAB_HOME, false)
                    || com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_HIDE_TAB_HOT, false)
                    || com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_HIDE_TAB_GAME, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void scheduleInstall(Activity activity) {
        if (!isGlassEnabled(activity)) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        decor.post(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                ViewGroup root = findViewByName(activity, ID_ROOT);
                if (root == null) {
                    LiquidGlassLog.log(android.util.Log.WARN,
                            "root view " + ID_ROOT + " not found, retry in 200ms");
                    decor.postDelayed(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            ViewGroup r = findViewByName(activity, ID_ROOT);
                            if (r != null) {
                                install(activity, r);
                            }
                        }
                    }, 200L);
                    return;
                }
                install(activity, root);
            } catch (Throwable t) {
                LiquidGlassLog.logErr("scheduleInstall failed", t);
            }
        });
    }

    private static void install(Activity activity, ViewGroup root) {
        if (root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG) != null) {
            return;
        }
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

        hideLegacyShadow(root, bar.getId());

        int barHeightSpec = bar.getLayoutParams().height;
        int navPad = computeNavInsetPadding(activity, root, bar);
        int origBarPadBottom = bar.getPaddingBottom();

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
                        host.attach();
                        if (!sTabBarActive) {
                            setupTabPopAnimation(bar);
                        }
                        LiquidGlassLog.log(android.util.Log.INFO,
                                "liquid glass installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " navPad=" + navPad
                                        + " barH=" + bar.getHeight()
                                        + " children=" + host.getChildCount());
                    }
                });
    }

    /**
 * QWEA0 模式：用 LiquidGlassTabBar 替换可见 RadioGroup（自带水滴选中动画）
 */
    private static final boolean USE_QWEA0_TABBAR = true;
    /**
 * 发布按钮占位空列的权重（普通 tab 为 1）
 */
    private static final float CENTER_GAP_WEIGHT = 1.3f;
    private static volatile boolean sTabBarActive;

    /**
 * API 33+ 用 QWEA0 LiquidGlassTabBar 渲染器替换 RadioGroup（采样 fl_container）；API<33 走 legacy frost 路径
 */
    private static void attachGlassRenderer(Activity activity, ViewGroup host,
                                            ViewGroup bar, ViewGroup tips, View midTab,
                                            ViewGroup content, int barHeightSpec, int navPad) {
        if (Build.VERSION.SDK_INT < 33 || content == null) {
            return;
        }
        try {
            if (USE_QWEA0_TABBAR && bar instanceof android.widget.RadioGroup) {
                attachQwea0TabBar(activity, host,
                        (android.widget.RadioGroup) bar, tips, midTab,
                        content, barHeightSpec, navPad);
            } else {
                attachQwea0Renderer(activity, host, content, barHeightSpec);
            }
        } catch (Throwable t) {
            // QWEA0 挂载失败不中断 install：host 已挂好、legacy frost 路径继续渲染玻璃
            LiquidGlassLog.logErr("QWEA0 renderer failed, legacy frost fallback", t);
        }
    }

    static void refreshGlass() {
        try {
            WindowImmersiveController.refresh();
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

    private static void attachQwea0Renderer(Activity activity, ViewGroup host,
                                            ViewGroup content, int barHeightSpec) {
        try {
            float density = host.getResources().getDisplayMetrics().density;
            com.example.liquidglass.LiquidGlassView glass =
                    new com.example.liquidglass.LiquidGlassView(activity, null, 0);
            applyQwea0Params(glass, content, density, true);

            host.addView(glass, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    barHeightSpec > 0 ? barHeightSpec : ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL));

            LiquidGlassLog.log(android.util.Log.INFO,
                    "renderer=QWEA0 LiquidGlassView (lens+dispersion+sensor specular)");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("qwea0 renderer unavailable, frost fallback", t);
        }
    }

    /**
 * QWEA0 LiquidGlassTabBar 整体替换可见 RadioGroup：自身是 LiquidGlassView（水滴滑动/拉伸选中动画），原 RadioGroup 保留但不可见。
 * 双向同步：tab 点击 → rb.performClick()；app 选中 → 监听包装 → tabBar.setSelectedIndex
 */
    private static void attachQwea0TabBar(Activity activity, ViewGroup host,
                                          android.widget.RadioGroup bar,
                                          ViewGroup tips, View midTab,
                                          ViewGroup content, int barHeightSpec,
                                          int navPad) {
        try {
            GlassConfig.load(activity);
            sHostRef = host;
            sDensity = host.getResources().getDisplayMetrics().density;
            // tab-bar 模式偏移 0 必须贴物理屏底：剥掉通用路径加的导航 inset padding
            int flushPad = Math.max(host.getPaddingBottom() - navPad, 0);
            host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                    host.getPaddingRight(), flushPad);
            sBasePadBottom = flushPad;
            sCenterRefStatic = null;
            final com.example.liquidglass.LiquidGlassTabBar tabBar =
                    new com.example.liquidglass.LiquidGlassTabBar(activity, null, 0);
            sTabBarRef = new java.lang.ref.WeakReference<>(tabBar);
            sNativeBarRef = new java.lang.ref.WeakReference<>(bar);
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
            // 关闭逐像素自适应染色（对亮背景反色且绕过 hook）；玻璃体用 currentTintColor override，标签色由 BackdropLuminanceMeter 驱动
            applyQwea0Params(tabBar, content, density, false);
            installTintOverride();

            // 高度随 tab 内容（图标+文字）自适应，贴合行而非固定高框
            host.addView(tabBar, host.getChildCount(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL));

            rebuildTabBar(tabBar);

            // bar -> app: forward taps to the hidden radio buttons
            tabBar.setOnTabSelected(new kotlin.jvm.functions.Function1<Integer, kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke(Integer index) {
                    try {
                        if (sSyncing || index == null || index < 0
                                || index >= sVisibleButtons.size()) {
                            return kotlin.Unit.INSTANCE;
                        }
                        if (applySelectionWeights(tabBar,
                                tabBar.getSelectedIndex())) {
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

            // 初始主题以 activity uiMode 为准（text-color 探测跨皮肤不可靠）
            applyTabBarOverLight(tabBar, isSystemNight(activity));

            final View centerRef = mountCenterButton(activity, host, midTab, tips);
            sCenterRefStatic = centerRef;

            // 布局完成后把发布按钮定位到 spacer 列上方
            placeCenterNow(host, tabBar, centerRef, 0);
            applyBarGeometry();

            startBackdropMeter(tabBar);

            // hide the original radio row visually (keeps state mechanics alive)
            bar.setVisibility(View.INVISIBLE);

            // app -> bar: extend the existing checked-listener wrapper
            setupTabSelectionSync(bar, tabBar);

            // uiMode 推送只播种初始态，亮度计预热后接管 chrome 颜色
            final com.example.liquidglass.LiquidGlassTabBar tabBarRef = tabBar;
            ((LiquidGlassHostLayout) host).setGlassTuner(
                    new LiquidGlassHostLayout.GlassTuner() {
                        @Override
                        public void onSize(int w, int h, float cornerRadius) {
                        }

                        @Override
                        public void onTheme(boolean dark) {
                            LiquidGlassLog.log(android.util.Log.INFO,
                                    "app theme changed: dark=" + dark
                                            + " (backdrop meter now drives chrome)");
                        }
                    });

            LiquidGlassLog.log(android.util.Log.INFO,
                    "renderer=QWEA0 LiquidGlassTabBar (glass droplet selection)");
            sTabBarActive = true;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("qwea0 tabbar unavailable", t);
        }
    }

    /**
 * 发布按钮须挂在 tab bar 之外（其吞掉全部触摸）；返回中心容器，无发布按钮为 null
 */
    private static View mountCenterButton(Activity activity, ViewGroup host,
                                          View midTab, ViewGroup tips) {
        View centerHost = null;
        if (!isPublishHidden(activity) && midTab != null && midTab.getParent() == host) {
            host.removeView(midTab);
            midTab.setVisibility(View.VISIBLE);

            final FrameLayout center = new FrameLayout(activity);
            center.setClickable(true);
            // CardView 保持自然尺寸；透明中心容器覆盖整个缺口，点击任意处触发发布
            center.addView(midTab, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER));
            center.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
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
        return centerHost;
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
                                        // 用户关闭自适应反色：标签色固定跟随应用主题
                                        boolean uiDark = tabBar.getResources()
                                                .getConfiguration().uiMode
                                                % 2 == 1;
                                        if (sChromeLight != !uiDark
                                                || sChromeForced) {
                                            sChromeForced = true;
                                            sChromeLight = !uiDark;
                                            final boolean d = uiDark;
                                            tabBar.post(() -> {
                                                try {
                                                    applyTabBarOverLight(
                                                            tabBar, d);
                                                } catch (Throwable ignored) {
                                                }
                                            });
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
                                            try {
                                                applyTabBarOverLight(tabBar,
                                                        !flip);
                                                LiquidGlassLog.log(
                                                        android.util.Log.INFO,
                                                        "backdrop flip: overLight="
                                                                + flip);
                                            } catch (Throwable ignored) {
                                            }
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
        // bar 可能已 attach（onAttach 监听不会触发），直接启动
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
            android.widget.RadioGroup bar = sNativeBarRef.get();
            ViewGroup host = sHostRef;
            if (bar == null || host == null || tabBar == null) {
                return;
            }
            GlassConfig.load(tabBar.getContext());
            boolean publishHidden = isPublishHidden(tabBar.getContext());
            java.util.List<com.example.liquidglass.LiquidGlassTabBar.TabItem> items =
                    new java.util.ArrayList<>();
            java.lang.StringBuilder sig = new java.lang.StringBuilder();
            sVisibleButtons.clear();
            sRepeatRefreshTabs.clear();
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
                    sRepeatRefreshTabs.add(isRepeatRefreshTab(title));
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
            // 中心缺口（权重 1.3）仅为发布按钮存在；隐藏加号/任 tab 时不再插入缺口，避免挤压剩余 tab
            sSyncing = true;
            try {
                tabBar.setTabs(items);
                if (!publishHidden) {
                    insertCenterGap(tabBar.getContext(), tabBar);
                }
                applyFitWidth(tabBar, items.size(), publishHidden);
                int checked = bar.getCheckedRadioButtonId();
                for (int i = 0; i < sVisibleButtons.size(); i++) {
                    if (sVisibleButtons.get(i).getId() == checked) {
                        prepareSelectionLayout(tabBar, i);
                        tabBar.setSelectedIndex(i);
                        break;
                    }
                }
            } finally {
                sSyncing = false;
            }
            final ViewGroup h2 = host;
            final com.example.liquidglass.LiquidGlassTabBar t2 = tabBar;
            host.post(() -> placeCenterNow(h2, t2, sCenterRefStatic, 0));
            LiquidGlassLog.log(android.util.Log.INFO,
                    "glass tab bar rebuilt: tabs=" + items.size()
                            + " fit=" + GlassConfig.fitTabs
                            + " centerGap=" + !publishHidden);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass tab bar rebuild failed", t);
        }
    }

    private static void applyFitWidth(
            com.example.liquidglass.LiquidGlassTabBar tabBar,
            int visibleCount, boolean publishHidden) {
        try {
            FrameLayout.LayoutParams lp = tabBar.getLayoutParams()
                    instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) tabBar.getLayoutParams() : null;
            if (lp == null) {
                return;
            }
            if (!GlassConfig.fitTabs) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = android.view.Gravity.TOP
                        | android.view.Gravity.FILL_HORIZONTAL;
                tabBar.setLayoutParams(lp);
                return;
            }
            float density = tabBar.getResources().getDisplayMetrics().density;
            int avail = tabBar.getResources().getDisplayMetrics().widthPixels
                    - Math.round(density * 20f);
            if (avail <= 0) {
                return;
            }
            float weightTotal = OTHER_TAB_WEIGHT * visibleCount
                    + (SELECTED_TAB_WEIGHT - OTHER_TAB_WEIGHT)
                    + (publishHidden ? 0f : CENTER_GAP_WEIGHT);
            int perTab = Math.min(
                    Math.round(FIT_TAB_MAX_WIDTH_DP * density),
                    Math.round(avail / weightTotal));
            int barW = Math.round(weightTotal * perTab)
                    + Math.round(density * 8f);
            lp.width = Math.min(barW, avail);
            lp.gravity = android.view.Gravity.TOP
                    | android.view.Gravity.CENTER_HORIZONTAL;
            tabBar.setLayoutParams(lp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass fit width failed", t);
        }
    }

    private static boolean applySelectionWeights(
            com.example.liquidglass.LiquidGlassTabBar tabBar,
            int selectedIndex) {
        boolean changed = false;
        try {
            if (tabBar.getChildCount() <= 0) {
                return false;
            }
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return false;
            }
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            int selected = selectedIndex;
            int tabIndex = 0;
            for (int i = 0; i < ll.getChildCount(); i++) {
                View child = ll.getChildAt(i);
                if (!(child instanceof android.widget.LinearLayout)) {
                    continue;
                }
                float weight = !GlassConfig.fitTabs
                        ? 1f
                        : (tabIndex == selected
                        ? SELECTED_TAB_WEIGHT : OTHER_TAB_WEIGHT);
                android.widget.LinearLayout.LayoutParams lp =
                        child.getLayoutParams()
                                instanceof android.widget.LinearLayout.LayoutParams
                                ? (android.widget.LinearLayout.LayoutParams)
                                child.getLayoutParams() : null;
                if (lp != null && lp.weight != weight) {
                    lp.weight = weight;
                    child.setLayoutParams(lp);
                    changed = true;
                }
                tabIndex++;
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass selection weights failed", t);
        }
        return changed;
    }

    private static void prepareSelectionLayout(
            com.example.liquidglass.LiquidGlassTabBar tabBar,
            int selectedIndex) {
        try {
            if (!applySelectionWeights(tabBar, selectedIndex)) {
                return;
            }
            int w = tabBar.getMeasuredWidth();
            int h = tabBar.getMeasuredHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            int ws = android.view.View.MeasureSpec.makeMeasureSpec(
                    w, android.view.View.MeasureSpec.EXACTLY);
            int hs = android.view.View.MeasureSpec.makeMeasureSpec(
                    h, android.view.View.MeasureSpec.EXACTLY);
            tabBar.measure(ws, hs);
            tabBar.layout(tabBar.getLeft(), tabBar.getTop(),
                    tabBar.getRight(), tabBar.getBottom());
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass selection layout failed", t);
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

    /**
 * 向 QWEA0 tab bar 前景（图标/文字）推主题色，玻璃体保持白色 REGULAR 染色不变。
 * 主路径直写 TabHolder 视图，另尝试私有 updateTabStyles() 刷新
 */
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
            ll.addView(spacer, count / 2, lp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("center gap failed", t);
        }
    }

    private static void placeCenterNow(ViewGroup host, ViewGroup tabBar,
                                       View center, int attempt) {
        if (center == null || attempt > 10) {
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
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    spacer.getWidth(), tabBar.getHeight(),
                    android.view.Gravity.TOP | android.view.Gravity.START);
            lp.leftMargin = left;
            lp.topMargin = tabBar.getTop();
            center.setLayoutParams(lp);
        } catch (Throwable ignored) {
        }
    }

    /**
 * 库默认以 MATCH_PARENT|WRAP_CONTENT 加 tabsRow 落在 TOP|START，固定高度下贴顶，改为 CENTER_VERTICAL
 */
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

    static void applyBarGeometry() {
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

            host.requestLayout();
            final ViewGroup h2 = host;
            final ViewGroup b2 = (ViewGroup) barV;
            host.post(() -> placeCenterNow(h2, b2, center, 0));
        } catch (Throwable t) {
            LiquidGlassLog.logErr("applyBarGeometry failed", t);
        }
    }

    private static boolean isRepeatRefreshTab(CharSequence title) {
        if (title == null) {
            return false;
        }
        String value = title.toString().trim();
        return "首页".equals(value) || "热点".equals(value) || "游戏库".equals(value);
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
                    }
                    if (!moved[0]) {
                        final int target = findTabIndexAt(tabBar, event.getX());
                        final int before = selectedBefore[0];
                        tabBar.post(() -> {
                            try {
                                if (target != before || target != tabBar.getSelectedIndex()
                                        || target < 0 || target >= sVisibleButtons.size()
                                        || target >= sRepeatRefreshTabs.size()
                                        || !Boolean.TRUE.equals(sRepeatRefreshTabs.get(target))) {
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

    private static void setupTabSelectionSync(final android.widget.RadioGroup bar,
                                              final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            java.lang.reflect.Field f = android.widget.RadioGroup.class
                    .getDeclaredField("mOnCheckedChangeListener");
            f.setAccessible(true);
            final Object original = f.get(bar);
            bar.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.RadioGroup group, int checkedId) {
                    if (original instanceof android.widget.RadioGroup.OnCheckedChangeListener) {
                        ((android.widget.RadioGroup.OnCheckedChangeListener) original)
                                .onCheckedChanged(group, checkedId);
                    }
                    try {
                        for (int i = 0; i < sVisibleButtons.size(); i++) {
                            if (sVisibleButtons.get(i).getId() == checkedId
                                    && tabBar.getSelectedIndex() != i) {
                                prepareSelectionLayout(tabBar, i);
                                tabBar.setSelectedIndex(i);
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "tab selection sync unavailable: " + t);
        }
    }

    /** Installed once per process: theme-driven glass body tint. */
    private static volatile boolean sTintHookInstalled;
    private static volatile boolean sChromeLight;
    private static volatile boolean sChromeForced;
    private static volatile ViewGroup sHostRef;
    private static volatile View sCenterRefStatic;
    private static volatile float sDensity;
    private static int sBasePadBottom;
    private static final java.lang.ref.WeakReference<View> EMPTY_BAR_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<View> sTabBarRef = EMPTY_BAR_REF;
    private static final java.lang.ref.WeakReference<android.widget.RadioGroup> EMPTY_RG_BAR_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<android.widget.RadioGroup> sNativeBarRef =
            EMPTY_RG_BAR_REF;
    private static final java.util.List<android.widget.RadioButton> sVisibleButtons =
            new java.util.ArrayList<>();
    private static final java.util.List<Boolean> sRepeatRefreshTabs =
            new java.util.ArrayList<>();
    private static volatile boolean sSyncing;
    private static volatile String sBuildSig = "";
    private static final float FIT_TAB_MAX_WIDTH_DP = 96f;
    private static final float SELECTED_TAB_WEIGHT = 1.4f;
    private static final float OTHER_TAB_WEIGHT = 0.9f;

    /**
 * 覆写渲染器 currentTintColor() 让玻璃体随应用主题（深色→subtle 白，浅色→高不透明白磨砂）。
 * 不碰亮度计的 chrome（图标/文字）适配
 */
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
                        // 水滴叠加在已染色条之上，减淡避免双重加权
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
                        // lighten stacked layers to ~60% of the bar's opacity
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

    /**
 * 参考项目同款入口：HomeTitleBar 搜索图标与设置页「通用设置」行长按。只追加长按，不改原有点击行为
 */
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
                        sGlassEntries.put(icon, Boolean.TRUE);
                        android.content.Context cx = icon.getContext();
                        if (cx instanceof Activity) {
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
        try {
            java.lang.reflect.Field f = android.widget.RadioGroup.class
                    .getDeclaredField("mOnCheckedChangeListener");
            f.setAccessible(true);
            final Object original = f.get(bar);
            android.widget.RadioGroup group = (android.widget.RadioGroup) bar;
            group.setOnCheckedChangeListener((rg, checkedId) -> {
                if (original instanceof android.widget.RadioGroup.OnCheckedChangeListener) {
                    ((android.widget.RadioGroup.OnCheckedChangeListener) original)
                            .onCheckedChanged(rg, checkedId);
                }
                if (rg.getParent() instanceof LiquidGlassHostLayout) {
                    ((LiquidGlassHostLayout) rg.getParent())
                            .popChild(rg.findViewById(checkedId));
                }
            });
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "tab pop animation unavailable: " + t);
        }
    }

    private static void hideLegacyShadow(ViewGroup root, int barId) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getClass() == View.class && child.getBackground() != null) {
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp instanceof RelativeLayout.LayoutParams) {
                    if (((RelativeLayout.LayoutParams) lp)
                            .getRules()[RelativeLayout.ABOVE] == barId) {
                        child.setVisibility(View.GONE);
                        return;
                    }
                }
            }
        }
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
