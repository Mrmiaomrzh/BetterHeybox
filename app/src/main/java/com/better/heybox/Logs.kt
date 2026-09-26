package com.better.heybox

import android.util.Log

object Logs {

    @JvmStatic
    fun i(tag: String, msg: String) {
        if (BuildFlags.DEBUG) {
            Log.i(tag, msg)
        }
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
    }

    @JvmStatic
    fun shouldLog(level: Int): Boolean = BuildFlags.DEBUG || level >= Log.WARN
}
