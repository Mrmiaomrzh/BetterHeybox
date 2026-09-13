package com.better.heybox.watch;

import com.better.heybox.App;
import com.better.heybox.MainModule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 已推送集合 + 上次检查时间。
 *
 * <p>用 betterheybox.xml 里的定长字符串保存（逗号分隔、有界），避免引入新文件；
 * 这样也能被现有的配置备份机制一并带走。
 */
public final class WatchSeen {

    private static final Object LOCK = new Object();
    private static Set<String> sSeen;
    private static MainModule sModule;

    public static void init(MainModule module) {
        sModule = module;
    }

    private static Set<String> seen() {
        if (sSeen == null) {
            Set<String> set = new LinkedHashSet<>();
            String raw = App.readString(App.KEY_WATCH_SEEN, "");
            for (String s : raw.split(",")) {
                String v = s.trim();
                if (!v.isEmpty()) {
                    set.add(v);
                }
            }
            sSeen = set;
        }
        return sSeen;
    }

    /** @return true 表示这条是新看到的（尚未推送过） */
    public static boolean markNew(String linkId) {
        if (linkId == null || linkId.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            Set<String> set = seen();
            if (set.contains(linkId)) {
                return false;
            }
            set.add(linkId);
            persist(set);
            return true;
        }
    }

    public static boolean contains(String linkId) {
        synchronized (LOCK) {
            return seen().contains(linkId);
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return seen().size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            sSeen = new LinkedHashSet<>();
            persist(sSeen);
        }
    }

    private static void persist(Set<String> set) {
        // 有界：超出上限时丢最早的
        List<String> list = new ArrayList<>(set);
        if (list.size() > WatchConfig.SEEN_LIMIT) {
            list = list.subList(list.size() - WatchConfig.SEEN_LIMIT, list.size());
            set.clear();
            set.addAll(list);
        }
        App.writeString(App.KEY_WATCH_SEEN, String.join(",", list));
    }

    // ------------------------------------------------------------ 首轮基线

    private static Set<String> sBaselined;

    private static Set<String> baselined() {
        if (sBaselined == null) {
            Set<String> set = new LinkedHashSet<>();
            for (String s : App.readString(App.KEY_WATCH_BASELINED, "").split(",")) {
                String v = s.trim();
                if (!v.isEmpty()) {
                    set.add(v);
                }
            }
            sBaselined = set;
        }
        return sBaselined;
    }

    /** 该关注对象是否已完成首轮基线（首轮只记录不推送，避免把历史帖全推一遍） */
    public static boolean isBaselined(String userId) {
        if (userId == null || userId.isEmpty()) {
            return true;
        }
        synchronized (LOCK) {
            return baselined().contains(userId);
        }
    }

    public static void markBaselined(String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            Set<String> set = baselined();
            set.add(userId);
            App.writeString(App.KEY_WATCH_BASELINED, String.join(",", set));
        }
    }

    public static void clearBaselines() {
        synchronized (LOCK) {
            sBaselined = new LinkedHashSet<>();
            App.writeString(App.KEY_WATCH_BASELINED, "");
        }
    }

    /** 记录一次检查时间；用于节流 */
    public static void touchCheck() {
        App.writeString(App.KEY_WATCH_LAST_CHECK, String.valueOf(System.currentTimeMillis()));
    }

    public static long lastCheck() {
        try {
            return Long.parseLong(App.readString(App.KEY_WATCH_LAST_CHECK, "0").trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
