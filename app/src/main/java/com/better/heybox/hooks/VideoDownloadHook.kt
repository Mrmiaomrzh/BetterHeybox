package com.better.heybox.hooks

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.better.heybox.App
import com.better.heybox.MainModule
import com.better.heybox.ThemeUtils
import com.better.heybox.VideoDownloadManager
import com.better.heybox.ViewUtils
import java.lang.ref.WeakReference
import java.util.WeakHashMap

class VideoDownloadHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        hookW(cl)
    }

    private fun hookW(cl: ClassLoader) {
        var firstError: Throwable? = null
        val methods = arrayOf(
            "setVideoRes",
            "setVideoRes",
            "W"
        )
        val signatures: Array<Array<Class<*>>> = arrayOf(
            arrayOf(String::class.java),
            arrayOf(String::class.java, java.util.Map::class.java),
            arrayOf(String::class.java, java.util.Map::class.java)
        )
        for (i in methods.indices) {
            try {
                val absVideoView = Class.forName("com.max.video.AbsVideoView", false, cl)
                val method = absVideoView.getDeclaredMethod(methods[i], *signatures[i])
                val label = methods[i] + if (signatures[i].size == 2) "(String, Map)" else "(String)"
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val args = chain.args
                        val thisObj = chain.instanceOrNull
                        val url = if (args.isNotEmpty() && args[0] is String)
                            args[0] as String else null
                        val headers = if (args.size > 1 && args[1] is Map<*, *>)
                            args[1] as Map<String, String> else null
                        if (url != null && !url.isEmpty()) {
                            module.logd(
                                Log.INFO, MainModule.TAG,
                                "捕获视频URL[" + label + "]: " + shorten(url)
                            )
                            onVideoUrl(thisObj, url, headers)
                        }
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "视频 URL 捕获处理异常: " + t)
                    }
                    result
                }
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "✔ 视频下载入口 Hook 已安装: AbsVideoView." + label
                )
            } catch (t: Throwable) {
                if (firstError == null) {
                    firstError = t
                }
                module.logd(Log.WARN, MainModule.TAG, "✘ 视频 URL 入口 Hook 失败: " + methods[i], t)
            }
        }
        if (firstError != null) {
            module.logd(Log.WARN, MainModule.TAG, "部分视频 URL 入口未安装（见上）")
        }
    }

    private fun onVideoUrl(viewObject: Any?, url: String, headers: Map<String, String>?) {
        val enabled: Boolean = try {
            module.isEnabled(App.KEY_VIDEO_DOWNLOAD, true)
        } catch (t: Throwable) {
            true
        }
        if (viewObject !is View) {
            return
        }
        val videoView = viewObject
        if (!isVideoPageHost(videoView)) {
            return
        }
        VideoDownloadManager.get().init(videoView.context)
        val decor = ViewUtils.findDecor(videoView)
        if (decor == null) {
            return
        }
        var controller = EntryController.get(decor)
        if (!enabled || !VideoDownloadManager.isSupportedUrl(url)) {
            if (controller != null) {
                controller.removeCandidate(videoView)
            }
            return
        }
        if (controller == null) {
            controller = EntryController(module, decor)
            EntryController.put(decor, controller)
        }
        controller.addCandidate(videoView, url, headers)
        controller.sync()
    }

    private class EntryController(private val module: MainModule, private val decor: ViewGroup) {

        private class Candidate(
            val video: WeakReference<View>,
            val url: String,
            val headers: Map<String, String>?
        )

        private val candidates = ArrayList<Candidate>()

        private val decorScrollListener = object : ViewTreeObserver.OnScrollChangedListener {
            override fun onScrollChanged() {
                sync()
            }
        }

        private val decorDrawListener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                sync()
            }
        }

        private var listenersAttached = false

        private var entry: DownloadFab? = null
        private var sheet: DownloadSheet? = null

        private var active: Candidate? = null

        fun addCandidate(video: View, url: String, headers: Map<String, String>?) {
            removeCandidateInternal(video)
            candidates.add(Candidate(WeakReference(video), url, headers))
            module.logd(
                Log.INFO, MainModule.TAG, "入口候选登记: " + shorten(url)
                        + " (共" + candidates.size + "个候选)"
            )
            decor.postDelayed({ sync() }, 800L)
        }

        fun removeCandidate(video: View) {
            if (removeCandidateInternal(video)) {
                sync()
            }
        }

        private fun removeCandidateInternal(video: View): Boolean {
            var removed = false
            for (i in candidates.size - 1 downTo 0) {
                val v = candidates[i].video.get()
                if (v == null || v === video) {
                    candidates.removeAt(i)
                    removed = true
                }
            }
            return removed
        }

        fun sync() {
            try {
                syncInternal()
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "入口同步异常: " + t)
            }
        }

        private fun syncInternal() {
            ensureEntry()
            val e = entry
            if (glassSettingsVisible) {
                active = null
                if (e != null && e.visibility != View.GONE) {
                    e.visibility = View.GONE
                }
                return
            }
            var chosen: Candidate? = null
            var chosenAnchor: View? = null
            val decorH = decor.height
            for (i in candidates.size - 1 downTo 0) {
                val c = candidates[i]
                val v = c.video.get()
                if (v == null) {
                    candidates.removeAt(i)
                    continue
                }
                val anchor = anchorOf(v)
                if (chosen == null && anchor.isShown && anchor.width > 0
                    && anchor.windowToken != null
                    && intersectsDecor(anchor, decorH)
                    && !coveredBySiblingLayer(anchor, decor)
                ) {
                    chosen = c
                    chosenAnchor = anchor
                }
            }
            active = chosen
            if (e == null) {
                return
            }
            if (chosen == null) {
                if (e.visibility != View.GONE) {
                    e.visibility = View.GONE
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "入口隐藏（无可见候选，候选数=" + candidates.size + "）"
                    )
                }
                return
            }
            e.bind(chosen.url)
            val video = chosenAnchor!!
            val loc = IntArray(2)
            video.getLocationInWindow(loc)
            val margin = ThemeUtils.dp(video.context, 10f)
            var entryW = e.measuredWidth
            var entryH = e.measuredHeight
            if (entryW <= 0) {
                e.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                entryW = e.measuredWidth
                entryH = e.measuredHeight
            }
            var left = loc[0] + video.width - margin - entryW
            var top = loc[1] + margin
            val maxLeft = decor.width - entryW - margin
            val maxTop = decor.height - entryH - margin
            if (left < margin) {
                left = margin
            }
            if (left > maxLeft) {
                left = maxLeft
            }
            if (top < margin) {
                top = margin
            }
            if (top > maxTop) {
                top = maxTop
            }
            val lp = e.layoutParams as FrameLayout.LayoutParams
            if (e.visibility != View.VISIBLE
                || lp.leftMargin != left || lp.topMargin != top
            ) {
                e.visibility = View.VISIBLE
                lp.leftMargin = left
                lp.topMargin = top
                e.layoutParams = lp
            }
            e.invalidate()
        }

        private fun anchorOf(v: View): View {
            var cur = v
            var p: ViewParent? = v.parent
            while (cur.width <= 0 && p is ViewGroup) {
                cur = p
                p = cur.parent
            }
            return cur
        }

        private fun intersectsDecor(v: View, decorH: Int): Boolean {
            val xy = IntArray(2)
            v.getLocationInWindow(xy)
            val top = xy[1]
            val bottom = top + v.height
            val visible = Math.min(bottom, decorH) - Math.max(top, 0)
            return visible >= ThemeUtils.dp(v.context, 64f)
        }

        private fun coveredBySiblingLayer(anchor: View, decor: ViewGroup): Boolean {
            var px = anchor.width / 2f
            var py = anchor.height / 2f
            var cur = anchor
            var par: ViewParent? = anchor.parent
            while (par is ViewGroup) {
                val parent: ViewGroup = par
                if (parent === decor) {
                    break
                }
                px += cur.left + cur.translationX
                py += cur.top + cur.translationY
                val index = parent.indexOfChild(cur)
                for (j in parent.childCount - 1 downTo index + 1) {
                    val sib = parent.getChildAt(j)
                    if (sib.visibility != View.VISIBLE) {
                        continue
                    }
                    val sx = px - sib.left - sib.translationX
                    val sy = py - sib.top - sib.translationY
                    if (sx >= 0 && sy >= 0 && sx < sib.width && sy < sib.height
                        && occludesAt(sib, sx, sy)
                    ) {
                        return true
                    }
                }
                cur = parent
                par = parent.parent
            }
            return false
        }

        private fun occludesAt(v: View, px: Float, py: Float): Boolean {
            if (v.isOpaque) {
                return true
            }
            if (v !is ViewGroup) {
                return false
            }
            for (i in v.childCount - 1 downTo 0) {
                val child = v.getChildAt(i)
                if (child.visibility != View.VISIBLE) {
                    continue
                }
                val cx = px - child.left - child.translationX
                val cy = py - child.top - child.translationY
                if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height) {
                    if (occludesAt(child, cx, cy)) {
                        return true
                    }
                }
            }
            return false
        }

        private fun ensureEntry() {
            val existing = entry
            if (existing != null && existing.parent === decor) {
                if (!listenersAttached) {
                    attachListeners()
                }
                return
            }
            val fab = DownloadFab(decor.context)
            entry = fab
            fab.setOnClickListener { showSheet() }
            decor.addView(
                fab,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            attachListeners()
            fab.bringToFront()
        }

        private fun attachListeners() {
            try {
                val vto = decor.viewTreeObserver
                vto.addOnScrollChangedListener(decorScrollListener)
                vto.addOnDrawListener(decorDrawListener)
                listenersAttached = true
            } catch (ignored: Throwable) {
            }
        }

        private fun showSheet() {
            val current = active
            if (current == null || !VideoDownloadManager.isSupportedUrl(current.url)) {
                return
            }
            dismissSheet()
            val created = DownloadSheet(decor.context, decor)
            sheet = created
            val videoView = current.video.get()
            created.show(
                current.url, current.headers,
                if (videoView != null) cardTitle(videoView) else null
            )
        }

        private fun dismissSheet() {
            val current = sheet
            if (current != null) {
                current.dismiss()
                sheet = null
            }
        }

        companion object {

            private val CONTROLLERS: MutableMap<ViewGroup, EntryController?> = WeakHashMap()

            fun get(decor: ViewGroup): EntryController? =
                synchronized(CONTROLLERS) { CONTROLLERS[decor] }

            fun put(decor: ViewGroup, controller: EntryController) {
                synchronized(CONTROLLERS) { CONTROLLERS[decor] = controller }
            }

            fun syncAll() {
                synchronized(CONTROLLERS) {
                    for (controller in CONTROLLERS.values) {
                        if (controller != null) {
                            controller.sync()
                        }
                    }
                }
            }
        }
    }

    private object BottomSheetFallback {
        fun videoTitle(context: Context): String? {
            val activity = ViewUtils.findActivity(context)
            if (activity == null) {
                return null
            }
            val raw = activity.title
            if (raw == null) {
                return null
            }
            var title = raw.toString().trim()
            val sep = title.indexOf(" - ")
            if (sep > 0) {
                title = title.substring(0, sep).trim()
            }
            if (title.isEmpty() || title.contains("小黑盒")) {
                return null
            }
            return title
        }
    }

    private class DownloadFab(context: Context) : View(context) {

        private companion object {
            const val PHASE_IDLE = 0
            const val PHASE_RUNNING = 1
            const val PHASE_DONE = 2
            const val PHASE_RETRY = 3
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringRect = RectF()
        private val path = Path()
        private var boundUrl: String? = null
        private var phase = PHASE_IDLE
        private var progress = -1

        private val taskListener: VideoDownloadManager.TaskListener =
            object : VideoDownloadManager.TaskListener {
                override fun onTasksChanged() {
                    refreshTaskState()
                    invalidate()
                }
            }

        init {
            val size = ThemeUtils.dp(context, 44f)
            isClickable = true
            isFocusable = true
            contentDescription = "下载视频"
            elevation = ThemeUtils.dp(context, 4f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, width, height)
                }
            }
            clipToOutline = true
            foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, circleMask())
            setOnTouchListener(object : View.OnTouchListener {
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> animate().scaleX(0.9f).scaleY(0.9f)
                            .setDuration(ThemeUtils.ANIM_PRESS_MS)
                            .setInterpolator(DecelerateInterpolator()).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(100L)
                            .setInterpolator(DecelerateInterpolator()).start()
                    }
                    return false
                }
            })
        }

        private fun circleMask(): Drawable {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            return d
        }

        fun bind(url: String) {
            boundUrl = url
            refreshTaskState()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            VideoDownloadManager.get().addListener(taskListener)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            VideoDownloadManager.get().removeListener(taskListener)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val d = ThemeUtils.dp(getContext(), 40f)
            setMeasuredDimension(d, d)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width
            val h = height
            if (w <= 0) {
                return
            }
            val context = getContext()

            if (paint.shader !is LinearGradient) {
                paint.shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    ThemeUtils.resolveAccentStrong(context),
                    ThemeUtils.resolveAccent2Strong(context), Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(w / 2f, h / 2f, w / 2f, paint)

            val iconColor = ThemeUtils.readableForegroundOn(
                ThemeUtils.resolveAccentStrong(context)
            )
            paint.shader = null
            paint.color = iconColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ThemeUtils.dp(context, 2f).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            val cx = w / 2f
            val cy = h / 2f
            path.rewind()

            if (phase == PHASE_RUNNING) {
                val ringRadius = w / 2f - ThemeUtils.dp(context, 2.5f)
                ringPaint.style = Paint.Style.STROKE
                ringPaint.strokeWidth = ThemeUtils.dp(context, 2.5f).toFloat()
                ringPaint.color = ThemeUtils.withAlpha(iconColor, 0x4D)
                ringRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
                canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
                ringPaint.color = iconColor
                val pct = Math.max(0, Math.min(100, progress))
                canvas.drawArc(ringRect, -90f, 360f * pct / 100f, false, ringPaint)
                drawDownloadGlyph(canvas, cx, cy, w * 0.16f, paint, path)
            } else if (phase == PHASE_DONE) {
                val r = w * 0.20f
                path.moveTo(cx - r, cy)
                path.lineTo(cx - r * 0.25f, cy + r * 0.7f)
                path.lineTo(cx + r, cy - r * 0.55f)
                canvas.drawPath(path, paint)
            } else if (phase == PHASE_RETRY) {
                val r = w * 0.2f
                ringRect.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(ringRect, -60f, 285f, false, paint)
                val ax = cx + (r * Math.cos(Math.toRadians(135.0))).toFloat()
                val ay = cy - (r * Math.sin(Math.toRadians(135.0))).toFloat()
                path.moveTo(ax - r * 0.28f, ay - r * 0.05f)
                path.lineTo(ax + r * 0.12f, ay - r * 0.38f)
                path.lineTo(ax + r * 0.3f, ay + r * 0.22f)
                canvas.drawPath(path, paint)
            } else {
                drawDownloadGlyph(canvas, cx, cy, w * 0.2f, paint, path)
            }
        }

        private fun drawDownloadGlyph(
            canvas: Canvas, cx: Float, cy: Float, r: Float,
            paint: Paint, path: Path
        ) {
            path.moveTo(cx, cy - r)
            path.lineTo(cx, cy + r * 0.45f)
            path.moveTo(cx - r * 0.7f, cy - r * 0.15f)
            path.lineTo(cx, cy + r * 0.45f)
            path.lineTo(cx + r * 0.7f, cy - r * 0.15f)
            canvas.drawPath(path, paint)
            canvas.drawLine(cx - r * 0.85f, cy + r, cx + r * 0.85f, cy + r, paint)
        }

        private fun refreshTaskState() {
            val task = VideoDownloadManager.get().findTask(boundUrl)
            if (task == null) {
                phase = PHASE_IDLE
                progress = -1
                return
            }
            when (task.state) {
                VideoDownloadManager.State.PENDING,
                VideoDownloadManager.State.DOWNLOADING,
                VideoDownloadManager.State.PAUSED -> {
                    phase = PHASE_RUNNING
                    progress = task.percent()
                }
                VideoDownloadManager.State.COMPLETED -> phase = PHASE_DONE
                VideoDownloadManager.State.FAILED -> phase = PHASE_RETRY
                else -> phase = PHASE_IDLE
            }
        }
    }

    private class DownloadSheet(private val context: Context, private val decor: ViewGroup) {

        private val accent: Int = ThemeUtils.resolveAccentStrong(context)

        private var scrim: FrameLayout? = null
        private var panel: LinearLayout? = null
        private var content: FrameLayout? = null
        private var url: String? = null
        private var headers: Map<String, String>? = null
        private var title: String? = null

        @Volatile
        private var dismissed = false

        private var shownState: VideoDownloadManager.State? = null

        @Volatile
        private var probeTotal = -1L

        @Volatile
        private var probeSegments = -1
        private var probing = false

        private var progressBar: View? = null
        private var pctText: TextView? = null
        private val progressHolder = IntArray(1)

        private val listener: VideoDownloadManager.TaskListener =
            object : VideoDownloadManager.TaskListener {
                override fun onTasksChanged() {
                    if (dismissed || content == null) {
                        return
                    }
                    val task = VideoDownloadManager.get().findTask(url)
                    val s = shownStateOf(task)
                    if (s != shownState) {
                        rebuild(task)
                    } else if (s == VideoDownloadManager.State.DOWNLOADING
                        || s == VideoDownloadManager.State.PAUSED
                    ) {
                        updateProgress(task!!)
                    }
                }
            }

        fun show(url: String, headers: Map<String, String>?, title: String?) {
            this.url = url
            this.headers = headers
            this.title = title
            this.dismissed = false
            this.shownState = null
            removeExisting()

            val scrimView = FrameLayout(context)
            scrim = scrimView
            scrimView.setBackgroundColor(0x52000000)
            scrimView.isClickable = true
            scrimView.alpha = 0f
            scrimView.setOnClickListener { dismiss() }
            decor.addView(
                scrimView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            scrimView.animate().alpha(1f).setDuration(ThemeUtils.ANIM_SCRIM_IN_MS).start()

            val panelView = LinearLayout(context)
            panel = panelView
            panelView.orientation = LinearLayout.VERTICAL
            panelView.elevation = ThemeUtils.dp(context, 16f).toFloat()
            val bg = GradientDrawable()
            bg.setColor(ThemeUtils.surfaceColor(context))
            bg.setCornerRadii(
                floatArrayOf(
                    ThemeUtils.dp(context, ThemeUtils.RADIUS_SHEET_DP.toFloat()).toFloat(),
                    ThemeUtils.dp(context, ThemeUtils.RADIUS_SHEET_DP.toFloat()).toFloat(),
                    ThemeUtils.dp(context, ThemeUtils.RADIUS_SHEET_DP.toFloat()).toFloat(),
                    ThemeUtils.dp(context, ThemeUtils.RADIUS_SHEET_DP.toFloat()).toFloat(),
                    0f, 0f, 0f, 0f
                )
            )
            panelView.background = bg

            val handle = View(context)
            val handleBg = GradientDrawable()
            handleBg.setColor(ThemeUtils.outlineColor(context))
            handleBg.setCornerRadius(ThemeUtils.dp(context, 2f).toFloat())
            handle.background = handleBg
            val handleLp = LinearLayout.LayoutParams(
                ThemeUtils.dp(context, 32f), ThemeUtils.dp(context, 4f)
            )
            handleLp.gravity = Gravity.CENTER_HORIZONTAL
            handleLp.setMargins(0, ThemeUtils.dp(context, 10f), 0, ThemeUtils.dp(context, 6f))
            panelView.addView(handle, handleLp)
            attachDragToClose(handle)

            val contentView = FrameLayout(context)
            content = contentView
            panelView.addView(
                contentView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            decor.addView(
                panelView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )

            val task = VideoDownloadManager.get().findTask(url)
            rebuild(task)
            panelView.translationY = (panelView.height + ThemeUtils.dp(context, 40f)).toFloat()
            panelView.animate().translationY(0f).setDuration(ThemeUtils.ANIM_SHEET_IN_MS)
                .setInterpolator(DecelerateInterpolator(1.1f)).start()

            VideoDownloadManager.get().addListener(listener)
        }

        private fun attachDragToClose(dragArea: View) {
            val downY = floatArrayOf(0f)
            val lastY = floatArrayOf(0f)
            val lastT = longArrayOf(0L)
            dragArea.setOnTouchListener(object : View.OnTouchListener {
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downY[0] = event.rawY
                            lastY[0] = event.rawY
                            lastT[0] = System.currentTimeMillis()
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dy = event.rawY - downY[0]
                            if (dy > 0) {
                                panel!!.translationY = dy
                            }
                            lastY[0] = event.rawY
                            lastT[0] = System.currentTimeMillis()
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val dy = event.rawY - downY[0]
                            val dt = Math.max(1L, System.currentTimeMillis() - lastT[0])
                            val velocity = (event.rawY - lastY[0]) / dt
                            if (dy > ThemeUtils.dp(context, 96f) || (dy > 24 && velocity > 1.4f)) {
                                dismiss()
                            } else {
                                panel!!.animate().translationY(0f)
                                    .setDuration(ThemeUtils.ANIM_STATE_MS)
                                    .setInterpolator(DecelerateInterpolator()).start()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        private fun rebuild(task: VideoDownloadManager.DownloadTask?) {
            if (dismissed || content == null) {
                return
            }
            content!!.removeAllViews()
            shownState = shownStateOf(task)
            val c = context
            val state = if (task != null) task.state
            else VideoDownloadManager.State.PENDING

            val box = LinearLayout(c)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(
                ThemeUtils.dp(c, 20f), ThemeUtils.dp(c, 8f),
                ThemeUtils.dp(c, 20f), ThemeUtils.dp(c, 16f)
            )
            content!!.addView(
                box,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            if (state == VideoDownloadManager.State.COMPLETED) {
                buildDoneState(c, box, task!!)
            } else if (state == VideoDownloadManager.State.FAILED) {
                buildFailedState(c, box, task!!)
            } else {
                buildHeader(c, box, task)

                if (state == VideoDownloadManager.State.DOWNLOADING) {
                    buildProgressBlock(c, box, task!!)
                    addActiveButtons(
                        c, box, false, "暂停下载",
                        Runnable { VideoDownloadManager.get().pause(url) }
                    )
                } else if (state == VideoDownloadManager.State.PAUSED) {
                    buildProgressBlock(c, box, task!!)
                    addActiveButtons(
                        c, box, true, "继续下载",
                        Runnable { VideoDownloadManager.get().resume(url) }
                    )
                } else {
                    val sizeLine = secondaryText(c, "小黑盒 · 计算大小中…")
                    box.addView(sizeLine)
                    maybeProbe()
                    if (probeTotal > 0) {
                        sizeLine.text = "小黑盒 · 预计 " + VideoDownloadManager
                            .formatSize(probeTotal)
                    } else if (probeSegments > 0) {
                        sizeLine.text = "小黑盒 · " + probeSegments + " 个分段"
                    }
                    addButtons(
                        c, box,
                        sheetButton(
                            c, ButtonStyle.PRIMARY, "开始下载",
                            Runnable {
                                VideoDownloadManager.get().startDownload(url, headers, title)
                            },
                            false
                        ),
                        sheetButton(c, ButtonStyle.TEXT, "取消", null, true)
                    )
                }
            }
        }

        private fun updateProgress(task: VideoDownloadManager.DownloadTask) {
            progressHolder[0] = Math.max(0, task.percent())
            progressBar?.invalidate()
            pctText?.text = task.statusLine() + " · " + Math.max(0, task.percent()) + "%"
        }

        private fun maybeProbe() {
            if (probing) {
                return
            }
            probing = true
            VideoDownloadManager.get().probeInfo(
                url, headers,
                object : VideoDownloadManager.ProbeCallback {
                    override fun onInfo(totalBytes: Long, segments: Int) {
                        if (dismissed) {
                            return
                        }
                        probeTotal = totalBytes
                        probeSegments = segments
                        val task = VideoDownloadManager.get().findTask(url)
                        if (task == null) {
                            rebuild(task)
                        }
                    }
                }
            )
        }

        private fun buildHeader(
            c: Context, box: LinearLayout,
            task: VideoDownloadManager.DownloadTask?
        ) {
            val titleView = TextView(c)
            titleView.text = if (title != null) title
            else (if (task != null) task.displayTitle() else "视频")
            titleView.setTextSize(16f)
            titleView.setTypeface(null, android.graphics.Typeface.BOLD)
            titleView.setTextColor(ThemeUtils.textPrimaryColor(c))
            titleView.maxLines = 2
            box.addView(
                titleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            box.addView(space(c, 4))
            box.addView(secondaryText(c, "小黑盒 · MP4 / HLS"))
            box.addView(space(c, 14))
        }

        private fun buildProgressBlock(
            c: Context, box: LinearLayout,
            task: VideoDownloadManager.DownloadTask
        ) {
            val pct = Math.max(0, task.percent())
            val pctRow = LinearLayout(c)
            pctRow.orientation = LinearLayout.HORIZONTAL
            val statusText = secondaryText(c, task.statusLine())
            statusText.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            pctRow.addView(statusText)
            val pctView = TextView(c)
            pctText = pctView
            pctView.text = (if (task.state == VideoDownloadManager.State.PAUSED) "已暂停 " else "") +
                    (if (pct >= 0) "$pct%" else "…")
            pctView.setTextSize(13f)
            pctView.setTypeface(null, android.graphics.Typeface.BOLD)
            pctView.setTextColor(accent)
            pctRow.addView(pctView)
            box.addView(pctRow)
            box.addView(space(c, 8))

            progressHolder[0] = pct
            val bar = object : View(c) {
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)
                    val radius = height / 2f
                    p.color = ThemeUtils.surfaceVariantColor(c)
                    canvas.drawRoundRect(
                        RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, p
                    )
                    p.color = accent
                    val pw = (width * (progressHolder[0] / 100f)).toInt()
                    if (pw > 0) {
                        canvas.drawRoundRect(
                            RectF(0f, 0f, Math.max(pw, height).toFloat(), height.toFloat()),
                            radius, radius, p
                        )
                    }
                }
            }
            progressBar = bar
            box.addView(
                bar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ThemeUtils.dp(c, 6f)
                )
            )
            box.addView(space(c, 14))
        }

        private fun buildDoneState(
            c: Context, box: LinearLayout,
            task: VideoDownloadManager.DownloadTask
        ) {
            val done = TextView(c)
            done.text = "✓ 下载完成"
            done.setTextSize(16f)
            done.setTypeface(null, android.graphics.Typeface.BOLD)
            done.setTextColor(accent)
            box.addView(
                done,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            box.addView(space(c, 4))
            val pathText = secondaryText(
                c,
                (task.savedPath ?: "")
                        + (if (task.total > 0)
                            " · " + VideoDownloadManager.formatSize(task.total) else "")
            )
            pathText.maxLines = 2
            box.addView(
                pathText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            box.addView(space(c, 12))

            val t = task
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            val play = sheetButton(
                c, ButtonStyle.PRIMARY, "播放",
                Runnable { t.open(context) }, true
            )
            val playLp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            playLp.rightMargin = ThemeUtils.dp(c, 6f)
            row.addView(play, playLp)
            val share = sheetButton(
                c, ButtonStyle.SECONDARY, "分享",
                Runnable { t.share(context) }, true
            )
            val shareLp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            shareLp.leftMargin = ThemeUtils.dp(c, 6f)
            row.addView(share, shareLp)
            box.addView(row)
            box.addView(space(c, 6))
            addButtons(c, box, sheetButton(c, ButtonStyle.TEXT, "完成", null, true))
        }

        private fun buildFailedState(
            c: Context, box: LinearLayout,
            task: VideoDownloadManager.DownloadTask
        ) {
            buildHeader(c, box, task)
            val err = TextView(c)
            err.text = "下载失败" + (if (task.errorMsg != null) " · " + task.errorMsg else "")
            err.setTextSize(14f)
            err.setTextColor(0xFFE53935.toInt())
            box.addView(err)
            box.addView(space(c, 12))
            addButtons(
                c, box,
                sheetButton(
                    c, ButtonStyle.PRIMARY, "重新下载",
                    Runnable { VideoDownloadManager.get().resume(url) }, false
                ),
                sheetButton(c, ButtonStyle.TEXT, "取消", null, true)
            )
        }

        private enum class ButtonStyle { PRIMARY, SECONDARY, TEXT }

        private fun space(c: Context, dp: Int): View {
            val v = View(c)
            v.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ThemeUtils.dp(c, dp.toFloat())
            )
            return v
        }

        private fun secondaryText(c: Context, text: String): TextView {
            val tv = TextView(c)
            tv.text = text
            tv.setTextSize(13f)
            tv.setTextColor(ThemeUtils.textSecondaryColor(c))
            return tv
        }

        private fun applyStyle(button: TextView, style: ButtonStyle) {
            val bgColor: Int
            val rippleOverlay: Int
            val textColor: Int
            when (style) {
                ButtonStyle.PRIMARY -> {
                    bgColor = accent
                    rippleOverlay = ThemeUtils.withAlpha(
                        ThemeUtils.readableForegroundOn(accent), 0x33
                    )
                    textColor = ThemeUtils.readableForegroundOn(accent)
                }
                ButtonStyle.SECONDARY -> {
                    bgColor = ThemeUtils.surfaceVariantColor(context)
                    rippleOverlay = ThemeUtils.withAlpha(
                        ThemeUtils.textPrimaryColor(context), 0x1F
                    )
                    textColor = ThemeUtils.textPrimaryColor(context)
                }
                else -> {
                    bgColor = Color.TRANSPARENT
                    rippleOverlay = ThemeUtils.withAlpha(
                        ThemeUtils.textPrimaryColor(context), 0x1F
                    )
                    textColor = ThemeUtils.textSecondaryColor(context)
                }
            }
            val bg = GradientDrawable()
            bg.setColor(bgColor)
            bg.setCornerRadius(
                ThemeUtils.dp(button.context, ThemeUtils.RADIUS_BUTTON_DP.toFloat()).toFloat()
            )
            button.background = RippleDrawable(ColorStateList.valueOf(rippleOverlay), bg, null)
            button.setTextColor(textColor)
        }

        private fun sheetButton(
            c: Context, style: ButtonStyle, text: String,
            action: Runnable?, dismissAfter: Boolean
        ): TextView {
            val button = TextView(c)
            button.text = text
            button.setTextSize(15f)
            button.gravity = Gravity.CENTER
            button.minHeight = ThemeUtils.dp(c, 48f)
            button.isClickable = true
            button.isFocusable = true
            applyStyle(button, style)
            button.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    try {
                        if (action != null) {
                            action.run()
                        }
                    } catch (ignored: Throwable) {
                    }
                    if (dismissAfter) {
                        dismiss()
                    }
                }
            })
            return button
        }

        private fun addButtons(c: Context, box: LinearLayout, vararg buttons: View) {
            for (b in buttons) {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = ThemeUtils.dp(c, 8f)
                box.addView(b, lp)
            }
        }

        private fun addActiveButtons(
            c: Context, box: LinearLayout, primary: Boolean,
            actionLabel: String, primaryAction: Runnable?
        ) {
            addButtons(
                c, box,
                sheetButton(
                    c, if (primary) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                    actionLabel, primaryAction, false
                ),
                sheetButton(
                    c, ButtonStyle.TEXT, "取消下载",
                    Runnable { VideoDownloadManager.get().cancel(url) }, true
                )
            )
        }

        private fun removeExisting() {
            val currentScrim = scrim
            if (currentScrim != null && currentScrim.parent != null) {
                (currentScrim.parent as ViewGroup).removeView(currentScrim)
            }
            val currentPanel = panel
            if (currentPanel != null && currentPanel.parent != null) {
                (currentPanel.parent as ViewGroup).removeView(currentPanel)
            }
            scrim = null
            panel = null
            content = null
        }

        fun dismiss() {
            if (dismissed) {
                return
            }
            dismissed = true
            VideoDownloadManager.get().removeListener(listener)
            val p = panel
            if (p != null) {
                val s = scrim
                p.animate().translationY((p.height + ThemeUtils.dp(context, 40f)).toFloat())
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator(1.4f)).start()
                if (s != null) {
                    s.animate().alpha(0f).setDuration(120L).start()
                }
                p.postDelayed({ removeExisting() }, 200L)
            } else {
                removeExisting()
            }
        }

        companion object {

            private fun shownStateOf(
                task: VideoDownloadManager.DownloadTask?
            ): VideoDownloadManager.State? {
                if (task == null) {
                    return null
                }
                return if (task.state == VideoDownloadManager.State.PENDING)
                    VideoDownloadManager.State.DOWNLOADING else task.state
            }
        }
    }

    companion object {

        @Volatile
        private var glassSettingsVisible = false

        @JvmStatic
        fun setGlassSettingsVisible(visible: Boolean) {
            glassSettingsVisible = visible
            EntryController.syncAll()
        }

        private val VIDEO_PAGE_HOSTS: Set<String> =
            java.util.HashSet(
                java.util.Arrays.asList(
                    "com.max.xiaoheihe.module.video.VideoActivity",
                    "com.max.xiaoheihe.module.story.StoryActivity"
                )
            )

        private fun isVideoPageHost(videoView: View): Boolean {
            val activity: Activity? = ViewUtils.findActivity(videoView)
            return activity != null && VIDEO_PAGE_HOSTS.contains(activity.javaClass.name)
        }

        private fun shorten(url: String): String {
            if (url.length <= 120) {
                return url
            }
            return url.substring(0, 117) + "..."
        }

        private fun cardTitle(video: View): String? {
            for (idName in arrayOf("tv_title", "tvTitle")) {
                val title = titleFromAncestors(video, idName, false)
                if (title != null) {
                    return title
                }
            }
            val byTag = titleFromAncestors(video, "tvTitle", true)
            if (byTag != null) {
                return byTag
            }
            return BottomSheetFallback.videoTitle(video.context)
        }

        private fun titleFromAncestors(video: View, idName: String, byTag: Boolean): String? {
            try {
                val context = video.context
                val titleId = if (byTag) 0
                else context.resources.getIdentifier(idName, "id", context.packageName)
                if (!byTag && titleId == 0) {
                    return null
                }
                var p: ViewParent? = video.parent
                while (p is ViewGroup) {
                    val container: ViewGroup = p
                    val title: View? = if (byTag)
                        container.findViewWithTag<View>(idName)
                    else container.findViewById<View>(titleId)
                    if (title is TextView) {
                        val text = title.text
                        if (text != null && text.toString().trim().length > 0) {
                            return text.toString().trim()
                        }
                    }
                    p = container.parent
                }
            } catch (ignored: Throwable) {
            }
            return null
        }
    }
}
