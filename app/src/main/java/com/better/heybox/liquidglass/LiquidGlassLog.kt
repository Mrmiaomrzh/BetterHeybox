package com.better.heybox.liquidglass

import android.util.Log
import com.better.heybox.LogRecorder

internal object LiquidGlassLog {

    private const val TAG = "BetterHeybox"

    @JvmStatic
    fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
        try {
            LogRecorder.record(priority, TAG, message)
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun logErr(message: String, error: Throwable) {
        Log.e(TAG, message, error)
        try {
            LogRecorder.record(Log.ERROR, TAG, message, error)
        } catch (ignored: Throwable) {
        }
    }
}
