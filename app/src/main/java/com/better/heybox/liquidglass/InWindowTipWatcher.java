package com.better.heybox.liquidglass;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

final class InWindowTipWatcher {

    private static final String TIP_ID_NAME = "vg_update_tips";
    private static final long SCAN_INTERVAL_MS = 2000L;
    private static final java.lang.ref.WeakReference<View> EMPTY_DECOR_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<View> sWatchedDecor =
            EMPTY_DECOR_REF;
    private static volatile int sTipId;
    private static final java.lang.ref.WeakReference<View> EMPTY_TIP_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<View> sTipRef =
            EMPTY_TIP_REF;
    private static final java.util.Map<View, Boolean> sWatched =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());

    private InWindowTipWatcher() {
    }

    static void start(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            final View decor = activity.getWindow().getDecorView();
            if (decor == null || sWatchedDecor.get() == decor) {
                return;
            }
            sWatchedDecor = new java.lang.ref.WeakReference<>(decor);
            sTipId = activity.getResources().getIdentifier(
                    TIP_ID_NAME, "id", activity.getPackageName());
            if (sTipId == 0) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "in-window tip id not found: " + TIP_ID_NAME);
                return;
            }
            sTipRef = EMPTY_TIP_REF;
            scan(decor);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                if (activity.isFinishing()
                                        || activity.isDestroyed()) {
                                    android.view.ViewTreeObserver vto =
                                            decor.getViewTreeObserver();
                                    if (vto != null && vto.isAlive()) {
                                        vto.removeOnGlobalLayoutListener(this);
                                    }
                                    return;
                                }
                                View known = sTipRef.get();
                                if (known != null && known.isAttachedToWindow()) {
                                    return;
                                }
                                scan(decor);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        scan(decor);
                    } catch (Throwable ignored) {
                    }
                    decor.postDelayed(this, SCAN_INTERVAL_MS);
                }
            }, SCAN_INTERVAL_MS);
            LiquidGlassLog.log(android.util.Log.INFO,
                    "in-window tip watcher started id=" + sTipId);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("in-window tip watcher failed", t);
        }
    }

    private static void scan(View view) {
        if (view.getId() == sTipId) {
            watch(view);
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            scan(group.getChildAt(i));
        }
    }

    private static void watch(final View tip) {
        sTipRef = new java.lang.ref.WeakReference<>(tip);
        if (sWatched.put(tip, Boolean.TRUE) != null) {
            return;
        }
        tip.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (tip.getVisibility() == View.VISIBLE
                                    && tip.isAttachedToWindow()
                                    && tip.getWidth() > 0
                                    && tip.getHeight() > 0) {
                                liftAboveGlass(tip);
                            }
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                });
        LiquidGlassLog.log(android.util.Log.INFO,
                "update tips view watched: " + tip.getResources()
                        .getResourceName(sTipId));
    }

    private static final java.util.Map<View, float[]> sLiftState =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, float[]>());

    private static void liftAboveGlass(View tip) {
        View host = LiquidGlassInstaller.activeGlassHost();
        if (host == null) {
            return;
        }
        int bottom = layoutBottomOnScreen(tip);
        if (bottom <= 0) {
            return;
        }
        int[] hostLoc = new int[2];
        host.getLocationOnScreen(hostLoc);
        int target = hostLoc[1] - Math.round(host.getResources()
                .getDisplayMetrics().density * 12f);
        float lift = Math.min(target - bottom, 0f);
        float[] st = sLiftState.get(tip);
        if (st == null) {
            st = new float[]{Float.NaN};
            sLiftState.put(tip, st);
        }
        float current = tip.getTranslationY();
        float want;
        if (!Float.isNaN(st[0]) && Math.abs(current - st[0]) < 0.5f) {
            want = Math.abs(st[0] - lift) >= 0.5f ? lift : st[0];
        } else {
            want = current + lift;
        }
        st[0] = want;
        if (Math.abs(want - current) >= 0.5f) {
            tip.setTranslationY(want);
        }
    }

    private static int layoutBottomOnScreen(View view) {
        View root = rootOf(view);
        if (root == null) {
            return 0;
        }
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        int top = 0;
        View cur = view;
        ViewParent p = view.getParent();
        int hops = 0;
        while (cur != root && p instanceof View && hops < 60) {
            top += cur.getTop();
            cur = (View) p;
            p = cur.getParent();
            hops++;
        }
        if (cur != root) {
            return 0;
        }
        return loc[1] + top + view.getHeight();
    }

    private static View rootOf(View view) {
        View cur = view;
        ViewParent p = view.getParent();
        int hops = 0;
        while (p instanceof View && hops < 60) {
            cur = (View) p;
            p = cur.getParent();
            hops++;
        }
        return cur;
    }
}
