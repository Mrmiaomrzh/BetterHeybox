package com.better.heybox.liquidglass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.math.roundToInt

internal class SearchGlyphDrawable private constructor() : Drawable() {

    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPath = Path()

    private var mDensity = 1f

    private var mIntrinsicSize = 0

    private var mAlpha = 255

    private var mTintList: ColorStateList? = null
    private var mGeometryValid = false

    init {
        mPaint.style = Paint.Style.STROKE
        mPaint.strokeCap = Paint.Cap.ROUND
        mPaint.strokeJoin = Paint.Join.ROUND
        mPaint.color = BASE_COLOR
    }

    override fun getIntrinsicWidth(): Int = mIntrinsicSize

    override fun getIntrinsicHeight(): Int = mIntrinsicSize

    override fun onBoundsChange(bounds: Rect) {
        mGeometryValid = false
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) {
            return
        }
        val size = min(w, h).toFloat()
        val ox = bounds.left + (w - size) * 0.5f
        val oy = bounds.top + (h - size) * 0.5f

        var stroke = STROKE_DP * mDensity
        val refSize = CONTENT_DP * mDensity
        if (refSize > 0f && size < refSize) {
            stroke *= size / refSize
        }
        if (stroke <= 0f) {
            stroke = 1f
        }
        mPaint.strokeWidth = stroke

        val cx = ox + size * LENS_CX
        val cy = oy + size * LENS_CY

        val r = size * LENS_R_OUTER - stroke * 0.5f
        if (r <= 0f) {
            return
        }
        mPath.reset()
        mPath.addCircle(cx, cy, r, Path.Direction.CW)

        val inner = r * SQRT_HALF
        mPath.moveTo(cx + inner, cy + inner)
        mPath.lineTo(ox + size * HANDLE_END, oy + size * HANDLE_END)
        mGeometryValid = true
    }

    override fun draw(canvas: Canvas) {
        if (!mGeometryValid) {
            return
        }
        val color = mTintList?.getColorForState(state, BASE_COLOR) ?: BASE_COLOR

        val alpha = Color.alpha(color) * mAlpha / 255
        mPaint.color = (color and 0x00FFFFFF) or (alpha shl 24)
        canvas.drawPath(mPath, mPaint)
    }

    override fun setAlpha(alpha: Int) {
        val clamped = if (alpha < 0) 0 else (if (alpha > 255) 255 else alpha)
        if (clamped == mAlpha) {
            return
        }
        mAlpha = clamped
        invalidateSelf()
    }

    override fun getAlpha(): Int = mAlpha

    @Deprecated("Deprecated in Java")
    override fun setColorFilter(colorFilter: ColorFilter?) {
        mPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getColorFilter(): ColorFilter? = mPaint.colorFilter

    override fun setTintList(tint: ColorStateList?) {
        mTintList = tint
        invalidateSelf()
    }

    override fun isStateful(): Boolean =
        mTintList != null && mTintList!!.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        if (mTintList != null && mTintList!!.isStateful) {
            invalidateSelf()
            return true
        }
        return false
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {

        private const val CONTENT_DP = 24f

        private const val STROKE_DP = 2f

        private const val BASE_COLOR = 0xFFFFFFFF.toInt()

        private const val LENS_CX = 0.4375f
        private const val LENS_CY = 0.4375f
        private const val LENS_R_OUTER = 0.271f
        private const val HANDLE_END = 0.855f
        private const val SQRT_HALF = 0.70710677f

        internal fun create(context: Context?): Drawable {
            val d = SearchGlyphDrawable()
            var density = 1f
            if (context != null) {
                try {
                    density = context.resources.displayMetrics.density
                } catch (ignored: Throwable) {
                }
            }
            if (density <= 0f) {
                density = 1f
            }
            d.mDensity = density
            d.mIntrinsicSize = (CONTENT_DP * density).roundToInt()
            return d
        }
    }
}
