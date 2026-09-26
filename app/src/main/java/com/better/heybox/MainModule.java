package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.highcapable.yukihookapi.hook.param.PackageParam;

import com.better.heybox.yuki.YukiChain;
import com.better.heybox.yuki.YukiHookBridge;
import com.better.heybox.hooks.AdFilterHook;
import com.better.heybox.hooks.BottomTabHook;
import com.better.heybox.hooks.BrowserRedirectHook;
import com.better.heybox.hooks.CommentCopyHook;
import com.better.heybox.hooks.CommentFilterHook;
import com.better.heybox.hooks.LiquidGlassBottomBarHook;
import com.better.heybox.hooks.DailyTaskHook;
import com.better.heybox.hooks.FavourAutoCleanHook;
import com.better.heybox.hooks.FeedBannerHook;
import com.better.heybox.hooks.GameLibraryCleanHook;
import com.better.heybox.hooks.GeneralHook;
import com.better.heybox.hooks.ImageShareHook;
import com.better.heybox.hooks.MessageRedDotHook;
import com.better.heybox.hooks.PromotePostHook;
import com.better.heybox.hooks.PostFilterHook;
import com.better.heybox.hooks.WatchHook;
import com.better.heybox.hooks.SearchPageCleanHook;
import com.better.heybox.hooks.SettingsEntryHook;
import com.better.heybox.hooks.ShareLinkPurifyHook;
import com.better.heybox.hooks.SingleColumnFeedHook;
import com.better.heybox.hooks.TextSelectHook;
import com.better.heybox.hooks.VideoDownloadHook;
import com.better.heybox.hooks.TargetHintHook;
import com.better.heybox.hooks.WebViewDevToolsHook;
import com.better.heybox.liquidglass.LiquidGlassHookBridge;
import com.better.heybox.liquidglass.LiquidGlassInstaller;

public final class MainModule {

    public static final String TAG = "BetterHeybox";

    private com.better.heybox.hooks.DailyTaskHook dailyTaskHook;

    private static volatile MainModule sInstance;

    private volatile ClassLoader targetClassLoader;
    private final java.util.List<HookSpec> hookSpecs =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingHookCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private SharedPreferences.OnSharedPreferenceChangeListener settingsListener;

    private final PackageParam param;
    private final YukiHookBridge bridge;

    private MainModule(PackageParam param) {
        this.param = param;
        this.bridge = new YukiHookBridge(param);
        this.targetClassLoader = param.getHostClassLoader();
    }

    public static void attach(PackageParam param) {
        MainModule module = new MainModule(param);
        sInstance = module;
        module.logd(Log.INFO, TAG, ">>> 命中小黑盒，安装 Hook");
        module.deferInstallForDowngradeCheck();
    }

    public static MainModule get() {
        return sInstance;
    }

    public static final String TARGET_PKG = "com.max.xiaoheihe";

