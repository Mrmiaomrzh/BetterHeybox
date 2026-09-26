package com.better.heybox.liquidglass

import android.content.Context
import android.graphics.Color
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs

internal object GlassConfig {

    private const val DEFAULT_DARK_COLOR = 0xFF000000.toInt()
    private const val DEFAULT_DARK_ALPHA = 56
    private const val DEFAULT_LIGHT_COLOR = 0xFFFFFFFF.toInt()
    private const val DEFAULT_LIGHT_ALPHA = 64
    private const val DEFAULT_ADAPTIVE = true
    private const val DEFAULT_IMMERSIVE = true
    private const val DEFAULT_FIT_TABS = false
    private const val DEFAULT_BAR_HEIGHT = 0
    private const val DEFAULT_BAR_OFFSET = 16
    private const val DEFAULT_SIDE_MARGIN = 16
    private const val DEFAULT_BAR_WIDTH_MODE = 0
    private const val DEFAULT_BAR_WIDTH_PCT = 100
    private const val DEFAULT_TAB_WIDTH_PCT = 100
    private const val DEFAULT_BAR_LAYOUT = 0

    @JvmField @Volatile var darkColor = DEFAULT_DARK_COLOR
    @JvmField @Volatile var darkAlphaPct = DEFAULT_DARK_ALPHA
    @JvmField @Volatile var lightColor = DEFAULT_LIGHT_COLOR
    @JvmField @Volatile var lightAlphaPct = DEFAULT_LIGHT_ALPHA
    @JvmField @Volatile var adaptiveChrome = DEFAULT_ADAPTIVE
    @JvmField @Volatile var immersiveGestureNavigation = DEFAULT_IMMERSIVE
    @JvmField @Volatile var fitTabs = DEFAULT_FIT_TABS
    @JvmField @Volatile var barHeightDp = DEFAULT_BAR_HEIGHT
    @JvmField @Volatile var barOffsetDp = DEFAULT_BAR_OFFSET

    @JvmField @Volatile var barSideMarginDp = DEFAULT_SIDE_MARGIN

    @JvmField @Volatile var barWidthMode = DEFAULT_BAR_WIDTH_MODE

    @JvmField @Volatile var barWidthPct = DEFAULT_BAR_WIDTH_PCT

    @JvmField @Volatile var tabWidthPct = DEFAULT_TAB_WIDTH_PCT

    @JvmField @Volatile var barLayoutMode = DEFAULT_BAR_LAYOUT

    @JvmStatic
    fun darkTint(): Int = compose(darkColor, darkAlphaPct)

    @JvmStatic
    fun lightTint(): Int = compose(lightColor, lightAlphaPct)

