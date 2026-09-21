package com.better.heybox.hooks;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.better.heybox.App;
import com.better.heybox.Checkpoint;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.MainModule;

public final class MessageRedDotHook {

    private static final String HOST_CACHE_CLASS = "com.max.hbcache.c";
    private static final String UNREAD_METHOD = "v";
    private static final String BASE_ADAPTER_CLASS = "com.max.hbcommon.base.adapter.s";
    private static final String HOLDER_CLASS = "com.max.hbcommon.base.adapter.s$e";
    private static final String ID_BADGE = "badge";
    private static final String[] ID_TITLES = new String[]{"tv_title", "tv_name"};
    private static final String KEY_OBSERVED = "msg_badge_seen_entries";
    private static final int MAX_OBSERVED = 60;
    public static final int PICK_NUMBER = 1;
    public static final int PICK_FULL = 2;
    private static final String[] DEFAULT_NUMBER_NAMES = new String[]{"活动消息", "官方消息"};
    private final MainModule module;
    private static volatile MessageRedDotHook sInstance;
    private static volatile boolean sHideDot;
    private static volatile boolean sEntryClean;
    private static volatile Set<String> sNumberNames = Collections.emptySet();
    private static volatile Set<String> sFullNames = Collections.emptySet();
    private static volatile Set<String> sObserved = new LinkedHashSet<>();
    private static volatile boolean sDotHooked;
    private static volatile boolean sRowHooked;
    private static final Map<View, Hidden> sHiddenBadges =
            Collections.synchronizedMap(new WeakHashMap<View, Hidden>());
    private static final Map<View, String> sHiddenRows =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    private static final class Hidden {
        final String title;
        final int visibility;

        Hidden(String title, int visibility) {
            this.title = title;
            this.visibility = visibility;
        }
    }

    private static final Map<String, Integer> sIds = new ConcurrentHashMap<>();
    private static final Map<Class<?>, java.lang.reflect.Field> sItemViewFields = new ConcurrentHashMap<>();
    private static final Set<String> sWarned = ConcurrentHashMap.newKeySet();
    private static final Set<String> sLoggedTitles = ConcurrentHashMap.newKeySet();

    public MessageRedDotHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    public static void refresh() {
        MessageRedDotHook instance = sInstance;
        if (instance == null) {
            return;
        }
        MainModule m = instance.module;
        boolean clean = m.isEnabled(App.KEY_HIDE_MSG_BADGE, false);
        Set<String> numbers = resolveNames(m.getString(App.KEY_MSG_BADGE_ENTRIES, null),
                DEFAULT_NUMBER_NAMES);
        Set<String> full = resolveNames(m.getString(App.KEY_MSG_FULL_HIDE_ENTRIES, null),
                new String[0]);
        sHideDot = m.isEnabled(App.KEY_HIDE_MSG_DOT, false);
        sEntryClean = clean;
        sNumberNames = numbers;
        sFullNames = full;
        loadObserved();
        if (!clean) {
            restoreAll();
            return;
        }
        for (Map.Entry<View, String> entry : new ArrayList<>(sHiddenRows.entrySet())) {
            String title = entry.getValue();
            if (title == null || !full.contains(title)) {
                restoreRow(entry.getKey());
            }
        }
        for (Map.Entry<View, Hidden> entry : new ArrayList<>(sHiddenBadges.entrySet())) {
            Hidden hidden = entry.getValue();
            if (hidden == null || !numbers.contains(hidden.title) || full.contains(hidden.title)) {
                restoreBadge(entry.getKey());
            }
        }
    }

    public void install(ClassLoader cl) {
        refresh();
        boolean dot = hookUnreadFlag(cl);
        boolean rows = hookRowBind(cl);
        sDotHooked = dot;
        sRowHooked = rows;
        Checkpoint.mark("消息红点安装: 红点=%b 入口=%b 数字=%d 整行=%d",
                dot, rows, sNumberNames.size(), sFullNames.size());
        module.logd(Log.INFO, module.TAG, "✔ 消息红点 Hook 已安装（未读红点="
                + (dot ? "已挂" : "未挂") + " / 消息入口=" + (rows ? "已挂" : "未挂")
                + " | 红点=" + onOff(sHideDot)
                + " 入口精简=" + onOff(sEntryClean)
                + "（红数字 " + sNumberNames.size() + " 项 / 整行 " + sFullNames.size() + " 项））");
    }

