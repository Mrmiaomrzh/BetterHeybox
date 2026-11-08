package com.better.heybox.hooks

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.better.heybox.App
import com.better.heybox.MainModule
import java.lang.reflect.Method

class ScanShortcutHook(
    private val module: MainModule,
    private val enabled: () -> Boolean
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedMethods = mutableSetOf<Method>()
    private var installed = false
    private var hostContext: Context? = null
    private var launcher: ComponentName? = null

    @Synchronized
    fun install(cl: ClassLoader) {
        if (installed) {
            refresh()
            return
        }
        val context = App.resolveAppContext() ?: error("扫一扫快捷方式：宿主 Context 尚未就绪")
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            try { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') } catch (_: Throwable) { null }
        }
        if (context.packageName != MainModule.TARGET_PKG || processName != context.packageName) {
            return
        }

        if (!enabled()) {
            try {
                context.getSystemService(ShortcutManager::class.java)
                    ?.removeDynamicShortcuts(listOf(SHORTCUT_ID))
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "扫一扫快捷方式清理失败", t)
            }
        }
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val candidates = pm.queryIntentActivities(launchIntent, 0)
        val preferred = pm.getLaunchIntentForPackage(context.packageName)?.component
        val info = candidates.firstOrNull {
            it.activityInfo?.let { activity ->
                ComponentName(activity.packageName, activity.name) == preferred
            } == true
        }?.activityInfo ?: candidates.firstOrNull()?.activityInfo
            ?: error("扫一扫快捷方式：未找到宿主桌面入口")
        val component = ComponentName(info.packageName, info.name)
        val activityName = info.targetActivity ?: info.name
        val activityClass = Class.forName(activityName, false, cl)
        require(Activity::class.java.isAssignableFrom(activityClass))

        hookLifecycle(activityClass, "onCreate", arrayOf(Bundle::class.java))
        hookLifecycle(activityClass, "onNewIntent", arrayOf(Intent::class.java))
        if (Build.VERSION.SDK_INT >= 35) {
            val caller = Class.forName("android.app.ComponentCaller")
            hookLifecycle(activityClass, "onNewIntent", arrayOf(Intent::class.java, caller))
        }
        hostContext = context.applicationContext ?: context
        launcher = component
        installed = true
        refresh()
    }

    private fun hookLifecycle(activityClass: Class<*>, name: String, params: Array<Class<*>>) {
        var owner: Class<*>? = activityClass
        var method: Method? = null
        while (owner != null && method == null) {
            method = try { owner.getDeclaredMethod(name, *params) } catch (_: NoSuchMethodException) { null }
            owner = owner.superclass
        }
        val target = method ?: throw NoSuchMethodException("$name on "+ activityClass.name)
        if (!hookedMethods.add(target)) return
        try {
            module.hook(target).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.instanceOrNull as? Activity
                if (activity != null && activityClass.isInstance(activity)) {
                    val intent = if (name == "onCreate") activity.intent else chain.args[0] as? Intent
                    dispatch(activity, intent)
                }
                result
            }
        } catch (t: Throwable) {
            hookedMethods.remove(target)
            throw t
        }
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refresh() }
            return
        }
        val context = hostContext ?: return
        val component = launcher ?: return
        try {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            if (!enabled()) {
                manager.removeDynamicShortcuts(listOf(SHORTCUT_ID))
                return
            }
            val intent = Intent(ACTION_SCAN).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val drawable = context.getDrawable(android.R.drawable.ic_menu_camera)
                ?: error("扫一扫快捷方式：无法加载图标")
            val fallbackSize = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: fallbackSize
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: fallbackSize
            val scale = minOf(1f, manager.iconMaxWidth.coerceAtLeast(1).toFloat() / width,
                manager.iconMaxHeight.coerceAtLeast(1).toFloat() / height)
            val bitmap = Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
                .setShortLabel("扫一扫")
                .setLongLabel("扫一扫")
                .setIcon(Icon.createWithBitmap(bitmap))
                .setActivity(component)
                .setIntent(intent)
                .build()
            if (!manager.addDynamicShortcuts(listOf(shortcut))) {
                module.logd(Log.WARN, MainModule.TAG, "扫一扫快捷方式注册失败：系统限流或数量已满")
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "扫一扫快捷方式刷新失败", t)
        }
    }

    private fun dispatch(activity: Activity, intent: Intent?) {
        if (intent?.action != ACTION_SCAN) return
        intent.action = null
        try {
            if (!enabled()) return
            activity.startActivity(Intent().setClassName(activity.packageName, SCAN_ACTIVITY))
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "扫一扫快捷方式启动失败", t)
        }
    }

    companion object {
        private const val SHORTCUT_ID = "better_heybox_scan_shortcut"
        private const val ACTION_SCAN = "com.better.heybox.action.SCAN_SHORTCUT"
        private const val SCAN_ACTIVITY = "com.max.common.common.qrcode.scan.ScanActivityV2"
    }
}
