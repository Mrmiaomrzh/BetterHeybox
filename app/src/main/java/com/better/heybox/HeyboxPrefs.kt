package com.better.heybox

import android.content.Context
import android.content.SharedPreferences
import com.better.heybox.watch.WatchConfig

object HeyboxPrefs {

    const val PREFS_NAME = "betterheybox"

    @Volatile private var sPrefs: SharedPreferences? = null

    @JvmStatic
    fun init(context: Context?) {
        if (context != null && sPrefs == null) {
            sPrefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    @JvmStatic
    fun get(): SharedPreferences? {
        var prefs = sPrefs
        if (prefs == null) {
            val context = App.resolveAppContext()
            if (context != null) {
                prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sPrefs = prefs
            }
        }
        return prefs
    }

    @JvmStatic
    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val prefs = get()
        return if (prefs != null) prefs.getBoolean(key, defaultValue) else defaultValue
    }

    @JvmStatic
    fun contains(key: String): Boolean = get()?.contains(key) ?: false

    @JvmStatic
    fun setBoolean(key: String, value: Boolean): Boolean {
        val prefs = get() ?: return false
        val ok = prefs.edit().putBoolean(key, value).commit()
        if (isWatchKey(key)) {
            WatchConfig.invalidate()
        }
        return ok
    }

    @JvmStatic
    fun getString(key: String, defaultValue: String?): String? {
        val prefs = get()
        return if (prefs != null) prefs.getString(key, defaultValue) else defaultValue
    }

    @JvmStatic
    fun setString(key: String, value: String?): Boolean {
        val prefs = get() ?: return false
        val ok = prefs.edit().putString(key, value).commit()
        if (isWatchKey(key)) {
            WatchConfig.invalidate()
        }
        return ok
    }

    private fun isWatchKey(key: String?): Boolean = key != null && key.startsWith("watch_")
}