    public static final Set<String> SUPPORTED_HEYBOX_VERSIONS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    "1.3.393",
                    "1.3.394",
                    "1.3.395",
                    "1.3.396"
            )));

    public YukiHookBridge.HookHandle hook(Member member) {
        return bridge.hook(member);
    }

    public SharedPreferences getRemotePreferences(String group) {
        return bridge.remotePreferences(group);
    }

    public ApplicationInfo getModuleApplicationInfo() {
        return param.getModule().getAppInfo();
    }

    private void log(int level, String tag, String msg) {
        bridge.frameworkLog(level, tag, msg, null);
    }

    private void log(int level, String tag, String msg, Throwable tr) {
        bridge.frameworkLog(level, tag, msg, tr);
    }

    private void deferInstallForDowngradeCheck() {
        try {
            Class<?> appCls = Class.forName("android.app.Application", false, targetClassLoader);
            Method onCreate = appCls.getDeclaredMethod("onCreate");
            AtomicBoolean decided = new AtomicBoolean(false);
            hook(onCreate).intercept(chain -> {
                chain.proceed();
                if (decided.compareAndSet(false, true)) {
                    activateIfNotDowngraded(chain.getThisObject());
                }
                return null;
            });
        } catch (Throwable t) {
            logd(Log.WARN, TAG, "检查决策点 Hook 失败", t);
            installHooks();
        }
    }
    private void activateIfNotDowngraded(Object app) {
        long own = ownVersionCode();
        long floor = -1;
        try {
            Context appContext = app instanceof Context ? (Context) app : null;
            if (appContext != null) {
                HeyboxPrefs.init(appContext);
            }
        } catch (Throwable ignored) {
        }
        if (!BuildFlags.DEBUG) {
            try {
                if (HeyboxPrefs.getBoolean(App.KEY_DEBUG_NO_DOWNGRADE, false)) {
                    HeyboxPrefs.setBoolean(App.KEY_DEBUG_NO_DOWNGRADE, false);
                }
            } catch (Throwable ignored) {
            }
        }
        if (BuildFlags.DEBUG && isEnabled(App.KEY_DEBUG_NO_DOWNGRADE, false)) {
            try {
                HeyboxPrefs.setString(App.KEY_MODULE_VERSION_FLOOR, "0");
            } catch (Throwable ignored) {
            }
            Checkpoint.mark("调试开关开启：已清除模块版本降级限制");
            logd(Log.WARN, TAG, "调试开关开启：已清除模块版本降级限制，按常规激活");
            installHooks();
            return;
        }
        boolean moduleUpdated = false;
        try {
            Context appContext = app instanceof Context ? (Context) app : null;
            if (appContext != null) {
                String stored = HeyboxPrefs.getString(App.KEY_MODULE_VERSION_FLOOR, null);
                if (stored != null && !stored.trim().isEmpty()) {
                    floor = Long.parseLong(stored.trim());
                }
                if (own > 0) {
                    long next = Math.max(floor, own);
                    HeyboxPrefs.setString(App.KEY_MODULE_VERSION_FLOOR, String.valueOf(next));
                    if (next != floor) {
                        moduleUpdated = true;
                        logd(Log.INFO, TAG, "版本下限: " + next);
                    }
                }
            }
        } catch (Throwable t) {
            logd(Log.WARN, TAG, "检查读取失败，常规处理", t);
            floor = -1;
        }
        boolean downgraded = floor > 0 && own > 0 && own < floor && !BuildFlags.DEBUG;
        if (downgraded) {
            GeneralHook.notifyDowngraded(app);
            Checkpoint.mark("检测到模块过时: own=%d floor=%d，已停用", own, floor);
            logd(Log.WARN, TAG, "检测到模块过时 own=" + own + " < floor=" + floor + "，拒绝激活");
            return;
        }
        installHooks();
        if (moduleUpdated) {
            GeneralHook.notifyModuleUpdated(app);
            Checkpoint.mark("检测到模块更新: floor=%d -> %d，提示重启", floor, own);
        }
    }

    private long ownVersionCode() {
        try {
            ApplicationInfo info = getModuleApplicationInfo();
            if (info == null) {
                return -1L;
            }
            android.content.pm.PackageInfo pkg = getPackageArchiveInfoCompat(info.sourceDir);
            if (pkg == null) {
                return -1L;
            }
            return android.os.Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode;
        } catch (Throwable t) {
            logd(Log.WARN, TAG, "读取模块自身版本失败", t);
            return -1L;
        }
    }

    private android.content.pm.PackageInfo getPackageArchiveInfoCompat(String sourceDir) {
        Context ctx = App.resolveAppContext();
        if (ctx != null) {
            return ctx.getPackageManager().getPackageArchiveInfo(sourceDir, 0);
        }
        return null;
    }
    private void installHooks() {
        ClassLoader cl = targetClassLoader;
        if (cl == null) {
            cl = param.getHostClassLoader();
            targetClassLoader = cl;
        }
        CrashGuard.install();
        LiquidGlassHookBridge.setModule(this);
        Checkpoint.mark(">>> 开始安装 Hook");
        long t0 = SystemClock.elapsedRealtime();

        HeyboxTargets.init(cl, App.resolveAppContext());
        Checkpoint.mark("目标解析: %s", HeyboxTargets.report().replace('\n', ' '));

        PostFilterHook postFilter = new PostFilterHook(this);
        registerHook("通用", new GeneralHook(this)::install, cl);
        registerHook("广告过滤", new AdFilterHook(this)::install, cl,
                App.KEY_OPEN_SCREEN, App.KEY_FEED_AD, App.KEY_BUBBLE_AD, App.KEY_CORNER_AD);
        registerHook("设置入口", new SettingsEntryHook(this)::install, cl);
        registerHook("底部导航", new BottomTabHook(this)::install, cl,
                App.KEY_HIDE_TAB_HOME, App.KEY_HIDE_TAB_HOT, App.KEY_HIDE_TAB_GAME, App.KEY_HIDE_ADD);
        registerHook("液态玻璃底栏", new LiquidGlassBottomBarHook(this)::install, cl,
                App.KEY_LIQUID_GLASS, App.KEY_GLASS_IMMERSIVE);
        registerHook("推广贴", new PromotePostHook(this)::install, cl, App.KEY_PROMOTE_AD);
        registerHook("首页广告横幅", new FeedBannerHook(this)::install, cl, App.KEY_PROMOTE_AD);
        registerHook("发帖过滤", postFilter::install, cl,
                App.KEY_PROMOTE_AD, App.KEY_BLOCK_VIDEO_POST, App.KEY_POST_NO_LEVEL,
                App.KEY_POST_AI_ENABLED, App.KEY_FLOW_DIAGNOSE,
                App.KEY_POST_MIN_LEVEL, App.KEY_POST_KEYWORDS,
                App.KEY_POST_MIN_LIKE, App.KEY_POST_MIN_COMMENT, App.KEY_POST_MIN_FAVOUR);
        registerHook("失效收藏清理", new FavourAutoCleanHook(this)::install, cl,
                App.KEY_FAVOUR_AUTO_CLEAN);
        registerHook("单列信息流", new SingleColumnFeedHook(this)::install, cl,
                App.KEY_SINGLE_COLUMN_FEED);
        registerHook("搜索页精简", new SearchPageCleanHook(this)::install, cl,
                App.KEY_SEARCH_HIDE_BANNER, App.KEY_SEARCH_HIDE_DISCOVER, App.KEY_SEARCH_HIDE_HOT_RANK);
        registerHook("游戏库精简", new GameLibraryCleanHook(this)::install, cl,
                App.KEY_GAME_LIB_HIDE_BANNER, App.KEY_GAME_LIB_HIDE_MENU, App.KEY_GAME_LIB_HIDE_SECTIONS);
        registerHook("文本选择", new TextSelectHook(this)::install, cl,
                App.KEY_COPY_POST, App.KEY_CUSTOM_TEXT_SELECT);
        registerHook("评论自由复制", new CommentCopyHook(this)::install, cl,
                App.KEY_COMMENT_FREE_COPY, App.KEY_CUSTOM_TEXT_SELECT);
        registerHook("评论过滤", new CommentFilterHook(this)::install, cl,
                App.KEY_BLOCK_CY_COMMENT, App.KEY_HOST_HIDE_CY, App.KEY_BLOCK_GAME_RELAY);
        registerHook("图片分享", new ImageShareHook(this)::install, cl, App.KEY_SYSTEM_SHARE);
        registerHook("消息红点", new MessageRedDotHook(this)::install, cl,
                App.KEY_HIDE_MSG_DOT, App.KEY_HIDE_MSG_BADGE);
        registerHook("分享链接净化", new ShareLinkPurifyHook(this)::install, cl,
                App.KEY_PURIFY_SHARE_LINK);
        registerHook("浏览器重定向", new BrowserRedirectHook(this)::install, cl,
                App.KEY_BROWSER_REDIRECT, App.KEY_BROWSER_REDIRECT_KNOWN, App.KEY_WEB_LOG);
        registerHook("视频下载", new VideoDownloadHook(this)::install, cl, App.KEY_VIDEO_DOWNLOAD);
        registerHook("网页 DevTools", new WebViewDevToolsHook(this)::install, cl,
                App.KEY_WEBVIEW_DEVTOOLS);
        registerHook("目标提示", new TargetHintHook(this)::install, cl);
        registerHook("每日任务", ignored -> {
            dailyTaskHook = new DailyTaskHook(this);
            dailyTaskHook.install(ignored);
        }, cl, App.KEY_DAILY_TASK_ENABLED);
        registerHook("动态推送", new WatchHook(this)::install, cl, App.KEY_WATCH_ENABLED);

        watchSettingsChanges();
        Checkpoint.mark(">>> Hook 安装完成，总耗时 %d ms", SystemClock.elapsedRealtime() - t0);
        logd(Log.INFO, TAG, "Hook 安装流程结束（因开关关闭延迟安装 "
                + pendingHookCount.get() + " 个）");
        stashRuntimeStatus();
    }
    private interface HookInstaller {
        void install(ClassLoader cl);
    }

    private static final class HookSpec {
        final String label;
        final HookInstaller installer;
        final String[] keys;
        volatile boolean installed;

        HookSpec(String label, HookInstaller installer, String[] keys) {
            this.label = label;
            this.installer = installer;
            this.keys = keys;
        }

        boolean matches(String key) {
            for (String k : keys) {
                if (k.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void registerHook(String label, HookInstaller installer, ClassLoader cl, String... keys) {
        for (HookSpec existing : hookSpecs) {
            if (existing.label.equals(label)) {
                return;
            }
        }
        HookSpec spec = new HookSpec(label, installer, keys == null ? new String[0] : keys);
        hookSpecs.add(spec);
        if (installIfEnabled(spec, cl)) {
            return;
        }
        if (!spec.installed) {
            pendingHookCount.incrementAndGet();
            logv(TAG, "跳过安装（开关关闭）: " + label);
        }
    }

    private synchronized boolean installIfEnabled(HookSpec spec, ClassLoader cl) {
        if (spec.installed || cl == null) {
            return false;
        }
        if (!isAnySwitchOn(spec.keys)) {
            return false;
        }
        spec.installed = true;
        installHook(spec.label, spec.installer, cl);
        return true;
    }

    private boolean isAnySwitchOn(String[] keys) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            Boolean def = App.BOOLEAN_DEFAULTS.get(key);
            if (def != null) {
                if (isEnabled(key, def)) {
                    return true;
                }
            } else if (isConfiguredString(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isConfiguredString(String key) {
        String value = getString(key, "");
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty() && !"0".equals(trimmed);
    }

    public void onSettingChanged(String key) {
        if (key == null || pendingHookCount.get() == 0) {
            return;
        }
        ClassLoader cl = targetClassLoader;
        if (cl == null) {
            return;
        }
        for (HookSpec spec : hookSpecs) {
            if (spec.installed || !spec.matches(key)) {
                continue;
            }
            if (installIfEnabled(spec, cl)) {
                pendingHookCount.decrementAndGet();
            }
        }
    }

    public void forceInstallHook(String key) {
        ClassLoader cl = targetClassLoader;
        if (cl == null || key == null) {
            return;
        }
        for (HookSpec spec : hookSpecs) {
            if (spec.installed || !spec.matches(key)) {
                continue;
            }
            boolean installedNow = false;
            synchronized (this) {
                if (!spec.installed) {
                    spec.installed = true;
                    installHook(spec.label, spec.installer, cl);
                    installedNow = true;
                }
            }
            if (installedNow) {
                pendingHookCount.decrementAndGet();
            }
        }
    }

    private void watchSettingsChanges() {
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs == null) {
                return;
            }
            settingsListener = (p, key) -> {
                if (key == null || pendingHookCount.get() == 0) {
                    return;
                }
                try {
                    mainHandler().post(() -> onSettingChanged(key));
                } catch (Throwable ignored) {
                    onSettingChanged(key);
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(settingsListener);
        } catch (Throwable t) {
            logv(TAG, "设置变更监听注册失败（不影响面板内打开开关即时生效）: " + t);
        }
    }

    private static volatile android.os.Handler sMainHandler;

    private static android.os.Handler mainHandler() {
        android.os.Handler handler = sMainHandler;
        if (handler == null) {
            handler = new android.os.Handler(android.os.Looper.getMainLooper());
            sMainHandler = handler;
        }
        return handler;
    }

    private void installHook(String label, HookInstaller installer, ClassLoader cl) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            installer.install(cl);
            Checkpoint.mark("✔ %s Hook 安装完成 (%d ms)", label, SystemClock.elapsedRealtime() - t0);
        } catch (Throwable t) {
            Checkpoint.mark("✘ %s Hook 安装失败: %s (%d ms)",
                    label, t, SystemClock.elapsedRealtime() - t0);
            logd(Log.ERROR, TAG, "✘ " + label + " Hook 安装失败", t);
        }
    }
    private void stashRuntimeStatus() {
        if (!BuildFlags.DEBUG) {
            return;
        }
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs != null) {
                prefs.edit().putString(App.KEY_RUNTIME_STATUS, Checkpoint.dump()).commit();
                logd(Log.INFO, TAG, "运行状态检查点已写入 RemotePreferences");
            }
        } catch (Throwable t) {
            logv(TAG, "运行状态检查点写入失败（框架只读）");
        }
    }
    public boolean isEnabled(String key, boolean def) {
        if (HeyboxPrefs.contains(key)) {
            return HeyboxPrefs.getBoolean(key, def);
        }
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs != null && prefs.contains(key)) {
                return prefs.getBoolean(key, def);
            }
        } catch (Throwable t) {
        }
        return def;
    }
    public static String getHeyboxTabLabel(Context context, String resName, String def) {
        try {
            android.content.res.Resources res = null;
            int id = 0;
            try {
                res = context.getResources();
                id = res.getIdentifier(resName, "string", TARGET_PKG);
            } catch (Throwable ignored) {
            }
            if (id == 0) {
                try {
                    res = context.getPackageManager().getResourcesForApplication(TARGET_PKG);
                    id = res.getIdentifier(resName, "string", TARGET_PKG);
                } catch (Throwable ignored) {
                }
            }
            if (id != 0 && res != null) {
                return res.getString(id);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }
    public String getString(String key, String def) {
        if (HeyboxPrefs.contains(key)) {
            return HeyboxPrefs.getString(key, def);
        }
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs != null && prefs.contains(key)) {
                return prefs.getString(key, def);
            }
        } catch (Throwable t) {
        }
        return def;
    }

    private static final long LOG_SWITCH_TTL_MS = 1_000L;

    private volatile boolean logSwitchEnabled;
    private volatile boolean logSwitchVerbose;
    private volatile long logSwitchAt;

    public void logd(int level, String tag, String msg) {
        boolean verbose = logSwitchVerbose();
        if (!Logs.shouldLog(level) && !verbose) {
            return;
        }
        try {
            boolean enabled = logSwitchEnabled();
            LogRecorder.setEnabled(enabled);
            LogRecorder.setVerbose(verbose);
            if (enabled) {
                LogRecorder.record(level, tag, msg);
            }
        } catch (Throwable ignored) {
        }
        log(level, tag, msg);
    }
    public void logd(int level, String tag, String msg, Throwable tr) {
        boolean verbose = logSwitchVerbose();
        if (!Logs.shouldLog(level) && !verbose) {
            return;
        }
        try {
            boolean enabled = logSwitchEnabled();
            LogRecorder.setEnabled(enabled);
            LogRecorder.setVerbose(verbose);
            if (enabled) {
                LogRecorder.record(level, tag, msg, tr);
            }
        } catch (Throwable ignored) {
        }
        log(level, tag, msg, tr);
    }

    public void logv(String tag, String msg) {
        logd(Log.DEBUG, tag, msg);
    }

    public void invalidateLogSwitches() {
        logSwitchAt = 0L;
    }

    private boolean logSwitchEnabled() {
        refreshLogSwitches();
        return logSwitchEnabled;
    }

    private boolean logSwitchVerbose() {
        refreshLogSwitches();
        return logSwitchVerbose;
    }

    private void refreshLogSwitches() {
        long now = SystemClock.uptimeMillis();
        if (now - logSwitchAt < LOG_SWITCH_TTL_MS) {
            return;
        }
        logSwitchEnabled = isEnabled(App.KEY_LOG, false);
        logSwitchVerbose = isEnabled(App.KEY_VERBOSE_LOG, false);
        logSwitchAt = now;
    }

    public int dp(Context context, float value) {
        return ThemeUtils.dp(context, value);
    }

    public void clearDailyTaskAndRetry(android.app.Activity activity) {
        forceInstallHook(App.KEY_DAILY_TASK_ENABLED);
        if (dailyTaskHook != null) {
            dailyTaskHook.clearTodayAndRetry(activity);
        }
    }
}
