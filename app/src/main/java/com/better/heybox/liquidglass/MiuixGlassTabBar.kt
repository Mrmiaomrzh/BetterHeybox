package com.better.heybox.liquidglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class MiuixGlassTabBar(context: Context) :
    com.example.liquidglass.LiquidGlassTabBar(context, null, 0) {

    private val mDensity: Float = context.resources.displayMetrics.density

    private val mPill = GlassSelectionPill()

    private var mPillBroken = false

    private val mBounds = RectF()

    private val mShaderMatrix = Matrix()
    private val mGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var mUseAboveOnly = false
    private var mBackdrop: Bitmap? = null
    private var mRadius = 0f
    private var mLastCaptureAt = 0L
    private var mCapturing = false

    private var mDrawnViews = 0

    private var mCaptureFailures = 0

    private var mTriedRootFallback = false

    private var mHasBackdrop = false
    private var mOverLight = false
    private var mDark = false
    private var mLogs = 0

    private var mPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    init {
        try {
            GlassConfig.load(context)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("miuix tabbar config load failed", t)
        }
        mDark = initialDarkGuess()
        applyFallbackColor()
        mPill.setDensity(mDensity)
        reloadPillConfig()
    }

    private fun initialDarkGuess(): Boolean {
        return try {
            val darkAlpha = Color.alpha(GlassConfig.darkTint())
            val lightAlpha = Color.alpha(GlassConfig.lightTint())
            darkAlpha > lightAlpha + 24
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun applyFallbackColor() {
        var color: Int
        try {
            color = if (mDark) GlassConfig.darkTint() else GlassConfig.lightTint()
        } catch (t: Throwable) {
            color = 0
        }
        if (Color.alpha(color) == 0) {
            color = if (mDark) FALLBACK_DARK else FALLBACK_LIGHT
        }
        mFallbackPaint.color = color
        mFallbackPaint.alpha = min(Color.alpha(color), 0x99)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mPreDrawListener == null) {
            mPreDrawListener = ViewTreeObserver.OnPreDrawListener {
                if (mPill.pollSelection(this)) {
                    reloadPillConfig()
                    postInvalidateOnAnimation()
                }
                maybeCapture()
                true
            }
            viewTreeObserver.addOnPreDrawListener(mPreDrawListener)
        }
        mPill.reset()
        reloadPillConfig()

        resetCaptureState()
    }

    private fun resetCaptureState() {
        mHasBackdrop = false
        mLastCaptureAt = 0L
        mDrawnViews = 0
        mCaptureFailures = 0
        mTriedRootFallback = false
        if (mBackdrop != null && !mBackdrop!!.isRecycled) {
            mBackdrop!!.eraseColor(0)
        }
        applyFallbackColor()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        if (mPreDrawListener != null) {
            try {
                viewTreeObserver.removeOnPreDrawListener(mPreDrawListener)
            } catch (ignored: Throwable) {
            }
            mPreDrawListener = null
        }
        mPill.reset()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        mRadius = LiquidGlassInstaller.barCornerRadiusPx(h, mDensity)
        ensureBackdrop(w, h)
        mPill.reset()

        resetCaptureState()
    }

    private fun ensureBackdrop(w: Int, h: Int) {
        val bw = max(1, (w * BACKDROP_SCALE).roundToInt())
        val bh = max(1, (h * BACKDROP_SCALE).roundToInt())
        if (mBackdrop == null || mBackdrop!!.isRecycled
            || mBackdrop!!.width != bw || mBackdrop!!.height != bh
        ) {
            if (mBackdrop != null && !mBackdrop!!.isRecycled) {
                mBackdrop!!.recycle()
            }
            try {
                mBackdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                LiquidGlassLog.logErr("miuix tabbar backdrop alloc failed", t)
                mBackdrop = null
            }
        }
    }

    private fun maybeCapture() {
        if (mCapturing || !isAttachedToWindow || visibility != VISIBLE) {
            return
        }
        val now = SystemClock.uptimeMillis()

        var interval = CAPTURE_INTERVAL_MS
        if (!mHasBackdrop) {
            interval = if (mCaptureFailures >= MAX_FAST_RETRIES)
                SLOW_RETRY_INTERVAL_MS
            else
                RETRY_INTERVAL_MS
        }
        if (now - mLastCaptureAt < interval) {
            return
        }
        if (width <= 0 || height <= 0) {
            return
        }

        reloadPillConfig()
        mLastCaptureAt = now
        mCapturing = true
        try {
            ensureBackdrop(width, height)
            if (mBackdrop == null) {
                onCaptureFailed()
                return
            }
            val root = rootView ?: run {
                onCaptureFailed()
                return
            }
            val rootLoc = IntArray(2)
            val selfLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            getLocationOnScreen(selfLoc)
            val dx = (selfLoc[0] - rootLoc[0]).toFloat()
            val dy = (selfLoc[1] - rootLoc[1]).toFloat()

            val shift = if (height > 0) height.toFloat() else 0f
            mUseAboveOnly = dy >= shift

            val bmp = mBackdrop!!
            bmp.eraseColor(0)
            val c = Canvas(bmp)
            c.save()
            c.scale(BACKDROP_SCALE, BACKDROP_SCALE)
            c.translate(-dx, -(if (mUseAboveOnly) dy - shift else dy))
            val vis = visibility
            setVisibility(INVISIBLE)
            try {
                drawAboveSiblings(root, c)
            } finally {
                setVisibility(vis)
                c.restore()
            }

            if (mDrawnViews == 0) {
                if (!mTriedRootFallback) {
                    mTriedRootFallback = true
                    captureWholeRoot(root, c, shift)
                }
                if (mDrawnViews == 0) {
                    mBackdrop!!.eraseColor(0)
                    onCaptureFailed()
                    return
                }
            }

            val luma = averageLuma()

            val above = lumaAbove()
            mDark = above <= OVER_LIGHT_LUMA
            applyFallbackColor()
            MiuixGlassPipeline.process(mBackdrop, mDensity, mDark)
            mHasBackdrop = true
            mCaptureFailures = 0
            mTriedRootFallback = false
            updateAppearance(luma)
            invalidate()
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("miuix tabbar capture failed", t)
            onCaptureFailed()
        } finally {
            mCapturing = false
        }
    }

    private fun onCaptureFailed() {
        mCaptureFailures++
        mHasBackdrop = false
        mTriedRootFallback = false
        applyFallbackColor()
        invalidate()
        if (mCaptureFailures == 1 || DEBUG_APPEARANCE) {
            LiquidGlassLog.log(Log.INFO, "miuix tabbar capture retry, failures=$mCaptureFailures")
        }
    }

    private fun captureWholeRoot(root: View, canvas: Canvas, shift: Float) {
        if (!mUseAboveOnly) {
            return
        }
        val vis = visibility
        setVisibility(INVISIBLE)
        try {
            val selfTopCanvas = shift
            if (selfTopCanvas <= 0f) {
                return
            }
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), selfTopCanvas)
            root.draw(canvas)
            canvas.restore()
            mDrawnViews = 1
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("miuix tabbar root fallback failed", t)
        } finally {
            setVisibility(vis)
        }
    }

    private fun drawAboveSiblings(root: View, canvas: Canvas) {
        mDrawnViews = 0
        if (root !is ViewGroup) {
            return
        }
        var child: View? = this
        val chain = ArrayList<View>()
        while (child != null && child !== root) {
            chain.add(child)
            val p: ViewParent? = child.parent
            child = p as? View
        }
        if (chain.isEmpty() || child !== root) {
            return
        }
        chain.reverse()
        var group: ViewGroup = root
        val selfLoc = IntArray(2)
        getLocationOnScreen(selfLoc)
        val selfTop = selfLoc[1]

        val aboveTop = max(0, selfTop - height)
        for (level in chain.indices) {
            val current = chain[level]
            val next = if (level + 1 < chain.size) chain[level + 1] else null
            val count: Int
            try {
                count = group.childCount
            } catch (t: Throwable) {
                return
            }
            for (i in 0 until count) {
                var c: View? = null
                try {
                    c = group.getChildAt(i)
                } catch (ignored: Throwable) {
                    continue
                }
                if (c == null || c === next || c === current) {
                    continue
                }
                if (!visibleInTree(c, group)) {
                    continue
                }
                val loc = IntArray(2)
                c.getLocationOnScreen(loc)

                if (loc[1] + c.height <= selfTop && loc[1] + c.height > aboveTop) {
                    try {
                        canvas.save()
                        canvas.clipRect(0, aboveTop, width, selfTop)
                        c.draw(canvas)
                        canvas.restore()
                        mDrawnViews++
                    } catch (ignored: Throwable) {
                    }
                }
            }
            if (current !is ViewGroup) {
                return
            }
            group = current
        }
    }

    private fun visibleInTree(v: View, ancestor: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur.visibility != VISIBLE) {
                return false
            }
            if (cur === ancestor) {
                return true
            }
            val p: ViewParent? = cur.parent
            cur = p as? View
        }
        return false
    }

    private fun lumaAbove(): Int {
        if (mBackdrop == null || mBackdrop!!.isRecycled) {
            return 255
        }
        val w = mBackdrop!!.width
        val h = mBackdrop!!.height
        val limit = max(1, (h * 0.30f).roundToInt())
        val stepX = max(1, w / 24)
        val stepY = max(1, limit / 6)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < limit) {
            var x = 0
            while (x < w) {
                val px = mBackdrop!!.getPixel(x, y)
                sum += (((px shr 16) and 0xFF) * 30 + ((px shr 8) and 0xFF) * 59
                        + (px and 0xFF) * 11) / 100
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 255 else (sum / count).toInt()
    }

    private fun averageLuma(): Int {
        if (mBackdrop == null || mBackdrop!!.isRecycled) {
            return 255
        }
        val w = mBackdrop!!.width
        val h = mBackdrop!!.height
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 8)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val px = mBackdrop!!.getPixel(x, y)
                sum += (((px shr 16) and 0xFF) * 30 + ((px shr 8) and 0xFF) * 59
                        + (px and 0xFF) * 11) / 100
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 255 else (sum / count).toInt()
    }

    private fun updateAppearance(luma: Int) {
        val overLight = luma > OVER_LIGHT_LUMA
        mOverLight = overLight
        try {
            onAppearanceChanged(overLight)
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("miuix tabbar appearance failed", t)
        }

        if (DEBUG_APPEARANCE && mLogs++ < 20) {
            Log.i(
                "BetterHeybox", "miuix tabbar overLight=$overLight"
                        + " avg=$luma dark=$mDark aboveOnly=$mUseAboveOnly"
                        + " w=$width h=$height"
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (mHasBackdrop && mBackdrop != null && !mBackdrop!!.isRecycled) {
            val bmp = mBackdrop!!
            val bw = bmp.width
            val bh = bmp.height
            if (mUseAboveOnly && bh > height && height > 0) {
                val shader = BitmapShader(
                    bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
                )
                mShaderMatrix.setScale(width / bw.toFloat(), 1f)
                shader.setLocalMatrix(mShaderMatrix)
                mGlassPaint.shader = shader
            } else {
                val shader = BitmapShader(
                    bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
                )
                mShaderMatrix.setScale(width / bw.toFloat(), height / bh.toFloat())
                shader.setLocalMatrix(mShaderMatrix)
                mGlassPaint.shader = shader
            }

            mGlassPaint.alpha = GLASS_ALPHA
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mGlassPaint)
            mGlassPaint.shader = null
            mTintPaint.color = if (mDark) DARK_TINT else LIGHT_TINT
            mTintPaint.alpha = Color.alpha(mTintPaint.color)
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mTintPaint)
            mTintPaint.alpha = 255
        } else {
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mFallbackPaint)
        }
        MiuixGlassPipeline.drawEdgeHighlight(canvas, mBounds, mRadius, mDensity, mDark)
        if (PILL_BELOW_DROPLET) {
            drawSelectionPill(canvas)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!PILL_BELOW_DROPLET) {
            drawSelectionPill(canvas)
        }
        super.dispatchDraw(canvas)
    }

    private fun drawSelectionPill(canvas: Canvas) {
        if (mPillBroken) {
            return
        }
        try {
            if (mPill.draw(canvas, this, mDark)) {
                postInvalidateOnAnimation()
            }
        } catch (t: Throwable) {
            mPillBroken = true
            LiquidGlassLog.logErr("miuix tabbar selection pill failed", t)
        }
    }

    private fun reloadPillConfig() {
        try {
            mPill.reloadConfig()
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("miuix tabbar pill config failed", t)
        }
    }

    companion object {

        private const val CAPTURE_INTERVAL_MS = 120L

        private const val RETRY_INTERVAL_MS = 200L

        private const val MAX_FAST_RETRIES = 12
        private const val SLOW_RETRY_INTERVAL_MS = 500L

        private const val DEBUG_APPEARANCE = false

        private const val OVER_LIGHT_LUMA = 150
        private const val BACKDROP_SCALE = 1.0f

        private const val GLASS_ALPHA = 168

        private const val LIGHT_TINT = 0x24FFFFFF
        private const val DARK_TINT = 0x24101214

        private const val FALLBACK_DARK = 0x38000000
        private const val FALLBACK_LIGHT = 0x40FFFFFF.toInt()

        private const val PILL_BELOW_DROPLET = true
    }
}
