package com.better.heybox.hooks;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.better.heybox.App;
import com.better.heybox.Checkpoint;
import com.better.heybox.MainModule;
import io.github.libxposed.api.XposedInterface;

/** Comment filter (issue #36). Data: floors. List: sub-comments. Row: fallback. */
public final class CommentFilterHook {

    private static final String POST_COMMENT_SECTION_CLASS =
            "com.max.data.model.community.PostCommentSectionModel";
    private static final String MEANINGLESS_MODEL_CLASS =
            "com.max.data.model.community.MeaninglessCommentModel";
    private static final String COMMENTS_OBJ_CLASS =
            "com.max.basebbs.bean.BBSCommentsObj";
    private static final String COMMENT_OBJ_CLASS =
            "com.max.basebbs.bean.BBSCommentObj";
    private static final String BASE_ADAPTER_CLASS =
            "com.max.hbcommon.base.adapter.s";
    private static final String BASE_ADAPTER_HOLDER_CLASS =
            "com.max.hbcommon.base.adapter.s$e";
    private static final String SUB_COMMENT_VIEW_CLASS = "com.max.xiaoheihe.view.SubCommentView";

    private static final String HOLDER_PREFIX = "com.max.hbcommon.base.adapter.s$";
    private static final String VIEW_HOLDER_CLASS =
            "androidx.recyclerview.widget.RecyclerView$ViewHolder";

    private static final String[] ADAPTER_CLASSES = new String[]{
            "com.max.xiaoheihe.module.bbs.adapter.CommentAdapterV2",
            "com.max.xiaoheihe.module.bbs.adapter.n",
    };

