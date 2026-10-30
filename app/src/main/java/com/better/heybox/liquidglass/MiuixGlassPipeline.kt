package com.better.heybox.liquidglass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

internal object MiuixGlassPipeline {

    class Style {
        val blurRadiusDp = 20f
        val saturation = 1.22f
        val brightness = 0.02f
        val contrast = 1.04f
        val noiseCoefficient = 0.0045f
        val highlightWidthDp = 0.8f
        val innerBlurRadiusDp = 2.8f
        val highlightAlpha = 0.06f
        val primaryIntensity = 0.40f
        val secondaryIntensity = 0.25f
        val cornerRadiusDp = 30f

        val surfaceTint = 0.17f

        val noiseScale = 1f
    }

    private val STYLE = Style()

    private var sNoise: Bitmap? = null

    @JvmStatic
    fun clampBlurDp(dp: Float): Float {
        return max(0f, min(150f, dp))
    }

    @JvmStatic
    fun process(bitmap: Bitmap?, density: Float, darkMode: Boolean) {
        process(bitmap, density, darkMode, STYLE)
    }

    @JvmStatic
    fun process(bitmap: Bitmap?, density: Float, darkMode: Boolean, style: Style?) {
        if (bitmap == null || bitmap.isRecycled || style == null) {
            return
        }
        val radius = max(1,
                Math.round(clampBlurDp(style.blurRadiusDp) * density))

        val scaled = max(3, Math.round(radius * 0.22f))
        StackBlur.blur(bitmap, scaled)
        val canvas = Canvas(bitmap)
        applyColorControls(bitmap, darkMode, style)

        val tint = Paint()
        tint.color = if (darkMode)
            withAlpha(0xFF000000.toInt(), style.surfaceTint)
        else
            withAlpha(0xFFFFFFFF.toInt(), style.surfaceTint)
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), tint)
        drawNoise(canvas, bitmap.width, bitmap.height, darkMode, style)
    }

    private fun applyColorControls(bitmap: Bitmap, darkMode: Boolean, style: Style) {
        val cm = ColorMatrix()
        cm.setSaturation(style.saturation)

        val translate = style.brightness * 100f * (if (darkMode) 0.6f else 1f)
        val contrast = style.contrast
        val t = (1f - contrast) * 127.5f + translate
        cm.postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
        )))
        val p = Paint()
        p.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(bitmap).drawBitmap(bitmap, 0f, 0f, p)
    }

    private fun drawNoise(canvas: Canvas, width: Int, height: Int, darkMode: Boolean,
                          style: Style) {
        val alpha = Math.round(style.noiseCoefficient * 255f * 8f)
        if (alpha <= 0) {
            return
        }
        val noise = noiseBitmap() ?: return
        val p = Paint()
        p.shader = BitmapShader(
                noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.alpha = min(alpha, 12)
        p.colorFilter = PorterDuffColorFilter(
                if (darkMode) Color.WHITE else Color.BLACK, PorterDuff.Mode.SRC_IN)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }

    @Synchronized
    private fun noiseBitmap(): Bitmap? {
        val current = sNoise
        if (current != null && !current.isRecycled) {
            return current
        }
        return try {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.TRANSPARENT)
            var seed = 0x1234567
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    seed = seed * 1103515245 + 12345
                    val v = (seed ushr 16) and 0xFF
                    bmp.setPixel(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
                }
            }
            sNoise = bmp
            bmp
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("noise bitmap failed", t)
            null
        }
    }

    @JvmStatic
    fun drawEdgeHighlight(canvas: Canvas, bounds: RectF, radius: Float,
                          density: Float, darkMode: Boolean) {
        val style = STYLE
        val width = max(style.highlightWidthDp * density, 0.75f)
        val innerBlur = max(style.innerBlurRadiusDp * density, 2f)
        val inset = width * 0.5f
        val r = RectF(bounds)
        r.inset(inset, inset)
        val rr = max(0f, radius - inset)

        val primary = Paint(Paint.ANTI_ALIAS_FLAG)
        primary.style = Paint.Style.STROKE
        primary.strokeWidth = width
        val light = if (darkMode) 0x33FFFFFF else 0x40FFFFFF
        primary.shader = LinearGradient(0f, r.top, r.width() * 0.7f, r.bottom,
                withAlpha(light, style.primaryIntensity), 0x00000000, Shader.TileMode.CLAMP)
        if (innerBlur > 0f) {
            primary.maskFilter = BlurMaskFilter(innerBlur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(r, rr, rr, primary)

        val secondary = Paint(Paint.ANTI_ALIAS_FLAG)
        secondary.style = Paint.Style.STROKE
        secondary.strokeWidth = width
        secondary.shader = LinearGradient(r.width() * 0.3f, r.bottom, r.width(),
                r.top, 0x00000000,
                withAlpha(light, style.secondaryIntensity), Shader.TileMode.CLAMP)
        secondary.maskFilter = BlurMaskFilter(innerBlur, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(r, rr, rr, secondary)
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val a = Math.round(Color.alpha(color) * max(0f, min(1f, factor)))
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    @JvmStatic
    fun featherTop(canvas: Canvas, width: Int, featherPx: Float, fromTop: Boolean) {
        if (featherPx <= 0f) {
            return
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        if (fromTop) {
            p.shader = LinearGradient(0f, 0f, 0f, featherPx,
                    0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        } else {
            p.shader = LinearGradient(0f, featherPx, 0f, 0f,
                    0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), featherPx, p)
    }

    @JvmStatic
    fun drawFeatheredTop(canvas: Canvas, bounds: RectF, backdrop: Bitmap?,
                         blurScale: Float, featherPx: Float, darkMode: Boolean) {
        if (backdrop == null || backdrop.isRecycled || bounds.isEmpty) {
            return
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = BitmapShader(
                backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = android.graphics.Matrix()
        m.setScale(bounds.width() / backdrop.width.toFloat(),
                bounds.height() / backdrop.height.toFloat())
        shader.setLocalMatrix(m)
        p.shader = shader
        p.isFilterBitmap = true
        canvas.drawRect(bounds, p)

        val tint = Paint()
        tint.color = if (darkMode) 0x40000000 else 0x33FFFFFF
        canvas.drawRect(bounds, tint)
        featherTop(canvas, Math.ceil(bounds.width().toDouble()).toInt(), featherPx, false)
    }
}
