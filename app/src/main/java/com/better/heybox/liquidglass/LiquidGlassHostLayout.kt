package com.better.heybox.liquidglass

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout

internal class LiquidGlassHostLayout(
    context: Context,
    private val mSampleRoot: ViewGroup,
    @Suppress("UNUSED_PARAMETER") bar: ViewGroup
) : FrameLayout(context) {

    companion object {
        private const val SAMPLE_SCALE_LEGACY = 0.4f
        private const val BLUR_RADIUS_LEGACY = 3
        private const val SATURATION_BOOST = 1.08f

        @JvmField
        val GLASS_TAG: Any = Any()
    }


    private val mDensity: Float = context.getResources().getDisplayMetrics().density
    private var mDarkMode: Boolean = isSystemNight(context)
    private var mCaptureCount: Int = 0

    private val mUseAgsl: Boolean = Build.VERSION.SDK_INT >= 33

    private var mExternalRenderer: Boolean = false

    private val mBackdropPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mTintPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mGlossPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBorderPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBounds: RectF = RectF()

    private var mCornerRadius: Float = 0f
    private var mRegionBuf: Bitmap? = null
    private var mCapturing: Boolean = false

    private var mPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    init {
        setTag(GLASS_TAG)
        setWillNotDraw(false)
        setupPaints()
        LiquidGlassLog.log(
            Log.INFO,
            "host created: sdk=" + Build.VERSION.SDK_INT +
                    " path=" + (if (mUseAgsl) "agsl" else "legacy-frost") +
                    " dark=" + mDarkMode + " source=uiMode"
        )
    }

    fun setExternalRendererActive(active: Boolean) {
        mExternalRenderer = active
    }

    private fun isSystemNight(context: Context): Boolean {
        val mode = context.getResources().getConfiguration().uiMode and
                Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupPaints() {
        if (!mUseAgsl) {
            if (mDarkMode) {
                mTintPaint.setColor(0x33000000)
                mBorderPaint.setColor(0x1FFFFFFF)
                mBackdropPaint.setColor(0x40000000)
            } else {
                mTintPaint.setColor(0x4DFFFFFF)
                mBorderPaint.setColor(0x2EFFFFFF)
                mBackdropPaint.setColor(0x8CFFFFFF.toInt())
            }
            mBorderPaint.setStyle(Paint.Style.STROKE)
            mBorderPaint.setStrokeWidth(Math.max(mDensity * 0.8f, 0.75f))
            updateGlossShader(getHeight())
        }
    }

    private fun updateGlossShader(h: Int) {
        if (getWidth() <= 0 || h <= 0) {
            return
        }
        mGlossPaint.setShader(
            LinearGradient(
                0f, 0f, 0f, h * 0.45f,
                if (mDarkMode) 0x1FFFFFFF else 0x40FFFFFF,
                0x00FFFFFF, Shader.TileMode.CLAMP
            )
        )
    }

    fun attach() {
        detach()
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!mCapturing && isAttachedToWindow()
                && getVisibility() == View.VISIBLE
                && getWidth() > 0 && getHeight() > 0
            ) {
                capture()
            }
            true
        }
        mPreDrawListener = listener
        mSampleRoot.getViewTreeObserver().addOnPreDrawListener(listener)
        invalidate()
        playRevealAnimation()
    }

    fun detach() {
        val listener = mPreDrawListener
        if (listener != null) {
            mSampleRoot.getViewTreeObserver().removeOnPreDrawListener(listener)
            mPreDrawListener = null
        }
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        mCornerRadius = Math.min(h * 0.46f, 30f * mDensity)
        if (mExternalRenderer) {
            return
        }
        if (!mUseAgsl) {
            updateGlossShader(h)
        }
    }

    private fun sampleScale(): Float {
        return if (mUseAgsl) 1.0f else SAMPLE_SCALE_LEGACY
    }

    private fun capture() {
        try {
            mCapturing = true
            maybeRefreshTheme()
            if (mExternalRenderer) {
                return
            }
            val w = getWidth()
            val h = getHeight()
            if (w <= 0 || h <= 0 || mSampleRoot.getWidth() <= 0) {
                return
            }
            ensureRegionBuffer(w, h)
            val region = mRegionBuf!!

            val c = Canvas(region)
            val scale = sampleScale()
            val rootLoc = IntArray(2)
            val selfLoc = IntArray(2)
            mSampleRoot.getLocationOnScreen(rootLoc)
            getLocationOnScreen(selfLoc)
            val dx = (selfLoc[0] - rootLoc[0]).toFloat()
            val dy = (selfLoc[1] - rootLoc[1]).toFloat()

            c.save()
            c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
            c.scale(scale, scale)
            c.translate(-dx, -dy)
            val vis = getVisibility()
            setVisibility(View.INVISIBLE)
            try {
                mSampleRoot.draw(c)
            } finally {
                setVisibility(vis)
                c.restore()
            }

            applySaturationBoost(region)
            if (!mUseAgsl) {
                StackBlur.blur(region, BLUR_RADIUS_LEGACY)
            }
            invalidate()
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("capture failed", t)
        } finally {
            mCapturing = false
        }
    }

    private fun ensureRegionBuffer(w: Int, h: Int) {
        val scale = sampleScale()
        val bw = Math.max(Math.round(w * scale), 1)
        val bh = Math.max(Math.round(h * scale), 1)
        val buf = mRegionBuf
        if (buf == null
            || buf.isRecycled()
            || buf.getWidth() != bw
            || buf.getHeight() != bh
        ) {
            mRegionBuf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            if (buf != null && !buf.isRecycled()) {
                buf.recycle()
            }
        } else {
            buf.eraseColor(Color.TRANSPARENT)
        }
    }

    private fun maybeRefreshTheme() {
        mCaptureCount++
        if (mCaptureCount % 20 != 1) {
            return
        }
        val detected = isSystemNight(getContext())
        if (mCaptureCount == 1) {
            LiquidGlassLog.log(
                Log.INFO,
                "theme probe first sample: dark=" + detected +
                        " current=" + mDarkMode
            )
        }
        if (detected != mDarkMode) {
            mDarkMode = detected
            setupPaints()
            invalidate()
            LiquidGlassLog.log(Log.INFO, "theme switched: dark=" + mDarkMode)
        }
    }

    private fun applySaturationBoost(bmp: Bitmap) {
        val cm = ColorMatrix()
        cm.setSaturation(SATURATION_BOOST)
        val p = Paint()
        p.setColorFilter(ColorMatrixColorFilter(cm))
        Canvas(bmp).drawBitmap(bmp, 0f, 0f, p)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mExternalRenderer) {
            return
        }
        if (getWidth() <= 0 || getHeight() <= 0) {
            return
        }
        drawLegacyFrost(canvas)
    }

    private fun drawLegacyFrost(canvas: Canvas) {
        val r = mCornerRadius
        val buf = mRegionBuf

        if (buf != null && !buf.isRecycled()) {
            val shader = BitmapShader(
                buf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
            )
            val m = Matrix()
            m.setScale(
                getWidth().toFloat() / buf.getWidth().toFloat(),
                getHeight().toFloat() / buf.getHeight().toFloat()
            )
            shader.setLocalMatrix(m)
            mBackdropPaint.setShader(shader)
        } else {
            mBackdropPaint.setShader(null)
            mBackdropPaint.setColor(if (mDarkMode) 0x50000000 else 0x8CFFFFFF.toInt())
        }
        canvas.drawRoundRect(mBounds, r, r, mBackdropPaint)

        canvas.drawRoundRect(mBounds, r, r, mTintPaint)
        canvas.drawRoundRect(mBounds, r, r, mGlossPaint)

        val half = mBorderPaint.getStrokeWidth() * 0.5f
        val border = RectF(half, half, getWidth() - half, getHeight() - half)
        canvas.drawRoundRect(border, r - half, r - half, mBorderPaint)
    }

    private fun playRevealAnimation() {
        try {
            setPivotX(getWidth() * 0.5f)
            setPivotY(getHeight().toFloat())
            setScaleY(0.86f)
            setAlpha(0f)
            animate().alpha(1f).scaleY(1f)
                .setDuration(380L)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        } catch (ignored: Throwable) {
        }
    }

    fun popChild(child: View?) {
        if (child == null || !isAttachedToWindow()) {
            return
        }
        try {
            child.setPivotX(child.getWidth() * 0.5f)
            child.setPivotY(child.getHeight() * 0.62f)
            val set = AnimatorSet()
            val up = ObjectAnimator.ofFloat(child, View.SCALE_X, 1f, 1.16f)
            up.setDuration(90L)
            up.setInterpolator(OvershootInterpolator(0.6f))
            val downX = ObjectAnimator.ofFloat(child, View.SCALE_X, 1.16f, 1f)
            val downY = ObjectAnimator.ofFloat(child, View.SCALE_Y, 1.16f, 1f)
            downX.setDuration(240L)
            downY.setDuration(240L)
            val back = OvershootInterpolator(2.2f)
            downX.setInterpolator(back)
            downY.setInterpolator(back)
            val downSet = AnimatorSet()
            downSet.playTogether(downX, downY)
            set.playSequentially(up, downSet)
            set.start()
        } catch (ignored: Throwable) {
        }
    }
}
