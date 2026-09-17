package com.better.heybox.hooks;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.better.heybox.App;
import com.better.heybox.MainModule;

public final class SearchPageCleanHook {

    private static final String FRAGMENT_CLASS = "com.max.hbsearch.SearchNewFragment";

    private static final String ID_PLACEHOLDER = "sv_placeholder";

    private static final String ID_BANNER = "banner";
    private static final String[] IDS_BANNER = new String[]{ID_BANNER};

    private static final String[] IDS_DISCOVER = new String[]{
            "rl_list_header_v2",
            "rv_search_recommend_v2",
    };

    private static final String[] IDS_HOT_RANK = new String[]{
            "v_top_space_hot_search_v3",
            "ll_hot_search_v3",
            "nsv_hot_search_v3",
            "space_hot_search_v3",
            "v_default_gap",
            "ll_hot",
            "sv_hot_v2",
    };

    private final MainModule module;

    private static volatile SearchPageCleanHook sInstance;
    private static volatile boolean sHideBanner;
    private static volatile boolean sHideDiscover;
    private static volatile boolean sHideHotRank;

    private static final Map<View, Boolean> sSeen =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static final Map<View, Boolean> sWatching =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static final Map<View, Integer> sOriginal =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    private static final Map<String, Integer> sIds = new ConcurrentHashMap<>();
    private static final Set<String> sMissing = ConcurrentHashMap.newKeySet();
    private static final Set<String> sNotFound = ConcurrentHashMap.newKeySet();

    public SearchPageCleanHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    public static void refresh() {
        SearchPageCleanHook instance = sInstance;
        if (instance == null) {
            return;
        }
        MainModule m = instance.module;
        sHideBanner = m.isEnabled(App.KEY_SEARCH_HIDE_BANNER, false);
        sHideDiscover = m.isEnabled(App.KEY_SEARCH_HIDE_DISCOVER, false);
        sHideHotRank = m.isEnabled(App.KEY_SEARCH_HIDE_HOT_RANK, false);
        for (View root : new ArrayList<>(sSeen.keySet())) {
            try {
                apply(root);
            } catch (Throwable ignored) {
            }
        }
    }

