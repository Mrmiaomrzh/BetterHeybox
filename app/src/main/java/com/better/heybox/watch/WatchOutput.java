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
 * 输出层：本地通知栏 + 应用内横幅 + 第三方推送（钉钉 / WxPusher / OneBot / 自定义 webhook）。
 *
 * <p>通知渠道自建（与 VideoDownloadManager 同一套做法），挂在宿主包名下，
 * 因此复用小黑盒已有的通知权限，无需额外申请。
 */
public final class WatchOutput {

    public static final String CHANNEL_ID = "betterheybox_watch";
    private static final String CHANNEL_NAME = "动态推送";

    private static volatile MainModule sModule;

    private WatchOutput() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    // ------------------------------------------------------------ 通知栏

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

    /** @return 是否成功发出（测试面板据此显示结果） */
    public static boolean notifyPost(Context ctx, WatchItem item) {
        try {
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return false;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl()));
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

    // ------------------------------------------------------------ 应用内横幅

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

    public static void openPost(Context ctx, WatchItem item) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            log(Log.WARN, "打开帖子失败: " + t);
        }
    }

    // ------------------------------------------------------------ 第三方推送

    /**
     * 测试用应用内横幅：点击只提示回调是否正常，不跳转（测试条目的链接是占位值）。
     *
     * @return 是否成功弹出
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

    /** 依次发送到所有已配置渠道；返回成功条数 */
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

    /** 钉钉机器人：access_token 或完整 webhook 均可 */
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

    /** WxPusher：格式 appToken|topicId 或 appToken|uid:UID */
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

    /** OneBot v11（AstrBot / NapCat 等）：格式 baseUrl|group_id 或 baseUrl|private:QQ */
    private static boolean sendOneBot(String cfg, WatchItem item) {
        try {
            String[] parts = cfg.split("\\|");
            String base = parts[0].trim().replaceAll("/+$", "");
            String target = parts.length > 1 ? parts[1].trim() : "";
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
            o.put("auto_escape", false);
            return postJson(base + action, o.toString()) != null;
        } catch (Throwable t) {
            log(Log.WARN, "OneBot 推送失败: " + t);
            return false;
        }
    }

    /** 自定义 webhook：支持 {title} {author} {link} {desc} 占位符；URL 可直接带 query */
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
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
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

    /** 设置面板"测试推送"用 */
    public static Map<String, String> testPayload() {
        Map<String, String> m = new HashMap<>();
        m.put("title", "测试消息");
        m.put("text", "BetterHeybox 动态推送测试");
        return m;
    }
}
