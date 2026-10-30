package com.better.heybox.liquidglass

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

internal object StackBlur {

    @JvmStatic
    fun blur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0 || radius < 1) {
            return
        }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val temp = IntArray(w * h)
        val wm = w - 1
        val hm = h - 1
        val div = radius + radius + 1

        for (pass in 0 until 3) {
            boxBlurH(pixels, temp, w, h, radius, wm, div)
            boxBlurV(temp, pixels, w, h, radius, hm, div)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int,
                         radius: Int, wm: Int, div: Int) {
        for (y in 0 until h) {
            val rowStart = y * w
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val p = src[rowStart + min(wm, max(0, i))]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                dst[rowStart + x] = (aSum / div shl 24) or
                        (rSum / div shl 16) or
                        (gSum / div shl 8) or
                        (bSum / div)
                val outX = max(x - radius, 0)
                val inX = min(x + radius + 1, wm)
                val pOut = src[rowStart + outX]
                val pIn = src[rowStart + inX]
                aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int,
                         radius: Int, hm: Int, div: Int) {
        for (x in 0 until w) {
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val p = src[min(hm, max(0, i)) * w + x]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (aSum / div shl 24) or
                        (rSum / div shl 16) or
                        (gSum / div shl 8) or
                        (bSum / div)
                val outY = max(y - radius, 0)
                val inY = min(y + radius + 1, hm)
                val pOut = src[outY * w + x]
                val pIn = src[inY * w + x]
                aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }
}
