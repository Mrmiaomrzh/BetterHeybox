package com.better.heybox.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.Logs;
import com.better.heybox.MainModule;
import com.better.heybox.ModuleStats;
import com.better.heybox.ViewUtils;

public final class DailyTaskHook {

    private static final int STEP_PICTURE = 0;
    private static final int STEP_NORMAL = 1;
    private static final int STEP_CHANNEL = 2;
    private static final String[] STEP_KEYS = {
            App.KEY_DAILY_TASK_PICTURE,
            App.KEY_DAILY_TASK_NORMAL,
            App.KEY_DAILY_TASK_CHANNEL,
    };
    private static final String[] STEP_NAMES = {"分享任意帖子", "分享游戏详情", "分享游戏评价"};
    private static final int STEP_COUNT = STEP_KEYS.length;

    private static final java.util.Map<String, String[]> CHANNEL_VIEW_TEXTS =
            new java.util.HashMap<>();
    static {
        CHANNEL_VIEW_TEXTS.put("WECHAT", new String[]{"微信", "朋友圈"});
        CHANNEL_VIEW_TEXTS.put("WEIBO", new String[]{"微博"});
        CHANNEL_VIEW_TEXTS.put("QQ", new String[]{"QQ", "QQ空间"});
    }

    private static final String[] CHANNEL_FALLBACK_ORDER = {"QQ", "WECHAT", "WEIBO"};

    private static final String SHARE_CONTAINER_ID = "rv_share_container";

    private static final int CHANNEL_CLICK_ATTEMPTS = 3;
    private static final long CHANNEL_CLICK_RETRY_MS = 400L;

    private static final long STEP_TIMEOUT_MS = 15000L;

    private static final long NO_LINK_LOG_INTERVAL_MS = 10 * 60_000L;

    private static final String[][] UMENG_SHARE_HANDLERS = {
            {"com.umeng.socialize.handler.UMQQSsoHandler", "QQ", "QQ好友"},
            {"com.umeng.socialize.handler.QZoneSsoHandler", "QZONE", "QQ空间"},
            {"com.umeng.socialize.handler.UMWXHandler", "WEIXIN", "微信"},
            {"com.umeng.socialize.handler.SinaSsoHandler", "SINA", "微博"},
    };

    private final MainModule module;

    private volatile ClassLoader targetCl;

    private volatile boolean autoActive;
    private volatile int currentStep = -1;
    private volatile boolean stepTriggered;
    private volatile int completedStep = -1;
    private volatile long stepToken;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile Context autoContext;

    private volatile long lastNoLinkLogAt;

