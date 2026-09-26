package com.better.heybox.hooks

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.CustomTextSelection
import com.better.heybox.MainModule
import com.better.heybox.ModuleStats
import com.better.heybox.ThemeUtils
import com.better.heybox.ViewUtils
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayList

class CommentCopyHook(private val module: MainModule) {

    @Volatile
    private var lastLongPressed: WeakReference<View> = WeakReference<View>(null)
    @Volatile
    private var lastLongPressAt = 0L
    @Volatile
    private var lastSheetAt = 0L
    @Volatile
    private var suppressToastUntil = 0L
    @Volatile
    private var lastNoMatchLogAt = 0L
    @Volatile
    private var freeCopyScreenShowing = false

    init {
        sInstance = this
    }


    fun install(cl: ClassLoader) {
        try {
            sCommentViewClass = Class.forName(COMMENT_VIEW_CLASS, false, cl)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "未找到评论正文控件类，改用类名匹配: " + t)
        }
        val helpers = hookCopyHelpers(cl)
        val clipboard = hookClipboard()
        val copyToast = hookCopyToast(cl)
        val toast = hookToastSuppress()
        val longPress = hookPerformLongClick()

        module.logd(
            Log.WARN, MainModule.TAG, "[评论自由复制] Hook 安装结果：复制助手="
                    + (if (helpers) "✔" else "✘")
                    + " / 剪贴板出口=" + (if (clipboard) "✔" else "✘")
                    + " / 复制提示抑制=" + (if (copyToast) "✔" else "✘")
                    + " / Toast 兜底抑制=" + (if (toast) "✔" else "✘")
                    + " / 长按目标=" + (if (longPress) "✔" else "✘")
                    + " / 状态=" + (if (module.isEnabled(App.KEY_COMMENT_FREE_COPY, true)) "开启" else "关闭")
        )
    }


    private fun hookCopyHelpers(cl: ClassLoader): Boolean {
        var any = false
        any = any or hookNamedCopyHelper(cl, "com.max.xiaoheihe.utils.h", "t")
        any = any or hookCopyOnlyClass(cl, "com.max.hbutils.utils.y")
        any = any or hookCopyOnlyClass(cl, "com.max.accelworld.c")
        return any
    }

    private fun hookNamedCopyHelper(cl: ClassLoader, className: String, methodName: String): Boolean {
        try {
            val clazz = Class.forName(className, false, cl)
            var hooked = 0
            val names = StringBuilder()
            for (method in clazz.declaredMethods) {
                if (methodName != method.name || !isCopyHelperShape(method)) {
                    continue
                }
                hookCopyMethod(clazz, method)
                hooked++
                if (names.length > 0) {
                    names.append(" / ")
                }
                names.append(method.name).append('(')
                    .append(describeParams(method.parameterTypes)).append(')')
            }
            if (hooked == 0) {
                module.logd(
                    Log.WARN, MainModule.TAG, "✘ 复制助手精确挂点缺失: " + className + "#"
                            + methodName + "（该版实现可能改名，已由剪贴板出口兜底）"
                )
            } else {
                module.logd(
                    Log.WARN, MainModule.TAG, "✔ 复制助手精确挂点: " + className
                            + "#" + names + " ×" + hooked + " 处"
                )
            }
            return hooked > 0
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG, "复制助手精确挂点跳过 " + className + "#"
                        + methodName + ": " + t
            )
            return false
        }
    }

    private fun hookCopyOnlyClass(cl: ClassLoader, className: String): Boolean {
        try {
            val clazz = Class.forName(className, false, cl)
            var hooked = 0
            for (method in clazz.declaredMethods) {
                if (!isCopyHelperShape(method)) {
                    continue
                }
                hookCopyMethod(clazz, method)
                hooked++
            }
            if (hooked > 0) {
                module.logd(
                    Log.WARN, MainModule.TAG, "✔ 复制助手 Hook: " + className + " ×" + hooked + " 处"
                )
            }
            return hooked > 0
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "复制助手 Hook 跳过 " + className + ": " + t)
            return false
        }
    }

    private fun hookCopyMethod(clazz: Class<*>, method: Method) {
        val params = method.parameterTypes
        val textIdx = textParamIndex(params)
        val ctxIdx = contextParamIndex(params)
        module.hook(method).intercept { chain ->
            try {
                if (canIntercept() && !inRecentSheetWindow()) {
                    val textArg = chain.arg(textIdx)
                    if (textArg is CharSequence && textArg.length > 0) {
                        ModuleStats.commentCopyHelperCalls.incrementAndGet()
                        val recorded = validRecordedView()
                        var activity = activityOfArg(chain.arg(ctxIdx))
                        if (activity == null && recorded != null) {
                            activity = ViewUtils.findActivity(recorded)
                        }
                        if (recorded != null) {
                            ModuleStats.commentCopyWithRecord.incrementAndGet()
                        } else {
                            ModuleStats.commentCopyNoRecord.incrementAndGet()
                        }
                        val comment = findCommentView(activity, textArg, recorded)
                        if (comment is TextView) {
                            markIntercepted()
                            module.logd(
                                Log.WARN, MainModule.TAG,
                                "[评论自由复制] 拦下评论复制（助手 "
                                        + clazz.simpleName + "#"
                                        + method.name + "）："
                                        + summarize(textArg.toString()) + " → 改弹二级菜单"
                            )
                            showCopySheet(comment, textArg, null)
                            return@intercept null
                        }
                    }
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "复制助手拦截异常（放行）: " + t)
            }
            chain.proceed()
        }
    }

    private fun activityOfArg(arg: Any?): Activity? {
        if (arg is View) {
            return ViewUtils.findActivity(arg)
        }
        if (arg is Context) {
            return ViewUtils.findActivity(arg)
        }
        return null
    }


    private fun hookClipboard(): Boolean {
        try {
            val method = ClipboardManager::class.java
                .getDeclaredMethod("setPrimaryClip", ClipData::class.java)
            module.hook(method).intercept { chain ->
                val clip = chain.arg(0)
                try {
                    if (canIntercept() && OWN_CLIP_LABEL != clipLabel(clip)
                        && !inRecentSheetWindow()
                    ) {
                        val text = clipText(clip)
                        if (text != null && text.length > 0) {
                            val comment = findCommentViewFor(text)
                            if (comment is TextView) {
                                markIntercepted()
                                module.logd(
                                    Log.WARN, MainModule.TAG, "[评论自由复制] 拦下评论复制："
                                            + summarize(text.toString()) + " → 改弹二级菜单"
                                )
                                showCopySheet(comment, text, chain.instanceOrNull)
                                return@intercept null
                            }
                        }
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "剪贴板拦截异常（放行）: " + t)
                }
                chain.proceed()
            }
            module.logd(Log.WARN, MainModule.TAG, "✔ 剪贴板出口 Hook: ClipboardManager#setPrimaryClip")
            return true
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "剪贴板出口 Hook 失败: " + t)
            return false
        }
    }

    private fun canIntercept(): Boolean {
        return module.isEnabled(App.KEY_COMMENT_FREE_COPY, true) && !freeCopyScreenShowing
    }


    private fun hookCopyToast(cl: ClassLoader): Boolean {
        var any = hookToastUtil(cl, TOAST_UTIL_CLASS)
        any = any or hookToastUtil(cl, "com.max.hbutils.utils.b0")
        return any
    }

    private fun hookToastUtil(cl: ClassLoader, className: String): Boolean {
        try {
            val clazz = Class.forName(className, false, cl)
            var hooked = 0
            for (method in clazz.declaredMethods) {
                if (!java.lang.reflect.Modifier.isStatic(method.modifiers)
                    || method.returnType !== Void.TYPE
                    || method.parameterCount != 1
                    || !CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
                ) {
                    continue
                }
                module.hook(method).intercept { chain ->
                    try {
                        val arg = chain.arg(0)
                        if (inSuppressWindow() || isCopyToastText(stringify(arg))) {
                            module.logd(
                                Log.WARN, MainModule.TAG, "[评论自由复制] 已抑制宿主提示（"
                                        + clazz.simpleName + "#" + method.name + "）："
                                        + (if (arg == null) "null" else summarize(stringify(arg)))
                            )
                            return@intercept null
                        }
                    } catch (ignored: Throwable) {
                    }
                    chain.proceed()
                }
                hooked++
            }
            if (hooked > 0) {
                module.logd(
                    Log.WARN, MainModule.TAG, "✔ 提示抑制 Hook: " + className
                            + " ×" + hooked + " 处"
                )
            }
            return hooked > 0
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "提示抑制 Hook 跳过 " + className + ": " + t)
            return false
        }
    }

    private fun hookToastSuppress(): Boolean {
        try {
            val show = Toast::class.java.getDeclaredMethod("show")
            module.hook(show).intercept { chain ->
                try {
                    if (shouldSuppressToast(chain.instanceOrNull)) {
                        module.logd(Log.WARN, MainModule.TAG, "[评论自由复制] 已抑制宿主「已复制」Toast")
                        return@intercept null
                    }
                } catch (ignored: Throwable) {
                }
                chain.proceed()
            }
            return true
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "Toast 兜底抑制 Hook 失败: " + t)
            return false
        }
    }

    @Suppress("unused")
    private fun shouldSuppressCopyToast(shown: String): Boolean {
        if (SystemClock.uptimeMillis() > suppressToastUntil) {
            return false
        }
        val expected = copiedToastText()
        if (expected != null && expected == shown) {
            return true
        }
        return (shown.contains("复制") || shown.contains("複製"))
                && (shown.contains("剪贴板") || shown.contains("剪貼簿"))
    }

    private fun shouldSuppressToast(toast: Any?): Boolean {
        if (toast !is Toast) {
            return false
        }
        var shown: String? = null
        try {
            val value = Toast::class.java.getMethod("getText").invoke(toast)
            shown = if (value == null) null else stringify(value)
        } catch (ignored: Throwable) {
        }
        if (!inSuppressWindow() && !isCopyToastText(shown)) {
            return false
        }
        module.logd(
            Log.WARN, MainModule.TAG, "[评论自由复制] 已抑制宿主 Toast："
                    + (if (shown == null) "(自定义 View)" else summarize(shown))
        )
        return true
    }

    private fun isCopyToastText(shown: String?): Boolean {
        if (shown == null || !module.isEnabled(App.KEY_COMMENT_FREE_COPY, true)) {
            return false
        }
        val expected = copiedToastText()
        if (expected != null && expected == shown) {
            return true
        }
        val copying = shown.contains("复制") || shown.contains("複製")
        val clipboard = shown.contains("剪贴板") || shown.contains("剪切板")
                || shown.contains("剪貼簿") || shown.contains("剪貼板")
        return copying && clipboard
    }

    private fun inSuppressWindow(): Boolean {
        return SystemClock.uptimeMillis() <= suppressToastUntil
    }

    private fun inRecentSheetWindow(): Boolean {
        return SystemClock.uptimeMillis() - lastSheetAt < SHEET_WINDOW_MS
    }

    private fun markIntercepted() {
        val now = SystemClock.uptimeMillis()
        lastSheetAt = now
        suppressToastUntil = now + TOAST_SUPPRESS_MS
    }


    private fun hookPerformLongClick(): Boolean {
        try {
            val method = View::class.java.getDeclaredMethod("performLongClick")
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (module.isEnabled(App.KEY_COMMENT_FREE_COPY, true)) {
                        val self = chain.instanceOrNull
                        if (self is View) {
                            val comment = findCommentViewNear(self)
                            if (comment != null) {
                                lastLongPressed = WeakReference(comment)
                                lastLongPressAt = SystemClock.uptimeMillis()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "记录长按目标失败: " + t)
                }
                result
            }
            return true
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "长按目标 Hook 失败: " + t)
            return false
        }
    }

    private fun validRecordedView(): View? {
        val target = lastLongPressed.get() ?: return null
        if (SystemClock.uptimeMillis() - lastLongPressAt > LONG_PRESS_TTL_MS) {
            return null
        }
        if (!target.isShown || target.windowToken == null) {
            return null
        }
        return target
    }

    private fun findCommentViewNear(start: View?): View? {
        if (start == null) {
            return null
        }
        if (isCommentView(start)) {
            return start
        }
        val inSelf = ArrayList<View>()
        collectCommentViews(start, inSelf, 0, Scan())
        if (!inSelf.isEmpty()) {
            return inSelf[0]
        }
        var parent: ViewParent? = start.parent
        var depth = 0
        while (parent is View && depth < 4) {
            depth++
            val ancestor = parent
            val found = ArrayList<View>()
            collectCommentViews(ancestor, found, 0, Scan())
            if (found.size == 1) {
                return found[0]
            }
            if (found.size > 1) {
                return null
            }
            parent = ancestor.parent
        }
        return null
    }

    private fun collectCommentViews(root: View?, out: MutableList<View>, depth: Int, scan: Scan) {
        if (root == null || depth > 40 || out.size > 64 || scan.nodes >= MAX_SCAN_NODES) {
            return
        }
        scan.nodes++
        if (isCommentView(root)) {
            out.add(root)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectCommentViews(root.getChildAt(i), out, depth + 1, scan)
            }
        }
    }

    private class Scan {
        var nodes = 0
    }


    private fun findCommentViewFor(copied: CharSequence): View? {
        val recorded = validRecordedView()
        return findCommentView(activityOf(recorded), copied, recorded)
    }

    private fun findCommentView(activity: Activity?, copied: CharSequence, recorded: View?): View? {
        val wanted = normalize(copied)
        if (wanted.length == 0) {
            return null
        }
        if (recorded is TextView && textMatches(normalize(recorded.text), wanted)) {
            return recorded
        }
        if (activity == null || activity.isFinishing) {
            return null
        }
        ModuleStats.commentDfsRuns.incrementAndGet()
        val startAt = SystemClock.uptimeMillis()
        val scan = Scan()
        var found: View? = null
        try {
            found = findCommentByText(activity.window.decorView, wanted, scan)
        } catch (ignored: Throwable) {
        }
        val cost = SystemClock.uptimeMillis() - startAt
        ModuleStats.commentDfsNodes.addAndGet(scan.nodes.toLong())
        ModuleStats.commentDfsMillis.addAndGet(cost)
        ModuleStats.slow("评论复制整树查找", cost)
        if (found != null) {
            ModuleStats.commentCopyMatched.incrementAndGet()
            return found
        }
        ModuleStats.commentCopyNoMatch.incrementAndGet()
        val exhausted = scan.nodes >= MAX_SCAN_NODES
        if (exhausted) {
            ModuleStats.commentDfsBudgetHits.incrementAndGet()
        }
        logNoMatch(scan.nodes, copied, recorded, exhausted)
        return null
    }

    private fun findCommentByText(root: View?, wanted: String, scan: Scan): View? {
        if (root == null || scan.nodes >= MAX_SCAN_NODES) {
            return null
        }
        scan.nodes++
        if (isCommentView(root)) {
            if (root is TextView && textMatches(normalize(root.text), wanted)) {
                return root
            }

            return null
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findCommentByText(root.getChildAt(i), wanted, scan)
                if (found != null) {
                    return found
                }
                if (scan.nodes >= MAX_SCAN_NODES) {
                    break
                }
            }
        }
        return null
    }

    private fun logNoMatch(
        scannedNodes: Int,
        copied: CharSequence,
        recorded: View?,
        budgetExhausted: Boolean,
    ) {
        val now = SystemClock.uptimeMillis()
        if (!budgetExhausted && now - lastNoMatchLogAt < NO_MATCH_LOG_INTERVAL_MS) {
            return
        }
        lastNoMatchLogAt = now
        module.logv(
            MainModule.TAG, "[评论自由复制] 文本未匹配到评论 复制文本=" + summarize(copied.toString())
                    + " / 长按记录=" + (if (recorded is TextView) {
                summarize(stringify(recorded.text))
            } else {
                "无"
            })
        )
    }

    private fun activityOf(anchor: View?): Activity? {
        return if (anchor != null) ViewUtils.findActivity(anchor) else null
    }


    private fun showCopySheet(commentView: TextView, copiedText: CharSequence, clipboard: Any?) {
        val activity = ViewUtils.findActivity(commentView)
        if (activity == null || activity.isFinishing) {
            module.logd(Log.WARN, MainModule.TAG, "[评论自由复制] 找不到 Activity，放行宿主复制")
            writeClipboard(clipboard, copiedText)
            return
        }
        val nickname = nicknameOf(commentView)
        activity.window.decorView.postDelayed({
            try {
                presentSheet(activity, commentView, copiedText, clipboard, nickname)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "二级菜单失败，退回整条复制: " + t)
                writeClipboard(clipboard, copiedText)
            }
        }, MENU_DISMISS_DELAY_MS)
    }

    private fun presentSheet(
        activity: Activity,
        commentView: TextView,
        copiedText: CharSequence,
        clipboard: Any?,
        nickname: String?,
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val window = dialog.window
        val dark = ThemeUtils.isDarkMode(activity)
        val surface = if (dark) 0xFF232327.toInt() else 0xFFFFFFFF.toInt()
        applyBottomSheetWindow(window, activity, surface, 0.35f)
        dialog.setCanceledOnTouchOutside(true)

        val primary = if (dark) 0xE6FFFFFF.toInt() else 0xDD000000.toInt()
        val secondary = if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt()
        val dividerColor = if (dark) 0x1FFFFFFF.toInt() else 0x14000000.toInt()
        val ripple = if (dark) 0x33FFFFFF.toInt() else 0x1A000000.toInt()

        val container = LinearLayout(activity)
        container.orientation = LinearLayout.VERTICAL
        val shape = GradientDrawable()
        shape.setColor(surface)
        val radius = ThemeUtils.dp(activity, ThemeUtils.RADIUS_SHEET_DP.toFloat()).toFloat()
        shape.setCornerRadii(floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f))
        container.background = shape
        val padV = ThemeUtils.dp(activity, 10f)
        container.setPadding(0, padV, 0, padV)

        container.addView(
            sheetRow(
                activity, "复制全部内容", primary, ripple,
                View.OnClickListener {
                    dialog.dismiss()
                    writeClipboard(clipboard, copiedText)
                }
            )
        )
        container.addView(sheetDivider(activity, dividerColor))
        container.addView(
            sheetRow(
                activity, "自由复制", primary, ripple,
                View.OnClickListener {
                    dialog.dismiss()
                    showFreeCopyScreen(activity, commentView, copiedText)
                }
            )
        )
        if (nickname != null && nickname.length > 0) {
            container.addView(sheetDivider(activity, dividerColor))
            container.addView(
                sheetRow(
                    activity, "复制 @" + nickname, primary, ripple,
                    View.OnClickListener {
                        dialog.dismiss()
                        writeClipboard(clipboard, "@" + nickname)
                    }
                )
            )
        }
        container.addView(sheetDivider(activity, dividerColor))
        container.addView(
            sheetRow(
                activity, "取消", secondary, ripple,
                View.OnClickListener {
                    dialog.dismiss()
                }
            )
        )

        dialog.setContentView(container)
        dialog.show()
        module.logd(
            Log.WARN, MainModule.TAG, "[评论自由复制] ✔ 已弹出二级菜单（昵称="
                    + (if (nickname == null) "未取到" else nickname) + "）"
        )
    }

    private fun sheetRow(
        context: Context,
        label: String,
        textColor: Int,
        rippleColor: Int,
        listener: View.OnClickListener,
    ): TextView {
        val row = TextView(context)
        row.text = label
        row.setTextSize(16f)
        row.setTextColor(textColor)
        row.gravity = Gravity.CENTER
        row.setPadding(
            ThemeUtils.dp(context, 20f), ThemeUtils.dp(context, 15f),
            ThemeUtils.dp(context, 20f), ThemeUtils.dp(context, 15f)
        )
        row.background = pressedBackground(context, rippleColor)
        row.isLongClickable = false
        row.isClickable = true
        row.setOnClickListener(listener)
        return row
    }

    private fun sheetDivider(context: Context, color: Int): View {
        val divider = View(context)
        divider.setBackgroundColor(color)
        divider.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ThemeUtils.dp(context, 0.5f))
        )
        return divider
    }


    private fun showFreeCopyScreen(
        activity: Activity,
        commentView: TextView,
        interceptedText: CharSequence?,
    ) {
        val text = rawCommentText(commentView, interceptedText)
        if (text == null || text.length == 0) {
            return
        }
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val window = dialog.window
        val dark = ThemeUtils.isDarkMode(activity)
        val surface = if (dark) 0xFF232327.toInt() else 0xFFFFFFFF.toInt()
        val primary = if (dark) 0xE6FFFFFF.toInt() else 0xDD000000.toInt()
        val secondary = if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt()

        applyCenteredCardWindow(window, activity, surface, 0.45f)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(surface)
        bg.setCornerRadius(ThemeUtils.dp(activity, 20f).toFloat())
        root.background = bg
        val pad = ThemeUtils.dp(activity, 18f)
        root.setPadding(pad, pad, pad, ThemeUtils.dp(activity, 18f))
        val customHandles = module.isEnabled(App.KEY_CUSTOM_TEXT_SELECT, false)

        val body = TextView(activity)
        body.text = SpannableStringBuilder(text)
        body.setTextSize(17f)
        body.setTextColor(primary)
        body.gravity = Gravity.CENTER
        body.setLineSpacing(ThemeUtils.dp(activity, 5f).toFloat(), 1f)
        val handlePad = ThemeUtils.dp(activity, 26f)
        body.setPadding(0, handlePad, 0, handlePad)
        if (!customHandles) {
            body.setTextIsSelectable(true)
        }

        val metrics: DisplayMetrics = activity.resources.displayMetrics
        val maxHeight = (metrics.heightPixels * 0.55f).toInt()
        val availWidth = Math.max(0, metrics.widthPixels - pad * 2)
        body.measure(
            View.MeasureSpec.makeMeasureSpec(availWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val scroll: MaxHeightScrollView? = if (body.measuredHeight > maxHeight) {
            MaxHeightScrollView(activity, maxHeight)
        } else {
            null
        }
        if (scroll != null) {
            scroll.isFillViewport = false
            scroll.clipToPadding = false
            scroll.clipChildren = false
            scroll.addView(
                body,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            body.setOnTouchListener(object : View.OnTouchListener {
                private var downHadSelection = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val selecting = body.selectionStart != body.selectionEnd
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        downHadSelection = selecting
                    }
                    if (selecting || downHadSelection) {
                        scroll.keepGesture(SystemClock.uptimeMillis() + 800L)
                    }
                    return false
                }
            })
        } else {
            root.addView(
                body,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val hint = TextView(activity)
        hint.text = "长按自由复制"
        hint.setTextSize(12f)
        hint.setTextColor(secondary)
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, ThemeUtils.dp(activity, 10f), 0, ThemeUtils.dp(activity, 4f))
        root.addView(hint)

        freeCopyScreenShowing = true
        suppressToastUntil = 0L

        dialog.setOnDismissListener {
            freeCopyScreenShowing = false
            CustomTextSelection.detach(body)
        }

        dialog.setContentView(root)
        dialog.show()

        var selfDrawn = customHandles
        if (selfDrawn) {
            try {
                CustomTextSelection.attach(body, dialog.window!!.decorView as? ViewGroup)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "自绘手柄挂载失败，退回系统选择: " + t)
                selfDrawn = false
                body.setTextIsSelectable(true)
            }
        }
        module.logd(
            Log.WARN, MainModule.TAG, "[评论自由复制] ✔ 已打开自由复制文本页（完整 "
                    + text.length + " 字，" + (if (selfDrawn) "自绘手柄" else "系统文本选择") + "）"
        )
    }


    private fun applyCenteredCardWindow(
        window: Window?,
        activity: Activity,
        surface: Int,
        dim: Float,
    ) {
        if (window == null) {
            return
        }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attrs = window.attributes
        attrs.dimAmount = dim
        window.attributes = attrs
        styleNavigationBar(window, activity, surface)
    }

    private fun styleNavigationBar(window: Window, activity: Activity, surface: Int) {
        val dark = ThemeUtils.isDarkMode(activity)
        try {
            val decor = window.decorView
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            if (!dark) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            decor.systemUiVisibility = flags
        } catch (ignored: Throwable) {
        }
        try {
            window.navigationBarColor = surface
        } catch (ignored: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val controller = window.insetsController
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                        if (dark) 0
                        else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun applyBottomSheetWindow(
        window: Window?,
        activity: Activity,
        surface: Int,
        dim: Float,
    ) {
        if (window == null) {
            return
        }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attrs = window.attributes
        attrs.dimAmount = dim
        window.attributes = attrs

        styleNavigationBar(window, activity, surface)
    }

    @Suppress("unused")
    private fun navBarHeight(window: Window?): Int {
        if (window == null) {
            return 0
        }
        return try {
            val insets = window.decorView.rootWindowInsets ?: return 0
            if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                insets.systemWindowInsetBottom
            }
        } catch (ignored: Throwable) {
            0
        }
    }

    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) :
        ScrollView(context) {

        @Volatile
        private var keepGestureUntil = 0L

        fun keepGesture(until: Long) {
            keepGestureUntil = Math.max(keepGestureUntil, until)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (SystemClock.uptimeMillis() < keepGestureUntil) {
                return false
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            )
        }
    }

    private fun rawCommentText(commentView: View, fallback: CharSequence?): String? {
        try {
            val tag = commentView.tag
            if (tag != null) {
                val text = tag.javaClass.getMethod("getText").invoke(tag)
                if (text is CharSequence && text.length > 0) {
                    return sanitizeCardText(text.toString())
                }
            }
        } catch (ignored: Throwable) {
        }
        return if (fallback == null) null else sanitizeCardText(fallback.toString())
    }


    private fun writeClipboard(clipboard: Any?, text: CharSequence?) {
        try {
            val cleaned: CharSequence = sanitizeCardText(text?.toString())
            val cm: ClipboardManager? = if (clipboard is ClipboardManager) {
                clipboard
            } else {
                App.resolveAppContext()!!
                    .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            }
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(OWN_CLIP_LABEL, cleaned))
            }
            toast("已复制")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "写剪贴板失败: " + t)
        }
    }

    private fun toast(text: String) {
        try {
            suppressToastUntil = 0L
            val context = App.resolveAppContext()
            if (context != null) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun nicknameOf(commentView: View): String? {
        try {
            val tag = commentView.tag ?: return null
            val user = tag.javaClass.getMethod("getUser").invoke(tag) ?: return null
            val name = user.javaClass.getMethod("getUsername").invoke(user)
            return name?.toString()
        } catch (ignored: Throwable) {
            return null
        }
    }

    companion object {

        private const val COMMENT_VIEW_CLASS =
            "com.max.xiaoheihe.view.CustomLongPressExpressionTextView"
        private const val TOAST_UTIL_CLASS = "com.max.hbutils.utils.f"
        private const val OWN_CLIP_LABEL = "BetterHeybox"

        private const val LONG_PRESS_TTL_MS = 60_000L
        private const val MENU_DISMISS_DELAY_MS = 200L
        private const val SHEET_WINDOW_MS = 1_500L
        private const val TOAST_SUPPRESS_MS = 2_500L

        private const val MAX_SCAN_NODES = 20_000

        private const val NO_MATCH_LOG_INTERVAL_MS = 2_000L

        @Volatile
        private var sInstance: CommentCopyHook? = null

        private var sCommentViewClass: Class<*>? = null
        private var sCopiedToastText: String? = null
        private var sCopiedToastResolved = false

        @JvmStatic
        fun refresh() {
            val instance = sInstance ?: return
            instance.module.logd(
                Log.WARN, MainModule.TAG,
                "[评论自由复制] 开关已变更：该功能在下一次点「复制」时按新状态生效"
            )
        }

        private fun isCopyHelperShape(method: Method): Boolean {
            if (!java.lang.reflect.Modifier.isStatic(method.modifiers)
                || method.returnType !== Void.TYPE
            ) {
                return false
            }
            val params = method.parameterTypes
            val textIdx = textParamIndex(params)
            val ctxIdx = contextParamIndex(params)
            return textIdx >= 0 && ctxIdx >= 0 && textIdx != ctxIdx
        }

        private fun describeParams(params: Array<Class<*>>): String {
            val sb = StringBuilder()
            for (i in params.indices) {
                if (i > 0) {
                    sb.append(", ")
                }
                sb.append(params[i].simpleName)
            }
            return sb.toString()
        }

        private fun textParamIndex(params: Array<Class<*>>): Int {
            var found = -1
            for (i in params.indices) {
                if (CharSequence::class.java.isAssignableFrom(params[i])) {
                    found = i
                }
            }
            return found
        }

        private fun contextParamIndex(params: Array<Class<*>>): Int {
            for (i in params.indices) {
                if (Context::class.java.isAssignableFrom(params[i])
                    || View::class.java.isAssignableFrom(params[i])
                ) {
                    return i
                }
            }
            return -1
        }

        private fun clipLabel(clipData: Any?): String? {
            if (clipData !is ClipData) {
                return null
            }
            return try {
                val label = clipData.description.label
                label?.toString()
            } catch (t: Throwable) {
                null
            }
        }

        private fun clipText(clipData: Any?): CharSequence? {
            if (clipData !is ClipData) {
                return null
            }
            return try {
                if (clipData.itemCount <= 0) {
                    return null
                }
                clipData.getItemAt(0).text
            } catch (t: Throwable) {
                null
            }
        }

        private fun copiedToastText(): String? {
            if (sCopiedToastResolved) {
                return sCopiedToastText
            }
            sCopiedToastResolved = true
            try {
                val context = App.resolveAppContext()
                if (context != null) {
                    val id = context.resources
                        .getIdentifier("text_copied", "string", MainModule.TARGET_PKG)
                    if (id != 0) {
                        sCopiedToastText = context.getString(id)
                    }
                }
            } catch (ignored: Throwable) {
            }
            return sCopiedToastText
        }

        private fun isCommentView(view: View): Boolean {
            val clazz = sCommentViewClass
            if (clazz != null) {
                return clazz.isInstance(view)
            }
            return COMMENT_VIEW_CLASS == view.javaClass.name
        }

        private fun pressedBackground(context: Context, pressedColor: Int): Drawable {
            val radius = ThemeUtils.dp(context, 14f).toFloat()
            val normal = GradientDrawable()
            normal.setColor(Color.TRANSPARENT)
            normal.setCornerRadius(radius)
            val pressed = GradientDrawable()
            pressed.setColor(pressedColor)
            pressed.setCornerRadius(radius)
            val states = StateListDrawable()
            states.addState(intArrayOf(android.R.attr.state_pressed), pressed)
            states.addState(intArrayOf(), normal)
            return states
        }

        private fun textMatches(viewText: String, copied: String): Boolean {
            if (viewText.length == 0 || copied.length == 0) {
                return false
            }
            if (viewText == copied) {
                return true
            }
            if (copied.length >= 6 && viewText.contains(copied)) {
                return true
            }
            if (viewText.length >= 6 && copied.contains(viewText)) {
                return true
            }
            val probe = Math.min(Math.min(viewText.length, copied.length), 24)
            return probe >= 8 && viewText.regionMatches(0, copied, 0, probe)
        }

        private fun sanitizeCardText(text: String?): String {
            if (text == null) {
                return ""
            }
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u2028', '\n').replace('\u2029', '\n').replace('\u0085', '\n')
            val sb = StringBuilder(normalized.length)
            var lastNewline = true
            for (i in 0 until normalized.length) {
                var ch = normalized[i]
                if (isHiddenChar(ch)) {
                    continue
                }
                if (ch == '\n') {
                    if (lastNewline) {
                        continue
                    }
                    lastNewline = true
                    sb.append('\n')
                    continue
                }
                if (ch == '\u00A0' || ch == '\u3000' || ch == '\t') {
                    ch = ' '
                }
                lastNewline = false
                sb.append(ch)
            }
            val lines = sb.toString().split("\n")
            val out = StringBuilder(sb.length)
            for (line in lines) {
                val trimmed = line.trim { it <= ' ' }
                if (trimmed.length == 0) {
                    continue
                }
                if (out.length > 0) {
                    out.append('\n')
                }
                out.append(trimmed)
            }
            return out.toString()
        }

        private fun isHiddenChar(ch: Char): Boolean {
            if (ch == '\uFEFF' || ch == '\u2060' || ch == '\u180E' || ch == '\u00AD'
                || ch == '\u061C' || ch == '\uFFFC'
            ) {
                return true
            }
            if (ch >= '\u2000' && ch <= '\u200F') {
                return true
            }
            if (ch >= '\u202A' && ch <= '\u202E') {
                return true
            }
            if (ch >= '\u2066' && ch <= '\u2069') {
                return true
            }
            return ch.code == 0x7F || (ch.code < 0x20 && ch != '\n')
        }

        private fun normalize(src: CharSequence?): String {
            if (src == null) {
                return ""
            }
            val sb = StringBuilder(src.length)
            for (i in 0 until src.length) {
                val ch = src[i]
                if (Character.isWhitespace(ch) || isHiddenChar(ch)) {
                    continue
                }
                sb.append(ch)
            }
            return sb.toString()
        }

        private fun summarize(text: String): String {
            val one = text.replace('\n', ' ')
            return if (one.length <= 24) one else one.substring(0, 24) + "…"
        }

        private fun stringify(v: Any?): String = if (v == null) "null" else v.toString()
    }
}
