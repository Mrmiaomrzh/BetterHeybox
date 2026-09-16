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
 * Browser redirect + web log. Layers: entry-intent rewrite, WebView.loadUrl,
 * in-page navigation, container onCreate fallback.
 * Order: block list > force list > host-app H5 pages > defaults. Known hosts,
 * sensitive pages and host-app H5 pages stay in-app (login cookies are injected
 * into the built-in WebView only).
 * Hard rule: swallow a load only when the container is closeable, see canCloseContainer.
 */
public final class BrowserRedirectHook {

    private final MainModule module;

    /** Web container entries; subclasses covered by the family check */
    private static final String[] ENTRY_ACTIVITIES = {
            "com.max.xiaoheihe.module.webview.WebActionActivity",
            "com.max.xiaoheihe.module.webview.NativeWebActionActivity",
    };

    /** Fallback entry-container list, used only when the component class cannot be resolved */
    private static final Set<String> ENTRY_ACTIVITY_CLASSES = new HashSet<>(Arrays.asList(
            "com.max.xiaoheihe.module.webview.WebActionActivity",
            "com.max.xiaoheihe.module.webview.NativeWebActionActivity",
            "com.max.xiaoheihe.module.webview.TransparentWebActionActivity"));

    /**
     * Web container family roots. Subclasses are covered by walking the superclass chain:
     * TransparentWebAction, InjectJsV2, MiniProgramHost (mini programs) all extend
     * WebActionActivity, which an exact-name list misses.
     */
    private static final String[] CONTAINER_ROOT_CLASSES = {
            "com.max.xiaoheihe.module.webview.WebActionActivity",
            "com.max.xiaoheihe.module.webview.NativeWebActionActivity",
    };

