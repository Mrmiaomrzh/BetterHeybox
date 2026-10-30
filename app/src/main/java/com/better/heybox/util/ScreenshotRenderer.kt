package com.better.heybox.util

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ScreenshotRenderer {

    private const val GAP_DP = 10
    private const val PADDING_DP = 10
    private const val MAX_OUTPUT_HEIGHT = 20000
    private const val EXPAND_NODES = 600
    private const val SCROLL_SETTLE_MS = 300
    private const val SETTLE_EXTRA_MS = 140
    private const val CHUNK_OVERLAP = 16
    private const val MAX_STITCH_CHUNKS = 12
    private const val WARMUP_DELAY_MS = 90
    private const val WARMUP_SETTLE_MS = 420
    private const val CHUNK_RETRIES = 2
    private const val STITCH_ATTEMPTS = 3

    fun interface BitmapCallback {
        fun onCaptured(bitmap: Bitmap?)
    }

    class FoldedText {
        val relaxed = ArrayList<TextView>()
        val maxLines = ArrayList<Int>()
        val ellipsize = ArrayList<TextUtils.TruncateAt?>()
        var clicked = false

        fun restore() {
            for (i in relaxed.indices) {
                try {
                    relaxed[i].maxLines = maxLines[i]
                    relaxed[i].ellipsize = ellipsize[i]
                } catch (ignored: Throwable) {
                }
            }
            relaxed.clear()
            maxLines.clear()
            ellipsize.clear()
        }
    }

    @JvmStatic
    fun expandFoldedTexts(units: List<View>?): FoldedText {
        val state = FoldedText()
        if (units == null) {
            return state
        }
        for (unit in units) {
            try {
                relaxFoldedText(unit, state)
                if (clickExpandControl(unit)) {
                    state.clicked = true
                }
            } catch (ignored: Throwable) {
            }
        }
        return state
    }

    private fun relaxFoldedText(root: View, state: FoldedText) {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (!queue.isEmpty() && visited++ < EXPAND_NODES) {
            val current = queue.poll()
            if (current is TextView) {
                val text = current
                val lines = text.maxLines
                if (lines > 0 && lines != Int.MAX_VALUE) {
                    state.relaxed.add(text)
                    state.maxLines.add(lines)
                    state.ellipsize.add(text.ellipsize)
                    text.maxLines = Int.MAX_VALUE
                    text.ellipsize = null
                }
            }
            if (current is ViewGroup) {
                val group = current
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
    }

    private fun clickExpandControl(root: View): Boolean {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (!queue.isEmpty() && visited++ < EXPAND_NODES) {
            val current = queue.poll()
            if (current is TextView && current.isShown && current.isClickable) {
                val raw: CharSequence? = current.text
                val text = raw?.toString()?.trim() ?: ""
                if (text.isNotEmpty() && text.length <= 12
                    && (text.contains("展开全文") || text.contains("展开剩余")
                        || text == "全文")
                ) {
                    current.performClick()
                    return true
                }
            }
            if (current is ViewGroup) {
                val group = current
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
        return false
    }

    @JvmStatic
    fun backgroundColor(context: Context): Int {
        return try {
            val value = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ) {
                    return value.data
                }
                if (value.resourceId != 0) {
                    return context.resources.getColor(value.resourceId)
                }
            }
            Color.WHITE
        } catch (ignored: Throwable) {
            Color.WHITE
        }
    }

    @JvmStatic
    fun layoutForCapture(unit: View?) {
        if (unit == null) {
            return
        }
        var width = unit.width
        if (width <= 0) {
            width = unit.measuredWidth
        }
        if (width <= 0) {
            return
        }
        try {
            val originalHeight = unit.height
            unit.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val measured = unit.measuredHeight
            val height = fallbackHeight(measured, originalHeight)
            if (height > 0) {
                unit.layout(0, 0, width, height)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun fallbackHeight(measured: Int, originalHeight: Int): Int {
        if (originalHeight <= 0) {
            return measured
        }
        if (measured <= 0 || measured < originalHeight * 0.6f) {
            return originalHeight
        }
        return measured
    }

    @JvmStatic
    fun captureUnit(unit: View?, bgColor: Int): Bitmap? {
        if (unit == null) {
            return null
        }
        val width = unit.width
        if (width <= 0) {
            return null
        }
        val fixedHeight = ScreenshotSelectionManager.isWebView(unit)

        val savedLeft = unit.left
        val savedTop = unit.top
        val savedRight = unit.right
        val savedBottom = unit.bottom
        val originalHeight = unit.height
        var height = originalHeight

        try {
            unit.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val measured = unit.measuredHeight
            if (!fixedHeight) {
                height = fallbackHeight(measured, originalHeight)
            }
            if (height <= 0) {
                return null
            }
            unit.layout(0, 0, width, height)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(bgColor)
            unit.draw(canvas)
            if (ScreenshotSelectionManager.DEBUG) {
                Log.i(
                    "BetterHeybox", "截图诊断 单元绘制 " + unit.javaClass.simpleName
                            + " 原 h=" + originalHeight + " measured=" + measured
                            + " -> 位图 " + width + "x" + height
                )
            }
            return bitmap
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (ignored: Throwable) {
            return null
        } finally {
            try {
                unit.layout(savedLeft, savedTop, savedRight, savedBottom)
                unit.requestLayout()
            } catch (ignored: Throwable) {
            }
        }
    }

    @JvmStatic
    fun captureWebViewAsync(
        activity: Activity?,
        web: View?,
        bgColor: Int,
        callback: BitmapCallback?
    ) {
        if (web == null || callback == null) {
            callback?.onCaptured(null)
            return
        }
        val width = web.width
        val viewHeight = web.height
        if (width <= 0 || viewHeight <= 0) {
            callback.onCaptured(null)
            return
        }

        var total = min(MAX_OUTPUT_HEIGHT, viewHeight * MAX_STITCH_CHUNKS)
        val content = contentHeight(web)
        if (content > total) {
            total = content
        }
        try {
            val budget = Runtime.getRuntime().maxMemory() / 2
            val byMemory = max(
                viewHeight.toLong(),
                min(Int.MAX_VALUE.toLong(), budget / (4L * width))
            ).toInt()
            total = min(total, byMemory)
        } catch (ignored: Throwable) {
        }
        for (attempt in 0 until 3) {
            try {
                Log.i(
                    "BetterHeybox", "截图诊断 网页拼接 宽=$width 视口高=$viewHeight"
                            + " 总高=$total 内容高=$content attempt=$attempt"
                )
                WebStitcher(activity, web, width, viewHeight, total, bgColor, callback, 0).start()
                return
            } catch (error: OutOfMemoryError) {
                total /= 2
                if (total < viewHeight) {
                    break
                }
            }
        }
        callback.onCaptured(null)
    }

    private fun contentHeight(web: View): Int {
        return try {
            val raw = web.javaClass.getMethod("getContentHeight").invoke(web)
            var scale = 1f
            try {
                val value = web.javaClass.getMethod("getScale").invoke(web)
                if (value is Number) {
                    scale = value.toFloat()
                }
            } catch (ignored: Throwable) {
            }
            val content = ((raw as Number).toInt() * (if (scale <= 0f) 1f else scale)).toInt()
            max(content, 0)
        } catch (ignored: Throwable) {
            0
        }
    }

    private fun drawChunk(web: View, width: Int, crop: Int, height: Int, bgColor: Int): Bitmap? {
        if (width <= 0 || height <= 0) {
            return null
        }
        return try {
            val chunk = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(chunk)
            canvas.drawColor(bgColor)
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            if (crop > 0) {
                canvas.translate(0f, -crop.toFloat())
            }
            web.draw(canvas)
            canvas.restore()
            chunk
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun isUniform(chunk: Bitmap): Boolean {
        val width = chunk.width
        val height = chunk.height
        val step = 8
        val reference = chunk.getPixel(0, 0)
        var sampled = 0
        var different = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                sampled++
                if (chunk.getPixel(x, y) != reference) {
                    different++
                }
                x += step
            }
            y += step
        }
        return sampled == 0 || different * 100 < sampled
    }

    private fun signature(chunk: Bitmap): Long {
        val width = chunk.width
        val height = chunk.height
        var hash = 1125899906842597L
        for (gy in 0 until 16) {
            val y = min(height - 1, gy * max(1, height / 16))
            for (gx in 0 until 16) {
                val x = min(width - 1, gx * max(1, width / 16))
                hash = hash * 31 + chunk.getPixel(x, y)
            }
        }
        return hash
    }

    private class WebStitcher(
        private val activity: Activity?,
        private val web: View,
        private val width: Int,
        private val viewHeight: Int,
        total: Int,
        private val bgColor: Int,
        private val callback: BitmapCallback,
        private val attempt: Int
    ) {

        private var captureHeight = viewHeight
        private var total = total
        private val savedScrollY = web.scrollY
        private val handler = Handler(Looper.getMainLooper())
        private val scrolledAncestors = ArrayList<View>()
        private val ancestorScrolls = ArrayList<Int>()

        private var output: Bitmap? = null
        private var canvas: Canvas? = null
        private var drawn = 0
        private var captureStartContent = 0
        private var captureRows = 0
        private var lastChunkSignature = 0L
        private var blankRetries = 0
        private var jamRetries = 0
        private var stableTries = 0
        private var lastStableContent = -1
        private var pendingCrop = 0
        private var pendingHeight = 0
        private var retries = 0
        private var restartNeeded = false
        private var finishing = false

        fun start() {
            if (ScreenshotSelectionManager.get().isDebug()) {
                Log.i(
                    "BetterHeybox", "截图诊断 网页拼接开始 宽=$width 视口高=$viewHeight"
                            + " 预算高=$total 内容高=" + contentHeight(web) + " attempt=$attempt"
                            + " 已滚动=$savedScrollY"
                )
            }
            bringIntoView()
            updateCaptureHeight()
            warmup()
        }

        private fun updateCaptureHeight() {
            try {
                val visible = Rect()
                if (web.getGlobalVisibleRect(visible) && visible.height() > 0) {
                    captureHeight = min(viewHeight, visible.height())
                }
            } catch (ignored: Throwable) {
            }
            if (ScreenshotSelectionManager.get().isDebug()) {
                Log.i(
                    "BetterHeybox", "截图诊断 网页可见高=$captureHeight"
                            + " 视图高=$viewHeight"
                )
            }
        }

        private fun warmup() {
            val content = max(contentHeight(web), total)
            val step = max(viewHeight * 3 / 4, 240)
            val limit = min(content, total) + viewHeight
            val position = intArrayOf(0)
            val runnable = object : Runnable {
                override fun run() {
                    if (position[0] > limit) {
                        warmupDone()
                        return
                    }
                    try {
                        web.scrollTo(0, position[0])
                    } catch (ignored: Throwable) {
                    }
                    position[0] += step
                    handler.postDelayed(this, WARMUP_DELAY_MS.toLong())
                }
            }
            handler.post(runnable)
        }

        private fun warmupDone() {
            try {
                web.scrollTo(0, 0)
            } catch (ignored: Throwable) {
            }
            handler.postDelayed({ allocate() }, WARMUP_SETTLE_MS.toLong())
        }

        private fun allocate() {
            val measured = contentHeight(web)
            if (stableTries < 8 && (stableTries < 2 || measured != lastStableContent)) {
                lastStableContent = measured
                stableTries++
                if (ScreenshotSelectionManager.get().isDebug()) {
                    Log.i(
                        "BetterHeybox", "截图诊断 等内容高度稳定 第 $stableTries"
                                + " 次 内容高=$measured"
                    )
                }
                handler.postDelayed({ allocate() }, 380)
                return
            }
            val content = contentHeight(web)
            if (content > total) {
                total = content
            }
            try {
                val budget = Runtime.getRuntime().maxMemory() / 2
                val byMemory = max(
                    viewHeight.toLong(),
                    min(Int.MAX_VALUE.toLong(), budget / (4L * width))
                ).toInt()
                total = min(total, max(viewHeight, byMemory))
            } catch (ignored: Throwable) {
            }
            try {
                output = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
            } catch (error: OutOfMemoryError) {
                val reduced = max(viewHeight * 2, total / 2)
                try {
                    output = Bitmap.createBitmap(width, reduced, Bitmap.Config.ARGB_8888)
                    total = reduced
                } catch (again: OutOfMemoryError) {
                    callback.onCaptured(null)
                    return
                }
            }
            canvas = Canvas(output!!)
            canvas!!.drawColor(bgColor)
            step()
        }

        private fun bringIntoView() {
            try {
                val location = IntArray(2)
                val windowLocation = IntArray(2)
                val decor = activity!!.window.decorView
                web.getLocationOnScreen(location)
                decor.getLocationOnScreen(windowLocation)
                var top = location[1] - windowLocation[1]
                val windowHeight = decor.height
                if (top >= 0 && top + viewHeight <= windowHeight) {
                    return
                }
                var parent: ViewParent? = web.parent
                var depth = 0
                while (depth < 20 && parent is View) {
                    val p = parent as View
                    if (p.scrollY != 0) {
                        scrolledAncestors.add(p)
                        ancestorScrolls.add(p.scrollY)
                        p.scrollTo(0, 0)
                    }
                    parent = p.parent
                    depth++
                }
                web.getLocationOnScreen(location)
                top = location[1] - windowLocation[1]
                if (top < 0 || top + viewHeight > windowHeight) {
                    web.requestRectangleOnScreen(Rect(0, 0, width, viewHeight), false)
                }
                if (ScreenshotSelectionManager.get().isDebug()) {
                    web.getLocationOnScreen(location)
                    top = location[1] - windowLocation[1]
                    Log.i(
                        "BetterHeybox", "截图诊断 网页入屏 top=$top"
                                + " 视口高=$viewHeight 窗口高=$windowHeight"
                    )
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun step() {
            if (finishing) {
                return
            }
            val content = contentHeight(web)
            if (content > 0 && drawn >= content - 2) {
                finish()
                return
            }
            if (drawn >= total) {
                finish()
                return
            }
            val maxScroll = if (content > 0) {
                max(0, content - max(1, captureHeight))
            } else {
                Int.MAX_VALUE
            }
            val requested = min(max(0, drawn - CHUNK_OVERLAP), maxScroll)
            try {
                web.scrollTo(0, requested)
            } catch (ignored: Throwable) {
                captureFallback(requested)
                return
            }
            handler.postDelayed({ settled(requested) }, SCROLL_SETTLE_MS.toLong())
        }

        private fun settled(requested: Int) {
            var actual = requested
            try {
                actual = web.scrollY
            } catch (ignored: Throwable) {
                actual = requested
            }
            if (actual < 0) {
                actual = requested
            }
            val contentNow = contentHeight(web)
            val scrollJammed = drawn > 0 && actual < requested - 2
            if (scrollJammed && jamRetries < 10 && contentNow > max(drawn, actual) + 4) {
                jamRetries++
                if (ScreenshotSelectionManager.get().isDebug()) {
                    Log.i(
                        "BetterHeybox", "截图诊断 滚动到不了目标，等页面重新布局 第 "
                                + jamRetries + " 次 requested=$requested actual=$actual"
                                + " 内容高=$contentNow 已拼=$drawn"
                    )
                }
                handler.postDelayed({ step() }, 420)
                return
            }
            if (!scrollJammed) {
                jamRetries = 0
            }
            val bottomReached = scrollJammed
            if (ScreenshotSelectionManager.get().isDebug()) {
                Log.i(
                    "BetterHeybox", "截图诊断 网页滚动 requested=$requested"
                            + " actual=$actual 已拼=$drawn 到底=$bottomReached"
                            + " 内容高=" + contentHeight(web)
                )
            }
            val start = if (bottomReached) max(drawn, actual)
            else max(drawn - CHUNK_OVERLAP, actual)
            if (start >= total) {
                finish()
                return
            }
            val availableEnd = actual + max(1, captureHeight)
            if (start >= availableEnd) {
                finish()
                return
            }
            val rect = captureRect(actual, start)
            if (rect == null) {
                captureFallback(actual)
                return
            }
            pixelCopy(rect, false)
        }

        private fun captureRect(actual: Int, startContent: Int): Rect? {
            return try {
                val location = IntArray(2)
                val windowLocation = IntArray(2)
                val decor = activity!!.window.decorView
                web.getLocationOnScreen(location)
                decor.getLocationOnScreen(windowLocation)
                val windowWidth = decor.width
                val windowHeight = decor.height
                val rawTop = location[1] - windowLocation[1]
                val left = max(0, location[0] - windowLocation[0])
                val right = min(left + width, windowWidth)
                val rowOffset = max(0, startContent - actual)
                val clippedAbove = max(0, -rawTop)
                val top = max(0, rawTop) + rowOffset
                val height = min(captureHeight - rowOffset - clippedAbove, windowHeight - top)
                if (right - left <= 0 || height < max(1, captureHeight / 4)) {
                    return null
                }
                captureStartContent = actual + rowOffset + clippedAbove
                captureRows = height
                Rect(left, top, right, top + height)
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun pixelCopy(rect: Rect, second: Boolean) {
            val chunk: Bitmap
            try {
                chunk = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            } catch (error: OutOfMemoryError) {
                throw error
            } catch (ignored: Throwable) {
                if (second) {
                    captureFallback(captureStartContent)
                } else {
                    retryPixelCopy(rect)
                }
                return
            }
            try {
                PixelCopy.request(
                    activity!!.window, rect, chunk,
                    { copyResult ->
                        if (copyResult != PixelCopy.SUCCESS) {
                            chunk.recycle()
                            if (second) {
                                captureFallback(captureStartContent)
                            } else {
                                retryPixelCopy(rect)
                            }
                            return@request
                        }
                        if (second) {
                            accept(captureStartContent, chunk)
                            return@request
                        }
                        chunk.recycle()
                        retryPixelCopy(rect)
                    },
                    handler
                )
            } catch (error: OutOfMemoryError) {
                throw error
            } catch (ignored: Throwable) {
                chunk.recycle()
                if (second) {
                    captureFallback(captureStartContent)
                } else {
                    retryPixelCopy(rect)
                }
            }
        }

        private fun retryPixelCopy(rect: Rect) {
            handler.postDelayed({ pixelCopy(rect, true) }, SETTLE_EXTRA_MS.toLong())
        }

        private fun captureFallback(actual: Int) {
            val start = max(drawn, actual)
            val crop = max(0, start - actual)
            val height = min(viewHeight - crop, total - start)
            if (height <= 0) {
                finish()
                return
            }
            pendingCrop = crop
            pendingHeight = height
            val chunk = drawChunk(web, width, crop, height, bgColor)
            if (chunk == null) {
                finish()
                return
            }
            accept(start, chunk)
        }

        private fun accept(contentStart: Int, chunk: Bitmap?) {
            if (finishing) {
                chunk?.recycle()
                return
            }
            if (chunk == null || chunk.height <= 0) {
                chunk?.recycle()
                finish()
                return
            }
            val chunkHeight = chunk.height
            val contentTop = max(0, contentStart)
            val uniform = drawn > 0 && isUniform(chunk)
            val sig = if (uniform) 0L else signature(chunk)
            if (!uniform && drawn > 0 && lastChunkSignature != 0L && sig == lastChunkSignature) {
                if (ScreenshotSelectionManager.get().isDebug()) {
                    Log.i("BetterHeybox", "截图诊断 分块与上一块相同，判定已到内容底部")
                }
                chunk.recycle()
                finish()
                return
            }
            var overlap = min(CHUNK_OVERLAP, max(0, drawn - contentTop))
            overlap = min(overlap, chunkHeight)
            var overlapOk = true
            if (!uniform && overlap > 0) {
                overlapOk = overlapMatches(contentTop, chunk, overlap)
            }
            if (ScreenshotSelectionManager.get().isDebug()) {
                Log.i(
                    "BetterHeybox", "截图诊断 WebView 分块 y=$contentStart"
                            + " 重叠校验=$overlap 校验通过=$overlapOk"
                            + " 高=$chunkHeight 空白=$uniform 已拼=$drawn"
                )
            }
            if (uniform) {
                chunk.recycle()
                finish()
                return
            }
            if (!overlapOk) {
                chunk.recycle()
                retries++
                if (retries <= CHUNK_RETRIES) {
                    if (ScreenshotSelectionManager.get().isDebug()) {
                        Log.i("BetterHeybox", "截图诊断 重叠不一致，重截当前位置 第 $retries 次")
                    }
                    handler.postDelayed({
                        val actual: Int = try {
                            web.scrollY
                        } catch (ignored: Throwable) {
                            max(0, drawn - CHUNK_OVERLAP)
                        }
                        settled(actual)
                    }, SETTLE_EXTRA_MS * 2L)
                    return
                }
                Log.w("BetterHeybox", "截图诊断 重叠多次不一致，强制接受当前分块")
                restartNeeded = true
            }
            retries = 0
            lastChunkSignature = sig
            val lastRow = lastContentRow(chunk)
            if (lastRow < 0) {
                chunk.recycle()
                blankRetries++
                if (blankRetries <= 3) {
                    if (ScreenshotSelectionManager.get().isDebug()) {
                        Log.i(
                            "BetterHeybox", "截图诊断 分块空白，错位重绘后重取 第 "
                                    + blankRetries + " 次 y=$contentTop"
                        )
                    }
                    val target = max(0, contentTop)
                    handler.postDelayed({
                        try {
                            web.scrollTo(0, max(0, target - 40))
                        } catch (ignored: Throwable) {
                        }
                        handler.postDelayed({
                            try {
                                web.scrollTo(0, target)
                            } catch (ignored: Throwable) {
                            }
                            handler.postDelayed({ step() }, 520)
                        }, 260)
                    }, 320)
                    return
                }
                if (contentHeight(web) > drawn + 6) {
                    Log.w("BetterHeybox", "截图诊断 分块持续空白，改用软件绘制兜底 y=$contentTop")
                    captureFallback(contentTop)
                    return
                }
                finish()
                return
            }
            blankRetries = 0
            val usedHeight = min(chunkHeight, lastRow + 1)
            val at = max(0, contentStart)
            if (ScreenshotSelectionManager.get().isDebug()) {
                Log.i("BetterHeybox", "截图诊断 长图落点 y=$at 高度=$chunkHeight")
            }
            val previous = drawn
            try {
                canvas!!.drawBitmap(
                    chunk,
                    Rect(0, 0, width, usedHeight),
                    Rect(0, at, width, at + usedHeight),
                    null
                )
            } catch (ignored: Throwable) {
            }
            val bottom = at + usedHeight
            if (bottom > drawn) {
                drawn = min(total, bottom)
            }
            chunk.recycle()
            if (drawn <= previous) {
                finish()
                return
            }
            if (drawn >= total) {
                finish()
                return
            }
            step()
        }

        private fun lastContentRow(chunk: Bitmap): Int {
            return try {
                val chunkWidth = chunk.width
                val xLimit = max(1, chunkWidth - 96)
                val stepX = max(1, xLimit / 64)
                val backgroundRed = (bgColor shr 16) and 0xFF
                val backgroundGreen = (bgColor shr 8) and 0xFF
                val backgroundBlue = bgColor and 0xFF
                for (y in chunk.height - 1 downTo 0) {
                    var x = 0
                    while (x < xLimit) {
                        val pixel = chunk.getPixel(x, y)
                        val distance = abs(((pixel shr 16) and 0xFF) - backgroundRed) +
                                abs(((pixel shr 8) and 0xFF) - backgroundGreen) +
                                abs((pixel and 0xFF) - backgroundBlue)
                        if (distance > 30) {
                            return y
                        }
                        x += stepX
                    }
                }
                -1
            } catch (ignored: Throwable) {
                -1
            }
        }

        private fun overlapMatches(contentTop: Int, chunk: Bitmap, overlap: Int): Boolean {
            val blockWidth = 32
            val blockHeight = 4
            val tolerance = 36
            return try {
                var blocks = 0
                var different = 0
                val width = chunk.width
                var y = 0
                while (y + blockHeight <= overlap) {
                    val outY = contentTop + y
                    if (outY + blockHeight > output!!.height) {
                        break
                    }
                    var x = 0
                    while (x + blockWidth <= width) {
                        blocks++
                        if (blockDistance(
                                chunk, x, y, output!!, x, outY,
                                blockWidth, blockHeight
                            ) > tolerance
                        ) {
                            different++
                        }
                        x += blockWidth
                    }
                    y += blockHeight
                }
                if (ScreenshotSelectionManager.get().isDebug()) {
                    Log.i("BetterHeybox", "截图诊断 重叠块比较 总数=$blocks 差异=$different")
                }
                blocks == 0 || different * 100 <= blocks * 12
            } catch (ignored: Throwable) {
                true
            }
        }

        private fun blockDistance(
            first: Bitmap, fx: Int, fy: Int, second: Bitmap, sx: Int, sy: Int,
            blockWidth: Int, blockHeight: Int
        ): Int {
            var firstSum = 0
            var secondSum = 0
            var count = 0
            var y = 0
            while (y < blockHeight) {
                var x = 0
                while (x < blockWidth) {
                    val a = first.getPixel(fx + x, fy + y)
                    val b = second.getPixel(sx + x, sy + y)
                    firstSum += ((a shr 16) and 0xFF) + ((a shr 8) and 0xFF) + (a and 0xFF)
                    secondSum += ((b shr 16) and 0xFF) + ((b shr 8) and 0xFF) + (b and 0xFF)
                    count++
                    x += 2
                }
                y += 2
            }
            if (count == 0) {
                return 0
            }
            return abs(firstSum - secondSum) / count / 3
        }

        private fun finish() {
            if (finishing) {
                return
            }
            finishing = true
            Log.i(
                "BetterHeybox", "截图诊断 网页拼接结束 drawn=$drawn total=$total"
                        + " attempt=$attempt 重排重试=$restartNeeded 已滚动=$savedScrollY"
            )
            try {
                web.scrollTo(0, savedScrollY)
            } catch (ignored: Throwable) {
            }
            for (i in scrolledAncestors.indices) {
                try {
                    scrolledAncestors[i].scrollTo(0, ancestorScrolls[i])
                } catch (ignored: Throwable) {
                }
            }
            val out = output ?: run {
                callback.onCaptured(null)
                return
            }
            var result: Bitmap? = out
            try {
                if (drawn <= 0) {
                    result = null
                } else if (drawn < out.height) {
                    result = Bitmap.createBitmap(out, 0, 0, width, drawn)
                }
            } catch (error: OutOfMemoryError) {
                result = out
            } catch (ignored: Throwable) {
                result = out
            }
            if (result !== out) {
                out.recycle()
            }
            output = null
            canvas = null
            callback.onCaptured(result)
        }
    }

    @JvmStatic
    fun composeVertical(context: Context, parts: List<Bitmap>?, bgColor: Int): Bitmap? {
        if (parts == null || parts.isEmpty()) {
            return null
        }
        if (parts.size == 1) {
            val only = parts[0]
            return only?.copy(Bitmap.Config.ARGB_8888, false)
        }

        val density = context.resources.displayMetrics.density
        val gap = max(1, (GAP_DP * density).toInt())
        val padding = max(0, (PADDING_DP * density).toInt())

        var contentWidth = 0
        var contentHeight = gap * (parts.size - 1)
        for (part in parts) {
            if (part == null) {
                continue
            }
            contentWidth = max(contentWidth, part.width)
            contentHeight += part.height
        }
        if (contentWidth <= 0 || contentHeight <= 0) {
            return null
        }

        var outWidth = contentWidth + padding * 2
        var outHeight = contentHeight + padding * 2

        var scale = 1f
        if (outHeight > MAX_OUTPUT_HEIGHT) {
            scale = MAX_OUTPUT_HEIGHT / outHeight.toFloat()
            outWidth = max(1, (outWidth * scale).toInt())
            outHeight = MAX_OUTPUT_HEIGHT
        }

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(bgColor)
        canvas.save()
        canvas.scale(scale, scale)

        var y = padding
        for (part in parts) {
            if (part == null || part.isRecycled) {
                continue
            }
            val x = padding + (contentWidth - part.width) / 2f
            canvas.drawBitmap(part, x, y.toFloat(), null)
            y += part.height + gap
        }
        canvas.restore()
        return output
    }
}
