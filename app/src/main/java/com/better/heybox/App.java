package com.better.heybox;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "BetterHeybox";

    public static final String PREFS_GROUP = "betterheybox";

    public static final String PENDING_PREFS = "betterheybox_pending";

    public static final String KEY_OPEN_SCREEN = "open_screen";
    public static final String KEY_FEED_AD = "feed_ad";
    public static final String KEY_BUBBLE_AD = "bubble_ad";
    public static final String KEY_CORNER_AD = "corner_ad";
    public static final String KEY_PROMOTE_AD = "promote_ad";
    public static final String KEY_HIDE_TAB_HOME = "hide_tab_home";
    public static final String KEY_HIDE_TAB_HOT = "hide_tab_hot";
    public static final String KEY_HIDE_TAB_GAME = "hide_tab_game";
    public static final String KEY_HIDE_ADD = "hide_add";
    public static final String KEY_COPY_POST = "copy_post";

    public static final String KEY_CUSTOM_TEXT_SELECT = "custom_text_select";

    public static final String KEY_COMMENT_FREE_COPY = "comment_free_copy";

    public static final String KEY_SEARCH_HIDE_BANNER = "search_hide_banner";

    public static final String KEY_SEARCH_HIDE_DISCOVER = "search_hide_discover";

    public static final String KEY_SEARCH_HIDE_HOT_RANK = "search_hide_hot_rank";

    public static final String KEY_GAME_LIB_HIDE_BANNER = "game_lib_hide_banner";

    public static final String KEY_GAME_LIB_HIDE_MENU = "game_lib_hide_menu";

    public static final String KEY_GAME_LIB_HIDE_SECTIONS = "game_lib_hide_sections";

    public static final String KEY_GAME_LIB_HIDE_TYPES = "game_lib_hide_types";

    public static final String KEY_GAME_LIB_HIDE_ENTRIES = "game_lib_hide_entries";

    public static final String KEY_GAME_LIB_HIDE_SECTION_NAMES = "game_lib_hide_section_names";

    public static final String KEY_BLOCK_UPDATE = "block_update";
    public static final String KEY_SYSTEM_SHARE = "system_share";

    public static final String KEY_DAILY_TASK_ENABLED = "daily_task_enabled";

    public static final String KEY_DAILY_TASK_PICTURE = "daily_task_picture";

    public static final String KEY_DAILY_TASK_NORMAL = "daily_task_normal";

    public static final String KEY_DAILY_TASK_CHANNEL = "daily_task_channel";

    public static final String KEY_DAILY_TASK_DONE_DATE = "daily_task_done_date";

    public static final String KEY_DAILY_TASK_RESET = "daily_task_reset";

    public static final String KEY_SHARE_CHANNEL = "daily_task_channel_type";

    public static final String KEY_DAILY_TASK_BACK_HOME = "daily_task_back_home";

    public static final String KEY_FAKE_NOTIFICATION = "fake_notification";

    public static final String KEY_VIDEO_DOWNLOAD = "video_download";

    public static final String KEY_VIDEO_DIR = "video_download_dir";

    public static final String KEY_VIDEO_TO_MP4 = "video_download_to_mp4";

    public static final String KEY_PURIFY_SHARE_LINK = "purify_share_link";

    public static final String KEY_BROWSER_REDIRECT = "browser_redirect";

    public static final String KEY_BROWSER_REDIRECT_KNOWN = "browser_redirect_known_hosts";

    public static final String KEY_BROWSER_REDIRECT_FORCE = "browser_redirect_force_domains";

    public static final String KEY_BROWSER_REDIRECT_BLOCK = "browser_redirect_block_domains";

    public static final String KEY_BROWSER_TARGET = "browser_redirect_package";

    public static final String KEY_WEB_LOG = "web_log";

    public static final String KEY_WEB_LOG_DATA = "web_log_data";

    public static final String KEY_LOG = "log";

    public static final String KEY_WEBVIEW_DEVTOOLS = "webview_devtools";

    public static final String KEY_LIQUID_GLASS = "liquid_glass";
    public static final String KEY_GLASS_IMMERSIVE = "glass_immersive";
    public static final String KEY_GLASS_ADAPTIVE = "glass_adaptive";
    public static final String KEY_GLASS_DARK_COLOR = "glass_dark_color";
    public static final String KEY_GLASS_DARK_ALPHA = "glass_dark_alpha";
    public static final String KEY_GLASS_LIGHT_COLOR = "glass_light_color";
    public static final String KEY_GLASS_LIGHT_ALPHA = "glass_light_alpha";
    public static final String KEY_GLASS_BAR_HEIGHT = "glass_bar_height";
    public static final String KEY_GLASS_BAR_OFFSET = "glass_bar_offset";
    public static final String KEY_GLASS_SIDE_MARGIN = "glass_side_margin";

    public static final String KEY_GLASS_FIT_TABS = "glass_fit_tabs";

    public static final String KEY_GLASS_BAR_WIDTH_MODE = "glass_bar_width_mode";
    public static final String KEY_GLASS_BAR_WIDTH_PCT = "glass_bar_width_pct";
    public static final String KEY_GLASS_TAB_WIDTH_PCT = "glass_tab_width_pct";
    public static final String KEY_GLASS_BAR_LAYOUT = "glass_bar_layout";

    public static final String KEY_GLASS_PROVIDER = "glass_provider";

    public static final String KEY_SINGLE_COLUMN_FEED = "single_column_feed";

    public static final String KEY_POST_MIN_LEVEL = "post_min_level";

    public static final String KEY_POST_MIN_LIKE = "post_min_like";

    public static final String KEY_POST_MIN_COMMENT = "post_min_comment";

    public static final String KEY_POST_MIN_FAVOUR = "post_min_favour";

    public static final String KEY_POST_NO_LEVEL = "post_filter_no_level";

    public static final String KEY_POST_KEYWORDS = "post_filter_keywords";

    public static final String KEY_POST_AI_ENABLED = "post_filter_ai_enabled";

    public static final String KEY_BLOCK_VIDEO_POST = "block_video_post";
    public static final String KEY_HOST_HIDE_CY = "host_hide_cy";

    public static final String KEY_BLOCK_CY_COMMENT = "block_cy_comment";

    public static final String KEY_COMMENT_KEYWORDS = "comment_filter_keywords";

    public static final String KEY_BLOCK_GAME_RELAY = "block_game_relay_comment";

    public static final String KEY_RELAY_IGNORE_EMOJI = "relay_ignore_emoji";

    public static final String KEY_FLOW_DIAGNOSE = "flow_diagnose";

    public static final String KEY_VERBOSE_LOG = "verbose_log";

    public static final String KEY_FAVOUR_AUTO_CLEAN = "favour_auto_clean";

    public static final String KEY_HIDE_MSG_DOT = "hide_msg_dot";

    public static final String KEY_HIDE_MSG_BADGE = "hide_msg_badge";

    public static final String KEY_MSG_BADGE_ENTRIES = "msg_badge_entries";

    public static final String KEY_MSG_FULL_HIDE_ENTRIES = "msg_full_hide_entries";

    public static final String KEY_AI_PROVIDER = "ai_provider";

    public static final String KEY_AI_BASE_URL = "ai_base_url";

    public static final String KEY_AI_MODEL = "ai_model";

    public static final String KEY_AI_TOKEN = "ai_token";

    public static final String KEY_AI_PROMPT = "ai_prompt";

    public static final String KEY_AI_MAX_TOKENS = "ai_max_tokens";

    public static final String KEY_GLASS_DARK_PRESET = "glass_dark_preset";
    public static final String KEY_GLASS_LIGHT_PRESET = "glass_light_preset";

    public static final String KEY_WEBVIEW_ENTRY_URL = "webview_entry_url";

    public static final String KEY_RUNTIME_STATUS = "runtime_status";

    public static final String KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted";
    public static final String KEY_MODULE_VERSION_FLOOR = "module_version_floor";

    public static final String KEY_DEBUG_NO_DOWNGRADE = "debug_no_downgrade";
    public static final String KEY_TARGET_HINT_VISIBLE = "target_hint_visible";

    public static final String KEY_WATCH_ENABLED = "watch_enabled";
    public static final String KEY_WATCH_USERS = "watch_users";
    public static final String KEY_WATCH_KEYWORDS = "watch_keywords";
    public static final String KEY_WATCH_BANNER = "watch_banner";
    public static final String KEY_WATCH_NOTIFY = "watch_notify";
    public static final String KEY_WATCH_INTERVAL_MIN = "watch_interval_min";
    public static final String KEY_WATCH_WINDOW_DAYS = "watch_window_days";
    public static final String KEY_WATCH_WINDOW_MIN = "watch_window_min";
    public static final String KEY_WATCH_TOPICS = "watch_topics";
    public static final String KEY_WATCH_RECENT_TOPICS = "watch_recent_topics";
    public static final String KEY_WATCH_TITLE_ONLY = "watch_title_only";
    public static final String KEY_WATCH_STREAM_FETCH = "watch_stream_fetch";
    public static final String KEY_WATCH_PUSH_ENABLED = "watch_push_enabled";
    public static final String KEY_WATCH_PUSH_DINGTALK = "watch_push_dingtalk";
    public static final String KEY_WATCH_PUSH_WXPUSHER = "watch_push_wxpusher";
    public static final String KEY_WATCH_PUSH_ONEBOT = "watch_push_onebot";
    public static final String KEY_WATCH_PUSH_CUSTOM = "watch_push_custom";
    public static final String KEY_WATCH_SEEN = "watch_seen";
    public static final String KEY_WATCH_LAST_CHECK = "watch_last_check";
    public static final String KEY_WATCH_BASELINED = "watch_baselined";

    public static final java.util.Map<String, Boolean> BOOLEAN_DEFAULTS = buildBooleanDefaults();

    private static java.util.Map<String, Boolean> buildBooleanDefaults() {
        java.util.Map<String, Boolean> m = new java.util.LinkedHashMap<>();
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
        return m;
    }

    private static volatile XposedService sService;

    private static volatile App sApp;

    private static final List<OnServiceBoundListener> sBoundListeners = new ArrayList<>();

    public interface OnServiceBoundListener {
        void onServiceBound();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        LogRecorder.setContext(this);
        Checkpoint.mark("模块进程启动 (pid=%d)", android.os.Process.myPid());
        Logs.i(TAG, "App.onCreate: pid=" + android.os.Process.myPid());
        XposedServiceHelper.registerListener(this);
        Logs.i(TAG, "已注册 XposedService 监听器");
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        Checkpoint.mark("XposedService 已绑定: %s", describe(service));
        LogRecorder.setEnabled(readBoolean(KEY_LOG, false));
        LogRecorder.setVerbose(readBoolean(KEY_VERBOSE_LOG, false));
        LogRecorder.recordEvent("XposedService 已绑定: " + describe(service));
        SharedPreferences pending = getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
        Logs.i(TAG, "XposedService 已绑定: service=" + describe(service)
                + ", pendingCount=" + pending.getAll().size());
        PreferenceReceiver.tryFlush(this, pending);
        notifyServiceBound();
    }

    @Override
    public void onServiceDied(XposedService service) {
        Checkpoint.mark("XposedService 断开: %s", describe(service));
        Logs.w(TAG, "XposedService 已断开: service=" + describe(service)
                + ", current=" + describe(sService));
        sService = null;
    }

    public static SharedPreferences getPrefs() {
        XposedService service = sService;
        if (service == null) {
            Logs.w(TAG, "获取 RemotePreferences 失败: XposedService 未绑定");
            return null;
        }
        try {
            SharedPreferences prefs = service.getRemotePreferences(PREFS_GROUP);
            if (prefs == null) {
                Logs.e(TAG, "获取 RemotePreferences 失败: service 返回 null, group=" + PREFS_GROUP);
            } else {
                Logs.i(TAG, "获取 RemotePreferences 成功: group=" + PREFS_GROUP);
            }
            return prefs;
        } catch (Throwable t) {
            Logs.e(TAG, "获取 RemotePreferences 异常: group=" + PREFS_GROUP, t);
            return null;
        }
    }

    public static boolean readBoolean(String key, boolean defaultValue) {
        App app = sApp;
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            if (pending.contains(key)) {
                return pending.getBoolean(key, defaultValue);
            }
        }
        SharedPreferences remote = getPrefs();
        return remote != null ? remote.getBoolean(key, defaultValue) : defaultValue;
    }

    public static String readString(String key, String defaultValue) {
        App app = sApp;
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            if (pending.contains(key)) {
                return pending.getString(key, defaultValue);
            }
        }
        SharedPreferences remote = getPrefs();
        return remote != null ? remote.getString(key, defaultValue) : defaultValue;
    }

    public static void writeString(String key, String value) {
        App app = sApp;
        SharedPreferences remote = getPrefs();
        if (remote != null) {
            remote.edit().putString(key, value).apply();
            LogRecorder.recordEvent("字符串已写入 RemotePreferences: key=" + key);
            return;
        }
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            pending.edit().putString(key, value).commit();
            LogRecorder.recordEvent("字符串写入待提交缓存: key=" + key);
            PreferenceReceiver.tryFlush(app, pending);
        }
    }

    public static void writeBoolean(String key, boolean value) {
        App app = sApp;
        SharedPreferences remote = getPrefs();
        if (remote != null) {
            remote.edit().putBoolean(key, value).apply();
            LogRecorder.recordEvent("开关已写入 RemotePreferences: key=" + key + ", value=" + value);
            return;
        }
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            pending.edit().putBoolean(key, value).commit();
            LogRecorder.recordEvent("服务未连接，开关写入待提交缓存: key=" + key + ", value=" + value);
            PreferenceReceiver.tryFlush(app, pending);
        }
    }

    public static void addOnServiceBoundListener(OnServiceBoundListener listener) {
        synchronized (sBoundListeners) {
            if (!sBoundListeners.contains(listener)) {
                sBoundListeners.add(listener);
            }
        }
    }

    private static void notifyServiceBound() {
        final List<OnServiceBoundListener> snapshot;
        synchronized (sBoundListeners) {
            snapshot = new ArrayList<>(sBoundListeners);
        }
        if (snapshot.isEmpty()) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                for (OnServiceBoundListener listener : snapshot) {
                    try {
                        listener.onServiceBound();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private static String describe(XposedService service) {
        return service == null ? "null"
                : service.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(service));
    }

    public static XposedService getService() {
        return sService;
    }

    public static Context getAppContext() {
        return sApp;
    }

    public static Context resolveAppContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String currentProcessName() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object name = activityThread.getMethod("currentProcessName").invoke(null);
            return name != null ? name.toString() : String.valueOf(android.os.Process.myPid());
        } catch (Throwable ignored) {
            return String.valueOf(android.os.Process.myPid());
        }
    }
}
