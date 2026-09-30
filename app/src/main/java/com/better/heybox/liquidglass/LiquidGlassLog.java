package com.better.heybox.liquidglass;

import android.util.Log;

import com.better.heybox.LogRecorder;

final class LiquidGlassLog {

    private static final String TAG = "BetterHeybox";

    private LiquidGlassLog() {
    }

    static void log(int priority, String message) {
        Log.println(priority, TAG, message);
        try {
            LogRecorder.record(priority, TAG, message);
        } catch (Throwable ignored) {
        }
    }

    static void logErr(String message, Throwable error) {
        Log.e(TAG, message, error);
        try {
            LogRecorder.record(Log.WARN, TAG, message, error);
        } catch (Throwable ignored) {
        }
    }
}
