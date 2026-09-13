package com.better.heybox.watch;

import android.util.Log;

import com.better.heybox.MainModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 复用宿主 OkHttp 的请求桥。
 *
 * <p>小黑盒业务接口需要 hkey/_time/nonce 签名，客户端自己算既脆弱又容易随版本失效；
 * 这里改为<b>捕获宿主自己的 HTTP 客户端实例</b>，用它发我们自己的 URL —— 宿主的签名拦截器、
 * Cookie、UA 全部照常生效。
 *
 * <p><b>为什么不按类名找</b>：实测小黑盒 1.3.395 的 R8 把 OkHttp 的公开 API 类全部改了名
 * （{@code OkHttpClient} → {@code okhttp3.z}、{@code Request} → {@code okhttp3.a0}，
 * 且 {@code okhttp3.Request} 这个名字根本不存在），所以：
 * <ol>
 *   <li>载体类用 {@code okhttp3.internal.connection.RealCall} 的构造函数捕获
 *       —— 它的第一个参数就是客户端（R8 改不了参数顺序）</li>
 *   <li>请求的构造/发送全部按<b>方法签名形状</b>动态识别（与名字无关）：
 *       Request#newBuilder() → Builder#url(String) → Builder#build()；
 *       Client#newCall(Request) → Call#execute() → Response#code()/body().string()</li>
 * </ol>
 * 任何异常都只记日志并返回 null，绝不影响宿主。
 */
public final class HttpBridge {

    /** 候选载体类：OkHttp 4/5 与旧版路径 */
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

    public static String describe() {
        return sDescribe;
    }

    /**
     * 由 RealCall 构造函数调用：arg0 = 客户端（OkHttpClient），arg1 = 原始请求（Request）。
     *
     * @return 是否已具备发请求的能力
     */
    public static boolean captureIfClient(Object client, Object request, ClassLoader cl) {
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

    // ------------------------------------------------------------ 结构识别

    /** Request#newBuilder() -> Builder#url(String) -> Builder#build() */
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
            // Builder#method(String, RequestBody)：用于把复用的模板强制成 GET
            Method methodSetter = null;
            for (Method m : t.getMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 2 && ps[0] == String.class && !ps[1].isPrimitive()
                        && m.getReturnType() == t) {
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

    /** Client#newCall(Request) -> Call#execute() -> Response#code()/body().string() */
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

    /** Object 自带方法（toString/hashCode/getClass 等）不算候选，否则任何类都"看起来"有 string() */
    private static boolean isObjectMethod(Method m) {
        Class<?> d = m.getDeclaringClass();
        if (d == Object.class) {
            return true;
        }
        String n = m.getName();
        return "toString".equals(n) || "hashCode".equals(n) || "getClass".equals(n);
    }

    /** 在某类型上找返回 String 的 0 参数方法（排除 Object 自带） */
    private static Method findStringMethod(Class<?> c) {
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == String.class && !isObjectMethod(m)) {
                return m;
            }
        }
        return null;
    }

    /** 一次性结构诊断：识别失败时把关键信息打出来（每行一条，避免被文件日志按行截断） */
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

    // ------------------------------------------------------------ 发请求

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
            // newBuilder() 会把原请求的 method/body 一起复制过来，必须重置为 GET
            if (sMethod != null) {
                sMethod.invoke(builder, "GET", null);
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
            log(Log.WARN, "构造请求失败: " + t);
            return null;
        }
        // 只支持 GET（当前用途），POST 需要 RequestBody，这里不做，避免依赖更多混淆类
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
