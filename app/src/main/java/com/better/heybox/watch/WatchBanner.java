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

/**
 * 应用内横幅提醒（自绘，不依赖宿主内部组件）。
 *
 * <p>挂在 Activity 的 content 根布局上，顶部滑入、3.5 秒后自动消失，点击可打开帖子。
 * 之所以自绘而不是反射宿主的 {@code InAppNotificationManager}：后者的配置类被混淆
 * （{@code a}/{@code q}），跨版本极易失效；本模块已有大量自绘 UI 先例（液态玻璃等），
 * 自绘更稳。启动时若观测到宿主模板，可在日志里对照调整观感。
 */
public final class WatchBanner {

    private static final long AUTO_DISMISS_MS = 3500L;
    private static final String TAG_VIEW = "betterheybox_watch_banner";

    private static WeakReference<View> sCurrent = new WeakReference<>(null);

    private WatchBanner() {
    }

    public static void show(final Activity activity, final WatchItem item, final Runnable onClick) {
        if (activity == null || item == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                showInternal(activity, item, onClick);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void showInternal(final Activity activity, final WatchItem item, final Runnable onClick) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        dismiss();

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
        title.setText("🔔 " + item.displayTitle());
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(activity);
        sub.setTextColor(Color.parseColor("#B3FFFFFF"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setMaxLines(2);
        sub.setText(item.displayText().replace('\n', ' '));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(activity, 2);
        card.addView(sub, subLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        lp.setMargins(pad, dp(activity, 24), pad, 0);
        card.setLayoutParams(lp);
        card.setAlpha(0f);
        card.setTranslationY(-dp(activity, 24));

        card.setOnClickListener(v -> {
            dismiss();
            if (onClick != null) {
                onClick.run();
            }
        });

        root.addView(card);
        sCurrent = new WeakReference<>(card);
        card.animate().alpha(1f).translationY(0f).setDuration(180L).start();
        card.postDelayed(WatchBanner::dismiss, AUTO_DISMISS_MS);
    }

    public static void dismiss() {
        final View v = sCurrent.get();
        sCurrent = new WeakReference<>(null);
        if (v == null) {
            return;
        }
        v.post(() -> {
            try {
                v.animate().alpha(0f).translationY(-v.getHeight()).setDuration(160L)
                        .withEndAction(() -> {
                            ViewGroup parent = (ViewGroup) v.getParent();
                            if (parent != null) {
                                parent.removeView(v);
                            }
                        }).start();
            } catch (Throwable t) {
                ViewGroup parent = (ViewGroup) v.getParent();
                if (parent != null) {
                    parent.removeView(v);
                }
            }
        });
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
