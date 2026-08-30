package com.better.heybox.liquidglass;

import android.content.Context;
import android.graphics.Color;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;

final class GlassConfig {

    private static final int DEFAULT_DARK_COLOR = 0xFF000000;
    private static final int DEFAULT_DARK_ALPHA = 56;
    private static final int DEFAULT_LIGHT_COLOR = 0xFFFFFFFF;
    private static final int DEFAULT_LIGHT_ALPHA = 64;
    private static final boolean DEFAULT_ADAPTIVE = true;
    private static final boolean DEFAULT_IMMERSIVE = true;
    private static final boolean DEFAULT_FIT_TABS = false;
    private static final int DEFAULT_BAR_HEIGHT = 0;
    private static final int DEFAULT_BAR_OFFSET = 16;

    static volatile int darkColor = DEFAULT_DARK_COLOR;
    static volatile int darkAlphaPct = DEFAULT_DARK_ALPHA;
    static volatile int lightColor = DEFAULT_LIGHT_COLOR;
    static volatile int lightAlphaPct = DEFAULT_LIGHT_ALPHA;
    static volatile boolean adaptiveChrome = DEFAULT_ADAPTIVE;
    static volatile boolean immersiveGestureNavigation = DEFAULT_IMMERSIVE;
    static volatile boolean fitTabs = DEFAULT_FIT_TABS;
    static volatile int barHeightDp = DEFAULT_BAR_HEIGHT;
    static volatile int barOffsetDp = DEFAULT_BAR_OFFSET;

    private GlassConfig() {
    }

    static int darkTint() {
        return compose(darkColor, darkAlphaPct);
    }

    static int lightTint() {
        return compose(lightColor, lightAlphaPct);
    }

    private static int compose(int rgb, int pct) {
        int a = Math.round(255f * Math.max(5, Math.min(pct, 98)) / 100f);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    static void load(Context ctx) {
        try {
            HeyboxPrefs.init(ctx);
            immersiveGestureNavigation = HeyboxPrefs.getBoolean(
                    App.KEY_GLASS_IMMERSIVE, immersiveGestureNavigation);
            darkColor = parseColor(HeyboxPrefs.getString(App.KEY_GLASS_DARK_COLOR, null), darkColor);
            darkAlphaPct = parseInt(HeyboxPrefs.getString(App.KEY_GLASS_DARK_ALPHA, null), darkAlphaPct);
            lightColor = parseColor(HeyboxPrefs.getString(App.KEY_GLASS_LIGHT_COLOR, null), lightColor);
            lightAlphaPct = parseInt(HeyboxPrefs.getString(App.KEY_GLASS_LIGHT_ALPHA, null), lightAlphaPct);
            adaptiveChrome = HeyboxPrefs.getBoolean(App.KEY_GLASS_ADAPTIVE, adaptiveChrome);
            fitTabs = HeyboxPrefs.getBoolean(App.KEY_GLASS_FIT_TABS, fitTabs);
            barHeightDp = parseInt(HeyboxPrefs.getString(App.KEY_GLASS_BAR_HEIGHT, null), barHeightDp);
            barOffsetDp = parseInt(HeyboxPrefs.getString(App.KEY_GLASS_BAR_OFFSET, null), barOffsetDp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("config load failed", t);
        }
    }

    static void save(Context ctx) {
        try {
            HeyboxPrefs.init(ctx);
            HeyboxPrefs.setBoolean(App.KEY_GLASS_IMMERSIVE, immersiveGestureNavigation);
            HeyboxPrefs.setBoolean(App.KEY_GLASS_ADAPTIVE, adaptiveChrome);
            HeyboxPrefs.setBoolean(App.KEY_GLASS_FIT_TABS, fitTabs);
            HeyboxPrefs.setString(App.KEY_GLASS_DARK_COLOR, formatColor(darkColor));
            HeyboxPrefs.setString(App.KEY_GLASS_DARK_ALPHA, String.valueOf(darkAlphaPct));
            HeyboxPrefs.setString(App.KEY_GLASS_LIGHT_COLOR, formatColor(lightColor));
            HeyboxPrefs.setString(App.KEY_GLASS_LIGHT_ALPHA, String.valueOf(lightAlphaPct));
            HeyboxPrefs.setString(App.KEY_GLASS_BAR_HEIGHT, String.valueOf(barHeightDp));
            HeyboxPrefs.setString(App.KEY_GLASS_BAR_OFFSET, String.valueOf(barOffsetDp));
        } catch (Throwable t) {
            LiquidGlassLog.logErr("config save failed", t);
        }
    }

    static void resetDefaults() {
        darkColor = DEFAULT_DARK_COLOR;
        darkAlphaPct = DEFAULT_DARK_ALPHA;
        lightColor = DEFAULT_LIGHT_COLOR;
        lightAlphaPct = DEFAULT_LIGHT_ALPHA;
        adaptiveChrome = DEFAULT_ADAPTIVE;
        barHeightDp = DEFAULT_BAR_HEIGHT;
        barOffsetDp = DEFAULT_BAR_OFFSET;
        immersiveGestureNavigation = DEFAULT_IMMERSIVE;
        fitTabs = DEFAULT_FIT_TABS;
    }

    private static int parseColor(String raw, int fallback) {
        try {
            if (raw != null && !raw.trim().isEmpty()) {
                return Color.parseColor(raw.trim());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            if (raw != null && !raw.trim().isEmpty()) {
                return Integer.parseInt(raw.trim());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static String formatColor(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }
}
