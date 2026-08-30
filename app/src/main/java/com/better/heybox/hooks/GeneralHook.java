package com.better.heybox.hooks;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import com.better.heybox.App;
import com.better.heybox.ForegroundTracker;
import com.better.heybox.MainModule;
import com.better.heybox.LogRecorder;

/**
 * 通用功能：Heybox 版本前置检测提示 + 屏蔽更新入口。
 */
public final class GeneralHook {

    private final MainModule module;

    public GeneralHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookVersionNotice(cl);
        hookUpdateBlocking(cl);
        hookFakeNotification(cl);
    }

    /**
 * 伪装通知权限：Hook 框架 NotificationManager.areNotificationsEnabled() 恒返回 true（开关开启时）。
 * 小黑盒经 NotificationManagerCompat（混淆 m2）判断，compat 内部最终调用框架方法，故 hook 框架方法全局生效
 */
    private void hookFakeNotification(ClassLoader cl) {
        try {
            Class<?> nm = Class.forName("android.app.NotificationManager", false, cl);
            Method m = nm.getDeclaredMethod("areNotificationsEnabled");
            module.hook(m).intercept(chain -> {
                if (module.isEnabled(App.KEY_FAKE_NOTIFICATION, false)) {
                    return Boolean.TRUE;
                }
                return chain.proceed();
            });
            module.logd(Log.INFO, module.TAG, "✔ 伪装通知权限 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 伪装通知权限 Hook 失败", t);
        }
    }

    private static final AtomicBoolean VERSION_NOTICE_SHOWN = new AtomicBoolean(false);

    private void hookVersionNotice(ClassLoader cl) {
        try {
            Class<?> baseActivity = Class.forName(
                    "com.max.hbcommon.base.BaseActivity", false, cl);
            Method onResume = baseActivity.getDeclaredMethod("onResume");
            module.hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    Activity activity = (Activity) self;
                    // Debug 构建：每次应用打开到前台记录检查点（划到后台不记录）
                    ForegroundTracker.onActivityResumed(activity);
                    View decor = activity.getWindow().getDecorView();
                    decor.postDelayed(() -> showVersionNotice(activity, cl), 600L);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ Heybox 版本检测 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ Heybox 版本检测 Hook 失败", t);
        }
    }

    private void showVersionNotice(Activity activity, ClassLoader cl) {
        LogRecorder.setContext(activity);
        if (activity.isFinishing() || VERSION_NOTICE_SHOWN.get()) {
            return;
        }
        String version = "unknown";
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(MainModule.TARGET_PKG, 0);
            if (info.versionName != null) {
                version = info.versionName;
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "读取 Heybox 版本失败", t);
            return;
        }
        if (MainModule.SUPPORTED_HEYBOX_VERSIONS.contains(version)
                || !VERSION_NOTICE_SHOWN.compareAndSet(false, true)) {
            return;
        }

        String message = "BetterHeybox 支持 Heybox " + String.join(" / ", MainModule.SUPPORTED_HEYBOX_VERSIONS)
                + "，当前检测到 " + version;
        try {
            Class<?> toastUtil = Class.forName("com.max.hbutils.utils.f", false, cl);
            Method showBottomHint = toastUtil.getDeclaredMethod("d", String.class);
            showBottomHint.invoke(null, message);
        } catch (Throwable t) {
            Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        }
        module.logd(Log.WARN, module.TAG, message);
    }

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
                module.logd(Log.WARN, module.TAG, "✘ 未找到 AppUpdateManager.P(Boolean)");
                return;
            }
            module.hook(updateEntry).intercept(chain -> {
                if (module.isEnabled(App.KEY_BLOCK_UPDATE, false)) {
                    module.logd(Log.INFO, module.TAG, "已屏蔽 Heybox 更新入口 AppUpdateManager.P()");
                    return chain.getThisObject();
                }
                return chain.proceed();
            });
            module.logd(Log.INFO, module.TAG, "✔ Heybox 更新屏蔽 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ Heybox 更新屏蔽 Hook 失败", t);
        }
    }
}
