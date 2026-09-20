package com.better.heybox.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.CustomTextSelection;
import com.better.heybox.MainModule;
import com.better.heybox.ModuleStats;
import com.better.heybox.ThemeUtils;
import com.better.heybox.ViewUtils;

/**
 * Comment free-copy (issue #32): long-press a comment, tap "Copy" in the host menu and
 * a sheet offers Copy all / Free copy / Copy @user / Cancel. "Free copy" opens a centered
 * card with the full comment text, where long-press starts the system text selection.
 * Hooked at the copy exits (host copy helpers + ClipboardManager), so the host menu and
 * every other comment interaction stay untouched.
 */
public final class CommentCopyHook {

    private static final String COMMENT_VIEW_CLASS =
            "com.max.xiaoheihe.view.CustomLongPressExpressionTextView";
    private static final String TOAST_UTIL_CLASS = "com.max.hbutils.utils.f";
    private static final String OWN_CLIP_LABEL = "BetterHeybox";

    private static final long LONG_PRESS_TTL_MS = 60_000L;
    private static final long MENU_DISMISS_DELAY_MS = 200L;
    private static final long SHEET_WINDOW_MS = 1_500L;
    private static final long TOAST_SUPPRESS_MS = 2_500L;
    private static final int MAX_SCAN_NODES = 4_000;
    private static final long NO_MATCH_LOG_INTERVAL_MS = 2_000L;

    private final MainModule module;

    private static volatile CommentCopyHook sInstance;
    private static Class<?> sCommentViewClass;
    private static String sCopiedToastText;
    private static boolean sCopiedToastResolved;

    private volatile WeakReference<View> lastLongPressed = new WeakReference<>(null);
    private volatile long lastLongPressAt;
    private volatile long lastSheetAt;
    private volatile long suppressToastUntil;
    private volatile long lastNoMatchLogAt;
    // let clipboard writes through while the card is open
    private volatile boolean freeCopyScreenShowing;

    public CommentCopyHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    // flag is read on every copy; nothing to refresh
    public static void refresh() {
        CommentCopyHook instance = sInstance;
        if (instance != null) {
            instance.module.logd(Log.WARN, instance.module.TAG,
                    "[评论自由复制] 开关已变更：该功能在下一次点「复制」时按新状态生效");
        }
    }

    public void install(ClassLoader cl) {
        try {
            sCommentViewClass = Class.forName(COMMENT_VIEW_CLASS, false, cl);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "未找到评论正文控件类，改用类名匹配: " + t);
        }
        boolean helpers = hookCopyHelpers(cl);
        boolean clipboard = hookClipboard();
        boolean copyToast = hookCopyToast(cl);
        boolean toast = hookToastSuppress();
        boolean longPress = hookPerformLongClick();

