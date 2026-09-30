package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;

public final class HeyboxPrefs {

    public static final String PREFS_NAME = "betterheybox";

    private static volatile SharedPreferences sPrefs;

    private HeyboxPrefs() {
    }

    public static void init(Context context) {
        if (context != null && sPrefs == null) {
            sPrefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public static SharedPreferences get() {
        SharedPreferences prefs = sPrefs;
        if (prefs == null) {
            Context context = App.resolveAppContext();
            if (context != null) {
                prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sPrefs = prefs;
            }
        }
        return prefs;
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences prefs = get();
        return prefs != null ? prefs.getBoolean(key, defaultValue) : defaultValue;
    }

    public static boolean contains(String key) {
        SharedPreferences prefs = get();
        return prefs != null && prefs.contains(key);
    }

    public static boolean setBoolean(String key, boolean value) {
        SharedPreferences prefs = get();
        if (prefs == null) {
            return false;
        }
        prefs.edit().putBoolean(key, value).apply();
        if (isWatchKey(key)) {
            com.better.heybox.watch.WatchConfig.invalidate();
        }
        if (isFeedFilterKey(key)) {
            com.better.heybox.hooks.PostFilterHook hook = com.better.heybox.hooks.PostFilterHook.get();
            if (hook != null) {
                hook.invalidateConfig();
            }
        }
        if (isPostDetailKey(key)) {
            com.better.heybox.hooks.PostDetailCleanHook.refresh();
        }
        return true;
    }

    public static String getString(String key, String defaultValue) {
        SharedPreferences prefs = get();
        return prefs != null ? prefs.getString(key, defaultValue) : defaultValue;
    }

    public static boolean setString(String key, String value) {
        SharedPreferences prefs = get();
        if (prefs == null) {
            return false;
        }
        prefs.edit().putString(key, value).apply();
        if (isWatchKey(key)) {
            com.better.heybox.watch.WatchConfig.invalidate();
        }
        if (isFeedFilterKey(key)) {
            com.better.heybox.hooks.PostFilterHook hook = com.better.heybox.hooks.PostFilterHook.get();
            if (hook != null) {
                hook.invalidateConfig();
            }
        }
        if (isPostDetailKey(key)) {
            com.better.heybox.hooks.PostDetailCleanHook.refresh();
        }
        return true;
    }
    
    private static boolean isWatchKey(String key) {
        return key != null && key.startsWith("watch_");
    }

    private static boolean isFeedFilterKey(String key) {
        if (key == null) {
            return false;
        }
        return key.startsWith("post_filter_") || key.startsWith("post_min_")
                || "promote_ad".equals(key) || "block_video_post".equals(key)
                || "flow_diagnose".equals(key) || "verbose_log".equals(key)
                || "single_column_feed".equals(key)
                || "ai_base_url".equals(key) || "ai_model".equals(key);
    }

    private static boolean isPostDetailKey(String key) {
        return key != null && key.startsWith("post_detail_");
    }
}
