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
        boolean ok = prefs.edit().putBoolean(key, value).commit();
        if (isWatchKey(key)) {
            com.better.heybox.watch.WatchConfig.invalidate();
        }
        return ok;
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
        boolean ok = prefs.edit().putString(key, value).commit();
        if (isWatchKey(key)) {
            com.better.heybox.watch.WatchConfig.invalidate();
        }
        return ok;
    }
    
    private static boolean isWatchKey(String key) {
        return key != null && key.startsWith("watch_");
    }
}