    /** True when cls or a superclass is a container root */
    private static boolean isWebContainer(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            String name = c.getName();
            for (String root : CONTAINER_ROOT_CLASSES) {
                if (root.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Closeable container: family member and not finishing */
    private static boolean canCloseContainer(android.content.Context context) {
        if (!(context instanceof Activity)) {
            return false;
        }
        Activity activity = (Activity) context;
        return !activity.isFinishing() && !activity.isDestroyed()
                && isWebContainer(activity.getClass());
    }

    /** Known host suffixes (host whitelist); login cookies go to these only */
    private static final String[] KNOWN_HOST_SUFFIXES = {
            "xiaoheihe.cn", "maxjia.com", "max-c.com", "dotamax.com", "debugmode.cn", "heybox.hk",
    };

    /**
     * Host-app H5 page domains; official pages and mini programs live here and need the
     * built-in WebView cookies (x0.c writes pkey / x_heybox_id), so never redirect them.
     * The user's force-redirect list still wins.
     */
    private static final String[] HOST_APP_PAGE_HOSTS = {
            "web.xiaoheihe.cn",
            "web.debugmode.cn",
    };

    /** Host-app H5 page domain, subdomains included */
    private static boolean isHostAppPage(String host) {
        for (String h : HOST_APP_PAGE_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) {
                return true;
            }
        }
        return false;
    }

    /** Host of url, lowercase; empty on failure */
    private static String hostOf(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Mini program container (MiniProgramHostActivity, MiniProgramContainerActivity, ...).
     * Its first page is an in-app page and stays in-app; links clicked inside still redirect.
     */
    private static boolean isMiniProgramContainer(android.content.Context context) {
        return context != null && isMiniProgramContainer(context.getClass());
    }

    /** Class flavour, for the entry layer which only has the component class */
    private static boolean isMiniProgramContainer(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.contains(".miniprogram.") || name.contains(".littleprogram.")
                    || name.contains("MiniProgram") || name.contains("LittleProgram")) {
                return true;
            }
        }
        return false;
    }

    /** Sensitive pages (login / auth / wallet / pay) stay in-app */
    private static final String[] SENSITIVE_KEYWORDS = {
            "login", "logon", "signin", "signup", "register", "oauth", "passport", "auth",
            "account", "realname", "real_name", "bind", "verify",
            "wallet", "pay", "cashier", "checkout", "recharge", "trade", "order",
    };

    /** Hooked clients / methods; host clients share base classes */
    private final Set<Class<?>> hookedClients = new HashSet<>();
    private final Set<Method> hookedMethods = new HashSet<>();

    public BrowserRedirectHook(MainModule module) {
        this.module = module;
    }

    /** Host class loader, used to resolve entry component classes */
    private ClassLoader hostClassLoader;

    public void install(ClassLoader cl) {
        hostClassLoader = cl;
        int installed = 0;
        // main hook: every activity start goes through execStartActivity, so the intent is
        // rewritten before the container exists (no open-then-close flash)
        installed += hookActivityStart();
        // fallback for starts that bypass execStartActivity (task restore)
        for (String className : ENTRY_ACTIVITIES) {
            Class<?> activity;
            try {
                activity = Class.forName(className, false, cl);
            } catch (Throwable t) {
                continue;
            }
            try {
                // onCreate is usually inherited from BaseActivity: walk up
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

        // fragment navigation embeds a container without startActivity, so hook loadUrl too
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
                        WebView webView = self instanceof WebView ? (WebView) self : null;
                        android.content.Context context = webView == null ? null : webView.getContext();
                        if (!canCloseContainer(context)) {
                            // not a closeable container: let it load
                            module.logd(Log.WARN, module.TAG, "跳过重定向(容器不可关闭): "
                                    + (context == null ? "unknown" : context.getClass().getName())
                                    + " " + url);
                        } else if (isMiniProgramContainer(context) && !forcedByUser(url)) {
                            // mini program page: keep in-app (force list can override)
                            module.logd(Log.INFO, module.TAG, "跳过重定向(小程序页面): " + url);
                        } else {
                            // swallow the load and finish the container together (#33)
                            redirectLoadedPage(webView, url);
                            return null;
                        }
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

    /** Find a declared method up the chain (lifecycle methods live in base classes) */
    private static Method findMethodInHierarchy(Class<?> start, String name, Class<?>... paramTypes) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /**
     * loadUrl: open externally, then finish the container. Family-based rather than
     * exact-named, otherwise a missed subclass keeps an empty container on the stack (#33).
     */
    private void redirectLoadedPage(WebView webView, String url) {
        openExternal(webView.getContext(), url);
        try {
            android.content.Context context = webView.getContext();
            if (context instanceof Activity && isWebContainer(context.getClass())) {
                ((Activity) context).finish();
            }
        } catch (Throwable ignored) {
        }
    }

    /** onCreate fallback: redirect the container page from its intent and finish it */
    private void handleEntry(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        // the hook sits on BaseActivity.onCreate and sees every activity: limit it to
        // the container family, other activities keep their pageurl untouched
        if (!isWebContainer(activity.getClass())) {
            return;
        }
        String url = activity.getIntent() == null ? null : entryUrl(activity.getIntent());
        if (!shouldRedirect(url)) {
            return;
        }
        // same rule as loadUrl: mini program pages stay in-app
        if (isMiniProgramContainer(activity.getClass()) && !forcedByUser(url)) {
            module.logd(Log.INFO, module.TAG, "跳过重定向(小程序页面·入口): " + url);
            return;
        }
        openExternal(activity, url);
        // fallback path only: skip the close animation
        try {
            activity.overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
        activity.finish();
    }

    /** Hook Instrumentation.execStartActivity; returns the overloads hooked */
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
     * Rewrite the intent in place to ACTION_VIEW: the container is never created and the
     * browser opens straight from the current screen.
     */
    private void redirectEntryIntent(Intent intent) {
        android.content.ComponentName component = intent.getComponent();
        if (component == null) {
            return;
        }
        String className = component.getClassName();
        Class<?> target = resolveTargetClass(className);
        // resolved class -> family check; unresolved -> exact-name fallback
        boolean container = target != null
                ? isWebContainer(target)
                : ENTRY_ACTIVITY_CLASSES.contains(className);
        if (!container) {
            return;
        }
        // protocol containers (mini programs) keep the url in web_protocol.webview.url
        String url = entryUrl(intent);
        if (!shouldRedirect(url)) {
            return;
        }
        // mini program page stays in-app (force list can override)
        if (target != null && isMiniProgramContainer(target) && !forcedByUser(url)) {
            module.logd(Log.INFO, module.TAG, "跳过重定向(小程序页面·启动): " + url);
            return;
        }
        intent.setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(url), null)
                .setComponent(null)
                // router-built intents may carry setPackage(host): clear it, else it lands on
                // our own RouterActivity and flashes a splash-themed window
                .setPackage(null)
                .replaceExtras((Bundle) null);
        applyTargetPackage(intent);
        module.logd(Log.INFO, module.TAG, "浏览器重定向(启动): " + url);
    }

    /** Preferred browser package; skips the system resolver when set */
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

    /** Proxy the real WebViewClient subclass (framework method names never obfuscate) */
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
        // log host clients only, not third-party SDK webviews
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

    /** Page title when the host chrome client exposes onReceivedTitle */
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
     * Find by name up the chain, stopping at framework classes: the first declaration
     * catches every call. Methods are deduped globally so shared base classes hook once.
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

    /** Three-arg overload: main frame only, iframes untouched */
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

    /** Resolve a host component class; null when unavailable */
    private Class<?> resolveTargetClass(String className) {
        try {
            ClassLoader cl = hostClassLoader;
            return cl == null ? Class.forName(className) : Class.forName(className, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Entry url: pageurl first, else web_protocol -> WebCfgObj.url. Host classes are
     * obfuscated, so the getters are called reflectively; null when unavailable.
     */
    private static String entryUrl(Intent intent) {
        String url = intent.getStringExtra("pageurl");
        if (url != null && !url.trim().isEmpty()) {
            return url;
        }
        try {
            Object protocol = intent.getSerializableExtra("web_protocol");
            if (protocol == null) {
                return null;
            }
            Object cfg = invokeNoArg(protocol, "getWebview");
            Object value = cfg == null ? null : invokeNoArg(cfg, "getUrl");
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reflective no-arg getter; null on failure */
    private static Object invokeNoArg(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable t) {
            return null;
        }
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

    /** True when the url matches the user's force-redirect list */
    private boolean forcedByUser(String url) {
        return matchesDomain(hostOf(url),
                parseDomains(module.getString(App.KEY_BROWSER_REDIRECT_FORCE, "")));
    }

    /** Rule check: http(s) only; block list > force list > host-app pages > defaults */
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
        String host = hostOf(trimmed);
        // keywords match path+query only ("pay" in a host would hit paypal.com)
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
        // host-app H5 pages: cookies are injected in-app only. Checked after the force
        // list, so an explicit user entry still wins
        if (isHostAppPage(host)) {
            return false;
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

    /** One domain per line, full urls accepted; blank lines ignored */
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

    /** Exact or subdomain match */
    private static boolean matchesDomain(String host, List<String> domains) {
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    // ---- web log ----

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

    /** Title lands after onPageStarted: attach it to the newest same-url entry */
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

    /** Clear the memory ring and the persisted log (settings panel) */
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
