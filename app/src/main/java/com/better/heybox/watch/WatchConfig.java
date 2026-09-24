package com.better.heybox.watch;

import com.better.heybox.App;
import com.better.heybox.MainModule;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态推送配置。全部存 betterheybox.xml，与模块其它开关一致。
 *
 * <p>对应设置面板「动态推送」分组；解析规则：
 * <ul>
 *   <li>关注列表：一行一个，支持纯 userid 或用户主页链接</li>
 *   <li>监控关键词：一行一个，regex: 前缀为正则（与发帖过滤同一套写法）</li>
 * </ul>
 */
public final class WatchConfig {

    public static final int DEFAULT_WINDOW_DAYS = 3;
    public static final int DEFAULT_WINDOW_MIN = DEFAULT_WINDOW_DAYS * 24 * 60;
    public static final int DEFAULT_INTERVAL_MIN = 10;
    public static final int MAX_USERS = 30;
    public static final int MAX_KEYWORDS = 20;
    public static final int MAX_TOPICS = 20;
    public static final int MAX_LIMIT_PER_USER = 20;
    /** 已提醒 link_id 的记忆上限（有界，避免配置无限膨胀） */
    public static final int SEEN_LIMIT = 400;

    public final boolean enabled;
    public final boolean banner;
    public final boolean notify;
    public final boolean pushEnabled;
    public final int windowDays;
    /** 获取时间窗（分钟），优先于 windowDays */
    public final int windowMin;
    public final int intervalMin;
    public final List<String> users;
    public final List<String> keywords;
    /** 关注的话题，每项形如 "topicId|话题名"（id 可能为空） */
    public final List<String> topics;
    /** 关键词只匹配标题 */
    public final boolean titleOnly;
    /** 关键词/话题主动拉流 */
    public final boolean streamFetch;
    public final String dingtalk;
    public final String wxpusher;
    public final String onebot;
    public final String custom;

    private WatchConfig(boolean enabled, boolean banner, boolean notify, boolean pushEnabled,
                        int windowDays, int windowMin, int intervalMin, List<String> users,
                        List<String> keywords, List<String> topics, boolean titleOnly,
                        boolean streamFetch,
                        String dingtalk, String wxpusher, String onebot, String custom) {
        this.enabled = enabled;
        this.banner = banner;
        this.notify = notify;
        this.pushEnabled = pushEnabled;
        this.windowDays = windowDays;
        this.windowMin = windowMin;
        this.intervalMin = intervalMin;
        this.users = users;
        this.keywords = keywords;
        this.topics = topics;
        this.titleOnly = titleOnly;
        this.streamFetch = streamFetch;
        this.dingtalk = dingtalk;
        this.wxpusher = wxpusher;
        this.onebot = onebot;
        this.custom = custom;
    }

    private static final long CACHE_TTL_MS = 1000L;
    private static volatile WatchConfig sCached;
    private static volatile long sCachedAt;
    private static volatile MainModule sCachedModule;

    public static void invalidate() {
        sCached = null;
        sCachedModule = null;
        sCachedAt = 0L;
    }

    public static WatchConfig load(MainModule module) {
        WatchConfig cached = sCached;
        long now = android.os.SystemClock.elapsedRealtime();
        if (cached != null && sCachedModule == module && now - sCachedAt < CACHE_TTL_MS) {
            return cached;
        }
        WatchConfig built = build(module);
        sCached = built;
        sCachedModule = module;
        sCachedAt = now;
        return built;
    }