    private final java.util.Map<Object, String> actionIcons =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, String>());

    public DailyTaskHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        this.targetCl = cl;
        hookShareUtils(cl);
        hookSharePanel(cl);
        hookTencentShareToQQ(cl);
        hookTencentShareToQzone(cl);
        hookShareActionEntry(cl);
        hookUmengShareHandlers(cl);
        hookExternalJumps();
        hookWebViewNavigations();
        hookMainResume(cl);
        hookSharePages(cl);
        module.logd(Log.INFO, module.TAG, "✔ 每日任务 Hook 安装完成");
    }
    private void hookShareUtils(ClassLoader cl) {
        try {
            Class<?> shareUtils = Class.forName("com.max.hbshare.ShareUtils", false, cl);
            Class<?> hbShareData = Class.forName("com.max.hbshare.bean.HBShareData", false, cl);
            boolean hooked = false;
            for (Method m : shareUtils.getDeclaredMethods()) {
                if (!"P".equals(m.getName()) && !"y".equals(m.getName())) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2 || pts[0] != Context.class || pts[1] != hbShareData) {
                    continue;
                }
                module.hook(m).intercept(chain -> {
                    if (!autoActive) {
                        return chain.proceed();
                    }
                    try {
                        Object data = chain.getArg(1);
                        if (data != null) {
                            Object ctx = chain.getArg(0);
                            completeShare(data, ctx, cl);
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "每日任务完成回调异常: " + t);
                    }
                    return null;
                });
                hooked = true;
                module.logd(Log.INFO, module.TAG, "✔ 分享完成 Hook 已安装: ShareUtils." + m.getName());
            }
            if (!hooked) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 ShareUtils.P/y(Context,HBShareData)");
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 分享完成 Hook 失败", t);
        }
    }
    private void completeShare(Object hbShareData, Object ctx, ClassLoader cl) throws Throwable {
        Class<?> shareMedia = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl);
        Object qq = Enum.valueOf((Class<Enum>) shareMedia, "QQ");
        Field listenerField = hbShareData.getClass().getDeclaredField("shareListener");
        listenerField.setAccessible(true);
        Object listener = listenerField.get(hbShareData);
        if (listener == null) {
            module.logd(Log.WARN, module.TAG, "HBShareData.shareListener 为 null，跳过");
            return;
        }
        Method onResult = listener.getClass().getMethod("onResult", shareMedia);
        onResult.invoke(listener, qq);
        module.logd(Log.INFO, module.TAG, "✔ 每日任务：分享成功回调已触发 (步骤 " + (currentStep + 1) + "/" + STEP_COUNT + ")");

        Context context = ctx instanceof Context ? (Context) ctx : null;
        mainHandler.post(() -> onStepCompleted(context));
    }

    private static final String[] SHARE_PANEL_CLASSES = {
            "com.max.hbcommon.component.m",
            "com.max.hbcommon.component.i",
    };

    private static final String SHARE_PANEL_SUPER =
            "com.max.hbcustomview.swipebacklayout.a";

    private final java.util.concurrent.atomic.AtomicReference<Class<?>>
            sharePanelClassCache = new java.util.concurrent.atomic.AtomicReference<>();

    private void hookSharePanel(ClassLoader cl) {
        for (Class<?> panel : collectSharePanelCandidates(cl)) {
            Method show;
            try {
                show = panel.getMethod("show");
            } catch (NoSuchMethodException e) {
                module.logd(Log.WARN, module.TAG, "分享面板候选 " + panel.getName()
                        + " 无 show() 方法：类名被占用");
                continue;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "分享面板候选 " + panel.getName()
                        + " 解析失败: " + t);
                continue;
            }
            try {
                module.hook(show).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!autoActive) {
                        return result;
                    }
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof Dialog) {
                            autoClickChannel((Dialog) self);
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "分享面板自动点渠道异常: " + t);
                    }
                    return result;
                });
                module.logd(Log.INFO, module.TAG, "✔ 分享面板 Hook 已安装: "
                        + panel.getName() + ".show()");
                return;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "分享面板候选 " + panel.getName()
                        + " Hook 失败: " + t);
            }
        }
        module.logd(Log.ERROR, module.TAG,
                "✘ 分享面板 Hook 失败：候选名与结构定位均未命中，每日任务自动分享将不可用"
                        + "（请反馈日志，含 swipebacklayout.a 的子类清单）");
    }

    private java.util.List<Class<?>> collectSharePanelCandidates(ClassLoader cl) {
        java.util.LinkedHashSet<Class<?>> out = new java.util.LinkedHashSet<>();

        Class<?> cached = sharePanelClassCache.get();
        if (cached != null) {
            out.add(cached);
        }

        for (String name : SHARE_PANEL_CLASSES) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                out.add(c);
            } catch (Throwable ignored) {
            }
        }

        if (out.isEmpty()) {
            for (Class<?> c : findSharePanelByShape(cl)) {
                out.add(c);
            }
        }

        try {
            out.remove(Class.forName(SHARE_PANEL_SUPER, false, cl));
        } catch (Throwable ignored) {
        }
        return new java.util.ArrayList<>(out);
    }

    private java.util.List<Class<?>> findSharePanelByShape(ClassLoader cl) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        Class<?> superClass;
        try {
            superClass = Class.forName(SHARE_PANEL_SUPER, false, cl);
        } catch (Throwable t) {
            if (sharedShapeWarned.compareAndSet(false, true)) {
                module.logd(Log.WARN, module.TAG,
                        "分享面板结构定位跳过：找不到父类 " + SHARE_PANEL_SUPER
                                + "（已回退到候选类名）: " + t);
            }
            return out;
        }

        for (String name : dexkitSubclassesOfPanel(cl, superClass)) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (matchesSharePanelShape(c)) {
                    out.add(c);
                    sharePanelClassCache.compareAndSet(null, c);
                    return out;
                }
            } catch (Throwable ignored) {
            }
        }

        int probed = 0;
        for (String name : probePanelNamesViaDexkit(cl)) {
            probed++;
            if (probed > 400) {
                break;
            }
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (matchesSharePanelShape(c)) {
                    out.add(c);
                    sharePanelClassCache.compareAndSet(null, c);
                    return out;
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty() && sharedShapeWarned.compareAndSet(false, true)) {
            module.logd(Log.WARN, module.TAG,
                    "分享面板结构定位未命中（已回退到候选类名）");
        }
        return out;
    }

    private static boolean matchesSharePanelShape(Class<?> c) {
        if (c == null || c.isInterface() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
            return false;
        }
        try {
            Class<?> sup = c.getSuperclass();
            if (sup == null || !SHARE_PANEL_SUPER.equals(sup.getName())) {
                return false;
            }
            boolean show = false;
            for (Method m : c.getDeclaredMethods()) {
                if ("show".equals(m.getName())
                        && m.getParameterTypes().length == 0
                        && m.getReturnType() == void.class
                        && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    show = true;
                    break;
                }
            }
            if (!show) {
                return false;
            }
            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                Class<?>[] ps = ctor.getParameterTypes();
                if (ps.length == 3 && ps[0] == Context.class
                        && (ps[1] == int.class || ps[1] == Integer.class)
                        && ps[2] == View.class) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private final java.util.concurrent.atomic.AtomicBoolean sharedShapeWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    private String apkPathForDexKit(ClassLoader cl) {
        for (String probe : new String[]{
                "com.max.xiaoheihe.app.HeyBoxApplication",
                "com.max.hbcommon.base.BaseActivity",
                "com.max.xiaoheihe.MainActivity",
        }) {
            try {
                Class<?> c = Class.forName(probe, false, cl);
                java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    String p = cs.getLocation().getPath();
                    if (p != null && p.endsWith(".apk") && new java.io.File(p).exists()) {
                        return p;
                    }
                    if (p != null && p.endsWith(".dex")) {
                        int i = p.lastIndexOf('/');
                        if (i > 0) {
                            String apk = p.substring(0, i) + "/base.apk";
                            if (new java.io.File(apk).exists()) {
                                return apk;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            Context app = com.better.heybox.App.resolveAppContext();
            if (app == null) {
                app = com.better.heybox.App.getAppContext();
            }
            if (app != null) {
                android.content.pm.ApplicationInfo ai =
                        app.getPackageManager().getApplicationInfo(MainModule.TARGET_PKG, 0);
                if (ai != null && ai.sourceDir != null && new java.io.File(ai.sourceDir).exists()) {
                    return ai.sourceDir;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            String guess = "/data/app/" + MainModule.TARGET_PKG + "/base.apk";
            if (new java.io.File(guess).exists()) {
                return guess;
            }
        } catch (Throwable ignored) {
        }

        module.logd(Log.WARN, module.TAG,
                "分享面板结构定位：APK 路径推导失败（三级兜底均未命中），DexKit 不可用");
        return MainModule.TARGET_PKG;
    }

    private java.util.List<String> dexkitSubclassesOfPanel(ClassLoader cl, Class<?> superClass) {
        java.util.List<String> names = new java.util.ArrayList<>();
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            System.loadLibrary("dexkit");
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPathForDexKit(cl));
            org.luckypray.dexkit.query.FindClass q = org.luckypray.dexkit.query.FindClass.create()
                    .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                            .superClass(superClass.getName()));
            for (org.luckypray.dexkit.result.ClassData cd : bridge.findClass(q)) {
                if (cd.getName() != null) {
                    names.add(cd.getName());
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "分享面板 DexKit 父类查询不可用: " + t);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return names;
    }

    private java.util.List<String> probePanelNamesViaDexkit(ClassLoader cl) {
        java.util.List<String> names = new java.util.ArrayList<>();
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            System.loadLibrary("dexkit");
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPathForDexKit(cl));
            org.luckypray.dexkit.query.FindClass q = org.luckypray.dexkit.query.FindClass.create()
                    .searchPackages("com.max.hbcommon.component");
            for (org.luckypray.dexkit.result.ClassData cd : bridge.findClass(q)) {
                if (cd.getName() != null) {
                    names.add(cd.getName());
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "分享面板 DexKit 包枚举不可用: " + t);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return names;
    }

    private void autoClickChannel(final Dialog dialog) {
        final String channel = currentChannel();
        final int scheduledStep = currentStep;
        mainHandler.postDelayed(new Runnable() {
            private int attempt;

            @Override
            public void run() {
                try {
                    if (!autoActive || dialog == null || !dialog.isShowing()
                            || currentStep != scheduledStep) {
                        return;
                    }
                    ViewGroup root = dialog.getWindow() != null
                            && dialog.getWindow().getDecorView() instanceof ViewGroup
                            ? (ViewGroup) dialog.getWindow().getDecorView() : null;
                    ViewGroup container = findShareContainer(root, dialog.getContext());
                    if (container == null) {
                        boolean idResolved = shareContainerId(dialog.getContext()) != 0;
                        module.logd(idResolved ? Log.INFO : Log.WARN, module.TAG, idResolved
                                ? "每日任务：该弹窗不是分享面板（无 " + SHARE_CONTAINER_ID + "），不点击"
                                : "每日任务：解析不到渠道容器 " + SHARE_CONTAINER_ID
                                + "，为安全起见不点击，请反馈日志");
                        return;
                    }
                    ClickTarget target = findChannelTarget(container, channel);
                    if (target.view == null) {
                        if (++attempt < CHANNEL_CLICK_ATTEMPTS) {
                            mainHandler.postDelayed(this, CHANNEL_CLICK_RETRY_MS);
                        } else {
                            module.logd(Log.WARN, module.TAG,
                                    "分享面板未找到渠道按钮(" + channel + ")，跳过该步");
                        }
                        return;
                    }
                    if (!target.text.equals(firstCandidate(channel))) {
                        module.logd(Log.WARN, module.TAG, "每日任务：配置渠道 "
                                + channel + " 不在面板中，改用「" + target.text + "」完成本步");
                    }
                    module.logd(Log.INFO, module.TAG, "每日任务：自动点击分享面板「" + target.text
                            + "」渠道 (步骤 " + (currentStep + 1) + "/" + STEP_COUNT + ")");
                    target.view.performClick();
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "自动点渠道异常: " + t);
                }
            }
        }, 800L);
    }

    private static final class ClickTarget {
        final View view;
        final String text;

        ClickTarget(View view, String text) {
            this.view = view;
            this.text = text;
        }
    }

    private ClickTarget findChannelTarget(ViewGroup container, String channel) {
        for (String text : channelCandidates(channel)) {
            View view = findChannelView(container, text);
            if (view != null) {
                return new ClickTarget(view, text);
            }
        }
        return new ClickTarget(null, null);
    }

    private static java.util.List<String> channelCandidates(String channel) {
        java.util.List<String> out = new java.util.ArrayList<>();
        appendCandidates(out, channel);
        for (String fallback : CHANNEL_FALLBACK_ORDER) {
            appendCandidates(out, fallback);
        }
        return out;
    }

    private static void appendCandidates(java.util.List<String> out, String channel) {
        String[] texts = CHANNEL_VIEW_TEXTS.get(channel);
        if (texts == null) {
            texts = CHANNEL_VIEW_TEXTS.get("QQ");
        }
        for (String text : texts) {
            if (!out.contains(text)) {
                out.add(text);
            }
        }
    }

    private static String firstCandidate(String channel) {
        String[] texts = CHANNEL_VIEW_TEXTS.get(channel);
        if (texts == null || texts.length == 0) {
            texts = CHANNEL_VIEW_TEXTS.get("QQ");
        }
        return texts[0];
    }

    private static ViewGroup findShareContainer(View root, Context context) {
        if (root == null) {
            return null;
        }
        int id = shareContainerId(context);
        if (id == 0) {
            return null;
        }
        try {
            View container = root.findViewById(id);
            return container instanceof ViewGroup ? (ViewGroup) container : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int shareContainerId(Context context) {
        if (context == null) {
            return 0;
        }
        try {
            return context.getResources().getIdentifier(
                    SHARE_CONTAINER_ID, "id", MainModule.TARGET_PKG);
        } catch (Throwable t) {
            return 0;
        }
    }

    private String currentChannel() {
        String v = module.getString(App.KEY_SHARE_CHANNEL, "");
        return v == null || v.isEmpty() ? "QQ" : v;
    }

    private static View findChannelView(ViewGroup root, String targetText) {
        if (root == null || targetText == null) {
            return null;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                View found = findChannelView((ViewGroup) child, targetText);
                if (found != null) {
                    return found;
                }
            }
            if (child instanceof android.widget.TextView) {
                CharSequence text = ((android.widget.TextView) child).getText();
                if (text != null && targetText.equals(text.toString().trim())) {
                    View v = child;
                    while (v != null) {
                        if (v.isClickable()) {
                            return v;
                        }
                        if (!(v.getParent() instanceof View)) {
                            break;
                        }
                        v = (View) v.getParent();
                    }
                    return child;
                }
            }
        }
        return null;
    }
    private void hookTencentShareToQQ(ClassLoader cl) {
        try {
            Class<?> tencent = Class.forName("com.tencent.tauth.Tencent", false, cl);
            Class<?> iUiListener = Class.forName("com.tencent.tauth.IUiListener", false, cl);
            Method shareToQQ = ViewUtils.findMethod(tencent, "shareToQQ",
                    Activity.class, android.os.Bundle.class, iUiListener);
            if (shareToQQ == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 Tencent.shareToQQ(Activity,Bundle,IUiListener)");
                return;
            }
            final Method onComplete = iUiListener.getMethod("onComplete", Object.class);
            hookFakeShareSuccess(shareToQQ, "QQ", "Tencent.shareToQQ", 2, listener -> {
                org.json.JSONObject ret = new org.json.JSONObject();
                ret.put("ret", 0);
                onComplete.invoke(listener, ret);
            });
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ Tencent.shareToQQ Hook 失败", t);
        }
    }

    private void hookTencentShareToQzone(ClassLoader cl) {
        try {
            Class<?> tencent = Class.forName("com.tencent.tauth.Tencent", false, cl);
            Class<?> iUiListener = Class.forName("com.tencent.tauth.IUiListener", false, cl);
            Method shareToQzone = ViewUtils.findMethod(tencent, "shareToQzone",
                    Activity.class, android.os.Bundle.class, iUiListener);
            if (shareToQzone == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 Tencent.shareToQzone(Activity,Bundle,IUiListener)");
                return;
            }
            final Method onComplete = iUiListener.getMethod("onComplete", Object.class);
            hookFakeShareSuccess(shareToQzone, "QQ", "Tencent.shareToQzone", 2, listener -> {
                org.json.JSONObject ret = new org.json.JSONObject();
                ret.put("ret", 0);
                onComplete.invoke(listener, ret);
            });
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ Tencent.shareToQzone Hook 失败", t);
        }
    }

    private void hookUmengShareHandlers(ClassLoader cl) {
        Class<?> shareContent;
        Class<?> listener;
        Class<?> shareMedia;
        try {
            shareContent = Class.forName("com.umeng.socialize.ShareContent", false, cl);
            listener = Class.forName("com.umeng.socialize.UMShareListener", false, cl);
            shareMedia = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 未找到 UMeng 分享类，跳过全渠道拦截: " + t);
            return;
        }
        int installed = 0;
        for (String[] entry : UMENG_SHARE_HANDLERS) {
            final String className = entry[0];
            final String defaultMedia = entry[1];
            final String label = entry[2];
            try {
                Class<?> handler = Class.forName(className, false, cl);
                final Method share = ViewUtils.findMethod(handler, "share", shareContent, listener);
                if (share == null) {
                    module.logd(Log.WARN, module.TAG, "✘ 未找到 " + className
                            + ".share(ShareContent,UMShareListener)");
                    continue;
                }
                module.hook(share).intercept(chain -> {
                    if (!autoActive) {
                        return chain.proceed();
                    }
                    try {
                        fakeUmengShareSuccess(chain.getThisObject(), chain.getArg(1),
                                shareMedia, defaultMedia, label);
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, label + " 伪造回调异常: " + t);
                    }
                    mainHandler.post(() -> onStepCompleted(null));
                    return share.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
                installed++;
                module.logd(Log.INFO, module.TAG, "✔ 分享拦截已安装: " + className
                        + ".share (" + label + ")");
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "✘ " + className + " 分享拦截失败: " + t);
            }
        }
        if (installed == 0) {
            module.logd(Log.WARN, module.TAG, "✘ 未安装任何 UMeng 分享拦截，自动化可能触发真实分享");
        }
    }

    private void hookShareActionEntry(ClassLoader cl) {
        try {
            Class<?> shareAction = Class.forName("com.umeng.socialize.ShareAction", false, cl);
            Class<?> listenerType = Class.forName("com.umeng.socialize.UMShareListener", false, cl);
            Class<?> mediaType = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl);
            Method share = ViewUtils.findMethod(shareAction, "share");
            if (share == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 ShareAction.share()");
                return;
            }
            module.hook(share).intercept(chain -> {
                if (!autoActive) {
                    return chain.proceed();
                }
                Object self = chain.getThisObject();
                Object listener = readFieldByType(self, listenerType);
                Object media = readFieldByType(self, mediaType);
                if (listener == null || media == null) {
                    return chain.proceed();
                }
                try {
                    invokeShareSuccess(listener, media, "分享");
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "分享成功回调伪造异常: " + t);
                }
                mainHandler.post(() -> onStepCompleted(null));
                return null;
            });
            module.logd(Log.INFO, module.TAG, "✔ 分享出口拦截已安装: ShareAction.share()");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ ShareAction.share() 拦截失败: " + t);
        }
    }

    private void hookExternalJumps() {
        int installed = 0;
        try {
            for (Method method : android.app.Instrumentation.class.getDeclaredMethods()) {
                if (!"execStartActivity".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 7 || params[4] != Intent.class) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    if (!autoActive) {
                        return chain.proceed();
                    }
                    try {
                        java.util.List<Object> args = chain.getArgs();
                        if (args.size() > 4 && args.get(4) instanceof Intent) {
                            Intent intent = (Intent) args.get(4);
                            if (isExternalWebJump(intent)) {
                                String url = String.valueOf(intent.getData());
                                onAuxNavigationBlocked("外部跳转(" + callerHint() + ")", url);
                                return blockedReturn(method);
                            }
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "每日任务：外部跳转拦截异常: " + t);
                    }
                    return chain.proceed();
                });
                installed++;
            }
            module.logd(Log.INFO, module.TAG, "✔ 外部跳转拦截已安装: " + installed + " 个 execStartActivity");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 外部跳转拦截 Hook 失败", t);
        }
    }

    private void onAuxNavigationBlocked(String kind, String url) {
        boolean dead = isAppStoreJump(url);
        module.logd(Log.WARN, module.TAG, "每日任务：拦下" + kind + " " + url
                + (dead ? "（跳下载页，放弃本步）" : ""));
        if (dead) {
            abandonStepAsync("的链接跳到了应用商店/下载页");
        }
    }

    private void abandonStepAsync(String reason) {
        mainHandler.post(() -> {
            if (!autoActive) {
                return;
            }
            module.logd(Log.WARN, module.TAG, "每日任务：步骤 " + (currentStep + 1) + " " + reason
                    + "，跳过该步");
            advance(autoContext);
        });
    }

    private static boolean isExternalWebJump(Intent intent) {
        if (intent == null || intent.getComponent() != null) {
            return false;
        }
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        Uri data = intent.getData();
        if (data == null) {
            return false;
        }
        String scheme = data.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static Object blockedReturn(Method method) {
        Class<?> type = method.getReturnType();
        if (type == int.class) {
            return 0;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String callerHint() {
        try {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String name = frame.getClassName();
                if (name.startsWith("android.") || name.startsWith("java.")
                        || name.startsWith("com.better.heybox") || name.startsWith("de.robv")) {
                    continue;
                }
                return name + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private void hookWebViewNavigations() {
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
                    if (autoActive && arg instanceof String && isBlockedAuxNavigation((String) arg)) {
                        onAuxNavigationBlocked("网页容器加载", (String) arg);
                        return null;
                    }
                    return chain.proceed();
                });
                loads++;
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ loadUrl 看门狗 Hook 失败", t);
        }

        int clients = 0;
        try {
            Method setter = WebView.class.getDeclaredMethod("setWebViewClient", WebViewClient.class);
            module.hook(setter).intercept(chain -> {
                Object client = chain.getArg(0);
                if (client != null) {
                    hookWebClientClass(client.getClass());
                }
                return chain.proceed();
            });
            clients = 1;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ WebViewClient 看门狗 Hook 失败", t);
        }
        module.logd(Log.INFO, module.TAG, "✔ 网页跳转看门狗已安装（loadUrl " + loads
                + "，client " + clients + "）");
    }

    private final java.util.Set<Class<?>> hookedWebClients =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void hookWebClientClass(Class<?> clientClass) {
        if (clientClass == null || clientClass == WebViewClient.class) {
            return;
        }
        String name = clientClass.getName();
        if (name.startsWith("android.") || name.startsWith("androidx.")
                || name.startsWith("com.android.") || name.startsWith("org.chromium.")) {
            return;
        }
        synchronized (hookedWebClients) {
            if (!hookedWebClients.add(clientClass)) {
                return;
            }
        }
        for (Class<?> type = clientClass; type != null && type != Object.class;
             type = type.getSuperclass()) {
            if (type.getName().startsWith("android.")) {
                break;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!"shouldOverrideUrlLoading".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2 || params[0] != WebView.class
                        || method.getReturnType() != boolean.class) {
                    continue;
                }
                if (params[1] != String.class && params[1] != WebResourceRequest.class) {
                    continue;
                }
                try {
                    module.hook(method).intercept(chain -> {
                        if (!autoActive) {
                            return chain.proceed();
                        }
                        String url = extractNavigateUrl(chain.getArgs());
                        if (isBlockedAuxNavigation(url)) {
                            onAuxNavigationBlocked("网页跳转", url);
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "shouldOverrideUrlLoading 看门狗 Hook 失败: " + name, t);
                }
            }
        }
    }

    private static String extractNavigateUrl(java.util.List<Object> args) {
        if (args.size() < 2) {
            return null;
        }
        Object arg = args.get(1);
        if (arg instanceof String) {
            return (String) arg;
        }
        if (arg instanceof WebResourceRequest) {
            try {
                Uri uri = ((WebResourceRequest) arg).getUrl();
                return uri == null ? null : uri.toString();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static final String[] APP_STORE_HOST_MARKERS = {
            "a.app.qq.com", "app.qq.com", "sj.qq.com", "android.myapp.com",
            "myapp.com", "log.umsns.com",
    };

    private static final String[] BLOCKED_SCHEMES = {
            "weixin:", "mqqapi:", "mqqwpa:", "mqq:", "qqmusic:", "qzone:",
            "alipays:", "alipay:", "market:", "tmast:", "snssdk1128:", "snssdk2329:",
            "sinaweibo:", "sinawb:", "bilibili:", "taobao:", "pinduoduo:",
    };

    private static final String[] HEYBOX_SCHEMES = {
            "heybox:", "xiaoheihe:", "hbox:", "maxjia:",
    };

    private static boolean isAppStoreJump(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        for (String marker : APP_STORE_HOST_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return lower.contains("xiaoheihe") && lower.contains("download");
    }

    private static boolean isBlockedAuxNavigation(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return isAppStoreJump(lower);
        }
        if (lower.startsWith("intent:")) {
            return !(lower.contains("com.max.xiaoheihe")
                    || lower.contains("scheme=heybox") || lower.contains("scheme=xiaoheihe"));
        }
        for (String scheme : HEYBOX_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return false;
            }
        }
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("javascript:")
                || lower.startsWith("file:") || lower.startsWith("blob:") || lower.startsWith("content:")) {
            return false;
        }
        for (String scheme : BLOCKED_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return true;
            }
        }
        return false;
    }

    private void fakeUmengShareSuccess(Object handler, Object listener, Class<?> shareMedia,
                                       String defaultMedia, String label) throws Throwable {
        if (listener == null) {
            module.logd(Log.WARN, module.TAG, label + " 无分享监听，仅跳过真实分享");
            return;
        }
        Object media = resolveShareMedia(handler, shareMedia, defaultMedia);
        if (media == null) {
            module.logd(Log.WARN, module.TAG, label + " 无法解析 SHARE_MEDIA，仅跳过真实分享");
            return;
        }
        invokeShareSuccess(listener, media, label);
    }

    private void invokeShareSuccess(Object listener, Object media, String label) throws Throwable {
        Method onResult = findOnResult(listener.getClass(), media.getClass());
        if (onResult == null) {
            onResult = Class.forName("com.umeng.socialize.UMShareListener", false,
                    listener.getClass().getClassLoader()).getMethod("onResult", media.getClass());
        }
        onResult.setAccessible(true);
        onResult.invoke(listener, media);
        module.logd(Log.INFO, module.TAG, "✔ 每日任务：" + label + " 成功回调已触发 (步骤 "
                + (currentStep + 1) + "/" + STEP_COUNT + ")");
        warnChannelMismatch(enumName(media), label);
    }

    private static String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    private static Object readFieldByType(Object target, Class<?> type) {
        if (target == null || type == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object resolveShareMedia(Object handler, Class<?> shareMedia, String defaultMedia) {
        if (handler != null && shareMedia != null) {
            for (Class<?> type = handler.getClass(); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField("mTarget");
                    field.setAccessible(true);
                    Object value = field.get(handler);
                    if (shareMedia.isInstance(value)) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (shareMedia == null || defaultMedia == null) {
            return null;
        }
        try {
            return Enum.valueOf((Class<Enum>) shareMedia, defaultMedia);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findOnResult(Class<?> type, Class<?> mediaType) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if ("onResult".equals(method.getName()) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(mediaType)) {
                    return method;
                }
            }
            for (Class<?> iface : current.getInterfaces()) {
                try {
                    return iface.getMethod("onResult", mediaType);
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return null;
    }

    private void warnChannelMismatch(String mediaName, String label) {
        String configured = currentChannel();
        String actual = channelKeyOf(mediaName);
        if (actual != null && !actual.equals(configured)) {
            module.logd(Log.WARN, module.TAG, "每日任务：实际走的是 " + label + "（设置渠道 "
                    + configured + "），已按成功处理");
        }
    }

    private static String channelKeyOf(String mediaName) {
        if (mediaName == null) {
            return null;
        }
        if ("QQ".equals(mediaName) || "QZONE".equals(mediaName)) {
            return "QQ";
        }
        if ("WEIXIN".equals(mediaName) || "WEIXIN_CIRCLE".equals(mediaName)) {
            return "WECHAT";
        }
        if ("SINA".equals(mediaName)) {
            return "WEIBO";
        }
        return null;
    }

    private void hookFakeShareSuccess(Method share, String channel, String logLabel,
                                      int listenerArg, FakeShareInvoker fake) {
        module.hook(share).intercept(chain -> {
            if (!autoActive) {
                return chain.proceed();
            }
            try {
                Object listener = chain.getArg(listenerArg);
                if (listener != null) {
                    fake.invoke(listener);
                    module.logd(Log.INFO, module.TAG, "✔ 每日任务：" + logLabel
                            + " 成功回调已触发 (步骤 " + (currentStep + 1) + "/" + STEP_COUNT + ")");
                    warnChannelMismatch(channel, logLabel);
                }
                Object ctx = chain.getArg(0);
                Context context = ctx instanceof Context ? (Context) ctx : null;
                mainHandler.post(() -> onStepCompleted(context));
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, logLabel + " 伪造回调异常: " + t);
            }
            return share.getReturnType() == boolean.class ? Boolean.TRUE : null;
        });
    }

    private interface FakeShareInvoker {
        void invoke(Object listener) throws Throwable;
    }
    private void hookMainResume(ClassLoader cl) {
        try {
            Class<?> mainActivity = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onResume = mainActivity.getMethod("onResume");
            module.hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        maybeStartDailyTask((Activity) self);
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "每日任务启动检查异常: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 每日任务入口 Hook 失败", t);
        }
    }

    private void maybeStartDailyTask(Activity activity) {
        if (autoActive) {
            return;
        }
        if (!module.isEnabled(App.KEY_DAILY_TASK_ENABLED, false)) {
            return;
        }
        ModuleStats.dailyTaskResumeChecks.incrementAndGet();
        handleResetFlag();
        if (isTodayDone()) {
            return;
        }
        if (!hasAnyLink()) {
            ModuleStats.dailyTaskNoLink.incrementAndGet();
            long now = SystemClock.uptimeMillis();
            if (now - lastNoLinkLogAt >= NO_LINK_LOG_INTERVAL_MS) {
                lastNoLinkLogAt = now;
                module.logd(Log.WARN, module.TAG, "每日任务：未配置分享链接（帖子/游戏详情/游戏评价）");
            }
            return;
        }
        autoActive = true;
        autoContext = activity.getApplicationContext();
        currentStep = STEP_PICTURE;
        stepTriggered = false;
        module.logd(Log.INFO, module.TAG, "每日任务启动（3 种分享类型：图片帖→普通帖→频道）");
        openStep(activity, STEP_PICTURE);
    }
    private void hookSharePages(ClassLoader cl) {
        try {
            Class<?> titleBar = Class.forName("com.max.hbcommon.component.TitleBar", false, cl);
            hookActionIconSetter(cl, titleBar);
            hookTitleBarSetter(cl, titleBar, "setActionIconOnClickListener",
                    "iv_appbar_action_button", STEP_PICTURE, STEP_NORMAL, STEP_CHANNEL);
            hookTitleBarSetter(cl, titleBar, "setActionMoreIconOnClickListener",
                    "iv_appbar_action_button_more", STEP_NORMAL, STEP_CHANNEL);
            module.logd(Log.INFO, module.TAG, "✔ 分享按钮 Hook 已安装（TitleBar setter）");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 分享按钮 Hook 失败", t);
        }
    }
    private void hookActionIconSetter(ClassLoader cl, Class<?> titleBar) {
        try {
            Method intSetter = titleBar.getMethod("setActionIcon", int.class);
            module.hook(intSetter).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    int resId = (Integer) chain.getArg(0);
                    if (self instanceof View && resId != 0) {
                        String name = ((View) self).getResources()
                                .getResourceEntryName(resId);
                        actionIcons.put(self, name);
                        if (autoActive) {
                            module.logd(Log.INFO, module.TAG,
                                    "每日任务：setActionIcon 记录 " + name);
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            for (Method m : titleBar.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if ("setActionIcon".equals(m.getName()) && pts.length == 1
                        && pts[0] != int.class) {
                    module.hook(m).intercept(chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (self instanceof View) {
                                actionIcons.put(self, "");
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    break;
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ setActionIcon 记录 Hook 失败: " + t);
        }
    }
    private void hookTitleBarSetter(final ClassLoader cl, final Class<?> titleBar,
                                    final String setterName, final String viewName,
                                    final int... allowedSteps) {
        try {
            Method setter = titleBar.getMethod(setterName, View.OnClickListener.class);
            module.hook(setter).intercept(chain -> {
                Object result = chain.proceed();
                if (!autoActive) {
                    return result;
                }
                boolean allowed = false;
                for (int s : allowedSteps) {
                    if (currentStep == s) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    return result;
                }
                Object selfObj = chain.getThisObject();
                if ("setActionIconOnClickListener".equals(setterName)
                        && isMessageIconPage(selfObj)) {
                    module.logd(Log.INFO, module.TAG,
                            "每日任务：该页 action 图标是消息入口，跳过不点");
                    return result;
                }
                try {
                    Object self = chain.getThisObject();
                    if (self == null) {
                        return result;
                    }
                    Context ctx = null;
                    if (self instanceof View) {
                        ctx = ((View) self).getContext();
                    }
                    if (ctx == null && self instanceof Context) {
                        ctx = (Context) self;
                    }
                    if (ctx instanceof Activity) {
                        final Activity act = (Activity) ctx;
                        final Object titleBarObj = self;
                        final Object listener = chain.getArg(0);
                        final int scheduledStep = currentStep;
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (!autoActive || listener == null) {
                                        return;
                                    }

                                    if (currentStep != scheduledStep) {
                                        return;
                                    }

                                    if (stepTriggered) {
                                        return;
                                    }
                                    stepTriggered = true;

                                    View.OnClickListener l = (View.OnClickListener) listener;
                                    module.logd(Log.INFO, module.TAG, "每日任务：自动触发 "
                                            + viewName + " 分享 (步骤 " + (currentStep + 1)
                                            + "/" + STEP_COUNT + ") 页面="
                                            + act.getClass().getSimpleName());
                                    clickShareButton(act, titleBar, titleBarObj, viewName, l);
                                } catch (Throwable t2) {
                                    module.logd(Log.WARN, module.TAG, "自动触发分享异常: " + t2);
                                }
                            }
                        }, 1200L);
                    } else {
                        module.logd(Log.WARN, module.TAG,
                                "TitleBar context 不是 Activity: " + (ctx == null ? "null" : ctx.getClass().getName()));
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "分享按钮调度异常: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ TitleBar " + setterName + " Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ TitleBar " + setterName + " Hook 失败", t);
        }
    }
    private static void clickShareButton(Activity act, Class<?> titleBar, Object titleBarObj,
                                         String viewName, View.OnClickListener l) {
        int btnId = act.getResources().getIdentifier(viewName, "id", MainModule.TARGET_PKG);
        View btn = btnId == 0 ? null : act.findViewById(btnId);
        if (btn != null) {
            l.onClick(btn);
            return;
        }
        try {
            Method getView = titleBar.getMethod("getAppbarActionButtonView");
            Object v = getView.invoke(titleBarObj);
            if (v instanceof View) {
                l.onClick((View) v);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isMessageIconPage(Object titleBar) {
        Activity act = ViewUtils.findActivity(titleBar instanceof View ? (View) titleBar : null);
        if (act != null && "com.max.xiaoheihe.module.bbs.ChannelsDetailActivity"
                .equals(act.getClass().getName())) {
            return true;
        }
        return titleBar != null && "common_notice".equals(actionIcons.get(titleBar));
    }

    private void onStepCompleted(Context context) {
        if (!autoActive) {
            return;
        }
        int done = currentStep;
        if (done == completedStep) {
            module.logd(Log.INFO, module.TAG, "每日任务：步骤 " + (done + 1)
                    + " 重复回调，忽略");
            return;
        }
        completedStep = done;
        module.logd(Log.INFO, module.TAG, "每日任务：步骤 " + (done + 1) + "/" + STEP_COUNT + " 完成 ("
                + stepName(done) + ")");
        advance(context);
    }
    private void advance(Context context) {
        if (!autoActive) {
            return;
        }
        int next = currentStep + 1;
        if (next < STEP_COUNT) {
            currentStep = next;
            stepTriggered = false;
            Context ctx = context != null ? context : autoContext;
            if (ctx != null) {
                openStep(ctx, next);
            }
        } else {
            finishDailyTask(context);
        }
    }

    private void openStep(Context context, int step) {
        String link = getLinkForStep(step);
        if (link == null || link.isEmpty()) {
            module.logd(Log.INFO, module.TAG, "每日任务：步骤 " + (step + 1) + "（"
                    + stepName(step) + "）未配置，跳过");
            advance(context);
            return;
        }
        scheduleStepTimeout(step);
        ClassLoader cl = targetCl != null ? targetCl
                : (context != null ? context.getClassLoader() : null);
        try {
            Class<?> router = Class.forName("com.max.xiaoheihe.RouterActivity", false, cl);
            Intent intent = new Intent(context, router)
                    .setData(Uri.parse(link.trim()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            module.logd(Log.INFO, module.TAG, "每日任务：打开 " + stepName(step) + ": " + link.trim());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "RouterActivity 打开失败，尝试 link_id 直开: " + t);
            String linkId = extractLinkId(link);
            if (linkId == null) {
                module.logd(Log.WARN, module.TAG, "无法解析 link_id，跳过该步");
                advance(context);
                return;
            }
            try {
                Class<?> normalPage = Class.forName(
                        "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
                        false, cl);
                Intent intent = new Intent(context, normalPage)
                        .putExtra("link_id", linkId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable t2) {
                module.logd(Log.ERROR, module.TAG, "帖子打开失败", t2);
                abortDailyTask();
            }
        }
    }
    private void scheduleStepTimeout(final int step) {
        final long token = ++stepToken;
        mainHandler.postDelayed(() -> {
            if (!autoActive || stepToken != token || currentStep != step) {
                return;
            }
            module.logd(Log.WARN, module.TAG, "每日任务：步骤 " + (step + 1) + "（"
                    + stepName(step) + "）" + (STEP_TIMEOUT_MS / 1000) + "s 内没走完，跳过该步");
            advance(autoContext);
        }, STEP_TIMEOUT_MS);
    }

    private void abortDailyTask() {
        reset();
        module.logd(Log.WARN, module.TAG,
                "每日任务：打开帖子失败，已复位（今日未标记完成，下次进入主页将重试）");
    }
    public void clearTodayAndRetry(Activity activity) {
        reset();
        clearDoneDate();
        module.logd(Log.INFO, module.TAG, "已清除今日打卡状态，重新尝试每日任务");
        if (activity != null) {
            maybeStartDailyTask(activity);
        }
    }
    private void handleResetFlag() {
        if (!module.isEnabled(App.KEY_DAILY_TASK_RESET, false)) {
            return;
        }
        clearDoneDate();
        HeyboxPrefs.setBoolean(App.KEY_DAILY_TASK_RESET, false);
        try {
            SharedPreferences remote = module.getRemotePreferences(App.PREFS_GROUP);
            if (remote != null) {
                remote.edit().remove(App.KEY_DAILY_TASK_RESET).apply();
            }
        } catch (Throwable ignored) {
        }
        module.logd(Log.INFO, module.TAG, "检测到清除今日打卡标志，已重置完成状态");
    }
    private void clearDoneDate() {
        writeDoneDate("");
    }

    private void reset() {
        autoActive = false;
        currentStep = -1;
        stepTriggered = false;
        completedStep = -1;
        stepToken++;
    }

    private void writeDoneDate(String value) {
        HeyboxPrefs.setString(App.KEY_DAILY_TASK_DONE_DATE, value);
        try {
            SharedPreferences remote = module.getRemotePreferences(App.PREFS_GROUP);
            if (remote != null) {
                if (value == null || value.isEmpty()) {
                    remote.edit().remove(App.KEY_DAILY_TASK_DONE_DATE).apply();
                } else {
                    remote.edit().putString(App.KEY_DAILY_TASK_DONE_DATE, value).apply();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void finishDailyTask(Context context) {
        reset();
        writeDoneDate(today());
        module.logd(Log.INFO, module.TAG, "每日任务：3 种分享类型全部完成，已记录今日状态");
        Context ctx = context != null ? context : autoContext;
        if (ctx == null) {
            return;
        }
        try {
            Toast.makeText(ctx.getApplicationContext(),
                    "每日分享任务已完成", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
        if (module.isEnabled(App.KEY_DAILY_TASK_BACK_HOME, true)) {
            mainHandler.postDelayed(() -> backToHome(ctx), 800L);
        }
    }

    private void backToHome(Context context) {
        try {
            ClassLoader cl = targetCl != null ? targetCl : context.getClassLoader();
            Class<?> main = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Intent intent = new Intent(context, main)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
            module.logd(Log.INFO, module.TAG, "每日任务：已自动退回首页");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "每日任务：退回首页失败: " + t);
        }
    }
    private String getLinkForStep(int step) {
        if (step < 0 || step >= STEP_KEYS.length) {
            return null;
        }
        String value = module.getString(STEP_KEYS[step], "");
        return value == null ? null : value.trim();
    }

    private boolean hasAnyLink() {
        return !getLinkForStep(STEP_PICTURE).isEmpty()
                || !getLinkForStep(STEP_NORMAL).isEmpty()
                || !getLinkForStep(STEP_CHANNEL).isEmpty();
    }

    private static String stepName(int step) {
        return step >= 0 && step < STEP_NAMES.length ? STEP_NAMES[step] : "未知";
    }

    private boolean isTodayDone() {
        if (today().equals(HeyboxPrefs.getString(App.KEY_DAILY_TASK_DONE_DATE, ""))) {
            return true;
        }
        try {
            SharedPreferences remote = module.getRemotePreferences(App.PREFS_GROUP);
            if (remote != null && today().equals(
                    remote.getString(App.KEY_DAILY_TASK_DONE_DATE, ""))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String extractLinkId(String link) {
        try {
            Uri uri = Uri.parse(link);
            String id = uri.getQueryParameter("link_id");
            if (id != null && !id.isEmpty()) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        try {
            int idx = link.indexOf("link_id=");
            if (idx >= 0) {
                String v = link.substring(idx + 8);
                int end = v.indexOf('&');
                if (end > 0) {
                    v = v.substring(0, end);
                }
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
