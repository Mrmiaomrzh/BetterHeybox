package com.better.heybox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * BetterHeybox 模块入口（libxposed Modern API 102）。
 *
 * 功能：
 * 1. 广告过滤（开屏 / 信息流 / 气泡 / 角标 / 推广贴），各功能可在设置界面独立开关
 * 2. 小黑盒通用设置页注入 "BetterHeybox 设置" 入口（复用原生 SettingItemView），
 *    点击后叠加原生子页面风格设置面板（TitleBar + CardView 分组 + SettingItemView 开关），
 *    深浅色完全跟随小黑盒主题
 *
 * 开关状态：模块 App 通过 libxposed service 写入 RemotePreferences，
 * 本类在小黑盒进程用 getRemotePreferences() 读取/写入（跨进程共享）。
 */
public class MainModule extends XposedModule {

    private static final String TAG = "BetterHeybox";
    private static final String TARGET_PKG = "com.max.xiaoheihe";
    private static final String TARGET_HEYBOX_VERSION = "1.3.393";
    private static final String ENTRY_TAG = "betterheybox_entry";
    private static final String EMBEDDED_SETTINGS_TAG = "betterheybox_embedded_settings";
    private static final AtomicBoolean VERSION_NOTICE_SHOWN = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName());
        log(Log.INFO, TAG, "framework: " + getFrameworkName()
                + " (" + getFrameworkVersion() + ") API " + getApiVersion());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        // 允许热重载（否则设置界面「立即重启」的热重载会被框架拒绝）
        log(Log.INFO, TAG, "允许热重载");
        return true;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        log(Log.INFO, TAG, "onPackageReady: " + packageName);

        if (TARGET_PKG.equals(packageName)) {
            log(Log.INFO, TAG, ">>> 命中小黑盒，安装 Hook");
            installHooks(param);
        }
    }

    private void installHooks(PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();

        hookVersionNotice(cl);
        hookUpdateBlocking(cl);
        hookOpenScreenAd(cl);
        hookFeedAds(cl);
        hookBubbleAndCornerAds(cl);
        hookSettingsEntry(cl);
        hookBottomTabs(cl);
        hookPromotePosts(cl);
        hookTextSelectHandler(cl);
        hookPostTextSelect(cl);
        hookScrollIntercept(cl);

        log(Log.INFO, TAG, "Hook 安装流程结束");
    }

    /** 读取功能开关（RemotePreferences，与设置界面跨进程共享） */
    private boolean isEnabled(String key, boolean def) {
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs != null) {
                return prefs.getBoolean(key, def);
            }
        } catch (Throwable t) {
            // 读取失败按默认值处理
        }
        return def;
    }

    // ==================== 0. 版本前置检查 / 更新屏蔽 ====================

    /**
     * Heybox 的页面基类会在主界面及其它页面恢复时回调，适合作为版本提示入口。
     * 提示使用 Heybox 自带的底部提示栏，避免引入额外 UI 依赖。
     */
    private void hookVersionNotice(ClassLoader cl) {
        try {
            Class<?> baseActivity = Class.forName(
                    "com.max.hbcommon.base.BaseActivity", false, cl);
            Method onResume = baseActivity.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    Activity activity = (Activity) self;
                    View decor = activity.getWindow().getDecorView();
                    decor.postDelayed(() -> showVersionNotice(activity, cl), 600L);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ Heybox 版本检测 Hook 已安装");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ Heybox 版本检测 Hook 失败", t);
        }
    }

    private void showVersionNotice(Activity activity, ClassLoader cl) {
        if (activity.isFinishing() || VERSION_NOTICE_SHOWN.get()) {
            return;
        }
        String version = "unknown";
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(TARGET_PKG, 0);
            if (info.versionName != null) {
                version = info.versionName;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "读取 Heybox 版本失败", t);
            return;
        }
        if (TARGET_HEYBOX_VERSION.equals(version)
                || !VERSION_NOTICE_SHOWN.compareAndSet(false, true)) {
            return;
        }

        String message = "BetterHeybox 目标版本为 Heybox " + TARGET_HEYBOX_VERSION
                + "，当前检测到 " + version;
        try {
            Class<?> toastUtil = Class.forName("com.max.hbutils.utils.f", false, cl);
            Method showBottomHint = toastUtil.getDeclaredMethod("d", String.class);
            showBottomHint.invoke(null, message);
        } catch (Throwable t) {
            // 目标 Toast 工具不可用时仍保留版本提示。
            Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        }
        log(Log.WARN, TAG, message);
    }

    /** 屏蔽 AppUpdateManager 的统一更新入口，开关关闭时完全保留原行为。 */
    private void hookUpdateBlocking(ClassLoader cl) {
        try {
            Class<?> manager = Class.forName(
                    "com.max.xiaoheihe.utils.AppUpdateManager", false, cl);
            Method updateEntry = null;
            for (Method method : manager.getDeclaredMethods()) {
                if ("P".equals(method.getName())
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == Boolean.class) {
                    updateEntry = method;
                    break;
                }
            }
            if (updateEntry == null) {
                log(Log.WARN, TAG, "✘ 未找到 AppUpdateManager.P(Boolean)");
                return;
            }
            hook(updateEntry).intercept(chain -> {
                if (isEnabled(App.KEY_BLOCK_UPDATE, false)) {
                    log(Log.INFO, TAG, "已屏蔽 Heybox 更新入口 AppUpdateManager.P()");
                    return chain.getThisObject();
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "✔ Heybox 更新屏蔽 Hook 已安装");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ Heybox 更新屏蔽 Hook 失败", t);
        }
    }

    // ==================== 1. 开屏广告 ====================

    private void hookOpenScreenAd(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.ads.e", false, cl);
            Method g = clazz.getDeclaredMethod("g", boolean.class);
            hook(g).intercept(chain -> {
                if (isEnabled(App.KEY_OPEN_SCREEN, true)) {
                    log(Log.INFO, TAG, "拦截开屏广告 e.g()");
                    return null; // 调用方已判空，null = 无广告
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "✔ 开屏广告 Hook 已安装");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 开屏广告 Hook 失败", t);
        }
    }

    // ==================== 2. 信息流广告 ====================

    private void hookFeedAds(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.network.gson.FeedsContentDeserializer", false, cl);
            // 必须用小黑盒的 classloader 加载 gson（单参 Class.forName 会用模块自己的 classloader）
            Class<?> jsonElement = Class.forName("com.google.gson.JsonElement", false, cl);
            Class<?> type = Class.forName("java.lang.reflect.Type", false, cl);
            Class<?> ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl);

            try {
                Method a = clazz.getDeclaredMethod("a", jsonElement, type, ctx);
                hook(a).intercept(chain -> filterFeedAd(chain));
                log(Log.INFO, TAG, "✔ 信息流广告 Hook 已安装 (a)");
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Method deserialize = clazz.getDeclaredMethod("deserialize", jsonElement, type, ctx);
                hook(deserialize).intercept(chain -> filterFeedAd(chain));
                log(Log.INFO, TAG, "✔ 信息流广告 Hook 已安装 (deserialize)");
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 信息流广告 Hook 失败", t);
        }
    }

    /** 信息流广告过滤：content_type == "23" 时返回空 FeedsContentBaseObj（避免 null 导致列表 NPE 崩溃） */
    private Object filterFeedAd(Object chainObj) throws Throwable {
        XposedInterface.Chain chain = (XposedInterface.Chain) chainObj;
        if (!isEnabled(App.KEY_FEED_AD, true)) {
            return chain.proceed();
        }
        try {
            Object elem = chain.getArg(0);
            if (elem != null) {
                Object obj = elem.getClass().getMethod("getAsJsonObject").invoke(elem);
                if (obj != null) {
                    Object ct = obj.getClass().getMethod("get", String.class).invoke(obj, "content_type");
                    if (ct != null) {
                        String ctStr = (String) ct.getClass().getMethod("getAsString").invoke(ct);
                        if ("23".equals(ctStr)) {
                            log(Log.INFO, TAG, "过滤信息流广告条目 (content_type=23)");
                            return createEmptyFeedObj(chain.getThisObject());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "信息流广告判断异常，放行: " + t);
        }
        return chain.proceed();
    }

    /** 创建空的 FeedsContentBaseObj（content_type=0 + 无分割线），替换广告条目避免 null 崩溃 */
    private Object createEmptyFeedObj(Object thisObj) {
        try {
            ClassLoader cl = thisObj != null ? thisObj.getClass().getClassLoader()
                    : getClass().getClassLoader();
            Class<?> base = Class.forName("com.max.xiaoheihe.bean.news.FeedsContentBaseObj", false, cl);
            Object empty = base.getDeclaredConstructor().newInstance();
            base.getMethod("setContent_type", String.class).invoke(empty, "0");
            try {
                base.getMethod("setShowDivider", boolean.class).invoke(empty, false);
            } catch (Throwable ignored) {
            }
            return empty;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "创建空 FeedsContentBaseObj 失败: " + t);
            return null;
        }
    }

    // ==================== 3. 气泡 / 角标广告 ====================

    private void hookBubbleAndCornerAds(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.ads.h", false, cl);
            Class<?> callback = Class.forName("com.max.xiaoheihe.utils.x0$g", false, cl);

            // 气泡展示检查入口：l() 的参数是内部类 h$g（注意：不是 x0$g）
            try {
                Class<?> innerG = Class.forName("com.max.xiaoheihe.module.ads.h$g", false, cl);
                Method l = clazz.getDeclaredMethod("l", innerG);
                hook(l).intercept(chain -> {
                    if (isEnabled(App.KEY_BUBBLE_AD, true)) {
                        log(Log.INFO, TAG, "拦截气泡广告 h.l()");
                        return null;
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "✔ 气泡广告 Hook 已安装");
            } catch (NoSuchMethodException ignored) {
            }

            // 广告拉取入口：阻断后 f86785b 恒为 null，角标数据源消失
            try {
                Method h = clazz.getDeclaredMethod("h", callback);
                hook(h).intercept(chain -> {
                    if (isEnabled(App.KEY_CORNER_AD, true)) {
                        log(Log.INFO, TAG, "拦截广告拉取 h.h()");
                        return null;
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "✔ 角标广告拉取 Hook 已安装");
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 气泡/角标广告 Hook 失败", t);
        }
    }

    // ==================== 4. 设置页入口 ====================

    /**
     * 向小黑盒通用设置页（GeneralSettingsActivity）注入 BetterHeybox 入口。
     * 在 G1（onCreate 模板，每次进页恰好触发一次）返回后插入，此时布局已 setContentView。
     * 注入不能同步做：binding 字段与列表容器此时未必就绪（经验证会破坏设置项），
     * 故 post 到下一帧再试，未就绪则由 insertSettingsEntryWithRetry 短间隔重试。
     */
    private void hookSettingsEntry(ClassLoader cl) {
        try {
            // 通用设置页：GeneralSettingsActivity，ViewBinding = fi.r0（ActivityGeneralSettingsBinding）。
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.account.GeneralSettingsActivity", false, cl);
            Method g1 = clazz.getDeclaredMethod("G1");
            hook(g1).intercept(chain -> {
                Object result = chain.proceed(); // 先执行原 G1（setContentView 完成）
                try {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
                        final Activity activity = (Activity) thisObj;
                        activity.getWindow().getDecorView().post(new Runnable() {
                            @Override
                            public void run() {
                                insertSettingsEntryWithRetry(activity, 0);
                            }
                        });
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "设置入口插入调度异常", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ 设置页入口 Hook 已安装 (G1+retry)");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 设置页入口 Hook 失败", t);
        }
    }

    /**
     * 插入入口：布局未就绪时以 50ms 间隔重试（最多约 1s，20 次），
     * 成功、最终失败或 Activity 销毁即停止。
     */
    private void insertSettingsEntryWithRetry(final Activity activity, final int attempt) {
        if (attempt > 20) {
            log(Log.WARN, TAG, "设置页布局迟迟未就绪，放弃插入入口");
            return;
        }
        try {
            boolean ok = tryInsertSettingsEntry(activity);
            if (!ok && !activity.isFinishing()) {
                activity.getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        insertSettingsEntryWithRetry(activity, attempt + 1);
                    }
                }, 50);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "插入设置入口重试异常: " + t);
        }
    }

    /** 尝试插入入口，返回是否成功（未就绪返回 false 供上层重试） */
    private boolean tryInsertSettingsEntry(Activity activity) {
        try {
            Object binding = getGeneralSettingsBinding(activity);
            if (binding == null) {
                return false;
            }
            Object listObj = binding.getClass().getMethod("b").invoke(binding);
            if (!(listObj instanceof LinearLayout)) {
                return false;
            }
            LinearLayout list = (LinearLayout) listObj;

            // 已有入口（Activity 重建）则先移除，再插入
            for (int i = list.getChildCount() - 1; i >= 0; i--) {
                if (ENTRY_TAG.equals(list.getChildAt(i).getTag())) {
                    list.removeViewAt(i);
                }
            }

            View entry = buildEntryCard(activity);
            if (entry == null) {
                return false;
            }
            entry.setTag(ENTRY_TAG);
            entry.setClickable(true);
            entry.setFocusable(true);
            entry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        showEmbeddedSettings(activity);
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "渲染内嵌设置界面失败", t);
                        Toast.makeText(activity, "BetterHeybox 内嵌设置加载失败",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            // 插到列表最前面：作为第一个列表项（标题栏下方、原生设置卡之前）
            list.addView(entry, 0);
            log(Log.INFO, TAG, "✔ 原生 BetterHeybox 入口已作为列表项插入通用设置页顶部");
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "插入设置入口异常: " + t);
            return false;
        }
    }

    /** 反射取 GeneralSettingsActivity 的 ViewBinding（fi.r0，ActivityGeneralSettingsBinding）字段 */
    private Object getGeneralSettingsBinding(Activity activity) {
        try {
            for (Field f : activity.getClass().getDeclaredFields()) {
                if ("fi.r0".equals(f.getType().getName())) {
                    f.setAccessible(true);
                    return f.get(activity);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "查找 GeneralSettings binding 失败: " + t);
        }
        return null;
    }

    /** 当前叠加显示的面板（弱引用，Activity 销毁自动释放） */
    private WeakReference<View> mSettingsPanel;

    /**
     * 原生子页面风格设置面板：叠加到 GeneralSettingsActivity 自身窗口（decorView），
     * 与小黑盒共享同一 Window → 状态栏沉浸式、深浅色全部跟随小黑盒主题。
     * 结构：状态栏占位 + 原生 TitleBar（返回箭头）+ ScrollView + 分组卡片（SettingItemView 开关）。
     * 关闭：顶栏返回箭头或系统返回键。
     */
    private void showEmbeddedSettings(final Activity activity) {
        try {
            dismissEmbeddedSettings();

            int appbarBg = 0xFFFFFFFF;
            int pageBg = 0xFFFFFFFF;
            try {
                appbarBg = activity.getResources().getColor(0x7f060022);
            } catch (Throwable ignored) {
            }
            try {
                pageBg = activity.getResources().getColor(0x7f0600b9);
            } catch (Throwable ignored) {
            }

            // 状态栏高度：沉浸式下顶栏背景延伸到状态栏后方
            int statusBarH = 0;
            try {
                int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) {
                    statusBarH = activity.getResources().getDimensionPixelSize(id);
                }
            } catch (Throwable ignored) {
            }
            if (statusBarH <= 0) {
                statusBarH = dp(activity, 24);
            }

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(pageBg);
            overlay.setTag(EMBEDDED_SETTINGS_TAG);
            overlay.setClickable(true);
            overlay.setFocusable(true);
            overlay.setFocusableInTouchMode(true);

            LinearLayout page = new LinearLayout(activity);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.addView(page);

            // 状态栏占位（与顶栏同色，沉浸式背景连贯）
            View statusSpacer = new View(activity);
            statusSpacer.setBackgroundColor(appbarBg);
            statusSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, statusBarH));
            page.addView(statusSpacer);

            // 原生 TitleBar：返回箭头 + 标题，与小黑盒设置子页顶栏完全一致
            ClassLoader cl = activity.getClassLoader();
            Class<?> titleBarCls = Class.forName("com.max.hbcommon.component.TitleBar", false, cl);
            Object titleBar = titleBarCls.getConstructor(Context.class).newInstance(activity);
            ((View) titleBar).setBackgroundColor(appbarBg);
            titleBarCls.getMethod("setTitle", CharSequence.class).invoke(titleBar, "BetterHeybox 设置");
            titleBarCls.getMethod("setNavigationIcon", int.class).invoke(titleBar, 0x7f08009b);
            Class<?> ocl = Class.forName("android.view.View$OnClickListener", false, cl);
            Object backListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissEmbeddedSettings();
                }
            };
            titleBarCls.getMethod("setNavigationOnClickListener", ocl).invoke(titleBar, backListener);
            ((View) titleBar).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44)));
            page.addView((View) titleBar);

            // 内容：ScrollView + 分组卡片
            ScrollView scroller = new ScrollView(activity);
            scroller.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 顶栏与首张卡片间距：paddingTop=2dp（与原生 activity_general_settings 一致）
            box.setPadding(0, dp(activity, 2), 0, 0);
            scroller.addView(box);
            page.addView(scroller);

            for (SettingsGroup group : SETTINGS_GROUPS) {
                View card = buildSectionCard(activity, cl, group);
                if (card != null) {
                    box.addView(card);
                }
            }

            // 系统返回键：只关闭面板，不退出设置页
            overlay.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                        dismissEmbeddedSettings();
                        return true;
                    }
                    return false;
                }
            });

            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(overlay);
            overlay.requestFocus();
            mSettingsPanel = new WeakReference<View>(overlay);
            log(Log.INFO, TAG, "✔ 原生子页面设置面板已叠加到小黑盒窗口");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "渲染原生设置面板失败", t);
        }
    }

    /** 移除叠加面板 */
    private void dismissEmbeddedSettings() {
        try {
            View panel = mSettingsPanel == null ? null : mSettingsPanel.get();
            if (panel != null && panel.getParent() != null) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
        } catch (Throwable ignored) {
        }
        mSettingsPanel = null;
    }

    /** 单个开关项定义 */
    private static class SwitchDef {
        final String title;
        final String desc;
        final String key;
        final boolean def;
        final boolean restart; // 需重启小黑盒才生效
        SwitchDef(String title, String desc, String key, boolean def, boolean restart) {
            this.title = title;
            this.desc = desc;
            this.key = key;
            this.def = def;
            this.restart = restart;
        }
    }

    /** 开关分组（含分组标题，每组渲染为一张原生卡片） */
    private static class SettingsGroup {
        final String title;
        final SwitchDef[] items;
        SettingsGroup(String title, SwitchDef[] items) {
            this.title = title;
            this.items = items;
        }
    }

    /** 开关分类：广告过滤 / 底部导航栏隐藏 / 解除复制 / 通用 */
    private static final SettingsGroup[] SETTINGS_GROUPS = new SettingsGroup[]{
            new SettingsGroup("广告过滤", new SwitchDef[]{
                    new SwitchDef("开屏广告", null, App.KEY_OPEN_SCREEN, true, false),
                    new SwitchDef("信息流广告", null, App.KEY_FEED_AD, true, false),
                    new SwitchDef("气泡广告", null, App.KEY_BUBBLE_AD, true, false),
                    new SwitchDef("角标广告", null, App.KEY_CORNER_AD, true, false),
                    new SwitchDef("推广贴", null, App.KEY_PROMOTE_AD, true, false),
            }),
            new SettingsGroup("底部导航栏隐藏", new SwitchDef[]{
                    new SwitchDef("隐藏首页", null, App.KEY_HIDE_TAB_HOME, false, true),
                    new SwitchDef("隐藏热点", null, App.KEY_HIDE_TAB_HOT, false, true),
                    new SwitchDef("隐藏游戏库", null, App.KEY_HIDE_TAB_GAME, false, true),
                    new SwitchDef("隐藏加号", null, App.KEY_HIDE_ADD, false, true),
            }),
            new SettingsGroup("解除复制", new SwitchDef[]{
                    new SwitchDef("解除复制", "恢复系统标准文本选择", App.KEY_COPY_POST, true, false),
            }),
            new SettingsGroup("通用", new SwitchDef[]{
                    new SwitchDef("屏蔽更新", "屏蔽小黑盒更新入口", App.KEY_BLOCK_UPDATE, false, false),
            }),
    };

    /**
     * 构建一个分组：外层 LinearLayout = [独立标题行 + CardView 列表]。
     * 与小黑盒分组布局一致：标题是卡片外部的独立 TextView（text_size_13 + tertiary 色，
     * 12dp 水平边距、顶部 16dp 间距），CardView（圆角8dp/0海拔/12dp边距/8dp顶距）内放设置项。
     */
    private View buildSectionCard(Activity activity, ClassLoader cl, SettingsGroup group) {
        try {
            LinearLayout groupRoot = new LinearLayout(activity);
            groupRoot.setOrientation(LinearLayout.VERTICAL);
            groupRoot.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 1. 独立标题行（卡片外，与小黑盒「我的新动态」等分组标题一致）
            TextView groupTitle = new TextView(activity);
            groupTitle.setText(group.title);
            int titleSize;
            try {
                titleSize = activity.getResources().getDimensionPixelSize(0x7f07039b);
            } catch (Throwable ignored) {
                titleSize = dp(activity, 13);
            }
            groupTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize);
            int titleColor = 0xFF8A8A8A;
            try {
                titleColor = activity.getResources().getColor(0x7f06013a);
            } catch (Throwable ignored) {
            }
            groupTitle.setTextColor(titleColor);
            groupTitle.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int tm = dp(activity, 12);
            titleLp.setMargins(tm, dp(activity, 16), tm, 0);
            groupTitle.setLayoutParams(titleLp);
            groupRoot.addView(groupTitle);

            // 2. CardView：圆角 8dp、0 海拔，与小黑盒原生卡片一致
            Class<?> cardCls = Class.forName("androidx.cardview.widget.CardView", false, cl);
            Object card = cardCls.getConstructor(Context.class).newInstance(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            cardCls.getMethod("setRadius", float.class).invoke(card, 8f * density);
            cardCls.getMethod("setCardElevation", float.class).invoke(card, 0f);
            try {
                cardCls.getMethod("setMaxCardElevation", float.class).invoke(card, 0f);
            } catch (Throwable ignored) {
            }
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = dp(activity, 12);
            cardLp.setMargins(m, dp(activity, 8), m, 0);
            ((View) card).setLayoutParams(cardLp);

            // 3. 卡片内部竖向容器
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((ViewGroup) card).addView(content);

            // 4. 逐项构建 SettingItemView 开关
            for (int i = 0; i < group.items.length; i++) {
                View item = createSettingSwitch(activity, cl, group.items[i]);
                if (item == null) {
                    continue;
                }
                // 末项不显示底部分割线（与小黑盒原生卡片一致）
                if (i == group.items.length - 1) {
                    try {
                        Class<?> itemCls = Class.forName(
                                "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
                        itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, false);
                    } catch (Throwable ignored) {
                    }
                }
                content.addView(item);
            }
            groupRoot.addView((View) card);
            return groupRoot;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "构建设置卡片分区失败: " + t);
            return null;
        }
    }

    /** 反射创建一个小黑盒原生 SettingItemView 开关项（Type.SwitchButton） */
    private View createSettingSwitch(Activity activity, ClassLoader cl, SwitchDef def) {
        try {
            Class<?> itemCls = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
            Object item = itemCls.getConstructor(Context.class).newInstance(activity);

            itemCls.getMethod("setTitle", String.class).invoke(item, def.title);
            if (def.desc != null) {
                itemCls.getMethod("setTitleDesc", String.class).invoke(item, def.desc);
            }
            Class<?> typeEnum = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView$Type", false, cl);
            Object switchType = Enum.valueOf((Class) typeEnum, "SwitchButton");
            itemCls.getMethod("setRightType", typeEnum).invoke(item, switchType);
            try {
                itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, true);
            } catch (Throwable ignored) {
            }

            // 回填当前值：setChecked(checked, false) 第二参 = 不触发监听器（SwitchButton 内部逻辑）
            boolean cur = readEmbeddedBoolean(def.key, def.def);
            itemCls.getMethod("setChecked", boolean.class, boolean.class).invoke(item, cur, false);

            // 监听切换：writeEmbeddedBoolean 写回（广播交由模块进程写入 RemotePreferences），
            // 需重启项复用小黑盒「重启APP生效」Dialog（AccelWorldWebkitKt.x）。
            // 用真实匿名类而非 Proxy：避免 Proxy 动态代理与宿主实现间的 ClassCastException。
            Class<?> listenerCls = Class.forName(
                    "android.widget.CompoundButton$OnCheckedChangeListener", false, cl);
            Object listener = new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    try {
                        if (writeEmbeddedBoolean(activity, def.key, isChecked) && def.restart) {
                            showRestartAppDialog(activity, cl);
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "开关监听回调异常: " + def.title, t);
                    }
                }
            };
            itemCls.getMethod("setOnCheckedChangeListener", listenerCls).invoke(item, listener);

            // 高度复用小黑盒原生设置项高度（list_item_height = 46dp）
            int itemH = activity.getResources().getDimensionPixelSize(0x7f0700ff);
            ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            return (View) item;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "创建 SettingItemView 开关失败 (" + def.title + "): " + t);
            return null;
        }
    }

    /**
     * 需重启项切换后弹「重启APP生效」确认框。
     * 优先复用小黑盒原生 Dialog（AccelWorldWebkitKt.x，与「使用系统字体」等项一致，
     * 正按钮「重启」会真正重启小黑盒进程）；反射调用失败则回退系统 AlertDialog。
     */
    private void showRestartAppDialog(Activity activity, ClassLoader cl) {
        try {
            Class<?> ktCls = Class.forName(
                    "com.max.xiaoheihe.accelworld.AccelWorldWebkitKt", false, cl);
            Method x = ktCls.getDeclaredMethod("x", Context.class, String.class);
            x.invoke(null, activity, "底栏改动需重启小黑盒后生效");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "复用小黑盒重启 Dialog 失败，回退系统 AlertDialog: " + t);
            try {
                new AlertDialog.Builder(activity)
                        .setTitle("重新启动APP生效")
                        .setMessage("底栏改动需重启小黑盒后生效")
                        .setPositiveButton("我知道了", null)
                        .show();
            } catch (Throwable t2) {
                log(Log.ERROR, TAG, "回退弹窗也失败", t2);
            }
        }
    }

    private boolean readEmbeddedBoolean(String key, boolean defaultValue) {
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            return prefs != null ? prefs.getBoolean(key, defaultValue) : defaultValue;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "读取设置失败: " + key, t);
            return defaultValue;
        }
    }

    private boolean writeEmbeddedBoolean(Activity activity, String key, boolean value) {
        try {
            Intent request = new Intent(PreferenceReceiver.ACTION_SET_BOOLEAN)
                    .setComponent(new android.content.ComponentName(
                            "com.better.heybox", "com.better.heybox.PreferenceReceiver"))
                    .putExtra(PreferenceReceiver.EXTRA_KEY, key)
                    .putExtra(PreferenceReceiver.EXTRA_VALUE, value);
            activity.sendBroadcast(request);
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "写入设置失败: " + key, t);
            Toast.makeText(activity, R.string.service_not_ready, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /**
     * 原生样式入口卡片，对齐「通用设置子级列表项」样式：
     * CardView（圆角8dp/0海拔/12dp边距/8dp顶距）> LinearLayout > SettingItemView(Arrow)。
     * 列表项 46dp 高、带底部 1dp 分割线，与同级列表项一致，深浅色自动跟随小黑盒。
     */
    private View buildEntryCard(final Activity activity) {
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> cardCls = Class.forName("androidx.cardview.widget.CardView", false, cl);
            Object card = cardCls.getConstructor(Context.class).newInstance(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            cardCls.getMethod("setRadius", float.class).invoke(card, 8f * density);
            cardCls.getMethod("setCardElevation", float.class).invoke(card, 0f);
            try {
                cardCls.getMethod("setMaxCardElevation", float.class).invoke(card, 0f);
            } catch (Throwable ignored) {
            }
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = dp(activity, 12);
            cardLp.setMargins(m, dp(activity, 8), m, 0);
            ((View) card).setLayoutParams(cardLp);

            // 卡内竖向容器（与原生设置卡一致）
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((ViewGroup) card).addView(content);

            // 列表项：SettingItemView（Arrow），带底部 1dp 分割线，46dp 高
            Class<?> itemCls = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
            Object item = itemCls.getConstructor(Context.class).newInstance(activity);
            itemCls.getMethod("setTitle", String.class).invoke(item, "BetterHeybox 设置");
            try {
                itemCls.getMethod("setTitleDesc", String.class).invoke(item, "广告过滤与界面增强");
            } catch (Throwable ignored) {
            }
            Class<?> typeEnum = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView$Type", false, cl);
            Object arrow = Enum.valueOf((Class) typeEnum, "Arrow");
            itemCls.getMethod("setRightType", typeEnum).invoke(item, arrow);
            try {
                itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, true);
            } catch (Throwable ignored) {
            }
            int itemH = activity.getResources().getDimensionPixelSize(0x7f0700ff);
            ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            content.addView((View) item);
            return (View) card;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "构建原生入口卡片失败: " + t);
            return null;
        }
    }

    private int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 5. 底部导航栏屏蔽 ====================

    private void hookBottomTabs(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onCreate = clazz.getDeclaredMethod("onCreate", android.os.Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed(); // 先执行原 onCreate（底部 tab 已初始化）
                try {
                    applyBottomTabSettings(chain.getThisObject());
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "应用底部导航栏设置异常", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ 底部导航栏 Hook 已安装");

            // hook onResume：热重载后切回小黑盒立即重新应用底栏设置（不依赖生命周期观察者回调）
            try {
                Method onResume = clazz.getDeclaredMethod("onResume");
                hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        applyBottomTabSettings(chain.getThisObject());
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "onResume 应用底栏设置失败: " + t);
                    }
                    return result;
                });
                log(Log.INFO, TAG, "✔ 底栏 onResume Hook 已安装");
            } catch (Throwable t) {
                log(Log.WARN, TAG, "底栏 onResume Hook 失败: " + t);
            }

            // 加号/底栏会被 MainActivity$j.b(Boolean) 生命周期回调重新 setVisibility(0) 显示，
            // hook 该回调，显示后重新应用隐藏设置
            try {
                Class<?> observerCls = Class.forName("com.max.xiaoheihe.MainActivity$j", false, cl);
                for (Method m : observerCls.getDeclaredMethods()) {
                    if ("b".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Boolean.class) {
                        hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object mainActivity = findOuterInstance(chain.getThisObject(), cl);
                                if (mainActivity != null) {
                                    applyBottomTabSettings(mainActivity);
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "底栏状态回调后重新隐藏失败: " + t);
                            }
                            return result;
                        });
                        log(Log.INFO, TAG, "✔ 底栏状态回调 Hook 已安装");
                        break;
                    }
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "底栏状态回调 Hook 安装失败: " + t);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 底部导航栏 Hook 失败", t);
        }
    }

    /** 通用查找：内部类里指向 MainActivity 的字段（this$0 被 Robust 改名） */
    private Object findOuterInstance(Object innerObj, ClassLoader cl) {
        try {
            Class<?> mainCls = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            for (Field f : innerObj.getClass().getDeclaredFields()) {
                if (f.getType() == mainCls) {
                    f.setAccessible(true);
                    return f.get(innerObj);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "查找外部 MainActivity 实例失败: " + t);
        }
        return null;
    }

    /** 根据开关反射隐藏底部导航栏的 tab（首页/热点/游戏库）与加号 */
    private void applyBottomTabSettings(Object activityObj) {
        try {
            Object binding = findViewBinding(activityObj);
            if (binding == null) {
                log(Log.WARN, TAG, "未找到 ViewBinding 字段（fi.i1）");
                return;
            }
            // 诊断：打印 hook 侧读到的开关值
            log(Log.INFO, TAG, "开关值: home=" + isEnabled(App.KEY_HIDE_TAB_HOME, false)
                    + " hot=" + isEnabled(App.KEY_HIDE_TAB_HOT, false)
                    + " game=" + isEnabled(App.KEY_HIDE_TAB_GAME, false)
                    + " add=" + isEnabled(App.KEY_HIDE_ADD, false));
            boolean anyTabHidden = false;
            if (isEnabled(App.KEY_HIDE_TAB_HOME, false)) {
                hideTabField(binding, "j", "首页");
                anyTabHidden = true;
            }
            if (isEnabled(App.KEY_HIDE_TAB_HOT, false)) {
                hideTabField(binding, "k", "热点");
                anyTabHidden = true;
            }
            if (isEnabled(App.KEY_HIDE_TAB_GAME, false)) {
                hideTabField(binding, "m", "游戏库");
                anyTabHidden = true;
            }
            // 加号：独立开关，或隐藏了任意 tab 时联动隐藏（保持底栏布局对称）
            if (isEnabled(App.KEY_HIDE_ADD, false) || anyTabHidden) {
                hideTabField(binding, "r", "加号");
                // 同时去掉「推荐」占位（rb_3 默认 INVISIBLE 占位），让剩余 tab 完全等分、无空白
                hideTabField(binding, "l", "推荐占位");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "底部导航栏设置应用失败: " + t);
        }
    }

    /** 通用查找：遍历字段找类型为 fi.i1 的绑定类字段（Robust 混淆后名字为 v4） */
    private Object findViewBinding(Object activity) {
        try {
            for (Field f : activity.getClass().getDeclaredFields()) {
                if (f.getType().getName().endsWith(".i1")) {
                    f.setAccessible(true);
                    return f.get(activity);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "查找 ViewBinding 失败: " + t);
        }
        return null;
    }

    /** 诊断：打印对象所有字段名和类型 */
    private void dumpFields(Object obj) {
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                log(Log.WARN, TAG, "  field: " + f.getName() + " : " + f.getType().getName());
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "转储字段失败: " + t);
        }
    }

    private void hideTabField(Object binding, String fieldName, String label) {
        try {
            Field field = binding.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object obj = field.get(binding);
            if (obj instanceof View) {
                final View v = (View) obj;
                v.setVisibility(View.GONE);
                // 小黑盒会在启动/生命周期回调中延迟重新显示 tab/加号，
                // 延迟多次重新隐藏以覆盖（否则出现 tab 与加号重合、布局错乱）
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 500);
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 1500);
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 3000);
                log(Log.INFO, TAG, "隐藏 " + label + ": " + v.getVisibility());
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "隐藏 tab 失败 (" + label + ")，字段 " + fieldName + " 可能被 Robust 重命名，转储字段名：");
            dumpFields(binding);
        }
    }

    // ==================== 6. 推广贴屏蔽 ====================

    private void hookPromotePosts(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.bbs.utils.b", false, cl);
            // 遍历找渲染 BBS 帖子的静态方法 L（5 参数），避免精确参数类型匹配受 Robust 影响
            for (Method m : clazz.getDeclaredMethods()) {
                if ("L".equals(m.getName()) && m.getParameterTypes().length == 5) {
                    hook(m).intercept(chain -> {
                        try {
                            if (!isEnabled(App.KEY_PROMOTE_AD, true)) {
                                return chain.proceed();
                            }
                            Object bbsLink = chain.getArg(1);
                            String ct = getContentType(bbsLink);
                            if ("28".equals(ct) || "29".equals(ct)) {
                                log(Log.INFO, TAG, "屏蔽推广贴 (content_type=" + ct + ")");
                                hideItemView(chain.getArg(3));
                                return null; // 跳过原渲染
                            }
                            // 屏蔽指定官方账号的帖子（小黑盒推广 / 商城看板娘）
                            String username = getUsername(chain.getArg(2));
                            if ("小黑盒推广".equals(username) || "商城看板娘".equals(username)) {
                                log(Log.INFO, TAG, "屏蔽账号帖子: " + username);
                                hideItemView(chain.getArg(3));
                                return null;
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "推广贴判断异常，放行: " + t);
                        }
                        return chain.proceed();
                    });
                    log(Log.INFO, TAG, "✔ 推广贴屏蔽 Hook 已安装");
                    return;
                }
            }
            log(Log.WARN, TAG, "✘ 未找到推广贴渲染方法 L");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 推广贴屏蔽 Hook 失败", t);
        }
    }

    /** 反射获取 BBSLinkObj 的 content_type */
    private String getContentType(Object bbsLink) {
        try {
            Method getter = bbsLink.getClass().getMethod("getContent_type");
            Object v = getter.invoke(bbsLink);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射获取帖子作者的 username（BBSUserInfoObj.getUsername()） */
    private String getUsername(Object userInfo) {
        try {
            if (userInfo == null) {
                return null;
            }
            Method getter = userInfo.getClass().getMethod("getUsername");
            Object v = getter.invoke(userInfo);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 隐藏 ViewHolder 的 itemView（高度置 0，避免列表留空白） */
    private void hideItemView(Object viewHolder) {
        try {
            Field itemViewField = viewHolder.getClass().getField("itemView");
            Object v = itemViewField.get(viewHolder);
            if (v instanceof View) {
                View itemView = (View) v;
                itemView.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                if (lp != null) {
                    lp.height = 0;
                    itemView.setLayoutParams(lp);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "隐藏 itemView 失败: " + t);
        }
    }

    // ==================== 6. 帖子正文复制（系统标准文本选择） ====================

    /**
     * 解除小黑盒自定义 TextSelectHandler 的长按拦截（防复制机制的核心）。
     *
     * 小黑盒在 SDK>26 时给正文/标题 TextView 设置 TextSelectHandler 触摸拦截：
     * onTouch 消费长按，交给 TextSelectViewManager 自定义选择（复制被截断成「盒」）。
     * 这里 hook TextSelectHandler.onTouch 恒返回 false，让长按事件继续传递，
     * 配合 enablePostTextSelect 的 setTextIsSelectable(true)，长按正文即弹安卓系统
     * 「复制/全选」菜单，采用系统复制。全局生效且不惧小黑盒事后重设拦截。
     */
    private void hookTextSelectHandler(ClassLoader cl) {
        try {
            Class<?> handler = Class.forName(
                    "com.max.common.common.selecthandler.TextSelectHandler", false, cl);
            Method onTouch = null;
            for (Method m : handler.getDeclaredMethods()) {
                if ("onTouch".equals(m.getName()) && m.getParameterCount() == 2) {
                    onTouch = m;
                    break;
                }
            }
            if (onTouch == null) {
                log(Log.WARN, TAG, "✘ 未找到 TextSelectHandler.onTouch");
                return;
            }
            hook(onTouch).intercept(chain -> false); // 不消费任何触摸 → 长按回到 TextView 默认逻辑
            log(Log.INFO, TAG, "✔ TextSelectHandler 防复制拦截已解除");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ TextSelectHandler 解除失败", t);
        }
    }

    /**
     * 让帖子详情页标题/正文 TextView 恢复安卓系统标准文本选择（textIsSelectable）。
     *
     * 小黑盒在 SDK>26 时只设置 TextSelectHandler 而不调用 setTextIsSelectable(true)
     * （标准分支仅在 SDK<=26 生效）。这里在 installViews 后强制开启，
     * 长按正文即弹出系统「复制/全选」菜单。
     */
    private void hookPostTextSelect(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName(
                    "com.max.xiaoheihe.module.bbs.post.ui.fragments.v2.PostPictureFragmentV2", false, cl);
            Method target = null;
            for (Method m : clazz.getDeclaredMethods()) {
                // Robust 可能重命名方法，按前缀 + 签名匹配
                if (m.getName().startsWith("installViews") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == View.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                log(Log.WARN, TAG, "✘ 未找到 PostPictureFragmentV2.installViews");
                return;
            }
            hook(target).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof View) {
                        final View content = (View) arg;
                        // 等布局稳定后恢复标准文本选择（installViews 同步流程里小黑盒会设置拦截）
                        content.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    enablePostTextSelect(content);
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "正文选择设置异常: " + t);
                                }
                            }
                        }, 300);
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "正文选择调度异常: " + t);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ 帖子正文复制 Hook 已安装");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 帖子正文复制 Hook 失败", t);
        }
    }

    /** 恢复标题/正文 TextView 的系统标准文本选择（绕过小黑盒防复制） */
    private void enablePostTextSelect(View root) {
        if (!isEnabled(App.KEY_COPY_POST, true)) {
            return;
        }
        String[] idNames = {"tv_title", "tv_desc"};
        for (String idName : idNames) {
            try {
                int id = root.getResources().getIdentifier(idName, "id", TARGET_PKG);
                if (id == 0) {
                    continue;
                }
                View v = root.findViewById(id);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setTextIsSelectable(true);
                    tv.setOnTouchListener(null); // 清掉 TextSelectHandler 拦截
                    try {
                        tv.setCustomSelectionActionModeCallback(null);
                    } catch (Throwable ignored) {
                    }
                    log(Log.INFO, TAG, "✔ 已开启标准文本选择: " + idName);
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "设置文本选择失败 (" + idName + "): " + t);
            }
        }
    }

    /**
     * 修复「拖动选择只能拉一行」：正文 TextView 在滚动容器（NestedScrollView）里，
     * 拖动选择手柄时触摸被滚动容器拦截（被当成页面滚动），选择无法跨行扩展。
     * 这里 hook 滚动容器的 onInterceptTouchEvent：当子树内有处于选择模式的
     * 可选择 TextView（hasSelection）时放行触摸（返回 false），让选择手柄拖动直达
     * TextView；平时无选择时行为不变，滚动正常。
     */
    private void hookScrollIntercept(ClassLoader cl) {
        String[] classes = {
                "androidx.core.widget.NestedScrollView", // binding 根（正文所在）
                "android.widget.NestedScrollView",
        };
        for (String name : classes) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                Method m = null;
                for (Method mm : c.getDeclaredMethods()) {
                    if ("onInterceptTouchEvent".equals(mm.getName())
                            && mm.getParameterCount() == 1
                            && mm.getParameterTypes()[0] == android.view.MotionEvent.class) {
                        m = mm;
                        break;
                    }
                }
                if (m == null) {
                    continue;
                }
                hook(m).intercept(chain -> {
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof ViewGroup && hasSelectingTextView((ViewGroup) self)) {
                            return false; // 文本选择激活时放行触摸给 TextView
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "✔ 滚动容器选择放行 Hook 已安装: " + name);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 子树中是否存在处于文本选择模式（有选中范围）的可选择 TextView */
    private boolean hasSelectingTextView(ViewGroup root) {
        if (root == null) {
            return false;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                try {
                    if (tv.isTextSelectable() && tv.hasSelection()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            } else if (child instanceof ViewGroup) {
                if (hasSelectingTextView((ViewGroup) child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
