package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.MainModule;

/**
 * 浏览器重定向 + 网页日志。拦截链：Instrumentation 启动改写 → WebView.loadUrl →
 * 页内跳转代理 → 容器 onCreate 兜底。
 * 规则优先级：强制内置域名 > 强制重定向域名 > 默认；已知域与敏感页默认内置（登录态依赖内置 WebView）。
 */
public final class BrowserRedirectHook {

    private final MainModule module;

    /** 内置网页容器入口（Transparent 为 WebActionActivity 子类，自动覆盖） */
    private static final String[] ENTRY_ACTIVITIES = {
            "com.max.xiaoheihe.module.webview.WebActionActivity",
            "com.max.xiaoheihe.module.webview.NativeWebActionActivity",
    };

    /** Instrumentation 层拦截的容器类（含 Transparent 子类）；命中即把 Intent 原地改成 ACTION_VIEW */
    private static final Set<String> ENTRY_ACTIVITY_CLASSES = new HashSet<>(Arrays.asList(
            "com.max.xiaoheihe.module.webview.WebActionActivity",
            "com.max.xiaoheihe.module.webview.NativeWebActionActivity",
            "com.max.xiaoheihe.module.webview.TransparentWebActionActivity"));

    /** 已知域名后缀（取自宿主白名单），登录态 Cookie 只注入这些域 */
    private static final String[] KNOWN_HOST_SUFFIXES = {
            "xiaoheihe.cn", "maxjia.com", "max-c.com", "dotamax.com", "debugmode.cn", "heybox.hk",
    };

    /** 敏感页关键词：命中即强制内置（登录/授权/实名/钱包/结算等，缺失回调会断登录链路） */
    private static final String[] SENSITIVE_KEYWORDS = {
            "login", "logon", "signin", "signup", "register", "oauth", "passport", "auth",
            "account", "realname", "real_name", "bind", "verify",
            "wallet", "pay", "cashier", "checkout", "recharge", "trade", "order",
    };

    /** 已处理过的 client 类与已 hook 的 Method（宿主多个 client 共享父类时防重复 hook） */
    private final Set<Class<?>> hookedClients = new HashSet<>();
    private final Set<Method> hookedMethods = new HashSet<>();

