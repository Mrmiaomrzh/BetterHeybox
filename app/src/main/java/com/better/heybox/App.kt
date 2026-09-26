package com.better.heybox

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        sApp = this
        LogRecorder.setContext(this)
        Checkpoint.mark("模块进程启动 (pid=%d)", Process.myPid())
        Logs.i(TAG, "App.onCreate: pid=" + Process.myPid())
        XposedServiceHelper.registerListener(this)
        Logs.i(TAG, "已注册 XposedService 监听器")
    }

    override fun onServiceBind(service: XposedService) {
        sService = service
        Checkpoint.mark("XposedService 已绑定: %s", describe(service))
        LogRecorder.setEnabled(readBoolean(KEY_LOG, false))
        LogRecorder.setVerbose(readBoolean(KEY_VERBOSE_LOG, false))
        LogRecorder.recordEvent("XposedService 已绑定: " + describe(service))
        val pending = getSharedPreferences(PENDING_PREFS, MODE_PRIVATE)
        Logs.i(
            TAG,
            "XposedService 已绑定: service=" + describe(service) +
                    ", pendingCount=" + pending.all.size
        )
        PreferenceReceiver.tryFlush(this, pending)
        notifyServiceBound()
    }

    override fun onServiceDied(service: XposedService) {
        Checkpoint.mark("XposedService 断开: %s", describe(service))
        Logs.w(TAG, "XposedService 已断开: service=" + describe(service) + ", current=" + describe(sService))
        sService = null
    }

    fun interface OnServiceBoundListener {
        fun onServiceBound()
    }

    companion object {

        private const val TAG = "BetterHeybox"

    const val PREFS_GROUP = "betterheybox"
    const val PENDING_PREFS = "betterheybox_pending"
    const val KEY_OPEN_SCREEN = "open_screen"
    const val KEY_FEED_AD = "feed_ad"
    const val KEY_BUBBLE_AD = "bubble_ad"
    const val KEY_CORNER_AD = "corner_ad"
    const val KEY_PROMOTE_AD = "promote_ad"
    const val KEY_HIDE_TAB_HOME = "hide_tab_home"
    const val KEY_HIDE_TAB_HOT = "hide_tab_hot"
    const val KEY_HIDE_TAB_GAME = "hide_tab_game"
    const val KEY_HIDE_ADD = "hide_add"
    const val KEY_COPY_POST = "copy_post"
    const val KEY_CUSTOM_TEXT_SELECT = "custom_text_select"
    const val KEY_COMMENT_FREE_COPY = "comment_free_copy"
    const val KEY_SEARCH_HIDE_BANNER = "search_hide_banner"
    const val KEY_SEARCH_HIDE_DISCOVER = "search_hide_discover"
    const val KEY_SEARCH_HIDE_HOT_RANK = "search_hide_hot_rank"
    const val KEY_GAME_LIB_HIDE_BANNER = "game_lib_hide_banner"
    const val KEY_GAME_LIB_HIDE_MENU = "game_lib_hide_menu"
    const val KEY_GAME_LIB_HIDE_SECTIONS = "game_lib_hide_sections"
    const val KEY_GAME_LIB_HIDE_TYPES = "game_lib_hide_types"
    const val KEY_GAME_LIB_HIDE_ENTRIES = "game_lib_hide_entries"
    const val KEY_GAME_LIB_HIDE_SECTION_NAMES = "game_lib_hide_section_names"
    const val KEY_BLOCK_UPDATE = "block_update"
    const val KEY_SYSTEM_SHARE = "system_share"
    const val KEY_DAILY_TASK_ENABLED = "daily_task_enabled"
    const val KEY_DAILY_TASK_PICTURE = "daily_task_picture"
    const val KEY_DAILY_TASK_NORMAL = "daily_task_normal"
    const val KEY_DAILY_TASK_CHANNEL = "daily_task_channel"
    const val KEY_DAILY_TASK_DONE_DATE = "daily_task_done_date"
    const val KEY_DAILY_TASK_RESET = "daily_task_reset"
    const val KEY_SHARE_CHANNEL = "daily_task_channel_type"
    const val KEY_DAILY_TASK_BACK_HOME = "daily_task_back_home"
    const val KEY_FAKE_NOTIFICATION = "fake_notification"
    const val KEY_VIDEO_DOWNLOAD = "video_download"
    const val KEY_VIDEO_DIR = "video_download_dir"
    const val KEY_VIDEO_TO_MP4 = "video_download_to_mp4"
    const val KEY_PURIFY_SHARE_LINK = "purify_share_link"
    const val KEY_BROWSER_REDIRECT = "browser_redirect"
    const val KEY_BROWSER_REDIRECT_KNOWN = "browser_redirect_known_hosts"
    const val KEY_BROWSER_REDIRECT_FORCE = "browser_redirect_force_domains"
    const val KEY_BROWSER_REDIRECT_BLOCK = "browser_redirect_block_domains"
    const val KEY_BROWSER_TARGET = "browser_redirect_package"
    const val KEY_WEB_LOG = "web_log"
    const val KEY_WEB_LOG_DATA = "web_log_data"
    const val KEY_LOG = "log"
    const val KEY_WEBVIEW_DEVTOOLS = "webview_devtools"
    const val KEY_LIQUID_GLASS = "liquid_glass"
    const val KEY_GLASS_IMMERSIVE = "glass_immersive"
    const val KEY_GLASS_ADAPTIVE = "glass_adaptive"
    const val KEY_GLASS_DARK_COLOR = "glass_dark_color"
    const val KEY_GLASS_DARK_ALPHA = "glass_dark_alpha"
    const val KEY_GLASS_LIGHT_COLOR = "glass_light_color"
    const val KEY_GLASS_LIGHT_ALPHA = "glass_light_alpha"
    const val KEY_GLASS_BAR_HEIGHT = "glass_bar_height"
    const val KEY_GLASS_BAR_OFFSET = "glass_bar_offset"
    const val KEY_GLASS_SIDE_MARGIN = "glass_side_margin"
    const val KEY_GLASS_FIT_TABS = "glass_fit_tabs"
    const val KEY_GLASS_BAR_WIDTH_MODE = "glass_bar_width_mode"
    const val KEY_GLASS_BAR_WIDTH_PCT = "glass_bar_width_pct"
    const val KEY_GLASS_TAB_WIDTH_PCT = "glass_tab_width_pct"
    const val KEY_GLASS_BAR_LAYOUT = "glass_bar_layout"
    const val KEY_GLASS_PROVIDER = "glass_provider"
    const val KEY_SINGLE_COLUMN_FEED = "single_column_feed"
    const val KEY_POST_MIN_LEVEL = "post_min_level"
    const val KEY_POST_MIN_LIKE = "post_min_like"
    const val KEY_POST_MIN_COMMENT = "post_min_comment"
    const val KEY_POST_MIN_FAVOUR = "post_min_favour"
    const val KEY_POST_NO_LEVEL = "post_filter_no_level"
    const val KEY_POST_KEYWORDS = "post_filter_keywords"
    const val KEY_POST_AI_ENABLED = "post_filter_ai_enabled"
    const val KEY_BLOCK_VIDEO_POST = "block_video_post"
    const val KEY_HOST_HIDE_CY = "host_hide_cy"
    const val KEY_BLOCK_CY_COMMENT = "block_cy_comment"
    const val KEY_COMMENT_KEYWORDS = "comment_filter_keywords"
    const val KEY_BLOCK_GAME_RELAY = "block_game_relay_comment"
    const val KEY_RELAY_IGNORE_EMOJI = "relay_ignore_emoji"
    const val KEY_FLOW_DIAGNOSE = "flow_diagnose"
    const val KEY_VERBOSE_LOG = "verbose_log"
    const val KEY_FAVOUR_AUTO_CLEAN = "favour_auto_clean"
    const val KEY_HIDE_MSG_DOT = "hide_msg_dot"
    const val KEY_HIDE_MSG_BADGE = "hide_msg_badge"
    const val KEY_MSG_BADGE_ENTRIES = "msg_badge_entries"
    const val KEY_MSG_FULL_HIDE_ENTRIES = "msg_full_hide_entries"
    const val KEY_AI_PROVIDER = "ai_provider"
    const val KEY_AI_BASE_URL = "ai_base_url"
    const val KEY_AI_MODEL = "ai_model"
    const val KEY_AI_TOKEN = "ai_token"
    const val KEY_AI_PROMPT = "ai_prompt"
    const val KEY_AI_MAX_TOKENS = "ai_max_tokens"
    const val KEY_GLASS_DARK_PRESET = "glass_dark_preset"
    const val KEY_GLASS_LIGHT_PRESET = "glass_light_preset"
    const val KEY_WEBVIEW_ENTRY_URL = "webview_entry_url"
    const val KEY_RUNTIME_STATUS = "runtime_status"
    const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
    const val KEY_MODULE_VERSION_FLOOR = "module_version_floor"
    const val KEY_DEBUG_NO_DOWNGRADE = "debug_no_downgrade"
    const val KEY_TARGET_HINT_VISIBLE = "target_hint_visible"
    const val KEY_WATCH_ENABLED = "watch_enabled"
    const val KEY_WATCH_USERS = "watch_users"
    const val KEY_WATCH_KEYWORDS = "watch_keywords"
    const val KEY_WATCH_BANNER = "watch_banner"
    const val KEY_WATCH_NOTIFY = "watch_notify"
    const val KEY_WATCH_INTERVAL_MIN = "watch_interval_min"
    const val KEY_WATCH_WINDOW_DAYS = "watch_window_days"
    const val KEY_WATCH_WINDOW_MIN = "watch_window_min"
    const val KEY_WATCH_TOPICS = "watch_topics"
    const val KEY_WATCH_RECENT_TOPICS = "watch_recent_topics"
    const val KEY_WATCH_TITLE_ONLY = "watch_title_only"
    const val KEY_WATCH_STREAM_FETCH = "watch_stream_fetch"
    const val KEY_WATCH_PUSH_ENABLED = "watch_push_enabled"
    const val KEY_WATCH_PUSH_DINGTALK = "watch_push_dingtalk"
    const val KEY_WATCH_PUSH_WXPUSHER = "watch_push_wxpusher"
    const val KEY_WATCH_PUSH_ONEBOT = "watch_push_onebot"
    const val KEY_WATCH_PUSH_CUSTOM = "watch_push_custom"
    const val KEY_WATCH_SEEN = "watch_seen"
    const val KEY_WATCH_LAST_CHECK = "watch_last_check"
    const val KEY_WATCH_BASELINED = "watch_baselined"

        @JvmField
        val BOOLEAN_DEFAULTS: MutableMap<String, Boolean> = buildBooleanDefaults()

        private fun buildBooleanDefaults(): MutableMap<String, Boolean> {
            val m = LinkedHashMap<String, Boolean>()
            m.put(KEY_OPEN_SCREEN, true);
            m.put(KEY_FEED_AD, true);
            m.put(KEY_BUBBLE_AD, true);
            m.put(KEY_CORNER_AD, true);
            m.put(KEY_PROMOTE_AD, true);
            m.put(KEY_HIDE_TAB_HOME, false);
            m.put(KEY_HIDE_TAB_HOT, false);
            m.put(KEY_HIDE_TAB_GAME, false);
            m.put(KEY_HIDE_ADD, false);
            m.put(KEY_COPY_POST, true);
            m.put(KEY_CUSTOM_TEXT_SELECT, false);
            m.put(KEY_COMMENT_FREE_COPY, true);
            m.put(KEY_SEARCH_HIDE_BANNER, false);
            m.put(KEY_SEARCH_HIDE_DISCOVER, false);
            m.put(KEY_SEARCH_HIDE_HOT_RANK, false);
            m.put(KEY_GAME_LIB_HIDE_BANNER, false);
            m.put(KEY_GAME_LIB_HIDE_MENU, false);
            m.put(KEY_GAME_LIB_HIDE_SECTIONS, false);
            m.put(KEY_SYSTEM_SHARE, true);
            m.put(KEY_VIDEO_DOWNLOAD, true);
            m.put(KEY_VIDEO_TO_MP4, true);
            m.put(KEY_PURIFY_SHARE_LINK, true);
            m.put(KEY_BROWSER_REDIRECT, false);
            m.put(KEY_BROWSER_REDIRECT_KNOWN, false);
            m.put(KEY_WEB_LOG, false);
            m.put(KEY_BLOCK_UPDATE, false);
            m.put(KEY_DAILY_TASK_ENABLED, false);
            m.put(KEY_DAILY_TASK_BACK_HOME, true);
            m.put(KEY_FAKE_NOTIFICATION, false);
            m.put(KEY_LOG, false);
            m.put(KEY_WEBVIEW_DEVTOOLS, false);
            m.put(KEY_LIQUID_GLASS, true);
            m.put(KEY_GLASS_IMMERSIVE, true);
            m.put(KEY_GLASS_ADAPTIVE, true);
            m.put(KEY_GLASS_FIT_TABS, false);
            m.put(KEY_SINGLE_COLUMN_FEED, false);
            m.put(KEY_POST_AI_ENABLED, false);
            m.put(KEY_POST_NO_LEVEL, false);
            m.put(KEY_BLOCK_VIDEO_POST, false);
            m.put(KEY_HOST_HIDE_CY, true);
            m.put(KEY_BLOCK_CY_COMMENT, false);
            m.put(KEY_BLOCK_GAME_RELAY, false);
            m.put(KEY_RELAY_IGNORE_EMOJI, false);
            m.put(KEY_FLOW_DIAGNOSE, false);
            m.put(KEY_VERBOSE_LOG, false);
            m.put(KEY_FAVOUR_AUTO_CLEAN, false);
            m.put(KEY_HIDE_MSG_DOT, false);
            m.put(KEY_HIDE_MSG_BADGE, false);
            m.put(KEY_DISCLAIMER_ACCEPTED, false);
            m.put(KEY_TARGET_HINT_VISIBLE, true);
            m.put(KEY_DEBUG_NO_DOWNGRADE, false);
            m.put(KEY_WATCH_ENABLED, false);
            m.put(KEY_WATCH_BANNER, true);
            m.put(KEY_WATCH_NOTIFY, true);
            m.put(KEY_WATCH_PUSH_ENABLED, false);
            m.put(KEY_WATCH_TITLE_ONLY, false);
            m.put(KEY_WATCH_STREAM_FETCH, false);
            return m
        }

        @Volatile private var sService: XposedService? = null

        @Volatile private var sApp: App? = null

        private val sBoundListeners = ArrayList<OnServiceBoundListener>()

        @JvmStatic
        fun getPrefs(): SharedPreferences? {
            val service = sService
            if (service == null) {
                Logs.w(TAG, "获取 RemotePreferences 失败: XposedService 未绑定")
                return null
            }
            return try {
                val prefs = service.getRemotePreferences(PREFS_GROUP)
                if (prefs == null) {
                    Logs.e(TAG, "获取 RemotePreferences 失败: service 返回 null, group=" + PREFS_GROUP)
                } else {
                    Logs.i(TAG, "获取 RemotePreferences 成功: group=" + PREFS_GROUP)
                }
                prefs
            } catch (t: Throwable) {
                Logs.e(TAG, "获取 RemotePreferences 异常: group=" + PREFS_GROUP, t)
                null
            }
        }

        @JvmStatic
        fun readBoolean(key: String, defaultValue: Boolean): Boolean {
            val app = sApp
            if (app != null) {
                val pending = app.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                if (pending.contains(key)) {
                    return pending.getBoolean(key, defaultValue)
                }
            }
            val remote = getPrefs()
            return if (remote != null) remote.getBoolean(key, defaultValue) else defaultValue
        }

        @JvmStatic
        fun readString(key: String, defaultValue: String?): String? {
            val app = sApp
            if (app != null) {
                val pending = app.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                if (pending.contains(key)) {
                    return pending.getString(key, defaultValue)
                }
            }
            val remote = getPrefs()
            return if (remote != null) remote.getString(key, defaultValue) else defaultValue
        }

        @JvmStatic
        fun writeString(key: String, value: String?) {
            val app = sApp
            val remote = getPrefs()
            if (remote != null) {
                remote.edit().putString(key, value).apply()
                LogRecorder.recordEvent("字符串已写入 RemotePreferences: key=" + key)
                return
            }
            if (app != null) {
                val pending = app.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                pending.edit().putString(key, value).commit()
                LogRecorder.recordEvent("字符串写入待提交缓存: key=" + key)
                PreferenceReceiver.tryFlush(app, pending)
            }
        }

        @JvmStatic
        fun writeBoolean(key: String, value: Boolean) {
            val app = sApp
            val remote = getPrefs()
            if (remote != null) {
                remote.edit().putBoolean(key, value).apply()
                LogRecorder.recordEvent("开关已写入 RemotePreferences: key=" + key + ", value=" + value)
                return
            }
            if (app != null) {
                val pending = app.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                pending.edit().putBoolean(key, value).commit()
                LogRecorder.recordEvent("服务未连接，开关写入待提交缓存: key=" + key + ", value=" + value)
                PreferenceReceiver.tryFlush(app, pending)
            }
        }

        @JvmStatic
        fun addOnServiceBoundListener(listener: OnServiceBoundListener) {
            synchronized(sBoundListeners) {
                if (!sBoundListeners.contains(listener)) {
                    sBoundListeners.add(listener)
                }
            }
        }

        private fun notifyServiceBound() {
            val snapshot: List<OnServiceBoundListener>
            synchronized(sBoundListeners) {
                snapshot = ArrayList(sBoundListeners)
            }
            if (snapshot.isEmpty()) {
                return
            }
            Handler(Looper.getMainLooper()).post {
                for (listener in snapshot) {
                    try {
                        listener.onServiceBound()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }

        private fun describe(service: XposedService?): String =
            if (service == null) "null"
            else service.javaClass.name + "@" + Integer.toHexString(System.identityHashCode(service))

        @JvmStatic
        fun getService(): XposedService? = sService

        @JvmStatic
        fun getAppContext(): Context? = sApp

        @JvmStatic
        fun resolveAppContext(): Context? {
            return try {
                val activityThread = Class.forName("android.app.ActivityThread")
                val app = activityThread.getMethod("currentApplication").invoke(null)
                app as? Context
            } catch (ignored: Throwable) {
                null
            }
        }

        @JvmStatic
        fun currentProcessName(): String {
            return try {
                val activityThread = Class.forName("android.app.ActivityThread")
                val name = activityThread.getMethod("currentProcessName").invoke(null)
                name?.toString() ?: Process.myPid().toString()
            } catch (ignored: Throwable) {
                Process.myPid().toString()
            }
        }
    }
}