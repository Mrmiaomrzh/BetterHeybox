package com.better.heybox.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.ModuleStats;
import com.better.heybox.ViewUtils;
import com.better.heybox.task.MyPostIdCatcher;
import com.better.heybox.task.OwnPostClient;
import com.better.heybox.task.TaskItem;
import com.better.heybox.task.TaskListClient;
import com.better.heybox.task.TaskMatcher;
import com.better.heybox.watch.HttpBridge;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MyTaskHook {

    private static final int S_IDLE = 0;
    private static final int S_READ = 1;
    private static final int S_OPEN = 2;
    private static final int S_FILL = 3;
    private static final int S_PUBLISH = 4;
    private static final int S_WAIT_ID = 5;
    private static final int S_VERIFY = 6;
    private static final int S_CLAIM = 7;
    private static final int S_DELETE = 8;

    private static final String[] STAGE_NAMES = {
            "空闲", "读任务列表", "打开任务入口", "填正文", "发布", "等新帖", "核对任务",
            "领奖", "删帖", "完成"
    };

    private static final long OPEN_TIMEOUT_MS = 45_000L;
    private static final long FILL_TIMEOUT_MS = 25_000L;
    private static final long WAIT_PUBLISH_MS = 4 * 60_000L;
    private static final long POST_ID_TIMEOUT_MS = 60_000L;
    private static final long VERIFY_TIMEOUT_MS = 20_000L;
    private static final int VERIFY_MAX_ROUNDS = 3;
    private static final long CLAIM_SETTLE_MS = 3_000L;
    private static final long DELETE_TIMEOUT_MS = 60_000L;
    private static final long TICK_MS = 800L;
    private static final int DELETE_MAX_TICKS = 30;
    private static final long ENTRY_TICK_MS = 1_200L;
    private static final int OPEN_MAX_TICKS = 24;
    private static final int ENTRY_MAX_CLICKS = 2;
    private static final long NEWPOST_POLL_MS = 3_000L;
    private static final int NEWPOST_MAX_POLLS = 20;
    private static final long RUN_COOLDOWN_MS = 3 * 60_000L;
    private static final long RETRY_COOLDOWN_MS = 90_000L;
    private static final int MAX_DIALOGS = 8;

    private static final String[] DELETE_TEXTS = {"删除此内容", "删除"};
    private static final String[] CONFIRM_TEXTS = {"确定", "确认"};

    private static final String[] ENTRY_TEXTS = {
            "参与讨论", "去讨论", "开始讨论", "参与话题", "去参与",
            "写点什么", "说点什么", "我要发帖", "去发帖", "发帖", "发表内容",
    };

    private final MainModule module;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "betterheybox-mytask");
        t.setDaemon(true);
        return t;
    });

    private volatile ClassLoader targetCl;

    private volatile boolean running;
    private volatile int stage = S_IDLE;
    private volatile long stageToken;
    private volatile TaskItem target;
    private volatile String targetLinkId = "";
    private volatile String startState = "";
    private volatile int verifyRounds;
    private volatile int deleteTicks;
    private volatile boolean clickedDeleteItem;
    private volatile boolean clickedConfirm;
    private volatile long lastRunAt;
    private volatile long lastBusyLogAt;
    private volatile long runStartedAt;
    private volatile int openTicks;
    private volatile int entryClicks;
    private volatile int newPostPolls;
    private volatile boolean textsDumped;
    private volatile String lastPageSummary = "";

    private volatile Object publishBar;
    private volatile Object publishListener;
    private volatile Object moreBar;
    private volatile Object moreListener;
    private volatile Activity currentActivity;

    private final List<WeakReference<Object>> dialogs = new ArrayList<>();

    public MyTaskHook(MainModule module) {
        this.module = module;
    }


    public void install(ClassLoader cl) {
        this.targetCl = cl;
        TaskListClient.init(module);
        HttpBridge.init(module);
        HttpBridge.installCapture(module, cl);
        MyPostIdCatcher.register(App.resolveAppContext(), module, linkId -> onPostCreated(linkId));
        hookMainResume(cl);
        hookTitleBar(cl);
        hookDialogs(cl);
        hookPostPage(cl);
        module.logd(Log.INFO, module.TAG, "✔ 我的任务 Hook 安装完成");
    }

    private void hookMainResume(ClassLoader cl) {
        try {
            Class<?> mainCls = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onResume = ViewUtils.findMethod(mainCls, "onResume");
            if (onResume == null) {
                throw new NoSuchMethodException("MainActivity#onResume");
            }
            module.hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    Activity act = (Activity) self;
                    main.post(() -> maybeRun(act));
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 我的任务：MainActivity#onResume Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "我的任务：onResume Hook 失败（只能手动点「立即执行」）: " + t);
        }
    }

    private void hookTitleBar(ClassLoader cl) {
        try {
            Class<?> titleBar = Class.forName("com.max.hbcommon.component.TitleBar", false, cl);
            captureClickSetter(titleBar, "setActionOnClickListener", true);
            captureClickSetter(titleBar, "setActionIconOnClickListener", true);
            captureClickSetter(titleBar, "setActionMoreIconOnClickListener", false);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "我的任务：TitleBar Hook 失败（无法自动点发布/更多）: " + t);
        }
    }

    private void captureClickSetter(Class<?> titleBar, String setterName, boolean isPublish) {
        try {
            Method setter = titleBar.getMethod(setterName, View.OnClickListener.class);
            module.hook(setter).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    Object listener = chain.getArg(0);
                    if (!(self instanceof View) || listener == null) {
                        return result;
                    }
                    Activity act = ViewUtils.findActivity((View) self);
                    if (act == null) {
                        return result;
                    }
                    if (isPublish) {
                        publishBar = self;
                        publishListener = listener;
                        if (running && stage != S_DELETE) {
                            onPublishActionReady(act);
                        }
                    } else {
                        moreBar = self;
                        moreListener = listener;
                        if (running && stage == S_DELETE) {
                            clickMoreSoon(act);
                        }
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 我的任务：TitleBar#" + setterName + " Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "我的任务：TitleBar#" + setterName + " Hook 失败: " + t);
        }
    }

    private void onPublishActionReady(Activity act) {
        if (stage != S_OPEN && stage != S_FILL) {
            return;
        }
        if (!looksLikeEditor(act)) {
            logThrottled("页面 " + act.getClass().getSimpleName() + " 不是发帖页，等真正的编辑器");
            return;
        }
        onEditorReady(act);
    }

    private void onEditorReady(Activity act) {
        if (!running || stage != S_OPEN && stage != S_FILL) {
            return;
        }
        if (stage == S_OPEN) {
            setStage(S_FILL);
            schedule(FILL_TIMEOUT_MS, "发帖页的「发布」按钮没就绪");
        }
        main.postDelayed(() -> doFill(act), 1_200L);
    }

    private boolean looksLikeEditor(Activity act) {
        if (act == null) {
            return false;
        }
        String name = act.getClass().getName();
        if (name.contains("post_edit") || name.contains("PostEdit") || name.contains("PostTab")) {
            return true;
        }
        String publishLabel = MainModule.getHeyboxTabLabel(act, "post", "发布");
        View v = actionTextView(publishBar);
        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            if (text != null && publishLabel.equals(text.toString().trim())) {
                return true;
            }
        }
        return false;
    }

    private void hookDialogs(ClassLoader cl) {
        try {
            Class<?> popup = Class.forName("com.max.hbcommon.component.HeyBoxPopupMenu", false, cl);
            Method show = popup.getMethod("show");
            module.hook(show).intercept(chain -> {
                Object result = chain.proceed();
                rememberDialog(chain.getThisObject());
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 我的任务：HeyBoxPopupMenu#show Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "我的任务：HeyBoxPopupMenu Hook 失败（删帖将只能靠帖子页菜单）: " + t);
        }
        try {
            Method show = Dialog.class.getMethod("show");
            module.hook(show).intercept(chain -> {
                Object result = chain.proceed();
                if (running) {
                    rememberDialog(chain.getThisObject());
                }
                return result;
            });
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "我的任务：Dialog#show Hook 失败: " + t);
        }
        for (String m : new String[]{"showAsDropDown", "showAtLocation", "show"}) {
            try {
                Method show = PopupWindow.class.getMethod(m,
                        m.equals("showAtLocation")
                                ? new Class<?>[]{View.class, int.class, int.class}
                                : m.equals("showAsDropDown")
                                        ? new Class<?>[]{View.class}
                                        : new Class<?>[0]);
                module.hook(show).intercept(chain -> {
                    Object result = chain.proceed();
                    if (running) {
                        try {
                            Object v = chain.getThisObject().getClass()
                                    .getMethod("getContentView").invoke(chain.getThisObject());
                            rememberDialog(v);
                        } catch (Throwable ignored) {
                        }
                    }
                    return result;
                });
                module.logd(Log.INFO, module.TAG, "✔ 我的任务：PopupWindow#" + m + " Hook 已安装");
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    private void rememberDialog(Object dialog) {
        if (dialog == null) {
            return;
        }
        synchronized (dialogs) {
            dialogs.add(0, new WeakReference<>(dialog));
            while (dialogs.size() > MAX_DIALOGS) {
                dialogs.remove(dialogs.size() - 1);
            }
        }
    }

    private void hookPostPage(ClassLoader cl) {
        String[] pages = {
                "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
                "com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.BasePostPageActivityV2",
        };
        for (String cn : pages) {
            try {
                Class<?> page = Class.forName(cn, false, cl);
                Method onCreate = ViewUtils.findMethod(page, "onCreate", android.os.Bundle.class);
                if (onCreate == null) {
                    continue;
                }
                module.hook(onCreate).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Intent intent = chain.getArg(0) instanceof Intent ? (Intent) chain.getArg(0) : null;
                        if (intent != null) {
                            String id = extraLinkId(intent);
                            if (!id.isEmpty()) {
                                MyPostIdCatcher.report(id, "帖子页 " + cn.substring(cn.lastIndexOf('.') + 1));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
                module.logd(Log.INFO, module.TAG, "✔ 我的任务：帖子页 link_id 兜底 Hook 已安装 (" + cn + ")");
            } catch (Throwable ignored) {
            }
        }
    }

    private static String extraLinkId(Intent intent) {
        try {
            android.os.Bundle extras = intent.getExtras();
            if (extras == null) {
                return "";
            }
            Object v = extras.get("link_id");
            if (v == null) {
                v = extras.get("linkid");
            }
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }


    private boolean enabled() {
        return module.isEnabled(App.KEY_MY_TASK_ENABLED, false);
    }

    private void maybeRun(Activity activity) {
        if (!enabled()) {
            return;
        }
        ModuleStats.myTaskResumeChecks.incrementAndGet();
        if (running || activity == null || activity.isFinishing()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastRunAt < RUN_COOLDOWN_MS) {
            return;
        }
        String uid = HttpBridge.hostUserId();
        if (uid == null || uid.isEmpty()) {
            logThrottled("未登录，跳过我的任务");
            return;
        }
        if (DailyTaskHook.isAutoActive()) {
            logThrottled("每日分享任务进行中，本轮跳过我的任务（避免两个自动化抢屏）");
            return;
        }
        if (doneCount() >= maxPerDay()) {
            logThrottled("今日已完成 " + doneCount() + "/" + maxPerDay() + " 条，达到每日上限");
            return;
        }
        startRun(activity, "自动检查");
    }

    public void runNow(Activity activity) {
        if (activity == null) {
            return;
        }
        if (running) {
            toast("我的任务正在执行中");
            return;
        }
        startRun(activity, "手动触发");
    }

    private void startRun(Activity activity, String reason) {
        running = true;
        target = null;
        targetLinkId = "";
        startState = "";
        verifyRounds = 0;
        deleteTicks = 0;
        clickedDeleteItem = false;
        clickedConfirm = false;
        openTicks = 0;
        entryClicks = 0;
        newPostPolls = 0;
        textsDumped = false;
        lastPageSummary = "";
        currentActivity = activity;
        lastRunAt = SystemClock.uptimeMillis();
        runStartedAt = System.currentTimeMillis();
        ModuleStats.myTaskRuns.incrementAndGet();
        setStage(S_READ);
        schedule(OPEN_TIMEOUT_MS + FILL_TIMEOUT_MS + WAIT_PUBLISH_MS + POST_ID_TIMEOUT_MS,
                "整轮超时，已复位");
        log(Log.INFO, "我的任务：开始检查（" + reason + "），宿主客户端 "
                + (HttpBridge.ready() ? "已就绪" : "未就绪"));
        io.execute(() -> {
            List<TaskItem> items = TaskListClient.fetch();
            String err = TaskListClient.lastError();
            main.post(() -> onListLoaded(items, err));
        });
    }

    private void onListLoaded(List<TaskItem> items, String err) {
        if (!running) {
            return;
        }
        if (items == null || items.isEmpty()) {
            ModuleStats.myTaskReadFail.incrementAndGet();
            stop("未读到任务列表" + (err == null || err.isEmpty() ? "" : "：" + err), false);
            return;
        }
        TaskListClient.logAll(module, items);
        TaskMatcher.Match match = TaskMatcher.pick(items, urgentOnly(), keywords(),
                doneFingerprints(), doneCount(), maxPerDay());
        if (match.task == null) {
            ModuleStats.myTaskNoMatch.incrementAndGet();
            log(Log.INFO, "我的任务：没有可自动完成的任务（" + match.reason + "）");
            stop(null, false);
            return;
        }
        target = match.task;
        startState = target.state;
        ModuleStats.myTaskMatched.incrementAndGet();
        log(Log.INFO, "我的任务：命中「" + target.title + "」type=" + target.type
                + " state=" + target.state + " 按钮=" + target.stateDesc
                + "（" + match.reason + "）");
        openTarget();
    }


    private void openTarget() {
        if (!running) {
            return;
        }
        TaskItem t = target;
        Activity act = currentActivity;
        String link = t == null ? "" : t.maxjia;
        if (link.isEmpty()) {
            stop("任务没有可打开的入口（maxjia 为空）", false);
            return;
        }
        if (act == null || act.isFinishing()) {
            stop("没有可用 Activity（应用已退到后台）", false);
            return;
        }
        setStage(S_OPEN);
        openTicks = 0;
        entryClicks = 0;
        schedule(OPEN_TIMEOUT_MS, "打开任务入口超时");
        try {
            Class<?> router = Class.forName("com.max.xiaoheihe.RouterActivity", false, targetCl);
            act.startActivity(new Intent(act, router)
                    .setData(Uri.parse(link.trim()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            log(Log.INFO, "我的任务：打开任务入口 " + link.trim());
        } catch (Throwable err) {
            stop("打开任务入口失败: " + err, false);
            return;
        }
        main.postDelayed(this::openTick, ENTRY_TICK_MS);
    }

    private void openTick() {
        if (!running || stage != S_OPEN) {
            return;
        }
        Activity act = currentActivity;
        if (act == null || act.isFinishing()) {
            stop("应用已退到后台", false);
            return;
        }
        if (looksLikeEditor(act)) {
            onEditorReady(act);
            return;
        }
        if (entryClicks < ENTRY_MAX_CLICKS && clickEntry(act)) {
            entryClicks++;
            log(Log.INFO, "我的任务：点击入口按钮进入编辑器（第 " + entryClicks + " 次）");
            main.postDelayed(this::openTick, 1_600L);
            return;
        }
        if (++openTicks >= OPEN_MAX_TICKS) {
            dumpVisibleTexts(act, "入口页没有出现发帖编辑器");
            stop("入口页没有可用的发帖按钮（见日志里的页面文字）", false);
            return;
        }
        if (openTicks == 2) {
            dumpVisibleTexts(act, "入口页停留中");
        }
        main.postDelayed(this::openTick, ENTRY_TICK_MS);
    }

    private boolean clickEntry(Activity act) {
        ViewGroup decor = decorOf(act);
        View hit = findTextClickable(decor, ENTRY_TEXTS);
        if (hit != null) {
            try {
                hit.performClick();
                return true;
            } catch (Throwable ignored) {
            }
        }
        View action = actionTextView(publishBar);
        if (action instanceof TextView && publishListener != null) {
            CharSequence cs = ((TextView) action).getText();
            String text = cs == null ? "" : cs.toString().trim();
            for (String want : ENTRY_TEXTS) {
                if (want.equals(text)) {
                    try {
                        ((View.OnClickListener) publishListener).onClick(action);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return clickInDialogs(ENTRY_TEXTS);
    }

    private void dumpVisibleTexts(Activity act, String reason) {
        if (textsDumped) {
            return;
        }
        textsDumped = true;
        try {
            ViewGroup decor = decorOf(act);
            java.util.List<String> texts = new ArrayList<>();
            collectTexts(decor, texts, 0);
            StringBuilder sb = new StringBuilder();
            for (String t : texts) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(t);
            }
            lastPageSummary = act.getClass().getSimpleName() + " → " + sb;
            log(Log.INFO, "我的任务：页面可见文字（" + reason + "）[" + lastPageSummary + "]");
        } catch (Throwable t) {
            log(Log.WARN, "我的任务：收集页面文字失败: " + t);
        }
    }

    private static void collectTexts(View root, java.util.List<String> out, int depth) {
        if (root == null || depth > 14 || out.size() >= 25) {
            return;
        }
        if (root instanceof TextView) {
            CharSequence cs = ((TextView) root).getText();
            String s = cs == null ? "" : cs.toString().trim();
            if (!s.isEmpty() && s.length() <= 24 && sizable(root) && !out.contains(s)) {
                out.add(s);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTexts(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    private void doFill(Activity act) {
        if (!running || stage != S_FILL) {
            return;
        }
        String text = postText();
        if (text.isEmpty()) {
            log(Log.WARN, "我的任务：发帖内容为空（设置里的「发帖内容」），本轮放弃");
            stop("发帖内容为空", false);
            return;
        }
        String how = fill(act, text);
        log(Log.INFO, "我的任务：正文填充方式=" + how + " 内容=" + text);
        setStage(S_PUBLISH);
        if (autoPost()) {
            schedule(WAIT_PUBLISH_MS, "自动发布超时（宿主没接受这次点击）");
            main.postDelayed(() -> clickPublish(act), 900L);
        } else {
            schedule(WAIT_PUBLISH_MS, "等用户发布超时（本次不删帖）");
            toast("内容已填好，点「发布」完成任务后模块会继续领奖/删帖");
        }
        startNewPostWatch();
    }

    private void startNewPostWatch() {
        newPostPolls = 0;
        main.postDelayed(this::pollNewPost, 2_500L);
    }

    private void pollNewPost() {
        if (!running || (stage != S_PUBLISH && stage != S_WAIT_ID)) {
            return;
        }
        if (!targetLinkId.isEmpty()) {
            return;
        }
        if (++newPostPolls > NEWPOST_MAX_POLLS) {
            return;
        }
        final String text = postText();
        final long since = runStartedAt;
        io.execute(() -> {
            String id = "";
            try {
                id = OwnPostClient.findRecent(text, since);
            } catch (Throwable ignored) {
            }
            final String found = id;
            main.post(() -> {
                if (!found.isEmpty() && running && targetLinkId.isEmpty()
                        && (stage == S_PUBLISH || stage == S_WAIT_ID)) {
                    log(Log.INFO, "我的任务：没收到发帖广播，改用「我的帖子」反查到新帖");
                    onPostCreated(found);
                } else if (running && (stage == S_PUBLISH || stage == S_WAIT_ID)) {
                    main.postDelayed(this::pollNewPost, NEWPOST_POLL_MS);
                }
            });
        });
    }

    private String fill(Activity act, String text) {
        ViewGroup decor = decorOf(act);
        if (decor != null) {
            WebView web = findFirst(decor, WebView.class, true);
            if (web != null) {
                try {
                    web.evaluateJavascript(insertTextJs(text), null);
                    return "WebView 富文本编辑器";
                } catch (Throwable t) {
                    log(Log.WARN, "我的任务：WebView 注入失败: " + t);
                }
            }
            EditText et = findFirst(decor, EditText.class, true);
            if (et != null) {
                try {
                    et.setText(text);
                    et.setSelection(et.getText() != null ? et.getText().length() : 0);
                    return "原生 EditText";
                } catch (Throwable t) {
                    log(Log.WARN, "我的任务：EditText 填充失败: " + t);
                }
            }
        }
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("BetterHeybox", text));
                toast("无法自动填入编辑器，内容已复制，请手动粘贴");
                return "剪贴板（需要手动粘贴）";
            }
        } catch (Throwable ignored) {
        }
        return "失败（正文未填入）";
    }

    private static String insertTextJs(String text) {
        return "(function(){var t=" + JSONObject.quote(text) + ";"
                + "var els=document.querySelectorAll('[contenteditable=\"true\"],textarea');"
                + "var el=null;"
                + "for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();"
                + "if(r.width>0&&r.height>0){el=els[i];break;}}"
                + "if(!el){return 'no-editable';}"
                + "el.focus();"
                + "var ok=false;"
                + "try{ok=document.execCommand('insertText',false,t);}catch(e){ok=false;}"
                + "if(!ok){try{el.innerText=(el.innerText||'')+t;ok=true;}catch(e){}}"
                + "return ok?'ok':'fail';})()";
    }

    private void clickPublish(Activity act) {
        if (!running || stage != S_PUBLISH) {
            return;
        }
        Object listener = publishListener;
        if (listener == null) {
            log(Log.WARN, "我的任务：没抓到「发布」按钮的点击回调，放弃本轮");
            stop("没抓到发布按钮", false);
            return;
        }
        View btn = actionTextView(publishBar);
        if (btn != null && !btn.isEnabled()) {
            log(Log.WARN, "我的任务：宿主未启用「发布」按钮（正文可能仍为空），本轮放弃");
            ModuleStats.myTaskPublishFail.incrementAndGet();
            stop("宿主未启用发布按钮", false);
            return;
        }
        try {
            log(Log.INFO, "我的任务：自动点击「发布」页面=" + act.getClass().getSimpleName());
            ((View.OnClickListener) listener).onClick(btn);
            setStage(S_WAIT_ID);
            schedule(POST_ID_TIMEOUT_MS, "发布后没拿到新帖 link_id");
        } catch (Throwable t) {
            stop("点击发布失败: " + t, false);
        }
    }

    private View actionTextView(Object titleBar) {
        if (titleBar == null) {
            return null;
        }
        for (String getter : new String[]{"getAppbarActionTextView", "getAppbarActionButtonView"}) {
            try {
                Object v = titleBar.getClass().getMethod(getter).invoke(titleBar);
                if (v instanceof View) {
                    return (View) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void onPostCreated(String linkId) {
        if (linkId == null || linkId.isEmpty()) {
            return;
        }
        if (!running) {
            return;
        }
        if (stage == S_DELETE || stage == S_CLAIM) {
            return;
        }
        targetLinkId = linkId;
        ModuleStats.myTaskPublished.incrementAndGet();
        log(Log.INFO, "我的任务：已发布新帖 link_id=" + linkId + "（来源：" + MyPostIdCatcher.lastSource() + "）");
        setStage(S_VERIFY);
        schedule(VERIFY_TIMEOUT_MS, "核对任务进度超时");
        main.postDelayed(this::verifyTask, 2500L);
    }

    private void verifyTask() {
        if (!running || stage != S_VERIFY) {
            return;
        }
        io.execute(() -> {
            List<TaskItem> items = TaskListClient.fetch();
            main.post(() -> onVerified(items));
        });
    }

    private void onVerified(List<TaskItem> items) {
        if (!running || stage != S_VERIFY) {
            return;
        }
        if (items == null || items.isEmpty()) {
            retryVerify("重新读列表失败");
            return;
        }
        String fp = target == null ? "" : target.fingerprint();
        TaskItem now = null;
        for (TaskItem t : items) {
            if (t.fingerprint().equals(fp)) {
                now = t;
                break;
            }
        }
        if (now == null) {
            retryVerify("任务列表里已经找不到这条任务");
            return;
        }
        boolean advanced = !now.state.equals(startState) || now.canClaim() || now.done();
        if (!advanced) {
            retryVerify("任务状态还没变（仍是 " + now.state + "）");
            return;
        }
        ModuleStats.myTaskVerified.incrementAndGet();
        log(Log.INFO, "我的任务：任务进度已推进 " + startState + " → " + now.state
                + "（按钮=" + now.stateDesc + "）");
        if (now.canClaim() && claimEnabled()) {
            claimReward(now);
        } else {
            goDelete();
        }
    }

    private void retryVerify(String why) {
        if (++verifyRounds >= VERIFY_MAX_ROUNDS) {
            ModuleStats.myTaskVerifyFail.incrementAndGet();
            log(Log.WARN, "我的任务：核对 " + VERIFY_MAX_ROUNDS + " 次仍未确认任务完成（" + why
                    + "），保留帖子不删除，明天再试");
            stop("任务进度未确认，保留帖子", false);
            return;
        }
        log(Log.INFO, "我的任务：任务进度还没确认（" + why + "），第 " + verifyRounds + " 次重试");
        schedule(VERIFY_TIMEOUT_MS, "核对任务进度超时");
        main.postDelayed(this::verifyTask, VERIFY_TIMEOUT_MS);
    }

    private void claimReward(TaskItem task) {
        Activity act = currentActivity;
        if (act == null || task.maxjia.isEmpty()) {
            goDelete();
            return;
        }
        setStage(S_CLAIM);
        ModuleStats.myTaskClaimed.incrementAndGet();
        try {
            Class<?> router = Class.forName("com.max.xiaoheihe.RouterActivity", false, targetCl);
            act.startActivity(new Intent(act, router)
                    .setData(Uri.parse(task.maxjia.trim()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            log(Log.INFO, "我的任务：打开领奖入口 " + task.maxjia.trim());
            toast("任务可领取，已打开领奖入口");
        } catch (Throwable t) {
            log(Log.WARN, "我的任务：打开领奖入口失败: " + t);
        }
        main.postDelayed(this::goDelete, CLAIM_SETTLE_MS);
    }

    private void goDelete() {
        if (!running) {
            return;
        }
        if (!deleteEnabled()) {
            stop("按设置不删帖，任务已推进", false);
            return;
        }
        if (targetLinkId.isEmpty()) {
            stop("没有新帖 link_id，无法删帖", false);
            return;
        }
        Activity act = currentActivity;
        if (act == null || act.isFinishing()) {
            stop("应用已退到后台，删帖跳过", false);
            return;
        }
        setStage(S_DELETE);
        deleteTicks = 0;
        clickedDeleteItem = false;
        clickedConfirm = false;
        moreBar = null;
        moreListener = null;
        schedule(DELETE_TIMEOUT_MS, "删帖流程超时");
        try {
            Class<?> page = Class.forName(
                    "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
                    false, targetCl);
            act.startActivity(new Intent(act, page)
                    .putExtra("link_id", targetLinkId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            log(Log.INFO, "我的任务：打开自己刚发的帖子，准备删除 link_id=" + targetLinkId);
        } catch (Throwable t) {
            log(Log.WARN, "我的任务：打开帖子页失败（可手动在「我的帖子」里删）: " + t);
            verifyDelete();
            return;
        }
        main.postDelayed(this::deleteTick, 2000L);
    }

    private void clickMoreSoon(Activity act) {
        main.postDelayed(() -> {
            if (!running || stage != S_DELETE) {
                return;
            }
            if (moreListener == null) {
                return;
            }
            try {
                ((View.OnClickListener) moreListener).onClick(actionMoreView(moreBar));
                log(Log.INFO, "我的任务：点击帖子页「更多」");
            } catch (Throwable t) {
                log(Log.WARN, "我的任务：点击「更多」失败: " + t);
            }
        }, 800L);
    }

    private View actionMoreView(Object titleBar) {
        if (titleBar == null) {
            return null;
        }
        try {
            Object v = titleBar.getClass().getMethod("getAppbarActionButtonMoreView").invoke(titleBar);
            if (v instanceof View) {
                return (View) v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void deleteTick() {
        if (!running || stage != S_DELETE) {
            return;
        }
        if (!clickedConfirm && clickInDialogs(CONFIRM_TEXTS)) {
            clickedConfirm = true;
            log(Log.INFO, "我的任务：已点击删帖确认，稍后核对是否真的删掉");
            main.postDelayed(this::verifyDelete, 2500L);
            return;
        }
        if (!clickedDeleteItem && clickInDialogs(DELETE_TEXTS)) {
            clickedDeleteItem = true;
            log(Log.INFO, "我的任务：已点击菜单「删除此内容」");
        } else if (!clickedDeleteItem) {
            ViewGroup decor = currentActivity != null ? decorOf(currentActivity) : null;
            View inline = findTextClickable(decor, DELETE_TEXTS);
            if (inline != null) {
                try {
                    inline.performClick();
                    clickedDeleteItem = true;
                    log(Log.INFO, "我的任务：已在页面上找到「删除此内容」并点击");
                } catch (Throwable ignored) {
                }
            }
        }
        if (!clickedDeleteItem && moreListener != null && deleteTicks % 3 == 0) {
            Activity act = currentActivity;
            if (act != null) {
                try {
                    ((View.OnClickListener) moreListener).onClick(actionMoreView(moreBar));
                    log(Log.INFO, "我的任务：再点一次帖子页「更多」");
                } catch (Throwable ignored) {
                }
            }
        }
        if (++deleteTicks >= DELETE_MAX_TICKS) {
            if (currentActivity != null) {
                dumpVisibleTexts(currentActivity, "删帖流程走完仍未确认");
            }
            verifyDelete();
            return;
        }
        main.postDelayed(this::deleteTick, TICK_MS);
    }

    private boolean clickInDialogs(String[] texts) {
        synchronized (dialogs) {
            for (WeakReference<Object> ref : dialogs) {
                Object dialog = ref.get();
                if (dialog == null) {
                    continue;
                }
                View root = null;
                if (dialog instanceof Dialog) {
                    try {
                        root = ((Dialog) dialog).getWindow() != null
                                ? ((Dialog) dialog).getWindow().getDecorView() : null;
                    } catch (Throwable ignored) {
                    }
                } else if (dialog instanceof View) {
                    root = (View) dialog;
                }
                View target = findTextClickable(root, texts);
                if (target != null) {
                    try {
                        target.performClick();
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    private void verifyDelete() {
        if (!running) {
            return;
        }
        if (HttpBridge.hostUserId() == null || HttpBridge.hostUserId().isEmpty()) {
            stop("未登录，无法确认是否已删帖（如仍需删除请手动操作）", false);
            return;
        }
        final String id = targetLinkId;
        io.execute(() -> {
            boolean stillThere = OwnPostClient.hasPost(id);
            main.post(() -> {
                if (!running) {
                    return;
                }
                if (stillThere) {
                    log(Log.WARN, "我的任务：帖子 " + id + " 似乎还在（如需删除请手动在「我的帖子」里操作）");
                    stop("未能确认删除", false);
                } else {
                    ModuleStats.myTaskDeleted.incrementAndGet();
                    markDone();
                    stop("帖子已删除", true);
                }
            });
        });
    }


    private void stop(String reason, boolean success) {
        running = false;
        stageToken++;
        stage = S_IDLE;
        target = null;
        currentActivity = null;
        synchronized (dialogs) {
            dialogs.clear();
        }
        if (reason == null || reason.isEmpty()) {
            if (success) {
                log(Log.INFO, "我的任务：本轮结束");
            }
            return;
        }
        log(success ? Log.INFO : Log.WARN, "我的任务：本轮结束 — " + reason);
        if (!success) {
            ModuleStats.myTaskAbort.incrementAndGet();
            lastRunAt = SystemClock.uptimeMillis() - (RUN_COOLDOWN_MS - RETRY_COOLDOWN_MS);
        }
    }

    private void markDone() {
        TaskItem t = target;
        if (t == null) {
            return;
        }
        String today = today();
        Set<String> entries = doneEntries();
        entries.add(today + "|" + t.fingerprint());
        java.util.List<String> keep = new ArrayList<>();
        for (String e : entries) {
            if (e.startsWith(today + "|")) {
                keep.add(e);
            }
        }
        persistDone(keep);
        log(Log.INFO, "我的任务：已记录今日完成 " + keep.size() + "/" + maxPerDay() + " 条");
    }

    private void persistDone(java.util.List<String> keep) {
        StringBuilder sb = new StringBuilder();
        for (String e : keep) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e);
        }
        com.better.heybox.HeyboxPrefs.setString(App.KEY_MY_TASK_DONE, sb.toString());
    }

    public void clearToday() {
        com.better.heybox.HeyboxPrefs.setString(App.KEY_MY_TASK_DONE, "");
        try {
            android.content.SharedPreferences remote = module.getRemotePreferences(App.PREFS_GROUP);
            if (remote != null) {
                remote.edit().remove(App.KEY_MY_TASK_DONE).apply();
            }
        } catch (Throwable ignored) {
        }
        log(Log.INFO, "我的任务：已清除今日完成记录");
    }


    private boolean autoPost() {
        return module.isEnabled(App.KEY_MY_TASK_AUTO_POST, false);
    }

    private boolean urgentOnly() {
        return module.isEnabled(App.KEY_MY_TASK_URGENT_ONLY, true);
    }

    private boolean deleteEnabled() {
        return module.isEnabled(App.KEY_MY_TASK_DELETE_AFTER, true);
    }

    private boolean claimEnabled() {
        return module.isEnabled(App.KEY_MY_TASK_CLAIM, true);
    }

    private String keywords() {
        return module.getString(App.KEY_MY_TASK_KEYWORDS, null);
    }

    private String postText() {
        String v = module.getString(App.KEY_MY_TASK_TEXT, "扣1");
        return v == null ? "" : v.trim();
    }

    private int maxPerDay() {
        String v = module.getString(App.KEY_MY_TASK_MAX_PER_DAY, "1");
        try {
            int n = Integer.parseInt(v.trim());
            return n <= 0 ? 1 : n;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private Set<String> doneEntries() {
        Set<String> out = new HashSet<>();
        String raw = com.better.heybox.HeyboxPrefs.getString(App.KEY_MY_TASK_DONE, "");
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private Set<String> doneFingerprints() {
        Set<String> out = new HashSet<>();
        String today = today();
        for (String entry : doneEntries()) {
            int idx = entry.indexOf('|');
            if (idx > 0 && entry.substring(0, idx).equals(today)) {
                out.add(entry.substring(idx + 1));
            }
        }
        return out;
    }

    private int doneCount() {
        return doneFingerprints().size();
    }


    public void refreshReport(Activity activity) {
        if (!running) {
            startReportOnly(activity);
        }
    }

    private void startReportOnly(Activity activity) {
        io.execute(() -> {
            TaskListClient.fetch();
            main.post(() -> {
                log(Log.INFO, "我的任务：任务状态已刷新");
                toast("任务状态已刷新");
            });
        });
    }

    public String report() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(TaskListClient.report());
        sb.append("\n本机开关：总开关=").append(enabled());
        sb.append(" / 全自动=").append(autoPost());
        sb.append(" / 只做限时=").append(urgentOnly());
        sb.append(" / 完成后删帖=").append(deleteEnabled());
        sb.append(" / 自动领奖=").append(claimEnabled());
        sb.append("\n发帖内容=").append(postText());
        sb.append("\n匹配关键词=").append(java.util.Arrays.toString(
                TaskMatcher.keywords(keywords())));
        sb.append("\n今日已完成=").append(doneCount()).append("/").append(maxPerDay());
        sb.append("\n当前状态：").append(running ? "运行中（" + STAGE_NAMES[stage] + "）" : "空闲");
        if (!lastPageSummary.isEmpty()) {
            sb.append("\n最后看到的页面：").append(lastPageSummary);
        }
        sb.append("\n入口页轮询=").append(openTicks).append(" 次，点入口按钮=").append(entryClicks).append(" 次");
        sb.append("\n发帖正文方式：WebView 注入 → 原生 EditText → 剪贴板（都不行就放弃，绝不发空帖）");
        sb.append("\n入口按钮候选：").append(java.util.Arrays.toString(ENTRY_TEXTS));
        sb.append("\n\n统计：运行=").append(ModuleStats.myTaskRuns.get())
                .append(" / 命中=").append(ModuleStats.myTaskMatched.get())
                .append(" / 发帖成功=").append(ModuleStats.myTaskPublished.get())
                .append(" / 发帖失败=").append(ModuleStats.myTaskPublishFail.get())
                .append(" / 删帖成功=").append(ModuleStats.myTaskDeleted.get())
                .append(" / 领奖=").append(ModuleStats.myTaskClaimed.get())
                .append(" / 中止=").append(ModuleStats.myTaskAbort.get());
        return sb.toString();
    }


    private void setStage(int next) {
        stage = next;
        stageToken++;
    }

    private void schedule(long ms, String message) {
        final long token = stageToken;
        main.postDelayed(() -> {
            if (!running || stageToken != token) {
                return;
            }
            log(Log.WARN, "我的任务：" + STAGE_NAMES[stage] + " 阶段超时 — " + message);
            stop(message, false);
        }, ms);
    }

    private void logThrottled(String message) {
        long now = SystemClock.uptimeMillis();
        if (now - lastBusyLogAt < 60_000L) {
            return;
        }
        lastBusyLogAt = now;
        log(Log.INFO, "我的任务：" + message);
    }

    private void log(int level, String message) {
        module.logd(level, module.TAG, message);
    }

    private void toast(String message) {
        try {
            Context ctx = currentActivity;
            if (ctx == null) {
                ctx = App.resolveAppContext();
            }
            if (ctx != null) {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private static ViewGroup decorOf(Activity act) {
        try {
            View decor = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            return decor instanceof ViewGroup ? (ViewGroup) decor : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sizable(View v) {
        try {
            return v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static <T extends View> T findFirst(View root, Class<T> type, boolean requireSizable) {
        if (root == null || type == null) {
            return null;
        }
        if (type.isInstance(root) && (!requireSizable || sizable(root))) {
            return type.cast(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T hit = findFirst(group.getChildAt(i), type, requireSizable);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static View findTextClickable(View root, String[] texts) {
        if (root == null || texts == null) {
            return null;
        }
        if (root instanceof TextView) {
            CharSequence cs = ((TextView) root).getText();
            String text = cs == null ? "" : cs.toString().trim();
            if (!text.isEmpty()) {
                for (String want : texts) {
                    if (text.equals(want)) {
                        View clickable = nearestClickable(root);
                        if (clickable != null) {
                            return clickable;
                        }
                    }
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findTextClickable(group.getChildAt(i), texts);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static View nearestClickable(View v) {
        if (v == null) {
            return null;
        }
        View cur = v;
        int guard = 0;
        while (cur != null && guard++ < 6) {
            if (cur.isClickable() && cur.isEnabled()) {
                return cur;
            }
            if (cur.getParent() instanceof View) {
                cur = (View) cur.getParent();
            } else {
                break;
            }
        }
        return v.isClickable() && v.isEnabled() ? v : null;
    }
}