    public void install(ClassLoader cl) {
        refresh();
        int hooked = 0;
        try {
            Class<?> fragment = Class.forName(FRAGMENT_CLASS, false, cl);
            try {
                Method onCreateView = fragment.getDeclaredMethod(
                        "onCreateView", LayoutInflater.class, ViewGroup.class,
                        android.os.Bundle.class);
                module.hook(onCreateView).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof View) {
                        apply((View) result);
                    }
                    return result;
                });
                hooked++;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "搜索页精简：onCreateView Hook 失败: " + t);
            }
            try {
                final Method getView = fragment.getMethod("getView");
                Method onResume = fragment.getDeclaredMethod("onResume");
                module.hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object view = getView.invoke(chain.getThisObject());
                        if (view instanceof View) {
                            apply((View) view);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
                hooked++;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "搜索页精简：onResume Hook 失败: " + t);
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "搜索页精简：未找到 " + FRAGMENT_CLASS, t);
        }
        module.logd(Log.INFO, module.TAG, "✔ 搜索页精简 Hook 已安装（" + hooked + " 处）"
                + " | 横幅=" + onOff(sHideBanner)
                + " 搜索发现=" + onOff(sHideDiscover)
                + " 黑盒热榜=" + onOff(sHideHotRank));
    }

    private static String onOff(boolean enabled) {
        return enabled ? "隐藏" : "保留";
    }

    private static boolean anyEnabled() {
        return sHideBanner || sHideDiscover || sHideHotRank;
    }

    private static boolean apply(View root) {
        if (root == null) {
            return false;
        }
        boolean firstSeen = sSeen.put(root, Boolean.TRUE) == null;
        boolean changed = false;
        if (firstSeen) {
            logRootReady(root);
        }
        changed |= applyGroup(root, sHideBanner, "隐藏搜索页横幅", IDS_BANNER);
        View scope = scopeOf(root);
        changed |= applyGroup(scope, sHideDiscover, "隐藏「搜索发现」", IDS_DISCOVER);
        changed |= applyGroup(scope, sHideHotRank, "隐藏「黑盒热榜」", IDS_HOT_RANK);
        if (anyEnabled()) {
            watch(root);
        }
        return changed;
    }

    private static void logRootReady(View root) {
        SearchPageCleanHook instance = sInstance;
        if (instance == null) {
            return;
        }
        boolean bannerFound = false;
        int bannerId = idOf(root, ID_BANNER);
        if (bannerId != 0) {
            bannerFound = root.findViewById(bannerId) != null;
        }
        instance.module.logd(Log.INFO, instance.module.TAG,
                "搜索页精简：页根就绪 | 横幅视图=" + (bannerFound ? "已找到" : "未找到")
                        + " | 横幅=" + onOff(sHideBanner)
                        + " 搜索发现=" + onOff(sHideDiscover)
                        + " 黑盒热榜=" + onOff(sHideHotRank));
    }

    private static boolean applyGroup(View root, boolean hide, String label, String[] names) {
        boolean changed = false;
        boolean found = false;
        for (String name : names) {
            int id = idOf(root, name);
            if (id == 0) {
                continue;
            }
            View view = root.findViewById(id);
            if (view == null) {
                continue;
            }
            found = true;
            if (hide) {
                changed |= hide(view, label, name);
            } else {
                changed |= restore(view);
            }
        }
        if (hide && !found && sNotFound.add(label)) {
            SearchPageCleanHook instance = sInstance;
            if (instance != null) {
                instance.module.logd(Log.WARN, instance.module.TAG,
                        "搜索页精简：本页未找到 " + label + " 的目标视图，跳过");
            }
        }
        return changed;
    }

    private static boolean hide(View view, String label, String name) {
        if (view.getVisibility() == View.GONE) {
            return false;
        }
        if (!sOriginal.containsKey(view)) {
            sOriginal.put(view, view.getVisibility());
        }
        view.setVisibility(View.GONE);
        SearchPageCleanHook instance = sInstance;
        if (instance != null) {
            instance.module.logd(Log.INFO, instance.module.TAG,
                    "搜索页精简：" + label + " (" + name + ")");
        }
        return true;
    }

    private static boolean restore(View view) {
        Integer original = sOriginal.remove(view);
        if (original == null || view.getVisibility() == original) {
            return false;
        }
        view.setVisibility(original);
        return true;
    }

    private static void watch(final View root) {
        if (sWatching.put(root, Boolean.TRUE) != null) {
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
                                    ViewTreeObserver observer = root.getViewTreeObserver();
                                    if (observer != null && observer.isAlive()) {
                                        observer.removeOnPreDrawListener(this);
                                    }
                                    return true;
                                }
                                return !apply(root);
                            } catch (Throwable ignored) {
                                return true;
                            }
                        }
                    });
        } catch (Throwable t) {
            sWatching.remove(root);
            SearchPageCleanHook instance = sInstance;
            if (instance != null) {
                instance.module.logd(Log.WARN, instance.module.TAG,
                        "搜索页精简：挂绘制监听失败: " + t);
            }
        }
    }

    private static View scopeOf(View root) {
        int id = idOf(root, ID_PLACEHOLDER);
        if (id != 0) {
            View scope = root.findViewById(id);
            if (scope != null) {
                return scope;
            }
        }
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
            sIds.put(name, 0);
            if (sMissing.add(name)) {
                SearchPageCleanHook instance = sInstance;
                if (instance != null) {
                    instance.module.logd(Log.WARN, instance.module.TAG,
                            "搜索页精简：当前版本没有资源 " + name + "，跳过");
                }
            }
            return 0;
        }
        sIds.put(name, id);
        return id;
    }
}
