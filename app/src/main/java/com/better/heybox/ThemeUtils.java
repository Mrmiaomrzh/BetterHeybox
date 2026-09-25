package com.better.heybox;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.util.TypedValue;

public final class ThemeUtils {

    public static final int FALLBACK_ACCENT = 0xFF1677FF;

    public static final int RADIUS_SHEET_DP = 28;
    public static final int RADIUS_BUTTON_DP = 16;
    public static final int RADIUS_ITEM_DP = 16;
    public static final int RADIUS_SMALL_DP = 12;

    public static final long ANIM_SHEET_IN_MS = 220;
    public static final long ANIM_SCRIM_IN_MS = 150;
    public static final long ANIM_PRESS_MS = 80;
    public static final long ANIM_STATE_MS = 150;

    private ThemeUtils() {
    }

    public static int resolveAccent(Context context) {
        return systemShade(context, "system_accent1_", FALLBACK_ACCENT);
    }

    public static int resolveAccent2(Context context) {
        return systemShade(context, "system_accent2_", resolveAccent(context));
    }

    public static int resolveAccentStrong(Context context) {
        return systemShadeReversed(context, "system_accent1_", resolveAccent(context));
    }

    public static int resolveAccent2Strong(Context context) {
        return systemShadeReversed(context, "system_accent2_", resolveAccentStrong(context));
    }

    private static int systemShadeReversed(Context context, String family, int fallback) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                String shade = isDarkMode(context) ? "200" : "600";
                int resId = context.getResources().getIdentifier(
                        family + shade, "color", "android");
                if (resId != 0) {
                    int color = context.getColor(resId);
                    if (Color.alpha(color) == 255) {
                        return color;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public static int surfaceColor(Context context) {
        return isDarkMode(context)
                ? systemShade(context, "system_neutral1_900", 0xFF1C1C1E)
                : systemShade(context, "system_neutral1_50", 0xFFFFFFFF);
    }

    public static int surfaceVariantColor(Context context) {
        return isDarkMode(context)
                ? systemShade(context, "system_neutral2_800", 0xFF2C2C2E)
                : systemShade(context, "system_neutral2_100", 0xFFF2F2F7);
    }

    public static int outlineColor(Context context) {
        if (isDarkMode(context)) {
            return withAlpha(systemShade(context, "system_neutral2_700", 0xFFFFFFFF), 0x2E);
        }
        return withAlpha(systemShade(context, "system_neutral2_200", 0xFF000000), 0x1F);
    }

    public static int textPrimaryColor(Context context) {
        return isDarkMode(context)
                ? systemShade(context, "system_neutral1_50", 0xDEFFFFFF)
                : systemShade(context, "system_neutral1_900", 0xDD000000);
    }

    public static int textSecondaryColor(Context context) {
        return withAlpha(textPrimaryColor(context), 0x99);
    }

    private static int systemShade(Context context, String family, int fallback) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                String shade = isDarkMode(context) ? "600" : "200";
                int resId = context.getResources().getIdentifier(
                        family + shade, "color", "android");
                if (resId != 0) {
                    int color = context.getColor(resId);
                    if (Color.alpha(color) == 255) {
                        return color;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (family.startsWith("system_accent")) {
            try {
                TypedValue value = new TypedValue();
                if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)
                        && value.data != 0) {
                    return value.data;
                }
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    public static boolean isDarkMode(Context context) {
        int night = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int readableForegroundOn(int backgroundColor) {
        double luminance = (0.2126 * linear(Color.red(backgroundColor))
                + 0.7152 * linear(Color.green(backgroundColor))
                + 0.0722 * linear(Color.blue(backgroundColor)));
        return luminance > 0.45 ? Color.BLACK : Color.WHITE;
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
