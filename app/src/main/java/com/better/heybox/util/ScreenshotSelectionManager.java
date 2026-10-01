package com.better.heybox.util;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ScreenshotSelectionManager {

    private static final String TAG = "BetterHeybox";

    private static final int MAX_HIT_DEPTH = 32;

    private static final int MAX_WALK_DEPTH = 24;    private static final int MAX_WALK_NODES = 3000;
    private static final int MAX_UNITS = 60;
    private static final int SIGNATURE_NODES = 400;
    private static final int SIGNATURE_CHARS = 400;

    static final boolean DEBUG = false;

    public interface Listener {
        void onSelectionChanged(int count);

        void onModeChanged(boolean active);
    }

    public static final class Selection {
        public final View view;
        public final boolean comment;

        public final String signature;

        Selection(View view, boolean comment, String signature) {
            this.view = view;
            this.comment = comment;
            this.signature = signature;
        }
    }

    private static ScreenshotSelectionManager instance;

    private final Map<View, Selection> selected = new LinkedHashMap<>();
    private final Map<View, Drawable> originalForegrounds = new WeakHashMap<>();

    private final Set<View> ignored = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private WeakReference<Activity> activityRef = new WeakReference<>(null);
    private WeakReference<View> postBodyRef = new WeakReference<>(null);
    private Listener listener;
    private boolean active;
    private float downRawX;
    private float downRawY;
    private boolean moved;

    private ScreenshotSelectionManager() {
    }

    public static synchronized ScreenshotSelectionManager get() {
        if (instance == null) {
            instance = new ScreenshotSelectionManager();
        }
        return instance;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
    }

    public int count() {
        return selected.size();
    }

    public boolean isSelected(View unitView) {
        return selected.containsKey(unitView);
    }

    public void ignore(View view) {
        if (view != null) {
            ignored.add(view);
        }
    }

    public void unignore(View view) {
        if (view != null) {
            ignored.remove(view);
        }
    }

    public boolean enter(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        if (active) {
            exit();
        }
        activityRef = new WeakReference<>(activity);
        postBodyRef = new WeakReference<>(null);
        active = true;
        clearSelectionInternal();

        View body = findPostBody(activity);
        if (body != null) {
            postBodyRef = new WeakReference<>(body);
            applySelection(ScreenshotUnit.postBody(body), true);
        }
        notifyMode(true);
        notifyCount();
        return true;
    }

    public void exit() {
        if (!active && originalForegrounds.isEmpty()) {
            return;
        }
        active = false;
        for (View view : new ArrayList<>(originalForegrounds.keySet())) {
            restoreForeground(view);
        }
        clearSelectionInternal();
        activityRef = new WeakReference<>(null);
        postBodyRef = new WeakReference<>(null);
        notifyMode(false);
        notifyCount();
    }

    public void toggle(View anyViewInsideUnit) {
        ScreenshotUnit unit = ScreenshotUnit.resolve(anyViewInsideUnit);
        if (unit == null) {
            View body = postBodyOf();
            if (body != null && isDescendant(anyViewInsideUnit, body)) {
                togglePostBody(body);
            }
            return;
        }
        applySelection(unit, !selected.containsKey(unit.view));
    }

    public void togglePostBody(View body) {
        if (body != null) {
            applySelection(ScreenshotUnit.postBody(body), !selected.containsKey(body));
        }
    }

    public void toggleUnit(ScreenshotUnit unit) {
        if (unit != null) {
            boolean want = !selected.containsKey(unit.view);
            if (DEBUG) {
                Log.i(TAG, "截图触控 切换 " + unit.idName + " -> " + want);
            }
            applySelection(unit, want);
        }
    }

    public void setSelected(ScreenshotUnit unit, boolean wantSelected) {
        if (unit != null) {
            applySelection(unit, wantSelected);
        }
    }

    public boolean onActivityTouch(Activity activity, MotionEvent event) {
        if (!active || activity == null || event == null) {
            return false;
        }
        Activity current = activityRef.get();
        if (current != null && current != activity) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                moved = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!moved) {
                    float slop = touchSlop(activity);
                    if (Math.abs(event.getRawX() - downRawX) > slop
                            || Math.abs(event.getRawY() - downRawY) > slop) {
                        moved = true;
                    }
                }
                return false;
            case MotionEvent.ACTION_UP: {
                boolean tap = !moved;
                moved = false;
                if (!tap) {
                    return false;
                }
                if (isOverlayOnTop(activity, downRawX, downRawY)) {

                    if (DEBUG) {
                        Log.i(TAG, "截图触控 轻点 浮层 -> null");
                    }
                    return false;
                }
                View hit = deepestPoint(activity, downRawX, downRawY);
                ScreenshotUnit unit = pickUnit(activity, downRawX, downRawY);
                if (unit == null) {
                    View body = postBodyOf();
                    if (body != null && containsPoint(body, downRawX, downRawY)) {
                        unit = ScreenshotUnit.postBody(body);
                    }
                }
                if (DEBUG) {
                    Log.i(TAG, "截图触控 轻点 " + describe(hit) + " -> "
                            + (unit == null ? "null" : unit.idName + " comment=" + unit.comment));
                }
                if (unit == null) {
                    return false;
                }
                toggleUnit(unit);
                cancelHostGesture(activity, event);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                moved = false;
                return false;
            default:
                return false;
        }
    }

    public void selectAll() {
        Activity activity = activityRef.get();
        if (activity == null) {
            return;
        }
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int[] location = new int[2];
        for (ScreenshotUnit unit : visibleUnits(activity)) {
            try {
                unit.view.getLocationOnScreen(location);
            } catch (Throwable ignored) {
                continue;
            }
            if (location[1] > screenHeight || location[1] + unit.view.getHeight() < 0) {
                continue;
            }
            applySelection(unit, true);
        }
    }

    public void clearSelection() {
        for (View view : new ArrayList<>(selected.keySet())) {
            restoreForeground(view);
        }
        clearSelectionInternal();
        notifyCount();
    }

    public List<Selection> orderedValidSelections() {
        List<Selection> valid = new ArrayList<>();
        for (Selection selection : new ArrayList<>(selected.values())) {
            View view = selection.view;
            if (view == null || !view.isAttachedToWindow() || view.getWidth() <= 0) {
                continue;
            }
            if (view.getParent() == null) {
                continue;
            }
            String now = signature(view);
            if (selection.signature != null && !selection.signature.isEmpty()
                    && !selection.signature.equals(now)) {
                continue;
            }
            valid.add(selection);
        }
        Collections.sort(valid, new Comparator<Selection>() {
            private final int[] location = new int[2];

            @Override
            public int compare(Selection left, Selection right) {
                return Integer.compare(screenTop(left.view, location),
                        screenTop(right.view, location));
            }
        });
        return valid;
    }

    public List<ScreenshotUnit> visibleUnits(Activity activity) {
        List<ScreenshotUnit> units = new ArrayList<>();
        if (activity == null || activity.getWindow() == null) {
            return units;
        }
        List<View> seen = new ArrayList<>();
        try {
            collectUnits(activity.getWindow().getDecorView(), units, seen, 0, new int[]{0});
        } catch (Throwable throwable) {
            Log.i(TAG, "截图触控 收集单元失败: " + throwable);
        }
        View body = findPostBody(activity);
        if (body != null && !seen.contains(body)) {
            units.add(0, ScreenshotUnit.postBody(body));
        }
        final int[] location = new int[2];
        Collections.sort(units, new Comparator<ScreenshotUnit>() {
            @Override
            public int compare(ScreenshotUnit left, ScreenshotUnit right) {
                return Integer.compare(screenTop(left.view, location),
                        screenTop(right.view, location));
            }
        });
        return units;
    }

    public View findPostBody(Activity activity) {
        if (activity == null) {
            return null;
        }
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return null;
            }
            View decor = window.getDecorView();
            int bodyId = ScreenshotIds.id(activity, ScreenshotIds.POST_BODY);
            View byId = bodyId == 0 ? null : ScreenshotIds.findDescendantById(decor, bodyId);
            if (byId != null) {
                return byId;
            }
            View derived = derivePostBody(activity, decor);
            if (derived != null) {
                return derived;
            }

            return findArticleWebView(decor);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private View findArticleWebView(View decor) {
        int minHeight = (int) (decor.getResources().getDisplayMetrics().heightPixels * 0.5f);
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(decor);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 3000) {
            View current = queue.poll();
            if (isWebView(current) && current.isShown()
                    && current.getWidth() > 200 && current.getHeight() >= minHeight) {
                return current;
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return null;
    }

    public static boolean isWebView(View view) {
        if (view == null) {
            return false;
        }
        for (Class<?> type = view.getClass(); type != null && type != View.class;
                type = type.getSuperclass()) {
            if ("WebView".equals(type.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    public void logBodySearch(Activity activity) {
        if (!DEBUG || activity == null) {
            return;
        }
        try {
            Window window = activity.getWindow();
            View decor = window == null ? null : window.getDecorView();
            if (decor == null) {
                return;
            }
            int bodyId = ScreenshotIds.id(activity, ScreenshotIds.POST_BODY);
            int textId = ScreenshotIds.id(activity, ScreenshotIds.POST_TEXT);
            int titleId = ScreenshotIds.id(activity, ScreenshotIds.POST_TITLE);
            View byId = bodyId == 0 ? null : ScreenshotIds.findDescendantById(decor, bodyId);
            View text = textId == 0 ? null : ScreenshotIds.findDescendantById(decor, textId);
            View title = titleId == 0 ? null : ScreenshotIds.findDescendantById(decor, titleId);
            Log.i(TAG, "截图诊断 post_content_container(" + bodyId + ")=" + describe(byId)
                    + " tv_content(" + textId + ")=" + describe(text)
                    + " tv_title(" + titleId + ")=" + describe(title));
            if (text != null) {
                Log.i(TAG, "截图诊断 tv_content 祖先=" + ancestorChain(text));
            }
            if (title != null) {
                Log.i(TAG, "截图诊断 tv_title 祖先=" + ancestorChain(title));
            }
            Log.i(TAG, "截图诊断 滚动容器=" + scrollerList(decor));
            Log.i(TAG, "截图诊断 分页子块=" + childList(decor));
            Log.i(TAG, "截图诊断 网页视图=" + webViewList(decor));
        } catch (Throwable throwable) {
            Log.i(TAG, "截图诊断失败: " + throwable);
        }
    }

    private String webViewList(View root) {
        StringBuilder builder = new StringBuilder();
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int found = 0;
        while (!queue.isEmpty() && visited++ < 3000 && found < 4) {
            View current = queue.poll();
            if (isWebView(current)) {
                found++;
                ViewParent parent = current.getParent();
                int parentHeight = parent instanceof View ? ((View) parent).getHeight() : -1;
                builder.append(describe(current))
                        .append(" shown=").append(current.isShown())
                        .append(" w=").append(current.getWidth())
                        .append(" h=").append(current.getHeight())
                        .append(" 父h=").append(parentHeight)
                        .append(" | ");
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return builder.length() == 0 ? "无" : builder.toString();
    }

    private String childList(View root) {
        StringBuilder builder = new StringBuilder();
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int sections = 0;
        while (!queue.isEmpty() && visited++ < 3000 && sections < 4) {
            View current = queue.poll();
            if (current instanceof ViewGroup && isScrollerLike(current)
                    && ((ViewGroup) current).getChildCount() > 0
                    && current.getHeight() > 400) {
                sections++;
                builder.append('[').append(describe(current)).append("] ");
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount() && i < 14; i++) {
                    View child = group.getChildAt(i);
                    builder.append(describe(child)).append("(h=").append(child.getHeight())
                            .append(",kids=")
                            .append(child instanceof ViewGroup
                                    ? ((ViewGroup) child).getChildCount() : 0)
                            .append(") ");
                }
                builder.append(" | ");
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return builder.toString();
    }

    private String ancestorChain(View view) {
        StringBuilder builder = new StringBuilder();
        View current = view;
        for (int i = 0; i < 14 && current != null; i++) {
            if (i > 0) {
                builder.append(" < ");
            }
            builder.append(describe(current));
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return builder.toString();
    }

    private String scrollerList(View root) {
        StringBuilder builder = new StringBuilder();
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int count = 0;
        while (!queue.isEmpty() && visited++ < 3000 && count < 12) {
            View current = queue.poll();
            if (isScrollerLike(current)) {
                String adapter = "?";
                try {
                    Object value = current.getClass().getMethod("getAdapter").invoke(current);
                    adapter = value == null ? "null" : value.getClass().getSimpleName();
                } catch (Throwable ignored) {
                    adapter = "-";
                }
                builder.append(describe(current)).append("[adapter=").append(adapter)
                        .append(" children=")
                        .append(current instanceof ViewGroup
                                ? ((ViewGroup) current).getChildCount() : -1)
                        .append(" h=").append(current.getHeight()).append("] ");
                count++;
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return builder.toString();
    }

    private View postBodyOf() {
        View body = postBodyRef.get();
        if (body == null) {
            Activity activity = activityRef.get();
            if (activity != null) {
                body = findPostBody(activity);
                if (body != null) {
                    postBodyRef = new WeakReference<>(body);
                }
            }
        }
        return body;
    }

    private View derivePostBody(Activity activity, View decor) {
        int textId = ScreenshotIds.id(activity, ScreenshotIds.POST_TEXT);
        View anchor = textId == 0 ? null : ScreenshotIds.findDescendantById(decor, textId);
        if (anchor == null) {
            int titleId = ScreenshotIds.id(activity, ScreenshotIds.POST_TITLE);
            anchor = titleId == 0 ? null : ScreenshotIds.findDescendantById(decor, titleId);
        }
        if (anchor == null) {
            return null;
        }

        if (ScreenshotIds.findAncestorByName(anchor, ScreenshotIds.FEED_CARD) != null) {
            return null;
        }
        int commentId = ScreenshotIds.id(activity, ScreenshotIds.COMMENT_BLOCK);
        View best = null;
        View current = anchor;
        for (int i = 0; i < 12; i++) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            View candidate = (View) parent;
            if (candidate.getId() == android.R.id.content
                    || isScrollerLike(candidate)
                    || hasDescendantId(candidate, commentId)) {
                break;
            }
            best = candidate;
            current = candidate;
        }

        if (best != null) {
            int minHeight = (int) (decor.getResources().getDisplayMetrics().heightPixels * 0.35f);
            if (best.getHeight() < minHeight || hasRecyclerAncestor(best)) {
                if (DEBUG) {
                    Log.i(TAG, "截图诊断 正文派生被否 h=" + best.getHeight() + " 阈值=" + minHeight);
                }
                return null;
            }
        }
        return best;
    }

    private static boolean hasRecyclerAncestor(View view) {
        ViewParent parent = view.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 20) {
            View ancestor = (View) parent;
            if (isRecyclerLike(ancestor)) {
                return true;
            }
            parent = ancestor.getParent();
        }
        return false;
    }

    private static boolean isRecyclerLike(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class;
                type = type.getSuperclass()) {
            if ("RecyclerView".equals(type.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private void applySelection(ScreenshotUnit unit, boolean wantSelected) {
        if (unit == null || unit.view == null) {
            return;
        }
        if (wantSelected) {
            if (!selected.containsKey(unit.view)) {
                selected.put(unit.view, new Selection(unit.view, unit.comment, signature(unit.view)));
                applyHighlight(unit.view, true);
                notifyCount();
            }
        } else if (selected.remove(unit.view) != null) {
            restoreForeground(unit.view);
            notifyCount();
        }
    }

    private void clearSelectionInternal() {
        selected.clear();
    }

    private void notifyCount() {
        Listener current = listener;
        if (current != null) {
            current.onSelectionChanged(selected.size());
        }
    }

    private void notifyMode(boolean isActive) {
        Listener current = listener;
        if (current != null) {
            current.onModeChanged(isActive);
        }
    }

    private void applyHighlight(View view, boolean highlight) {
        try {
            if (highlight) {
                if (!originalForegrounds.containsKey(view)) {
                    originalForegrounds.put(view, view.getForeground());
                }
                view.setForeground(buildHighlight(view));
            } else {
                restoreForeground(view);
            }
        } catch (Throwable ignored) {

        }
    }

    private void restoreForeground(View view) {
        if (view == null) {
            return;
        }
        if (!originalForegrounds.containsKey(view)) {
            return;
        }
        Drawable original = originalForegrounds.remove(view);
        try {
            view.setForeground(original);
        } catch (Throwable ignored) {

        }
    }

    private Drawable buildHighlight(View view) {
        float density = view.getResources().getDisplayMetrics().density;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(0x223B82F6);
        drawable.setStroke(Math.max(2, (int) (2 * density)), 0xFF3B82F6);
        drawable.setCornerRadius(6 * density);
        return new HighlightDrawable(drawable, density, longLabel(view));
    }

    private String longLabel(View view) {
        if (!isWebView(view)) {
            return null;
        }
        int content = webContentHeight(view);
        int height = view.getHeight();
        if (content <= 0 || height <= 0 || content <= height + 32) {
            return null;
        }
        int screens = (int) Math.ceil(content / (double) height);
        return "长图 · 共 " + Math.max(2, screens) + " 屏";
    }

    private static int webContentHeight(View view) {
        try {
            Object raw = view.getClass().getMethod("getContentHeight").invoke(view);
            int height = raw instanceof Integer ? (Integer) raw : 0;
            if (height <= 0) {
                return 0;
            }
            try {
                Object scale = view.getClass().getMethod("getScale").invoke(view);
                if (scale instanceof Float) {
                    height = (int) (height * (Float) scale);
                }
            } catch (Throwable ignored) {

            }
            return height;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int screenTop(View view, int[] location) {
        try {
            view.getLocationOnScreen(location);
            return location[1];
        } catch (Throwable ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static float touchSlop(Activity activity) {
        try {
            return ViewConfiguration.get(activity).getScaledTouchSlop();
        } catch (Throwable ignored) {
            return 12f;
        }
    }

    private void cancelHostGesture(Activity activity, MotionEvent event) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            View decor = window.getDecorView();
            if (decor == null) {
                return;
            }
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            decor.dispatchTouchEvent(cancel);
            cancel.recycle();
        } catch (Throwable ignored) {

        }
    }

    private View deepestPoint(Activity activity, float rawX, float rawY) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return null;
            }
            return descendDeepest(window.getDecorView(), rawX, rawY, 0);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private ScreenshotUnit pickUnit(Activity activity, float rawX, float rawY) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return null;
            }
            return pickIn(window.getDecorView(), rawX, rawY, 0);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public boolean isDebug() {
        return DEBUG;
    }

    private boolean isOverlayOnTop(Activity activity, float rawX, float rawY) {
        return overlayContains(rawX, rawY);
    }

    public boolean overlayContains(float rawX, float rawY) {
        for (View view : new ArrayList<>(ignored)) {
            if (view == null || view.getVisibility() != View.VISIBLE
                    || view.getWidth() <= 0 || view.getHeight() <= 0) {
                continue;
            }
            if (containsPoint(view, rawX, rawY)) {
                return true;
            }
        }
        return false;
    }

    private ScreenshotUnit pickIn(View node, float rawX, float rawY, int depth) {
        if (node == null || depth > MAX_HIT_DEPTH) {
            return null;
        }
        if (!(node instanceof ViewGroup)) {
            return ScreenshotUnit.resolve(node);
        }
        ViewGroup group = (ViewGroup) node;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (!isHitCandidate(child) || !containsPoint(child, rawX, rawY)) {
                continue;
            }
            if (ignored.contains(child)) {

                return null;
            }
            ScreenshotUnit unit = pickIn(child, rawX, rawY, depth + 1);
            if (unit == null) {
                unit = ScreenshotUnit.resolveAncestors(child);
            }
            if (unit != null) {
                if (DEBUG) {
                    Log.i(TAG, "截图触控 命中 " + describe(child) + " -> " + unit.idName);
                }
                return unit;
            }
            if (DEBUG) {
                Log.i(TAG, "截图触控 跳过 " + describe(child));
            }
        }
        return null;
    }

    private View descendDeepest(View node, float rawX, float rawY, int depth) {
        if (node == null || depth > MAX_HIT_DEPTH || node.getVisibility() != View.VISIBLE
                || ignored.contains(node)) {
            return null;
        }
        if (!(node instanceof ViewGroup)) {
            return node;
        }
        ViewGroup group = (ViewGroup) node;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (!isHitCandidate(child) || !containsPoint(child, rawX, rawY)
                    || ignored.contains(child)) {
                continue;
            }
            View inner = descendDeepest(child, rawX, rawY, depth + 1);
            return inner != null ? inner : child;
        }
        return node;
    }

    private boolean isHitCandidate(View view) {
        return view != null && view.getVisibility() == View.VISIBLE
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    private boolean containsPoint(View view, float rawX, float rawY) {
        if (view == null) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX < location[0] + view.getWidth()
                && rawY >= location[1] && rawY < location[1] + view.getHeight();
    }

    private void collectUnits(View node, List<ScreenshotUnit> out, List<View> seen,
            int depth, int[] visited) {
        if (node == null || depth > MAX_WALK_DEPTH || visited[0] > MAX_WALK_NODES
                || out.size() >= MAX_UNITS) {
            return;
        }
        visited[0]++;
        if (ignored.contains(node)) {
            return;
        }
        if (node.isShown() && node.getWidth() > 0) {
            ScreenshotUnit unit = ScreenshotUnit.resolve(node);
            if (unit != null && unit.view != null && unit.view.isShown()
                    && !seen.contains(unit.view)) {
                seen.add(unit.view);
                out.add(unit);
            }
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectUnits(group.getChildAt(i), out, seen, depth + 1, visited);
            }
        }
    }

    private boolean isDescendant(View node, View ancestor) {
        if (node == null || ancestor == null) {
            return false;
        }
        View current = node;
        for (int i = 0; i < 24 && current != null; i++) {
            if (current == ancestor) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private boolean hasDescendantId(View root, int viewId) {
        if (viewId == 0) {
            return false;
        }
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 4000) {
            View current = queue.poll();
            if (current != root && current.getId() == viewId) {
                return true;
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return false;
    }

    private static boolean isScrollerLike(View view) {
        String name = view.getClass().getSimpleName();
        return name.contains("Scroller") || name.contains("RecyclerView")
                || name.contains("ViewPager") || name.contains("ScrollView");
    }

    private static String signature(View root) {
        StringBuilder builder = new StringBuilder(64);
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < SIGNATURE_NODES
                && builder.length() < SIGNATURE_CHARS) {
            View current = queue.poll();
            if (current instanceof TextView) {
                CharSequence text = ((TextView) current).getText();
                if (text != null && text.length() > 0) {
                    builder.append(text.subSequence(0, Math.min(text.length(), 32))).append('|');
                }
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return builder.length() > SIGNATURE_CHARS
                ? builder.substring(0, SIGNATURE_CHARS)
                : builder.toString();
    }

    private String describe(View view) {
        if (view == null) {
            return "null";
        }
        return view.getClass().getSimpleName() + "#" + ScreenshotIds.entryName(view);
    }

    private static final class HighlightDrawable extends android.graphics.drawable.Drawable {
        private final Drawable inner;
        private final float density;
        private final String label;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);

        HighlightDrawable(Drawable inner, float density, String label) {
            this.inner = inner;
            this.density = density;
            this.label = label;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, 2f * density));
            paint.setColor(0xFF3B82F6);
            labelPaint.setColor(0xFFFFFFFF);
            labelPaint.setTextSize(11f * density);
            labelBg.setColor(0xE63B82F6);
        }

        @Override
        public void draw(Canvas canvas) {
            inner.setBounds(getBounds());
            inner.draw(canvas);
            canvas.drawRect(getBounds(), paint);
            if (label == null || label.isEmpty()) {
                return;
            }
            android.graphics.Rect bounds = getBounds();
            float pad = 6f * density;
            float textWidth = labelPaint.measureText(label);
            android.graphics.Paint.FontMetrics metrics = labelPaint.getFontMetrics();
            float textHeight = metrics.descent - metrics.ascent;
            float right = bounds.right - pad;
            float bottom = bounds.bottom - pad;
            if (right - textWidth - pad * 2 < bounds.left || bottom - textHeight - pad < bounds.top) {
                return;
            }
            android.graphics.RectF box = new android.graphics.RectF(
                    right - textWidth - pad * 2, bottom - textHeight - pad,
                    right, bottom);
            float radius = 4f * density;
            canvas.drawRoundRect(box, radius, radius, labelBg);
            canvas.drawText(label, box.left + pad, box.bottom - pad - metrics.descent, labelPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            inner.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
