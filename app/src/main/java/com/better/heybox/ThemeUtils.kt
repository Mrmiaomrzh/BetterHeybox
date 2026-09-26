package com.better.heybox

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import kotlin.math.pow

object ThemeUtils {

    const val FALLBACK_ACCENT = 0xFF1677FF.toInt()

    const val RADIUS_SHEET_DP = 28
    const val RADIUS_BUTTON_DP = 16
    const val RADIUS_ITEM_DP = 16
    const val RADIUS_SMALL_DP = 12

    const val ANIM_SHEET_IN_MS = 220L
    const val ANIM_SCRIM_IN_MS = 150L
    const val ANIM_PRESS_MS = 80L
    const val ANIM_STATE_MS = 150L

    @JvmStatic
    fun resolveAccent(context: Context?): Int =
        systemShade(context, "system_accent1_", FALLBACK_ACCENT)

    @JvmStatic
    fun resolveAccent2(context: Context?): Int =
        systemShade(context, "system_accent2_", resolveAccent(context))

    @JvmStatic
    fun resolveAccentStrong(context: Context?): Int =
        systemShadeReversed(context, "system_accent1_", resolveAccent(context))

    @JvmStatic
    fun resolveAccent2Strong(context: Context?): Int =
        systemShadeReversed(context, "system_accent2_", resolveAccentStrong(context))

    private fun systemShadeReversed(context: Context?, family: String, fallback: Int): Int {
        if (context == null) return fallback
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val shade = if (isDarkMode(context)) "200" else "600"
                val resId = context.resources.getIdentifier(family + shade, "color", "android")
                if (resId != 0) {
                    val color = context.getColor(resId)
                    if (Color.alpha(color) == 255) {
                        return color
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        return fallback
    }

    @JvmStatic
    fun surfaceColor(context: Context?): Int =
        if (isDarkMode(context)) {
            systemShade(context, "system_neutral1_900", 0xFF1C1C1E.toInt())
        } else {
            systemShade(context, "system_neutral1_50", 0xFFFFFFFF.toInt())
        }

    @JvmStatic
    fun surfaceVariantColor(context: Context?): Int =
        if (isDarkMode(context)) {
            systemShade(context, "system_neutral2_800", 0xFF2C2C2E.toInt())
        } else {
            systemShade(context, "system_neutral2_100", 0xFFF2F2F7.toInt())
        }

    @JvmStatic
    fun outlineColor(context: Context?): Int =
        if (isDarkMode(context)) {
            withAlpha(systemShade(context, "system_neutral2_700", 0xFFFFFFFF.toInt()), 0x2E)
        } else {
            withAlpha(systemShade(context, "system_neutral2_200", 0xFF000000.toInt()), 0x1F)
        }

    @JvmStatic
    fun textPrimaryColor(context: Context?): Int =
        if (isDarkMode(context)) {
            systemShade(context, "system_neutral1_50", 0xDEFFFFFF.toInt())
        } else {
            systemShade(context, "system_neutral1_900", 0xDD000000.toInt())
        }

    @JvmStatic
    fun textSecondaryColor(context: Context?): Int =
        withAlpha(textPrimaryColor(context), 0x99)

    private fun systemShade(context: Context?, family: String, fallback: Int): Int {
        if (context == null) return fallback
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val shade = if (isDarkMode(context)) "600" else "200"
                val resId = context.resources.getIdentifier(family + shade, "color", "android")
                if (resId != 0) {
                    val color = context.getColor(resId)
                    if (Color.alpha(color) == 255) {
                        return color
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        if (family.startsWith("system_accent")) {
            try {
                val value = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.colorAccent, value, true) &&
                    value.data != 0
                ) {
                    return value.data
                }
            } catch (ignored: Throwable) {
            }
        }
        return fallback
    }

    @JvmStatic
    fun isDarkMode(context: Context?): Boolean {
        if (context == null) return false
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    @JvmStatic
    fun dp(context: Context?, value: Float): Int {
        if (context == null) return 0
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    @JvmStatic
    fun readableForegroundOn(backgroundColor: Int): Int {
        val luminance = 0.2126 * linear(Color.red(backgroundColor)) +
                0.7152 * linear(Color.green(backgroundColor)) +
                0.0722 * linear(Color.blue(backgroundColor))
        return if (luminance > 0.45) Color.BLACK else Color.WHITE
    }

    @JvmStatic
    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}
