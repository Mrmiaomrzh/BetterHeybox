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

    private static volatile Object sClient;
    private static volatile Method sNewCall;
    private static volatile Method sExecute;
    private static volatile Method sCode;
    private static volatile Method sBody;
    private static volatile Method sString;
    private static volatile Method sNewBuilder;
    private static volatile Method sUrl;
    private static volatile Method sBuild;
    private static volatile Method sMethod;
    private static volatile Object sTemplate;

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
                com.better.heybox.HeyboxPrefs.init(com.better.heybox.App.resolveAppContext());
                com.better.heybox.HeyboxPrefs.setString(
                        com.better.heybox.App.KEY_WATCH_RECENT_TOPICS, sb.toString());
            } catch (Throwable ignored) {
            }
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
    private static final java.util.regex.Pattern HOST_UID =
            java.util.regex.Pattern.compile("user_id=(\\d{5,20})");
    private static final java.util.regex.Pattern ANY_UID =
            java.util.regex.Pattern.compile("userid=(\\d{5,20})");
    private static final java.util.regex.Pattern TOPIC_ID =
            java.util.regex.Pattern.compile("topic_id=(\\d{1,20})");
    private static final java.util.LinkedHashSet<String> sRecentTopics = new java.util.LinkedHashSet<>();
    private static final int RECENT_TOPIC_LIMIT = 20;

    private static final java.util.regex.Pattern URL_IN_TOSTRING =
            java.util.regex.Pattern.compile("url=([^,\\s]+)");

    private static void logHostRequest(Object request) {
        try {
            if (request == null) {
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
            java.util.regex.Matcher mt = URL_IN_TOSTRING.matcher(String.valueOf(request));
            if (!mt.find()) {
                return;
            }
            String url = mt.group(1);
            if (sHostUserId == null) {
                java.util.regex.Matcher um = HOST_UID.matcher(url);
                if (!um.find() && url.contains("ws.xiaoheihe.cn")) {
                    um = ANY_UID.matcher(url);
                }
                if (um.find()) {
                    sHostUserId = um.group(1);
                    log(Log.INFO, "宿主登录 userid = " + sHostUserId);
                }
            }
            if (url.contains("topic") || url.contains("hashtag") || url.contains("/search")) {
                log(Log.INFO, "宿主请求 " + url);
            }
            java.util.regex.Matcher tm = TOPIC_ID.matcher(url);
            if (tm.find()) {
                rememberTopic(tm.group(1));
            }
        } catch (Throwable ignored) {
        }
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
