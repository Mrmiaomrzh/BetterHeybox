package com.better.heybox.watch;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.better.heybox.MainModule;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 动态推送引擎：过滤 → 去重 → 输出。
 *
 * <p>三种触发时机（都不需要任何凭据）：
 * <ol>
 *   <li><b>打开小黑盒</b>：MainActivity onCreate/onResume → 主动拉一次关注用户的帖子</li>
 *   <li><b>搭便车</b>：宿主收到任意推送（个推/厂商通道）时 HBGTIntentService 回调 → 顺手拉一次</li>
 *   <li><b>被动命中</b>：信息流反序列化时直接看当前这条帖子的原始 JSON → 命中即提醒（零网络）</li>
 * </ol>
 * 全部 fail-open：任何异常只记日志，绝不影响宿主。
 */
public final class WatchEngine {

    private static volatile MainModule sModule;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "betterheybox-watch");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static volatile WeakReference<Activity> sActivity = new WeakReference<>(null);
    private static volatile String sLastResult = "尚未检查";

    private WatchEngine() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    public static String lastResult() {
        return sLastResult;
    }

    // ------------------------------------------------------------ 触发入口

    /** 打开小黑盒（MainActivity 创建/回到前台） */
    public static void onAppOpen(Activity activity) {
        if (activity != null) {
            sActivity = new WeakReference<>(activity);
        }
        maybeCheck(activity, "打开小黑盒", false);
    }

    /** 宿主收到推送时搭便车 */
    public static void onPushArrived(Context context) {
        maybeCheck(context, "收到推送", false);
    }

    /** 设置面板"立即检查" */
    public static void checkNow(Activity activity, boolean announce) {
        maybeCheck(activity, "手动检查", true);
        if (announce) {
            sLastResult = "已触发检查，结果见日志";
        }
    }

    private static void maybeCheck(Context context, String reason, boolean force) {
        MainModule module = sModule;
        if (module == null || context == null) {
            return;
        }
        try {
            WatchConfig cfg = WatchConfig.load(module);
            if (!cfg.enabled) {
                return;
            }
            if (!cfg.hasTargets()) {
                sLastResult = "未配置关注对象或关键词";
                return;
            }
            if (!cfg.hasAnyChannel()) {
                sLastResult = "未开启任何提醒方式";
                return;
            }
            long now = System.currentTimeMillis();
            long elapsed = now - WatchSeen.lastCheck();
            if (!force && elapsed < cfg.intervalMin * 60000L) {
                return;
            }
            if (!sRunning.compareAndSet(false, true)) {
                return; // 上一轮还没跑完
            }
            WatchSeen.touchCheck();
            final Context appCtx = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            POOL.execute(() -> {
                try {
                    run(cfg, appCtx, reason);
                } catch (Throwable t) {
                    log(Log.WARN, "检查异常: " + t);
                    sLastResult = "检查异常：" + t;
                } finally {
                    sRunning.set(false);
                }
            });
        } catch (Throwable t) {
            sRunning.set(false);
            log(Log.WARN, "触发检查失败: " + t);
        }
    }

    // ------------------------------------------------------------ 主动检查

    private static void run(WatchConfig cfg, Context ctx, String reason) {
        log(Log.INFO, "开始检查（" + reason + "）：关注 " + cfg.users.size()
                + " 人，关键词 " + cfg.keywords.size() + " 个");
        List<WatchItem> found = new ArrayList<>();
        if (!HttpBridge.ready()) {
            log(Log.WARN, "尚未捕获宿主网络栈，跳过主动拉取（可先在小黑盒里刷新一次信息流以完成捕获）");
        } else {
            for (String raw : cfg.users) {
                String uid = WatchConfig.parseUserId(raw);
                if (uid == null) {
                    log(Log.WARN, "无法解析 userid：" + raw);
                    continue;
                }
                List<WatchItem> items = WatchFetcher.fetchUserPosts(uid, WatchFetcher.FETCH_LIMIT);
                // 首轮基线：第一次看这个关注对象时只登记、不推送，避免把历史帖一次性全推出去
                if (!WatchSeen.isBaselined(uid)) {
                    int recorded = 0;
                    for (WatchItem it : items) {
                        if (WatchSeen.markNew(it.linkId)) {
                            recorded++;
                        }
                    }
                    WatchSeen.markBaselined(uid);
                    log(Log.INFO, "首次检查「" + displayName(raw) + "」：登记 " + recorded
                            + " 条历史帖作为基线，不推送（下次起只推新增）");
                    sleep(800);
                    continue;
                }
                for (WatchItem it : items) {
                    if (uid.equals(it.authorId) || it.authorId == null || it.authorId.isEmpty()) {
                        found.add(new WatchItem(it.linkId, it.title, it.desc, it.authorId,
                                it.authorName, it.createAt, "user", displayName(raw)));
                    }
                }
                // 拉取间隔，避免触发风控
                sleep(800);
            }
        }
        deliver(cfg, ctx, WatchFetcher.dedupe(found), "主动拉取");
    }

    // ------------------------------------------------------------ 被动命中（信息流原始 JSON）

    /** 由信息流反序列化 hook 调用；elem 是 Gson JsonElement */
    public static void onFeedJson(Object elem) {
        MainModule module = sModule;
        if (module == null || elem == null) {
            return;
        }
        try {
            WatchConfig cfg = WatchConfig.load(module);
            if (!cfg.enabled || !cfg.hasTargets()) {
                return;
            }
            Object obj = elem.getClass().getMethod("getAsJsonObject").invoke(elem);
            if (obj == null) {
                return;
            }
            String linkId = jsonStr(obj, "link_id", "linkid");
            if (linkId == null || linkId.isEmpty()) {
                Object link = jsonGet(obj, "link");
                if (link != null) {
                    linkId = jsonStr(link, "link_id", "linkid");
                }
            }
            if (linkId == null || linkId.isEmpty() || WatchSeen.contains(linkId)) {
                return;
            }
            String title = jsonStr(obj, "title");
            String desc = jsonStr(obj, "description", "desc");
            String authorId = jsonStr(obj, "userid", "user_id");
            String authorName = jsonStr(obj, "username", "nickname");
            Object user = jsonGet(obj, "user");
            if (user != null) {
                if (authorId == null) {
                    authorId = jsonStr(user, "userid", "user_id");
                }
                if (authorName == null) {
                    authorName = jsonStr(user, "username", "nickname");
                }
            }
            String hitUser = matchUser(cfg, authorId);
            boolean kw = matchKeywords(cfg, title, desc);
            if (hitUser == null && !kw) {
                return;
            }
            if (!WatchSeen.markNew(linkId)) {
                return;
            }
            WatchItem item = new WatchItem(linkId, nz(title), nz(desc), nz(authorId), nz(authorName),
                    System.currentTimeMillis() / 1000L,
                    hitUser != null ? "user" : "keyword", hitUser);
            log(Log.INFO, "信息流命中（" + (hitUser != null ? "关注" : "关键词") + "）：" + item.displayTitle());
            output(cfg, item, true);
        } catch (Throwable t) {
            log(Log.WARN, "信息流命中检查异常: " + t);
        }
    }

    // ------------------------------------------------------------ 过滤与投递

    /** 单轮最多提醒多少条（防止一次刷屏） */
    public static final int MAX_PUSH_PER_CHECK = 5;

    private static void deliver(WatchConfig cfg, Context ctx, List<WatchItem> items, String from) {
        long now = System.currentTimeMillis() / 1000L;
        long window = cfg.windowSeconds();
        int tooOld = 0;
        int noTime = 0;
        List<WatchItem> fresh = new ArrayList<>();
        for (WatchItem it : items) {
            // 时间未知的直接跳过：宁可漏推，也不把可能很旧的帖子推出去
            if (it.createAt <= 0) {
                noTime++;
                continue;
            }
            if (now - it.createAt > window) {
                tooOld++;
                continue;
            }
            if (WatchSeen.contains(it.linkId)) {
                continue;
            }
            fresh.add(it);
        }
        // 新的在前，超出单轮上限的留到下一轮（不标记为已读）
        fresh.sort((a, b) -> Long.compare(b.createAt, a.createAt));
        int over = Math.max(0, fresh.size() - MAX_PUSH_PER_CHECK);
        if (fresh.size() > MAX_PUSH_PER_CHECK) {
            fresh = new ArrayList<>(fresh.subList(0, MAX_PUSH_PER_CHECK));
        }
        for (WatchItem it : fresh) {
            WatchSeen.markNew(it.linkId);
        }
        if (cfg.banner && !fresh.isEmpty()) {
            Activity a = sActivity.get();
            if (a != null) {
                if (fresh.size() > 1) {
                    WatchItem first = fresh.get(0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < fresh.size() && i < 3; i++) {
                        if (i > 0) {
                            sb.append('\n');
                        }
                        sb.append("· ").append(fresh.get(i).displayTitle());
                    }
                    if (fresh.size() > 3) {
                        sb.append("\n· 还有 ").append(fresh.size() - 3).append(" 条…");
                    }
                    WatchOutput.bannerSummary(a, "🔔 " + fresh.size() + " 条新动态", sb.toString(),
                            () -> WatchOutput.openPost(a, first));
                } else {
                    WatchOutput.bannerOrToast(a, fresh.get(0));
                }
            }
        }
        for (WatchItem it : fresh) {
            if (cfg.notify) {
                try {
                    WatchOutput.notifyPost(pickContext(), it);
                } catch (Throwable t) {
                    log(Log.WARN, "通知失败: " + t);
                }
            }
            if (cfg.pushEnabled) {
                int n = WatchOutput.pushAll(cfg, it);
                log(Log.INFO, "第三方推送完成，成功 " + n + " 个渠道：" + it.displayTitle());
            }
        }
        sLastResult = from + "：候选 " + items.size() + " 条（超窗 " + tooOld + "、无时间 " + noTime
                + "、未提醒 " + over + "），提醒 " + fresh.size() + " 条";
        log(Log.INFO, sLastResult);
    }

    private static void output(WatchConfig cfg, WatchItem item, boolean fromFeed) {
        if (cfg.notify) {
            try {
                WatchOutput.notifyPost(pickContext(), item);
            } catch (Throwable t) {
                log(Log.WARN, "通知失败: " + t);
            }
        }
        // 横幅对所有来源都生效（主动拉取 / 信息流命中）
        if (cfg.banner) {
            Activity a = sActivity.get();
            if (a != null) {
                WatchOutput.bannerOrToast(a, item);
            }
        }
        if (cfg.pushEnabled) {
            int n = WatchOutput.pushAll(cfg, item);
            log(Log.INFO, "第三方推送完成，成功 " + n + " 个渠道");
        }
    }

    private static Context pickContext() {
        Activity a = sActivity.get();
        if (a != null) {
            return a;
        }
        MainModule m = sModule;
        return m != null ? com.better.heybox.App.resolveAppContext() : null;
    }

    // ------------------------------------------------------------ 匹配

    private static String matchUser(WatchConfig cfg, String authorId) {
        if (authorId == null || authorId.isEmpty()) {
            return null;
        }
        for (String raw : cfg.users) {
            String uid = WatchConfig.parseUserId(raw);
            if (uid != null && uid.equals(authorId)) {
                return displayName(raw);
            }
        }
        return null;
    }

    /** 与发帖过滤同一套写法：一行一个，regex: 前缀为正则，忽略大小写 */
    public static boolean matchKeywords(WatchConfig cfg, String title, String desc) {
        if (cfg.keywords.isEmpty()) {
            return false;
        }
        String text = nz(title) + "\n" + nz(desc);
        for (String kw : cfg.keywords) {
            try {
                if (kw.startsWith("regex:")) {
                    String p = kw.substring(6).trim();
                    if (!p.isEmpty() && Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                        return true;
                    }
                } else if (text.toLowerCase().contains(kw.toLowerCase())) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String displayName(String raw) {
        return raw == null ? "" : raw.trim();
    }

    // ------------------------------------------------------------ JSON 反射小工具

    private static Object jsonGet(Object jsonObj, String key) {
        try {
            return jsonObj.getClass().getMethod("get", String.class).invoke(jsonObj, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String jsonStr(Object jsonObj, String... keys) {
        for (String k : keys) {
            try {
                Object v = jsonGet(jsonObj, k);
                if (v == null) {
                    continue;
                }
                Object s = v.getClass().getMethod("getAsString").invoke(v);
                if (s != null && !s.toString().isEmpty() && !"null".equals(s.toString())) {
                    return s.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(int level, String msg) {
        MainModule m = sModule;
        if (m != null) {
            m.logd(level, m.TAG, "[动态推送] " + msg);
        }
    }

    private static final Set<String> EMPTY = new HashSet<>();
}
