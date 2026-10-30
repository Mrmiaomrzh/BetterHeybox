package com.better.heybox.liquidglass

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class GlassSelectionPill {

    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val mPill = RectF()

    private val mTarget = RectF()

    private var mDensity = 0f
    private var mInsetX = 0
    private var mInsetY = 0

    private var mConfiguredTint = 0x29F2F2F2

    private var mSelectAlphaPct = 100

    private var mTintFollowsConfig = false

    private var mCachedTint = 0
    private var mCachedDark = false
    private var mCachedFailCase = -1

    private var mStartL = 0f
    private var mStartT = 0f
    private var mStartR = 0f
    private var mStartB = 0f
    private var mStartAt = 0L
    private var mAnimating = false

    private var mPlaced = false

    private var mSelected = Int.MIN_VALUE

    private var mRow: View? = null
    private var mTab: View? = null
    private var mTabIndex = Int.MIN_VALUE
    private var mRowChildCount = -1

    fun setDensity(densityIn: Float) {
        var density = densityIn
        if (density <= 0f) {
            density = 1f
        }
        if (density == mDensity && mInsetX > 0) {
            return
        }
        mDensity = density
        mInsetX = max(1, (INSET_DP_X * density).roundToInt())
        mInsetY = max(1, (INSET_DP_Y * density).roundToInt())
        mPlaced = false
    }

    fun reloadConfig() {
        mConfiguredTint = GlassConfig.tabSelectTint()
        mSelectAlphaPct = GlassConfig.tabSelectAlphaPct
        mTintFollowsConfig = GlassConfig.tabSelectFollowConfig()
        mCachedFailCase = -1
    }

    fun reset() {
        mPlaced = false
        mAnimating = false
        mRow = null
        mTab = null
        mTabIndex = Int.MIN_VALUE
        mRowChildCount = -1
    }

    fun pollSelection(bar: MiuixGlassTabBar): Boolean {
        val selected: Int
        try {
            selected = bar.selectedIndex
        } catch (ignored: Throwable) {
            return false
        }
        if (selected != mSelected) {
            mSelected = selected
            mTab = null
            mTabIndex = Int.MIN_VALUE
            return true
        }
        return false
    }

    fun draw(canvas: Canvas, bar: MiuixGlassTabBar, dark: Boolean): Boolean {
        val selected: Int
        try {
            selected = bar.selectedIndex
        } catch (ignored: Throwable) {
            return mAnimating
        }
        if (selected != mSelected) {
            mSelected = selected
        }
        if (selected < 0) {
            mPlaced = false
            mAnimating = false
            return mAnimating
        }
        val now = SystemClock.uptimeMillis()
        if (!computeTarget(bar, selected)) {
            mPlaced = false
            mAnimating = false
            return mAnimating
        }
        if (!mPlaced) {
            mPill.set(mTarget)
            mPlaced = true
            mAnimating = false
        } else if (!mAnimating && targetMoved()) {
            beginAnim(now)
        }
        if (mAnimating) {
            advanceAnim(now)
        }
        val w = mPill.width()
        val h = mPill.height()
        if (w <= 0f || h <= 0f) {
            return mAnimating
        }
        val radius = min(RADIUS_DP * mDensity, min(w, h) * 0.5f)
        mPaint.color = tintFor(dark)
        canvas.drawRoundRect(mPill, radius, radius, mPaint)
        return mAnimating
    }

    private fun computeTarget(bar: MiuixGlassTabBar, selected: Int): Boolean {
        val row = resolveRow(bar)
        if (row !is ViewGroup) {
            return false
        }
        val rowGroup = row
        val count = rowGroup.childCount
        if (count != mRowChildCount) {
            mRowChildCount = count
            mPlaced = false
        }
        val tab = resolveTab(rowGroup, selected)
        if (tab == null || tab.width <= 0 || tab.height <= 0) {
            return false
        }
        val bw = bar.width
        val bh = bar.height
        if (bw <= 0 || bh <= 0) {
            return false
        }
        val rowLeft = row.left
        val rowTop = row.top
        var left = rowLeft + tab.left + mInsetX.toFloat()
        var right = rowLeft + tab.right - mInsetX.toFloat()

        val barHeight = max(1, bh)
        val insetY = max(mInsetY, (barHeight * VERTICAL_INSET_RATIO).roundToInt())
        var top = rowTop + tab.top + insetY.toFloat()
        var bottom = rowTop + tab.bottom - insetY.toFloat()

        if (right - left < 1f) {
            val cx = (left + right) * 0.5f
            left = cx - 0.5f
            right = cx + 0.5f
        }
        if (bottom - top < 1f) {
            val cy = (top + bottom) * 0.5f
            top = cy - 0.5f
            bottom = cy + 0.5f
        }

        if (left < 0f) {
            left = 0f
        }
        if (top < 0f) {
            top = 0f
        }
        if (right > bw) {
            right = bw.toFloat()
        }
        if (bottom > bh) {
            bottom = bh.toFloat()
        }
        if (right <= left || bottom <= top) {
            return false
        }
        mTarget.set(left, top, right, bottom)
        return true
    }

    private fun resolveRow(bar: ViewGroup): View? {
        val cached = mRow
        if (cached != null && cached.parent === bar
            && cached is ViewGroup
            && cached.childCount > 0
        ) {
            return cached
        }
        mRow = null
        var best: View? = null
        var bestCount = 0
        for (i in 0 until bar.childCount) {
            val c = bar.getChildAt(i)
            if (c !is ViewGroup) {
                continue
            }
            if (c is com.example.liquidglass.LiquidGlassView) {
                continue
            }
            val n = c.childCount
            if (n > bestCount) {
                bestCount = n
                best = c
            }
        }
        if (best != null) {
            mRow = best
        }
        return mRow
    }

    private fun resolveTab(row: ViewGroup, selected: Int): View? {
        val index = if (selected < 0) 0 else selected
        val cached = mTab
        if (cached != null && cached.parent === row && mTabIndex == index) {
            return cached
        }
        var found: View? = null
        var idx = 0
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c !is LinearLayout) {
                continue
            }
            if (idx == index) {
                found = c
                break
            }
            idx++
        }
        if (found == null) {
            idx = 0
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c !is ViewGroup) {
                    continue
                }
                if (idx == index) {
                    found = c
                    break
                }
                idx++
            }
        }
        mTab = found
        mTabIndex = index
        return found
    }

    private fun targetMoved(): Boolean =
        abs(mTarget.left - mPill.left) > TARGET_EPSILON
                || abs(mTarget.top - mPill.top) > TARGET_EPSILON
                || abs(mTarget.right - mPill.right) > TARGET_EPSILON
                || abs(mTarget.bottom - mPill.bottom) > TARGET_EPSILON

    private fun beginAnim(now: Long) {
        mStartL = mPill.left
        mStartT = mPill.top
        mStartR = mPill.right
        mStartB = mPill.bottom
        mStartAt = now
        mAnimating = true
    }

    private fun advanceAnim(now: Long) {
        var t = (now - mStartAt) / ANIM_MS.toFloat()
        if (t >= 1f) {
            mPill.set(mTarget)
            mAnimating = false
            return
        }
        if (t < 0f) {
            t = 0f
        }
        val inv = 1f - t

        val e = 1f - inv * inv * inv
        mPill.left = mStartL + (mTarget.left - mStartL) * e
        mPill.top = mStartT + (mTarget.top - mStartT) * e
        mPill.right = mStartR + (mTarget.right - mStartR) * e
        mPill.bottom = mStartB + (mTarget.bottom - mStartB) * e
    }

    private fun tintFor(dark: Boolean): Int {
        if (mCachedFailCase == (if (dark) 1 else 0) && mCachedTint != 0) {
            return mCachedTint
        }
        val result = computeTintFor(dark)
        mCachedFailCase = if (dark) 1 else 0
        mCachedTint = result
        return result
    }

    private fun computeTintFor(dark: Boolean): Int {
        val alpha = 255 * max(0, min(100, mSelectAlphaPct)) / 100
        if (!mTintFollowsConfig) {
            val gray = if (dark) 0xEBEBEB else 0xF2F2F2
            return (alpha shl 24) or gray
        }
        val base = mConfiguredTint
        var a = (base ushr 24) and 0xFF
        a = (a * (mSelectAlphaPct / 100f)).roundToInt()
        if (!dark) {
            return (max(0, min(255, a)) shl 24) or (base and 0x00FFFFFF)
        }
        val cap = 255 * DARK_ALPHA_CAP_PCT / 100
        val na = min(cap, (a * DARK_ALPHA_SCALE).roundToInt())
        var r = (base shr 16) and 0xFF
        var g = (base shr 8) and 0xFF
        var b = base and 0xFF
        r = (r + (255 - r) * DARK_WHITE_MIX).roundToInt()
        g = (g + (255 - g) * DARK_WHITE_MIX).roundToInt()
        b = (b + (255 - b) * DARK_WHITE_MIX).roundToInt()
        return (na shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {

        private const val INSET_DP_X = 3.5f

        private const val INSET_DP_Y = 4f

        private const val VERTICAL_INSET_RATIO = 0.12f

        private const val RADIUS_DP = 13f

        private const val DARK_ALPHA_SCALE = 1.5f

        private const val DARK_ALPHA_CAP_PCT = 98

        private const val DARK_WHITE_MIX = 0.08f

        private const val ANIM_MS = 200L

        private const val TARGET_EPSILON = 0.5f
    }
}
