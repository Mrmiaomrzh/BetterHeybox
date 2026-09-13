package com.better.heybox.watch;

import android.util.Log;

import com.better.heybox.MainModule;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 复用宿主 OkHttp 的请求桥。
 *
 * <p>小黑盒业务接口需要 hkey/_time/nonce 签名，客户端自己算既脆弱又容易随版本失效
 * （公开的签名实现已因算法变更而废弃）。这里改为：<b>捕获宿主自己的 OkHttpClient 实例</b>，
 * 用它发我们自己的 URL —— 宿主的签名拦截器、Cookie、UA 全部照常生效。
 *
 * <p>捕获方式：hook {@code okhttp3.OkHttpClient#newCall(Request)}，只读 {@code this}，不改变行为。
 * 任何异常都只记日志并返回 null，绝不影响宿主。
 */
public final class HttpBridge {

    private static volatile Object sClient;
    private static volatile ClassLoader sCl;
    private static volatile MainModule sModule;
    private static volatile boolean sLogged;

    private HttpBridge() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    /** 由 OkHttpClient.newCall 的 hook 调用 */
    public static void capture(Object client, ClassLoader cl) {
        if (client == null) {
            return;
        }
        if (sClient == null) {
            sClient = client;
            sCl = cl != null ? cl : client.getClass().getClassLoader();
            log(Log.INFO, "已捕获宿主 OkHttpClient：" + client.getClass().getName());
        }
    }

    public static boolean ready() {
        return sClient != null && sCl != null;
    }

    public static String describe() {
        Object c = sClient;
        return c == null ? "未捕获" : c.getClass().getName();
    }

    /**
     * 用宿主 OkHttp 发 GET。
     *
     * @return 响应体；任何失败返回 null（调用方按"没拿到数据"处理）
     */
    public static String get(String url, Map<String, String> headers) {
        return request("GET", url, headers, null);
    }

    public static String postJson(String url, Map<String, String> headers, String json) {
        return request("POST", url, headers, json);
    }

    private static String request(String method, String url, Map<String, String> headers, String body) {
        Object client = sClient;
        ClassLoader cl = sCl;
        if (client == null || cl == null) {
            log(Log.WARN, "HttpBridge 尚未捕获宿主 OkHttpClient，跳过请求 " + url);
            return null;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            Class<?> reqBuilder = Class.forName("okhttp3.Request$Builder", false, cl);
            Object b = reqBuilder.getDeclaredConstructor().newInstance();
            reqBuilder.getMethod("url", String.class).invoke(b, url);
            if (headers != null) {
                Method addHeader = reqBuilder.getMethod("addHeader", String.class, String.class);
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    addHeader.invoke(b, e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                Class<?> bodyCls = Class.forName("okhttp3.RequestBody", false, cl);
                Class<?> mediaType = Class.forName("okhttp3.MediaType", false, cl);
                Object mt = mediaType.getMethod("parse", String.class).invoke(null, "application/json; charset=utf-8");
                Object rb = bodyCls.getMethod("create", mediaType, String.class).invoke(null, mt, body);
                reqBuilder.getMethod("method", String.class, bodyCls).invoke(b, method, rb);
            } else if (!"GET".equals(method)) {
                reqBuilder.getMethod("method", String.class, Class.forName("okhttp3.RequestBody", false, cl))
                        .invoke(b, method, null);
            }
            Object req = reqBuilder.getMethod("build").invoke(b);

            Class<?> reqCls = Class.forName("okhttp3.Request", false, cl);
            Object call = client.getClass().getMethod("newCall", reqCls).invoke(client, req);
            Class<?> callCls = Class.forName("okhttp3.Call", false, cl);
            Object resp = callCls.getMethod("execute").invoke(call);

            int code = (Integer) resp.getClass().getMethod("code").invoke(resp);
            Object respBody = resp.getClass().getMethod("body").invoke(resp);
            String text = respBody == null ? null
                    : (String) respBody.getClass().getMethod("string").invoke(respBody);
            long cost = android.os.SystemClock.elapsedRealtime() - t0;
            log(Log.INFO, method + " " + shortUrl(url) + " → HTTP " + code
                    + " (" + cost + "ms, " + (text == null ? 0 : text.length()) + " 字符)");
            if (text != null && text.length() > 0 && (code < 200 || code >= 300)) {
                log(Log.WARN, "响应片段：" + clip(text, 200));
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
            m.logd(level, m.TAG, "[动态推送] " + msg);
        }
    }
}
