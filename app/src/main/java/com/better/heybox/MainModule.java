package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

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

/**
 * 模块入口（libxposed Modern API 102）：只负责模块生命周期与 Hook 安装编排。
 * 各 Hook 职责：通用、广告过滤、设置入口+内嵌面板、底部导航、推广贴、文本选择、图片分享
 */
public class MainModule extends XposedModule {

    public static final String TAG = "BetterHeybox";

    private com.better.heybox.hooks.DailyTaskHook dailyTaskHook;

    public static final String TARGET_PKG = "com.max.xiaoheihe";

    public static final Set<String> SUPPORTED_HEYBOX_VERSIONS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    "1.3.393",
                    "1.3.394",
                    "1.3.395"
            )));

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Checkpoint.mark("onModuleLoaded: %s", param.getProcessName());
        Checkpoint.mark("framework: %s (%s) API %d", getFrameworkName(), getFrameworkVersion(), getApiVersion());
        logd(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName());
        logd(Log.INFO, TAG, "framework: " + getFrameworkName()
                + " (" + getFrameworkVersion() + ") API " + getApiVersion());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        logd(Log.INFO, TAG, "允许热重载");
        return true;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        Checkpoint.mark("onPackageReady: %s (target=%b)", packageName, TARGET_PKG.equals(packageName));
        logd(Log.INFO, TAG, "onPackageReady: " + packageName);

        if (TARGET_PKG.equals(packageName)) {
            logd(Log.INFO, TAG, ">>> 命中小黑盒，安装 Hook");
            deferInstallForDowngradeCheck(param);
        }
    }

    private void deferInstallForDowngradeCheck(PackageReadyParam param) {
        try {
            Class<?> appCls = Class.forName("android.app.Application", false, param.getClassLoader());
            java.lang.reflect.Method onCreate = appCls.getDeclaredMethod("onCreate");
            java.util.concurrent.atomic.AtomicBoolean decided =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            hook(onCreate).intercept(chain -> {
                chain.proceed();
                if (decided.compareAndSet(false, true)) {
                    activateIfNotDowngraded(param, chain.getThisObject());
                }
                return null;
            });
        } catch (Throwable t) {
            logd(Log.WARN, TAG, "检查决策点 Hook 失败", t);
            installHooks(param);
        }
    }
    private void activateIfNotDowngraded(PackageReadyParam param, Object app) {
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
            installHooks(param);
            return;
        }
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
        installHooks(param);
    }

    private long ownVersionCode() {
        try {
            android.content.pm.ApplicationInfo info = getModuleApplicationInfo();
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
    private void installHooks(PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();
        LiquidGlassHookBridge.setModule(this);
        Checkpoint.mark(">>> 开始安装 Hook");
        long t0 = SystemClock.elapsedRealtime();

        HeyboxTargets.init(cl, App.resolveAppContext());
        Checkpoint.mark("目标解析: %s", HeyboxTargets.report().replace('\n', ' '));

        PostFilterHook postFilter = new PostFilterHook(this);
        installHook("通用", new GeneralHook(this)::install, cl);
        installHook("广告过滤", new AdFilterHook(this)::install, cl);
        installHook("设置入口", new SettingsEntryHook(this)::install, cl);
        installHook("底部导航", new BottomTabHook(this)::install, cl);
        installHook("液态玻璃底栏", new LiquidGlassBottomBarHook(this)::install, cl);
        installHook("推广贴", new PromotePostHook(this)::install, cl);
        installHook("首页广告横幅", new FeedBannerHook(this)::install, cl);
        installHook("发帖过滤", postFilter::install, cl);
        installHook("失效收藏清理", new FavourAutoCleanHook(this)::install, cl);
        installHook("单列信息流", new SingleColumnFeedHook(this)::install, cl);
        installHook("搜索页精简", new SearchPageCleanHook(this)::install, cl);
        installHook("游戏库精简", new GameLibraryCleanHook(this)::install, cl);
        installHook("文本选择", new TextSelectHook(this)::install, cl);
        installHook("评论自由复制", new CommentCopyHook(this)::install, cl);
        installHook("评论过滤", new CommentFilterHook(this)::install, cl);
        installHook("图片分享", new ImageShareHook(this)::install, cl);
        installHook("分享链接净化", new ShareLinkPurifyHook(this)::install, cl);
        installHook("浏览器重定向", new BrowserRedirectHook(this)::install, cl);
        installHook("视频下载", new VideoDownloadHook(this)::install, cl);
        installHook("网页 DevTools", new WebViewDevToolsHook(this)::install, cl);
        installHook("目标提示", new TargetHintHook(this)::install, cl);
        installHook("每日任务", ignored -> {
            dailyTaskHook = new DailyTaskHook(this);
            dailyTaskHook.install(ignored);
        }, cl);
        installHook("动态推送", new WatchHook(this)::install, cl);

        Checkpoint.mark(">>> Hook 安装完成，总耗时 %d ms", SystemClock.elapsedRealtime() - t0);
        logd(Log.INFO, TAG, "Hook 安装流程结束");
        stashRuntimeStatus();
    }
    private interface HookInstaller {
        void install(ClassLoader cl);
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
            logd(Log.WARN, TAG, "运行状态检查点写入失败", t);
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

    public void logd(int level, String tag, String msg) {
        if (!Logs.shouldLog(level)) {
            return;
        }
        try {
            boolean logEnabled = isEnabled(App.KEY_LOG, false);
            LogRecorder.setEnabled(logEnabled);
            LogRecorder.setVerbose(isEnabled(App.KEY_VERBOSE_LOG, false));
            if (logEnabled) {
                LogRecorder.record(level, tag, msg);
            }
        } catch (Throwable ignored) {
        }
        log(level, tag, msg);
    }
    public void logd(int level, String tag, String msg, Throwable tr) {
        if (!Logs.shouldLog(level)) {
            return;
        }
        try {
            boolean logEnabled = isEnabled(App.KEY_LOG, false);
            LogRecorder.setEnabled(logEnabled);
            LogRecorder.setVerbose(isEnabled(App.KEY_VERBOSE_LOG, false));
            if (logEnabled) {
                LogRecorder.record(level, tag, msg, tr);
            }
        } catch (Throwable ignored) {
        }
        log(level, tag, msg, tr);
    }

    public int dp(Context context, float value) {
        return ThemeUtils.dp(context, value);
    }

    public void clearDailyTaskAndRetry(android.app.Activity activity) {
        if (dailyTaskHook != null) {
            dailyTaskHook.clearTodayAndRetry(activity);
        }
    }
}
