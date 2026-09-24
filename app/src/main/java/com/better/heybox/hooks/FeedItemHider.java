package com.better.heybox.hooks;

import android.view.View;
import android.view.ViewGroup;

import java.util.WeakHashMap;

/** 信息流条目隐藏与复用恢复 */
public final class FeedItemHider {

    /** 被隐藏 itemView 的原始高度，WeakHashMap 防泄漏 */
    private static final WeakHashMap<View, Integer> HIDDEN_HEIGHTS = new WeakHashMap<>();

    private FeedItemHider() {
    }

    public static View getItemView(Object viewHolder) {
        try {
            Object v = viewHolder.getClass().getField("itemView").get(viewHolder);
            return v instanceof View ? (View) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static View topLevel(View view) {
        View current = view;
        for (int depth = 0; depth < 16 && current != null; depth++) {
            android.view.ViewParent parent = current.getParent();
            if (!(parent instanceof ViewGroup)) {
                return current;
            }
            if (isRecyclerView(parent)) {
                return current;
            }
            current = (View) parent;
        }
        return current;
    }

    private static boolean isRecyclerView(android.view.ViewParent parent) {
        for (Class<?> c = parent.getClass(); c != null; c = c.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    public static void hide(View itemView) {
        try {
            if (itemView == null) {
                return;
            }
            if (itemView.getVisibility() == View.GONE && HIDDEN_HEIGHTS.containsKey(itemView)) {
                return;
            }
            if (!HIDDEN_HEIGHTS.containsKey(itemView)) {
                ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                HIDDEN_HEIGHTS.put(itemView, lp != null ? lp.height : null);
            }
            itemView.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                itemView.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void restore(Object viewHolder) {
        restore(getItemView(viewHolder));
    }

    public static void restore(View itemView) {
        if (itemView == null || !HIDDEN_HEIGHTS.containsKey(itemView)) {
            return;
        }
        Integer height = HIDDEN_HEIGHTS.remove(itemView);
        itemView.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = itemView.getLayoutParams();
        if (lp != null && height != null) {
            lp.height = height;
            itemView.setLayoutParams(lp);
        }
    }
}