        module.logd(Log.WARN, module.TAG, "[评论自由复制] Hook 安装结果：复制助手="
                + (helpers ? "✔" : "✘")
                + " / 剪贴板出口=" + (clipboard ? "✔" : "✘")
                + " / 复制提示抑制=" + (copyToast ? "✔" : "✘")
                + " / Toast 兜底抑制=" + (toast ? "✔" : "✘")
                + " / 长按目标=" + (longPress ? "✔" : "✘")
                + " / 状态=" + (module.isEnabled(App.KEY_COMMENT_FREE_COPY, true) ? "开启" : "关闭"));
    }

    // ---- (1a) host copy helpers

    /**
     * #37: only {@code t(Context, CharSequence)} in com.max.xiaoheihe.utils.h writes the clipboard
     * (same in 1.3.393/394/395/396). A whole-class signature scan matched 21 unrelated methods
     * (Lv.xx row bindings, medal cards, broadcast helpers...), so every row bind ran a full-tree scan
     * plus one WARN. Now: hook exactly when found, otherwise warn once and rely on the (1b) clipboard
     * exit - never fall back to a full-class scan.
     */
    private boolean hookCopyHelpers(ClassLoader cl) {
        boolean any = false;
        any |= hookNamedCopyHelper(cl, "com.max.xiaoheihe.utils.h", "t");
        // every static void method below ends in setPrimaryClip -> copy-only classes, scanning is safe
        any |= hookCopyOnlyClass(cl, "com.max.hbutils.utils.y");
        any |= hookCopyOnlyClass(cl, "com.max.accelworld.c");
        return any;
    }

    /** Name-pinned hook: only same-name methods are shape-matched (String/CharSequence tolerant). */
    private boolean hookNamedCopyHelper(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            int hooked = 0;
            StringBuilder names = new StringBuilder();
            for (Method method : clazz.getDeclaredMethods()) {
                if (!methodName.equals(method.getName()) || !isCopyHelperShape(method)) {
                    continue;
                }
                hookCopyMethod(clazz, method);
                hooked++;
                if (names.length() > 0) {
                    names.append(" / ");
                }
                names.append(method.getName()).append('(')
                        .append(describeParams(method.getParameterTypes())).append(')');
            }
            if (hooked == 0) {
                module.logd(Log.WARN, module.TAG, "✘ 复制助手精确挂点缺失: " + className + "#"
                        + methodName + "（该版实现可能改名，已由剪贴板出口兜底）");
            } else {
                module.logd(Log.WARN, module.TAG, "✔ 复制助手精确挂点: " + className
                        + "#" + names + " ×" + hooked + " 处");
            }
            return hooked > 0;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "复制助手精确挂点跳过 " + className + "#"
                    + methodName + ": " + t);
            return false;
        }
    }

    /** Signature scan for a copy-only class (every static void method writes the clipboard). */
    private boolean hookCopyOnlyClass(ClassLoader cl, String className) {
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            int hooked = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!isCopyHelperShape(method)) {
                    continue;
                }
                hookCopyMethod(clazz, method);
                hooked++;
            }
            if (hooked > 0) {
                module.logd(Log.WARN, module.TAG, "✔ 复制助手 Hook: " + className + " ×" + hooked + " 处");
            }
            return hooked > 0;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "复制助手 Hook 跳过 " + className + ": " + t);
            return false;
        }
    }

    private static boolean isCopyHelperShape(Method method) {
        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != void.class) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        int textIdx = textParamIndex(params);
        int ctxIdx = contextParamIndex(params);
        return textIdx >= 0 && ctxIdx >= 0 && textIdx != ctxIdx;
    }

    private static String describeParams(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }

    private void hookCopyMethod(final Class<?> clazz, final Method method) {
        final Class<?>[] params = method.getParameterTypes();
        final int textIdx = textParamIndex(params);
        final int ctxIdx = contextParamIndex(params);
        module.hook(method).intercept(chain -> {
            try {
                if (canIntercept() && !inRecentSheetWindow()) {
                    Object textArg = chain.getArg(textIdx);
                    if (textArg instanceof CharSequence
                            && ((CharSequence) textArg).length() > 0) {
                        CharSequence text = (CharSequence) textArg;
                        ModuleStats.commentCopyHelperCalls.incrementAndGet();
                        // Do NOT require a long-press record here: on some builds the comment long-press
                        // never reaches View#performLongClick, so a record-less copy must still be matched
                        // by text inside the window (that is the only path that ever worked before #37).
                        View recorded = validRecordedView();
                        Activity activity = activityOfArg(chain.getArg(ctxIdx));
                        if (activity == null && recorded != null) {
                            activity = ViewUtils.findActivity(recorded);
                        }
                        if (recorded != null) {
                            ModuleStats.commentCopyWithRecord.incrementAndGet();
                        } else {
                            ModuleStats.commentCopyNoRecord.incrementAndGet();
                        }
                        View comment = findCommentView(activity, text, recorded);
                        if (comment instanceof TextView) {
                            markIntercepted();
                            module.logd(Log.WARN, module.TAG,
                                    "[评论自由复制] 拦下评论复制（助手 "
                                            + clazz.getSimpleName() + "#"
                                            + method.getName() + "）："
                                            + summarize(text.toString()) + " → 改弹二级菜单");
                            showCopySheet((TextView) comment, text, null);
                            return null;
                        }
                    }
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "复制助手拦截异常（放行）: " + t);
            }
            return chain.proceed();
        });
    }

    private static int textParamIndex(Class<?>[] params) {
        int found = -1;
        for (int i = 0; i < params.length; i++) {
            if (CharSequence.class.isAssignableFrom(params[i])) {
                found = i; // last match wins: text comes after label
            }
        }
        return found;
    }

    private static int contextParamIndex(Class<?>[] params) {
        for (int i = 0; i < params.length; i++) {
            if (Context.class.isAssignableFrom(params[i])
                    || View.class.isAssignableFrom(params[i])) {
                return i;
            }
        }
        return -1;
    }

    private Activity activityOfArg(Object arg) {
        if (arg instanceof View) {
            return ViewUtils.findActivity((View) arg);
        }
        if (arg instanceof Context) {
            return ViewUtils.findActivity((Context) arg);
        }
        return null;
    }

    // ---- (1b) clipboard exit

    private boolean hookClipboard() {
        try {
            Method method = ClipboardManager.class.getDeclaredMethod("setPrimaryClip", ClipData.class);
            module.hook(method).intercept(chain -> {
                Object clip = chain.getArg(0);
                try {
                    if (canIntercept() && !OWN_CLIP_LABEL.equals(clipLabel(clip))
                            && !inRecentSheetWindow()) {
                        CharSequence text = clipText(clip);
                        if (text != null && text.length() > 0) {
                            View comment = findCommentViewFor(text);
                            if (comment instanceof TextView) {
                                markIntercepted();
                                module.logd(Log.WARN, module.TAG, "[评论自由复制] 拦下评论复制："
                                        + summarize(text.toString()) + " → 改弹二级菜单");
                                showCopySheet((TextView) comment, text, chain.getThisObject());
                                return null;
                            }
                        }
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "剪贴板拦截异常（放行）: " + t);
                }
                return chain.proceed();
            });
            module.logd(Log.WARN, module.TAG, "✔ 剪贴板出口 Hook: ClipboardManager#setPrimaryClip");
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "剪贴板出口 Hook 失败: " + t);
            return false;
        }
    }

    private boolean canIntercept() {
        return module.isEnabled(App.KEY_COMMENT_FREE_COPY, true) && !freeCopyScreenShowing;
    }

    private static String clipLabel(Object clipData) {
        if (!(clipData instanceof ClipData)) {
            return null;
        }
        try {
            CharSequence label = ((ClipData) clipData).getDescription().getLabel();
            return label == null ? null : label.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static CharSequence clipText(Object clipData) {
        if (!(clipData instanceof ClipData)) {
            return null;
        }
        try {
            ClipData data = (ClipData) clipData;
            if (data.getItemCount() <= 0) {
                return null;
            }
            return data.getItemAt(0).getText();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- (1c) host "copied" toast

    // host "text copied" toast exit: hbutils.utils.f
    private boolean hookCopyToast(ClassLoader cl) {
        boolean any = hookToastUtil(cl, TOAST_UTIL_CLASS);
        any |= hookToastUtil(cl, "com.max.hbutils.utils.b0");
        return any;
    }

    // host toast utils: swallow anything inside the window
    private boolean hookToastUtil(ClassLoader cl, String className) {
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            int hooked = 0;
            for (final Method method : clazz.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class
                        || method.getParameterCount() != 1
                        || !CharSequence.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    try {
                        Object arg = chain.getArg(0);
                        if (inSuppressWindow() || isCopyToastText(String.valueOf(arg))) {
                            module.logd(Log.WARN, module.TAG, "[评论自由复制] 已抑制宿主提示（"
                                    + clazz.getSimpleName() + "#" + method.getName() + "）："
                                    + (arg == null ? "null" : summarize(String.valueOf(arg))));
                            return null;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked > 0) {
                module.logd(Log.WARN, module.TAG, "✔ 提示抑制 Hook: " + className
                        + " ×" + hooked + " 处");
            }
            return hooked > 0;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "提示抑制 Hook 跳过 " + className + ": " + t);
            return false;
        }
    }

    // backstop: plain Toast#show
    private boolean hookToastSuppress() {
        try {
            Method show = Toast.class.getDeclaredMethod("show");
            module.hook(show).intercept(chain -> {
                try {
                    if (shouldSuppressToast(chain.getThisObject())) {
                        module.logd(Log.WARN, module.TAG, "[评论自由复制] 已抑制宿主「已复制」Toast");
                        return null;
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "Toast 兜底抑制 Hook 失败: " + t);
            return false;
        }
    }

    private boolean shouldSuppressCopyToast(String shown) {
        if (SystemClock.uptimeMillis() > suppressToastUntil) {
            return false;
        }
        String expected = copiedToastText();
        if (expected != null && expected.equals(shown)) {
            return true;
        }
        return (shown.contains("复制") || shown.contains("複製"))
                && (shown.contains("剪贴板") || shown.contains("剪貼簿"));
    }

    /** Swallow host toasts while the suppression window is open (custom-view toasts included) */
    private boolean shouldSuppressToast(Object toast) {
        if (!(toast instanceof Toast)) {
            return false;
        }
        String shown = null;
        try {
            Object value = Toast.class.getMethod("getText").invoke(toast);
            shown = value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
        }
        // inside the window: swallow regardless of text (custom-view toasts included);
        // outside: only the "copied to clipboard" wording (toast-before-copy order)
        if (!inSuppressWindow() && !isCopyToastText(shown)) {
            return false;
        }
        module.logd(Log.WARN, module.TAG, "[评论自由复制] 已抑制宿主 Toast："
                + (shown == null ? "(自定义 View)" : summarize(shown)));
        return true;
    }

    // host "copied to clipboard" wording, simplified / traditional
    private boolean isCopyToastText(String shown) {
        if (shown == null || !module.isEnabled(App.KEY_COMMENT_FREE_COPY, true)) {
            return false;
        }
        String expected = copiedToastText();
        if (expected != null && expected.equals(shown)) {
            return true;
        }
        boolean copying = shown.contains("复制") || shown.contains("複製");
        boolean clipboard = shown.contains("剪贴板") || shown.contains("剪切板")
                || shown.contains("剪貼簿") || shown.contains("剪貼板");
        return copying && clipboard;
    }

    private boolean inSuppressWindow() {
        return SystemClock.uptimeMillis() <= suppressToastUntil;
    }

    private static String copiedToastText() {
        if (sCopiedToastResolved) {
            return sCopiedToastText;
        }
        sCopiedToastResolved = true;
        try {
            Context context = App.resolveAppContext();
            if (context != null) {
                int id = context.getResources()
                        .getIdentifier("text_copied", "string", MainModule.TARGET_PKG);
                if (id != 0) {
                    sCopiedToastText = context.getString(id);
                }
            }
        } catch (Throwable ignored) {
        }
        return sCopiedToastText;
    }

    private boolean inRecentSheetWindow() {
        return SystemClock.uptimeMillis() - lastSheetAt < SHEET_WINDOW_MS;
    }

    private void markIntercepted() {
        long now = SystemClock.uptimeMillis();
        lastSheetAt = now;
        suppressToastUntil = now + TOAST_SUPPRESS_MS;
    }

    // ---- (2) long-press target

    private boolean hookPerformLongClick() {
        try {
            Method method = View.class.getDeclaredMethod("performLongClick");
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (module.isEnabled(App.KEY_COMMENT_FREE_COPY, true)) {
                        Object self = chain.getThisObject();
                        if (self instanceof View) {
                            View comment = findCommentViewNear((View) self);
                            if (comment != null) {
                                lastLongPressed = new WeakReference<>(comment);
                                lastLongPressAt = SystemClock.uptimeMillis();
                            }
                        }
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "记录长按目标失败: " + t);
                }
                return result;
            });
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "长按目标 Hook 失败: " + t);
            return false;
        }
    }

    private View validRecordedView() {
        View target = lastLongPressed.get();
        if (target == null) {
            return null;
        }
        if (SystemClock.uptimeMillis() - lastLongPressAt > LONG_PRESS_TTL_MS) {
            return null;
        }
        if (!target.isShown() || target.getWindowToken() == null) {
            return null;
        }
        return target;
    }

    // nearest comment view: self -> subtree -> up to 4 ancestors (single match only)
    private View findCommentViewNear(View start) {
        if (start == null) {
            return null;
        }
        if (isCommentView(start)) {
            return start;
        }
        List<View> inSelf = new ArrayList<>();
        collectCommentViews(start, inSelf, 0, new Scan());
        if (!inSelf.isEmpty()) {
            return inSelf.get(0);
        }
        android.view.ViewParent parent = start.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 4) {
            View ancestor = (View) parent;
            List<View> found = new ArrayList<>();
            collectCommentViews(ancestor, found, 0, new Scan());
            if (found.size() == 1) {
                return found.get(0);
            }
            if (found.size() > 1) {
                return null;
            }
            parent = ancestor.getParent();
        }
        return null;
    }

    private void collectCommentViews(View root, List<View> out, int depth, Scan scan) {
        if (root == null || depth > 40 || out.size() > 64 || scan.nodes >= MAX_SCAN_NODES) {
            return;
        }
        scan.nodes++;
        if (isCommentView(root)) {
            out.add(root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectCommentViews(group.getChildAt(i), out, depth + 1, scan);
            }
        }
    }

    /** Per-traversal node budget (#37): large lists are no longer walked end to end. */
    private static final class Scan {
        int nodes;
    }

    private static boolean isCommentView(View view) {
        Class<?> clazz = sCommentViewClass;
        if (clazz != null) {
            return clazz.isInstance(view);
        }
        return COMMENT_VIEW_CLASS.equals(view.getClass().getName());
    }

    // ---- target lookup

    private View findCommentViewFor(CharSequence copied) {
        View recorded = validRecordedView();
        return findCommentView(activityOf(recorded), copied, recorded);
    }

    private View findCommentView(Activity activity, CharSequence copied, View recorded) {
        String wanted = normalize(copied);
        if (wanted.length() == 0) {
            return null;
        }
        if (recorded instanceof TextView
                && textMatches(normalize(((TextView) recorded).getText()), wanted)) {
            return recorded;
        }
        if (activity == null || activity.isFinishing()) {
            return null;
        }
        ModuleStats.commentDfsRuns.incrementAndGet();
        long startAt = SystemClock.uptimeMillis();
        Scan scan = new Scan();
        View found = null;
        try {
            found = findCommentByText(activity.getWindow().getDecorView(), wanted, scan);
        } catch (Throwable ignored) {
        }
        long cost = SystemClock.uptimeMillis() - startAt;
        ModuleStats.commentDfsNodes.addAndGet(scan.nodes);
        ModuleStats.commentDfsMillis.addAndGet(cost);
        ModuleStats.slow("评论复制整树查找", cost);
        if (found != null) {
            ModuleStats.commentCopyMatched.incrementAndGet();
            return found;
        }
        ModuleStats.commentCopyNoMatch.incrementAndGet();
        logNoMatch(scan.nodes, copied, recorded);
        return null;
    }

    private View findCommentByText(View root, String wanted, Scan scan) {
        if (root == null || scan.nodes >= MAX_SCAN_NODES) {
            return null;
        }
        scan.nodes++;
        if (isCommentView(root)) {
            if (root instanceof TextView
                    && textMatches(normalize(((TextView) root).getText()), wanted)) {
                return root;
            }

            return null;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findCommentByText(group.getChildAt(i), wanted, scan);
                if (found != null) {
                    return found;
                }
                if (scan.nodes >= MAX_SCAN_NODES) {
                    break;
                }
            }
        }
        return null;
    }

    private void logNoMatch(int scannedNodes, CharSequence copied, View recorded) {
        long now = SystemClock.uptimeMillis();
        if (now - lastNoMatchLogAt < NO_MATCH_LOG_INTERVAL_MS) {
            return;
        }
        lastNoMatchLogAt = now;
        module.logv(module.TAG, "[评论自由复制] 文本未匹配到评论 复制文本=" + summarize(copied.toString())
                + " / 长按记录=" + (recorded instanceof TextView
                ? summarize(String.valueOf(((TextView) recorded).getText())) : "无"));
    }

    private static boolean textMatches(String viewText, String copied) {
        if (viewText.length() == 0 || copied.length() == 0) {
            return false;
        }
        if (viewText.equals(copied)) {
            return true;
        }
        if (copied.length() >= 6 && viewText.contains(copied)) {
            return true;
        }
        if (viewText.length() >= 6 && copied.contains(viewText)) {
            return true;
        }
        // collapsed long comment: the view shows truncated text + "expand" while the host
        // copies the full raw text -> prefix match (first 24 chars of the shorter side)
        int probe = Math.min(Math.min(viewText.length(), copied.length()), 24);
        return probe >= 8 && viewText.regionMatches(0, copied, 0, probe);
    }

    private Activity activityOf(View anchor) {
        return anchor != null ? ViewUtils.findActivity(anchor) : null;
    }

    // ---- module sheet

    private void showCopySheet(final TextView commentView, final CharSequence copiedText,
                               final Object clipboard) {
        final Activity activity = ViewUtils.findActivity(commentView);
        if (activity == null || activity.isFinishing()) {
            module.logd(Log.WARN, module.TAG, "[评论自由复制] 找不到 Activity，放行宿主复制");
            writeClipboard(clipboard, copiedText);
            return;
        }
        final String nickname = nicknameOf(commentView);
        activity.getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    presentSheet(activity, commentView, copiedText, clipboard, nickname);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "二级菜单失败，退回整条复制: " + t);
                    writeClipboard(clipboard, copiedText);
                }
            }
        }, MENU_DISMISS_DELAY_MS);
    }

    private void presentSheet(final Activity activity, final TextView commentView,
                              final CharSequence copiedText, final Object clipboard,
                              String nickname) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        boolean dark = ThemeUtils.isDarkMode(activity);
        int surface = dark ? 0xFF232327 : 0xFFFFFFFF;
        applyBottomSheetWindow(window, activity, surface, 0.35f);
        dialog.setCanceledOnTouchOutside(true);

        int primary = dark ? 0xE6FFFFFF : 0xDD000000;
        int secondary = dark ? 0x99FFFFFF : 0x99000000;
        int dividerColor = dark ? 0x1FFFFFFF : 0x14000000;
        int ripple = dark ? 0x33FFFFFF : 0x1A000000;

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surface);
        float radius = ThemeUtils.dp(activity, ThemeUtils.RADIUS_SHEET_DP);
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0f, 0f, 0f, 0f});
        container.setBackground(shape);
        int padV = ThemeUtils.dp(activity, 10);
        container.setPadding(0, padV, 0, padV);

        container.addView(sheetRow(activity, "复制全部内容", primary, ripple,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        writeClipboard(clipboard, copiedText);
                    }
                }));
        container.addView(sheetDivider(activity, dividerColor));
        container.addView(sheetRow(activity, "自由复制", primary, ripple,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        showFreeCopyScreen(activity, commentView, copiedText);
                    }
                }));
        if (nickname != null && nickname.length() > 0) {
            container.addView(sheetDivider(activity, dividerColor));
            container.addView(sheetRow(activity, "复制 @" + nickname, primary, ripple,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            writeClipboard(clipboard, "@" + nickname);
                        }
                    }));
        }
        container.addView(sheetDivider(activity, dividerColor));
        container.addView(sheetRow(activity, "取消", secondary, ripple,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                    }
                }));

        dialog.setContentView(container);
        dialog.show();
        module.logd(Log.WARN, module.TAG, "[评论自由复制] ✔ 已弹出二级菜单（昵称="
                + (nickname == null ? "未取到" : nickname) + "）");
    }

    private TextView sheetRow(Context context, String label, int textColor, int rippleColor,
                              View.OnClickListener listener) {
        TextView row = new TextView(context);
        row.setText(label);
        row.setTextSize(16f);
        row.setTextColor(textColor);
        row.setGravity(Gravity.CENTER);
        row.setPadding(ThemeUtils.dp(context, 20), ThemeUtils.dp(context, 15),
                ThemeUtils.dp(context, 20), ThemeUtils.dp(context, 15));
        row.setBackground(pressedBackground(context, rippleColor));
        row.setLongClickable(false);
        row.setClickable(true);
        row.setOnClickListener(listener);
        return row;
    }

    /** Bounded rounded pressed highlight (no ripple) */
    private static android.graphics.drawable.Drawable pressedBackground(Context context,
                                                                       int pressedColor) {
        float radius = ThemeUtils.dp(context, 14);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        normal.setCornerRadius(radius);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressedColor);
        pressed.setCornerRadius(radius);
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    private View sheetDivider(Context context, int color) {
        View divider = new View(context);
        divider.setBackgroundColor(color);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ThemeUtils.dp(context, 0.5f))));
        return divider;
    }

    // ---- free copy card

    /**
     * The "free copy" card: full comment text, centered, system text selection on long-press.
     * Never call setMovementMethod after setTextIsSelectable (it would break selection).
     */
    private void showFreeCopyScreen(final Activity activity, final TextView commentView,
                                    final CharSequence interceptedText) {
        final String text = rawCommentText(commentView, interceptedText);
        if (text == null || text.length() == 0) {
            return;
        }
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        boolean dark = ThemeUtils.isDarkMode(activity);
        int surface = dark ? 0xFF232327 : 0xFFFFFFFF;
        int primary = dark ? 0xE6FFFFFF : 0xDD000000;
        int secondary = dark ? 0x99FFFFFF : 0x99000000;

        // centered card (same as the previous build)
        applyCenteredCardWindow(window, activity, surface, 0.45f);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(ThemeUtils.dp(activity, 20));
        root.setBackground(bg);
        int pad = ThemeUtils.dp(activity, 18);
        root.setPadding(pad, pad, pad, ThemeUtils.dp(activity, 18));
        // self-drawn handles follow the module switch 自绘制文本选择 (post body uses the same one)
        final boolean customHandles = module.isEnabled(App.KEY_CUSTOM_TEXT_SELECT, false);

        final TextView body = new TextView(activity);
        body.setText(new android.text.SpannableStringBuilder(text));
        body.setTextSize(17f);
        body.setTextColor(primary);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(ThemeUtils.dp(activity, 5), 1f);
        // room for the system handles of the first / last line
        int handlePad = ThemeUtils.dp(activity, 26);
        body.setPadding(0, handlePad, 0, handlePad);
        if (!customHandles) {
            // system text selection: do not touch MovementMethod
            body.setTextIsSelectable(true);
        }

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int maxHeight = (int) (metrics.heightPixels * 0.55f);
        int availWidth = Math.max(0, metrics.widthPixels - pad * 2);
        body.measure(View.MeasureSpec.makeMeasureSpec(availWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // wrap in a ScrollView only when the text does not fit: no scrollable parent, nothing to steal
        final MaxHeightScrollView scroll = body.getMeasuredHeight() > maxHeight
                ? new MaxHeightScrollView(activity, maxHeight) : null;
        if (scroll != null) {
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.setClipChildren(false);
            scroll.addView(body, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // keep the gesture with the TextView while a selection is alive
            body.setOnTouchListener(new View.OnTouchListener() {
                private boolean downHadSelection;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    boolean selecting = body.getSelectionStart() != body.getSelectionEnd();
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        downHadSelection = selecting;
                    }
                    if (selecting || downHadSelection) {
                        scroll.keepGesture(SystemClock.uptimeMillis() + 800L);
                    }
                    return false;
                }
            });
        } else {
            root.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView hint = new TextView(activity);
        hint.setText("长按自由复制");
        hint.setTextSize(12f);
        hint.setTextColor(secondary);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, ThemeUtils.dp(activity, 10), 0, ThemeUtils.dp(activity, 4));
        root.addView(hint);

        freeCopyScreenShowing = true;
        // system-toolbar copies must pass through
        suppressToastUntil = 0L;

        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                freeCopyScreenShowing = false;
                CustomTextSelection.detach(body);
            }
        });

        dialog.setContentView(root);
        dialog.show();

        boolean selfDrawn = customHandles;
        if (selfDrawn) {
            try {
                CustomTextSelection.attach(body, (ViewGroup) dialog.getWindow().getDecorView());
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "自绘手柄挂载失败，退回系统选择: " + t);
                selfDrawn = false;
                body.setTextIsSelectable(true);
            }
        }
        module.logd(Log.WARN, module.TAG, "[评论自由复制] ✔ 已打开自由复制文本页（完整 "
                + text.length() + " 字，" + (selfDrawn ? "自绘手柄" : "系统文本选择") + "）");
    }

    // ---- sheet window / navigation bar


    /** Centered card window: WRAP_CONTENT, centered, dim behind; styles the navigation bar */
    private void applyCenteredCardWindow(Window window, Activity activity, int surface, float dim) {
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.CENTER);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.dimAmount = dim;
        window.setAttributes(attrs);
        styleNavigationBar(window, activity, surface);
    }

    // nav bar: same color as the card, dark icons on light backgrounds
    private void styleNavigationBar(Window window, Activity activity, int surface) {
        boolean dark = ThemeUtils.isDarkMode(activity);
        try {
            View decor = window.getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            if (!dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
        try {
            window.setNavigationBarColor(surface);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                            dark ? 0
                                    : android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyBottomSheetWindow(Window window, Activity activity, int surface, float dim) {
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.dimAmount = dim;
        window.setAttributes(attrs);

        styleNavigationBar(window, activity, surface);
    }

    // navigation bar height (content avoids it; background does not need to)
    private static int navBarHeight(Window window) {
        if (window == null) {
            return 0;
        }
        try {
            android.view.WindowInsets insets = window.getDecorView().getRootWindowInsets();
            if (insets == null) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                return insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            }
            return insets.getSystemWindowInsetBottom();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ScrollView capped at a max height; never steals the gesture during a selection drag
    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;
        private volatile long keepGestureUntil;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        void keepGesture(long until) {
            keepGestureUntil = Math.max(keepGestureUntil, until);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (SystemClock.uptimeMillis() < keepGestureUntil) {
                return false;
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    // prefer the raw BBSCommentObj text from the view tag (keeps [emoji] markers)
    private String rawCommentText(View commentView, CharSequence fallback) {
        try {
            Object tag = commentView.getTag();
            if (tag != null) {
                Object text = tag.getClass().getMethod("getText").invoke(tag);
                if (text instanceof CharSequence && ((CharSequence) text).length() > 0) {
                    return sanitizeCardText(text.toString());
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback == null ? null : sanitizeCardText(fallback.toString());
    }

    /**
     * Drop everything the host hides inside comment text (zero-width chars, bidi marks,
     * control chars, odd separators), unify line breaks and collapse blank lines, so
     * selecting and dragging stays smooth.
     */
    private static String sanitizeCardText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u2028', '\n').replace('\u2029', '\n').replace('\u0085', '\n');
        StringBuilder sb = new StringBuilder(normalized.length());
        boolean lastNewline = true; // also trims leading blank lines
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (isHiddenChar(ch)) {
                continue;
            }
            if (ch == '\n') {
                if (lastNewline) {
                    continue;
                }
                lastNewline = true;
                sb.append('\n');
                continue;
            }
            if (ch == '\u00A0' || ch == '\u3000' || ch == '\t') {
                ch = ' ';
            }
            lastNewline = false;
            sb.append(ch);
        }
        String[] lines = sb.toString().split("\n");
        StringBuilder out = new StringBuilder(sb.length());
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(trimmed);
        }
        return out.toString();
    }

    /** Invisible characters the host sprinkles into comment text */
    private static boolean isHiddenChar(char ch) {
        if (ch == '\uFEFF' || ch == '\u2060' || ch == '\u180E' || ch == '\u00AD'
                || ch == '\u061C' || ch == '\uFFFC') {
            return true;
        }
        if (ch >= '\u2000' && ch <= '\u200F') {
            return true; // en/em/thin spaces + LRM/RLM/ZWNJ/ZWJ
        }
        if (ch >= '\u202A' && ch <= '\u202E') {
            return true; // bidi embeddings
        }
        if (ch >= '\u2066' && ch <= '\u2069') {
            return true; // bidi isolates
        }
        return ch == 0x7F || (ch < 0x20 && ch != '\n');
    }

    // ---- utils

    private void writeClipboard(Object clipboard, CharSequence text) {
        try {
            String cleaned = sanitizeCardText(text == null ? null : text.toString());
            text = cleaned;
            ClipboardManager cm = clipboard instanceof ClipboardManager
                    ? (ClipboardManager) clipboard
                    : (ClipboardManager) App.resolveAppContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(OWN_CLIP_LABEL, text));
            }
            toast("已复制");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "写剪贴板失败: " + t);
        }
    }

    private void toast(String text) {
        try {
            // let our own toast through
            suppressToastUntil = 0L;
            Context context = App.resolveAppContext();
            if (context != null) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private String nicknameOf(View commentView) {
        try {
            Object tag = commentView.getTag();
            if (tag == null) {
                return null;
            }
            Object user = tag.getClass().getMethod("getUser").invoke(tag);
            if (user == null) {
                return null;
            }
            Object name = user.getClass().getMethod("getUsername").invoke(user);
            return name == null ? null : name.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalize(CharSequence src) {
        if (src == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (Character.isWhitespace(ch) || isHiddenChar(ch)) {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String summarize(String text) {
        String one = text.replace('\n', ' ');
        return one.length() <= 24 ? one : one.substring(0, 24) + "…";
    }
}
