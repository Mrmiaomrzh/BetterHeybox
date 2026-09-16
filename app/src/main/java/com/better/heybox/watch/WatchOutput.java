package com.better.heybox.watch;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.better.heybox.MainModule;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Output layer: notification, in-app banner, third-party push (DingTalk / WxPusher /
 * OneBot / custom webhook).
 *
 * The channel is self-made and registered under the host package, so the host's
 * existing notification permission is reused.
 */
public final class WatchOutput {

    public static final String CHANNEL_ID = "betterheybox_watch";
    private static final String CHANNEL_NAME = "动态推送";

    /** Host router: dispatches a web/share url to post / web / native pages */
    private static final String HOST_ROUTER = "com.max.xiaoheihe.RouterActivity";
    /** Host post page used when the router is missing (needs link_id) */
    private static final String HOST_POST_PAGE =
            "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity";

    private static volatile MainModule sModule;

    private WatchOutput() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    // ---- notification ----

    private static void ensureChannel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("关注的作者发布新动态 / 关键词命中提醒");
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            log(Log.WARN, "创建通知渠道失败: " + t);
        }
    }

    /** @return true when the notification was posted */
    public static boolean notifyPost(Context ctx, WatchItem item) {
        try {
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return false;
            }
            // stay in-app: target the host's own component. An implicit ACTION_VIEW is
            // resolved by the system and lands in a browser (no login, no in-app comments)
            Intent intent = buildPostIntent(ctx, item);
            if (intent == null) {
                // host names changed and both components are missing: fall back to the browser
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl()));
            }
            // posted from outside the process: NEW_TASK required
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int code = item.linkId.hashCode();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, code, intent, flags);
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(item.displayTitle())
                    .setContentText(item.displayText().replace('\n', ' '))
                    .setStyle(new Notification.BigTextStyle().bigText(item.displayText()))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(code, b.build());
            log(Log.INFO, "已发通知：" + item.displayTitle());
            return true;
        } catch (Throwable t) {
            log(Log.WARN, "发通知失败: " + t);
            return false;
        }
    }

    // ---- in-app banner ----

    public static void bannerOrToast(final Activity activity, final WatchItem item) {
        if (activity == null) {
            return;
        }
        try {
            WatchBanner.show(activity, item, () -> openPost(activity, item));
        } catch (Throwable t) {
            try {
                Toast.makeText(activity, "🔔 " + item.displayTitle(), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Summary banner when a batch has several posts */
    public static void bannerSummary(Activity activity, String title, String sub, Runnable onClick) {
        if (activity == null) {
            return;
        }
        try {
            WatchBanner.showSummary(activity, title, sub, onClick);
        } catch (Throwable t) {
            log(Log.WARN, "汇总横幅失败: " + t);
        }
    }

    /** Open a post in-app (banner / summary banner / debug push) */
    public static void openPost(Context ctx, WatchItem item) {
        if (ctx == null || item == null) {
            return;
        }
        Intent i = buildPostIntent(ctx, item);
        if (i != null) {
            try {
                startActivity(ctx, i);
                return;
            } catch (Throwable t) {
                log(Log.WARN, "应用内打开帖子失败，回退浏览器: " + t);
            }
        }
        try {
            startActivity(ctx, new Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl())));
        } catch (Throwable t) {
            log(Log.WARN, "打开帖子失败: " + t);
        }
    }

    /** Non-Activity context (app context, background thread): NEW_TASK required */
    private static void startActivity(Context ctx, Intent intent) {
        if (!(ctx instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(intent);
    }

    /**
     * Post intent: the host router with the web/share url first, then the post page with
     * link_id. Explicit components keep the tap in-app, while an implicit ACTION_VIEW is
     * resolved by the system and can land in a browser.
     *
     * @return null when neither component exists (caller falls back to the browser)
     */
    private static Intent buildPostIntent(Context ctx, WatchItem item) {
        if (ctx == null || item == null || item.linkId == null || item.linkId.isEmpty()) {
            return null;
        }
        ClassLoader cl = ctx.getClassLoader();
        Class<?> router = loadHostClass(cl, HOST_ROUTER);
        if (router != null) {
            return new Intent(ctx, router).setData(Uri.parse(item.webUrl()));
        }
        Class<?> postPage = loadHostClass(cl, HOST_POST_PAGE);
        if (postPage != null) {
            return new Intent(ctx, postPage).putExtra("link_id", item.linkId);
        }
        log(Log.WARN, "宿主路由页与帖子详情页均未找到，本次改走浏览器: " + item.linkId);
        return null;
    }

    /** Null instead of throwing when the host class is missing */
    private static Class<?> loadHostClass(ClassLoader cl, String name) {
        if (cl != null) {
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {
            }
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---- third-party push ----

    /**
     * Debug banner: tapping only reports whether the callback fires, no navigation.
     *
     * @return true when the banner was shown
     */
    public static boolean testBanner(final Activity activity, final WatchItem item) {
        if (activity == null || item == null) {
            return false;
        }
        try {
            WatchBanner.show(activity, item, () -> {
                try {
                    Toast.makeText(activity, "横幅点击回调正常", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
            return true;
        } catch (Throwable t) {
            log(Log.WARN, "测试横幅失败: " + t);
            return false;
        }
    }

    /** Send to every configured channel; returns the success count */
    public static int pushAll(WatchConfig cfg, WatchItem item) {
        if (!cfg.pushEnabled) {
            return 0;
        }
        int ok = 0;
        String title = "【小黑盒】" + item.displayTitle();
        String body = item.displayText() + "\n" + item.webUrl();
        if (!cfg.dingtalk.isEmpty() && sendDingtalk(cfg.dingtalk, title, body)) {
            ok++;
        }
        if (!cfg.wxpusher.isEmpty() && sendWxPusher(cfg.wxpusher, title, body)) {
            ok++;
        }
        if (!cfg.onebot.isEmpty() && sendOneBot(cfg.onebot, item)) {
            ok++;
        }
        if (!cfg.custom.isEmpty() && sendCustom(cfg.custom, item)) {
            ok++;
        }
        return ok;
    }

    /** DingTalk bot: access_token or a full webhook url */
    private static boolean sendDingtalk(String cfg, String title, String text) {
        String url = cfg.startsWith("http") ? cfg : ("https://oapi.dingtalk.com/robot/send?access_token=" + cfg);
        try {
            JSONObject o = new JSONObject();
            o.put("msgtype", "markdown");
            JSONObject md = new JSONObject();
            md.put("title", title);
            md.put("text", "### " + title + "\n\n" + text);
            o.put("markdown", md);
            return postJson(url, o.toString()) != null;
        } catch (Throwable t) {
            log(Log.WARN, "钉钉推送失败: " + t);
            return false;
        }
    }

    /** WxPusher: appToken|topicId or appToken|uid:UID */
    private static boolean sendWxPusher(String cfg, String title, String text) {
        try {
            String[] parts = cfg.split("\\|");
            String appToken = parts[0].trim();
            JSONObject o = new JSONObject();
            o.put("appToken", appToken);
            o.put("content", text);
            o.put("summary", title);
            o.put("contentType", 1);
            if (parts.length > 1) {
                String second = parts[1].trim();
                if (second.startsWith("uid:") || second.startsWith("UID:")) {
                    org.json.JSONArray uids = new org.json.JSONArray();
                    uids.put(second.substring(4).trim());
                    o.put("uids", uids);
                } else {
                    org.json.JSONArray topics = new org.json.JSONArray();
                    topics.put(Integer.parseInt(second));
                    o.put("topicIds", topics);
                }
            }
            return postJson("https://wxpusher.zjiecode.com/api/send/message", o.toString()) != null;
        } catch (Throwable t) {
            log(Log.WARN, "WxPusher 推送失败: " + t);
            return false;
        }
    }

    /**
     * AstrBot / OneBot v11 (AstrBot's aiocqhttp adapter, NapCat, Lagrange, ...).
     *
     * <p>Config, pipe separated:
     * <pre>
     *   http://host:6199|group
     *   http://host:6199|group|access_token
     *   http://host:6199|private:QQ|access_token
     * </pre>
     * aiocqhttp listens on 6199 by default; the token is sent as Authorization: Bearer.
     */
    private static boolean sendOneBot(String cfg, WatchItem item) {
        try {
            String[] parts = cfg.split("\\|");
            String base = parts[0].trim().replaceAll("/+$", "");
            String target = parts.length > 1 ? parts[1].trim() : "";
            String token = parts.length > 2 ? parts[2].trim() : "";
            JSONObject o = new JSONObject();
            String action;
            if (target.startsWith("private:")) {
                action = "/send_private_msg";
                o.put("user_id", Long.parseLong(target.substring(8).trim()));
            } else {
                action = "/send_group_msg";
                o.put("group_id", Long.parseLong(target.isEmpty() ? "0" : target));
            }
            o.put("message", "【小黑盒】" + item.displayTitle() + "\n" + item.webUrl());
            // keep [CQ:...] in the title from being parsed as CQ codes
            o.put("auto_escape", true);
            Map<String, String> headers = new HashMap<>();
            if (!token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
            }
            return postJson(base + action, o.toString(), headers) != null;
        } catch (Throwable t) {
            log(Log.WARN, "AstrBot / OneBot 推送失败: " + t);
            return false;
        }
    }

    /** Custom webhook: {title} {author} {link} {desc} placeholders; query allowed */
    private static boolean sendCustom(String cfg, WatchItem item) {
        try {
            String url = cfg
                    .replace("{title}", enc(item.displayTitle()))
                    .replace("{author}", enc(item.authorName))
                    .replace("{link}", enc(item.webUrl()))
                    .replace("{desc}", enc(item.desc))
                    .replace("{id}", enc(item.linkId));
            if (url.startsWith("http")) {
                return postJson(url, buildDefaultPayload(item)) != null;
            }
            return false;
        } catch (Throwable t) {
            log(Log.WARN, "自定义推送失败: " + t);
            return false;
        }
    }

    private static String buildDefaultPayload(WatchItem item) throws Exception {
        JSONObject o = new JSONObject();
        o.put("title", item.displayTitle());
        o.put("author", item.authorName);
        o.put("link", item.webUrl());
        o.put("desc", item.desc);
        o.put("link_id", item.linkId);
        o.put("hit", item.hit);
        return o.toString();
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String postJson(String url, String json) {
        return postJson(url, json, null);
    }

    private static String postJson(String url, String json, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                return "ok";
            }
            log(Log.WARN, "推送 HTTP " + code + " " + url);
            return null;
        } catch (Throwable t) {
            log(Log.WARN, "推送请求异常 " + t);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static void log(int level, String msg) {
        MainModule m = sModule;
        if (m != null) {
            m.logd(level, m.TAG, "[动态推送] " + msg);
        }
    }

    /** Used by the settings panel "test push" */
    public static Map<String, String> testPayload() {
        Map<String, String> m = new HashMap<>();
        m.put("title", "测试消息");
        m.put("text", "BetterHeybox 动态推送测试");
        return m;
    }
}