    private boolean hookUnreadFlag(ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(HOST_CACHE_CLASS, false, cl);
            Method getter = cls.getDeclaredMethod(UNREAD_METHOD);
            getter.setAccessible(true);
            module.hook(getter).intercept(chain -> sHideDot ? Boolean.FALSE : chain.proceed());
            module.logd(Log.INFO, module.TAG, "✔ 消息红点：未读标记挂点 "
                    + HOST_CACHE_CLASS + "#" + UNREAD_METHOD + "()Z");
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG,
                    "✘ 消息红点：未读标记挂点失败（本版本无该目标），红点开关将不生效: " + t);
            return false;
        }
    }

    private boolean hookRowBind(ClassLoader cl) {
        try {
            Class<?> adapter = Class.forName(BASE_ADAPTER_CLASS, false, cl);
            Class<?> holder = Class.forName(HOLDER_CLASS, false, cl);
            Method bind = adapter.getDeclaredMethod("onBindViewHolder", holder, int.class);
            module.hook(bind).intercept(chain -> {
                Object viewHolder = chain.getArg(0);
                if (sEntryClean) {
                    try {
                        restoreForBind(viewHolder);
                    } catch (Throwable t) {
                        warnOnce("restore", "消息红点：入口行还原异常，已放行: " + t);
                    }
                }
                Object result = chain.proceed();
                if (sEntryClean) {
                    try {
                        applyBind(viewHolder);
                    } catch (Throwable t) {
                        warnOnce("apply", "消息红点：消息入口处理异常，已放行: " + t);
                    }
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 消息红点：消息入口挂点 "
                    + BASE_ADAPTER_CLASS + "#onBindViewHolder(" + HOLDER_CLASS + ", int)");
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG,
                    "✘ 消息红点：消息入口挂点失败（本版本无该目标），入口开关将不生效: " + t);
            return false;
        }
    }

    private static void restoreForBind(Object holder) {
        View itemView = itemViewOf(holder);
        if (itemView == null) {
            return;
        }
        if (sHiddenRows.containsKey(itemView)) {
            restoreRow(itemView);
        }
        int badgeId = idOf(itemView, ID_BADGE);
        if (badgeId == 0) {
            return;
        }
        View badge = itemView.findViewById(badgeId);
        if (badge != null && sHiddenBadges.containsKey(badge)) {
            restoreBadge(badge);
        }
    }

    private static void applyBind(Object holder) {
        View itemView = itemViewOf(holder);
        if (itemView == null) {
            return;
        }
        int badgeId = idOf(itemView, ID_BADGE);
        if (badgeId == 0) {
            return;
        }
        View badge = itemView.findViewById(badgeId);
        if (badge == null) {
            return;
        }
        String title = rowTitle(itemView);
        if (title == null || title.isEmpty()) {
            warnOnce("notitle", "消息红点：入口行没有标题文本，已跳过（入口以实际可见文案为准）");
            return;
        }
        rememberName(title);
        if (sFullNames.contains(title)) {
            sHiddenRows.put(itemView, title);
            FeedItemHider.hide(itemView);
            logOnce("row:" + title, "完整隐藏消息入口: " + title);
            return;
        }
        if (sNumberNames.contains(title)) {
            sHiddenBadges.put(badge, new Hidden(title, badge.getVisibility()));
            badge.setVisibility(View.GONE);
            logOnce("badge:" + title, "隐藏消息入口红数字: " + title);
        }
    }

    private static String rowTitle(View itemView) {
        for (String name : ID_TITLES) {
            int id = idOf(itemView, name);
            if (id == 0) {
                continue;
            }
            View view = itemView.findViewById(id);
            if (!(view instanceof TextView)) {
                continue;
            }
            CharSequence text = ((TextView) view).getText();
            if (text == null) {
                continue;
            }
            String value = text.toString().trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static void restoreRow(View itemView) {
        if (itemView == null) {
            return;
        }
        String title = sHiddenRows.remove(itemView);
        if (title == null) {
            return;
        }
        FeedItemHider.restore(itemView);
        logv("恢复消息入口整行: " + title);
    }

    private static void restoreBadge(View badge) {
        if (badge == null) {
            return;
        }
        Hidden hidden = sHiddenBadges.remove(badge);
        if (hidden == null) {
            return;
        }
        try {
            badge.setVisibility(hidden.visibility);
        } catch (Throwable ignored) {
        }
        logv("恢复消息入口红数字: " + hidden.title);
    }

    private static void restoreAll() {
        for (View row : new ArrayList<>(sHiddenRows.keySet())) {
            restoreRow(row);
        }
        for (View badge : new ArrayList<>(sHiddenBadges.keySet())) {
            restoreBadge(badge);
        }
    }

    public static List<String[]> pickerEntries(int kind) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : selectedNames(kind)) {
            if (seen.add(name)) {
                out.add(new String[]{name, ""});
            }
        }
        loadObserved();
        for (String name : new ArrayList<>(sObserved)) {
            if (seen.add(name)) {
                out.add(new String[]{name, ""});
            }
        }
        return out;
    }

    public static Set<String> selectedNames(int kind) {
        if (kind == PICK_FULL) {
            return resolveNames(readString(App.KEY_MSG_FULL_HIDE_ENTRIES, null), new String[0]);
        }
        return resolveNames(readString(App.KEY_MSG_BADGE_ENTRIES, null), DEFAULT_NUMBER_NAMES);
    }

    public static void setSelectedNames(int kind, Collection<String> names) {
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
            HeyboxPrefs.setString(kind == PICK_FULL
                    ? App.KEY_MSG_FULL_HIDE_ENTRIES : App.KEY_MSG_BADGE_ENTRIES, sb.toString());
        } catch (Throwable ignored) {
        }
        refresh();
    }

    public static String diagnostics() {
        MessageRedDotHook instance = sInstance;
        boolean dot = instance != null
                ? instance.module.isEnabled(App.KEY_HIDE_MSG_DOT, false) : sHideDot;
        boolean clean = instance != null
                ? instance.module.isEnabled(App.KEY_HIDE_MSG_BADGE, false) : sEntryClean;
        Set<String> numbers = selectedNames(PICK_NUMBER);
        Set<String> full = selectedNames(PICK_FULL);
        loadObserved();
        StringBuilder sb = new StringBuilder();
        sb.append("未读红点=").append(onOff(dot))
                .append("（挂点").append(sDotHooked ? "已装" : "未装").append("）").append('\n');
        sb.append("消息入口精简=").append(onOff(clean))
                .append("（挂点").append(sRowHooked ? "已装" : "未装").append("）").append('\n');
        sb.append("隐藏红数字（").append(numbers.size()).append("）：");
        sb.append(join(numbers)).append('\n');
        sb.append("完整隐藏（").append(full.size()).append("）：");
        sb.append(join(full)).append('\n');
        sb.append("已观察到（").append(sObserved.size()).append("）：");
        sb.append(join(sObserved)).append('\n');
        sb.append("提示：候选只在「消息入口精简」开关打开并浏览过消息列表后才会累积");
        return sb.toString();
    }

    private static String join(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(value);
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    private static String onOff(boolean enabled) {
        return enabled ? "隐藏" : "保留";
    }

    private static String readString(String key, String def) {
        MessageRedDotHook instance = sInstance;
        if (instance != null) {
            return instance.module.getString(key, def);
        }
        try {
            return HeyboxPrefs.getString(key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static Set<String> resolveNames(String raw, String[] defaults) {
        if (raw == null) {
            Set<String> out = new LinkedHashSet<>();
            Collections.addAll(out, defaults);
            return out;
        }
        return parseNames(raw);
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

    private static void loadObserved() {
        Set<String> loaded = parseNames(HeyboxPrefs.getString(KEY_OBSERVED, ""));
        if (!loaded.isEmpty()) {
            sObserved = new LinkedHashSet<>(loaded);
        }
    }

    private static void rememberName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String value = name.trim();
        Set<String> observed = sObserved;
        if (observed.contains(value)) {
            return;
        }
        Set<String> next = new LinkedHashSet<>(observed);
        next.add(value);
        Iterator<String> iterator = next.iterator();
        while (next.size() > MAX_OBSERVED && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
        sObserved = next;
        try {
            StringBuilder sb = new StringBuilder();
            for (String item : next) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(item);
            }
            HeyboxPrefs.setString(KEY_OBSERVED, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static View itemViewOf(Object holder) {
        if (holder == null) {
            return null;
        }
        Class<?> cls = holder.getClass();
        java.lang.reflect.Field cached = sItemViewFields.get(cls);
        if (cached != null) {
            try {
                Object value = cached.get(holder);
                return value instanceof View ? (View) value : null;
            } catch (Throwable ignored) {
            }
        }
        try {
            java.lang.reflect.Field field = cls.getField("itemView");
            sItemViewFields.put(cls, field);
            Object value = field.get(holder);
            return value instanceof View ? (View) value : null;
        } catch (Throwable t) {
            return FeedItemHider.getItemView(holder);
        }
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
            warnOnce("id:" + name, "消息红点：当前版本没有资源 " + name + "，入口精简对该行不生效");
            return 0;
        }
        sIds.put(name, id);
        return id;
    }

    private static void warnOnce(String key, String message) {
        MessageRedDotHook instance = sInstance;
        if (instance == null || !sWarned.add(key)) {
            return;
        }
        instance.module.logd(Log.WARN, instance.module.TAG, message);
    }

    private static void logOnce(String key, String message) {
        if (!sLoggedTitles.add(key)) {
            return;
        }
        log(Log.INFO, message);
    }

    private static void log(int level, String message) {
        MessageRedDotHook instance = sInstance;
        if (instance != null) {
            instance.module.logd(level, instance.module.TAG, message);
        }
    }

    private static void logv(String message) {
        MessageRedDotHook instance = sInstance;
        if (instance != null) {
            instance.module.logv(instance.module.TAG, message);
        }
    }
}
