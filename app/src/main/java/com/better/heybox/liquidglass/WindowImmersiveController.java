package com.better.heybox.liquidglass;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

final class WindowImmersiveController {

    private static final Map<Activity, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<Activity, State>());
    private static final Map<Activity, Boolean> ACTIVITIES =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());

    private WindowImmersiveController() {
    }
    static void refresh() {
        ArrayList<Activity> activities;
        synchronized (ACTIVITIES) {
            activities = new ArrayList<>(ACTIVITIES.keySet());
        }
        for (Activity activity : activities) {
            if (activity != null && !activity.isFinishing()
                    && !activity.isDestroyed()) {
                apply(activity);
            }
        }
    }

    static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        ACTIVITIES.put(activity, Boolean.TRUE);
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            if (!GlassConfig.immersiveGestureNavigation) {
                restore(activity, window);
                return;
            }
            State state = STATES.get(activity);
            boolean fresh = state == null;
            if (fresh) {
                state = new State(window);
                STATES.put(activity, state);
            }
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(state.systemUiVisibility
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            View content = decor == null
                    ? null : decor.findViewById(android.R.id.content);
            if (content != null) {
                content.setOnApplyWindowInsetsListener(DROP_NAV_INSET);
                if (fresh) {
                    content.requestApplyInsets();
                }
            }
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(Color.TRANSPARENT);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false);
            }
            window.setFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        } catch (Throwable t) {
            LiquidGlassLog.logErr(
                    WindowImmersiveController.class.getSimpleName(), t);
        }
    }

    private static void restore(Activity activity, Window window) {
        State state = STATES.remove(activity);
        if (state == null) {
            return;
        }
        try {
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(state.systemUiVisibility);
                View content = decor.findViewById(android.R.id.content);
                if (content != null) {
                    content.setOnApplyWindowInsetsListener(null);
                    content.requestApplyInsets();
                }
            }
            if (state.drawsSystemBarBackgrounds) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            }
            if (state.translucentNavigation) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            }
            window.setNavigationBarColor(state.navigationBarColor);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(state.navigationBarDividerColor);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(state.navigationBarContrastEnforced);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr(
                    WindowImmersiveController.class.getSimpleName(), t);
        }
    }

    /**
 * 仅清零导航栏 inset，保留状态栏 inset 供标题栏使用。
 * 不能用 setDecorFitsSystemWindows(false)：会连顶部一起丢，导致搜索/消息页标题栏塌陷
 */
    private static final View.OnApplyWindowInsetsListener DROP_NAV_INSET =
            new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v,
                                                        WindowInsets insets) {
                    return v.onApplyWindowInsets(withoutNavInset(insets));
                }
            };

    private static WindowInsets withoutNavInset(WindowInsets insets) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return withoutNavInsetApi30(insets);
            }
            return insets.replaceSystemWindowInsets(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), 0);
        } catch (Throwable t) {
            return insets;
        }
    }

    private static WindowInsets withoutNavInsetApi30(WindowInsets insets) {
        Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
        return new WindowInsets.Builder(insets)
                .setInsets(WindowInsets.Type.navigationBars(),
                        Insets.of(nav.left, nav.top, nav.right, 0))
                .build();
    }

    private static final class State {
        final int systemUiVisibility;
        final int navigationBarColor;
        final int navigationBarDividerColor;
        final boolean navigationBarContrastEnforced;
        final boolean drawsSystemBarBackgrounds;
        final boolean translucentNavigation;

        State(Window window) {
            View decor = window.getDecorView();
            systemUiVisibility = decor == null ? 0 : decor.getSystemUiVisibility();
            navigationBarColor = window.getNavigationBarColor();
            navigationBarDividerColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? window.getNavigationBarDividerColor() : Color.TRANSPARENT;
            navigationBarContrastEnforced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && window.isNavigationBarContrastEnforced();
            int flags = window.getAttributes().flags;
            drawsSystemBarBackgrounds = (flags & WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
            translucentNavigation = (flags & WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION) != 0;
        }
    }
}
