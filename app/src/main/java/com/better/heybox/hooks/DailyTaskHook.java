package com.better.heybox.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
import com.better.heybox.ViewUtils;

/**
 * 每日任务自动化：自动完成小黑盒每日 3 种分享任务（帖子 / 游戏详情 / 游戏评价），
 * 链接在设置中分别配置。Hook 分享面板与各 SDK 分享入口，自动化进行中直接触发
 * 成功回调跳过真实 SDK；只触发任务自身回调，不影响用户手动分享。
 */
public final class DailyTaskHook {

    /** 3 种分享步骤 */
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
        CHANNEL_VIEW_TEXTS.put("QQ", new String[]{"QQ"});
    }

    private final MainModule module;

    /** 宿主进程 classloader（打开页面用） */
    private volatile ClassLoader targetCl;

    private volatile boolean autoActive;
    private volatile int currentStep = -1;
    /** 本步是否已自动触发过（防重复触发） */
    private volatile boolean stepTriggered;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 缓存的 applicationContext（微信/微博回调拿不到 Context 时兜底） */
    private volatile Context autoContext;

    /**
     * TitleBar 当前 action 图标资源名：同一 setter 在不同页面语义不同
     * （帖子页为分享按钮，游戏详情页为消息入口），点之前先看图标防误触。
     */
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
        hookWeChatShare(cl);
        hookSinaShare(cl);
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
    private void hookSharePanel(ClassLoader cl) {
        try {
            Class<?> panel = Class.forName("com.max.hbcommon.component.i", false, cl);
            Method show = panel.getMethod("show");
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
            module.logd(Log.INFO, module.TAG, "✔ 分享面板 Hook 已安装: component.i.show()");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 分享面板 Hook 失败", t);
        }
    }

    private void autoClickChannel(final Dialog dialog) {
        final String channel = currentChannel();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!autoActive || dialog == null || !dialog.isShowing()) {
                        return;
                    }
                    ViewGroup root = dialog.getWindow() != null
                            && dialog.getWindow().getDecorView() instanceof ViewGroup
                            ? (ViewGroup) dialog.getWindow().getDecorView() : null;
                    String[] candidates = CHANNEL_VIEW_TEXTS.get(channel);
                    if (candidates == null) {
                        candidates = CHANNEL_VIEW_TEXTS.get("QQ");
                    }
                    View target = null;
                    for (String text : candidates) {
                        target = findChannelView(root, text);
                        if (target != null) {
                            break;
                        }
                    }
                    if (target == null) {
                        module.logd(Log.WARN, module.TAG, "分享面板未找到渠道按钮(" + channel + ")，跳过该步");
                        return;
                    }
                    module.logd(Log.INFO, module.TAG, "每日任务：自动点击分享面板 " + channel + " 渠道 (步骤 "
                            + (currentStep + 1) + "/" + STEP_COUNT + ")");
                    target.performClick();
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "自动点渠道异常: " + t);
                }
            }
        }, 800L);
    }

    /** 当前配置的分享渠道（默认 QQ） */
    private String currentChannel() {
        String v = module.getString(App.KEY_SHARE_CHANNEL, "");
        return v == null || v.isEmpty() ? "QQ" : v;
    }

    /** 递归查文本为目标渠道名的可点击 View */
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

    /** 微信分享经 UMWXHandler.share；自动化中直接触发 onResult(WEIXIN) 跳过真实微信 SDK */
    private void hookWeChatShare(ClassLoader cl) {
        try {
            Class<?> handler = Class.forName("com.umeng.socialize.handler.UMWXHandler", false, cl);
            Class<?> listener = Class.forName("com.umeng.socialize.UMShareListener", false, cl);
            Class<?> shareContent = Class.forName("com.umeng.socialize.ShareContent", false, cl);
            Method share = ViewUtils.findMethod(handler, "share", shareContent, listener);
            if (share == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 UMWXHandler.share(ShareContent,UMShareListener)");
                return;
            }
            final Method onResult = listener.getMethod("onResult",
                    Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl));
            final Object weixin = Enum.valueOf(
                    (Class<Enum>) Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl),
                    "WEIXIN");
            hookFakeShareSuccess(share, "WECHAT", "微信分享", 1, l -> onResult.invoke(l, weixin));
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 微信分享 Hook 失败", t);
        }
    }

    /** 微博分享经 SinaSsoHandler.share；自动化中直接触发 onResult(SINA) 跳过真实微博 SDK */
    private void hookSinaShare(ClassLoader cl) {
        try {
            Class<?> handler = Class.forName("com.umeng.socialize.handler.SinaSsoHandler", false, cl);
            Class<?> listener = Class.forName("com.umeng.socialize.UMShareListener", false, cl);
            Class<?> shareContent = Class.forName("com.umeng.socialize.ShareContent", false, cl);
            Class<?> shareMedia = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl);
            Method share = ViewUtils.findMethod(handler, "share", shareContent, listener);
            if (share == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 SinaSsoHandler.share(ShareContent,UMShareListener)");
                return;
            }
            final Method onResult = listener.getMethod("onResult", shareMedia);
            final Object sina = Enum.valueOf((Class<Enum>) shareMedia, "SINA");
            hookFakeShareSuccess(share, "WEIBO", "微博分享", 1, l -> onResult.invoke(l, sina));
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 微博分享 Hook 失败", t);
        }
    }

    /** 返回语义按被 hook 方法：void→null、boolean→TRUE */
    private void hookFakeShareSuccess(Method share, String channel, String logLabel,
                                      int listenerArg, FakeShareInvoker fake) {
        module.hook(share).intercept(chain -> {
            if (!autoActive || !channel.equals(currentChannel())) {
                return chain.proceed();
            }
            try {
                Object listener = chain.getArg(listenerArg);
                if (listener != null) {
                    fake.invoke(listener);
                    module.logd(Log.INFO, module.TAG, "✔ 每日任务：" + logLabel
                            + " 成功回调已触发 (步骤 " + (currentStep + 1) + "/" + STEP_COUNT + ")");
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
        handleResetFlag();
        if (isTodayDone()) {
            return;
        }
        if (!hasAnyLink()) {
            module.logd(Log.WARN, module.TAG, "每日任务：未配置分享链接（帖子/游戏详情/游戏评价）");
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
        // 微信/微博回调 context 为 null，用缓存兜底
        Context ctx = context != null ? context : autoContext;
        if (ctx != null) {
            try {
                Toast.makeText(ctx.getApplicationContext(),
                        "每日分享任务已完成", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
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
