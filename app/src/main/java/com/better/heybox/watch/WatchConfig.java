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
    public static final int DEFAULT_INTERVAL_MIN = 10;
    public static final int MAX_USERS = 30;
    public static final int MAX_KEYWORDS = 20;
    public static final int MAX_LIMIT_PER_USER = 20;
    /** 已提醒 link_id 的记忆上限（有界，避免配置无限膨胀） */
    public static final int SEEN_LIMIT = 400;

    public final boolean enabled;
    public final boolean banner;
    public final boolean notify;
    public final boolean pushEnabled;
    public final int windowDays;
    public final int intervalMin;
    public final List<String> users;
    public final List<String> keywords;
    public final String dingtalk;
    public final String wxpusher;
    public final String onebot;
    public final String custom;

    private WatchConfig(boolean enabled, boolean banner, boolean notify, boolean pushEnabled,
                        int windowDays, int intervalMin, List<String> users, List<String> keywords,
                        String dingtalk, String wxpusher, String onebot, String custom) {
        this.enabled = enabled;
        this.banner = banner;
        this.notify = notify;
        this.pushEnabled = pushEnabled;
        this.windowDays = windowDays;
        this.intervalMin = intervalMin;
        this.users = users;
        this.keywords = keywords;
        this.dingtalk = dingtalk;
        this.wxpusher = wxpusher;
        this.onebot = onebot;
        this.custom = custom;
    }

    public static WatchConfig load(MainModule module) {
        int win = parseInt(module.getString(App.KEY_WATCH_WINDOW_DAYS, String.valueOf(DEFAULT_WINDOW_DAYS)), DEFAULT_WINDOW_DAYS);
        int inter = parseInt(module.getString(App.KEY_WATCH_INTERVAL_MIN, String.valueOf(DEFAULT_INTERVAL_MIN)), DEFAULT_INTERVAL_MIN);
        return new WatchConfig(
                module.isEnabled(App.KEY_WATCH_ENABLED, false),
                module.isEnabled(App.KEY_WATCH_BANNER, true),
                module.isEnabled(App.KEY_WATCH_NOTIFY, true),
                module.isEnabled(App.KEY_WATCH_PUSH_ENABLED, false),
                clamp(win, 1, 30),
                clamp(inter, 3, 720),
                splitLines(module.getString(App.KEY_WATCH_USERS, ""), MAX_USERS),
                splitLines(module.getString(App.KEY_WATCH_KEYWORDS, ""), MAX_KEYWORDS),
                module.getString(App.KEY_WATCH_PUSH_DINGTALK, "").trim(),
                module.getString(App.KEY_WATCH_PUSH_WXPUSHER, "").trim(),
                module.getString(App.KEY_WATCH_PUSH_ONEBOT, "").trim(),
                module.getString(App.KEY_WATCH_PUSH_CUSTOM, "").trim());
    }

    /** 没有任何监控目标时不必轮询 */
    public boolean hasTargets() {
        return !users.isEmpty() || !keywords.isEmpty();
    }

    public boolean hasAnyChannel() {
        return banner || notify || (pushEnabled && (!dingtalk.isEmpty() || !wxpusher.isEmpty()
                || !onebot.isEmpty() || !custom.isEmpty()));
    }

    public long windowSeconds() {
        return windowDays * 86400L;
    }

    /** 逐行切分并去掉空行/注释，最多保留 max 条 */
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
        if (s.matches("\\d{5,20}")) {
            return s;
        }
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