    /** Pure cy text (invisible chars stripped). */
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\s\\u200b-\\u200f\\u202a-\\u202e\\ufeff]+");

    private static volatile CommentFilterHook sInstance;

    private final MainModule module;

    /** Seen adapters (weak). */
    private final WeakHashMap<Object, Boolean> adapterRefs = new WeakHashMap<>();

    /** Auto load-more count per floor (weak, key = main comment). */
    private final java.util.Map<Object, Integer> autoLoadCounts =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final int VISIBLE_TARGET = 6;
    private static final int MAX_AUTO_LOADS = 12;
    private static final long AUTO_LOAD_GAP_MS = 700L;

    private volatile long lastAutoLoadAt;

    private final Handler main = createMainHandler();

    /** Compiled keyword cache. */
    private final Object keywordLock = new Object();
    private String keywordRaw;
    private List<Object> keywordMatchers;

    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> getterCache =
            new ConcurrentHashMap<>();
    private static final Object NO_METHOD = new Object();

    public CommentFilterHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    /** Rebind comment list after settings change. */
    public static void refresh() {
        CommentFilterHook instance = sInstance;
        if (instance != null) {
            instance.requestRebind();
        }
    }

    public void install(ClassLoader cl) {
        boolean data = hookPostCommentsGetter(cl);
        // generic comment adapter
        boolean broad = hookBaseAdapterBind(cl);
        int legacy = broad ? 0 : hookCommentAdapterBinds(cl);
        // sub-comments: preview watcher + auto load more
        boolean subList = hookSubCommentListFilter(cl);
        // sub-comment rows: data-based hide (container kept)
        int subRows = hookSubCommentRowBinds(cl);
        module.logd(Log.WARN, module.TAG, "[评论过滤] Hook 安装结果：数据层="
                + (data ? "✔" : "✘") + " / 通用列表=" + (broad ? "✔" : "✘")
                + " / 指定适配器=" + legacy + " 处"
                + " / 楼中楼列表=" + (subList ? "✔" : "✘")
                + " / 楼中楼行=" + subRows + " 处"
                + " / 兜底过滤=" + (isEnabled() ? "开启" : "关闭")
                + " / 屏蔽插眼=" + (isHostHideCyEnabled() ? "开启" : "关闭"));
    }

    // ---------- data layer ----------

    private boolean hookPostCommentsGetter(ClassLoader cl) {
        try {
            Class<?> model = Class.forName(POST_COMMENT_SECTION_CLASS, false, cl);
            Method getter = model.getDeclaredMethod("getPostComments");
            module.hook(getter).intercept(chain -> {
                Object raw = chain.proceed();
                try {
                    if (!cyMarkFilterActive() || !(raw instanceof List)) {
                        return raw;
                    }
                    List<?> filtered = filterFloors((List<?>) raw);
                    return filtered == null ? raw : filtered;
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "评论数据层过滤异常，放行: " + t);
                    return raw;
                }
            });
            Checkpoint.mark("评论过滤数据层安装: ok");
            return true;
        } catch (Throwable t) {
            Checkpoint.mark("评论过滤数据层安装失败: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 评论过滤数据层 Hook 失败: " + t);
            return false;
        }
    }

    /** Drop on hit; null = pass through. */
    private List<?> filterFloors(List<?> raw) {
        List<Object> keep = new ArrayList<>(raw.size());
        int blocked = 0;
        for (Object item : raw) {
            String reason = isEnabled() ? spamReason(item) : cyOnlyReason(item);
            if (reason == null) {
                keep.add(item);
                continue;
            }
            blocked++;
            logBlocked("数据层", item, reason);
        }
        if (blocked == 0) {
            return null;
        }
        module.logd(Log.INFO, module.TAG, "屏蔽评论[数据层] 本页共屏蔽 " + blocked + " 条");
        return keep;
    }

    // ---------- adapter bind ----------

    private int hookCommentAdapterBinds(ClassLoader cl) {
        int installed = 0;
        for (String name : ADAPTER_CLASSES) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                installed += hookAdapterClass(cls);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "评论列表适配器不可用: " + name + " (" + t + ")");
            }
        }
        Checkpoint.mark("评论过滤列表绑定安装: %d 处", installed);
        return installed;
    }

    private int hookAdapterClass(Class<?> cls) {
        List<Method> real = new ArrayList<>();
        List<Method> bridge = new ArrayList<>();
        for (Method method : cls.getDeclaredMethods()) {
            if (!isCommentBinder(method)) {
                continue;
            }
            if (method.isBridge() || method.isSynthetic()) {
                bridge.add(method);
            } else {
                real.add(method);
            }
        }
        List<Method> targets = real.isEmpty() ? bridge : real;
        for (Method method : targets) {
            module.hook(method).intercept(this::onAdapterBind);
            module.logd(Log.INFO, module.TAG, "✔ 评论列表绑定 Hook: "
                    + cls.getName() + "#" + method.getName());
        }
        return targets.size();
    }

    /** Comment floor binder. */
    private boolean isCommentBinder(Method method) {
        if (method.getReturnType() != void.class || method.getParameterCount() != 2) {
            return false;
        }
        Class<?>[] types = method.getParameterTypes();
        if (!COMMENTS_OBJ_CLASS.equals(types[1].getName())) {
            return false;
        }
        String holderName = types[0].getName();
        if (VIEW_HOLDER_CLASS.equals(holderName) || holderName.startsWith(HOLDER_PREFIX)) {
            return true;
        }
        try {
            Class<?> holder = Class.forName(VIEW_HOLDER_CLASS, false, types[0].getClassLoader());
            return holder.isAssignableFrom(types[0]);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object onAdapterBind(XposedInterface.Chain chain) throws Throwable {
        Object holder = chain.getArg(0);
        View itemView = holderView(holder);
        if (itemView != null) {
            restoreCyView(itemView);
        }
        Object result = chain.proceed();
        try {
            Object self = chain.getThisObject();
            if (self != null) {
                registerAdapter(self);
            }
            if (itemView == null) {
                return result;
            }
            if (isEnabled()) {
                String reason = spamReason(chain.getArg(1));
                if (reason != null) {
                    logBlocked("列表", chain.getArg(1), reason);
                    hideCyView(itemView);
                    return result;
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "评论列表过滤异常，放行: " + t);
        }
        return result;
    }

    private View holderView(Object holder) {
        if (holder == null) {
            return null;
        }
        try {
            Field field = holder.getClass().getField("itemView");
            Object value = field.get(holder);
            return value instanceof View ? (View) value : null;
        } catch (Throwable t) {
            return FeedItemHider.getItemView(holder);
        }
    }

    // ---------- generic list adapter ----------

    private boolean hookBaseAdapterBind(ClassLoader cl) {
        try {
            Class<?> base = Class.forName(BASE_ADAPTER_CLASS, false, cl);
            Class<?> holder = Class.forName(BASE_ADAPTER_HOLDER_CLASS, false, cl);
            Method bind = base.getDeclaredMethod("onBindViewHolder", holder, int.class);
            module.hook(bind).intercept(this::onBaseAdapterBind);
            Checkpoint.mark("评论过滤通用列表安装: ok");
            return true;
        } catch (Throwable t) {
            Checkpoint.mark("评论过滤通用列表安装失败: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 评论过滤通用列表 Hook 失败: " + t);
            return false;
        }
    }

    private Object onBaseAdapterBind(XposedInterface.Chain chain) throws Throwable {
        Object holder = chain.getArg(0);
        int position = ((Number) chain.getArg(1)).intValue();
        View itemView = holderView(holder);
        if (itemView != null) {
            restoreCyView(itemView);
        }
        Object result = chain.proceed();
        try {
            Object adapter = chain.getThisObject();
            if (adapter != null) {
                registerAdapter(adapter);
            }
            if (itemView == null) {
                return result;
            }
            Object data = itemData(adapter, position);
            if (!isCommentFloor(data)) {
                return result;
            }
            if (isEnabled()) {
                diagnose("列表", data);
                String reason = spamReason(data);
                if (reason != null) {
                    logBlocked("列表", data, reason);
                    hideCyView(itemView);
                    return result;
                }
            } else if (cyMarkFilterActive()) {
                String reason = cyOnlyReason(data);
                if (reason != null) {
                    logBlocked("列表", data, reason);
                    hideCyView(itemView);
                    return result;
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "评论列表过滤异常，放行: " + t);
        }
        return result;
    }

    // ---------- sub-comment rows ----------

    /** Sub-comment row binder (data-based only). */
    private int hookSubCommentRowBinds(ClassLoader cl) {
        int installed = 0;
        for (String name : ADAPTER_CLASSES) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                for (Method method : cls.getDeclaredMethods()) {
                    if (method.getReturnType() != void.class || method.getParameterCount() != 5) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (!COMMENT_OBJ_CLASS.equals(types[2].getName())
                            || !COMMENT_OBJ_CLASS.equals(types[3].getName())
                            || !COMMENTS_OBJ_CLASS.equals(types[4].getName())) {
                        continue;
                    }
                    if (!hasRowViewGetter(types[1])) {
                        continue;
                    }
                    module.hook(method).intercept(this::onSubCommentRowBind);
                    installed++;
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "楼中楼行挂点跳过 " + name + ": " + t);
            }
        }
        Checkpoint.mark("评论过滤楼中楼行安装: %d 处", installed);
        return installed;
    }

    private boolean hasRowViewGetter(Class<?> holder) {
        try {
            return View.class.isAssignableFrom(holder.getMethod("c").getReturnType());
        } catch (Throwable t) {
            return false;
        }
    }

    private View holderRowView(Object holder) {
        if (holder == null) {
            return null;
        }
        try {
            Object value = holder.getClass().getMethod("c").invoke(holder);
            return value instanceof View ? (View) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object onSubCommentRowBind(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            if (!cyMarkFilterActive()) {
                return result;
            }
            View row = holderRowView(chain.getArg(1));
            if (row == null) {
                return result;
            }
            Object comment = chain.getArg(2);
            String reason = isEnabled() ? singleReason(comment) : cyMarkReason(comment);
            if (reason != null) {
                hideCyView(row);
                module.logd(Log.INFO, module.TAG, "屏蔽评论[楼中楼行] 原因=" + reason
                        + ", commentid=" + safeGet(comment, "getCommentid"));
            } else {
                // restore only rows we hid
                restoreCyView(row);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "楼中楼行过滤异常，放行: " + t);
        }
        return result;
    }

    private boolean hookSubCommentListFilter(ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(SUB_COMMENT_VIEW_CLASS, false, cl);
            module.hook(cls.getDeclaredMethod("setTotalList", List.class)).intercept(this::onSetTotalList);
            Checkpoint.mark("评论过滤楼中楼列表安装: ok");
            return true;
        } catch (Throwable t) {
            Checkpoint.mark("评论过滤楼中楼列表安装失败: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 评论过滤楼中楼列表 Hook 失败: " + t);
            return false;
        }
    }

    private Object onSetTotalList(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            Object arg = chain.getArg(0);
            if (cyMarkFilterActive() && arg instanceof List) {
                List<?> list = (List<?>) arg;
                if (list.size() > 1) {
                    Object floorKey = list.get(0);
                    int hidden = countFilteredSubComments(list);
                    int loaded = list.size() - 1;
                    int total = parseInt(safeGet(floorKey, "getChildNum"), 0);
                    boolean needMore = total > 0 ? loaded < total : loaded - hidden < VISIBLE_TARGET;
                    if (hidden > 0 && needMore) {
                        module.logd(Log.INFO, module.TAG, "楼中楼自动补数据：已加载 " + loaded + "/"
                                + (total > 0 ? String.valueOf(total) : "?") + " 条、屏蔽 " + hidden
                                + " 条，下一页游标=" + safeGet(list.get(list.size() - 1), "getCommentid"));
                        scheduleAutoLoadMore(chain.getThisObject(), floorKey);
                    }
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "楼中楼预览检查异常，放行: " + t);
        }
        return result;
    }

    private int countFilteredSubComments(List<?> list) {
        int hidden = 0;
        for (int i = 1; i < list.size(); i++) {
            Object comment = list.get(i);
            if (comment != null && (isEnabled() ? singleReason(comment) : cyMarkReason(comment)) != null) {
                hidden++;
            }
        }
        return hidden;
    }

    private static int parseInt(String text, int def) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private void scheduleAutoLoadMore(Object sub, Object floorKey) {
        if (main == null || !(sub instanceof ViewGroup) || floorKey == null) {
            return;
        }
        final ViewGroup group = (ViewGroup) sub;
        main.post(() -> tryAutoLoad(group, floorKey, 0));
    }

    private void tryAutoLoad(ViewGroup group, Object floorKey, int retry) {
        try {
            if (SystemClock.elapsedRealtime() - lastAutoLoadAt < AUTO_LOAD_GAP_MS) {
                if (retry < 3) {
                    main.postDelayed(() -> tryAutoLoad(group, floorKey, retry + 1), AUTO_LOAD_GAP_MS);
                }
                return;
            }
            synchronized (autoLoadCounts) {
                Integer done = autoLoadCounts.get(floorKey);
                if (done != null && done >= MAX_AUTO_LOADS) {
                    return;
                }
            }
            View footer = findLoadMoreFooter(group);
            if (footer == null) {
                return;
            }
                synchronized (autoLoadCounts) {
                    Integer done = autoLoadCounts.get(floorKey);
                    autoLoadCounts.put(floorKey, done == null ? 1 : done + 1);
                }
                lastAutoLoadAt = SystemClock.elapsedRealtime();
                footer.performClick();
                module.logd(Log.INFO, module.TAG, "楼中楼可显示评论不足，已自动加载下一页");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "自动加载更多回复失败: " + t);
        }
    }

    /** Find view-more footer (text based, skip collapse). */
    private View findLoadMoreFooter(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            String text = firstText(child, 0);
            if (text.contains("回复") && !text.contains("收起")) {
                return child;
            }
        }
        return null;
    }

    private String firstText(View view, int depth) {
        if (view == null || depth > 6) {
            return "";
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null ? "" : text.toString();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String text = firstText(group.getChildAt(i), depth + 1);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private void hideCyView(View view) {
        if (view == null) {
            return;
        }
        FeedItemHider.hide(view);
    }

    private void restoreCyView(View view) {
        if (view == null) {
            return;
        }
        FeedItemHider.restore(view);
    }

    // ---------- checks ----------

    /** Fallback switch. */
    private boolean isEnabled() {
        return module.isEnabled(App.KEY_BLOCK_CY_COMMENT, false);
    }

    /** Host hide-cy switch (default on). */
    private boolean isHostHideCyEnabled() {
        return module.isEnabled(App.KEY_HOST_HIDE_CY, true);
    }

    /** Either switch on. */
    private boolean cyMarkFilterActive() {
        return isEnabled() || isHostHideCyEnabled();
    }

    private String spamReason(Object floor) {
        if (floor == null) {
            return null;
        }
        if (isMeaninglessModel(floor)) {
            return "无意义评论折叠行";
        }
        List<?> comments = commentList(floor);
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        Object first = comments.get(0);
        String reason = singleReason(first);
        if (reason != null) {
            return reason;
        }
        return null;
    }

    /** Cy mark only: is_cy or pure cy text. */
    private String cyMarkReason(Object comment) {
        if (comment == null) {
            return null;
        }
        if (isTruthy(safeGet(comment, "getIs_cy"))) {
            return "cy 评论";
        }
        if (isCyText(safeGet(comment, "getText"))) {
            return "cy 评论（纯插眼文本）";
        }
        return null;
    }

    /** Floor main comment has Cy mark. */
    private String cyOnlyReason(Object floor) {
        if (floor == null || isMeaninglessModel(floor)) {
            return null;
        }
        List<?> comments = commentList(floor);
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        return cyMarkReason(comments.get(0));
    }

    private String singleReason(Object comment) {
        if (comment == null) {
            return null;
        }
        if (isTruthy(safeGet(comment, "getIs_cy"))) {
            return "cy 评论";
        }
        if (isTruthy(safeGet(comment, "getIs_meaningless"))) {
            return "无意义评论";
        }
        String text = safeGet(comment, "getText");
        if (isCyText(text)) {
            return "cy 评论（纯插眼文本）";
        }
        String keyword = keywordHit(text);
        return keyword == null ? null : "命中关键词 " + keyword;
    }

    private boolean isMeaninglessModel(Object obj) {
        return hasClassNamed(obj, MEANINGLESS_MODEL_CLASS);
    }

    private boolean isCommentObj(Object data) {
        return hasClassNamed(data, COMMENT_OBJ_CLASS);
    }

    private boolean isCommentFloor(Object data) {
        return hasClassNamed(data, COMMENTS_OBJ_CLASS);
    }

    private boolean hasClassNamed(Object obj, String name) {
        if (obj == null) {
            return false;
        }
        for (Class<?> cls = obj.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            if (name.equals(cls.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Verbose per-comment log. */
    private void diagnose(String where, Object item) {
        if (!module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            return;
        }
        List<?> comments = commentList(item);
        Object comment = comments != null && !comments.isEmpty() ? comments.get(0)
                : (isCommentObj(item) ? item : null);
        if (comment == null) {
            return;
        }
        module.logd(Log.INFO, module.TAG, "[评论过滤] 诊断[" + where + "] cy="
                + safeGet(comment, "getIs_cy") + " meaningless="
                + safeGet(comment, "getIs_meaningless") + " 正文="
                + abbreviate(safeGet(comment, "getText")));
    }

    private static String abbreviate(String text) {
        if (text == null || text.isEmpty()) {
            return "-";
        }
        return text.length() <= 16 ? text : text.substring(0, 16);
    }

    private List<?> commentList(Object floor) {
        Object value = safeInvoke(floor, "getComment");
        return value instanceof List ? (List<?>) value : null;
    }

    /** Pure cy text. */
    private boolean isCyText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String text = INVISIBLE.matcher(raw).replaceAll("").toLowerCase(Locale.ROOT);
        return "cy".equals(text) || "插眼".equals(text);
    }

    // ---------- keywords ----------

    private String keywordHit(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        List<Object> matchers = keywordMatchers();
        if (matchers.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Object matcher : matchers) {
            if (matcher instanceof Pattern) {
                Pattern pattern = (Pattern) matcher;
                if (pattern.matcher(lower).find()) {
                    return "regex:" + pattern.pattern();
                }
            } else if (lower.contains((String) matcher)) {
                return (String) matcher;
            }
        }
        return null;
    }

    private List<Object> keywordMatchers() {
        String raw = module.getString(App.KEY_COMMENT_KEYWORDS, "");
        synchronized (keywordLock) {
            if (keywordMatchers != null && raw.equals(keywordRaw)) {
                return keywordMatchers;
            }
        }
        List<Object> list = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String keyword = line.trim();
            if (keyword.isEmpty()) {
                continue;
            }
            if (keyword.startsWith("regex:")) {
                try {
                    list.add(Pattern.compile(keyword.substring(6).trim(), Pattern.CASE_INSENSITIVE));
                    continue;
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "评论关键词无效正则已忽略: " + keyword);
                    continue;
                }
            }
            list.add(keyword.toLowerCase(Locale.ROOT));
        }
        synchronized (keywordLock) {
            keywordRaw = raw;
            keywordMatchers = list;
        }
        return list;
    }

    // ---------- rebind ----------

    private void registerAdapter(Object adapter) {
        synchronized (adapterRefs) {
            adapterRefs.put(adapter, Boolean.TRUE);
        }
    }

    private void requestRebind() {
        final Object[] snapshot;
        synchronized (adapterRefs) {
            snapshot = adapterRefs.keySet().toArray();
        }
        if (snapshot.length == 0 || main == null) {
            return;
        }
        main.post(() -> {
            int rebound = 0;
            for (Object adapter : snapshot) {
                try {
                    adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
                    rebound++;
                } catch (Throwable ignored) {
                }
            }
            module.logd(Log.INFO, module.TAG, "[评论过滤] 设置已变更，已请求 " + rebound + " 个评论列表重绑");
        });
    }

    private static Handler createMainHandler() {
        try {
            return new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- reflection ----------

    private String safeGet(Object item, String getter) {
        Object value = safeInvoke(item, getter);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Object safeInvoke(Object item, String getter) {
        if (item == null) {
            return null;
        }
        try {
            Method method = findGetter(item.getClass(), getter);
            return method == null ? null : method.invoke(item);
        } catch (Throwable t) {
            return null;
        }
    }

    private Method findGetter(Class<?> cls, String name) {
        return findGetter(cls, name, new Class<?>[0]);
    }

    private Method findGetter(Class<?> cls, String name, Class<?>... params) {
        String key = params.length == 0 ? name : name + ":" + params[0].getName();
        ConcurrentHashMap<String, Object> byName = getterCache.get(cls);
        if (byName == null) {
            ConcurrentHashMap<String, Object> created = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Object> prev = getterCache.putIfAbsent(cls, created);
            byName = prev == null ? created : prev;
        }
        Object cached = byName.get(key);
        if (cached != null) {
            return cached == NO_METHOD ? null : (Method) cached;
        }
        Method found = null;
        try {
            found = cls.getMethod(name, params);
        } catch (Throwable ignored) {
        }
        byName.put(key, found == null ? NO_METHOD : found);
        return found;
    }

    private Object itemData(Object adapter, int position) {
        if (adapter == null) {
            return null;
        }
        try {
            Method method = findGetter(adapter.getClass(), "getItemData", int.class);
            return method == null ? null : method.invoke(adapter, position);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private void logBlocked(String where, Object item, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("屏蔽评论[").append(where).append("] 原因=").append(reason);
        List<?> comments = commentList(item);
        Object first = comments != null && !comments.isEmpty() ? comments.get(0)
                : (isCommentObj(item) ? item : null);
        if (first != null) {
            String id = safeGet(first, "getCommentid");
            if (!id.isEmpty()) {
                sb.append(", commentid=").append(id);
            }
            Object user = safeInvoke(first, "getUser");
            String author = user == null ? "" : safeGet(user, "getUsername");
            if (!author.isEmpty()) {
                sb.append(", 作者=").append(author);
            }
            if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                String text = safeGet(first, "getText");
                if (text.length() > 24) {
                    text = text.substring(0, 24);
                }
                sb.append(", 正文=").append(text);
            }
        }
        module.logd(Log.INFO, module.TAG, sb.toString());
    }
}
