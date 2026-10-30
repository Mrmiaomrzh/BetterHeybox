package com.better.heybox.hooks

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import com.better.heybox.ThemeUtils
import com.better.heybox.util.ScreenshotCleanupHelper
import com.better.heybox.util.ScreenshotExporter
import com.better.heybox.util.ScreenshotIds
import com.better.heybox.util.ScreenshotRenderer
import com.better.heybox.util.ScreenshotSelectionManager
import com.better.heybox.util.ScreenshotUnit
import com.better.heybox.util.WebViewHider
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.math.min

class ScreenshotCaptureHook(private val module: MainModule) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var resumedActivity: WeakReference<Activity?> = WeakReference(null)
    private var barHost: WeakReference<Activity?> = WeakReference(null)
    private var entryButton: View? = null
    private var actionBar: LinearLayout? = null
    private var countView: TextView? = null
    private var postChip: TextView? = null
    private var polling = false
    private var idsLogged = false
    private var entrySuspendedUntil = 0L

    private var capturing = false

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!polling) {
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now < entrySuspendedUntil) {
                mainHandler.postDelayed(this, min(80L, entrySuspendedUntil - now))
                return
            }
            val activity = resumedActivity.get()
            if (activity == null || activity.isFinishing) {
                polling = false
                return
            }
            try {
                refreshEntry(activity)
            } catch (throwable: Throwable) {
                module.logd(Log.DEBUG, TAG, "截图入口刷新失败: $throwable")
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun install(classLoader: ClassLoader) {
        if (!module.isEnabled(App.KEY_SCREENSHOT_ENABLED, true)) {
            module.logd(Log.INFO, TAG, "截图功能已关闭，跳过安装")
            return
        }
        instance = this
        ScreenshotSelectionManager.get().setListener(object : ScreenshotSelectionManager.Listener {
            override fun onSelectionChanged(count: Int) {
                updateBarState()
            }

            override fun onModeChanged(active: Boolean) {
                onSelectionModeChanged(active)
            }
        })
        try {
            hookActivityLifecycle()
            module.logd(Log.INFO, TAG, "截图捕获已安装")
        } catch (throwable: Throwable) {
            module.logd(Log.ERROR, TAG, "截图捕获安装失败: $throwable", throwable)
        }
    }

    @Throws(Throwable::class)
    private fun hookActivityLifecycle() {
        val onResume = Activity::class.java.getDeclaredMethod("onResume")
        module.hook(onResume).intercept { chain ->
            val result = chain.proceed()
            val self = chain.instanceOrNull
            if (self is Activity) {
                val activity = self
                mainHandler.post {
                    try {
                        val decor: View? = activity.window?.decorView
                        if (decor != null) {
                            decor.postOnAnimation { refreshFor(activity) }
                        } else {
                            refreshFor(activity)
                        }
                    } catch (throwable: Throwable) {
                        module.logd(Log.DEBUG, TAG, "截图入口延迟刷新失败: $throwable")
                    }
                }
            }
            result
        }
        val onPause = Activity::class.java.getDeclaredMethod("onPause")
        module.hook(onPause).intercept { chain ->
            val self = chain.instanceOrNull
            if (self is Activity) {
                val activity = self
                mainHandler.post { onPaused(activity) }
            }
            chain.proceed()
        }

        try {
            val onBackPressed = Activity::class.java.getDeclaredMethod("onBackPressed")
            module.hook(onBackPressed).intercept { chain ->
                val self = chain.instanceOrNull
                if (self is Activity) {
                    val activity = self
                    if (resumedActivity.get() === activity
                        && ScreenshotSelectionManager.get().isActive()
                    ) {
                        module.logd(Log.INFO, TAG, "截图模式拦截 BACK，退出选择模式")
                        ScreenshotSelectionManager.get().exit()
                        return@intercept null
                    }
                }
                chain.proceed()
            }
        } catch (throwable: Throwable) {
            module.logd(Log.WARN, TAG, "截图 BACK 拦截安装失败: $throwable")
        }

        val dispatch = Activity::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        module.hook(dispatch).intercept { chain ->
            val self = chain.instanceOrNull
            val arg = chain.arg(0)
            if (self is Activity && arg is MotionEvent) {
                try {
                    if (ScreenshotSelectionManager.get().onActivityTouch(self, arg)) {
                        return@intercept java.lang.Boolean.TRUE
                    }
                } catch (throwable: Throwable) {
                    module.logd(Log.DEBUG, TAG, "截图触摸处理失败: $throwable")
                }
            }
            chain.proceed()
        }
    }

    private fun onPaused(activity: Activity) {
        if (!isTarget(activity)) {
            return
        }
        if (resumedActivity.get() !== activity) {
            return
        }
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
        if (ScreenshotSelectionManager.get().isActive()) {
            ScreenshotSelectionManager.get().exit()
        }
        removeActionBar()
        removeEntryButton()
    }

    private fun refreshFor(activity: Activity) {
        if (!isTarget(activity)) {
            return
        }
        resumedActivity = WeakReference(activity)
        verifyIdsOnce(activity)
        polling = true
        entrySuspendedUntil = SystemClock.uptimeMillis() + RESUME_SETTLE_MS
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
    }

    private fun refreshEntry(activity: Activity) {
        if (capturing) {
            removeEntryButton()
            return
        }
        if (ScreenshotSelectionManager.get().isActive()) {
            removeEntryButton()
            ensureActionBar()
            return
        }
        if (hasTargets(activity)) {
            ensureEntryButton(activity)
        } else {
            removeEntryButton()
        }
    }

    private fun ensureActionBar() {
        var activity = barHost.get()
        if (activity == null || activity.isFinishing) {
            activity = resumedActivity.get()
        }
        if (activity == null || activity.isFinishing) {
            return
        }
        val bar = actionBar
        if (bar != null && bar.parent is ViewGroup) {
            return
        }
        module.logd(
            Log.INFO, TAG, "截图工具条已脱离父容器，重新挂载 activity="
                    + activity.javaClass.simpleName
        )
        barHost = WeakReference(activity)
        showActionBar(activity)
    }

    private fun hasTargets(activity: Activity): Boolean {
        if (activity.window == null) {
            return false
        }
        val decor: View = activity.window!!.decorView
        val body = ScreenshotSelectionManager.get().findPostBody(activity)
        val comment = matchesCommentBlock(decor, activity)
        val targets = body != null || comment
        if (targets && ScreenshotSelectionManager.get().isDebug()) {
            Log.i(
                "BetterHeybox", "截图诊断 入口判定 正文=" + describe(body)
                        + " 评论块=" + comment + " activity=" + activity.javaClass.simpleName
            )
        }
        return targets
    }

    private fun describe(view: View?): String {
        if (view == null) {
            return "null"
        }
        return view.javaClass.simpleName + "#" + ScreenshotIds.entryName(view) +
                "(w=" + view.width + ",h=" + view.height + ")"
    }

    private fun matchesCommentBlock(decor: View, context: Context): Boolean {
        val id = ScreenshotIds.id(context, ScreenshotIds.COMMENT_BLOCK)
        if (id == 0) {
            return false
        }
        val block = ScreenshotIds.findDescendantById(decor, id)
        return block != null && block.isShown && block.height > 100
    }

    private fun ensureEntryButton(activity: Activity) {
        val content = contentOf(activity) ?: return
        if (entryButton != null && entryButton!!.parent === content) {
            return
        }
        removeEntryButton()
        val context: Context = activity
        val dark = ThemeUtils.isDarkMode(context)
        val button = TextView(context)
        button.text = "截图"
        button.textSize = 13f
        button.setTextColor(0xFFFFFFFF.toInt())
        button.gravity = Gravity.CENTER
        button.setCompoundDrawablesWithIntrinsicBounds(
            CameraIconDrawable(0xFFFFFFFF.toInt(), context.resources.displayMetrics.density),
            null, null, null
        )
        button.compoundDrawablePadding = ThemeUtils.dp(context, 5f)
        val padH = ThemeUtils.dp(context, 12f)
        val padV = ThemeUtils.dp(context, 8f)
        button.setPadding(padH, padV, padH, padV)
        val background = GradientDrawable()
        background.shape = GradientDrawable.RECTANGLE
        background.cornerRadius = ThemeUtils.dp(context, 20f).toFloat()
        background.setColor(if (dark) 0xE6333338.toInt() else 0xE61F2937.toInt())
        background.setStroke(ThemeUtils.dp(context, 1f), 0x55FFFFFF)
        button.background = background
        button.alpha = 0.92f
        button.elevation = ThemeUtils.dp(context, 4f).toFloat()
        button.setOnClickListener {
            module.logd(Log.INFO, TAG, "截图入口被点击")
            var current = resumedActivity.get()
            if (current == null || current.isFinishing) {
                current = activity
            }
            enterSelectionMode(current)
        }
        val margin = ThemeUtils.dp(context, 12f)
        val params: ViewGroup.LayoutParams
        if (content is FrameLayout) {
            val frameParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            )
            frameParams.rightMargin = margin
            frameParams.bottomMargin = ThemeUtils.dp(context, 140f)
            params = frameParams
        } else {
            params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        try {
            content.addView(button, params)
            entryButton = button
            ScreenshotSelectionManager.get().ignore(button)
            animateIn(button, ThemeUtils.dp(context, 10f), 0.92f)
        } catch (throwable: Throwable) {
            module.logd(Log.DEBUG, TAG, "截图入口按钮添加失败: $throwable")
        }
    }

    private fun removeEntryButton() {
        val button = entryButton
        entryButton = null
        if (button != null) {
            ScreenshotSelectionManager.get().unignore(button)
        }
        animateOut(button)
    }

    fun enterSelectionMode(activity: Activity?) {
        if (activity == null || activity.isFinishing) {
            return
        }
        val entered = ScreenshotSelectionManager.get().enter(activity)
        module.logd(
            Log.INFO, TAG, "进入多选模式 entered=$entered activity="
                    + activity.javaClass.simpleName
        )
        if (!entered) {
            toast(activity, "当前页面没有可截图的内容")
            return
        }
        barHost = WeakReference(activity)
        ScreenshotSelectionManager.get().logBodySearch(activity)
    }

    private fun onSelectionModeChanged(active: Boolean) {
        if (active) {
            var activity = barHost.get()
            if (activity == null || activity.isFinishing) {
                activity = resumedActivity.get()
            }
            if (activity == null || activity.isFinishing) {
                ScreenshotSelectionManager.get().exit()
                return
            }
            barHost = WeakReference(activity)
            removeEntryButton()
            showActionBar(activity)
        } else {
            removeActionBar()
            val activity = resumedActivity.get()
            if (activity != null && !activity.isFinishing) {
                refreshEntry(activity)
            }
        }
    }

    private fun showActionBar(activity: Activity) {
        val content = contentOf(activity) ?: return
        detachActionBar()
        val context: Context = activity
        val dark = ThemeUtils.isDarkMode(context)
        val surface = if (dark) 0xFF232327.toInt() else 0xFFFFFFFF.toInt()
        val textColor = if (dark) 0xFFECECEC.toInt() else 0xFF1F1F1F.toInt()

        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(
            ThemeUtils.dp(context, 12f), ThemeUtils.dp(context, 8f),
            ThemeUtils.dp(context, 12f), ThemeUtils.dp(context, 8f)
        )
        val background = GradientDrawable()
        background.shape = GradientDrawable.RECTANGLE
        background.cornerRadius = ThemeUtils.dp(context, 14f).toFloat()
        background.setColor(surface)
        background.setStroke(
            ThemeUtils.dp(context, 1f),
            if (dark) 0x33FFFFFF else 0x14000000
        )
        bar.background = background
        bar.elevation = ThemeUtils.dp(context, 8f).toFloat()

        countView = TextView(context)
        countView!!.setTextColor(textColor)
        countView!!.textSize = 13f
        countView!!.text = "已选 0"
        bar.addView(
            countView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        postChip = newChip(context, "帖子", textColor)
        postChip!!.setOnClickListener {
            val host = barHost.get() ?: return@setOnClickListener
            val postBody = ScreenshotSelectionManager.get().findPostBody(host)
            if (postBody != null) {
                ScreenshotSelectionManager.get().togglePostBody(postBody)
            }
        }
        bar.addView(postChip)

        val selectAll = newChip(context, "全选", textColor)
        selectAll.setOnClickListener { ScreenshotSelectionManager.get().selectAll() }
        bar.addView(selectAll)

        val save = newChip(context, "保存", ACCENT)
        save.setOnClickListener { requestCapture(false) }
        bar.addView(save)

        val share = newChip(context, "分享", ACCENT)
        share.setOnClickListener { requestCapture(true) }
        bar.addView(share)

        val exit = newChip(context, "退出", textColor)
        exit.setOnClickListener { ScreenshotSelectionManager.get().exit() }
        bar.addView(exit)

        val margin = ThemeUtils.dp(context, 10f)
        val params: ViewGroup.LayoutParams
        if (content is FrameLayout) {
            val frameParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            frameParams.leftMargin = margin
            frameParams.rightMargin = margin
            frameParams.bottomMargin = margin
            params = frameParams
        } else {
            params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        try {
            content.addView(bar, params)
            actionBar = bar
            ScreenshotSelectionManager.get().ignore(bar)
            animateIn(bar, ThemeUtils.dp(context, 16f), 1f)
            module.logd(Log.INFO, TAG, "截图工具条已显示")
        } catch (throwable: Throwable) {
            module.logd(Log.DEBUG, TAG, "截图工具条添加失败: $throwable")
            return
        }
        updateBarState()
    }

    private fun updateBarState() {
        if (countView != null) {
            countView!!.text = "已选 " + ScreenshotSelectionManager.get().count()
        }
        val chip = postChip ?: return
        val activity = barHost.get() ?: return
        if (activity.isFinishing) {
            return
        }
        val postBody = ScreenshotSelectionManager.get().findPostBody(activity)
        if (postBody == null) {
            chip.visibility = View.GONE
            return
        }
        chip.visibility = View.VISIBLE
        applyChipBackground(chip, ScreenshotSelectionManager.get().isSelected(postBody), activity)
    }

    private fun removeActionBar() {
        val bar = actionBar
        actionBar = null
        countView = null
        postChip = null
        if (bar != null) {
            ScreenshotSelectionManager.get().unignore(bar)
        }
        animateOut(bar)
    }

    private fun detachActionBar() {
        val bar = actionBar
        actionBar = null
        countView = null
        postChip = null
        if (bar != null) {
            ScreenshotSelectionManager.get().unignore(bar)
            detach(bar)
        }
    }

    private fun animateIn(view: View?, offsetPx: Int, endAlpha: Float) {
        if (view == null) {
            return
        }
        view.alpha = 0f
        view.translationY = offsetPx.toFloat()
        view.animate().alpha(endAlpha).translationY(0f).setDuration(UI_ANIM_MS).start()
    }

    private fun animateOut(view: View?) {
        if (view == null) {
            return
        }
        if (view.parent !is ViewGroup) {
            return
        }
        view.animate().alpha(0f)
            .translationY(ThemeUtils.dp(view.context, 8f).toFloat())
            .setDuration(UI_ANIM_MS)
            .withEndAction { detach(view) }
            .start()
        mainHandler.postDelayed({ detach(view) }, UI_ANIM_MS + 120L)
    }

    private fun detach(view: View?) {
        if (view == null) {
            return
        }
        view.animate().cancel()
        if (view.parent is ViewGroup) {
            (view.parent as ViewGroup).removeView(view)
        }
    }

    private fun newChip(context: Context, label: String, textColor: Int): TextView {
        val chip = TextView(context)
        chip.text = label
        chip.textSize = 13f
        chip.setTextColor(textColor)
        chip.gravity = Gravity.CENTER
        chip.isClickable = true
        chip.setPadding(
            ThemeUtils.dp(context, 12f), ThemeUtils.dp(context, 6f),
            ThemeUtils.dp(context, 12f), ThemeUtils.dp(context, 6f)
        )
        applyChipBackground(chip, false, context)
        return chip
    }

    private fun applyChipBackground(chip: TextView, selected: Boolean, context: Context) {
        val background = GradientDrawable()
        background.shape = GradientDrawable.RECTANGLE
        background.cornerRadius = ThemeUtils.dp(context, 10f).toFloat()
        background.setColor(if (selected) ACCENT else 0x00000000)
        background.setStroke(ThemeUtils.dp(context, 1f), if (selected) ACCENT else 0x22FFFFFF)
        chip.background = background
    }

    private fun requestCapture(share: Boolean) {
        val activity = barHost.get() ?: return
        if (activity.isFinishing) {
            return
        }
        val manager = ScreenshotSelectionManager.get()
        val selections = manager.orderedValidSelections()
        val wanted = manager.count()
        if (selections.isEmpty()) {
            toast(activity, "请先选择要截图的贴文或留言")
            return
        }
        val units = ArrayList<View>()
        for (selection in selections) {
            units.add(selection.view)
        }
        if (manager.isDebug()) {
            for (i in selections.indices) {
                val selection = selections[i]
                val unit = selection.view
                Log.i(
                    "BetterHeybox", "截图诊断 选择[$i] pos="
                            + selection.adapterPosition + " w=" + unit.width
                            + " h=" + unit.height + " 在窗口内=" + isMostlyOnScreen(unit)
                )
            }
            Log.i("BetterHeybox", "截图诊断 选择汇总 选中=$wanted 有效=${selections.size}")
        }
        manager.exit()

        val folded = ScreenshotRenderer.expandFoldedTexts(units)
        var hasWebView = false
        for (unit in units) {
            if (ScreenshotSelectionManager.isWebView(unit)) {
                hasWebView = true
                break
            }
        }
        if (folded.clicked || hasWebView) {
            mainHandler.postDelayed(
                { finishCapture(activity, units, wanted, share, folded) },
                if (folded.clicked) 400L else 260L
            )
        } else {
            finishCapture(activity, units, wanted, share, folded)
        }
    }

    private fun finishCapture(
        activity: Activity, units: List<View>, wanted: Int,
        share: Boolean, folded: ScreenshotRenderer.FoldedText
    ) {
        capturing = true
        removeEntryButton()
        captureUnitsAsync(activity, units) { image ->
            capturing = false
            folded.restore()
            if (image == null) {
                toast(activity, "没有可截图的内容")
                return@captureUnitsAsync
            }
            if (wanted > units.size) {
                toast(
                    activity, "已选择 " + wanted + " 项，其中 " + (wanted - units.size)
                            + " 项已划出屏幕，未包含在截图中"
                )
            }
            saveAndShare(activity.applicationContext, image, share, SOURCE_SELECTION)
        }
    }

    private fun captureUnitsAsync(
        activity: Activity,
        units: List<View>,
        callback: ScreenshotRenderer.BitmapCallback
    ) {
        captureUnitAt(activity, units, 0, ScreenshotRenderer.backgroundColor(activity), ArrayList(), callback)
    }

    private fun captureUnitAt(
        activity: Activity, units: List<View>, index: Int,
        background: Int, parts: MutableList<Bitmap>,
        callback: ScreenshotRenderer.BitmapCallback
    ) {
        if (index >= units.size) {
            var composed: Bitmap? = null
            if (parts.isNotEmpty()) {
                if (parts.size == 1) {
                    composed = parts[0]
                } else {
                    composed = ScreenshotRenderer.composeVertical(activity, parts, background)
                    for (part in parts) {
                        if (!part.isRecycled) {
                            part.recycle()
                        }
                    }
                }
            }
            callback.onCaptured(composed)
            return
        }
        val unit = units[index]
        val cleanup = ScreenshotCleanupHelper()
        Log.i(
            "BetterHeybox", "截图诊断 截图路径 单元=" + unit.javaClass.name
                    + " WebView=" + ScreenshotSelectionManager.isWebView(unit)
                    + " w=" + unit.width + " h=" + unit.height
                    + " 屏幕内=" + isMostlyOnScreen(unit)
        )

        val originalBounds = intArrayOf(
            unit.left, unit.top, unit.right, unit.bottom
        )
        try {
            cleanup.hideInside(unit)
        } catch (throwable: Throwable) {
            module.logd(Log.DEBUG, TAG, "隐藏无关内容失败: $throwable")
        }
        if (!ScreenshotSelectionManager.isWebView(unit)) {
            try {
                ScreenshotRenderer.layoutForCapture(unit)
                val again = cleanup.hideInside(unit)
                if (again > 0 && ScreenshotSelectionManager.get().isDebug()) {
                    Log.i("BetterHeybox", "截图诊断 补隐藏=$again")
                }
            } catch (throwable: Throwable) {
                module.logd(Log.DEBUG, TAG, "补隐藏失败: $throwable")
            }
        }
        val next = ScreenshotRenderer.BitmapCallback { part ->
            try {
                WebViewHider.restore(unit)
            } catch (throwable: Throwable) {
                module.logd(Log.DEBUG, TAG, "还原网页隐藏失败: $throwable")
            }
            try {
                cleanup.restore()
            } catch (throwable: Throwable) {
                module.logd(Log.DEBUG, TAG, "还原隐藏内容失败: $throwable")
            }
            if (part != null) {
                if (ScreenshotSelectionManager.get().isDebug()) {
                    Log.i(
                        "BetterHeybox", "截图诊断 单元视图 h=" + unit.height
                                + " w=" + unit.width + " -> 位图 " + part.width + "x"
                                + part.height
                    )
                }
                parts.add(part)
            }
            captureUnitAt(activity, units, index + 1, background, parts, callback)
        }
        if (ScreenshotSelectionManager.isWebView(unit)) {
            val started = booleanArrayOf(false)
            val start = Runnable {
                if (!started[0]) {
                    started[0] = true
                    ScreenshotRenderer.captureWebViewAsync(activity, unit, background, next)
                }
            }
            var injected = false
            try {
                injected = WebViewHider.hide(unit, ScreenshotCleanupHelper.currentRules(), start)
            } catch (throwable: Throwable) {
                module.logd(Log.DEBUG, TAG, "注入隐藏脚本失败: $throwable")
            }
            if (!injected) {
                start.run()
            }
            return
        }
        var part: Bitmap? = null
        try {
            part = ScreenshotRenderer.captureUnit(unit, background)
        } catch (throwable: Throwable) {
            module.logd(Log.ERROR, TAG, "截图失败: $throwable", throwable)
        } finally {
            try {
                unit.layout(originalBounds[0], originalBounds[1], originalBounds[2], originalBounds[3])
                unit.requestLayout()
            } catch (ignored: Throwable) {
            }
        }
        next.onCaptured(part)
    }

    private fun saveAndShare(context: Context, image: Bitmap, share: Boolean) {
        saveAndShare(context, image, share, SOURCE_SELECTION)
    }

    private fun saveAndShare(context: Context, image: Bitmap, share: Boolean, source: Int) {
        val shareNoSave = HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_SHARE_NO_SAVE, true)
        Thread(Runnable {
            var result: Uri? = null
            var fellBackToGallery = false
            var fellBackToCache = false
            var failure: String? = null
            try {
                if (share) {
                    if (shareNoSave) {
                        result = ScreenshotExporter.prepareShareScreenshot(image, context)
                        if (result == null) {
                            fellBackToGallery = true
                            result = ScreenshotExporter.saveScreenshot(image, context)
                        }
                    } else {
                        result = ScreenshotExporter.saveScreenshot(image, context)
                        if (result == null) {
                            fellBackToCache = true
                            result = ScreenshotExporter.prepareShareScreenshot(image, context)
                        }
                    }
                } else {
                    result = ScreenshotExporter.saveScreenshot(image, context)
                }
            } catch (throwable: Throwable) {
                failure = throwable.toString()
            }
            val uri = result
            val fellBack = fellBackToGallery
            val cacheFallback = fellBackToCache
            val error = failure
            mainHandler.post {
                if (uri == null) {
                    module.logd(
                        Log.ERROR, TAG,
                        (if (share) "准备分享截图失败: " else "保存截图失败: ") + error
                    )
                    toast(context, if (share) "分享截图失败" else saveFailureText(source))
                    return@post
                }
                if (share) {
                    if (fellBack) {
                        module.logd(Log.WARN, TAG, "FileProvider 不可用，已回退相册并分享: $uri")
                    } else if (cacheFallback) {
                        module.logd(Log.WARN, TAG, "相册保存失败，已回退缓存临时文件并分享: $uri")
                    } else if (shareNoSave) {
                        module.logd(Log.INFO, TAG, "分享截图使用缓存临时文件: $uri")
                    } else {
                        module.logd(Log.INFO, TAG, "分享截图已保存到相册: $uri")
                    }
                    try {
                        ScreenshotExporter.shareScreenshot(context, uri)
                    } catch (throwable: Throwable) {
                        module.logd(Log.ERROR, TAG, "分享截图失败: $throwable", throwable)
                        toast(context, "分享失败")
                    }
                } else {
                    val suffix = if (source == SOURCE_COMMENT) "（单条长按截图 #50）" else ""
                    module.logd(
                        Log.INFO, TAG, "截图保存完成: $uri$suffix"
                    )
                    toast(context, if (source == SOURCE_COMMENT) COMMENT_SAVED_TEXT else SAVED_TEXT)
                }
            }
        }, if (share) "heybox-screenshot-share" else "heybox-screenshot-save").start()
    }

    fun captureCommentDirectly(activity: Activity?, anyViewInComment: View?, source: Int) {
        if (activity == null || anyViewInComment == null) {
            return
        }
        mainHandler.post {
            val unit = ScreenshotUnit.resolve(anyViewInComment)
            if (unit == null) {
                toast(
                    activity,
                    if (source == SOURCE_COMMENT) "没有识别到这条评论" else "没有识别到这条留言"
                )
                return@post
            }
            if (ScreenshotSelectionManager.get().isActive()) {
                ScreenshotSelectionManager.get().exit()
            }
            capturing = true
            removeEntryButton()
            captureUnitsAsync(activity, listOf(unit.view)) { image ->
                capturing = false
                if (image == null) {
                    toast(activity, if (source == SOURCE_COMMENT) "截图失败，请重试" else "截图失败")
                    return@captureUnitsAsync
                }
                module.logd(
                    Log.INFO, TAG, "单条评论截图已渲染: "
                            + image.width + "x" + image.height
                            + " 来源=" + (if (source == SOURCE_COMMENT) "长按评论" else "复制菜单")
                )
                saveAndShare(activity.applicationContext, image, false, source)
            }
        }
    }

    fun captureCommentDirectly(activity: Activity?, anyViewInComment: View?) {
        captureCommentDirectly(activity, anyViewInComment, SOURCE_SELECTION)
    }

    private fun contentOf(activity: Activity): ViewGroup? {
        val content: View? = activity.findViewById(android.R.id.content)
        return content as? ViewGroup
    }

    private fun isTarget(activity: Activity): Boolean {
        return try {
            MainModule.TARGET_PKG == activity.packageName
        } catch (throwable: Throwable) {
            false
        }
    }

    private fun toast(context: Context, message: String) {
        try {
            CommentCopyHook.noteOwnToast(message)
        } catch (ignored: Throwable) {
        }
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
        }
    }

    private fun verifyIdsOnce(activity: Activity) {
        if (idsLogged) {
            return
        }
        idsLogged = true
        try {
            var found = 0
            val missing = StringBuilder()
            for (name in ScreenshotIds.VERIFY) {
                if (ScreenshotIds.id(activity, name) != 0) {
                    found++
                } else {
                    missing.append(name).append(' ')
                }
            }
            module.logd(
                Log.INFO, TAG, "截图资源 id 校验: 命中 " + found + "/" + ScreenshotIds.VERIFY.size
                        + (if (missing.isNotEmpty()) " 缺失=$missing" else "")
            )
        } catch (throwable: Throwable) {
            module.logd(Log.DEBUG, TAG, "截图资源 id 校验异常: $throwable")
        }
    }

    private class CameraIconDrawable(color: Int, val density: Float) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1.4f, 1.5f * density)
            paint.strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            val left = bounds.left + width * 0.06f
            val right = bounds.right - width * 0.06f
            val top = bounds.top + height * 0.26f
            val bottom = bounds.bottom - height * 0.10f
            val radius = height * 0.18f
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)

            canvas.drawLine(left + width * 0.30f, top, left + width * 0.30f,
                bounds.top + height * 0.12f, paint)
            canvas.drawLine(right - width * 0.30f, top, right - width * 0.30f,
                bounds.top + height * 0.12f, paint)

            canvas.drawCircle(bounds.exactCenterX(), (top + bottom) / 2f, height * 0.20f, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        @Deprecated("Deprecated in Java")
        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = max(1, (16 * density).toInt())

        override fun getIntrinsicHeight(): Int = max(1, (16 * density).toInt())
    }

    companion object {

        private const val TAG = "BetterHeybox"
        private const val POLL_INTERVAL_MS = 1200L

        private const val RESUME_SETTLE_MS = 260L
        private const val UI_ANIM_MS = 140L
        private const val ACCENT = 0xFF3B82F6.toInt()
        private const val SAVED_TEXT = "已保存到相册 Pictures/Heybox"
        private const val COMMENT_SAVED_TEXT = "单条评论截图已保存到相册 Pictures/Heybox"

        const val SOURCE_SELECTION = 0
        const val SOURCE_COMMENT = 1

        @Volatile
        private var instance: ScreenshotCaptureHook? = null

        @JvmStatic
        fun getInstance(): ScreenshotCaptureHook? = instance

        private fun isMostlyOnScreen(view: View?): Boolean {
            if (view == null || view.width <= 0 || view.height <= 0) {
                return false
            }
            val visible = Rect()
            if (!view.getGlobalVisibleRect(visible)) {
                return false
            }
            val area = view.width.toLong() * view.height.toLong()
            return visible.width().toLong() * visible.height().toLong() * 2 >= area
        }

        private fun saveFailureText(source: Int): String =
            if (source == SOURCE_COMMENT) "单条评论截图保存失败" else "保存截图失败"
    }
}