    private static WatchConfig build(MainModule module) {
        int win = parseInt(module.getString(App.KEY_WATCH_WINDOW_DAYS, String.valueOf(DEFAULT_WINDOW_DAYS)), DEFAULT_WINDOW_DAYS);
        // 新的「获取时间窗」按分钟存，未设置时回落到旧的「天」
        int winMin = parseInt(module.getString(App.KEY_WATCH_WINDOW_MIN, ""), 0);
        if (winMin <= 0) {
            winMin = clamp(win, 1, 30) * 24 * 60;
        }
        int inter = parseInt(module.getString(App.KEY_WATCH_INTERVAL_MIN, String.valueOf(DEFAULT_INTERVAL_MIN)), DEFAULT_INTERVAL_MIN);
        return new WatchConfig(
                module.isEnabled(App.KEY_WATCH_ENABLED, false),
                module.isEnabled(App.KEY_WATCH_BANNER, true),
                module.isEnabled(App.KEY_WATCH_NOTIFY, true),
                module.isEnabled(App.KEY_WATCH_PUSH_ENABLED, false),
                clamp(win, 1, 30),
                clamp(winMin, 5, 30 * 24 * 60),
                clamp(inter, 3, 720),
                splitLines(module.getString(App.KEY_WATCH_USERS, ""), MAX_USERS),
                splitLines(module.getString(App.KEY_WATCH_KEYWORDS, ""), MAX_KEYWORDS),
                splitLines(module.getString(App.KEY_WATCH_TOPICS, ""), MAX_TOPICS),
                module.isEnabled(App.KEY_WATCH_TITLE_ONLY, false),
                module.isEnabled(App.KEY_WATCH_STREAM_FETCH, false),
                module.getString(App.KEY_WATCH_PUSH_DINGTALK, "").trim(),
                module.getString(App.KEY_WATCH_PUSH_WXPUSHER, "").trim(),
                module.getString(App.KEY_WATCH_PUSH_ONEBOT, "").trim(),
                module.getString(App.KEY_WATCH_PUSH_CUSTOM, "").trim());
    }

    /** 没有任何监控目标时不必轮询 */
    public boolean hasTargets() {
        return !users.isEmpty() || !keywords.isEmpty() || !topics.isEmpty();
    }

    public boolean hasAnyChannel() {
        return banner || notify || (pushEnabled && (!dingtalk.isEmpty() || !wxpusher.isEmpty()
                || !onebot.isEmpty() || !custom.isEmpty()));
    }

    public long windowSeconds() {
        return windowMin * 60L;
    }

    /** 时间窗的可读描述，如 "3 天" / "6 小时" */
    public String windowText() {
        if (windowMin % (24 * 60) == 0) {
            return (windowMin / (24 * 60)) + " 天";
        }
        if (windowMin % 60 == 0) {
            return (windowMin / 60) + " 小时";
        }
        return windowMin + " 分钟";
    }

    public static String formatTopic(String id, String name) {
        String n = name == null ? "" : name.trim();
        String i = id == null ? "" : id.trim();
        return i.isEmpty() ? n : (i + "|" + n);
    }

    public static String parseTopicId(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int bar = s.indexOf('|');
        if (bar > 0) {
            String head = s.substring(0, bar).trim();
            return head.matches("\\d{1,20}") ? head : null;
        }
        return s.matches("\\d{5,20}") ? s : null;
    }

    public static String topicName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int bar = s.indexOf('|');
        String name = bar >= 0 ? s.substring(bar + 1).trim() : s;
        return name.isEmpty() ? s : name;
    }

    public static List<String> splitLines(String raw, int max) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            out.add(s);
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /**
     * 从用户输入解析 userid：支持 {@code 12345678} 与
     * {@code https://www.xiaoheihe.cn/app/user/12345678} / {@code .../profile/user/12345678}
     * 这类主页链接，取最长的一段连续数字（≥5 位）。
     */
    public static String parseUserId(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 支持 "userid  # 昵称" / "userid | 昵称" 这类带注释的写法：只取第一段
        String head = s;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '#' || c == ' ' || c == '\t' || c == '|' || c == ',') {
                head = s.substring(0, i).trim();
                break;
            }
        }
        if (head.matches("\\d{5,20}")) {
            return head;
        }
        s = head;
        String best = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{5,20})").matcher(s);
        while (m.find()) {
            String g = m.group(1);
            if (best == null || g.length() > best.length()) {
                best = g;
            }
        }
        return best;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
