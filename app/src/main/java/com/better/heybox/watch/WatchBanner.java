package com.better.heybox.watch;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 应用内横幅提醒（自绘，不依赖宿主内部组件）。
 *
 * <p>挂在 Activity 的 content 根布局上，顶部滑入、3.5 秒后自动消失，点击可打开帖子。
 * 多条提醒<b>排队依次显示</b>（最多排 3 条），避免同时炸出一堆；显示中的横幅不会被
 * 前一条的定时器误关（定时器与具体 View 绑定）。
 *
 * <p>之所以自绘而不是反射宿主的 {@code InAppNotificationManager}：后者的配置类被混淆
 * （{@code a}/{@code q}），跨版本极易失效；本模块已有大量自绘 UI 先例（液态玻璃等）。
 */
public final class WatchBanner {

    private static final long AUTO_DISMISS_MS = 3500L;
    private static final int MAX_QUEUE = 3;
    private static final Object LOCK = new Object();

    private static final class Pending {
        final WeakReference<Activity> activity;
        final String title;
        final String sub;
        final Runnable onClick;

        Pending(Activity a, String title, String sub, Runnable onClick) {
            this.activity = new WeakReference<>(a);
            this.title = title;
            this.sub = sub;
            this.onClick = onClick;
        }
    }

    private static final Deque<Pending> QUEUE = new ArrayDeque<>();
    private static boolean sShowing;
    private static WeakReference<View> sCurrent = new WeakReference<>(null);

    private WatchBanner() {
    }

    public static void show(Activity activity, WatchItem item, Runnable onClick) {
        if (activity == null || item == null) {
            return;
        }
        enqueue(activity, "🔔 " + item.displayTitle(), item.displayText().replace('\n', ' '), onClick);
    }

    /** 汇总横幅（一批多条时用一条横幅代替刷屏） */
    public static void showSummary(Activity activity, String title, String sub, Runnable onClick) {
        if (activity == null) {
            return;
        }
        enqueue(activity, title, sub, onClick);
    }

    private static void enqueue(Activity activity, String title, String sub, Runnable onClick) {
        synchronized (LOCK) {
            if (QUEUE.size() >= MAX_QUEUE) {
                QUEUE.pollFirst();
            }
            QUEUE.addLast(new Pending(activity, title, sub, onClick));
        }
        pump();
    }

    /** 取队首显示（空闲时） */
    private static void pump() {
        Pending next = null;
        synchronized (LOCK) {
            if (sShowing) {
                return;
            }
            while (!QUEUE.isEmpty()) {
                Pending p = QUEUE.pollFirst();
                if (p.activity.get() != null) {
                    next = p;
                    break;
                }
            }
            if (next == null) {
                return;
            }
            sShowing = true;
        }
        final Pending p = next;
        final Activity activity = p.activity.get();
        if (activity == null) {
            finishOne();
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                showInternal(activity, p);
            } catch (Throwable t) {
                finishOne();
            }
        });
    }

    private static void finishOne() {
        synchronized (LOCK) {
            sShowing = false;
        }
        pump();
    }

    private static void showInternal(Activity activity, Pending p) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            finishOne();
            return;
        }
        int pad = dp(activity, 12);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2202124"));
        bg.setCornerRadius(dp(activity, 14));
        bg.setStroke(dp(activity, 1), Color.parseColor("#33FFFFFF"));
        card.setBackground(bg);
        card.setElevation(dp(activity, 6));

        TextView title = new TextView(activity);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setMaxLines(2);
        title.setText(p.title);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (p.sub != null && !p.sub.isEmpty()) {
            TextView sub = new TextView(activity);
            sub.setTextColor(Color.parseColor("#B3FFFFFF"));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setMaxLines(2);
            sub.setText(p.sub);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(activity, 2);
            card.addView(sub, subLp);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        lp.setMargins(pad, dp(activity, 24), pad, 0);
        card.setLayoutParams(lp);
        card.setAlpha(0f);
        card.setTranslationY(-dp(activity, 24));

        card.setOnClickListener(v -> {
            dismiss(card);
            if (p.onClick != null) {
                try {
                    p.onClick.run();
                } catch (Throwable ignored) {
                }
            }
        });

        root.addView(card);
        sCurrent = new WeakReference<>(card);
        card.animate().alpha(1f).translationY(0f).setDuration(180L).start();
        // 定时器与具体 View 绑定：只有它仍是当前横幅时才关闭，避免误关后一条
        card.postDelayed(() -> {
            if (sCurrent.get() == card) {
                dismiss(card);
            }
        }, AUTO_DISMISS_MS);
    }

    /** 关闭指定横幅；关完继续显示队列里的下一条 */
    private static void dismiss(final View v) {
        if (v == null) {
            return;
        }
        sCurrent = new WeakReference<>(null);
        v.post(() -> {
            try {
                v.animate().alpha(0f).translationY(-v.getHeight()).setDuration(160L)
                        .withEndAction(() -> {
                            detach(v);
                            finishOne();
                        }).start();
            } catch (Throwable t) {
                detach(v);
                finishOne();
            }
        });
    }

    private static void detach(View v) {
        try {
            ViewGroup parent = (ViewGroup) v.getParent();
            if (parent != null) {
                parent.removeView(v);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 立即清空队列并关闭当前横幅（切换页面/手动关闭时用） */
    public static void clear() {
        synchronized (LOCK) {
            QUEUE.clear();
        }
        View v = sCurrent.get();
        if (v != null) {
            dismiss(v);
        } else {
            finishOne();
        }
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
