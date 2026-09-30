package com.better.heybox.hooks;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.better.heybox.App;
import com.better.heybox.MainModule;

public final class PostDetailCleanHook {

    private static final String FRAGMENT_BASE_CLASS = "com.max.hbcommon.base.e";

    private static final String[] FRAGMENT_FALLBACK_CLASSES = new String[]{
            "com.max.hbcommon.base.e",
            "com.max.hbcommon.base.BaseFragment",
            "androidx.fragment.app.Fragment",
    };

    private static final String[] PAGE_ANCHORS = new String[]{
            "vg_relevant_search",
            "insert_fragment_container",
            "rv_post_guide",
            "vg_hashtag",
            "ll_article_collection",
    };

    private static final String[] IDS_RELEVANT_SEARCH = new String[]{
            "vg_relevant_search",
            "line_relevant_search",
    };

    private static final String[] IDS_POST_GUIDE = new String[]{
            "rv_post_guide",
    };

    private static final String[] IDS_MINI_APP = new String[]{
            "vp_hor_mini_program",
            "rv_mini_program_v3",
    };

    private static final String[] IDS_COLLECTION = new String[]{
            "ll_article_collection",
    };

    private static final String[] IDS_EVENT = new String[]{
            "rv_event_entry",
    };

    private static final String[] IDS_RELEVANT_SEARCH_BODY = new String[]{
            "rv_search_query",
            "relevant_search_divider",
    };

    private static final String[] RELEVANT_SEARCH_TEXTS = new String[]{
            "相关搜索", "都在搜", "大家还在搜", "相似搜索",
    };

    private static final int MAX_TEXT_CONTAINER_UP = 6;

    private static final String IDS_INSERT_CONTAINER = "insert_fragment_container";

    private static final String IDS_TOPIC_ROW = "vg_hashtag";

    private static final String[][] HIDE_GROUPS = new String[][]{
            IDS_RELEVANT_SEARCH,
            IDS_RELEVANT_SEARCH_BODY,
            IDS_POST_GUIDE,
            IDS_MINI_APP,
            IDS_COLLECTION,
            IDS_EVENT,
    };

    private static final String[] GROUP_LABELS = new String[]{
            "相关搜索(评论tab)",
            "相关搜索(正文tab)",
            "文字配图横幅",
            "小程序推荐",
            "合集",
            "活动",
    };

    private static final String[] CANDIDATE_KEYWORDS = new String[]{
            "workshop", "gongfang", "factory", "mini_app", "mini_program",
            "activity", "event", "collection", "compilation",
    };

    private static final String[] CANDIDATE_TEXTS = new String[]{
            "工坊",
    };

    private static final int MAX_WALK_DEPTH = 24;
    private static final int MAX_WALK_NODES = 3000;
    private static final int MAX_CANDIDATES = 40;

    public static final String BUILD_MARKER = "r7-topichide";

    private final MainModule module;

    private static volatile PostDetailCleanHook sInstance;

    private static volatile boolean sClean;
    private static volatile boolean sTopicNoClick;
    private static volatile boolean sTopicHide;

    private static volatile int sTopicRowId;