    private fun compose(rgb: Int, pct: Int): Int {
        val a = Math.round(255f * maxOf(5, minOf(pct, 98)) / 100f)
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    @JvmStatic
    fun load(ctx: Context?) {
        try {
            if (ctx == null) return
            HeyboxPrefs.init(ctx)
            immersiveGestureNavigation = HeyboxPrefs.getBoolean(
                App.KEY_GLASS_IMMERSIVE, immersiveGestureNavigation)
            darkColor = parseColor(HeyboxPrefs.getString(App.KEY_GLASS_DARK_COLOR, null), darkColor)
            darkAlphaPct = parseInt(
                HeyboxPrefs.getString(App.KEY_GLASS_DARK_ALPHA, null), darkAlphaPct)
            lightColor = parseColor(
                HeyboxPrefs.getString(App.KEY_GLASS_LIGHT_COLOR, null), lightColor)
            lightAlphaPct = parseInt(
                HeyboxPrefs.getString(App.KEY_GLASS_LIGHT_ALPHA, null), lightAlphaPct)
            adaptiveChrome = HeyboxPrefs.getBoolean(App.KEY_GLASS_ADAPTIVE, adaptiveChrome)
            fitTabs = HeyboxPrefs.getBoolean(App.KEY_GLASS_FIT_TABS, fitTabs)
            barHeightDp = parseInt(HeyboxPrefs.getString(App.KEY_GLASS_BAR_HEIGHT, null), barHeightDp)
            barOffsetDp = parseInt(HeyboxPrefs.getString(App.KEY_GLASS_BAR_OFFSET, null), barOffsetDp)
            barSideMarginDp = parseInt(
                HeyboxPrefs.getString(App.KEY_GLASS_SIDE_MARGIN, null), barSideMarginDp)
            barWidthMode = parseInt(
                HeyboxPrefs.getString(App.KEY_GLASS_BAR_WIDTH_MODE, null), barWidthMode)
            barWidthPct = parseInt(
                HeyboxPrefs.getString(App.KEY_GLASS_BAR_WIDTH_PCT, null), barWidthPct)
            tabWidthPct = parseInt(
                HeyboxPrefs.getString(App.KEY_GLASS_TAB_WIDTH_PCT, null), tabWidthPct)
            barLayoutMode = parseInt(
                HeyboxPrefs.getString(App.KEY_GLASS_BAR_LAYOUT, null), barLayoutMode)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("config load failed", t)
        }
    }

    @JvmStatic
    fun save(ctx: Context?) {
        try {
            if (ctx == null) return
            HeyboxPrefs.init(ctx)
            HeyboxPrefs.setBoolean(App.KEY_GLASS_IMMERSIVE, immersiveGestureNavigation)
            HeyboxPrefs.setBoolean(App.KEY_GLASS_ADAPTIVE, adaptiveChrome)
            HeyboxPrefs.setBoolean(App.KEY_GLASS_FIT_TABS, fitTabs)
            HeyboxPrefs.setString(App.KEY_GLASS_DARK_COLOR, formatColor(darkColor))
            HeyboxPrefs.setString(App.KEY_GLASS_DARK_ALPHA, darkAlphaPct.toString())
            HeyboxPrefs.setString(App.KEY_GLASS_LIGHT_COLOR, formatColor(lightColor))
            HeyboxPrefs.setString(App.KEY_GLASS_LIGHT_ALPHA, lightAlphaPct.toString())
            HeyboxPrefs.setString(App.KEY_GLASS_BAR_HEIGHT, barHeightDp.toString())
            HeyboxPrefs.setString(App.KEY_GLASS_BAR_OFFSET, barOffsetDp.toString())
            HeyboxPrefs.setString(App.KEY_GLASS_SIDE_MARGIN, barSideMarginDp.toString())
            HeyboxPrefs.setString(App.KEY_GLASS_BAR_WIDTH_MODE, barWidthMode.toString())
            HeyboxPrefs.setString(App.KEY_GLASS_BAR_WIDTH_PCT, barWidthPct.toString())
            HeyboxPrefs.setString(App.KEY_GLASS_TAB_WIDTH_PCT, tabWidthPct.toString())
            HeyboxPrefs.setString(App.KEY_GLASS_BAR_LAYOUT, barLayoutMode.toString())
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("config save failed", t)
        }
    }

    @JvmStatic
    fun resetDefaults() {
        darkColor = DEFAULT_DARK_COLOR
        darkAlphaPct = DEFAULT_DARK_ALPHA
        lightColor = DEFAULT_LIGHT_COLOR
        lightAlphaPct = DEFAULT_LIGHT_ALPHA
        adaptiveChrome = DEFAULT_ADAPTIVE
        barHeightDp = DEFAULT_BAR_HEIGHT
        barOffsetDp = DEFAULT_BAR_OFFSET
        barSideMarginDp = DEFAULT_SIDE_MARGIN
        immersiveGestureNavigation = DEFAULT_IMMERSIVE
        fitTabs = DEFAULT_FIT_TABS
        barWidthMode = DEFAULT_BAR_WIDTH_MODE
        barWidthPct = DEFAULT_BAR_WIDTH_PCT
        tabWidthPct = DEFAULT_TAB_WIDTH_PCT
        barLayoutMode = DEFAULT_BAR_LAYOUT
    }

    private fun parseColor(raw: String?, fallback: Int): Int {
        try {
            val trimmed = raw?.trim()
            if (!trimmed.isNullOrEmpty()) {
                return Color.parseColor(trimmed)
            }
        } catch (ignored: Throwable) {
        }
        return fallback
    }

    private fun parseInt(raw: String?, fallback: Int): Int {
        try {
            val trimmed = raw?.trim()
            if (!trimmed.isNullOrEmpty()) {
                return trimmed.toInt()
            }
        } catch (ignored: Throwable) {
        }
        return fallback
    }

    private fun formatColor(color: Int): String =
        String.format("#%06X", color and 0xFFFFFF)
}
