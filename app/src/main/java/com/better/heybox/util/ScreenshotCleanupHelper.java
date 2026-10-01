package com.better.heybox.util;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ScreenshotCleanupHelper {

    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 5000;

    private final Map<View, Integer> hidden = new WeakHashMap<>();

    public int hideInside(View root) {
        return hideInside(root,
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_TAGS, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_TIMESTAMPS, false),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_ACTIONS, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_IRRELEVANT, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_CLOSE, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_FOLLOW, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_LIKE_COUNT, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_IMAGE_COUNT, true),
                HeyboxPrefs.getString(App.KEY_SCREENSHOT_HIDE_CUSTOM, ""));
    }

    public static boolean[] currentRules() {
        return new boolean[] {
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_TAGS, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_TIMESTAMPS, false),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_ACTIONS, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_IRRELEVANT, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_CLOSE, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_FOLLOW, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_LIKE_COUNT, true),
                HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_IMAGE_COUNT, true),
        };
    }

    public int hideInside(View root, boolean tags, boolean timestamps,
                          boolean actions, boolean irrelevant) {
        return hideInside(root, tags, timestamps, actions, irrelevant,
                false, false, false, false, null);
    }

    public int hideInside(View root, boolean tags, boolean timestamps,
                          boolean actions, boolean irrelevant, boolean closeIcons,
                          boolean follow, boolean likeCount, boolean imageCount, String custom) {
        if (root == null) {
            return 0;
        }
        Context context = root.getContext();
        Set<Integer> targets = new HashSet<>();
        if (tags) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.TAGS));
        }
        if (timestamps) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.TIMESTAMPS));
        }
        if (actions) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.ACTIONS));
        }
        if (irrelevant) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.IRRELEVANT));
        }
        if (closeIcons) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.CLOSE_ICONS));
        }
        if (follow) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.FOLLOW));
        }
        if (likeCount) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.LIKE_COUNT));
        }
        if (imageCount) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.IMAGE_COUNT));
        }
        Set<String> customNames = new HashSet<>();
        List<String> customTexts = new ArrayList<>();
        parseCustom(custom, customNames, customTexts);

        if (targets.isEmpty() && customNames.isEmpty() && customTexts.isEmpty()
                && !actions && !likeCount && !follow && !irrelevant && !imageCount) {
            return 0;
        }
        return hideMatching(root, targets, actions, likeCount, irrelevant, imageCount,
                customNames, customTexts, follow);
    }

    public void restore() {
        for (Map.Entry<View, Integer> entry : hidden.entrySet()) {
            View view = entry.getKey();
            Integer visibility = entry.getValue();
            if (view == null || visibility == null) {
                continue;
            }
            try {
                view.setVisibility(visibility);
            } catch (Throwable ignored) {

            }
        }
        hidden.clear();
    }

    private int hideMatching(View root, Set<Integer> targets, boolean actions,
                             boolean likeCount, boolean irrelevant, boolean imageCount,
                             Set<String> customNames,
                             List<String> customTexts, boolean follow) {
        int count = 0;
        ArrayDeque<View> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            View current = queue.poll();
            Integer depthBox = depths.poll();
            int depth = depthBox == null ? 0 : depthBox;

            if (current != root && current.getVisibility() != View.GONE) {
                View target = hideTarget(current, targets, actions, likeCount, irrelevant,
                        imageCount, customNames, customTexts, follow);
                if (target != null && target.getVisibility() != View.GONE) {
                    hidden.put(target, target.getVisibility());
                    target.setVisibility(View.GONE);
                    count++;

                    continue;
                }
            }

            if (depth < MAX_DEPTH && current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                int childCount = group.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    queue.add(group.getChildAt(i));
                    depths.add(depth + 1);
                }
            }
        }
        return count;
    }

    private static boolean matches(View view, Set<Integer> targets, boolean actions,
                                   boolean likeCount, Set<String> customNames,
                                   List<String> customTexts) {
        if (!targets.isEmpty() && targets.contains(view.getId())) {
            return true;
        }
        if (likeCount && isLikeControl(view)) {
            return true;
        }
        if (likeCount && isIconStatGroup(view)) {
            return true;
        }
        if (!customNames.isEmpty()) {
            String entry = ScreenshotIds.entryName(view);
            if (entry != null && customNames.contains(entry.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (!customTexts.isEmpty() && view instanceof TextView) {
            String text = textOf(view);
            if (!text.isEmpty()) {
                String lower = text.toLowerCase(Locale.ROOT);
                for (String keyword : customTexts) {
                    if (lower.contains(keyword)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static View hideTarget(View view, Set<Integer> targets, boolean actions,
                                   boolean likeCount, boolean irrelevant, boolean imageCount,
                                   Set<String> customNames,
                                   List<String> customTexts, boolean follow) {
        if (!targets.isEmpty() && targets.contains(view.getId())) {
            return view;
        }
        if (imageCount && view instanceof TextView && isImageCountLabel(textOf(view))) {

            return smallContainer(view, 140);
        }
        if (irrelevant && isIrrelevantTitle(textOf(view))) {

            return irrelevantContainer(view);
        }
        if (likeCount && isLikeControl(view)) {
            return view;
        }
        if (likeCount && isIconStatGroup(view)) {
            return view;
        }
        if (!customNames.isEmpty()) {
            String entry = ScreenshotIds.entryName(view);
            if (entry != null && customNames.contains(entry.toLowerCase(Locale.ROOT))) {
                return view;
            }
        }
        if (!customTexts.isEmpty() && view instanceof TextView) {
            String text = textOf(view);
            if (!text.isEmpty()) {
                String lower = text.toLowerCase(Locale.ROOT);
                for (String keyword : customTexts) {
                    if (lower.contains(keyword)) {

                        return smallContainer(view, 140);
                    }
                }
            }
        }
        if (follow && isFollowControl(view)) {
            return followContainer(view);
        }
        return null;
    }

    private static boolean isImageCountLabel(String text) {
        if (text.isEmpty() || text.length() > 8) {
            return false;
        }
        int slash = text.indexOf('/');
        if (slash > 0 && slash < text.length() - 1) {
            String left = text.substring(0, slash).trim();
            String right = text.substring(slash + 1).trim();
            return isNumber(left) && isNumber(right);
        }
        String body = text.startsWith("共") ? text.substring(1) : text;
        if (body.endsWith("张")) {
            body = body.substring(0, body.length() - 1).trim();
        } else {
            return false;
        }
        return isNumber(body);
    }

    private static boolean isIrrelevantTitle(String text) {
        if (text.isEmpty() || text.length() > 12) {
            return false;
        }
        for (String keyword : ScreenshotIds.IRRELEVANT_TITLES) {
            if (text.startsWith(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static View irrelevantContainer(View title) {
        View node = title;
        View target = null;
        for (int i = 0; i < 6; i++) {
            ViewParent parent = node.getParent();
            if (!(parent instanceof ViewGroup)) {
                break;
            }
            ViewGroup group = (ViewGroup) parent;
            if (group.getChildCount() >= 2) {
                target = group;
                break;
            }
            node = group;
        }
        if (target == null) {
            ViewParent parent = title.getParent();
            target = parent instanceof View ? (View) parent : title;
        }
        int height = target.getHeight();
        int titleHeight = title.getHeight();
        if (height > 0 && titleHeight > 0 && height > titleHeight * 12) {
            return title;
        }
        return target;
    }

    private static View smallContainer(View view, int maxHeight) {
        View target = view;
        View current = view;
        for (int i = 0; i < 2; i++) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof ViewGroup)) {
                break;
            }
            ViewGroup candidate = (ViewGroup) parent;
            if (candidate.getChildCount() > 4) {
                break;
            }
            int height = candidate.getHeight();
            if (height > 0 && height > maxHeight) {
                break;
            }
            target = candidate;
            current = candidate;
        }
        return target;
    }

    private static boolean isFollowControl(View view) {
        if (!(view instanceof TextView)) {
            return false;
        }
        String text = textOf(view).replace("+", "").replace("＋", "").trim();
        boolean label = "关注".equals(text) || "已关注".equals(text) || "关注中".equals(text);
        if (!label && !nameLooksLikeFollow(view)) {
            return false;
        }

        return nameLooksLikeFollow(view) || isClickableNear(view, 3);
    }

    private static boolean nameLooksLikeFollow(View view) {
        String id = ScreenshotIds.entryName(view);
        if (id != null && id.toLowerCase().contains("follow")) {
            return true;
        }
        ViewParent parent = view.getParent();
        for (int i = 0; i < 3 && parent instanceof View; i++) {
            String name = parent.getClass().getSimpleName().toLowerCase();
            if (name.contains("follow") || name.contains("subscribe")) {
                return true;
            }
            parent = ((View) parent).getParent();
        }
        return false;
    }

    private static boolean isClickableNear(View view, int levels) {
        View current = view;
        for (int i = 0; i <= levels && current != null; i++) {
            if (current.isClickable()) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static View followContainer(View view) {
        View target = view;
        View current = view;
        for (int i = 0; i < 2; i++) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            View candidate = (View) parent;
            if (!candidate.isClickable()) {
                break;
            }
            int width = candidate.getWidth();
            int height = candidate.getHeight();
            if (width > 0 && (width > 460 || height > 160)) {
                break;
            }
            target = candidate;
            current = candidate;
        }
        return target;
    }

    private static boolean isLikeControl(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class;
             type = type.getSuperclass()) {
            if ("BBSLinkListLikeComment".equals(type.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIconStatGroup(View view) {
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        int childCount = group.getChildCount();
        if (childCount < 2 || childCount > 3) {
            return false;
        }
        boolean icon = false;
        boolean number = false;
        for (int i = 0; i < childCount; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            if (child instanceof ViewGroup) {
                return false;
            }
            if (child instanceof TextView) {
                String text = textOf(child);
                if (text.isEmpty() || !isNumber(text)) {
                    return false;
                }
                number = true;
            } else {
                icon = true;
            }
        }
        return icon && number;
    }

    private static String textOf(View view) {
        if (!(view instanceof TextView)) {
            return "";
        }
        CharSequence text = ((TextView) view).getText();
        return text == null ? "" : text.toString().trim();
    }

    private static boolean isNumber(String text) {
        int length = text.length();
        if (length == 0 || length > 8) {
            return false;
        }
        boolean digit = false;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c != '.' && c != '+' && c != '万' && c != '亿' && c != 'k'
                    && c != 'K' && c != 'w' && c != 'W') {
                return false;
            }
        }
        return digit;
    }

    static void parseCustom(String raw, Set<String> names, List<String> texts) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String line : raw.split("[\n,，;；]")) {
            String item = line.trim();
            if (item.isEmpty() || item.startsWith("#")) {
                continue;
            }
            String lower = item.toLowerCase(Locale.ROOT);
            if (lower.startsWith("text:") || lower.startsWith("文字:")) {
                String keyword = item.substring(item.indexOf(':') + 1).trim();
                if (!keyword.isEmpty()) {
                    texts.add(keyword.toLowerCase(Locale.ROOT));
                }
            } else {
                names.add(lower);
            }
        }
    }

    private static void addAll(Set<Integer> out, int[] values) {
        for (int value : values) {
            if (value != 0) {
                out.add(value);
            }
        }
    }
}

