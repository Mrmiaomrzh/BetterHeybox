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
import com.better.heybox.hooks.LiquidGlassBottomBarHook;
import com.better.heybox.hooks.DailyTaskHook;
import com.better.heybox.hooks.GeneralHook;
import com.better.heybox.hooks.ImageShareHook;
import com.better.heybox.hooks.PromotePostHook;
import com.better.heybox.hooks.PostFilterHook;
import com.better.heybox.hooks.SettingsEntryHook;
import com.better.heybox.hooks.ShareLinkPurifyHook;
import com.better.heybox.hooks.SingleColumnFeedHook;
import com.better.heybox.hooks.TextSelectHook;
import com.better.heybox.hooks.VideoDownloadHook;
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
                    "1.3.394"
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
            installHooks(param);
        }
    }
    private void installHooks(PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();
        LiquidGlassHookBridge.setModule(this);
        Checkpoint.mark(">>> 开始安装 Hook");
        long t0 = SystemClock.elapsedRealtime();

        // 先构造发帖过滤，供其他 hook 委托
        PostFilterHook postFilter = new PostFilterHook(this);
        installHook("通用", new GeneralHook(this)::install, cl);
        installHook("广告过滤", new AdFilterHook(this)::install, cl);
        installHook("设置入口", new SettingsEntryHook(this)::install, cl);
        installHook("底部导航", new BottomTabHook(this)::install, cl);
        // 玻璃提供方选择存在宿主本地配置，安装期（Application 未创建）读不到；
        // 由运行时挂载点（scheduleInstall / 玻璃长按入口）按选择门控
        installHook("液态玻璃底栏", new LiquidGlassBottomBarHook(this)::install, cl);
        installHook("推广贴", new PromotePostHook(this)::install, cl);
        installHook("发帖过滤", postFilter::install, cl);
        installHook("单列信息流", new SingleColumnFeedHook(this)::install, cl);
        installHook("文本选择", new TextSelectHook(this)::install, cl);
        installHook("图片分享", new ImageShareHook(this)::install, cl);
        installHook("分享链接净化", new ShareLinkPurifyHook(this)::install, cl);
        installHook("浏览器重定向", new BrowserRedirectHook(this)::install, cl);
        installHook("视频下载", new VideoDownloadHook(this)::install, cl);
        installHook("网页 DevTools", new WebViewDevToolsHook(this)::install, cl);
        installHook("每日任务", ignored -> {
            dailyTaskHook = new DailyTaskHook(this);
            dailyTaskHook.install(ignored);
        }, cl);

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
            // Release 构建下 Checkpoint 是空操作，必须单独留 error 日志供排查
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
