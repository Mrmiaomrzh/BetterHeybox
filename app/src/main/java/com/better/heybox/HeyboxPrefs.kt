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
        prefs.edit().putBoolean(key, value).apply()
        if (isWatchKey(key)) {
            WatchConfig.invalidate()
        }
        if (isFeedFilterKey(key)) {
            val hook = com.better.heybox.hooks.PostFilterHook.get()
            hook?.invalidateConfig()
        }
        if (isPostDetailKey(key)) {
            com.better.heybox.hooks.PostDetailCleanHook.refresh()
        }
        return true
    }

    @JvmStatic
    fun getString(key: String, defaultValue: String?): String? {
        val prefs = get()
        return if (prefs != null) prefs.getString(key, defaultValue) else defaultValue
    }

    @JvmStatic
    fun setString(key: String, value: String?): Boolean {
        val prefs = get() ?: return false
        prefs.edit().putString(key, value).apply()
        if (isWatchKey(key)) {
            WatchConfig.invalidate()
        }
        if (isFeedFilterKey(key)) {
            val hook = com.better.heybox.hooks.PostFilterHook.get()
            hook?.invalidateConfig()
        }
        if (isPostDetailKey(key)) {
            com.better.heybox.hooks.PostDetailCleanHook.refresh()
        }
        return true
    }

    private fun isWatchKey(key: String?): Boolean = key != null && key.startsWith("watch_")

    private fun isFeedFilterKey(key: String?): Boolean {
        if (key == null) {
            return false
        }
        return key.startsWith("post_filter_") || key.startsWith("post_min_")
                || "promote_ad" == key || "block_video_post" == key
                || "flow_diagnose" == key || "verbose_log" == key
                || "single_column_feed" == key
                || "ai_base_url" == key || "ai_model" == key
    }

    private fun isPostDetailKey(key: String?): Boolean =
        key != null && key.startsWith("post_detail_")
}
