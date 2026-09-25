package com.better.heybox;

import android.util.Log;

public final class Logs {

    private Logs() {
    }

    public static void i(String tag, String msg) {
        if (BuildFlags.DEBUG) {
            Log.i(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
    }

    public static boolean shouldLog(int level) {
        return BuildFlags.DEBUG || level >= Log.WARN;
    }
}
