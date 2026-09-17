package com.better.heybox.hooks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.better.heybox.App;
import com.better.heybox.DexKitResolver;
import com.better.heybox.HeyboxTargets;
import com.better.heybox.MainModule;

public final class GameLibraryCleanHook {

    public static final String TARGET_GAME_REC_BIND = "game.rec.list.bind";

    public static final String TARGET_GAME_REC_WRAPPER = "game.rec.list.wrapper";

    public static final String TARGET_GAME_REC_BB = "game.rec.list.bb";

    public static final String ADAPTER_CLASS =
            "com.max.xiaoheihe.module.game.adapter.recommend.GameRecommendAdapter";

    public static final String WRAPPER_CLASS = "com.max.hbcommon.base.adapter.BigBrotherAdapterWrapper";

    public static final String[] CLASS_ANCHORS = new String[]{
            "mall_newcomer",
            "big_game_card_scroll_v2",
            "game_comment_multi",
    };

    public static final String BB_DELEGATE_CLASS =
            "com.max.xiaoheihe.module.game.adapter.recommend.b";

    public static final String[] BB_CLASS_ANCHORS = new String[]{
            "mini_app_v2",
            "game_comment_multi",
            "mall_newcomer",
    };

    private static final Set<String> TYPES_BANNER =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("header")));

    private static final Set<String> TYPES_SMALL =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "menu", "menu_v2",
                    "mini_app", "mini_app_v2", "mini_app_v3")));

    private static final Set<String> TYPES_CONTENT =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "title", "space",
                    "game_card_duo", "game_card_duo_release_date",
                    "game_card_single", "game_card_single_with_tab",
                    "game_card_scroll", "big_game_card",
                    "big_game_card_scroll", "big_game_card_scroll_v2",
                    "big_game_series_card_scroll",
                    "middle_game_card", "middle_game_card_video", "middle_game_scroll",
                    "game_list_rectangle", "game_list_square",
                    "game_comment", "game_comments", "game_comment_multi",
                    "factory_list", "rec_goods", "mall_newcomer")));

    public static final String[][] TYPE_CATALOG = new String[][]{
            {"header", "顶端横幅（三图）"},
            {"menu", "小分区入口"},
            {"menu_v2", "小分区入口 v2"},
            {"mini_app", "小程序推荐"},
            {"mini_app_v2", "小程序推荐 v2"},
            {"mini_app_v3", "小程序推荐 v3"},
            {"title", "分区标题（为你推荐…）"},
            {"space", "留白"},
            {"game_card_duo", "双列游戏卡"},
            {"game_card_duo_release_date", "双列游戏卡（发售日）"},
            {"game_card_single", "单列游戏卡"},
            {"game_card_single_with_tab", "带页签游戏卡"},
            {"game_card_scroll", "横滑游戏卡"},
            {"big_game_card", "大卡"},
            {"big_game_card_scroll", "横滑大卡"},
            {"big_game_card_scroll_v2", "横滑大卡 v2"},
            {"big_game_series_card_scroll", "系列横滑大卡"},
            {"middle_game_card", "中卡"},
            {"middle_game_card_video", "中卡（视频）"},
            {"middle_game_scroll", "横滑中卡"},
            {"game_list_rectangle", "游戏列表（矩形）"},
            {"game_list_square", "游戏列表（方形）"},
            {"game_comment", "游戏评价卡"},
            {"game_comments", "游戏评价卡 v2"},
            {"game_comment_multi", "多列评价卡"},
            {"factory_list", "厂商列表"},
            {"rec_goods", "推荐商品"},
            {"mall_newcomer", "新人券"},
    };

    private static final String KEY_OBSERVED_TYPES = "game_lib_seen_types";
    private static final String KEY_OBSERVED_ENTRIES = "game_lib_seen_entries";
    private static final String KEY_OBSERVED_SECTIONS = "game_lib_seen_sections";
    private static final int MAX_OBSERVED = 60;

    private static final Set<String> TYPES_ENTRY_BOARDS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "menu", "menu_v2",
                    "mini_app", "mini_app_v2", "mini_app_v3")));

    public static final int PICK_TYPE = 0;
    public static final int PICK_ENTRY = 1;
    public static final int PICK_SECTION = 2;

    private static final Object NO_ACCESSOR = new Object();

    private final MainModule module;

    private static volatile GameLibraryCleanHook sInstance;
    private static volatile boolean sHideBanner;
    private static volatile boolean sHideSmall;
    private static volatile boolean sHideContent;

    private static volatile Set<String> sCustomTypes = Collections.emptySet();

    private static volatile Set<String> sObserved = new LinkedHashSet<>();

    private static volatile Set<String> sHiddenEntries = Collections.emptySet();
    private static volatile Set<String> sHiddenSections = Collections.emptySet();

    private static volatile Set<String> sLastHiddenEntries = Collections.emptySet();

    private static volatile Set<String> sObservedEntries = new LinkedHashSet<>();
    private static volatile Set<String> sObservedSections = new LinkedHashSet<>();
    private static final AtomicBoolean sErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sMissingLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sScopeLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sScopeMissingLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sEmptyCandidateLogged = new AtomicBoolean(false);

    private static volatile boolean sInstalledInner;
    private static volatile boolean sInstalledWrapper;
    private static volatile boolean sInstalledDelegate;
    private static volatile boolean sInstalledPress;
    private static volatile boolean sInstalledClickable;
    private static volatile boolean sInstalledAttach;
    private static volatile String sLastPress = "还没长按过";

    private static final Map<View, String[]> sBound =
            Collections.synchronizedMap(new WeakHashMap<View, String[]>());

    private static final Map<Class<?>, Object> sAccessors = new ConcurrentHashMap<>();

    private static final Map<View, String> sLogged =
            Collections.synchronizedMap(new WeakHashMap<View, String>());

    private static volatile Class<?> sAdapterClass;

    private static final Map<Class<?>, Object> sInnerAccessors = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Method> sDataListMethods = new ConcurrentHashMap<>();

    private static final Object sPromptLock = new Object();
    private static final long PROMPT_COOLDOWN_MS = 1200L;
    private static long sPromptAtMs;

    private static final Set<View> sBoardViews =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));

    private static final Set<View> sLongPressAttached =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));

    private static final Set<View> sLongPressRescan =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));

    private static final Set<Class<?>> sChildFiltersHooked = ConcurrentHashMap.newKeySet();

    private static final Map<Class<?>, Method> sItemsMethods = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Object> sLabelAccessors = new ConcurrentHashMap<>();

    private static final Set<String> sSeenEntries = ConcurrentHashMap.newKeySet();

    private static final Set<String> sPressTextsLogged = ConcurrentHashMap.newKeySet();

    private static final long LONG_PRESS_MS = 450L;
    private static final Set<View> sTouchAttached =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));
    private static volatile View sTouchTarget;
    private static volatile long sTouchDownAt;
    private static volatile float sTouchDownX;
    private static volatile float sTouchDownY;
    private static volatile float sTouchSlop;
    private static volatile boolean sTouchFired;

    private static final Runnable TOUCH_LONG_PRESS = () -> {
        View target = sTouchTarget;
        GameLibraryCleanHook instance = sInstance;
        if (target == null || instance == null || sTouchDownAt == 0L || sTouchFired) {
            return;
        }
        View pressed = findDeepestChildAt(target, sTouchDownX, sTouchDownY);
        if (pressed == null) {
            return;
        }
        Set<String> candidates = entryCandidates();
        String label = findEntryLabel(pressed, candidates);
        if (label == null) {
            label = fallbackLabel(pressed);
        }
        if (label == null) {
            sLastPress = nowText() + " 长按自检未取到卡片名";
            instance.logPressMiss(pressed, candidates.size());
            return;
        }
        sTouchFired = true;
        instance.module.logd(Log.INFO, instance.module.TAG,
                "游戏库精简：长按自检触发「" + label + "」");
        instance.showHideEntryPrompt(pressed, label);
    };

    public GameLibraryCleanHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    public static void refresh() {
        GameLibraryCleanHook instance = sInstance;
        if (instance == null) {
            return;
        }
        MainModule m = instance.module;
        sHideBanner = m.isEnabled(App.KEY_GAME_LIB_HIDE_BANNER, false);
        sHideSmall = m.isEnabled(App.KEY_GAME_LIB_HIDE_MENU, false);
        sHideContent = m.isEnabled(App.KEY_GAME_LIB_HIDE_SECTIONS, false);
        sCustomTypes = parseTypes(m.getString(App.KEY_GAME_LIB_HIDE_TYPES, ""));
        Set<String> previousEntries = sLastHiddenEntries;
        sHiddenEntries = parseNames(m.getString(App.KEY_GAME_LIB_HIDE_ENTRIES, ""));
        boolean entriesChanged = !previousEntries.equals(sHiddenEntries);
        sLastHiddenEntries = sHiddenEntries;
        sHiddenSections = parseNames(m.getString(App.KEY_GAME_LIB_HIDE_SECTION_NAMES, ""));
        sObserved = new LinkedHashSet<>(
                parseTypes(com.better.heybox.HeyboxPrefs.getString(KEY_OBSERVED_TYPES, "")));
        sObservedEntries = new LinkedHashSet<>(
                parseNames(com.better.heybox.HeyboxPrefs.getString(KEY_OBSERVED_ENTRIES, "")));
        sObservedSections = new LinkedHashSet<>(
                parseNames(com.better.heybox.HeyboxPrefs.getString(KEY_OBSERVED_SECTIONS, "")));
        for (Map.Entry<View, String[]> entry : new ArrayList<>(sBound.entrySet())) {
            String[] info = entry.getValue();
            if (info != null) {
                apply(entry.getKey(), info[0], info[1]);
            }
        }
        if (entriesChanged) {
            instance.notifyBoundList();
        }
    }

    public void install(ClassLoader cl) {
        refresh();
        try {
            android.content.Context ctx = App.resolveAppContext();
            if (ctx != null) {
                android.content.pm.PackageInfo info = ctx.getPackageManager()
                        .getPackageInfo(MainModule.TARGET_PKG, 0);
                setHostVersionCode(android.os.Build.VERSION.SDK_INT >= 28
                        ? info.getLongVersionCode() : info.versionCode);
            }
        } catch (Throwable ignored) {
        }
        HeyboxTargets.install(TARGET_GAME_REC_BIND, this::hookAdapterBind);
        HeyboxTargets.install(TARGET_GAME_REC_WRAPPER, this::hookWrapperBind);
        HeyboxTargets.install(TARGET_GAME_REC_BB, this::hookDelegateBind);
        hookTouchDispatch(cl);
        hookClickListeners(cl);
        hookLongPress(cl);
        hookLongClickable(cl);
        if (HeyboxTargets.methods(TARGET_GAME_REC_BIND).isEmpty()
                && HeyboxTargets.methods(TARGET_GAME_REC_WRAPPER).isEmpty()
                && HeyboxTargets.methods(TARGET_GAME_REC_BB).isEmpty()
                && sMissingLogged.compareAndSet(false, true)) {
            module.logd(Log.WARN, module.TAG,
                    "游戏库精简：未解析到推荐列表绑定入口（等待 DexKit 兜底）");
        }
    }

    private void hookAdapterBind(Method method) {
        sAdapterClass = method.getDeclaringClass();
        sInstalledInner = true;
        module.hook(method).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object adapter = chain.getThisObject();
                List<?> list = dataListOf(adapter);
                Object item = chain.getArg(1);
                handle(item, chain.getArg(0), "内层", list, indexOf(list, item));
            } catch (Throwable t) {
                logOnce(t);
            }
            return result;
        });
        module.logd(Log.INFO, module.TAG, "✔ 游戏库精简 Hook 已安装（内层） "
                + method.getDeclaringClass().getName() + "#" + method.getName()
                + "/" + method.getParameterCount()
                + " [来源=" + HeyboxTargets.sourceOf(TARGET_GAME_REC_BIND) + "]");
    }

    private void hookWrapperBind(Method method) {
        sInstalledWrapper = true;
        module.hook(method).intercept(chain -> {
            Object wrapper = chain.getThisObject();
            Object inner = innerAdapterOf(wrapper);
            if (inner == null) {
                if (sScopeMissingLogged.compareAndSet(false, true)) {
                    module.logd(Log.WARN, module.TAG,
                            "游戏库精简：外层作用域暂未取到内层适配器（适配器可能还没创建）");
                }
                return chain.proceed();
            }
            if (!isGameRecommendAdapter(inner)) {
                return chain.proceed();
            }
            if (sScopeLogged.compareAndSet(false, true)) {
                module.logd(Log.INFO, module.TAG,
                        "游戏库精简：外层作用域生效（内层=" + inner.getClass().getName() + "）");
            }
            Object result = chain.proceed();
            try {
                int position = chain.getArg(1) instanceof Integer ? (Integer) chain.getArg(1) : -1;
                handle(itemAt(inner, position), chain.getArg(0), "外层",
                        dataListOf(inner), position);
            } catch (Throwable t) {
                logOnce(t);
            }
            return result;
        });
        module.logd(Log.INFO, module.TAG, "✔ 游戏库精简 Hook 已安装（外层） "
                + method.getDeclaringClass().getName() + "#" + method.getName()
                + "/" + method.getParameterCount()
                + " [来源=" + HeyboxTargets.sourceOf(TARGET_GAME_REC_WRAPPER) + "]"
                + " | 横幅=" + onOff(sHideBanner)
                + " 小分区=" + onOff(sHideSmall)
                + " 推荐分区=" + onOff(sHideContent)
                + " 自定义=" + sCustomTypes.size() + " 条");
    }

    private void hookDelegateBind(Method method) {
        sInstalledDelegate = true;
        module.hook(method).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object item = chain.getArg(2);
                List<?> list = dataListOf(chain.getArg(1));
                handle(item, chain.getArg(0), "预绑定", list, indexOf(list, item));
            } catch (Throwable t) {
                logOnce(t);
            }
            return result;
        });
        module.logd(Log.INFO, module.TAG, "✔ 游戏库精简 Hook 已安装（预绑定） "
                + method.getDeclaringClass().getName() + "#" + method.getName()
                + "/" + method.getParameterCount()
                + " [来源=" + HeyboxTargets.sourceOf(TARGET_GAME_REC_BB) + "]"
                + " | 横幅=" + onOff(sHideBanner)
                + " 小分区=" + onOff(sHideSmall)
                + " 推荐分区=" + onOff(sHideContent)
                + " 自定义=" + sCustomTypes.size() + " 条");
    }

    private void handle(Object item, Object viewHolder, String path, List<?> list, int index) {
        if (item == null) {
            return;
        }
        String type = typeOf(item);
        if (type == null) {
            return;
        }
        remember(type);
        View boundView = FeedItemHider.getItemView(viewHolder);
        if (isEntryBoard(item, type)) {
            hookChildList(item);
            if (boundView != null) {
                sBoardViews.add(boundView);
                attachEntryLongPress(boundView);
                View pageRecycler = nearestRecyclerView(boundView);
                if (pageRecycler != null) {
                    attachTouchListener(pageRecycler);
                }
            }
        }
        String section = sectionNameOf(item, type, list, index);
        if ("title".equals(type) && section != null) {
            rememberName(sObservedSections, KEY_OBSERVED_SECTIONS, section);
        }
        View itemView = FeedItemHider.getItemView(viewHolder);
        if (itemView == null) {
            return;
        }
        sBound.put(itemView, new String[]{type, section});
        apply(itemView, type, section);
        if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            boolean hidden = isHiddenNow(type, section);
            String decision = path + "|" + type + "|" + hidden + "|" + section;
            if (!decision.equals(sLogged.get(itemView))) {
                sLogged.put(itemView, decision);
                module.logd(Log.INFO, module.TAG, "游戏库精简：" + path + " type=" + type
                        + (section == null ? "" : " 分区=" + section)
                        + " → " + (hidden ? "隐藏" : "保留"));
            }
        }
    }

    private static boolean isEntryBoard(Object item, String type) {
        if (type != null && TYPES_ENTRY_BOARDS.contains(type)) {
            return true;
        }
        return itemsMethodOf(item.getClass()) != null;
    }

    private void logOnce(Throwable t) {
        if (sErrorLogged.compareAndSet(false, true)) {
            module.logd(Log.WARN, module.TAG, "游戏库精简：条目隐藏异常: " + t);
        }
    }

    private static Object innerAdapterOf(Object wrapper) {
        if (wrapper == null) {
            return null;
        }
        Class<?> wrapperClass = wrapper.getClass();
        Object accessor = sInnerAccessors.get(wrapperClass);
        if (accessor == null) {
            accessor = resolveInnerAccessor(wrapper, wrapperClass);
            if (accessor != null) {
                sInnerAccessors.put(wrapperClass, accessor);
            }
        }
        if (accessor == null) {
            return null;
        }
        try {
            return accessor instanceof Field
                    ? ((Field) accessor).get(wrapper)
                    : ((Method) accessor).invoke(wrapper);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object resolveInnerAccessor(Object wrapper, Class<?> wrapperClass) {
        Class<?> adapterClass = adapterClass();
        if (adapterClass == null) {
            return null;
        }
        for (Field field : wrapperClass.getDeclaredFields()) {
            if (!field.getType().isAssignableFrom(adapterClass)) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (adapterClass.isInstance(field.get(wrapper))) {
                    return field;
                }
            } catch (Throwable ignored) {
            }
        }
        for (Method method : wrapperClass.getDeclaredMethods()) {
            if (method.getParameterCount() != 0
                    || !method.getReturnType().isAssignableFrom(adapterClass)) {
                continue;
            }
            try {
                method.setAccessible(true);
                if (adapterClass.isInstance(method.invoke(wrapper))) {
                    return method;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isGameRecommendAdapter(Object inner) {
        Class<?> adapterClass = adapterClass();
        return adapterClass != null && adapterClass.isInstance(inner);
    }

    private static Class<?> adapterClass() {
        Class<?> cached = sAdapterClass;
        if (cached != null) {
            return cached;
        }
        for (Method method : HeyboxTargets.methods(TARGET_GAME_REC_BIND)) {
            Class<?> declaring = method.getDeclaringClass();
            if (declaring != null) {
                sAdapterClass = declaring;
                return declaring;
            }
        }
        return null;
    }

    private static Object itemAt(Object inner, int position) {
        List<?> list = dataListOf(inner);
        return list != null && position >= 0 && position < list.size() ? list.get(position) : null;
    }

    private static List<?> dataListOf(Object adapter) {
        if (adapter == null) {
            return null;
        }
        Class<?> adapterClass = adapter.getClass();
        Method getter = sDataListMethods.get(adapterClass);
        if (getter == null) {
            getter = resolveDataListMethod(adapterClass);
            if (getter == null) {
                return null;
            }
            sDataListMethods.put(adapterClass, getter);
        }
        try {
            Object list = getter.invoke(adapter);
            return list instanceof List ? (List<?>) list : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int indexOf(List<?> list, Object item) {
        if (list == null || item == null) {
            return -1;
        }
        try {
            return list.indexOf(item);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String sectionNameOf(Object item, String type, List<?> list, int index) {
        if ("title".equals(type)) {
            return labelOf(item);
        }
        if (list == null || index <= 0) {
            return null;
        }
        for (int i = Math.min(index, list.size()) - 1; i >= 0; i--) {
            Object prev = list.get(i);
            if (prev != null && "title".equals(typeOf(prev))) {
                return labelOf(prev);
            }
        }
        return null;
    }

    private static Method resolveDataListMethod(Class<?> innerClass) {
        try {
            Method getter = innerClass.getMethod("getDataList");
            getter.setAccessible(true);
            return getter;
        } catch (Throwable ignored) {
        }
        for (Class<?> cls = innerClass; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Method method : cls.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && List.class.isAssignableFrom(method.getReturnType())) {
                    try {
                        method.setAccessible(true);
                        return method;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private void notifyBoundList() {
        try {
            View target = null;
            for (View view : new ArrayList<>(sBound.keySet())) {
                if (view != null) {
                    target = view;
                    break;
                }
            }
            if (target == null) {
                return;
            }
            View node = target;
            for (int i = 0; i < 40 && node != null
                    && !"androidx.recyclerview.widget.RecyclerView".equals(node.getClass().getName()); i++) {
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
            if (node == null
                    || !"androidx.recyclerview.widget.RecyclerView".equals(node.getClass().getName())) {
                return;
            }
            final View recyclerView = node;
            final Object adapter = recyclerView.getClass().getMethod("getAdapter").invoke(recyclerView);
            if (adapter == null) {
                return;
            }
            recyclerView.post(() -> {
                try {
                    adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String onOff(boolean enabled) {
        return enabled ? "隐藏" : "保留";
    }

    private static Set<String> parseTypes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String token : raw.split("[\\s,，;；]+")) {
            String type = token.trim().toLowerCase(Locale.ROOT);
            if (!type.isEmpty() && !type.startsWith("#")) {
                out.add(type);
            }
        }
        return out;
    }

    private static Set<String> parseNames(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            String name = line.trim();
            if (!name.isEmpty() && !name.startsWith("#")) {
                out.add(name);
            }
        }
        return out;
    }

    private static void persistNames(String prefsKey, Set<String> values) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String value : values) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(value);
            }
            com.better.heybox.HeyboxPrefs.setString(prefsKey, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void rememberName(Set<String> observed, String prefsKey, String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String value = name.trim();
        if (!observed.add(value)) {
            return;
        }
        java.util.Iterator<String> iterator = observed.iterator();
        while (observed.size() > MAX_OBSERVED && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
        persistNames(prefsKey, observed);
    }

    private void hookLongPress(ClassLoader cl) {
        try {
            Class<?> viewClass = Class.forName("android.view.View", false, cl);
            Method performLongClick = viewClass.getDeclaredMethod("performLongClick");
            module.hook(performLongClick).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (maybePromptHideEntry(chain.getThisObject())) {
                        return Boolean.TRUE;
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            sInstalledPress = true;
            module.logd(Log.INFO, module.TAG, "✔ 游戏库精简 Hook 已安装（长按入口卡片提示）");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "游戏库精简：长按提示 Hook 失败: " + t);
        }
    }

    private void hookTouchDispatch(ClassLoader cl) {
        try {
            Class<?> groupClass = Class.forName("android.view.ViewGroup", false, cl);
            Class<?> eventClass = Class.forName("android.view.MotionEvent", false, cl);
            Method dispatch = groupClass.getDeclaredMethod("dispatchTouchEvent", eventClass);
            module.hook(dispatch).intercept(chain -> {
                Object event = chain.getArg(0);
                if (!(event instanceof android.view.MotionEvent)
                        || !(chain.getThisObject() instanceof View)) {
                    return chain.proceed();
                }
                View dispatcher = (View) chain.getThisObject();
                if (!isTopLevelViewGroup(dispatcher)) {
                    return chain.proceed();
                }
                int action = ((android.view.MotionEvent) event).getActionMasked();
                if (sTouchFired) {
                    if (action == android.view.MotionEvent.ACTION_MOVE) {
                        return Boolean.TRUE;
                    }
                    if (action == android.view.MotionEvent.ACTION_UP
                            || action == android.view.MotionEvent.ACTION_CANCEL) {
                        sTouchFired = false;
                        sTouchDownAt = 0L;
                        sTouchTarget = null;
                        return Boolean.TRUE;
                    }
                }
                Object result = chain.proceed();
                try {
                    handleDispatchTouch(dispatcher, (android.view.MotionEvent) event, action);
                } catch (Throwable ignored) {
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 游戏库精简 Hook 已安装（长按自检·分发层）");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "游戏库精简：长按自检（分发层）Hook 失败: " + t);
        }
    }

    private static boolean isTopLevelViewGroup(View view) {
        return !(view.getParent() instanceof View);
    }

    private void handleDispatchTouch(View dispatcher, android.view.MotionEvent event, int action) {
        if (sBoardViews.isEmpty()) {
            return;
        }
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            sTouchFired = false;
            if (sTouchDownAt != 0L) {
                return;
            }
            View pressed = findDeepestChildAt(dispatcher, event.getRawX(), event.getRawY());
            if (pressed == null || !insideBoardView(pressed)) {
                return;
            }
            if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                module.logd(Log.INFO, module.TAG, "游戏库精简：长按自检收到按下");
            }
            sTouchTarget = dispatcher;
            sTouchDownAt = System.currentTimeMillis();
            sTouchDownX = event.getRawX();
            sTouchDownY = event.getRawY();
            sTouchFired = false;
            dispatcher.removeCallbacks(TOUCH_LONG_PRESS);
            dispatcher.postDelayed(TOUCH_LONG_PRESS, LONG_PRESS_MS);
        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
            if (sTouchDownAt == 0L) {
                return;
            }
            float slop = sTouchSlop;
            if (slop <= 0f) {
                slop = Math.max(24f, dispatcher.getResources().getDisplayMetrics().density * 24f);
                sTouchSlop = slop;
            }
            if (Math.hypot(event.getRawX() - sTouchDownX, event.getRawY() - sTouchDownY) > slop) {
                sTouchDownAt = 0L;
                dispatcher.removeCallbacks(TOUCH_LONG_PRESS);
            }
        } else if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            sTouchDownAt = 0L;
            dispatcher.removeCallbacks(TOUCH_LONG_PRESS);
            if (!sTouchFired) {
                sTouchTarget = null;
            }
        }
    }

    static String touchState() {
        return "down=" + sTouchDownAt + " fired=" + sTouchFired + " target="
                + (sTouchTarget == null ? "-" : sTouchTarget.getClass().getSimpleName());
    }

    private void hookClickListeners(ClassLoader cl) {
        try {
            Class<?> viewClass = Class.forName("android.view.View", false, cl);
            Class<?> listenerClass = Class.forName("android.view.View$OnClickListener", false, cl);
            Method setOnClickListener = viewClass.getDeclaredMethod("setOnClickListener", listenerClass);
            module.hook(setOnClickListener).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object target = chain.getThisObject();
                    if (target instanceof View && insideBoardView(target)) {
                        bindLongPressTree((View) target, 0);
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            sInstalledAttach = true;
            module.logd(Log.INFO, module.TAG, "✔ 游戏库精简 Hook 已安装（卡片长按挂载）");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "游戏库精简：卡片长按挂载 Hook 失败: " + t);
        }
    }

    private void hookLongClickable(ClassLoader cl) {
        try {
            Class<?> viewClass = Class.forName("android.view.View", false, cl);
            Method isLongClickable = viewClass.getDeclaredMethod("isLongClickable");
            module.hook(isLongClickable).intercept(chain -> {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(result)) {
                    return result;
                }
                return insideBoardView(chain.getThisObject()) ? Boolean.TRUE : result;
            });
            sInstalledClickable = true;
            module.logd(Log.INFO, module.TAG, "✔ 游戏库精简 Hook 已安装（长按可达）");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "游戏库精简：长按可达 Hook 失败: " + t);
        }
    }

    private static boolean insideBoardView(Object viewObject) {
        if (!(viewObject instanceof View) || sBoardViews.isEmpty()) {
            return false;
        }
        View node = (View) viewObject;
        for (int i = 0; i < 24 && node != null; i++) {
            if (sBoardViews.contains(node)) {
                return true;
            }
            android.view.ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private boolean maybePromptHideEntry(Object viewObject) {
        if (!(viewObject instanceof View) || sBound.isEmpty()) {
            return false;
        }
        View view = (View) viewObject;
        if (!sharesRootWithBoundView(view)) {
            return false;
        }
        return promptEntryHide(view);
    }

    private boolean promptEntryHide(View view) {
        Set<String> candidates = entryCandidates();
        if (candidates.isEmpty()) {
            sLastPress = nowText() + " 无候选卡片名（该分区还没读到子项列表）";
            if (sEmptyCandidateLogged.compareAndSet(false, true)) {
                module.logd(Log.INFO, module.TAG,
                        "游戏库精简：长按但还没有候选卡片名（该分区还没读到子项列表）");
            }
            return false;
        }
        String label = findEntryLabel(view, candidates);
        if (label == null) {
            label = fallbackLabel(view);
        }
        if (label == null) {
            logPressMiss(view, candidates.size());
            return false;
        }
        return showHideEntryPrompt(view, label);
    }

    private static Set<String> entryCandidates() {
        Set<String> candidates = new LinkedHashSet<>(sObservedEntries);
        candidates.addAll(sHiddenEntries);
        return candidates;
    }

    private void attachEntryLongPress(final View boardItemView) {
        List<View> recyclerViews = new ArrayList<>();
        collectRecyclerViews(boardItemView, recyclerViews, 0);
        for (View recyclerView : recyclerViews) {
            attachToRecycler(recyclerView);
        }
        if (sLongPressRescan.add(boardItemView)) {
            boardItemView.postDelayed(() -> {
                try {
                    List<View> again = new ArrayList<>();
                    collectRecyclerViews(boardItemView, again, 0);
                    for (View recyclerView : again) {
                        attachToRecycler(recyclerView);
                    }
                } catch (Throwable ignored) {
                }
                sLongPressRescan.remove(boardItemView);
            }, 400L);
        }
    }

    private static View nearestRecyclerView(View view) {
        View node = view;
        for (int i = 0; i < 24 && node != null; i++) {
            if (isRecyclerView(node)) {
                return node;
            }
            android.view.ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
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

    private static void collectRecyclerViews(View node, List<View> out, int depth) {
        if (node == null || depth > 8 || out.size() >= 4) {
            return;
        }
        if ("androidx.recyclerview.widget.RecyclerView".equals(node.getClass().getName())) {
            out.add(node);
            return;
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            int count = Math.min(group.getChildCount(), 30);
            for (int i = 0; i < count; i++) {
                collectRecyclerViews(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    private void attachToRecycler(View recyclerView) {
        attachTouchListener(recyclerView);
        if (!sLongPressAttached.add(recyclerView)) {
            return;
        }
        if (recyclerView instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) recyclerView;
            for (int i = 0; i < group.getChildCount(); i++) {
                bindLongPressTree(group.getChildAt(i), 0);
            }
        }
        try {
            ClassLoader loader = recyclerView.getClass().getClassLoader();
            Class<?> rvClass = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader);
            Class<?> listenerClass = Class.forName(
                    "androidx.recyclerview.widget.RecyclerView$OnChildAttachStateChangeListener", false, loader);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if (args != null && args.length > 0 && args[0] instanceof View) {
                            try {
                                bindLongPressTree((View) args[0], 0);
                            } catch (Throwable ignored) {
                            }
                        }
                        return null;
                    });
            rvClass.getMethod("addOnChildAttachStateChangeListener", listenerClass)
                    .invoke(recyclerView, listener);
        } catch (Throwable ignored) {
        }
    }

    private void attachTouchListener(View recyclerView) {
        if (!sTouchAttached.add(recyclerView)) {
            return;
        }
        try {
            ClassLoader loader = recyclerView.getClass().getClassLoader();
            Class<?> rvClass = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader);
            Class<?> listenerClass = Class.forName(
                    "androidx.recyclerview.widget.RecyclerView$OnItemTouchListener", false, loader);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(loader,
                    new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                        try {
                            return onRecyclerTouch(recyclerView, method.getName(), args);
                        } catch (Throwable t) {
                            return Boolean.FALSE;
                        }
                    });
            rvClass.getMethod("addOnItemTouchListener", listenerClass).invoke(recyclerView, listener);
            module.logd(Log.INFO, module.TAG, "✔ 游戏库精简 Hook 已安装（长按自检） "
                    + recyclerView.getClass().getName());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "游戏库精简：长按自检挂载失败: " + t);
        }
    }

    private Object onRecyclerTouch(View recyclerView, String methodName, Object[] args) {
        if (!"onInterceptTouchEvent".equals(methodName) && !"onTouchEvent".equals(methodName)) {
            return null;
        }
        Object raw = args != null && args.length > 1 ? args[1] : null;
        if (!(raw instanceof android.view.MotionEvent)) {
            return Boolean.FALSE;
        }
        android.view.MotionEvent event = (android.view.MotionEvent) raw;
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            GameLibraryCleanHook instance = sInstance;
            if (instance != null && instance.module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                instance.module.logd(Log.INFO, instance.module.TAG,
                        "游戏库精简：长按自检收到按下（" + recyclerView.getClass().getSimpleName() + "）");
            }
            sTouchTarget = recyclerView;
            sTouchDownAt = System.currentTimeMillis();
            sTouchDownX = event.getRawX();
            sTouchDownY = event.getRawY();
            sTouchFired = false;
            recyclerView.removeCallbacks(TOUCH_LONG_PRESS);
            recyclerView.postDelayed(TOUCH_LONG_PRESS, LONG_PRESS_MS);
        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
            if (sTouchDownAt != 0L) {
                float slop = sTouchSlop;
                if (slop <= 0f) {
                    slop = Math.max(24f, recyclerView.getResources().getDisplayMetrics().density * 24f);
                    sTouchSlop = slop;
                }
                if (Math.hypot(event.getRawX() - sTouchDownX,
                        event.getRawY() - sTouchDownY) > slop) {
                    sTouchDownAt = 0L;
                    recyclerView.removeCallbacks(TOUCH_LONG_PRESS);
                }
            }
        } else if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            sTouchDownAt = 0L;
            recyclerView.removeCallbacks(TOUCH_LONG_PRESS);
            if (sTouchFired) {
                sTouchFired = false;
                sLastPress = nowText() + " 已弹确认，吞掉松手事件";
                return Boolean.TRUE;
            }
        }
        return sTouchFired ? Boolean.TRUE : Boolean.FALSE;
    }

    private static View findDeepestChildAt(View view, float rawX, float rawY) {
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        int[] location = new int[2];
        group.getLocationOnScreen(location);
        float x = rawX - location[0];
        float y = rawY - location[1];
        if (x < 0 || y < 0 || x > group.getWidth() || y > group.getHeight()) {
            return null;
        }
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            View deeper = findDeepestChildAt(child, rawX, rawY);
            if (deeper != null) {
                return deeper;
            }
        }
        return group;
    }

    private void bindLongPressTree(View view, int depth) {
        if (view == null || depth > 6) {
            return;
        }
        try {
            view.setOnLongClickListener(v -> {
                try {
                    return promptEntryHide(v);
                } catch (Throwable t) {
                    return false;
                }
            });
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 30);
            for (int i = 0; i < count; i++) {
                bindLongPressTree(group.getChildAt(i), depth + 1);
            }
        }
    }

    private static boolean sharesRootWithBoundView(View view) {
        View root = view.getRootView();
        if (root == null) {
            return false;
        }
        for (View bound : new ArrayList<>(sBound.keySet())) {
            if (bound != null && bound.getRootView() == root) {
                return true;
            }
        }
        return false;
    }

    private static String findEntryLabel(View view, Set<String> candidates) {
        View node = view;
        for (int level = 0; level < 4 && node != null; level++) {
            String label = matchLabelIn(node, candidates, 0);
            if (label != null) {
                return label;
            }
            android.view.ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static String matchLabelIn(View node, Set<String> candidates, int depth) {
        if (depth > 6) {
            return null;
        }
        if (node instanceof TextView) {
            CharSequence text = ((TextView) node).getText();
            String label = matchCandidate(text, candidates);
            if (label != null) {
                return label;
            }
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            int count = Math.min(group.getChildCount(), 30);
            for (int i = 0; i < count; i++) {
                String label = matchLabelIn(group.getChildAt(i), candidates, depth + 1);
                if (label != null) {
                    return label;
                }
            }
        }
        return null;
    }

    private static String matchCandidate(CharSequence text, Set<String> candidates) {
        if (text == null) {
            return null;
        }
        String value = text.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (candidates.contains(value)) {
            return value;
        }
        for (String candidate : candidates) {
            if (candidate.length() >= 2 && value.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean showHideEntryPrompt(View view, String label) {
        final Activity activity = activityOf(view.getContext());
        if (activity == null) {
            module.logd(Log.WARN, module.TAG,
                    "游戏库精简：长按「" + label + "」但取不到 Activity，无法弹窗");
            return false;
        }
        if (!markPrompt()) {
            sLastPress = nowText() + " 冷却窗口内忽略「" + label + "」";
            module.logd(Log.INFO, module.TAG,
                    "游戏库精简：长按「" + label + "」在冷却窗口内被忽略");
            return false;
        }
        final boolean[] shown = {false};
        try {
            DexKitResolver.getHeyboxDialogSpec(module, activity, new DexKitResolver.SpecCallback() {
                @Override
                public void onReady(DexKitResolver.HeyboxDialogSpec spec) {
                    if (shown[0]) {
                        return;
                    }
                    shown[0] = true;
                    activity.runOnUiThread(() -> {
                        try {
                            View content = buildPromptContent(activity, label);
                            spec.buildAndShow(activity, "隐藏入口卡片", content, "隐藏",
                                    (d, w) -> {
                                        d.dismiss();
                                        clearPrompt();
                                        hideEntryByName(activity, label);
                                    },
                                    "取消", (d, w) -> {
                                        d.dismiss();
                                        clearPrompt();
                                    });
                            logPrompt(label, "原生");
                        } catch (Throwable t) {
                            logPrompt(label, "系统（原生构建失败）");
                            showHideEntryPromptFallback(activity, label);
                        }
                    });
                }

                @Override
                public void onFailed(String reason) {
                    if (shown[0]) {
                        return;
                    }
                    shown[0] = true;
                    activity.runOnUiThread(() -> {
                        logPrompt(label, "系统（" + reason + "）");
                        showHideEntryPromptFallback(activity, label);
                    });
                }
            });
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "游戏库精简：原生弹窗通道异常，改用系统弹窗: " + t);
        }
        if (!shown[0]) {
            shown[0] = true;
            logPrompt(label, "系统（原生弹窗未缓存）");
            showHideEntryPromptFallback(activity, label);
        }
        return true;
    }

    private void logPrompt(String label, String which) {
        sLastPress = nowText() + " 命中「" + label + "」→ " + which;
        module.logd(Log.INFO, module.TAG, "游戏库精简：长按 " + label + " → " + which + "确认框");
    }

    public static String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("宿主版本: code=").append(sHostVersionCode).append('\n');
        sb.append("目标解析:\n");
        sb.append("  内层 ").append(TARGET_GAME_REC_BIND).append(" = ")
                .append(HeyboxTargets.sourceOf(TARGET_GAME_REC_BIND)).append('\n');
        sb.append("  外层 ").append(TARGET_GAME_REC_WRAPPER).append(" = ")
                .append(HeyboxTargets.sourceOf(TARGET_GAME_REC_WRAPPER)).append('\n');
        sb.append("  预绑定 ").append(TARGET_GAME_REC_BB).append(" = ")
                .append(HeyboxTargets.sourceOf(TARGET_GAME_REC_BB)).append('\n');
        sb.append("Hook 安装: 内层=").append(yes(sInstalledInner))
                .append(" 外层=").append(yes(sInstalledWrapper))
                .append(" 预绑定=").append(yes(sInstalledDelegate)).append('\n');
        sb.append("           长按兜底=").append(yes(sInstalledPress))
                .append(" 卡片挂载=").append(yes(sInstalledAttach))
                .append(" 长按可达=").append(yes(sInstalledClickable)).append('\n');
        sb.append("已绑定条目=").append(sBound.size())
                .append(" 已记录分区条目=").append(sBoardViews.size()).append('\n');
        sb.append("候选: type=").append(sObserved.size())
                .append(" 入口卡片=").append(sObservedEntries.size())
                .append(" 分区=").append(sObservedSections.size()).append('\n');
        sb.append("已选隐藏: type=").append(sCustomTypes.size())
                .append(" 入口卡片=").append(sHiddenEntries.size())
                .append(" 分区=").append(sHiddenSections.size()).append('\n');
        sb.append("开关: 横幅=").append(onOff(sHideBanner))
                .append(" 小分区=").append(onOff(sHideSmall))
                .append(" 推荐分区=").append(onOff(sHideContent)).append('\n');
        sb.append("最近长按: ").append(sLastPress);
        return sb.toString();
    }

    private static String yes(boolean value) {
        return value ? "✔" : "✘";
    }

    private static String nowText() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
    }

    private static String sHostVersionCode = "-";

    public static void setHostVersionCode(long code) {
        sHostVersionCode = String.valueOf(code);
    }

    private static String fallbackLabel(View view) {
        View node = view;
        for (int level = 0; level < 4 && node != null; level++) {
            String text = firstText(node, 0);
            if (text != null && text.length() >= 2 && text.length() <= 14) {
                return text;
            }
            android.view.ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private void logPressMiss(View view, int candidateCount) {
        try {
            if (sPressTextsLogged.size() >= 12) {
                return;
            }
            String text = firstText(view, 0);
            sLastPress = nowText() + " 未命中（附近文本=" + text + "，候选=" + candidateCount + " 个）";
            if (text != null && sPressTextsLogged.add(text)) {
                module.logd(Log.INFO, module.TAG, "游戏库精简：长按未命中入口卡片（附近文本="
                        + text + "，候选=" + candidateCount + " 个）");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String firstText(View node, int depth) {
        if (node == null || depth > 6) {
            return null;
        }
        if (node instanceof TextView) {
            CharSequence text = ((TextView) node).getText();
            if (text != null && text.toString().trim().length() > 0) {
                return text.toString().trim();
            }
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            int count = Math.min(group.getChildCount(), 30);
            for (int i = 0; i < count; i++) {
                String text = firstText(group.getChildAt(i), depth + 1);
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    private View buildPromptContent(Activity activity, String label) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = module.dp(activity, 10);
        TextView message = new TextView(activity);
        message.setText("隐藏「" + label + "」？");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        message.setPadding(pad, pad, pad, pad);
        content.addView(message, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return content;
    }

    private void showHideEntryPromptFallback(Activity activity, String label) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("隐藏入口卡片")
                    .setMessage("隐藏「" + label + "」？")
                    .setPositiveButton("隐藏", (d, w) -> hideEntryByName(activity, label))
                    .setNegativeButton("取消", null)
                    .setOnDismissListener(d -> clearPrompt())
                    .show();
        } catch (Throwable t) {
            clearPrompt();
            module.logd(Log.WARN, module.TAG, "游戏库精简：长按提示弹窗失败: " + t);
        }
    }

    private static boolean markPrompt() {
        synchronized (sPromptLock) {
            long now = System.currentTimeMillis();
            if (now - sPromptAtMs < PROMPT_COOLDOWN_MS) {
                return false;
            }
            sPromptAtMs = now;
            return true;
        }
    }

    private static void clearPrompt() {
        synchronized (sPromptLock) {
            sPromptAtMs = 0L;
        }
    }

    private void hideEntryByName(Activity activity, String label) {
        Set<String> picked = new LinkedHashSet<>(sHiddenEntries);
        if (!picked.add(label)) {
            return;
        }
        setSelectedNames(PICK_ENTRY, picked);
        module.logd(Log.INFO, module.TAG, "游戏库精简：长按隐藏入口卡片 " + label);
        try {
            Toast.makeText(activity, "已隐藏「" + label + "」（设置里可取消）", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static Activity activityOf(Context context) {
        Context ctx = context;
        for (int i = 0; i < 10 && ctx != null; i++) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            if (ctx instanceof ContextWrapper) {
                ctx = ((ContextWrapper) ctx).getBaseContext();
            } else {
                return null;
            }
        }
        return null;
    }

    private void hookChildList(Object item) {
        Class<?> itemClass = item.getClass();
        if (sChildFiltersHooked.contains(itemClass)) {
            return;
        }
        Method getItems = itemsMethodOf(itemClass);
        if (getItems == null || !sChildFiltersHooked.add(itemClass)) {
            return;
        }
        try {
            Object current = getItems.invoke(item);
            if (current instanceof List) {
                for (Object child : (List<?>) current) {
                    String label = labelOf(child);
                    if (label != null) {
                        rememberName(sObservedEntries, KEY_OBSERVED_ENTRIES, label);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        module.hook(getItems).intercept(chain -> {
            Object result = chain.proceed();
            if (!(result instanceof List)) {
                return result;
            }
            List<?> source = (List<?>) result;
            if (source.isEmpty()) {
                return result;
            }
            List<Object> visible = new ArrayList<>(source.size());
            boolean filtered = false;
            for (Object child : source) {
                String label = labelOf(child);
                if (label != null) {
                    rememberName(sObservedEntries, KEY_OBSERVED_ENTRIES, label);
                }
                if (isHidden(sHiddenEntries, label)) {
                    filtered = true;
                    if (module.isEnabled(App.KEY_VERBOSE_LOG, false) && sSeenEntries.add(label)) {
                        module.logd(Log.INFO, module.TAG, "游戏库精简：入口卡片 " + label + " → 隐藏");
                    }
                    continue;
                }
                visible.add(child);
            }
            return filtered ? visible : result;
        });
        module.logd(Log.INFO, module.TAG,
                "✔ 游戏库精简 Hook 已安装（入口卡片过滤） " + itemClass.getName() + "#getItems");
    }

    private static Method itemsMethodOf(Class<?> itemClass) {
        Method cached = sItemsMethods.get(itemClass);
        if (cached != null) {
            return cached;
        }
        try {
            Method method = itemClass.getMethod("getItems");
            if (method.getParameterCount() == 0
                    && List.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                sItemsMethods.put(itemClass, method);
                return method;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String labelOf(Object child) {
        if (child == null) {
            return null;
        }
        Class<?> childClass = child.getClass();
        Object accessor = sLabelAccessors.get(childClass);
        if (accessor == null) {
            accessor = resolveLabelAccessor(childClass);
            sLabelAccessors.put(childClass, accessor == null ? NO_ACCESSOR : accessor);
        }
        if (accessor == NO_ACCESSOR) {
            return null;
        }
        try {
            Object value = ((Method) accessor).invoke(child);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return ((String) value).trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object resolveLabelAccessor(Class<?> childClass) {
        for (String methodName : new String[]{"getName", "getDesc", "getKey", "getTitle", "getText"}) {
            for (Class<?> cls = childClass; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                try {
                    Method method = cls.getDeclaredMethod(methodName);
                    if (method.getParameterCount() == 0 && method.getReturnType() == String.class) {
                        method.setAccessible(true);
                        return method;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static void remember(String type) {
        String normalized = type.toLowerCase(Locale.ROOT);
        if (isKnown(normalized)) {
            return;
        }
        Set<String> observed = sObserved;
        if (!observed.add(normalized)) {
            return;
        }
        java.util.Iterator<String> iterator = observed.iterator();
        while (observed.size() > MAX_OBSERVED && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String item : observed) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(item);
            }
            com.better.heybox.HeyboxPrefs.setString(KEY_OBSERVED_TYPES, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static boolean isKnown(String type) {
        for (String[] entry : TYPE_CATALOG) {
            if (entry[0].equals(type)) {
                return true;
            }
        }
        return TYPES_BANNER.contains(type) || TYPES_SMALL.contains(type)
                || TYPES_CONTENT.contains(type);
    }

    public static List<String[]> pickerEntries() {
        List<String[]> out = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        for (String[] entry : TYPE_CATALOG) {
            out.add(new String[]{entry[0], entry[1]});
            known.add(entry[0]);
        }
        for (String type : new ArrayList<>(sObserved)) {
            if (!known.contains(type)) {
                out.add(new String[]{type, ""});
            }
        }
        return out;
    }

    public static Set<String> selectedTypes() {
        return new LinkedHashSet<>(sCustomTypes);
    }

    public static void setSelectedTypes(java.util.Collection<String> types) {
        StringBuilder sb = new StringBuilder();
        if (types != null) {
            for (String type : types) {
                if (type == null || type.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(type.trim().toLowerCase(Locale.ROOT));
            }
        }
        try {
            com.better.heybox.HeyboxPrefs.setString(App.KEY_GAME_LIB_HIDE_TYPES, sb.toString());
        } catch (Throwable ignored) {
        }
        refresh();
    }

    public static List<String[]> pickerEntries(int kind) {
        if (kind == PICK_ENTRY) {
            return nameEntries(sHiddenEntries, sObservedEntries);
        }
        if (kind == PICK_SECTION) {
            return nameEntries(sHiddenSections, sObservedSections);
        }
        return pickerEntries();
    }

    private static List<String[]> nameEntries(Set<String> hidden, Set<String> observed) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : new ArrayList<>(hidden)) {
            if (seen.add(name)) {
                out.add(new String[]{name, ""});
            }
        }
        for (String name : new ArrayList<>(observed)) {
            if (seen.add(name)) {
                out.add(new String[]{name, ""});
            }
        }
        return out;
    }

    public static Set<String> selectedNames(int kind) {
        return new LinkedHashSet<>(kind == PICK_ENTRY ? sHiddenEntries : sHiddenSections);
    }

    public static void setSelectedNames(int kind, java.util.Collection<String> names) {
        StringBuilder sb = new StringBuilder();
        if (names != null) {
            for (String name : names) {
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(name.trim());
            }
        }
        try {
            com.better.heybox.HeyboxPrefs.setString(kind == PICK_ENTRY
                    ? App.KEY_GAME_LIB_HIDE_ENTRIES : App.KEY_GAME_LIB_HIDE_SECTION_NAMES,
                    sb.toString());
        } catch (Throwable ignored) {
        }
        refresh();
    }

    public static String coverageHint(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        if (TYPES_BANNER.contains(normalized)) {
            return "已在「隐藏游戏库横幅」";
        }
        if (TYPES_SMALL.contains(normalized)) {
            return "已在「隐藏游戏库小分区」";
        }
        if (TYPES_CONTENT.contains(normalized)) {
            return "已在「隐藏游戏库推荐分区」";
        }
        return null;
    }

    private static void apply(View itemView, String type, String section) {
        if (itemView == null || type == null) {
            return;
        }
        if (isHiddenNow(type, section)) {
            FeedItemHider.hide(itemView);
        } else {
            FeedItemHider.restore(itemView);
        }
    }

    private static boolean isHiddenNow(String type, String section) {
        return shouldHide(type) || isHidden(sHiddenSections, section);
    }

    private static boolean shouldHide(String type) {
        String normalized = type.toLowerCase(Locale.ROOT);
        Set<String> custom = sCustomTypes;
        if (!custom.isEmpty() && custom.contains(normalized)) {
            return true;
        }
        return (sHideBanner && TYPES_BANNER.contains(normalized))
                || (sHideSmall && TYPES_SMALL.contains(normalized))
                || (sHideContent && TYPES_CONTENT.contains(normalized));
    }

    private static boolean isHidden(Set<String> hidden, String name) {
        return name != null && !hidden.isEmpty()
                && hidden.contains(name.toLowerCase(Locale.ROOT));
    }

    private static String typeOf(Object item) {
        if (item == null) {
            return null;
        }
        Class<?> itemClass = item.getClass();
        Object accessor = sAccessors.get(itemClass);
        if (accessor == null) {
            accessor = resolveAccessor(itemClass);
            sAccessors.put(itemClass, accessor == null ? NO_ACCESSOR : accessor);
        }
        if (accessor == NO_ACCESSOR) {
            return null;
        }
        try {
            Object value = accessor instanceof Method
                    ? ((Method) accessor).invoke(item)
                    : ((Field) accessor).get(item);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object resolveAccessor(Class<?> itemClass) {
        for (Class<?> cls = itemClass; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                Method method = cls.getDeclaredMethod("getType");
                if (method.getParameterCount() == 0 && method.getReturnType() == String.class) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (Throwable ignored) {
            }
        }
        for (Class<?> cls = itemClass; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                Field field = cls.getDeclaredField("type");
                if (field.getType() == String.class) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
