package com.better.heybox.hooks;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** 信息流条目隐藏与复用恢复 */
public final class FeedItemHider {

    /** 被隐藏 itemView 的原始高度，WeakHashMap 防泄漏 */
    private static final WeakHashMap<View, Integer> HIDDEN_HEIGHTS = new WeakHashMap<>();

    private static final ConcurrentHashMap<Class<?>, Object> ITEM_VIEW_FIELDS =
            new ConcurrentHashMap<>();
    private static final Object NO_FIELD = new Object();

    private static final ConcurrentHashMap<Class<?>, Boolean> RECYCLER_VIEW_CLASSES =
            new ConcurrentHashMap<>();

    private FeedItemHider() {
    }

    public static View getItemView(Object viewHolder) {
        if (viewHolder == null) {
            return null;
        }
        try {
            Class<?> cls = viewHolder.getClass();
            Object cached = ITEM_VIEW_FIELDS.get(cls);
            if (cached == null) {
                Field found = null;
                try {
                    found = cls.getField("itemView");
                } catch (Throwable ignored) {
                }
                Object prev = ITEM_VIEW_FIELDS.putIfAbsent(cls, found == null ? NO_FIELD : found);
                cached = prev != null ? prev : (found == null ? NO_FIELD : found);
            }
            if (cached == NO_FIELD) {
                return null;
            }
            Object v = ((Field) cached).get(viewHolder);
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
        Class<?> cls = parent.getClass();
        Boolean cached = RECYCLER_VIEW_CLASSES.get(cls);
        if (cached != null) {
            return cached;
        }
        boolean found = false;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) {
                found = true;
                break;
            }
        }
        RECYCLER_VIEW_CLASSES.put(cls, found);
        return found;
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
            com.better.heybox.ModuleStats.bbsListItemsHidden.incrementAndGet();
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
