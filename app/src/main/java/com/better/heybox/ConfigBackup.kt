package com.better.heybox

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

object ConfigBackup {

    const val FORMAT = "betterheybox-config"
    const val VERSION = 1

    private const val TAG = "BetterHeybox"

    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_BOOLEANS = "booleans"
    private const val KEY_STRINGS = "strings"

    private val BOOLEAN_KEYS: Array<String> = App.BOOLEAN_DEFAULTS.keys.toTypedArray()

    private val STRING_KEYS = arrayOf(
        App.KEY_DAILY_TASK_PICTURE,
        App.KEY_DAILY_TASK_NORMAL,
        App.KEY_DAILY_TASK_CHANNEL,
        App.KEY_SHARE_CHANNEL,
        App.KEY_GLASS_PROVIDER,
        App.KEY_GLASS_DARK_COLOR,
        App.KEY_GLASS_DARK_ALPHA,
        App.KEY_GLASS_LIGHT_COLOR,
        App.KEY_GLASS_LIGHT_ALPHA,
        App.KEY_GLASS_BAR_HEIGHT,
        App.KEY_GLASS_BAR_OFFSET,
        App.KEY_GLASS_SIDE_MARGIN,
        App.KEY_GLASS_BAR_WIDTH_MODE,
        App.KEY_GLASS_BAR_WIDTH_PCT,
        App.KEY_GLASS_TAB_WIDTH_PCT,
        App.KEY_GLASS_BAR_LAYOUT,
        App.KEY_GLASS_DARK_PRESET,
        App.KEY_GLASS_LIGHT_PRESET,
        App.KEY_POST_MIN_LEVEL,
        App.KEY_POST_MIN_LIKE,
        App.KEY_POST_MIN_COMMENT,
        App.KEY_POST_MIN_FAVOUR,
        App.KEY_POST_KEYWORDS,
        App.KEY_COMMENT_KEYWORDS,
        App.KEY_GAME_LIB_HIDE_TYPES,
        App.KEY_GAME_LIB_HIDE_ENTRIES,
        App.KEY_GAME_LIB_HIDE_SECTION_NAMES,
        App.KEY_MSG_BADGE_ENTRIES,
        App.KEY_MSG_FULL_HIDE_ENTRIES,
        App.KEY_BROWSER_REDIRECT_FORCE,
        App.KEY_BROWSER_REDIRECT_BLOCK,
        App.KEY_BROWSER_TARGET,
        App.KEY_AI_PROVIDER,
        App.KEY_AI_BASE_URL,
        App.KEY_AI_MODEL,
        App.KEY_AI_PROMPT,
        App.KEY_AI_MAX_TOKENS,
        App.KEY_WATCH_USERS,
        App.KEY_WATCH_TOPICS,
        App.KEY_WATCH_KEYWORDS,
        App.KEY_WATCH_WINDOW_MIN,
        App.KEY_WATCH_INTERVAL_MIN,
        App.KEY_WATCH_PUSH_DINGTALK,
        App.KEY_WATCH_PUSH_WXPUSHER,
        App.KEY_WATCH_PUSH_ONEBOT,
        App.KEY_WATCH_PUSH_CUSTOM
    )

    private val RESTART_KEYS = arrayOf(
        App.KEY_HIDE_TAB_HOME,
        App.KEY_HIDE_TAB_HOT,
        App.KEY_HIDE_TAB_GAME,
        App.KEY_HIDE_ADD
    )

    interface Reader<T> {
        fun get(key: String, def: T): T
    }

    interface Writer<T> {
        fun write(key: String, value: T)
    }

    @JvmStatic
    fun buildJson(booleanReader: Reader<Boolean>, stringReader: Reader<String>): String? {
        return try {
            val booleans = JSONObject()
            for (key in BOOLEAN_KEYS) {
                booleans.put(key, booleanReader.get(key, defaultFor(key)))
            }
            val strings = JSONObject()
            for (key in STRING_KEYS) {
                val value = stringReader.get(key, "")
                strings.put(key, value ?: "")
            }
            val root = JSONObject()
            root.put(KEY_FORMAT, FORMAT)
            root.put(KEY_VERSION, VERSION)
            root.put(KEY_EXPORTED_AT, System.currentTimeMillis())
            root.put(KEY_BOOLEANS, booleans)
            root.put(KEY_STRINGS, strings)
            root.toString(2)
        } catch (e: JSONException) {
            Logs.e(TAG, "导出配置失败: $e")
            null
        }
    }

    @JvmStatic
    fun applyJson(
        json: String,
        booleanWriter: Writer<Boolean>,
        stringWriter: Writer<String>
    ): ApplyResult? {
        return try {
            val root = JSONObject(json)
            val format = root.optString(KEY_FORMAT)
            if (FORMAT != format) {
                Logs.w(TAG, "导入拒绝: format 不匹配, actual=$format")
                return null
            }
            var count = 0
            var restartRequired = false
            val booleans = root.optJSONObject(KEY_BOOLEANS)
            if (booleans != null) {
                for (key in BOOLEAN_KEYS) {
                    if (booleans.has(key)) {
                        booleanWriter.write(key, booleans.optBoolean(key))
                        count++
                        if (isRestartKey(key)) {
                            restartRequired = true
                        }
                    }
                }
            }
            val strings = root.optJSONObject(KEY_STRINGS)
            if (strings != null) {
                for (key in STRING_KEYS) {
                    if (strings.has(key)) {
                        stringWriter.write(key, strings.optString(key))
                        count++
                    }
                }
            }
            Logs.i(TAG, "导入完成: applied=$count, restartRequired=$restartRequired")
            ApplyResult(count, restartRequired)
        } catch (e: JSONException) {
            Logs.e(TAG, "导入配置解析失败: $e")
            null
        }
    }

    private fun isRestartKey(key: String): Boolean {
        for (k in RESTART_KEYS) {
            if (k == key) {
                return true
            }
        }
        return false
    }

    private fun defaultFor(key: String): Boolean = App.BOOLEAN_DEFAULTS[key] ?: false

    class ApplyResult(@JvmField val applied: Int, @JvmField val restartRequired: Boolean)
}
