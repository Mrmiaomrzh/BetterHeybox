package com.better.heybox.watch;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;
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
    private static boolean sDirty;

    private static final java.util.concurrent.ScheduledExecutorService sWriter =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "betterheybox-seen");
                t.setDaemon(true);
                return t;
            });
    private static final long PERSIST_DELAY_MS = 2000L;
    private static final java.util.concurrent.atomic.AtomicBoolean sPersistScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void schedulePersist() {
        if (sPersistScheduled.compareAndSet(false, true)) {
            try {
                sWriter.schedule(() -> {
                    sPersistScheduled.set(false);
                    flushNow();
                }, PERSIST_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                sPersistScheduled.set(false);
            }
        }
    }

    public static void flushNow() {
        String joined;
        synchronized (LOCK) {
            if (!sDirty || sSeen == null) {
                return;
            }
            sDirty = false;
            List<String> list = new ArrayList<>(sSeen);
            if (list.size() > WatchConfig.SEEN_LIMIT) {
                list = list.subList(list.size() - WatchConfig.SEEN_LIMIT, list.size());
                sSeen.clear();
                sSeen.addAll(list);
            }
            joined = String.join(",", list);
        }
        HeyboxPrefs.setString(App.KEY_WATCH_SEEN, joined);
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    private static Set<String> seen() {
        if (sSeen == null) {
            Set<String> set = new LinkedHashSet<>();
            String raw = HeyboxPrefs.getString(App.KEY_WATCH_SEEN, "");
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
            sDirty = true;
        }
        schedulePersist();
        return true;
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
            sDirty = true;
        }
        flushNow();
    }

    // ------------------------------------------------------------ 首轮基线

    private static Set<String> sBaselined;

    private static Set<String> baselined() {
        if (sBaselined == null) {
            Set<String> set = new LinkedHashSet<>();
            for (String s : HeyboxPrefs.getString(App.KEY_WATCH_BASELINED, "").split(",")) {
                String v = s.trim();
                if (!v.isEmpty()) {
                    set.add(v);
                }
            }
            sBaselined = set;
        }
        return sBaselined;
    }

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
        String joined;
        synchronized (LOCK) {
            Set<String> set = baselined();
            set.add(userId);
            joined = String.join(",", set);
        }
        HeyboxPrefs.setString(App.KEY_WATCH_BASELINED, joined);   // 锁外写盘
    }

    public static void clearBaselines() {
        synchronized (LOCK) {
            sBaselined = new LinkedHashSet<>();
        }
        HeyboxPrefs.setString(App.KEY_WATCH_BASELINED, "");
    }

    public static void touchCheck() {
        HeyboxPrefs.setString(App.KEY_WATCH_LAST_CHECK, String.valueOf(System.currentTimeMillis()));
    }

    public static long lastCheck() {
        try {
            return Long.parseLong(HeyboxPrefs.getString(App.KEY_WATCH_LAST_CHECK, "0").trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