    public BrowserRedirectHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        int installed = 0;
        // 主拦截：Instrumentation.execStartActivity 是进程内所有 Activity 启动的必经点，
        // 在网页容器创建前把 Intent 原地改成 ACTION_VIEW，避免"先开内置页再关闭"的闪跳
        installed += hookActivityStart();
        // 兜底：Activity 已创建（如最近任务恢复等绕过 execStartActivity 的路径）时再补重定向
        for (String className : ENTRY_ACTIVITIES) {
            Class<?> activity;
            try {
                activity = Class.forName(className, false, cl);
            } catch (Throwable t) {
                continue;
            }
            try {
                // onCreate 多数容器不自己声明（继承自 BaseActivity），沿父类链找
                Method onCreate = findMethodInHierarchy(activity, "onCreate", Bundle.class);
                if (onCreate == null) {
                    throw new NoSuchMethodException("onCreate(Bundle) not declared");
                }
                module.hook(onCreate).intercept(chain -> {
                    chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            handleEntry((Activity) self);
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "浏览器重定向入口处理失败", t);
                    }
                    return null;
                });
                installed++;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "✘ 浏览器重定向入口 Hook 失败: " + className, t);
            }
        }

        // 应用内 Fragment 导航嵌入网页容器不经过 startActivity（execStartActivity 拦不到，
        // 白屏就是先建好的容器），补 WebView.loadUrl 拦截：命中规则在加载前改跳浏览器
        int loads = 0;
        try {
            for (Method method : WebView.class.getDeclaredMethods()) {
                if (!"loadUrl".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 1 || params[0] != String.class) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String && shouldRedirect((String) arg)) {
                        String url = (String) arg;
                        Object self = chain.getThisObject();
                        if (self instanceof WebView) {
                            redirectLoadedPage((WebView) self, url);
                        }
                        return null;
                    }
                    return chain.proceed();
                });
                loads++;
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ loadUrl 拦截 Hook 失败", t);
        }

        int clients = 0;
        try {
            Method setter = WebView.class.getDeclaredMethod("setWebViewClient", WebViewClient.class);
            module.hook(setter).intercept(chain -> {
                Object client = chain.getArg(0);
                if (client != null) {
                    hookClientClass(client.getClass());
                }
                return chain.proceed();
            });
            clients = 1;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 浏览器重定向页内跳转 Hook 失败", t);
        }

        int chrome = 0;
        try {
            Method setter = WebView.class.getDeclaredMethod("setWebChromeClient", WebChromeClient.class);
            module.hook(setter).intercept(chain -> {
                Object client = chain.getArg(0);
                if (client != null) {
                    hookChromeClientClass(client.getClass());
                }
                return chain.proceed();
            });
            chrome = 1;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 网页日志标题 Hook 失败", t);
        }

        if (installed > 0 || clients > 0 || loads > 0) {
            module.logd(Log.INFO, module.TAG, "✔ 浏览器重定向 Hook 已安装（启动拦截 "
                    + installed + "，页内跳转 " + (clients > 0 ? "已挂" : "未挂")
                    + "，loadUrl " + loads + "，标题 " + (chrome > 0 ? "已挂" : "未挂") + "）");
        } else {
            module.logd(Log.WARN, module.TAG, "✘ 浏览器重定向 Hook 未命中任何拦截点");
        }
    }

    /** 沿父类链找声明的方法（onCreate 等生命周期方法多在基类） */
    private static Method findMethodInHierarchy(Class<?> start, String name, Class<?>... paramTypes) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** loadUrl 拦截：转外部浏览器；独立容器结束，嵌入容器只拦加载（finish 会误杀宿主页面） */
    private void redirectLoadedPage(WebView webView, String url) {
        openExternal(webView.getContext(), url);
        try {
            android.content.Context context = webView.getContext();
            if (context instanceof Activity
                    && ENTRY_ACTIVITY_CLASSES.contains(((Activity) context).getClass().getName())) {
                ((Activity) context).finish();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 入口拦截：从 activity intent 取 pageurl，命中规则则转外部浏览器并结束内置页 */
    private void handleEntry(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        String url = activity.getIntent() == null ? null : activity.getIntent().getStringExtra("pageurl");
        if (!shouldRedirect(url)) {
            return;
        }
        openExternal(activity, url);
        // 兜底路径才会走到这里：跳过关闭动画，减弱"界面后退"感
        try {
            activity.overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
        activity.finish();
    }

    /** 进程内所有 Activity 启动必经 Instrumentation；返回挂上的 overload 数量 */
    private int hookActivityStart() {
        int installed = 0;
        try {
            for (Method method : android.app.Instrumentation.class.getDeclaredMethods()) {
                if (!"execStartActivity".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                // (who, contextThread, token, target, intent, requestCode, options[, userId])
                if (params.length < 7 || params[4] != Intent.class) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    try {
                        List<Object> args = chain.getArgs();
                        if (args.size() > 4 && args.get(4) instanceof Intent) {
                            redirectEntryIntent((Intent) args.get(4));
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "浏览器重定向启动拦截失败", t);
                    }
                    return chain.proceed();
                });
                installed++;
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ Instrumentation 启动拦截 Hook 失败", t);
        }
        return installed;
    }

    /**
     * Intent 原地改写为 ACTION_VIEW（不重建参数，直接改对象字段，proceed 即生效）：
     * 网页容器 Activity 不再创建，外部浏览器直接从当前界面拉起，无后退跳变
     */
    private void redirectEntryIntent(Intent intent) {
        android.content.ComponentName component = intent.getComponent();
        if (component == null || !ENTRY_ACTIVITY_CLASSES.contains(component.getClassName())) {
            return;
        }
        String url = intent.getStringExtra("pageurl");
        if (!shouldRedirect(url)) {
            return;
        }
        intent.setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(url), null)
                .setComponent(null)
                // 路由构造的 Intent 可能带 setPackage(宿主包名)，不清掉会解析到自家
                // RouterActivity（splash 主题白屏闪一下）再二次派发
                .setPackage(null)
                .replaceExtras((Bundle) null);
        applyTargetPackage(intent);
        module.logd(Log.INFO, module.TAG, "浏览器重定向(启动): " + url);
    }

    /** 用户指定的浏览器包名；校验可用后 setPackage，跳过系统解析（未设默认时避免每次弹选择框） */
    private volatile String cachedTarget;
    private volatile boolean cachedTargetUsable;

    private void applyTargetPackage(Intent intent) {
        try {
            String target = module.getString(App.KEY_BROWSER_TARGET, "");
            if (target.isEmpty()) {
                return;
            }
            if (!target.equals(cachedTarget)) {
                cachedTarget = target;
                cachedTargetUsable = isBrowserResolvable(target);
                if (!cachedTargetUsable) {
                    module.logd(Log.WARN, module.TAG, "指定浏览器不可用: " + target + "，回退系统解析");
                }
            }
            if (cachedTargetUsable) {
                intent.setPackage(target);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "指定浏览器失败", t);
        }
    }

    private boolean isBrowserResolvable(String pkg) {
        try {
            android.content.Context context = App.resolveAppContext();
            if (context == null) {
                return false;
            }
            android.content.pm.PackageManager pm = context.getPackageManager();
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/"));
            probe.setPackage(pkg);
            return pm.resolveActivity(probe, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 给实际使用的 WebViewClient 子类挂重定向代理与页面日志（框架方法名不混淆，子类按需发现） */
    private void hookClientClass(Class<?> clientClass) {
        if (clientClass == null || clientClass == WebViewClient.class) {
            return;
        }
        String name = clientClass.getName();
        if (isFrameworkClass(name)) {
            return;
        }
        synchronized (hookedClients) {
            if (!hookedClients.add(clientClass)) {
                return;
            }
        }
        int hooked = 0;
        for (Method method : findHookTargets(clientClass, "shouldOverrideUrlLoading")) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[0] != WebView.class
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            final boolean isRequestVariant = params[1] == WebResourceRequest.class;
            if (!isRequestVariant && params[1] != String.class) {
                continue;
            }
            try {
                module.hook(method).intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    WebView webView = !args.isEmpty() && args.get(0) instanceof WebView
                            ? (WebView) args.get(0) : null;
                    String url = extractUrl(args);
                    if (webView != null && isMainFrameRequest(isRequestVariant, args)
                            && shouldRedirect(url)) {
                        openExternal(webView.getContext(), url);
                        module.logd(Log.INFO, module.TAG, "浏览器重定向(页内): " + url);
                        return true;
                    }
                    return chain.proceed();
                });
                hooked++;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "shouldOverrideUrlLoading Hook 失败: " + name, t);
            }
        }
        // 日志只看宿主自己的 client，第三方 SDK 的 WebView 不记
        if (name.startsWith("com.max.")) {
            for (Method method : findHookTargets(clientClass, "onPageStarted")) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 3 || params[0] != WebView.class
                        || params[1] != String.class) {
                    continue;
                }
                try {
                    module.hook(method).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        if (args.size() >= 2 && args.get(0) instanceof WebView
                                && args.get(1) instanceof String) {
                            recordPageStart((WebView) args.get(0), (String) args.get(1));
                        }
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "onPageStarted Hook 失败: " + name, t);
                }
            }
        }
        if (hooked > 0) {
            module.logd(Log.INFO, module.TAG, "✔ 浏览器重定向已代理 WebViewClient: " + name);
        }
    }

    /** 页面标题（可选：宿主 chrome client 未声明 onReceivedTitle 时日志只记 URL） */
    private void hookChromeClientClass(Class<?> clientClass) {
        if (clientClass == null || clientClass == WebChromeClient.class
                || isFrameworkClass(clientClass.getName()) || !clientClass.getName().startsWith("com.max.")) {
            return;
        }
        for (Method method : findHookTargets(clientClass, "onReceivedTitle")) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[0] != WebView.class
                    || params[1] != String.class) {
                continue;
            }
            try {
                module.hook(method).intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    if (args.size() >= 2 && args.get(0) instanceof WebView
                            && args.get(1) instanceof String) {
                        recordTitle((WebView) args.get(0), (String) args.get(1));
                    }
                    return chain.proceed();
                });
                module.logd(Log.INFO, module.TAG, "✔ 网页日志已代理 WebChromeClient: " + clientClass.getName());
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "onReceivedTitle Hook 失败: " + clientClass.getName(), t);
            }
        }
    }

    /**
     * 沿父类链找按名匹配的方法（框架类前停止）；子类可以不自己声明（如 WebviewFragment 的
     * client 继承 interceptrequest.d 的 final 实现），第一个声明处即可拦到所有调用。
     * 以 Method 全局去重：宿主多个 client 共享父类时不重复 hook。
     */
    private List<Method> findHookTargets(Class<?> start, String name) {
        List<Method> out = new ArrayList<>();
        for (Class<?> c = start; c != null && !isFrameworkClass(c.getName()); c = c.getSuperclass()) {
            boolean declared = false;
            for (Method method : c.getDeclaredMethods()) {
                if (!name.equals(method.getName())) {
                    continue;
                }
                declared = true;
                if (hookedMethods.add(method)) {
                    out.add(method);
                }
            }
            if (declared) {
                break;
            }
        }
        return out;
    }

    private static boolean isFrameworkClass(String name) {
        return name.startsWith("android.") || name.startsWith("com.android.") || name.startsWith("java.");
    }

    private static String extractUrl(List<Object> args) {
        for (int i = 1; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof String) {
                return (String) arg;
            }
            if (arg != null) {
                try {
                    Method getUrl = arg.getClass().getMethod("getUrl");
                    Object url = getUrl.invoke(arg);
                    if (url instanceof String) {
                        return (String) url;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 三参重载时只在主 frame 生效，iframe 跳转不动 */
    private static boolean isMainFrameRequest(boolean isRequestVariant, List<Object> args) {
        if (!isRequestVariant) {
            return true;
        }
        for (Object arg : args) {
            if (arg != null) {
                try {
                    Method isForMainFrame = arg.getClass().getMethod("isForMainFrame");
                    Object flag = isForMainFrame.invoke(arg);
                    if (flag instanceof Boolean) {
                        return (Boolean) flag;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return true;
    }

    private void openExternal(android.content.Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            applyTargetPackage(intent);
            context.startActivity(intent);
            module.logd(Log.INFO, module.TAG, "浏览器重定向(打开): " + url);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "外部浏览器打开失败: " + url, t);
        }
    }

    /** 规则判定：http(s) 外链；自定义排除 > 自定义强制 > 默认（敏感页/.apk/非小黑盒域才重定向） */
    boolean shouldRedirect(String url) {
        if (url == null) {
            return false;
        }
        if (!module.isEnabled(App.KEY_BROWSER_REDIRECT, false)) {
            return false;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        Uri uri = Uri.parse(trimmed);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        // 关键词只看 path+query（host 上 "pay" 子串会误伤 paypal.com 之类）
        String path = (uri.getPath() == null ? "" : uri.getPath().toLowerCase())
                + "?" + (uri.getQuery() == null ? "" : uri.getQuery().toLowerCase());

        List<String> blocked = parseDomains(module.getString(App.KEY_BROWSER_REDIRECT_BLOCK, ""));
        if (matchesDomain(host, blocked)) {
            return false;
        }
        List<String> forced = parseDomains(module.getString(App.KEY_BROWSER_REDIRECT_FORCE, ""));
        if (matchesDomain(host, forced)) {
            return true;
        }
        boolean knownHost = false;
        for (String suffix : KNOWN_HOST_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                knownHost = true;
                break;
            }
        }
        if (knownHost && !module.isEnabled(App.KEY_BROWSER_REDIRECT_KNOWN, false)) {
            return false;
        }
        if (path.contains(".apk")) {
            return false;
        }
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (path.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    /** 一行一个域名，兼容粘贴完整 URL（去掉 scheme 与路径）；空行忽略 */
    static List<String> parseDomains(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String d = line.trim().toLowerCase();
            if (d.isEmpty()) {
                continue;
            }
            if (d.startsWith("http://")) {
                d = d.substring(7);
            } else if (d.startsWith("https://")) {
                d = d.substring(8);
            }
            int slash = d.indexOf('/');
            if (slash >= 0) {
                d = d.substring(0, slash);
            }
            if (!d.isEmpty()) {
                out.add(d);
            }
        }
        return out;
    }

    /** 域名匹配：精确或子域（example.com 覆盖 a.example.com） */
    private static boolean matchesDomain(String host, List<String> domains) {
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    // ---- 网页日志 ----

    private static final int LOG_MAX_ENTRIES = 80;

    private static final class LogEntry {
        final long at;
        final String url;
        String title;

        LogEntry(long at, String url, String title) {
            this.at = at;
            this.url = url;
            this.title = title;
        }
    }

    private static final Object logLock = new Object();
    private static final java.util.ArrayDeque<LogEntry> logEntries = new java.util.ArrayDeque<>();

    private void recordPageStart(WebView webView, String url) {
        if (!module.isEnabled(App.KEY_WEB_LOG, false) || url == null || url.isEmpty()) {
            return;
        }
        synchronized (logLock) {
            logEntries.addFirst(new LogEntry(System.currentTimeMillis(), url, ""));
            while (logEntries.size() > LOG_MAX_ENTRIES) {
                logEntries.removeLast();
            }
        }
        persistLog();
    }

    /** 标题晚于开页到达：补进最新同 URL 条目，避免同页记两行 */
    private void recordTitle(WebView webView, String title) {
        if (!module.isEnabled(App.KEY_WEB_LOG, false) || title == null || title.isEmpty()) {
            return;
        }
        String url = null;
        try {
            url = webView.getUrl();
        } catch (Throwable ignored) {
        }
        if (url == null) {
            return;
        }
        synchronized (logLock) {
            for (LogEntry entry : logEntries) {
                if (url.equals(entry.url)) {
                    if (title.equals(entry.title)) {
                        return;
                    }
                    entry.title = title;
                    break;
                }
            }
        }
        persistLog();
    }

    private void persistLog() {
        try {
            HeyboxPrefs.setString(App.KEY_WEB_LOG_DATA, serializeLog());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "网页日志写入失败", t);
        }
    }

    /** 供设置页「查看网页日志」清空用；内存环与文件一并清，避免旧条目被下次事件写回 */
    public static void clearLog() {
        synchronized (logLock) {
            logEntries.clear();
        }
        HeyboxPrefs.setString(App.KEY_WEB_LOG_DATA, "");
    }

    private String serializeLog() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        synchronized (logLock) {
            for (LogEntry entry : logEntries) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append('[').append(fmt.format(new Date(entry.at))).append("] ")
                        .append(entry.title == null || entry.title.isEmpty() ? "（无标题）" : entry.title)
                        .append('\n').append(entry.url);
            }
        }
        return sb.toString();
    }
}
