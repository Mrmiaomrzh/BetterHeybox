package com.better.heybox.watch;

import android.util.Log;

import com.better.heybox.MainModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

public final class HttpBridge {

    public static final String[] CLIENT_HOLDERS = {
            "okhttp3.internal.connection.RealCall",
            "okhttp3.RealCall",
    };

    private static volatile Object sClient;      // 宿主的 HTTP 客户端实例
    private static volatile Method sNewCall;     // Client#newCall(Request)
    private static volatile Method sExecute;     // Call#execute()
    private static volatile Method sCode;        // Response#code()
    private static volatile Method sBody;        // Response#body()
    private static volatile Method sString;      // ResponseBody#string()
    private static volatile Method sNewBuilder;  // Request#newBuilder()
    private static volatile Method sUrl;         // Builder#url(String)
    private static volatile Method sBuild;       // Builder#build()
    private static volatile Method sMethod;      // Builder#method(String, RequestBody)
    private static volatile Object sTemplate;    // 捕获到的原始 Request（用于派生 Builder）

    private static volatile String sDescribe = "未捕获";
    private static volatile MainModule sModule;
    private static volatile boolean sDiagLogged;

    private HttpBridge() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    public static boolean ready() {
        return sClient != null && sNewCall != null && sExecute != null;
    }

