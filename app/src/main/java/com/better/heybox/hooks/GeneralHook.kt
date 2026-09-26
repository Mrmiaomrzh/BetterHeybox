package com.better.heybox.hooks

import android.app.Activity
import android.app.Application
import android.content.pm.PackageInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.ForegroundTracker
import com.better.heybox.LogRecorder
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class GeneralHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        hookVersionNotice(cl)
        hookUpdateBlocking(cl)
        hookFakeNotification(cl)
    }

    private fun hookFakeNotification(cl: ClassLoader) {
        try {
            val nm = Class.forName("android.app.NotificationManager", false, cl)
            val m = nm.getDeclaredMethod("areNotificationsEnabled")
            module.hook(m).intercept { chain ->
                if (module.isEnabled(App.KEY_FAKE_NOTIFICATION, false)) {
                    java.lang.Boolean.TRUE
                } else {
                    chain.proceed()
                }
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 伪装通知权限 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 伪装通知权限 Hook 失败", t)
        }
    }

    private fun hookVersionNotice(cl: ClassLoader) {
        try {
            val baseActivity = Class.forName("com.max.hbcommon.base.BaseActivity", false, cl)
            val onResume = baseActivity.getDeclaredMethod("onResume")
            module.hook(onResume).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.instanceOrNull as? Activity
                if (activity != null) {
                    ForegroundTracker.onActivityResumed(activity)
                    val decor: View = activity.window.decorView
                    decor.postDelayed({ showVersionNotice(activity, cl) }, 600L)
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ Heybox 版本检测 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ Heybox 版本检测 Hook 失败", t)
        }
    }

    private fun showVersionNotice(activity: Activity, cl: ClassLoader) {
        LogRecorder.setContext(activity)
        if (activity.isFinishing || VERSION_NOTICE_SHOWN.get()) {
            return
        }
        var version = "unknown"
        try {
            val info: PackageInfo =
                activity.packageManager.getPackageInfo(MainModule.TARGET_PKG, 0)
            val name = info.versionName
            if (name != null) {
                version = name
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "读取 Heybox 版本失败", t)
            return
        }
        if (MainModule.SUPPORTED_HEYBOX_VERSIONS.contains(version) ||
            !VERSION_NOTICE_SHOWN.compareAndSet(false, true)
        ) {
            return
        }

        val message = "BetterHeybox 支持 Heybox " +
                MainModule.SUPPORTED_HEYBOX_VERSIONS.joinToString(" / ") + "，当前检测到 $version"
        try {
            val toastUtil = Class.forName("com.max.hbutils.utils.f", false, cl)
            val showBottomHint = toastUtil.getDeclaredMethod("d", String::class.java)
            showBottomHint.invoke(null, message)
        } catch (t: Throwable) {
            Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
        }
        module.logd(Log.WARN, MainModule.TAG, message)
    }

    private fun hookUpdateBlocking(cl: ClassLoader) {
        try {
            val manager = Class.forName("com.max.xiaoheihe.utils.AppUpdateManager", false, cl)
            var updateEntry: Method? = null
            for (method in manager.declaredMethods) {
                if (method.name == "P" && method.parameterCount == 1 &&
                    method.parameterTypes[0] == java.lang.Boolean::class.java
                ) {
                    updateEntry = method
                    break
                }
            }
            if (updateEntry == null) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 未找到 AppUpdateManager.P(Boolean)")
                return
            }
            module.hook(updateEntry).intercept { chain ->
                if (module.isEnabled(App.KEY_BLOCK_UPDATE, false)) {
                    module.logd(Log.INFO, MainModule.TAG, "已屏蔽 Heybox 更新入口 AppUpdateManager.P()")
                    return@intercept chain.instanceOrNull
                }
                chain.proceed()
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ Heybox 更新屏蔽 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ Heybox 更新屏蔽 Hook 失败", t)
        }
    }

    companion object {
        private val VERSION_NOTICE_SHOWN = AtomicBoolean(false)
        private val DOWNGRADE_NOTICE_SHOWN = AtomicBoolean(false)
        private val UPDATE_NOTICE_SHOWN = AtomicBoolean(false)

        @JvmStatic
        fun notifyDowngraded(app: Any?) =
            notifyOnce(app, DOWNGRADE_NOTICE_SHOWN, "BetterHeybox 已停用：模块过时，请更新模块后重启小黑盒")

        @JvmStatic
        fun notifyModuleUpdated(app: Any?) =
            notifyOnce(app, UPDATE_NOTICE_SHOWN, "模块已更新，建议重启小黑盒以完整生效")

        private fun notifyOnce(app: Any?, guard: AtomicBoolean, message: String) {
            val application = app as? Application ?: return
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    if (!guard.compareAndSet(false, true)) {
                        return
                    }
                    try {
                        val toastUtil = Class.forName(
                            "com.max.hbutils.utils.f", false, activity.classLoader
                        )
                        val showBottomHint = toastUtil.getDeclaredMethod("d", String::class.java)
                        showBottomHint.invoke(null, message)
                    } catch (t: Throwable) {
                        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }
}
