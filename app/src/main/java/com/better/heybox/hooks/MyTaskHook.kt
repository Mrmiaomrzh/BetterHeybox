package com.better.heybox.hooks

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import com.better.heybox.ModuleStats
import com.better.heybox.ViewUtils
import com.better.heybox.task.MyPostIdCatcher
import com.better.heybox.task.OwnPostClient
import com.better.heybox.task.TaskItem
import com.better.heybox.task.TaskListClient
import com.better.heybox.task.TaskMatcher
import com.better.heybox.watch.HttpBridge
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MyTaskHook(private val module: MainModule) {

    private val main = Handler(Looper.getMainLooper())

    private val io: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            val t = Thread(r, "betterheybox-mytask")
            t.isDaemon = true
            t
        }

    @Volatile
    private var targetCl: ClassLoader? = null

    @Volatile
    private var running = false

    @Volatile
    private var stage = S_IDLE

    @Volatile
    private var stageToken = 0L

    @Volatile
    private var target: TaskItem? = null

    @Volatile
    private var targetLinkId = ""

    @Volatile
    private var startState = ""

    @Volatile
    private var verifyRounds = 0

    @Volatile
    private var deleteTicks = 0

    @Volatile
    private var clickedDeleteItem = false

    @Volatile
    private var clickedConfirm = false

    @Volatile
    private var lastRunAt = 0L

    @Volatile
    private var lastBusyLogAt = 0L

    @Volatile
    private var runStartedAt = 0L

    @Volatile
    private var openTicks = 0

    @Volatile
    private var entryClicks = 0

    @Volatile
    private var newPostPolls = 0

    @Volatile
    private var textsDumped = false

    @Volatile
    private var lastPageSummary = ""

    @Volatile
    private var publishBar: Any? = null

    @Volatile
    private var publishListener: Any? = null

    @Volatile
    private var moreBar: Any? = null

    @Volatile
    private var moreListener: Any? = null

    @Volatile
    private var currentActivity: Activity? = null

    private val dialogs = ArrayList<WeakReference<Any>>()

    fun install(cl: ClassLoader) {
        targetCl = cl
        TaskListClient.init(module)
        HttpBridge.init(module)
        HttpBridge.installCapture(module, cl)
        MyPostIdCatcher.register(App.resolveAppContext(), module) { linkId -> onPostCreated(linkId) }
        hookMainResume(cl)
        hookTitleBar(cl)
        hookDialogs(cl)
        hookPostPage(cl)
        module.logd(Log.INFO, MainModule.TAG, "✔ 我的任务 Hook 安装完成")
    }

    private fun hookMainResume(cl: ClassLoader) {
                                         
        MainActivityHookDispatcher.addOnResume(module, cl, "我的任务") { act ->
            main.post { maybeRun(act) }
        }
        module.logd(Log.INFO, MainModule.TAG, "✔ 我的任务：MainActivity#onResume 已接入共享挂点")
    }

    private fun hookTitleBar(cl: ClassLoader) {
        try {
            val titleBar = Class.forName("com.max.hbcommon.component.TitleBar", false, cl)
            captureClickSetter(titleBar, "setActionOnClickListener", true)
            captureClickSetter(titleBar, "setActionIconOnClickListener", true)
            captureClickSetter(titleBar, "setActionMoreIconOnClickListener", false)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "我的任务：TitleBar Hook 失败（无法自动点发布/更多）: $t")
        }
    }

    private fun captureClickSetter(titleBar: Class<*>, setterName: String, isPublish: Boolean) {
        try {
            val setter = titleBar.getMethod(setterName, View.OnClickListener::class.java)
            module.hook(setter).intercept { chain ->
                val result = chain.proceed()
                try {
                    val self = chain.instanceOrNull
                    val listener = chain.arg(0)
                    if (self !is View || listener == null) {
                        return@intercept result
                    }
                    val act = ViewUtils.findActivity(self) ?: return@intercept result
                    if (isPublish) {
                        publishBar = self
                        publishListener = listener
                        if (running && stage != S_DELETE) {
                            onPublishActionReady(act)
                        }
                    } else {
                        moreBar = self
                        moreListener = listener
                        if (running && stage == S_DELETE) {
                            clickMoreSoon(act)
                        }
                    }
                } catch (ignored: Throwable) {
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 我的任务：TitleBar#$setterName Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "我的任务：TitleBar#$setterName Hook 失败: $t")
        }
    }

    private fun onPublishActionReady(act: Activity) {
        if (stage != S_OPEN && stage != S_FILL) {
            return
        }
        if (!looksLikeEditor(act)) {
            logThrottled("页面 " + act.javaClass.simpleName + " 不是发帖页，等真正的编辑器")
            return
        }
        onEditorReady(act)
    }

    private fun onEditorReady(act: Activity) {
        if (!running || stage != S_OPEN && stage != S_FILL) {
            return
        }
        if (stage == S_OPEN) {
            setStage(S_FILL)
            schedule(FILL_TIMEOUT_MS, "发帖页的「发布」按钮没就绪")
        }
        main.postDelayed({ doFill(act) }, 1_200L)
    }

    private fun looksLikeEditor(act: Activity?): Boolean {
        if (act == null) {
            return false
        }
        val name = act.javaClass.name
        if (name.contains("post_edit") || name.contains("PostEdit") || name.contains("PostTab")) {
            return true
        }
        val publishLabel = MainModule.getHeyboxTabLabel(act, "post", "发布")
        val v = actionTextView(publishBar)
        if (v is TextView) {
            val text: CharSequence? = v.text
            if (text != null && publishLabel == text.toString().trim()) {
                return true
            }
        }
        return false
    }

    private fun hookDialogs(cl: ClassLoader) {
        try {
            val popup = Class.forName("com.max.hbcommon.component.HeyBoxPopupMenu", false, cl)
            val show = popup.getMethod("show")
            module.hook(show).intercept { chain ->
                val result = chain.proceed()
                rememberDialog(chain.instanceOrNull)
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 我的任务：HeyBoxPopupMenu#show Hook 已安装")
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "我的任务：HeyBoxPopupMenu Hook 失败（删帖将只能靠帖子页菜单）: $t"
            )
        }
        try {
            val show = Dialog::class.java.getMethod("show")
            module.hook(show).intercept { chain ->
                val result = chain.proceed()
                if (running) {
                    rememberDialog(chain.instanceOrNull)
                }
                result
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "我的任务：Dialog#show Hook 失败: $t")
        }
        for (m in arrayOf("showAsDropDown", "showAtLocation", "show")) {
            try {
                val paramTypes: Array<Class<*>> = when (m) {
                    "showAtLocation" -> arrayOf(View::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                    "showAsDropDown" -> arrayOf(View::class.java)
                    else -> arrayOf()
                }
                val show = PopupWindow::class.java.getMethod(m, *paramTypes)
                module.hook(show).intercept { chain ->
                    val result = chain.proceed()
                    if (running) {
                        try {
                            val self = chain.instanceOrNull
                            val v = self!!.javaClass
                                .getMethod("getContentView").invoke(self)
                            rememberDialog(v)
                        } catch (ignored: Throwable) {
                        }
                    }
                    result
                }
                module.logd(Log.INFO, MainModule.TAG, "✔ 我的任务：PopupWindow#$m Hook 已安装")
                break
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun rememberDialog(dialog: Any?) {
        if (dialog == null) {
            return
        }
        synchronized(dialogs) {
            dialogs.add(0, WeakReference(dialog))
            while (dialogs.size > MAX_DIALOGS) {
                dialogs.removeAt(dialogs.size - 1)
            }
        }
    }

    private fun hookPostPage(cl: ClassLoader) {
        val pages = arrayOf(
            "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
            "com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.BasePostPageActivityV2",
        )
        for (cn in pages) {
            try {
                val page = Class.forName(cn, false, cl)
                val onCreate = ViewUtils.findMethod(page, "onCreate", Bundle::class.java)
                    ?: continue
                module.hook(onCreate).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val intent = chain.arg(0) as? Intent
                        if (intent != null) {
                            val id = extraLinkId(intent)
                            if (id.isNotEmpty()) {
                                MyPostIdCatcher.report(
                                    id,
                                    "帖子页 " + cn.substring(cn.lastIndexOf('.') + 1)
                                )
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                    result
                }
                module.logd(Log.INFO, MainModule.TAG, "✔ 我的任务：帖子页 link_id 兜底 Hook 已安装 ($cn)")
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun enabled(): Boolean {
        return module.isEnabled(App.KEY_MY_TASK_ENABLED, false)
    }

    private fun maybeRun(activity: Activity?) {
        if (!enabled()) {
            return
        }
        ModuleStats.myTaskResumeChecks.incrementAndGet()
        if (running || activity == null || activity.isFinishing) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastRunAt < RUN_COOLDOWN_MS) {
            return
        }
        val uid = HttpBridge.hostUserId()
        if (uid.isNullOrEmpty()) {
            logThrottled("未登录，跳过我的任务")
            return
        }
        if (DailyTaskHook.isAutoActive()) {
            logThrottled("每日分享任务进行中，本轮跳过我的任务（避免两个自动化抢屏）")
            return
        }
        if (doneCount() >= maxPerDay()) {
            logThrottled("今日已完成 " + doneCount() + "/" + maxPerDay() + " 条，达到每日上限")
            return
        }
        startRun(activity, "自动检查")
    }

    fun runNow(activity: Activity?) {
        if (activity == null) {
            return
        }
        if (running) {
            toast("我的任务正在执行中")
            return
        }
        startRun(activity, "手动触发")
    }

    private fun startRun(activity: Activity, reason: String) {
        running = true
        target = null
        targetLinkId = ""
        startState = ""
        verifyRounds = 0
        deleteTicks = 0
        clickedDeleteItem = false
        clickedConfirm = false
        openTicks = 0
        entryClicks = 0
        newPostPolls = 0
        textsDumped = false
        lastPageSummary = ""
        currentActivity = activity
        lastRunAt = SystemClock.uptimeMillis()
        runStartedAt = System.currentTimeMillis()
        ModuleStats.myTaskRuns.incrementAndGet()
        setStage(S_READ)
        schedule(
            OPEN_TIMEOUT_MS + FILL_TIMEOUT_MS + WAIT_PUBLISH_MS + POST_ID_TIMEOUT_MS,
            "整轮超时，已复位"
        )
        log(
            Log.INFO, "我的任务：开始检查（$reason），宿主客户端 " +
                    (if (HttpBridge.ready()) "已就绪" else "未就绪")

        )
        io.execute {
            val items = TaskListClient.fetch()
            val err = TaskListClient.lastError()
            main.post { onListLoaded(items, err) }
        }
    }

    private fun onListLoaded(items: List<TaskItem>?, err: String?) {
        if (!running) {
            return
        }
        if (items.isNullOrEmpty()) {
            ModuleStats.myTaskReadFail.incrementAndGet()
            stop("未读到任务列表" + (if (err.isNullOrEmpty()) "" else "：$err"), false)
            return
        }
        TaskListClient.logAll(module, items)
        val match = TaskMatcher.pick(
            items, urgentOnly(), keywords(),
            doneFingerprints(), doneCount(), maxPerDay()
        )
        if (match.task == null) {
            ModuleStats.myTaskNoMatch.incrementAndGet()
            log(Log.INFO, "我的任务：没有可自动完成的任务（" + match.reason + "）")
            stop(null, false)
            return
        }
        target = match.task
        startState = target!!.state
        ModuleStats.myTaskMatched.incrementAndGet()
        log(
            Log.INFO, "我的任务：命中「" + target!!.title + "」type=" + target!!.type +
                    " state=" + target!!.state + " 按钮=" + target!!.stateDesc +
                    "（" + match.reason + "）"

        )
        openTarget()
    }

    private fun openTarget() {
        if (!running) {
            return
        }
        val t = target
        val act = currentActivity
        val link = t?.maxjia ?: ""
        if (link.isEmpty()) {
            stop("任务没有可打开的入口（maxjia 为空）", false)
            return
        }
        if (act == null || act.isFinishing) {
            stop("没有可用 Activity（应用已退到后台）", false)
            return
        }
        setStage(S_OPEN)
        openTicks = 0
        entryClicks = 0
        schedule(OPEN_TIMEOUT_MS, "打开任务入口超时")
        try {
            val router = Class.forName("com.max.xiaoheihe.RouterActivity", false, targetCl)
            act.startActivity(
                Intent(act, router)
                    .setData(Uri.parse(link.trim()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            log(Log.INFO, "我的任务：打开任务入口 " + link.trim())
        } catch (err: Throwable) {
            stop("打开任务入口失败: $err", false)
            return
        }
        main.postDelayed({ openTick() }, ENTRY_TICK_MS)
    }

    private fun openTick() {
        if (!running || stage != S_OPEN) {
            return
        }
        val act = currentActivity
        if (act == null || act.isFinishing) {
            stop("应用已退到后台", false)
            return
        }
        if (looksLikeEditor(act)) {
            onEditorReady(act)
            return
        }
        if (entryClicks < ENTRY_MAX_CLICKS && clickEntry(act)) {
            entryClicks++
            log(Log.INFO, "我的任务：点击入口按钮进入编辑器（第 $entryClicks 次）")
            main.postDelayed({ openTick() }, 1_600L)
            return
        }
        if (++openTicks >= OPEN_MAX_TICKS) {
            dumpVisibleTexts(act, "入口页没有出现发帖编辑器")
            stop("入口页没有可用的发帖按钮（见日志里的页面文字）", false)
            return
        }
        if (openTicks == 2) {
            dumpVisibleTexts(act, "入口页停留中")
        }
        main.postDelayed({ openTick() }, ENTRY_TICK_MS)
    }

    private fun clickEntry(act: Activity): Boolean {
        val decor = decorOf(act)
        val hit = findTextClickable(decor, ENTRY_TEXTS)
        if (hit != null) {
            try {
                hit.performClick()
                return true
            } catch (ignored: Throwable) {
            }
        }
        val action = actionTextView(publishBar)
        if (action is TextView && publishListener != null) {
            val cs: CharSequence? = action.text
            val text = cs?.toString()?.trim() ?: ""
            for (want in ENTRY_TEXTS) {
                if (want == text) {
                    try {
                        (publishListener as View.OnClickListener).onClick(action)
                        return true
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
        return clickInDialogs(ENTRY_TEXTS)
    }

    private fun dumpVisibleTexts(act: Activity, reason: String) {
        if (textsDumped) {
            return
        }
        textsDumped = true
        try {
            val decor = decorOf(act)
            val texts = ArrayList<String>()
            collectTexts(decor, texts, 0)
            val sb = StringBuilder()
            for (t in texts) {
                if (sb.isNotEmpty()) {
                    sb.append(" | ")
                }
                sb.append(t)
            }
            lastPageSummary = act.javaClass.simpleName + " → $sb"
            log(Log.INFO, "我的任务：页面可见文字（$reason）[$lastPageSummary]")
        } catch (t: Throwable) {
            log(Log.WARN, "我的任务：收集页面文字失败: $t")
        }
    }

    private fun doFill(act: Activity) {
        if (!running || stage != S_FILL) {
            return
        }
        val text = postText()
        if (text.isEmpty()) {
            log(Log.WARN, "我的任务：发帖内容为空（设置里的「发帖内容」），本轮放弃")
            stop("发帖内容为空", false)
            return
        }
        val how = fill(act, text)
        log(Log.INFO, "我的任务：正文填充方式=$how 内容=$text")
        setStage(S_PUBLISH)
        if (autoPost()) {
            schedule(WAIT_PUBLISH_MS, "自动发布超时（宿主没接受这次点击）")
            main.postDelayed({ clickPublish(act) }, 900L)
        } else {
            schedule(WAIT_PUBLISH_MS, "等用户发布超时（本次不删帖）")
            toast("内容已填好，点「发布」完成任务后模块会继续领奖/删帖")
        }
        startNewPostWatch()
    }

    private fun startNewPostWatch() {
        newPostPolls = 0
        main.postDelayed({ pollNewPost() }, 2_500L)
    }

    private fun pollNewPost() {
        if (!running || (stage != S_PUBLISH && stage != S_WAIT_ID)) {
            return
        }
        if (targetLinkId.isNotEmpty()) {
            return
        }
        if (++newPostPolls > NEWPOST_MAX_POLLS) {
            return
        }
        val text = postText()
        val since = runStartedAt
        io.execute {
            var id = ""
            try {
                id = OwnPostClient.findRecent(text, since)
            } catch (ignored: Throwable) {
            }
            val found = id
            main.post {
                if (found.isNotEmpty() && running && targetLinkId.isEmpty()
                    && (stage == S_PUBLISH || stage == S_WAIT_ID)
                ) {
                    log(Log.INFO, "我的任务：没收到发帖广播，改用「我的帖子」反查到新帖")
                    onPostCreated(found)
                } else if (running && (stage == S_PUBLISH || stage == S_WAIT_ID)) {
                    main.postDelayed({ pollNewPost() }, NEWPOST_POLL_MS)
                }
            }
        }
    }

    private fun fill(act: Activity, text: String): String {
        val decor = decorOf(act)
        if (decor != null) {
            val web = findFirst(decor, WebView::class.java, true)
            if (web != null) {
                try {
                    web.evaluateJavascript(insertTextJs(text), null)
                    return "WebView 富文本编辑器"
                } catch (t: Throwable) {
                    log(Log.WARN, "我的任务：WebView 注入失败: $t")
                }
            }
            val et = findFirst(decor, EditText::class.java, true)
            if (et != null) {
                try {
                    et.setText(text)
                    et.setSelection(et.text?.length ?: 0)
                    return "原生 EditText"
                } catch (t: Throwable) {
                    log(Log.WARN, "我的任务：EditText 填充失败: $t")
                }
            }
        }
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("BetterHeybox", text))
                toast("无法自动填入编辑器，内容已复制，请手动粘贴")
                return "剪贴板（需要手动粘贴）"
            }
        } catch (ignored: Throwable) {
        }
        return "失败（正文未填入）"
    }

    private fun clickPublish(act: Activity) {
        if (!running || stage != S_PUBLISH) {
            return
        }
        val listener = publishListener
        if (listener == null) {
            log(Log.WARN, "我的任务：没抓到「发布」按钮的点击回调，放弃本轮")
            stop("没抓到发布按钮", false)
            return
        }
        val btn = actionTextView(publishBar)
        if (btn != null && !btn.isEnabled) {
            log(Log.WARN, "我的任务：宿主未启用「发布」按钮（正文可能仍为空），本轮放弃")
            ModuleStats.myTaskPublishFail.incrementAndGet()
            stop("宿主未启用发布按钮", false)
            return
        }
        try {
            log(Log.INFO, "我的任务：自动点击「发布」页面=" + act.javaClass.simpleName)
            (listener as View.OnClickListener).onClick(btn)
            setStage(S_WAIT_ID)
            schedule(POST_ID_TIMEOUT_MS, "发布后没拿到新帖 link_id")
        } catch (t: Throwable) {
            stop("点击发布失败: $t", false)
        }
    }

    private fun actionTextView(titleBar: Any?): View? {
        if (titleBar == null) {
            return null
        }
        for (getter in arrayOf("getAppbarActionTextView", "getAppbarActionButtonView")) {
            try {
                val v = titleBar.javaClass.getMethod(getter).invoke(titleBar)
                if (v is View) {
                    return v
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    private fun onPostCreated(linkId: String?) {
        if (linkId.isNullOrEmpty()) {
            return
        }
        if (!running) {
            return
        }
        if (stage == S_DELETE || stage == S_CLAIM) {
            return
        }
        targetLinkId = linkId
        ModuleStats.myTaskPublished.incrementAndGet()
        log(
            Log.INFO, "我的任务：已发布新帖 link_id=$linkId（来源：" + MyPostIdCatcher.lastSource() + "）"
        )
        setStage(S_VERIFY)
        schedule(VERIFY_TIMEOUT_MS, "核对任务进度超时")
        main.postDelayed({ verifyTask() }, 2500L)
    }

    private fun verifyTask() {
        if (!running || stage != S_VERIFY) {
            return
        }
        io.execute {
            val items = TaskListClient.fetch()
            main.post { onVerified(items) }
        }
    }

    private fun onVerified(items: List<TaskItem>?) {
        if (!running || stage != S_VERIFY) {
            return
        }
        if (items.isNullOrEmpty()) {
            retryVerify("重新读列表失败")
            return
        }
        val fp = target?.fingerprint() ?: ""
        var now: TaskItem? = null
        for (t in items) {
            if (t.fingerprint() == fp) {
                now = t
                break
            }
        }
        if (now == null) {
            retryVerify("任务列表里已经找不到这条任务")
            return
        }
        val advanced = now.state != startState || now.canClaim() || now.done()
        if (!advanced) {
            retryVerify("任务状态还没变（仍是 " + now.state + "）")
            return
        }
        ModuleStats.myTaskVerified.incrementAndGet()
        log(
            Log.INFO, "我的任务：任务进度已推进 $startState → " + now.state +
                    "（按钮=" + now.stateDesc + "）"

        )
        if (now.canClaim() && claimEnabled()) {
            claimReward(now)
        } else {
            goDelete()
        }
    }

    private fun retryVerify(why: String) {
        if (++verifyRounds >= VERIFY_MAX_ROUNDS) {
            ModuleStats.myTaskVerifyFail.incrementAndGet()
            log(
                Log.WARN, "我的任务：核对 " + VERIFY_MAX_ROUNDS + " 次仍未确认任务完成（$why" +
                        "），保留帖子不删除，明天再试"

            )
            stop("任务进度未确认，保留帖子", false)
            return
        }
        log(Log.INFO, "我的任务：任务进度还没确认（$why），第 $verifyRounds 次重试")
        schedule(VERIFY_TIMEOUT_MS, "核对任务进度超时")
        main.postDelayed({ verifyTask() }, VERIFY_TIMEOUT_MS)
    }

    private fun claimReward(task: TaskItem) {
        val act = currentActivity
        if (act == null || task.maxjia.isEmpty()) {
            goDelete()
            return
        }
        setStage(S_CLAIM)
        ModuleStats.myTaskClaimed.incrementAndGet()
        try {
            val router = Class.forName("com.max.xiaoheihe.RouterActivity", false, targetCl)
            act.startActivity(
                Intent(act, router)
                    .setData(Uri.parse(task.maxjia.trim()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            log(Log.INFO, "我的任务：打开领奖入口 " + task.maxjia.trim())
            toast("任务可领取，已打开领奖入口")
        } catch (t: Throwable) {
            log(Log.WARN, "我的任务：打开领奖入口失败: $t")
        }
        main.postDelayed({ goDelete() }, CLAIM_SETTLE_MS)
    }

    private fun goDelete() {
        if (!running) {
            return
        }
        if (!deleteEnabled()) {
            stop("按设置不删帖，任务已推进", false)
            return
        }
        if (targetLinkId.isEmpty()) {
            stop("没有新帖 link_id，无法删帖", false)
            return
        }
        val act = currentActivity
        if (act == null || act.isFinishing) {
            stop("应用已退到后台，删帖跳过", false)
            return
        }
        setStage(S_DELETE)
        deleteTicks = 0
        clickedDeleteItem = false
        clickedConfirm = false
        moreBar = null
        moreListener = null
        schedule(DELETE_TIMEOUT_MS, "删帖流程超时")
        try {
            val page = Class.forName(
                "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
                false, targetCl
            )
            act.startActivity(
                Intent(act, page)
                    .putExtra("link_id", targetLinkId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            log(Log.INFO, "我的任务：打开自己刚发的帖子，准备删除 link_id=$targetLinkId")
        } catch (t: Throwable) {
            log(Log.WARN, "我的任务：打开帖子页失败（可手动在「我的帖子」里删）: $t")
            verifyDelete()
            return
        }
        main.postDelayed({ deleteTick() }, 2000L)
    }

    private fun clickMoreSoon(act: Activity) {
        main.postDelayed({
            if (!running || stage != S_DELETE) {
                return@postDelayed
            }
            val listener = moreListener ?: return@postDelayed
            try {
                (listener as View.OnClickListener).onClick(actionMoreView(moreBar))
                log(Log.INFO, "我的任务：点击帖子页「更多」")
            } catch (t: Throwable) {
                log(Log.WARN, "我的任务：点击「更多」失败: $t")
            }
        }, 800L)
    }

    private fun actionMoreView(titleBar: Any?): View? {
        if (titleBar == null) {
            return null
        }
        return try {
            val v = titleBar.javaClass.getMethod("getAppbarActionButtonMoreView").invoke(titleBar)
            v as? View
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun deleteTick() {
        if (!running || stage != S_DELETE) {
            return
        }
        if (!clickedConfirm && clickInDialogs(CONFIRM_TEXTS)) {
            clickedConfirm = true
            log(Log.INFO, "我的任务：已点击删帖确认，稍后核对是否真的删掉")
            main.postDelayed({ verifyDelete() }, 2500L)
            return
        }
        if (!clickedDeleteItem && clickInDialogs(DELETE_TEXTS)) {
            clickedDeleteItem = true
            log(Log.INFO, "我的任务：已点击菜单「删除此内容」")
        } else if (!clickedDeleteItem) {
            val decor = if (currentActivity != null) decorOf(currentActivity!!) else null
            val inline = findTextClickable(decor, DELETE_TEXTS)
            if (inline != null) {
                try {
                    inline.performClick()
                    clickedDeleteItem = true
                    log(Log.INFO, "我的任务：已在页面上找到「删除此内容」并点击")
                } catch (ignored: Throwable) {
                }
            }
        }
        if (!clickedDeleteItem && moreListener != null && deleteTicks % 3 == 0) {
            val act = currentActivity
            if (act != null) {
                try {
                    (moreListener as View.OnClickListener).onClick(actionMoreView(moreBar))
                    log(Log.INFO, "我的任务：再点一次帖子页「更多」")
                } catch (ignored: Throwable) {
                }
            }
        }
        if (++deleteTicks >= DELETE_MAX_TICKS) {
            if (currentActivity != null) {
                dumpVisibleTexts(currentActivity!!, "删帖流程走完仍未确认")
            }
            verifyDelete()
            return
        }
        main.postDelayed({ deleteTick() }, TICK_MS)
    }

    private fun clickInDialogs(texts: Array<String>): Boolean {
        synchronized(dialogs) {
            for (ref in dialogs) {
                val dialog = ref.get() ?: continue
                var root: View? = null
                if (dialog is Dialog) {
                    try {
                        root = if (dialog.window != null) dialog.window!!.decorView else null
                    } catch (ignored: Throwable) {
                    }
                } else if (dialog is View) {
                    root = dialog
                }
                val target = findTextClickable(root, texts)
                if (target != null) {
                    try {
                        target.performClick()
                        return true
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
        return false
    }

    private fun verifyDelete() {
        if (!running) {
            return
        }
        if (HttpBridge.hostUserId() == null || HttpBridge.hostUserId()!!.isEmpty()) {
            stop("未登录，无法确认是否已删帖（如仍需删除请手动操作）", false)
            return
        }
        val id = targetLinkId
        io.execute {
            val stillThere = OwnPostClient.hasPost(id)
            main.post {
                if (!running) {
                    return@post
                }
                if (stillThere) {
                    log(
                        Log.WARN,
                        "我的任务：帖子 $id 似乎还在（如需删除请手动在「我的帖子」里操作）"
                    )
                    stop("未能确认删除", false)
                } else {
                    ModuleStats.myTaskDeleted.incrementAndGet()
                    markDone()
                    stop("帖子已删除", true)
                }
            }
        }
    }

    fun shutdown() {
        stop(null, true)
        main.removeCallbacksAndMessages(null)
        try {
            io.shutdownNow()
        } catch (ignored: Throwable) {
        }
    }

    private fun stop(reason: String?, success: Boolean) {
        running = false
        stageToken++
        stage = S_IDLE
        target = null
        currentActivity = null
        synchronized(dialogs) {
            dialogs.clear()
        }
        if (reason.isNullOrEmpty()) {
            if (success) {
                log(Log.INFO, "我的任务：本轮结束")
            }
            return
        }
        log(if (success) Log.INFO else Log.WARN, "我的任务：本轮结束 — $reason")
        if (!success) {
            ModuleStats.myTaskAbort.incrementAndGet()
            lastRunAt = SystemClock.uptimeMillis() - (RUN_COOLDOWN_MS - RETRY_COOLDOWN_MS)
        }
    }

    private fun markDone() {
        val t = target ?: return
        val today = today()
        val entries = doneEntries()
        entries.add("$today|${t.fingerprint()}")
        val keep = ArrayList<String>()
        for (e in entries) {
            if (e.startsWith("$today|")) {
                keep.add(e)
            }
        }
        persistDone(keep)
        log(Log.INFO, "我的任务：已记录今日完成 " + keep.size + "/" + maxPerDay() + " 条")
    }

    private fun persistDone(keep: List<String>) {
        val sb = StringBuilder()
        for (e in keep) {
            if (sb.isNotEmpty()) {
                sb.append(',')
            }
            sb.append(e)
        }
        HeyboxPrefs.setString(App.KEY_MY_TASK_DONE, sb.toString())
    }

    fun clearToday() {
        HeyboxPrefs.setString(App.KEY_MY_TASK_DONE, "")
        try {
            val remote = module.getRemotePreferences(App.PREFS_GROUP)
            remote?.edit()?.remove(App.KEY_MY_TASK_DONE)?.apply()
        } catch (ignored: Throwable) {
        }
        log(Log.INFO, "我的任务：已清除今日完成记录")
    }

    private fun autoPost(): Boolean {
        return module.isEnabled(App.KEY_MY_TASK_AUTO_POST, false)
    }

    private fun urgentOnly(): Boolean {
        return module.isEnabled(App.KEY_MY_TASK_URGENT_ONLY, true)
    }

    private fun deleteEnabled(): Boolean {
        return module.isEnabled(App.KEY_MY_TASK_DELETE_AFTER, true)
    }

    private fun claimEnabled(): Boolean {
        return module.isEnabled(App.KEY_MY_TASK_CLAIM, true)
    }

    private fun keywords(): String? {
        return module.getString(App.KEY_MY_TASK_KEYWORDS, null)
    }

    private fun postText(): String {
        val v = module.getString(App.KEY_MY_TASK_TEXT, "扣1")
        return v?.trim() ?: ""
    }

    private fun maxPerDay(): Int {
        val v = module.getString(App.KEY_MY_TASK_MAX_PER_DAY, "1")
        return try {
            val n = v!!.trim().toInt()
            if (n <= 0) 1 else n
        } catch (ignored: Throwable) {
            1
        }
    }

    private fun doneEntries(): MutableSet<String> {
        val out = HashSet<String>()
        val raw = HeyboxPrefs.getString(App.KEY_MY_TASK_DONE, "")
        if (raw.isNullOrBlank()) {
            return out
        }
        for (part in raw.split(",".toRegex())) {
            val s = part.trim()
            if (s.isNotEmpty()) {
                out.add(s)
            }
        }
        return out
    }

    private fun doneFingerprints(): MutableSet<String> {
        val out = HashSet<String>()
        val today = today()
        for (entry in doneEntries()) {
            val idx = entry.indexOf('|')
            if (idx > 0 && entry.substring(0, idx) == today) {
                out.add(entry.substring(idx + 1))
            }
        }
        return out
    }

    private fun doneCount(): Int = doneFingerprints().size

    fun refreshReport(activity: Activity?) {
        if (!running) {
            startReportOnly(activity)
        }
    }

    private fun startReportOnly(activity: Activity?) {
        io.execute {
            TaskListClient.fetch()
            main.post {
                log(Log.INFO, "我的任务：任务状态已刷新")
                toast("任务状态已刷新")
            }
        }
    }

    fun report(): String {
        val sb = StringBuilder(2048)
        sb.append(TaskListClient.report())
        sb.append("\n本机开关：总开关=").append(enabled())
        sb.append(" / 全自动=").append(autoPost())
        sb.append(" / 只做限时=").append(urgentOnly())
        sb.append(" / 完成后删帖=").append(deleteEnabled())
        sb.append(" / 自动领奖=").append(claimEnabled())
        sb.append("\n发帖内容=").append(postText())
        sb.append("\n匹配关键词=").append(
            java.util.Arrays.toString(TaskMatcher.keywords(keywords()))
        )
        sb.append("\n今日已完成=").append(doneCount()).append("/").append(maxPerDay())
        sb.append("\n当前状态：").append(if (running) "运行中（" + STAGE_NAMES[stage] + "）" else "空闲")
        if (lastPageSummary.isNotEmpty()) {
            sb.append("\n最后看到的页面：").append(lastPageSummary)
        }
        sb.append("\n入口页轮询=").append(openTicks).append(" 次，点入口按钮=").append(entryClicks).append(" 次")
        sb.append("\n发帖正文方式：WebView 注入 → 原生 EditText → 剪贴板（都不行就放弃，绝不发空帖）")
        sb.append("\n入口按钮候选：").append(java.util.Arrays.toString(ENTRY_TEXTS))
        sb.append("\n\n统计：运行=").append(ModuleStats.myTaskRuns.get())
            .append(" / 命中=").append(ModuleStats.myTaskMatched.get())
            .append(" / 发帖成功=").append(ModuleStats.myTaskPublished.get())
            .append(" / 发帖失败=").append(ModuleStats.myTaskPublishFail.get())
            .append(" / 删帖成功=").append(ModuleStats.myTaskDeleted.get())
            .append(" / 领奖=").append(ModuleStats.myTaskClaimed.get())
            .append(" / 中止=").append(ModuleStats.myTaskAbort.get())
        return sb.toString()
    }

    private fun setStage(next: Int) {
        stage = next
        stageToken++
    }

    private fun schedule(ms: Long, message: String) {
        val token = stageToken
        main.postDelayed({
            if (!running || stageToken != token) {
                return@postDelayed
            }
            log(Log.WARN, "我的任务：" + STAGE_NAMES[stage] + " 阶段超时 — $message")
            stop(message, false)
        }, ms)
    }

    private fun logThrottled(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBusyLogAt < 60_000L) {
            return
        }
        lastBusyLogAt = now
        log(Log.INFO, "我的任务：$message")
    }

    private fun log(level: Int, message: String) {
        module.logd(level, MainModule.TAG, message)
    }

    private fun toast(message: String) {
        try {
            var ctx: Context? = currentActivity
            if (ctx == null) {
                ctx = App.resolveAppContext()
            }
            if (ctx != null) {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            }
        } catch (ignored: Throwable) {
        }
    }

    companion object {

        private const val S_IDLE = 0
        private const val S_READ = 1
        private const val S_OPEN = 2
        private const val S_FILL = 3
        private const val S_PUBLISH = 4
        private const val S_WAIT_ID = 5
        private const val S_VERIFY = 6
        private const val S_CLAIM = 7
        private const val S_DELETE = 8

        private val STAGE_NAMES = arrayOf(
            "空闲", "读任务列表", "打开任务入口", "填正文", "发布", "等新帖", "核对任务",
            "领奖", "删帖", "完成"
        )

        private const val OPEN_TIMEOUT_MS = 45_000L
        private const val FILL_TIMEOUT_MS = 25_000L
        private const val WAIT_PUBLISH_MS = 4 * 60_000L
        private const val POST_ID_TIMEOUT_MS = 60_000L
        private const val VERIFY_TIMEOUT_MS = 20_000L
        private const val VERIFY_MAX_ROUNDS = 3
        private const val CLAIM_SETTLE_MS = 3_000L
        private const val DELETE_TIMEOUT_MS = 60_000L
        private const val TICK_MS = 800L
        private const val DELETE_MAX_TICKS = 30
        private const val ENTRY_TICK_MS = 1_200L
        private const val OPEN_MAX_TICKS = 24
        private const val ENTRY_MAX_CLICKS = 2
        private const val NEWPOST_POLL_MS = 3_000L
        private const val NEWPOST_MAX_POLLS = 20
        private const val RUN_COOLDOWN_MS = 3 * 60_000L
        private const val RETRY_COOLDOWN_MS = 90_000L
        private const val MAX_DIALOGS = 8

        private val DELETE_TEXTS = arrayOf("删除此内容", "删除")
        private val CONFIRM_TEXTS = arrayOf("确定", "确认")

        private val ENTRY_TEXTS = arrayOf(
            "参与讨论", "去讨论", "开始讨论", "参与话题", "去参与",
            "写点什么", "说点什么", "我要发帖", "去发帖", "发帖", "发表内容",
        )

        private fun extraLinkId(intent: Intent): String {
            return try {
                val extras: Bundle? = intent.extras
                if (extras == null) {
                    return ""
                }
                var v: Any? = extras.get("link_id")
                if (v == null) {
                    v = extras.get("linkid")
                }
                v?.toString() ?: ""
            } catch (ignored: Throwable) {
                ""
            }
        }

        private fun insertTextJs(text: String): String {
            return "(function(){var t=" + JSONObject.quote(text) + ";" +
                    "var els=document.querySelectorAll('[contenteditable=\"true\"],textarea');" +
                    "var el=null;" +
                    "for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();" +
                    "if(r.width>0&&r.height>0){el=els[i];break;}}" +
                    "if(!el){return 'no-editable';}" +
                    "el.focus();" +
                    "var ok=false;" +
                    "try{ok=document.execCommand('insertText',false,t);}catch(e){ok=false;}" +
                    "if(!ok){try{el.innerText=(el.innerText||'')+t;ok=true;}catch(e){}}" +
                    "return ok?'ok':'fail';})()"

        }

        private fun collectTexts(root: View?, out: MutableList<String>, depth: Int) {
            if (root == null || depth > 14 || out.size >= 25) {
                return
            }
            if (root is TextView) {
                val cs: CharSequence? = root.text
                val s = cs?.toString()?.trim() ?: ""
                if (s.isNotEmpty() && s.length <= 24 && sizable(root) && !out.contains(s)) {
                    out.add(s)
                }
            }
            if (root is ViewGroup) {
                val g = root
                for (i in 0 until g.childCount) {
                    collectTexts(g.getChildAt(i), out, depth + 1)
                }
            }
        }

        private fun today(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        private fun decorOf(act: Activity): ViewGroup? {
            return try {
                val decor: View? = act.window?.decorView
                decor as? ViewGroup
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun sizable(v: View): Boolean {
            return try {
                v.visibility == View.VISIBLE && v.width > 0 && v.height > 0
            } catch (ignored: Throwable) {
                false
            }
        }

        private fun <T : View> findFirst(root: View?, type: Class<T>, requireSizable: Boolean): T? {
            if (root == null) {
                return null
            }
            if (type.isInstance(root) && (!requireSizable || sizable(root))) {
                return type.cast(root)
            }
            if (root is ViewGroup) {
                val group = root
                for (i in 0 until group.childCount) {
                    val hit = findFirst(group.getChildAt(i), type, requireSizable)
                    if (hit != null) {
                        return hit
                    }
                }
            }
            return null
        }

        private fun findTextClickable(root: View?, texts: Array<String>?): View? {
            if (root == null || texts == null) {
                return null
            }
            if (root is TextView) {
                val cs: CharSequence? = root.text
                val text = cs?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    for (want in texts) {
                        if (text == want) {
                            val clickable = nearestClickable(root)
                            if (clickable != null) {
                                return clickable
                            }
                        }
                    }
                }
            }
            if (root is ViewGroup) {
                val group = root
                for (i in 0 until group.childCount) {
                    val hit = findTextClickable(group.getChildAt(i), texts)
                    if (hit != null) {
                        return hit
                    }
                }
            }
            return null
        }

        private fun nearestClickable(v: View?): View? {
            if (v == null) {
                return null
            }
            var cur: View? = v
            var guard = 0
            while (cur != null && guard++ < 6) {
                if (cur.isClickable && cur.isEnabled) {
                    return cur
                }
                if (cur.parent is View) {
                    cur = cur.parent as View
                } else {
                    break
                }
            }
            return if (v.isClickable && v.isEnabled) v else null
        }
    }
}
