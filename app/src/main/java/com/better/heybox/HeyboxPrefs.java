package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 小黑盒进程内的设置存储（直接写本进程 SharedPreferences，不跨进程）。
 * 读写：setBoolean 直接写本文件；getBoolean 优先本文件，键不存在回退框架 RemotePreferences（模块设置页写入）
 */
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
        return prefs.edit().putBoolean(key, value).commit();
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
        return prefs.edit().putString(key, value).commit();
    }
}