    private static void rememberTopic(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        boolean changed = false;
        synchronized (sRecentTopics) {
            if (!sRecentTopics.contains(id)) {
                sRecentTopics.add(id);
                while (sRecentTopics.size() > RECENT_TOPIC_LIMIT) {
                    java.util.Iterator<String> it = sRecentTopics.iterator();
                    it.next();
                    it.remove();
                }
                changed = true;
            }
        }
        if (changed) {
            try {
                StringBuilder sb = new StringBuilder();
                synchronized (sRecentTopics) {
                    for (String t : sRecentTopics) {
                        sb.append(t).append(',');
                    }
                }
                final String joined = sb.toString();
                prefsExecutor().execute(() -> {
                    try {
                        com.better.heybox.HeyboxPrefs.init(com.better.heybox.App.resolveAppContext());
                        com.better.heybox.HeyboxPrefs.setString(
                                com.better.heybox.App.KEY_WATCH_RECENT_TOPICS, joined);
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static volatile java.util.concurrent.Executor sPrefsExecutor;

    private static java.util.concurrent.Executor prefsExecutor() {
        java.util.concurrent.Executor e = sPrefsExecutor;
        if (e != null) {
            return e;
        }
        synchronized (HttpBridge.class) {
            if (sPrefsExecutor == null) {
                sPrefsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "betterheybox-prefs");
                    t.setDaemon(true);
                    return t;
                });
            }
            return sPrefsExecutor;
        }
    }

    public static java.util.List<String> recentTopicIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (sRecentTopics) {
            String[] arr = sRecentTopics.toArray(new String[0]);
            for (int i = arr.length - 1; i >= 0; i--) {
                out.add(arr[i]);
            }
        }
        return out;
    }

    public static String hostUserId() {
        return sHostUserId;
    }

    public static String describe() {
        return sDescribe;
    }

    public static boolean captureIfClient(Object client, Object request, ClassLoader cl) {
        logHostRequest(request);
        if (client == null) {
            return false;
        }
        if (ready()) {
            return true;
        }
        synchronized (HttpBridge.class) {
            if (ready()) {
                return true;
            }
            try {
                if (request == null) {
                    return false;
                }
                Class<?> reqCls = request.getClass();
                if (!resolveRequestBuilder(reqCls) || !resolveCallChain(client.getClass(), reqCls)) {
                    dumpDiagnostics(client, request);
                    return false;
                }
                sTemplate = request;
                sClient = client;
                sDescribe = "client=" + client.getClass().getName()
                        + "#" + sNewCall.getName()
                        + " request=" + reqCls.getName() + "#" + sNewBuilder.getName()
                        + " builder=" + sBuild.getDeclaringClass().getName() + "#" + sBuild.getName()
                        + " call=" + sExecute.getName()
                        + " code=" + sCode.getName()
                        + " body=" + sBody.getName() + "/" + sString.getName();
                log(Log.INFO, "已捕获宿主 HTTP 客户端：" + sDescribe);
                WatchEngine.onNetworkCaptured();
                return true;
            } catch (Throwable t) {
                log(Log.WARN, "识别客户端失败：" + t);
                return false;
            }
        }
    }

    private static volatile long sLogFlagAt;
    private static volatile boolean sLogFlag;
    private static volatile String sHostUserId;
    private static final java.util.LinkedHashSet<String> sRecentTopics = new java.util.LinkedHashSet<>();
    private static final int RECENT_TOPIC_LIMIT = 20;

    private static final java.util.regex.Pattern URL_IN_TOSTRING =
            java.util.regex.Pattern.compile("url=([^,\\s]+)");

    private static final ThreadLocal<Boolean> sOwnRequest = new ThreadLocal<>();
    private static final int LOG_VALUE_MAX = 256;
    private static final int LOG_URL_SAMPLE_MAX = 1600;
    private static final long LOG_PARSE_SLOW_MS = 50L;
    private static void logHostRequest(Object request) {
        try {
            if (request == null) {
                return;
            }
            if (Boolean.TRUE.equals(sOwnRequest.get())) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - sLogFlagAt > 30000L) {
                sLogFlagAt = now;
                MainModule m = sModule;
                sLogFlag = m != null && m.isEnabled(com.better.heybox.App.KEY_LOG, false);
            }
            if (!sLogFlag) {
                return;
            }
            long t0 = android.os.SystemClock.elapsedRealtime();
            String raw = String.valueOf(request);
            java.util.regex.Matcher mt = URL_IN_TOSTRING.matcher(raw);
            if (!mt.find()) {
                return;
            }
            int from = mt.start(1);
            int to = mt.end(1);
            if (sHostUserId == null) {
                String uid = firstDigitsParam(raw, from, to, "user_id=");
                if (uid != null && uid.length() < 5) {
                    uid = null;
                }
                if (uid == null && contains(raw, from, to, "ws.xiaoheihe.cn")) {
                    uid = firstDigitsParam(raw, from, to, "userid=");
                    if (uid != null && uid.length() < 5) {
                        uid = null;
                    }
                }
                if (uid != null) {
                    sHostUserId = uid;
                    log(Log.INFO, "宿主登录 userid = " + sHostUserId);
                }
            }
            String topicId = firstDigitsParam(raw, from, to, "topic_id=");
            if (contains(raw, from, to, "topic") || contains(raw, from, to, "hashtag")
                    || contains(raw, from, to, "/search")) {
                long cost = android.os.SystemClock.elapsedRealtime() - t0;
                if (cost > LOG_PARSE_SLOW_MS) {
                    com.better.heybox.ModuleStats.slow("宿主请求解析", cost);
                }
                log(Log.INFO, "宿主请求 " + compactUrl(raw, from, to)
                        + " (len=" + (to - from) + ", " + cost + "ms, "
                        + Thread.currentThread().getName() + ")");
                noteTopicFeedRequest(raw, from, to, topicId);
            }
            if (topicId != null) {
                rememberTopic(topicId);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean contains(String raw, int from, int to, String needle) {
        int at = raw.indexOf(needle, from);
        return at >= 0 && at < to;
    }

    private static String firstDigitsParam(String raw, int from, int to, String key) {
        int at = raw.indexOf(key, from);
        if (at < 0 || at >= to) {
            return null;
        }
        int begin = at + key.length();
        int end = begin;
        while (end < to && end - begin < 20) {
            char c = raw.charAt(end);
            if (c < '0' || c > '9') {
                break;
            }
            end++;
        }
        return end > begin ? raw.substring(begin, end) : null;
    }

    private static int intParam(String raw, int from, int to, String key, int def) {
        String value = firstDigitsParam(raw, from, to, key);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String compactUrl(String raw, int from, int to) {
        StringBuilder sb = new StringBuilder(Math.min(to - from, 640) + 32);
        int i = from;
        while (i < to && sb.length() < LOG_URL_SAMPLE_MAX) {
            int amp = raw.indexOf('&', i);
            if (amp < 0 || amp >= to) {
                amp = to;
            }
            int eq = raw.indexOf('=', i);
            if (eq >= 0 && eq < amp) {
                int valueLen = amp - eq - 1;
                if (valueLen > LOG_VALUE_MAX) {
                    sb.append(raw, i, eq + 1).append('<').append(valueLen).append("字符>");
                } else {
                    sb.append(raw, i, amp);
                }
            } else {
                sb.append(raw, i, amp);
            }
            if (amp < to) {
                sb.append('&');
            }
            i = amp + 1;
        }
        if (i < to) {
            sb.append("…(剩余").append(to - i).append("字符)");
        }
        return sb.toString();
    }

    private static final int TOPIC_FEED_TRACK_MAX = 8;
    private static final long TOPIC_FEED_WINDOW_MS = 15_000L;
    private static final int TOPIC_FEED_BURST = 5;
    private static final long TOPIC_FEED_WARN_INTERVAL_MS = 60_000L;
    private static final class FeedTrace {
        final java.util.ArrayDeque<Long> recent = new java.util.ArrayDeque<>();
        int lastOffset = Integer.MIN_VALUE;
    }

    private static final java.util.LinkedHashMap<String, FeedTrace> sTopicFeed =
            new java.util.LinkedHashMap<>();
    private static volatile long sTopicFeedWarnAt;
    private static void noteTopicFeedRequest(String raw, int from, int to, String topicId) {
        if (topicId == null || !contains(raw, from, to, "bbs/app/topic/feeds")) {
            return;
        }
        int offset = intParam(raw, from, to, "offset=", -1);
        com.better.heybox.ModuleStats.topicFeedRequests.incrementAndGet();
        long now = System.currentTimeMillis();
        int inWindow;
        synchronized (sTopicFeed) {
            FeedTrace trace = sTopicFeed.get(topicId);
            if (trace == null) {
                trace = new FeedTrace();
                sTopicFeed.put(topicId, trace);
                while (sTopicFeed.size() > TOPIC_FEED_TRACK_MAX) {
                    java.util.Iterator<String> it = sTopicFeed.keySet().iterator();
                    it.next();
                    it.remove();
                }
            }
            if (offset > trace.lastOffset) {
                trace.recent.addLast(now);
            }
            trace.lastOffset = offset;
            while (!trace.recent.isEmpty() && now - trace.recent.peekFirst() > TOPIC_FEED_WINDOW_MS) {
                trace.recent.pollFirst();
            }
            inWindow = trace.recent.size();
        }
        if (inWindow < TOPIC_FEED_BURST) {
            return;
        }
        if (now - sTopicFeedWarnAt < TOPIC_FEED_WARN_INTERVAL_MS) {
            return;
        }
        sTopicFeedWarnAt = now;
        com.better.heybox.ModuleStats.topicFeedStallRuns.incrementAndGet();
        log(Log.WARN, "[#41] 话题信息流疑似分页空转：topic_id=" + topicId
                + " " + (TOPIC_FEED_WINDOW_MS / 1000) + "s 内翻页请求=" + inWindow + " 次"
                + "（最新 offset=" + offset
                + "，数据层条目=" + com.better.heybox.ModuleStats.bbsListItemsSeen.get()
                + " 删除=" + com.better.heybox.ModuleStats.bbsListItemsDropped.get()
                + " 视图隐藏=" + com.better.heybox.ModuleStats.bbsListItemsHidden.get() + "）");
    }

    private static boolean resolveRequestBuilder(Class<?> reqCls) {
        for (Method nb : reqCls.getMethods()) {
            if (nb.getParameterCount() != 0) {
                continue;
            }
            Class<?> t = nb.getReturnType();
            if (t == reqCls || t.isPrimitive() || t == void.class || t == String.class) {
                continue;
            }
            Method urlSetter = null;
            Method build = null;
            for (Method m : t.getMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == String.class && m.getReturnType() == t && urlSetter == null) {
                    urlSetter = m;
                }
                if (ps.length == 0 && m.getReturnType() == reqCls && build == null) {
                    build = m;
                }
            }
            if (urlSetter == null || build == null) {
                continue;
            }
            Method methodSetter = null;
            for (Method m : t.getMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 2 && ps[0] == String.class && ps[1] != String.class
                        && !ps[1].isPrimitive() && m.getReturnType() == t) {
                    methodSetter = m;
                    break;
                }
            }
            sNewBuilder = nb;
            sUrl = urlSetter;
            sBuild = build;
            sMethod = methodSetter;
            return true;
        }
        return false;
    }

    private static boolean resolveCallChain(Class<?> clientCls, Class<?> reqCls) {
        for (Method nc : clientCls.getMethods()) {
            Class<?>[] ps = nc.getParameterTypes();
            if (ps.length != 1 || !ps[0].isAssignableFrom(reqCls)) {
                continue;
            }
            Class<?> callCls = nc.getReturnType();
            if (callCls.isPrimitive() || callCls == void.class) {
                continue;
            }
            for (Method ex : callCls.getMethods()) {
                if (ex.getParameterCount() != 0) {
                    continue;
                }
                Class<?> respCls = ex.getReturnType();
                if (respCls.isPrimitive() || respCls == void.class || respCls == String.class) {
                    continue;
                }
                Method code = null;
                Method body = null;
                Method string = null;
                for (Method rm : respCls.getMethods()) {
                    if (rm.getParameterCount() != 0 || isObjectMethod(rm)) {
                        continue;
                    }
                    if (rm.getReturnType() == int.class) {
                        if (code == null) {
                            code = rm;
                        }
                        continue;
                    }
                    if (body != null || rm.getReturnType().isPrimitive() || rm.getReturnType() == String.class) {
                        continue;
                    }
                    Method s = findStringMethod(rm.getReturnType());
                    if (s != null) {
                        body = rm;
                        string = s;
                    }
                }
                if (code == null || body == null || string == null) {
                    continue;
                }
                sNewCall = nc;
                sExecute = ex;
                sCode = code;
                sBody = body;
                sString = string;
                return true;
            }
        }
        return false;
    }

    private static boolean isObjectMethod(Method m) {
        Class<?> d = m.getDeclaringClass();
        if (d == Object.class) {
            return true;
        }
        String n = m.getName();
        return "toString".equals(n) || "hashCode".equals(n) || "getClass".equals(n);
    }

    private static Method findStringMethod(Class<?> c) {
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == String.class && !isObjectMethod(m)) {
                return m;
            }
        }
        return null;
    }

    private static void dumpDiagnostics(Object client, Object request) {
        if (sDiagLogged) {
            return;
        }
        sDiagLogged = true;
        try {
            log(Log.WARN, "结构识别失败 client=" + client.getClass().getName()
                    + " request=" + (request == null ? "null" : request.getClass().getName()));
            int n = 0;
            for (Method m : client.getClass().getMethods()) {
                if (m.getParameterCount() > 1) {
                    continue;
                }
                StringBuilder sb = new StringBuilder("  client#");
                sb.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    sb.append(i > 0 ? ", " : "").append(ps[i].getSimpleName());
                }
                sb.append(") -> ").append(m.getReturnType().getSimpleName());
                log(Log.WARN, sb.toString());
                if (++n >= 15) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String get(String url, Map<String, String> headers) {
        return request("GET", url, headers, null);
    }

    public static String postJson(String url, Map<String, String> headers, String json) {
        return request("POST", url, headers, json);
    }

    private static String request(String method, String url, Map<String, String> headers, String body) {
        if (!ready()) {
            log(Log.WARN, "HTTP 客户端尚未捕获，跳过请求 " + shortUrl(url));
            return null;
        }
        Object client = sClient;
        Object template = sTemplate;
        Object req;
        try {
            Object builder = sNewBuilder.invoke(template);
            if (sMethod != null) {
                try {
                    sMethod.invoke(builder, "GET", null);
                } catch (Throwable ignored) {
                }
            }
            sUrl.invoke(builder, url);
            if (headers != null) {
                Method addHeader = null;
                for (Method m : builder.getClass().getMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 2 && ps[0] == String.class && ps[1] == String.class
                            && m.getReturnType() == builder.getClass()) {
                        addHeader = m;
                        break;
                    }
                }
                if (addHeader != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        addHeader.invoke(builder, e.getKey(), e.getValue());
                    }
                }
            }
            req = sBuild.invoke(builder);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            log(Log.WARN, "构造请求失败: " + t + " / cause=" + cause);
            return null;
        }
        if (!"GET".equals(method) && body != null) {
            log(Log.WARN, "暂不支持 " + method + " 请求，已跳过 " + shortUrl(url));
            return null;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        Boolean ownPrev = sOwnRequest.get();
        sOwnRequest.set(Boolean.TRUE);
        try {
            Object call = sNewCall.invoke(client, req);
            Object resp = sExecute.invoke(call);
            int code = (Integer) sCode.invoke(resp);
            Object respBody = sBody.invoke(resp);
            String text = respBody == null ? null : (String) sString.invoke(respBody);
            long cost = android.os.SystemClock.elapsedRealtime() - t0;
            log(Log.INFO, method + " " + shortUrl(url) + " → HTTP " + code
                    + " (" + cost + "ms, " + (text == null ? 0 : text.length()) + " 字符)");
            if (text != null && !text.isEmpty() && (code < 200 || code >= 300)) {
                log(Log.WARN, "响应片段：" + clip(text, 180));
            }
            return text;
        } catch (Throwable t) {
            log(Log.WARN, "请求失败 " + shortUrl(url) + " : " + t);
            return null;
        } finally {
            if (ownPrev == null) {
                sOwnRequest.remove();
            } else {
                sOwnRequest.set(ownPrev);
            }
        }
    }

    private static String shortUrl(String url) {
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static void log(int level, String msg) {
        MainModule m = sModule;
        if (m != null) {
            m.logd(level, m.TAG, msg);
        }
    }
}
