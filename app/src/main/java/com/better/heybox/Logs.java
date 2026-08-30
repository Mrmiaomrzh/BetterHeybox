package com.better.heybox;

import android.util.Log;

/**
 * 统一日志出口：Release 只放行 error 级，Debug 全量；模块内请勿直接调用 Log
 */
public final class Logs {

    private Logs() {
    }

    public static void i(String tag, String msg) {
        if (BuildFlags.DEBUG) {
            Log.i(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (BuildFlags.DEBUG) {
            Log.w(tag, msg);
        }
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
    }

    public static boolean shouldLog(int level) {
        return BuildFlags.DEBUG || level >= Log.ERROR;
    }
}
