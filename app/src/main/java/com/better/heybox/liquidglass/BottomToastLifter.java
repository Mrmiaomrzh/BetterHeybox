package com.better.heybox.liquidglass;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * 底部弹层抬升：底部 Toast / 应用内通知（Notification 组件）在玻璃栏上方 12dp 让位。
 * 三处 addView/setView 钩点加窗口级 y 调整，窗内 inflate 的提示布局走 translationY
 */
public final class BottomToastLifter {

    private static final String NOTIFICATION_VIEW =
            "com.max.hbcommon.component.inappnotification.Notification";
    private static final float GAP_DP = 12f;
    private static final int MAX_FIXES = 10;

    private BottomToastLifter() {
    }

    public static void install() {
        boolean armed = arm("android.view.WindowManagerImpl", false);
        armed |= arm("android.view.WindowManagerGlobal", false);
        armed |= arm("android.view.ViewRootImpl", true);
        if (!armed) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "toast lifter: no addView hook point available");
        }
        armDiagProbes();
        armInflateProbe();
    }

    private static boolean arm(String className, boolean setView) {
        try {
            Class<?> impl = Class.forName(className);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : impl.getDeclaredMethods()) {
                if (setView) {
                    if (!m.getName().equals("setView")
                            || m.getParameterTypes().length < 3
                            || m.getParameterTypes()[0] != View.class
                            || m.getParameterTypes()[1]
                                    != WindowManager.LayoutParams.class) {
                        continue;
                    }
                } else {
                    if (!m.getName().equals("addView")
                            || m.getParameterTypes().length < 2
                            || m.getParameterTypes()[0] != View.class
                            || m.getParameterTypes()[1]
                                    != ViewGroup.LayoutParams.class) {
                        continue;
                    }
                }
                target = m;
                break;
            }
            if (target == null) {
                throw new NoSuchMethodException(className + " target method");
            }
            LiquidGlassHookBridge.hookExecutable(target, chain -> {
                try {
                    preLift(chain.getArg(0), chain.getArg(1));
                } catch (Throwable ignored) {
                }
                Object result = chain.proceed();
                try {
                    watchAfterAdd(chain.getArg(0), chain.getArg(1));
                } catch (Throwable ignored) {
                }
                return result;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "bottom toast lifter armed on " + className);
            return true;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("arm " + className + " failed", t);
            return false;
        }
    }

    private static void preLift(Object viewObj, Object paramsObj) {
        if (!(viewObj instanceof View)
                || !(paramsObj instanceof WindowManager.LayoutParams)) {
            return;
        }
        View view = (View) viewObj;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) paramsObj;
        boolean inApp = isBottomInAppNotification(view, lp);
        boolean toast = lp.type == WindowManager.LayoutParams.TYPE_TOAST
                && (lp.gravity & android.view.Gravity.BOTTOM)
                        == android.view.Gravity.BOTTOM;
        if (!inApp && !toast) {
            return;
        }
        int lift = windowLift(view, inApp);
        if (lift <= 0) {
            return;
        }
        if (lp.y >= lift) {
            return;
        }
        lp.y = lift;
        LiquidGlassLog.log(android.util.Log.INFO,
                "window notification preLift y=" + lift);
    }

    private static void watchAfterAdd(Object viewObj, Object paramsObj) {
        if (!(viewObj instanceof View)
                || !(paramsObj instanceof WindowManager.LayoutParams)) {
            return;
        }
        final View view = (View) viewObj;
        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) paramsObj;
        final boolean inApp = isBottomInAppNotification(view, lp);
        final boolean toast = lp.type == WindowManager.LayoutParams.TYPE_TOAST;
        if (!inApp && !toast) {
            return;
        }
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private int fixes;

            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or2, int ob) {
                if (fixes >= MAX_FIXES) {
                    v.removeOnLayoutChangeListener(this);
                    return;
                }
                try {
                    if (alignAboveGlass(v, inApp)) {
                        fixes++;
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static boolean isBottomInAppNotification(View view,
                                                     WindowManager.LayoutParams lp) {
        return NOTIFICATION_VIEW.equals(view.getClass().getName())
                && lp.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                && (lp.gravity & android.view.Gravity.BOTTOM)
                        == android.view.Gravity.BOTTOM;
    }

    private static boolean alignAboveGlass(View container, boolean inApp) {
        View host = LiquidGlassInstaller.activeGlassHost();
        if (host == null || !container.isAttachedToWindow()
                || container.getWidth() <= 0 || container.getHeight() <= 0) {
            return false;
        }
        View[] pill = new View[1];
        int[] pillTop = new int[1];
        View[] leaf = new View[1];
        int[] leafTop = new int[1];
        findPill(container, 0, pill, pillTop, leaf, leafTop);
        if (pill[0] == null) {
            pill[0] = leaf[0];
            pillTop[0] = leafTop[0];
        }
        if (pill[0] == null || pill[0].getHeight() <= 0) {
            return false;
        }
        int[] containerLoc = new int[2];
        container.getLocationOnScreen(containerLoc);
        int pillBottom = containerLoc[1] + pillTop[0] + pill[0].getHeight();
        int[] hostLoc = new int[2];
        host.getLocationOnScreen(hostLoc);
        int target = hostLoc[1] - gapPx(host);
        int delta = target - pillBottom;
        if (Math.abs(delta) < 1) {
            return false;
        }
        if (!inApp && delta >= 0) {
            return false;
        }
        if (!applyWindowLift(container, -delta)) {
            return false;
        }
        LiquidGlassLog.log(android.util.Log.INFO,
                "bottom notification aligned, delta=" + delta);
        return true;
    }

    private static boolean applyWindowLift(View container, int upwardShift) {
        try {
            ViewGroup.LayoutParams cur = container.getLayoutParams();
            if (!(cur instanceof WindowManager.LayoutParams)) {
                return false;
            }
            WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) cur;
            int newY;
            if ((wlp.gravity & android.view.Gravity.BOTTOM)
                    == android.view.Gravity.BOTTOM) {
                newY = Math.max(wlp.y + upwardShift, 0);
            } else {
                newY = wlp.y - upwardShift;
            }
            if (newY == wlp.y) {
                return false;
            }
            wlp.y = newY;
            Object wm = container.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (!(wm instanceof WindowManager)) {
                return false;
            }
            ((WindowManager) wm).updateViewLayout(container, wlp);
            return true;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("window lift failed", t);
            return false;
        }
    }

    /** 找弹层里带背景的最小"药丸"（真正的提示气泡），没有就退到最深的叶子节点 */
    private static void findPill(View view, int top,
                                 View[] pill, int[] pillTop,
                                 View[] leaf, int[] leafTop) {
        if (view.getVisibility() == View.GONE) {
            return;
        }
        boolean backgrounded = view.getBackground() != null;
        int bottom = top + view.getHeight();
        if (backgrounded) {
            if (pill[0] == null || view.getHeight() < pill[0].getHeight()
                    || (view.getHeight() == pill[0].getHeight()
                            && bottom > pillTop[0] + pill[0].getHeight())) {
                pill[0] = view;
                pillTop[0] = top;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                findPill(child, top + child.getTop(), pill, pillTop, leaf, leafTop);
            }
        } else if (leaf[0] == null || bottom > leafTop[0] + leaf[0].getHeight()) {
            leaf[0] = view;
            leafTop[0] = top;
        }
    }

    private static int windowLift(View view, boolean inApp) {
        View host = LiquidGlassInstaller.activeGlassHost();
        if (host == null) {
            return 0;
        }
        int[] hostLoc = new int[2];
        host.getLocationOnScreen(hostLoc);
        int anchorBottom = anchorBottom(view, inApp);
        if (anchorBottom <= 0) {
            return 0;
        }
        return Math.max(anchorBottom - hostLoc[1] + gapPx(host), 0);
    }

    private static int anchorBottom(View view, boolean inApp) {
        if (inApp) {
            Activity activity = activityOf(view.getContext());
            if (activity == null || activity.getWindow() == null) {
                return 0;
            }
            View decor = activity.getWindow().getDecorView();
            if (decor.getHeight() <= 0) {
                return 0;
            }
            int[] decorLoc = new int[2];
            decor.getLocationOnScreen(decorLoc);
            return decorLoc[1] + decor.getHeight();
        }
        return realDisplayBottom(view.getContext());
    }

    private static int realDisplayBottom(Context context) {
        try {
            Object wm = context.getSystemService(Context.WINDOW_SERVICE);
            if (!(wm instanceof WindowManager)) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return ((WindowManager) wm).getMaximumWindowMetrics()
                        .getBounds().bottom;
            }
            Display display = ((WindowManager) wm).getDefaultDisplay();
            if (display == null) {
                return 0;
            }
            DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            return dm.heightPixels;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int gapPx(View host) {
        return Math.round(host.getResources().getDisplayMetrics().density * GAP_DP);
    }

    private static Activity activityOf(Context context) {
        Context cur = context;
        int hops = 0;
        while (cur != null && hops < 10) {
            if (cur instanceof Activity) {
                return (Activity) cur;
            }
            if (!(cur instanceof ContextWrapper)) {
                return null;
            }
            cur = ((ContextWrapper) cur).getBaseContext();
            hops++;
        }
        return null;
    }

    /** 诊断探针：Toast.show / 应用内通知构造打点，排"抬升没生效"时看类型 */
    private static void armDiagProbes() {
        try {
            java.lang.reflect.Method show = Class.forName("android.widget.Toast")
                    .getMethod("show");
            LiquidGlassHookBridge.hookExecutable(show, chain -> {
                Object result = chain.proceed();
                try {
                    Object thiz = chain.getThisObject();
                    if (thiz instanceof android.widget.Toast) {
                        View v = ((android.widget.Toast) thiz).getView();
                        LiquidGlassLog.log(android.util.Log.INFO,
                                "toast shown: "
                                        + (v == null ? "text" : v.getClass().getName()));
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> ntf = Class.forName(NOTIFICATION_VIEW);
            for (java.lang.reflect.Constructor<?> c : ntf.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length == 3 && ps[0] == Context.class) {
                    LiquidGlassHookBridge.hookExecutable(c, chain -> {
                        chain.proceed();
                        try {
                            LiquidGlassLog.log(android.util.Log.INFO,
                                    "in-app notification created");
                        } catch (Throwable ignored) {
                        }
                        return null;
                    });
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile int sToastLayoutId1;
    private static volatile int sToastLayoutId2;
    private static volatile int sResolveAttempts;
    private static final String[] TOAST_LAYOUTS = {
            "layout_toast_click_bottom_hint", "toast_bottom_hint",
    };

    /** 窗内 inflate 的底部提示布局：预解析宿主 toast 布局 id，命中才挂布局监听 */
    private static void armInflateProbe() {
        try {
            java.lang.reflect.Method inflate = android.view.LayoutInflater.class
                    .getMethod("inflate", int.class, ViewGroup.class, boolean.class);
            LiquidGlassHookBridge.hookExecutable(inflate, chain -> {
                try {
                    int resId = (Integer) chain.getArg(0);
                    if (sToastLayoutId1 == 0 && sToastLayoutId2 == 0
                            && sResolveAttempts < 5) {
                        resolveToastLayoutIds(chain.getThisObject());
                    }
                    if (resId != 0
                            && (resId == sToastLayoutId1
                            || resId == sToastLayoutId2)) {
                        Object result = chain.proceed();
                        try {
                            watchInflatedToast(result);
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
    }

    private static void resolveToastLayoutIds(Object inflaterObj) {
        sResolveAttempts++;
        try {
            if (!(inflaterObj instanceof android.view.LayoutInflater)) {
                return;
            }
            Context ctx = ((android.view.LayoutInflater) inflaterObj).getContext();
            if (ctx == null) {
                return;
            }
            for (String name : TOAST_LAYOUTS) {
                int id = ctx.getResources().getIdentifier(name, "layout",
                        ctx.getPackageName());
                if (id != 0 && sToastLayoutId1 == 0) {
                    sToastLayoutId1 = id;
                } else if (id != 0 && id != sToastLayoutId1 && sToastLayoutId2 == 0) {
                    sToastLayoutId2 = id;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void watchInflatedToast(Object viewObj) {
        if (!(viewObj instanceof View)) {
            return;
        }
        final View view = (View) viewObj;
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or2, int ob) {
                try {
                    if (v.getVisibility() == View.VISIBLE
                            && v.isAttachedToWindow()
                            && v.getWidth() > 0 && v.getHeight() > 0) {
                        liftInWindow(v);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void liftInWindow(View container) {
        View host = LiquidGlassInstaller.activeGlassHost();
        if (host == null || container.getWidth() <= 0 || container.getHeight() <= 0) {
            return;
        }
        View[] pill = new View[1];
        int[] pillTop = new int[1];
        View[] leaf = new View[1];
        int[] leafTop = new int[1];
        findPill(container, 0, pill, pillTop, leaf, leafTop);
        if (pill[0] == null) {
            pill[0] = leaf[0];
            pillTop[0] = leafTop[0];
        }
        if (pill[0] == null || pill[0].getHeight() <= 0) {
            return;
        }
        int[] containerLoc = new int[2];
        container.getLocationOnScreen(containerLoc);
        int pillBottom = containerLoc[1] + pillTop[0] + pill[0].getHeight();
        int[] hostLoc = new int[2];
        host.getLocationOnScreen(hostLoc);
        int target = hostLoc[1] - gapPx(host);
        int delta = target - pillBottom;
        if (delta > -1) {
            return;
        }
        if (Math.abs(delta - pill[0].getTranslationY()) < 1f) {
            return;
        }
        pill[0].setTranslationY(delta);
    }
}
