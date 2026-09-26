package com.better.heybox.liquidglass

import android.util.Log

internal object LiquidGlassLog {

    @JvmStatic
    fun log(priority: Int, message: String) {
        Log.println(priority, "BetterHeybox", message)
    }

    @JvmStatic
    fun logErr(message: String, error: Throwable) {
        Log.e("BetterHeybox", message, error)
    }
}
