package com.better.heybox

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.better.heybox.hooks.AdFilterHook
import com.better.heybox.hooks.BottomTabHook
import com.better.heybox.hooks.BrowserRedirectHook
import com.better.heybox.hooks.CommentCopyHook
import com.better.heybox.hooks.CommentFilterHook
import com.better.heybox.hooks.DailyTaskHook
import com.better.heybox.hooks.FavourAutoCleanHook
import com.better.heybox.hooks.FeedBannerHook
import com.better.heybox.hooks.GameLibraryCleanHook
import com.better.heybox.hooks.GeneralHook
import com.better.heybox.hooks.ImageShareHook
import com.better.heybox.hooks.LiquidGlassBottomBarHook
import com.better.heybox.hooks.MessageRedDotHook
import com.better.heybox.hooks.PostFilterHook
import com.better.heybox.hooks.PromotePostHook
import com.better.heybox.hooks.SearchPageCleanHook
import com.better.heybox.hooks.SettingsEntryHook
import com.better.heybox.hooks.ShareLinkPurifyHook
import com.better.heybox.hooks.SingleColumnFeedHook
import com.better.heybox.hooks.TargetHintHook
import com.better.heybox.hooks.TextSelectHook
import com.better.heybox.hooks.VideoDownloadHook
import com.better.heybox.hooks.WatchHook
import com.better.heybox.hooks.WebViewDevToolsHook
import com.better.heybox.liquidglass.LiquidGlassHookBridge
import com.highcapable.yukihookapi.hook.core.api.priority.YukiHookPriority
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookChain
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.lang.reflect.Member
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class MainModule private constructor(private val param: PackageParam) {

    private var dailyTaskHook: DailyTaskHook? = null

    @Volatile
    private var targetClassLoader: ClassLoader? = param.hostClassLoader

    private val hookSpecs: MutableList<HookSpec> = CopyOnWriteArrayList()

    private val pendingHookCount = AtomicInteger()

    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    fun hook(member: Member): HookHandle = HookHandle(member)

    inner class HookHandle(private val member: Member) {

        fun intercept(body: (HookChain) -> Any?) {
            with(param) {
                member.intercept(YukiHookPriority.DEFAULT) {
                    body(this)
                }
            }
        }
    }

    fun getRemotePreferences(group: String): SharedPreferences? = try {
        val prefs = param.preferences(group)
        prefs.javaClass.getMethod("getCurrent\$yukihook_core").invoke(prefs) as SharedPreferences
    } catch (t: Throwable) {
        null
    }

    fun getModuleApplicationInfo(): ApplicationInfo = param.module.appInfo

    /**
     * Unregisters the settings listener so the outgoing module instance is not
     * retained by the host preferences after a hot reload.
     */
    fun release() {
        val listener = settingsListener ?: return
        settingsListener = null
        try {
            getRemotePreferences(App.PREFS_GROUP)?.unregisterOnSharedPreferenceChangeListener(listener)
        } catch (t: Throwable) {
            logv(TAG, "设置变更监听注销失败: $t")
        }
    }


    private fun log(level: Int, tag: String, msg: String) {
        frameworkLog(level, tag, msg, null)
    }

    private fun log(level: Int, tag: String, msg: String, tr: Throwable) {
        frameworkLog(level, tag, msg, tr)
    }

    private fun frameworkLog(level: Int, tag: String, msg: String, tr: Throwable?) {
        when (level) {
            android.util.Log.DEBUG -> YLog.debug(msg, tr, tag, YLog.EnvType.BOTH)
            android.util.Log.INFO -> YLog.info(msg, tr, tag, YLog.EnvType.BOTH)
            android.util.Log.WARN -> YLog.warn(msg, tr, tag, YLog.EnvType.BOTH)
            else -> YLog.error(msg, tr, tag, YLog.EnvType.BOTH)
        }
    }

    private fun deferInstallForDowngradeCheck() {
        try {
            val cl = targetClassLoader
            val appCls = Class.forName("android.app.Application", false, cl)
            val onCreate = appCls.getDeclaredMethod("onCreate")
            val decided = AtomicBoolean(false)
            hook(onCreate).intercept { chain ->
                chain.proceed()
                if (decided.compareAndSet(false, true)) {
                    activateIfNotDowngraded(chain.instanceOrNull)
                }
                null
            }
        } catch (t: Throwable) {
            logd(Log.WARN, TAG, "检查决策点 Hook 失败", t)
            installHooks()
        }
    }

    private fun activateIfNotDowngraded(app: Any?) {
        val own = ownVersionCode()
        var floor = -1L
        try {
            val appContext = app as? Context
            if (appContext != null) {
                HeyboxPrefs.init(appContext)
            }
        } catch (ignored: Throwable) {
        }
        if (!BuildFlags.DEBUG) {
            try {
                if (HeyboxPrefs.getBoolean(App.KEY_DEBUG_NO_DOWNGRADE, false)) {
                    HeyboxPrefs.setBoolean(App.KEY_DEBUG_NO_DOWNGRADE, false)
                }
            } catch (ignored: Throwable) {
            }
        }
        if (BuildFlags.DEBUG && isEnabled(App.KEY_DEBUG_NO_DOWNGRADE, false)) {
            try {
                HeyboxPrefs.setString(App.KEY_MODULE_VERSION_FLOOR, "0")
            } catch (ignored: Throwable) {
            }
            Checkpoint.mark("调试开关开启：已清除模块版本降级限制")
            logd(Log.WARN, TAG, "调试开关开启：已清除模块版本降级限制，按常规激活")
            installHooks()
            return
        }
        var moduleUpdated = false
        try {
            val appContext = app as? Context
            if (appContext != null) {
                val stored = HeyboxPrefs.getString(App.KEY_MODULE_VERSION_FLOOR, null)
                if (!stored.isNullOrBlank()) {
                    floor = stored.trim().toLong()
                }
                if (own > 0) {
                    val next = max(floor, own)
                    HeyboxPrefs.setString(App.KEY_MODULE_VERSION_FLOOR, next.toString())
                    if (next != floor) {
                        moduleUpdated = true
                        logd(Log.INFO, TAG, "版本下限: $next")
                    }
                }
            }
        } catch (t: Throwable) {
            logd(Log.WARN, TAG, "检查读取失败，常规处理", t)
            floor = -1
        }
        val downgraded = floor > 0 && own > 0 && own < floor && !BuildFlags.DEBUG
        if (downgraded) {
            GeneralHook.notifyDowngraded(app)
            Checkpoint.mark("检测到模块过时: own=%d floor=%d，已停用", own, floor)
            logd(Log.WARN, TAG, "检测到模块过时 own=$own < floor=$floor，拒绝激活")
            return
        }
        installHooks()
        if (moduleUpdated) {
            GeneralHook.notifyModuleUpdated(app)
            Checkpoint.mark("检测到模块更新: floor=%d -> %d，提示重启", floor, own)
        }
    }

    private fun ownVersionCode(): Long {
        try {
            val info = getModuleApplicationInfo() ?: return -1L
            val pkg = getPackageArchiveInfoCompat(info.sourceDir) ?: return -1L
            return if (Build.VERSION.SDK_INT >= 28) pkg.getLongVersionCode() else pkg.versionCode.toLong()
        } catch (t: Throwable) {
            logd(Log.WARN, TAG, "读取模块自身版本失败", t)
            return -1L
        }
    }

    private fun getPackageArchiveInfoCompat(sourceDir: String): PackageInfo? {
        val ctx = App.resolveAppContext() ?: return null
        return ctx.packageManager.getPackageArchiveInfo(sourceDir, 0)
    }

    private fun installHooks() {
        var cl = targetClassLoader
        if (cl == null) {
            cl = param.hostClassLoader
            targetClassLoader = cl
        }
        CrashGuard.install()
        LiquidGlassHookBridge.setModule(this)
        Checkpoint.mark(">>> 开始安装 Hook")
        val t0 = SystemClock.elapsedRealtime()

        HeyboxTargets.init(cl, App.resolveAppContext())
        Checkpoint.mark("目标解析: %s", HeyboxTargets.report().replace('\n', ' '))

        val postFilter = PostFilterHook(this)
        registerHook("通用", GeneralHook(this)::install, cl)
        registerHook("广告过滤", AdFilterHook(this)::install, cl,
            App.KEY_OPEN_SCREEN, App.KEY_FEED_AD, App.KEY_BUBBLE_AD, App.KEY_CORNER_AD)
        registerHook("设置入口", SettingsEntryHook(this)::install, cl)
        registerHook("底部导航", BottomTabHook(this)::install, cl,
            App.KEY_HIDE_TAB_HOME, App.KEY_HIDE_TAB_HOT, App.KEY_HIDE_TAB_GAME, App.KEY_HIDE_ADD)
        registerHook("液态玻璃底栏", LiquidGlassBottomBarHook(this)::install, cl,
            App.KEY_LIQUID_GLASS, App.KEY_GLASS_IMMERSIVE)
        registerHook("推广贴", PromotePostHook(this)::install, cl, App.KEY_PROMOTE_AD)
        registerHook("首页广告横幅", FeedBannerHook(this)::install, cl, App.KEY_PROMOTE_AD)
        registerHook("发帖过滤", postFilter::install, cl,
            App.KEY_PROMOTE_AD, App.KEY_BLOCK_VIDEO_POST, App.KEY_POST_NO_LEVEL,
            App.KEY_POST_AI_ENABLED, App.KEY_FLOW_DIAGNOSE,
            App.KEY_POST_MIN_LEVEL, App.KEY_POST_KEYWORDS,
            App.KEY_POST_MIN_LIKE, App.KEY_POST_MIN_COMMENT, App.KEY_POST_MIN_FAVOUR)
        registerHook("失效收藏清理", FavourAutoCleanHook(this)::install, cl,
            App.KEY_FAVOUR_AUTO_CLEAN)
        registerHook("单列信息流", SingleColumnFeedHook(this)::install, cl,
            App.KEY_SINGLE_COLUMN_FEED)
        registerHook("搜索页精简", SearchPageCleanHook(this)::install, cl,
            App.KEY_SEARCH_HIDE_BANNER, App.KEY_SEARCH_HIDE_DISCOVER, App.KEY_SEARCH_HIDE_HOT_RANK)
        registerHook("游戏库精简", GameLibraryCleanHook(this)::install, cl,
            App.KEY_GAME_LIB_HIDE_BANNER, App.KEY_GAME_LIB_HIDE_MENU, App.KEY_GAME_LIB_HIDE_SECTIONS)
        registerHook("文本选择", TextSelectHook(this)::install, cl,
            App.KEY_COPY_POST, App.KEY_CUSTOM_TEXT_SELECT)
        registerHook("评论自由复制", CommentCopyHook(this)::install, cl,
            App.KEY_COMMENT_FREE_COPY, App.KEY_CUSTOM_TEXT_SELECT)
        registerHook("评论过滤", CommentFilterHook(this)::install, cl,
            App.KEY_BLOCK_CY_COMMENT, App.KEY_HOST_HIDE_CY, App.KEY_BLOCK_GAME_RELAY)
        registerHook("图片分享", ImageShareHook(this)::install, cl, App.KEY_SYSTEM_SHARE)
        registerHook("消息红点", MessageRedDotHook(this)::install, cl,
            App.KEY_HIDE_MSG_DOT, App.KEY_HIDE_MSG_BADGE)
        registerHook("分享链接净化", ShareLinkPurifyHook(this)::install, cl,
            App.KEY_PURIFY_SHARE_LINK)
        registerHook("浏览器重定向", BrowserRedirectHook(this)::install, cl,
            App.KEY_BROWSER_REDIRECT, App.KEY_BROWSER_REDIRECT_KNOWN, App.KEY_WEB_LOG)
        registerHook("视频下载", VideoDownloadHook(this)::install, cl, App.KEY_VIDEO_DOWNLOAD)
        registerHook("网页 DevTools", WebViewDevToolsHook(this)::install, cl,
            App.KEY_WEBVIEW_DEVTOOLS)
        registerHook("目标提示", TargetHintHook(this)::install, cl)
        registerHook("每日任务", HookInstaller { ignored ->
            dailyTaskHook = DailyTaskHook(this).also { it.install(ignored) }
        }, cl, App.KEY_DAILY_TASK_ENABLED)
        registerHook("动态推送", WatchHook(this)::install, cl, App.KEY_WATCH_ENABLED)

        watchSettingsChanges()
        Checkpoint.mark(">>> Hook 安装完成，总耗时 %d ms", SystemClock.elapsedRealtime() - t0)
        logd(Log.INFO, TAG, "Hook 安装流程结束（因开关关闭延迟安装 ${pendingHookCount.get()} 个）")
        stashRuntimeStatus()
    }

    fun interface HookInstaller {
        fun install(cl: ClassLoader)
    }

    private class HookSpec(
        val label: String,
        val installer: HookInstaller,
        val keys: Array<out String>
    ) {
        @Volatile
        var installed: Boolean = false

        fun matches(key: String): Boolean = keys.any { it == key }
    }

    private fun registerHook(
        label: String,
        installer: HookInstaller,
        cl: ClassLoader?,
        vararg keys: String
    ) {
        for (existing in hookSpecs) {
            if (existing.label == label) {
                return
            }
        }
        val spec = HookSpec(label, installer, keys.copyOf())
        hookSpecs.add(spec)
        if (installIfEnabled(spec, cl)) {
            return
        }
        if (!spec.installed) {
            pendingHookCount.incrementAndGet()
            logv(TAG, "跳过安装（开关关闭）: $label")
        }
    }

    @Synchronized
    private fun installIfEnabled(spec: HookSpec, cl: ClassLoader?): Boolean {
        if (spec.installed || cl == null) {
            return false
        }
        if (!isAnySwitchOn(spec.keys)) {
            return false
        }
        spec.installed = true
        installHook(spec.label, spec.installer, cl)
        return true
    }

    private fun isAnySwitchOn(keys: Array<out String>): Boolean {
        if (keys.isEmpty()) {
            return true
        }
        for (key in keys) {
            val def = App.BOOLEAN_DEFAULTS[key]
            if (def != null) {
                if (isEnabled(key, def)) {
                    return true
                }
            } else if (isConfiguredString(key)) {
                return true
            }
        }
        return false
    }

    private fun isConfiguredString(key: String): Boolean {
        val trimmed = getString(key, "")?.trim() ?: return false
        return trimmed.isNotEmpty() && trimmed != "0"
    }

    fun onSettingChanged(key: String?) {
        if (key == null || pendingHookCount.get() == 0) {
            return
        }
        val cl = targetClassLoader ?: return
        for (spec in hookSpecs) {
            if (spec.installed || !spec.matches(key)) {
                continue
            }
            if (installIfEnabled(spec, cl)) {
                pendingHookCount.decrementAndGet()
            }
        }
    }

    fun forceInstallHook(key: String?) {
        val cl = targetClassLoader
        if (cl == null || key == null) {
            return
        }
        for (spec in hookSpecs) {
            if (spec.installed || !spec.matches(key)) {
                continue
            }
            var installedNow = false
            synchronized(this) {
                if (!spec.installed) {
                    spec.installed = true
                    installHook(spec.label, spec.installer, cl)
                    installedNow = true
                }
            }
            if (installedNow) {
                pendingHookCount.decrementAndGet()
            }
        }
    }

    private fun watchSettingsChanges() {
        try {
            val prefs = getRemotePreferences(App.PREFS_GROUP) ?: return
            settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || pendingHookCount.get() == 0) {
                    return@OnSharedPreferenceChangeListener
                }
                try {
                    mainHandler().post { onSettingChanged(key) }
                } catch (ignored: Throwable) {
                    onSettingChanged(key)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        } catch (t: Throwable) {
            logv(TAG, "设置变更监听注册失败（不影响面板内打开开关即时生效）: $t")
        }
    }

    private fun installHook(label: String, installer: HookInstaller, cl: ClassLoader) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            installer.install(cl)
            Checkpoint.mark("✔ %s Hook 安装完成 (%d ms)", label, SystemClock.elapsedRealtime() - t0)
        } catch (t: Throwable) {
            Checkpoint.mark("✘ %s Hook 安装失败: %s (%d ms)",
                label, t, SystemClock.elapsedRealtime() - t0)
            logd(Log.ERROR, TAG, "✘ $label Hook 安装失败", t)
        }
    }

    private fun stashRuntimeStatus() {
        if (!BuildFlags.DEBUG) {
            return
        }
        try {
            val prefs = getRemotePreferences(App.PREFS_GROUP)
            if (prefs != null) {
                prefs.edit().putString(App.KEY_RUNTIME_STATUS, Checkpoint.dump()).commit()
                logd(Log.INFO, TAG, "运行状态检查点已写入 RemotePreferences")
            }
        } catch (t: Throwable) {
            logv(TAG, "运行状态检查点写入失败（框架只读）")
        }
    }

    fun isEnabled(key: String, def: Boolean): Boolean {
        if (HeyboxPrefs.contains(key)) {
            return HeyboxPrefs.getBoolean(key, def)
        }
        try {
            val prefs = getRemotePreferences(App.PREFS_GROUP)
            if (prefs != null && prefs.contains(key)) {
                return prefs.getBoolean(key, def)
            }
        } catch (t: Throwable) {
        }
        return def
    }

    fun getString(key: String, def: String?): String? {
        if (HeyboxPrefs.contains(key)) {
            return HeyboxPrefs.getString(key, def)
        }
        try {
            val prefs = getRemotePreferences(App.PREFS_GROUP)
            if (prefs != null && prefs.contains(key)) {
                return prefs.getString(key, def)
            }
        } catch (t: Throwable) {
        }
        return def
    }


    @Volatile private var logSwitchEnabled = false
    @Volatile private var logSwitchVerbose = false
    @Volatile private var logSwitchAt = 0L

    fun logd(level: Int, tag: String, msg: String) {
        val verbose = logSwitchVerbose()
        if (!Logs.shouldLog(level) && !verbose) {
            return
        }
        try {
            val enabled = logSwitchEnabled()
            LogRecorder.setEnabled(enabled)
            LogRecorder.setVerbose(verbose)
            if (enabled) {
                LogRecorder.record(level, tag, msg)
            }
        } catch (ignored: Throwable) {
        }
        log(level, tag, msg)
    }

    fun logd(level: Int, tag: String, msg: String, tr: Throwable) {
        val verbose = logSwitchVerbose()
        if (!Logs.shouldLog(level) && !verbose) {
            return
        }
        try {
            val enabled = logSwitchEnabled()
            LogRecorder.setEnabled(enabled)
            LogRecorder.setVerbose(verbose)
            if (enabled) {
                LogRecorder.record(level, tag, msg, tr)
            }
        } catch (ignored: Throwable) {
        }
        log(level, tag, msg, tr)
    }

    fun logv(tag: String, msg: String) {
        logd(Log.DEBUG, tag, msg)
    }

    fun invalidateLogSwitches() {
        logSwitchAt = 0L
    }

    private fun logSwitchEnabled(): Boolean {
        refreshLogSwitches()
        return logSwitchEnabled
    }

    private fun logSwitchVerbose(): Boolean {
        refreshLogSwitches()
        return logSwitchVerbose
    }

    private fun refreshLogSwitches() {
        val now = SystemClock.uptimeMillis()
        if (now - logSwitchAt < LOG_SWITCH_TTL_MS) {
            return
        }
        logSwitchEnabled = isEnabled(App.KEY_LOG, false)
        logSwitchVerbose = isEnabled(App.KEY_VERBOSE_LOG, false)
        logSwitchAt = now
    }

    fun dp(context: Context?, value: Float): Int = ThemeUtils.dp(context, value)

    fun clearDailyTaskAndRetry(activity: Activity) {
        forceInstallHook(App.KEY_DAILY_TASK_ENABLED)
        dailyTaskHook?.clearTodayAndRetry(activity)
    }

    companion object {
        private const val LOG_SWITCH_TTL_MS = 1_000L

        @JvmField
        val TAG = "BetterHeybox"

        const val TARGET_PKG = "com.max.xiaoheihe"

        @JvmField
        val SUPPORTED_HEYBOX_VERSIONS: Set<String> =
            java.util.Collections.unmodifiableSet(
                java.util.LinkedHashSet(
                    java.util.Arrays.asList("1.3.393", "1.3.394", "1.3.395", "1.3.396")
                )
            )

        @Volatile
        private var sInstance: MainModule? = null

        @Volatile
        private var sMainHandler: Handler? = null

        private fun mainHandler(): Handler {
            return sMainHandler ?: Handler(Looper.getMainLooper()).also { sMainHandler = it }
        }

        @JvmStatic
        fun attach(param: PackageParam) {
            val module = MainModule(param)
            sInstance = module
            module.logd(Log.INFO, TAG, ">>> 命中小黑盒，安装 Hook")
            module.deferInstallForDowngradeCheck()
        }

        @JvmStatic
        fun get(): MainModule? = sInstance

        /** Releases the current module instance before a hot reload. */
        @JvmStatic
        fun releaseCurrent() {
            val instance = sInstance ?: return
            sInstance = null
            try {
                instance.release()
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun getHeyboxTabLabel(context: Context?, resName: String?, def: String?): String? {
            try {
                var res: Resources? = null
                var id = 0
                try {
                    res = context?.resources
                    id = res?.getIdentifier(resName, "string", TARGET_PKG) ?: 0
                } catch (ignored: Throwable) {
                }
                if (id == 0) {
                    try {
                        res = context?.packageManager?.getResourcesForApplication(TARGET_PKG)
                        id = res?.getIdentifier(resName, "string", TARGET_PKG) ?: 0
                    } catch (ignored: Throwable) {
                    }
                }
                if (id != 0 && res != null) {
                    return res.getString(id)
                }
            } catch (ignored: Throwable) {
            }
            return def
        }
    }
}