    private static final Map<View, Integer> sTopicHiddenOriginal =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());

    private static final Set<View> sTopicSuppressed =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));

    private static volatile Set<String> sCustomIds = Collections.emptySet();

    private static final Map<View, Boolean> sSeen =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private static final Map<View, Boolean> sWatching =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private static final Map<View, Integer> sHiddenOriginal =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());

    private static final Map<View, boolean[]> sClickOriginal =
            Collections.synchronizedMap(new WeakHashMap<View, boolean[]>());

    private static final Map<String, Integer> sIds = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sIdMisses = new ConcurrentHashMap<>();
    private static final Set<String> sSeenCustom = ConcurrentHashMap.newKeySet();

    private static final Map<View, Boolean> sProcessed =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private static final Map<View, Long> sCheckedAt =
            Collections.synchronizedMap(new WeakHashMap<View, Long>());

    private static final long RESHOW_CHECK_INTERVAL_MS = 150L;

    private static final Map<String, String> sCandidates =
            Collections.synchronizedMap(new LinkedHashMap<String, String>());

    private static final Set<String> sDetailHits = ConcurrentHashMap.newKeySet();

    private static volatile int sHookCreateView;
    private static volatile int sHookResume;
    private static volatile int sHookSetVisibility;


    private static volatile Set<Integer> sSuppressedIds;
    private static final Object S_ID_LOCK = new Object();
    private static final Map<String, Integer> sIdByName = new ConcurrentHashMap<>();
    private static final Map<Integer, String> sNameById = new ConcurrentHashMap<>();

    private static final Set<View> sSuppressed =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));

    private static final java.util.concurrent.atomic.AtomicLong sSuppressHits =
            new java.util.concurrent.atomic.AtomicLong();

    private static volatile View sTopicRow;

    private static final Set<View> sTopicRows =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));

    private static volatile boolean sInDetailPage;

    private static final java.util.concurrent.atomic.AtomicLong sTextHideHits =
            new java.util.concurrent.atomic.AtomicLong();

    private static volatile int sHookSetText;

    private static final java.util.concurrent.atomic.AtomicLong sTopicClickBlocked =
            new java.util.concurrent.atomic.AtomicLong();

    private static volatile int sHookClickListener;

    private static volatile String sActivityName = "未知";

    private static volatile String sLastApply = "还没跑过";

    public PostDetailCleanHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    public static void refresh() {
        PostDetailCleanHook instance = sInstance;
        if (instance == null) {
            return;
        }
        MainModule m = instance.module;
        sClean = m.isEnabled(App.KEY_POST_DETAIL_CLEAN, false);
        sTopicNoClick = m.isEnabled(App.KEY_POST_DETAIL_TOPIC_NO_CLICK, false);
        sTopicHide = m.isEnabled(App.KEY_POST_DETAIL_TOPIC_HIDE, false);
        sCustomIds = parseIds(m.getString(App.KEY_POST_DETAIL_HIDE_IDS, ""));
        clearSuppressedIds();
        sProcessed.clear();
        for (View root : new ArrayList<>(sSeen.keySet())) {
            try {
                apply(root, true);
            } catch (Throwable ignored) {
            }
        }
        if (!sClean) {
            restoreSuppressed();
        }
        if (!sTopicHide) {
            for (View view : new ArrayList<>(sTopicSuppressed)) {
                try {
                    if (view != null) {
                        view.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {
                }
            }
            sTopicSuppressed.clear();
        }
    }

    private static void clearSuppressedIds() {
        synchronized (S_ID_LOCK) {
            sSuppressedIds = null;
            sIdByName.clear();
            sNameById.clear();
        }
    }

    private static void restoreSuppressed() {
        for (View view : new ArrayList<>(sSuppressed)) {
            if (view == null) {
                continue;
            }
            try {
                view.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {
            }
        }
        sSuppressed.clear();
    }

    public void install(ClassLoader cl) {
        refresh();
        int hooked = 0;
        hooked += hookOnCreateView(cl);
        hooked += hookOnResume(cl);
        hooked += hookSetVisibility(cl);
        hooked += hookClickListener(cl);
        hooked += hookSetText(cl);
        hooked += hookWebViewClient();
        module.logd(Log.INFO, module.TAG, "✔ 帖子详情精简 Hook 已安装（" + hooked + " 处）"
                + " | 屏蔽=" + onOff(sClean)
                + " 话题防误触=" + onOff(sTopicNoClick)
                + " 话题隐藏=" + onOff(sTopicHide)
                + " 自定义=" + sCustomIds.size() + " 个");
        if (hooked <= 1) {
            module.logd(Log.WARN, module.TAG,
                    "帖子详情精简：只有 " + hooked + " 个挂点成功，功能可能不完整");
        }
    }


    private static final String WEB_HIDE_SELECTORS =
            ".bbs-link-section-article-collection,"
                    + ".bbs-link-section-event-entry,"
                    + ".bbs-link-section-event-tip,"
                    + ".bbs-link-section-lottery,"
                    + ".bbs-link-view-story-related";

    private static final String WEB_TOPIC_SELECTORS =
            ".bbs-link-section-tags,.bbs-link-section-tags-item";

    private static final Set<Class<?>> sHookedWebClients =
            Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static final java.util.concurrent.atomic.AtomicLong sWebInjected =
            new java.util.concurrent.atomic.AtomicLong();

    private int hookWebViewClient() {
        try {
            Method setter = WebView.class.getDeclaredMethod("setWebViewClient", WebViewClient.class);
            module.hook(setter).intercept(chain -> {
                Object client = chain.getArg(0);
                if (client != null) {
                    try {
                        hookPageFinished(client.getClass());
                    } catch (Throwable ignored) {
                    }
                }
                return chain.proceed();
            });
            return 1;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "帖子详情精简：WebView 注入 Hook 失败: " + t);
            return 0;
        }
    }

    private void hookPageFinished(Class<?> clientClass) {
        if (clientClass == null || !clientClass.getName().startsWith("com.max.")
                || !sHookedWebClients.add(clientClass)) {
            return;
        }
        for (Class<?> c = clientClass; c != null && c != WebViewClient.class; c = c.getSuperclass()) {
            Method target;
            try {
                target = c.getDeclaredMethod("onPageFinished", WebView.class, String.class);
            } catch (NoSuchMethodException e) {
                continue;
            }
            if (!sHookedWebClients.add(c) && c != clientClass) {
                return;
            }
            try {
                target.setAccessible(true);
                module.hook(target).intercept(chain -> {
                    Object result = chain.proceed();
                    Object web = chain.getArg(0);
                    if (web instanceof WebView) {
                        injectWebClean((WebView) web);
                    }
                    return result;
                });
                module.logd(Log.INFO, module.TAG, "✔ 帖子详情精简 WebView 注入 @ " + c.getName());
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "帖子详情精简：onPageFinished 挂载失败: " + t);
            }
            return;
        }
    }

    private static void injectWebClean(WebView web) {
        try {
            if (!anyEnabled() || !isDetailWebView(web)) {
                return;
            }
            web.evaluateJavascript(buildWebCleanJs(sClean, sTopicNoClick, sTopicHide), null);
            sWebInjected.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isDetailWebView(WebView web) {
        if (sSeen.containsKey(web.getRootView()) || sInDetailPage) {
            return true;
        }
        android.content.Context ctx = web.getContext();
        while (ctx instanceof android.content.ContextWrapper && !(ctx instanceof android.app.Activity)) {
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return ctx instanceof android.app.Activity
                && ctx.getClass().getName().contains("PostPage");
    }

    static String buildWebCleanJs(boolean clean, boolean topicNoClick, boolean topicHide) {
        StringBuilder css = new StringBuilder();
        if (clean) {
            css.append(WEB_HIDE_SELECTORS).append("{display:none!important}");
        }
        if (topicNoClick) {
            css.append(WEB_TOPIC_SELECTORS).append("{pointer-events:none!important}");
        }
        if (topicHide) {
            css.append(WEB_TOPIC_SELECTORS).append("{display:none!important}");
        }
        return "(function(){try{"
                + "var d=document,s=d.getElementById('bh-post-clean');"
                + "if(!s){s=d.createElement('style');s.id='bh-post-clean';"
                + "(d.head||d.documentElement).appendChild(s);}"
                + "s.textContent='" + css + "';"
                + "window.__bhTopicNoClick=" + topicNoClick + ";"
                + "if(!window.__bhTopicStop){window.__bhTopicStop=1;"
                + "d.addEventListener('click',function(e){"
                + "if(window.__bhTopicNoClick&&e.target&&e.target.closest"
                + "&&e.target.closest('" + WEB_TOPIC_SELECTORS + "')){"
                + "e.stopPropagation();e.preventDefault();}},true);}"
                + "if(!window.__bhObs&&window.MutationObserver){"
                + "window.__bhObs=new MutationObserver(function(){"
                + "if(!d.getElementById('bh-post-clean')&&s){(d.head||d.documentElement).appendChild(s);}});"
                + "window.__bhObs.observe(d.documentElement,{childList:true,subtree:true});}"
                + "}catch(e){}})();";
    }

    private int hookSetVisibility(ClassLoader cl) {
        try {
            Class<?> viewClass = Class.forName("android.view.View", false, cl);
            Method setVisibility = viewClass.getDeclaredMethod("setVisibility", int.class);
            setVisibility.setAccessible(true);
            module.hook(setVisibility).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object target = chain.getThisObject();
                    Object arg = chain.getArg(0);
                    if (!(target instanceof View) || !(arg instanceof Integer)
                            || (!sClean && !sTopicHide)) {
                        return result;
                    }
                    int visibility = (Integer) arg;
                    if (visibility == View.GONE) {
                        return result;
                    }
                    View view = (View) target;
                    int topicId = sTopicRowId;
                    if (sTopicHide && topicId != 0 && view.getId() == topicId) {
                        View topicRoot = view.getRootView();
                        if (sSeen.containsKey(topicRoot)
                                || (sInDetailPage && !view.isAttachedToWindow())) {
                            View flexHost = flexboxRecyclerParent(view);
                            View hideTarget = flexHost != null ? flexHost : view;
                            try {
                                hideTarget.setVisibility(View.GONE);
                            } catch (Throwable ignored) {
                            }
                            sTopicSuppressed.add(hideTarget);
                        }
                        return null;
                    }
                    if (!sClean || !isSuppressedId(view)) {
                        return result;
                    }
                    View root = view.getRootView();
                    if (!sSeen.containsKey(root)
                            && !(sInDetailPage && !view.isAttachedToWindow())) {
                        return result;
                    }
                    try {
                        view.setVisibility(View.GONE);
                    } catch (Throwable ignored) {
                    }
                    sSuppressed.add(view);
                    long hits = sSuppressHits.incrementAndGet();
                    if (hits <= 5) {
                        module.logd(Log.INFO, module.TAG,
                                "帖子详情精简：兜底拦下 " + sNameById.get(view.getId())
                                        + " 的显示请求（第 " + hits + " 次）");
                    }
                } catch (Throwable ignored) {
                }
                return null;
            });
            module.logd(Log.INFO, module.TAG, "✔ 帖子详情精简 Hook：View.setVisibility 兜底");
            sHookSetVisibility = 1;
            return 1;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "帖子详情精简：setVisibility 兜底挂载失败: " + t);
            return 0;
        }
    }

    private int hookClickListener(ClassLoader cl) {
        try {
            Class<?> viewClass = Class.forName("android.view.View", false, cl);
            Class<?> listenerClass = Class.forName("android.view.View$OnClickListener", false, cl);
            Method setOnClickListener = viewClass.getDeclaredMethod("setOnClickListener", listenerClass);
            setOnClickListener.setAccessible(true);
            module.hook(setOnClickListener).intercept(chain -> {
                Object target = chain.getThisObject();
                Object listener = chain.getArg(0);
                if (!(target instanceof View) || listener == null || !sTopicNoClick) {
                    return chain.proceed();
                }
                View view = (View) target;
                if (!isInTopicRow(view)) {
                    return chain.proceed();
                }
                long hits = sTopicClickBlocked.incrementAndGet();
                if (hits <= 5) {
                    module.logd(Log.INFO, module.TAG,
                            "帖子详情精简：拦下话题行子 View 的点击监听注册（第 " + hits + " 次）");
                }
                return null;
            });
            module.logd(Log.INFO, module.TAG, "✔ 帖子详情精简 Hook：话题行点击监听拦截");
            sHookClickListener = 1;
            return 1;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "帖子详情精简：点击监听拦截挂载失败: " + t);
            return 0;
        }
    }

    private static boolean isInTopicRow(View view) {
        if (sTopicRows.isEmpty()) {
            return false;
        }
        View node = view;
        for (int i = 0; i < 16 && node != null; i++) {
            if (sTopicRows.contains(node)) {
                return true;
            }
            android.view.ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private int hookSetText(ClassLoader cl) {
        try {
            Class<?> textViewClass = Class.forName("android.widget.TextView", false, cl);
            Method setText = textViewClass.getDeclaredMethod("setText", CharSequence.class);
            setText.setAccessible(true);
            module.hook(setText).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object target = chain.getThisObject();
                    if (!(target instanceof View) || !sClean || !sInDetailPage) {
                        return result;
                    }
                    View view = (View) target;
                    if (view.getVisibility() == View.GONE) {
                        return result;
                    }
                    String text = textOf(view);
                    if (!looksLikeRelevantSearchTitle(text)) {
                        return result;
                    }
                    View row = findTextRowContainer(view);
                    if (row == null) {
                        return result;
                    }
                    long hits = sTextHideHits.incrementAndGet();
                    if (hits <= 8) {
                        module.logd(Log.INFO, module.TAG,
                                "帖子详情精简：按文本拦下「" + text + "」所在行（第 " + hits + " 次）"
                                        + " class=" + row.getClass().getName()
                                        + " id=" + idLabel(row)
                                        + " 路径=" + ancestorPath(row));
                    }
                    hide(row, "相关搜索(文本)", "文本:" + text);
                } catch (Throwable ignored) {
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 帖子详情精简 Hook：TextView.setText 文本兜底");
            sHookSetText = 1;
            return 1;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "帖子详情精简：setText 文本兜底挂载失败: " + t);
            return 0;
        }
    }

    private static boolean isSuppressedId(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID || id == 0) {
                return false;
            }
            Set<Integer> ids = ensureSuppressedIds(view);
            if (ids != null && ids.contains(id)) {
                resolveNamesFor(view, id);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Set<Integer> ensureSuppressedIds(View view) {
        Set<Integer> cached = sSuppressedIds;
        if (cached != null) {
            return cached;
        }
        synchronized (S_ID_LOCK) {
            if (sSuppressedIds != null) {
                return sSuppressedIds;
            }
            Context context = contextOf(view);
            if (context == null) {
                return null;
            }
            Resources resources = context.getResources();
            if (resources == null) {
                return null;
            }
            Set<Integer> ids = new java.util.HashSet<>();
            for (String[] group : HIDE_GROUPS) {
                for (String name : group) {
                    int id = resources.getIdentifier(name, "id", context.getPackageName());
                    if (id == 0) {
                        id = resources.getIdentifier(name, "id", MainModule.TARGET_PKG);
                    }
                    if (id != 0) {
                        ids.add(id);
                        sIdByName.put(name, id);
                        sNameById.put(id, name);
                    }
                }
            }
            for (String name : sCustomIds) {
                int id = resources.getIdentifier(name, "id", context.getPackageName());
                if (id == 0) {
                    id = resources.getIdentifier(name, "id", MainModule.TARGET_PKG);
                }
                if (id != 0) {
                    ids.add(id);
                    sIdByName.put(name, id);
                    sNameById.put(id, name);
                }
            }
            if (ids.isEmpty()) {
                return null;
            }
            sSuppressedIds = ids;
            return ids;
        }
    }

    private static void resolveNamesFor(View view, int id) {
        String name = sNameById.get(id);
        if (name != null) {
            sDetailHits.add(name);
        }
    }

    private static Context contextOf(View view) {
        try {
            Context context = view.getContext();
            if (context != null) {
                return context;
            }
            View root = view.getRootView();
            return root == null ? null : root.getContext();
        } catch (Throwable t) {
            return null;
        }
    }

    private int hookOnCreateView(ClassLoader cl) {
        int count = 0;
        for (String className : FRAGMENT_FALLBACK_CLASSES) {
            Class<?> fragment;
            try {
                fragment = Class.forName(className, false, cl);
            } catch (Throwable t) {
                continue;
            }
            try {
                Method onCreateView = fragment.getDeclaredMethod("onCreateView",
                        android.view.LayoutInflater.class, ViewGroup.class,
                        android.os.Bundle.class);
                onCreateView.setAccessible(true);
                module.hook(onCreateView).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof View) {
                        safeApply((View) result);
                    }
                    return result;
                });
                count++;
                sHookCreateView = count;
                module.logd(Log.INFO, module.TAG,
                        "✔ 帖子详情精简 Hook：onCreateView @ " + className);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG,
                        "帖子详情精简：" + className + " onCreateView 挂载失败: " + t);
            }
        }
        return count;
    }

    private int hookOnResume(ClassLoader cl) {
        Method getView = null;
        try {
            Class<?> fragmentBase = Class.forName("androidx.fragment.app.Fragment", false, cl);
            getView = fragmentBase.getMethod("getView");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "帖子详情精简：取不到 Fragment#getView: " + t);
            return 0;
        }
        final Method getViewRef = getView;
        int count = 0;
        for (String className : FRAGMENT_FALLBACK_CLASSES) {
            Class<?> fragment;
            try {
                fragment = Class.forName(className, false, cl);
            } catch (Throwable t) {
                continue;
            }
            try {
                Method onResume = fragment.getDeclaredMethod("onResume");
                onResume.setAccessible(true);
                module.hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object view = getViewRef.invoke(chain.getThisObject());
                        if (view instanceof View) {
                            safeApply((View) view);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
                count++;
                sHookResume = count;
                module.logd(Log.INFO, module.TAG,
                        "✔ 帖子详情精简 Hook：onResume @ " + className);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG,
                        "帖子详情精简：" + className + " onResume 挂载失败: " + t);
            }
        }
        return count;
    }

    private static boolean isPostDetailView(View view) {
        if (view == null) {
            return false;
        }
        boolean hit = false;
        for (String anchor : PAGE_ANCHORS) {
            int id = idOf(view, anchor);
            if (id != 0 && view.findViewById(id) != null) {
                sDetailHits.add(anchor);
                hit = true;
            }
        }
        return hit;
    }

    private static void safeApply(View root) {
        try {
            if (root == null || !anyEnabled()) {
                return;
            }
            if (!isPostDetailView(root)) {
                sInDetailPage = false;
                return;
            }
            sInDetailPage = true;
            View scope = root.getRootView();
            if (scope == null) {
                scope = root;
            }
            apply(scope, true);
        } catch (Throwable t) {
            PostDetailCleanHook instance = sInstance;
            if (instance != null) {
                instance.module.logd(Log.WARN, instance.module.TAG,
                        "帖子详情精简：apply 异常: " + t);
            }
        }
    }

    private static boolean anyEnabled() {
        return sClean || sTopicNoClick || sTopicHide;
    }

    private static String onOff(boolean enabled) {
        return enabled ? "开" : "关";
    }

    private static boolean apply(View root, boolean force) {
        if (root == null) {
            return false;
        }
        if (!force && sProcessed.containsKey(root)) {
            return false;
        }
        boolean firstSeen = sSeen.put(root, Boolean.TRUE) == null;
        boolean changed = false;
        int hit = 0;
        rememberActivity(root);
        if (sClean) {
            changed |= applyHideGroups(root);
            changed |= applyCustomIds(root);
            changed |= hideRelevantSearchByText(root);
            observeCandidates(root);
            hit = countFoundTargets(root);
        } else {
            changed |= restoreHidden();
        }
        if (sTopicNoClick) {
            rememberTopicRow(root);
            changed |= applyTopicNoClick(root);
        } else {
            changed |= restoreClicks();
        }
        if (sTopicHide) {
            changed |= applyTopicHide(root);
        } else {
            changed |= restoreTopicHidden();
        }
        sProcessed.put(root, Boolean.TRUE);
        if (anyEnabled()) {
            watch(root);
        }
        if (changed) {
            sLastApply = nowText() + " 改动生效";
        }
        if (firstSeen && anyEnabled()) {
            if (sForensicsDone.compareAndSet(false, true)) {
                logRootReady(root, hit);
                logTargetStates(root);
                sTextScanBudget.set(5000);
                logVisibleSearchNodes(root);
            }
            retryLater(root, 250L);
            retryLater(root, 900L);
        }
        return changed;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sForensicsDone =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void retryLater(final View root, long delayMs) {
        try {
            root.postDelayed(() -> {
                try {
                    if (!anyEnabled() || root.getWindowToken() == null) {
                        return;
                    }
                    sProcessed.remove(root);
                    apply(root, true);
                } catch (Throwable ignored) {
                }
            }, delayMs);
        } catch (Throwable ignored) {
        }
    }

    private static void rememberActivity(View root) {
        try {
            android.content.Context context = root.getContext();
            for (int i = 0; i < 8 && context instanceof android.content.ContextWrapper; i++) {
                if (context instanceof android.app.Activity) {
                    String name = context.getClass().getName();
                    if (!name.equals(sActivityName)) {
                        sActivityName = name;
                    }
                    return;
                }
                android.content.Context base =
                        ((android.content.ContextWrapper) context).getBaseContext();
                if (base == null || base == context) {
                    return;
                }
                context = base;
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean applyTopicHide(View root) {
        try {
            int id = idOf(root, IDS_TOPIC_ROW);
            if (id == 0) {
                return false;
            }
            sTopicRowId = id;
            View view = root.findViewById(id);
            if (view == null) {
                return false;
            }
            View flexHost = flexboxRecyclerParent(view);
            if (flexHost != null) {
                view = flexHost;
            }
            if (view.getVisibility() == View.GONE) {
                return false;
            }
            if (!sTopicHiddenOriginal.containsKey(view)) {
                sTopicHiddenOriginal.put(view, view.getVisibility());
            }
            view.setVisibility(View.GONE);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean restoreTopicHidden() {
        boolean changed = false;
        for (Map.Entry<View, Integer> entry : new ArrayList<>(sTopicHiddenOriginal.entrySet())) {
            View view = entry.getKey();
            Integer original = sTopicHiddenOriginal.remove(view);
            if (view == null || original == null) {
                continue;
            }
            if (view.getVisibility() != original) {
                view.setVisibility(original);
                changed = true;
            }
        }
        return changed;
    }

    private static void rememberTopicRow(View root) {
        try {
            int id = idOf(root, IDS_TOPIC_ROW);
            if (id != 0) {
                View topicRow = root.findViewById(id);
                if (topicRow != null) {
                    sTopicRow = topicRow;
                    sTopicRows.add(topicRow);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean hideRelevantSearchByText(View root) {
        sTextScanBudget.set(4000);
        List<View> titleNodes = new ArrayList<>();
        collectRelevantSearchTitles(root, 0, new int[]{0}, titleNodes);
        boolean changed = false;
        for (View title : titleNodes) {
            View container = findTextRowContainer(title);
            if (container == null) {
                continue;
            }
            if (container.getWidth() > 0 && container.getHeight() > 0
                    && container.getHeight() > title.getHeight() * 12) {
                continue;
            }
            changed |= hide(container, "相关搜索(文本)", "文本:" + textOf(title));
        }
        return changed;
    }

    private static void logVisibleSearchNodes(View root) {
        PostDetailCleanHook instance = sInstance;
        if (instance == null) {
            return;
        }
        List<View> found = new ArrayList<>();
        collectVisibleSearchNodes(root, 0, new int[]{0}, found);
        if (found.isEmpty()) {
            instance.module.logd(Log.INFO, instance.module.TAG,
                    "帖子详情精简：正文区没有可见的「搜」类文本节点");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (View view : found) {
            sb.append('\n').append("  ").append(idLabel(view)).append('=')
                    .append(visibilityName(view.getVisibility()))
                    .append(" 文本=").append(abbreviate(textOf(view)))
                    .append(" 路径=").append(ancestorPath(view));
        }
        sVisibleSearchDump = sb.toString();
        instance.module.logd(Log.INFO, instance.module.TAG,
                "帖子详情精简：可见的「搜」类节点（" + found.size() + " 个）" + sb);
    }

    private static void collectVisibleSearchNodes(View view, int depth, int[] visited,
                                                  List<View> out) {
        if (view == null || depth > 20 || visited[0] > 5000 || out.size() >= 25
                || sTextScanBudget.get() <= 0) {
            return;
        }
        visited[0]++;
        sTextScanBudget.decrementAndGet();
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            String text = textOf(view);
            if (text != null && text.contains("搜")) {
                out.add(view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 60);
            for (int i = 0; i < count; i++) {
                collectVisibleSearchNodes(group.getChildAt(i), depth + 1, visited, out);
            }
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    private static volatile String sVisibleSearchDump = "还没有抓过";

    private static void collectRelevantSearchTitles(View view, int depth, int[] visited,
                                                    List<View> out) {
        if (view == null || depth > 16 || visited[0] > 4000
                || sTextScanBudget.get() <= 0) {
            return;
        }
        visited[0]++;
        sTextScanBudget.decrementAndGet();
        if (view instanceof TextView) {
            String text = textOf(view);
            if (looksLikeRelevantSearchTitle(text)) {
                out.add(view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 60);
            for (int i = 0; i < count; i++) {
                collectRelevantSearchTitles(group.getChildAt(i), depth + 1, visited, out);
            }
        }
    }

    private static boolean looksLikeRelevantSearchTitle(String text) {
        if (text == null) {
            return false;
        }
        String value = text.trim();
        if (value.isEmpty() || value.length() > 12) {
            return false;
        }
        for (String keyword : RELEVANT_SEARCH_TEXTS) {
            if (value.startsWith(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static View findTextRowContainer(View title) {
        View node = title;
        View candidate = null;
        for (int i = 0; i < MAX_TEXT_CONTAINER_UP; i++) {
            android.view.ViewParent parent = node.getParent();
            if (!(parent instanceof ViewGroup)) {
                break;
            }
            ViewGroup group = (ViewGroup) parent;
            if (group.getChildCount() >= 2) {
                candidate = group;
                break;
            }
            node = group;
        }
        if (candidate == null) {
            android.view.ViewParent parent = title.getParent();
            candidate = parent instanceof View ? (View) parent : title;
        }
        return candidate;
    }

    private static final java.util.concurrent.atomic.AtomicInteger sTextScanBudget =
            new java.util.concurrent.atomic.AtomicInteger(4000);

    private static int countFoundTargets(View root) {
        int n = 0;
        for (String[] group : HIDE_GROUPS) {
            for (String name : group) {
                int id = idOf(root, name);
                if (id != 0 && root.findViewById(id) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean applyHideGroups(View root) {
        boolean changed = false;
        for (int i = 0; i < HIDE_GROUPS.length; i++) {
            changed |= applyGroup(root, GROUP_LABELS[i], HIDE_GROUPS[i]);
        }
        return changed;
    }

    private static boolean applyGroup(View root, String label, String[] names) {
        boolean changed = false;
        for (String name : names) {
            int id = idOf(root, name);
            if (id == 0) {
                continue;
            }
            View view = root.findViewById(id);
            if (view == null) {
                continue;
            }
            changed |= hide(view, label, name);
        }
        return changed;
    }

    private static boolean applyCustomIds(View root) {
        if (sCustomIds.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (String name : sCustomIds) {
            int id = idOf(root, name);
            View view = id == 0 ? null : root.findViewById(id);
            if (view == null) {
                view = findByIdName(scopeOf(root), name);
            }
            if (view == null) {
                if (sSeenCustom.add(name)) {
                    PostDetailCleanHook instance = sInstance;
                    if (instance != null) {
                        instance.module.logd(Log.WARN, instance.module.TAG,
                                "帖子详情精简：本页未找到自定义视图 " + name + "，跳过");
                    }
                }
                continue;
            }
            changed |= hide(view, "自定义", name);
        }
        return changed;
    }

    private static boolean hide(View view, String label, String name) {
        View flexHost = flexboxRecyclerParent(view);
        if (flexHost != null) {
            view = flexHost;
        }
        if (view.getVisibility() == View.GONE) {
            return false;
        }
        if (!sHiddenOriginal.containsKey(view)) {
            sHiddenOriginal.put(view, view.getVisibility());
        }
        view.setVisibility(View.GONE);
        PostDetailCleanHook instance = sInstance;
        if (instance != null && instance.module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            instance.module.logd(Log.INFO, instance.module.TAG,
                    "帖子详情精简：隐藏 " + label + " (" + name + ")");
        }
        return true;
    }

    private static boolean restoreHidden() {
        boolean changed = false;
        for (Map.Entry<View, Integer> entry : new ArrayList<>(sHiddenOriginal.entrySet())) {
            View view = entry.getKey();
            Integer original = sHiddenOriginal.remove(view);
            if (view == null || original == null) {
                continue;
            }
            if (view.getVisibility() != original) {
                view.setVisibility(original);
                changed = true;
            }
        }
        return changed;
    }

    private static View flexboxRecyclerParent(View view) {
        View child = view;
        for (int depth = 0; depth < 4 && child != null; depth++) {
            android.view.ViewParent parent = child.getParent();
            if (!(parent instanceof View)) {
                return null;
            }
            View parentView = (View) parent;
            if (isRecyclerView(parentView)) {
                try {
                    Object lm = parentView.getClass().getMethod("getLayoutManager").invoke(parentView);
                    if (lm != null && lm.getClass().getName().contains("Flexbox")) {
                        return parentView;
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }
            child = parentView;
        }
        return null;
    }

    private static boolean isRecyclerView(View view) {
        for (Class<?> cls = view.getClass(); cls != null; cls = cls.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(cls.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean applyTopicNoClick(View root) {
        int id = idOf(root, IDS_TOPIC_ROW);
        if (id == 0) {
            return false;
        }
        View topicRow = root.findViewById(id);
        if (topicRow == null) {
            return false;
        }
        boolean changed = false;
        if (topicRow.isClickable() || topicRow.isLongClickable() || topicRow.hasOnClickListeners()) {
            noteClickOriginal(topicRow);
            topicRow.setOnClickListener(null);
            topicRow.setOnLongClickListener(null);
            topicRow.setClickable(false);
            topicRow.setLongClickable(false);
            topicRow.setOnTouchListener(null);
            changed = true;
        }
        changed |= disableClicksDeep(topicRow, 0, new int[]{0});
        return changed;
    }

    private static boolean disableClicksDeep(View view, int depth, int[] visited) {
        if (view == null || depth > 12 || visited[0] > 400) {
            return false;
        }
        visited[0]++;
        boolean changed = false;
        if (view.isClickable() || view.isLongClickable() || view.hasOnClickListeners()) {
            noteClickOriginal(view);
            view.setOnClickListener(null);
            view.setOnLongClickListener(null);
            view.setClickable(false);
            view.setLongClickable(false);
            changed = true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 60);
            for (int i = 0; i < count; i++) {
                changed |= disableClicksDeep(group.getChildAt(i), depth + 1, visited);
            }
        }
        return changed;
    }

    private static void noteClickOriginal(View view) {
        if (!sClickOriginal.containsKey(view)) {
            sClickOriginal.put(view, new boolean[]{view.isClickable(), view.isLongClickable()});
        }
    }

    private static boolean restoreClicks() {
        boolean changed = false;
        for (Map.Entry<View, boolean[]> entry : new ArrayList<>(sClickOriginal.entrySet())) {
            View view = entry.getKey();
            boolean[] original = sClickOriginal.remove(view);
            if (view == null || original == null) {
                continue;
            }
            if (view.isClickable() != original[0]) {
                view.setClickable(original[0]);
                changed = true;
            }
            if (view.isLongClickable() != original[1]) {
                view.setLongClickable(original[1]);
                changed = true;
            }
        }
        return changed;
    }

    private static void observeCandidates(View root) {
        View scope = scopeOf(root);
        if (scope == null) {
            return;
        }
        int[] visited = new int[]{0};
        collectCandidates(scope, 0, visited);
    }

    private static void collectCandidates(View view, int depth, int[] visited) {
        if (view == null || depth > 8 || visited[0] > 300 || sCandidates.size() >= MAX_CANDIDATES) {
            return;
        }
        visited[0]++;
        if (looksLikeCandidate(view)) {
            String name = idNameOf(view);
            if (name != null) {
                String text = textOf(view);
                String previous = sCandidates.get(name);
                if (previous == null) {
                    sCandidates.put(name, text == null ? "" : text);
                    PostDetailCleanHook instance = sInstance;
                    if (instance != null) {
                        instance.module.logd(Log.INFO, instance.module.TAG,
                                "帖子详情精简：观察到候选区块 " + name
                                        + (text == null || text.isEmpty() ? "" : "（" + text + "）")
                                        + "，可加入自定义隐藏列表");
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 40);
            for (int i = 0; i < count; i++) {
                collectCandidates(group.getChildAt(i), depth + 1, visited);
            }
        }
    }

    private static boolean looksLikeCandidate(View view) {
        String name = idNameOf(view);
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String keyword : CANDIDATE_KEYWORDS) {
                if (lower.contains(keyword)) {
                    return true;
                }
            }
        }
        String text = textOf(view);
        if (text != null) {
            for (String keyword : CANDIDATE_TEXTS) {
                if (text.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String idNameOf(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID) {
                return null;
            }
            Context context = view.getContext();
            if (context == null) {
                return null;
            }
            return context.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String textOf(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null ? null : text.toString().trim();
        }
        return null;
    }

    private static View findByIdName(View view, String name) {
        if (view == null || name == null) {
            return null;
        }
        int[] visited = new int[]{0};
        return findByViewIdName(view, name, 0, visited);
    }

    private static View findByViewIdName(View view, String name, int depth, int[] visited) {
        if (view == null || depth > MAX_WALK_DEPTH || visited[0] > MAX_WALK_NODES) {
            return null;
        }
        visited[0]++;
        if (name.equals(idNameOf(view))) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 60);
            for (int i = 0; i < count; i++) {
                View hit = findByViewIdName(group.getChildAt(i), name, depth + 1, visited);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static boolean shouldCheckReshow(View root) {
        long now = android.os.SystemClock.uptimeMillis();
        Long last = sCheckedAt.get(root);
        if (last != null && now - last < RESHOW_CHECK_INTERVAL_MS) {
            return false;
        }
        sCheckedAt.put(root, now);
        return true;
    }

    private static boolean anyReShown(View root) {
        if (sClean) {
            for (String[] group : HIDE_GROUPS) {
                for (String name : group) {
                    int id = idOf(root, name);
                    if (id == 0) {
                        continue;
                    }
                    View view = root.findViewById(id);
                    if (view != null && view.getVisibility() != View.GONE) {
                        return true;
                    }
                }
            }
        }
        if (sTopicNoClick) {
            int id = idOf(root, IDS_TOPIC_ROW);
            if (id != 0) {
                View topicRow = root.findViewById(id);
                if (topicRow != null && topicRow.isClickable()) {
                    return true;
                }
            }
        }
        if (sTopicHide) {
            int id = idOf(root, IDS_TOPIC_ROW);
            if (id != 0) {
                View topicRow = root.findViewById(id);
                if (topicRow != null && topicRow.getVisibility() != View.GONE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void watch(final View root) {        if (sWatching.put(root, Boolean.TRUE) != null) {
            return;
        }
        try {
            root.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            try {
                                if (!anyEnabled() || !root.isAttachedToWindow()) {
                                    sWatching.remove(root);
                                    sProcessed.remove(root);
                                    ViewTreeObserver observer = root.getViewTreeObserver();
                                    if (observer != null && observer.isAlive()) {
                                        observer.removeOnPreDrawListener(this);
                                    }
                                    return true;
                                }
                                if (shouldCheckReshow(root) && anyReShown(root)) {
                                    sProcessed.remove(root);
                                }
                                apply(root, false);
                            } catch (Throwable ignored) {
                            }
                            return true;
                        }
                    });
        } catch (Throwable t) {
            sWatching.remove(root);
            PostDetailCleanHook instance = sInstance;
            if (instance != null) {
                instance.module.logd(Log.WARN, instance.module.TAG,
                        "帖子详情精简：挂绘制监听失败: " + t);
            }
        }
    }

    private static void logTargetStates(View root) {
        PostDetailCleanHook instance = sInstance;
        if (instance == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int visibleCount = 0;
        for (int i = 0; i < HIDE_GROUPS.length; i++) {
            for (String name : HIDE_GROUPS[i]) {
                int id = idOf(root, name);
                if (id == 0) {
                    continue;
                }
                View view = root.findViewById(id);
                if (view == null) {
                    continue;
                }
                if (view.getVisibility() != View.GONE) {
                    visibleCount++;
                    sb.append('\n').append("  仍可见 ").append(name).append('=')
                            .append(visibilityName(view.getVisibility()))
                            .append(" 路径=").append(ancestorPath(view));
                }
            }
        }
        int topicId = idOf(root, IDS_TOPIC_ROW);
        if (topicId != 0) {
            View topicRow = root.findViewById(topicId);
            if (topicRow != null && topicRow.getVisibility() != View.GONE) {
                visibleCount++;
                sb.append('\n').append("  仍可见 ").append(IDS_TOPIC_ROW)
                        .append(" clickable=").append(topicRow.isClickable())
                        .append(" hasListener=").append(topicRow.hasOnClickListeners())
                        .append(" 子View可点击=").append(countClickableDeep(topicRow, 0, new int[]{0}))
                        .append(" 路径=").append(ancestorPath(topicRow));
            }
        }
        instance.module.logd(Log.INFO, instance.module.TAG,
                "帖子详情精简：目标状态（仍可见 " + visibleCount + " 个）" + sb);
    }

    private static int countClickableDeep(View view, int depth, int[] visited) {
        if (view == null || depth > 12 || visited[0] > 400) {
            return 0;
        }
        visited[0]++;
        int n = (view.isClickable() || view.hasOnClickListeners()) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 60);
            for (int i = 0; i < count; i++) {
                n += countClickableDeep(group.getChildAt(i), depth + 1, visited);
            }
        }
        return n;
    }

    private static String visibilityName(int visibility) {
        if (visibility == View.VISIBLE) {
            return "VISIBLE";
        }
        if (visibility == View.INVISIBLE) {
            return "INVISIBLE";
        }
        if (visibility == View.GONE) {
            return "GONE";
        }
        return String.valueOf(visibility);
    }

    private static String ancestorPath(View view) {
        StringBuilder sb = new StringBuilder();
        View node = view;
        for (int i = 0; i < 12 && node != null; i++) {
            sb.append('/').append(shortClassName(node)).append(':').append(idLabel(node));
            android.view.ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return sb.toString();
    }

    private static String shortClassName(View view) {
        String name = view.getClass().getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String idLabel(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID || id == 0) {
                return "-";
            }
            Context context = view.getContext();
            return context == null ? ("0x" + Integer.toHexString(id))
                    : context.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void logRootReady(View root, int hit) {
        PostDetailCleanHook instance = sInstance;
        if (instance == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HIDE_GROUPS.length; i++) {
            boolean found = false;
            for (String name : HIDE_GROUPS[i]) {
                int id = idOf(root, name);
                if (id != 0 && root.findViewById(id) != null) {
                    found = true;
                    break;
                }
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(GROUP_LABELS[i]).append('=').append(found ? "已找到" : "未找到");
        }
        sb.append(" | 锚点命中=");
        if (sDetailHits.isEmpty()) {
            sb.append("无");
        } else {
            sb.append(sDetailHits);
        }
        instance.module.logd(Log.INFO, instance.module.TAG,
                "帖子详情精简：详情页命中 " + hit + " 个目标视图 | " + sb
                        + " | Activity=" + sActivityName
                        + " | 屏蔽=" + onOff(sClean)
                        + " 话题防误触=" + onOff(sTopicNoClick)
                        + " 话题隐藏=" + onOff(sTopicHide));
    }

    private static View scopeOf(View root) {
        return root;
    }

    private static int idOf(View root, String name) {
        Integer cached = sIds.get(name);
        if (cached != null) {
            return cached;
        }
        int id = 0;
        try {
            Context context = root.getContext();
            Resources resources = context != null ? context.getResources() : null;
            if (resources != null && context != null) {
                id = resources.getIdentifier(name, "id", context.getPackageName());
                if (id == 0) {
                    id = resources.getIdentifier(name, "id", MainModule.TARGET_PKG);
                }
            }
        } catch (Throwable ignored) {
        }
        if (id == 0) {
            int attempts = sIdMisses.merge(name, 1, Integer::sum);
            if (attempts == 1 || attempts >= MAX_ID_RESOLVE_ATTEMPTS) {
                PostDetailCleanHook instance = sInstance;
                if (instance != null) {
                    instance.module.logd(Log.WARN, instance.module.TAG,
                            "帖子详情精简：第 " + attempts + " 次解析不到资源 " + name
                                    + "，跳过（会继续重试）");
                }
            }
            return 0;
        }
        sIds.put(name, id);
        return id;
    }

    private static final int MAX_ID_RESOLVE_ATTEMPTS = 200;

    private static Set<String> parseIds(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            String name = line.trim();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            int space = name.indexOf(' ');
            if (space > 0) {
                name = name.substring(0, space).trim();
            }
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }


    public static String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("宿主版本码: ").append(hostVersionCode()).append('\n');
        sb.append("屏蔽总开关: ").append(onOff(sClean))
                .append(" / 话题防误触: ").append(onOff(sTopicNoClick))
                .append(" / 话题隐藏: ").append(onOff(sTopicHide))
                .append(" / 自定义: ").append(sCustomIds.size()).append(" 个\n");
        sb.append("精确版本: ").append(com.better.heybox.VersionUtils.getVersionName(
                App.resolveAppContext())).append("\n");
        sb.append("挂点: onCreateView=").append(sHookCreateView).append(" 处")
                .append(" / onResume=").append(sHookResume).append(" 处")
                .append(" / setVisibility兜底=").append(sHookSetVisibility).append(" 处")
                .append(" / 点击拦截=").append(sHookClickListener).append(" 处")
                .append(" / 文本兜底=").append(sHookSetText).append(" 处\n");
        sb.append("构建标记: ").append(BUILD_MARKER).append('\n');
        sb.append("最近 Activity: ").append(sActivityName).append('\n');
        sb.append("setVisibility 兜底拦截次数: ").append(sSuppressHits.get())
                .append(" / 话题点击拦截次数: ").append(sTopicClickBlocked.get())
                .append(" / 文本兜底命中: ").append(sTextHideHits.get()).append('\n');
        sb.append("当前是否详情页: ").append(sInDetailPage).append('\n');
        sb.append("兜底名单解析: ")
                .append(sSuppressedIds == null
                        ? "还没解析成功（没拿到宿主 Context？）"
                        : sSuppressedIds.size() + " 个 id")
                .append('\n');
        sb.append("详情页锚点解析:\n");
        for (String anchor : PAGE_ANCHORS) {
            sb.append("  ").append(anchor).append(" = ").append(describeId(anchor)).append('\n');
        }
        sb.append("资源解析:\n");
        for (String name : allTargetNames()) {
            sb.append("  ").append(name).append(" = ").append(describeId(name)).append('\n');
        }
        sb.append("最近一次生效: ").append(sLastApply).append('\n');
        sb.append("屏幕上可见的「搜」类节点:").append(sVisibleSearchDump).append('\n');
        sb.append("观察到的候选区块（可加入自定义隐藏列表）:\n");
        synchronized (sCandidates) {
            if (sCandidates.isEmpty()) {
                sb.append("  —\n");
            } else {
                for (Map.Entry<String, String> entry : sCandidates.entrySet()) {
                    sb.append("  ").append(entry.getKey());
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        sb.append("（").append(entry.getValue()).append("）");
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String describeId(String name) {
        Integer id = sIds.get(name);
        if (id != null) {
            return "OK(id=0x" + Integer.toHexString(id) + ", 详情页命中="
                    + (sDetailHits.contains(name) ? "是" : "否") + ")";
        }
        Integer misses = sIdMisses.get(name);
        if (misses == null) {
            return "未查";
        }
        return "未解析（已试 " + misses + " 次）";
    }

    private static List<String> allTargetNames() {
        List<String> out = new ArrayList<>();
        for (String[] group : HIDE_GROUPS) {
            for (String name : group) {
                out.add(name);
            }
        }
        out.add(IDS_INSERT_CONTAINER);
        out.add(IDS_TOPIC_ROW);
        out.addAll(sCustomIds);
        return out;
    }

    private static String shortName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static long hostVersionCode() {
        try {
            Context context = App.resolveAppContext();
            if (context == null) {
                return -1L;
            }
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(MainModule.TARGET_PKG, 0);
            return android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String nowText() {
        return new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                .format(new java.util.Date());
    }
}
