package com.better.heybox

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

object CustomTextSelection {

    interface OwnListener

    private const val MENU_DISMISS_DELAY_MS = 220L

    private const val TAG = "BetterHeybox"
    private const val DEFAULT_ACCENT = 0xFF1677FF.toInt()
    private val CONTROLLERS: MutableMap<TextView, Controller> =
        Collections.synchronizedMap(WeakHashMap<TextView, Controller>())

    private fun endAnchor(layout: Layout, text: CharSequence, end: Int, outLine: IntArray): Float {
        var line = layout.getLineForOffset(end)
        var x = layout.getPrimaryHorizontal(end)
        if (line > 0 && end == layout.getLineStart(line)) {
            line = line - 1
            x = layout.getLineRight(line)
        } else if (end > 0 && end <= text.length &&
            (text[end - 1] == '\n' || text[end - 1] == '\r')
        ) {
            x = layout.getLineRight(line)
        }
        outLine[0] = line
        return x
    }

    @JvmStatic
    fun attach(tv: TextView?) {
        attach(tv, true, null)
    }

    @JvmStatic
    fun attach(tv: TextView?, overlayHost: ViewGroup?) {
        attach(tv, true, overlayHost)
    }

    @JvmStatic
    fun attachPassive(tv: TextView?) {
        attach(tv, false, null)
    }

    @JvmStatic
    fun startSelectionNow(tv: TextView?, overlayHost: ViewGroup?, x: Float, y: Float): Boolean {
        if (tv == null) {
            return false
        }
        attach(tv, true, overlayHost)
        val controller = synchronized(CONTROLLERS) { CONTROLLERS[tv] }
        return controller != null && controller.beginSelectionNow(x, y)
    }

    private fun attach(tv: TextView?, takeLongPress: Boolean, overlayHost: ViewGroup?) {
        if (tv == null) {
            return
        }
        synchronized(CONTROLLERS) {
            val existing = CONTROLLERS[tv]
            if (existing != null) {
                existing.updateOverlayHost(overlayHost)
                existing.rebind(takeLongPress)
                return
            }
            val controller = Controller(tv, takeLongPress, overlayHost)
            CONTROLLERS[tv] = controller
            controller.attach()
        }
    }

    @JvmStatic
    fun isOwnListener(listener: Any?): Boolean {
        return listener is OwnListener
    }

    @JvmStatic
    fun beginSelection(tv: TextView?, x: Float, y: Float): Boolean {
        if (tv == null || tv.windowToken == null || !tv.isShown) {
            return false
        }
        val controller = synchronized(CONTROLLERS) { CONTROLLERS[tv] }
        if (controller == null) {
            return false
        }
        return controller.beginSelectionAt(x, y)
    }

    @JvmStatic
    fun lastDownPoint(tv: TextView?, out: FloatArray?): Boolean {
        if (tv == null || out == null || out.size < 2) {
            return false
        }
        val controller = synchronized(CONTROLLERS) { CONTROLLERS[tv] }
        if (controller == null || !controller.hasDownPoint()) {
            return false
        }
        out[0] = controller.downX
        out[1] = controller.downY
        return true
    }

    @JvmStatic
    fun detach(tv: TextView?) {
        if (tv == null) {
            return
        }
        val controller = synchronized(CONTROLLERS) { CONTROLLERS.remove(tv) }
        if (controller != null) {
            controller.detach()
        }
    }

    private fun cancelAll() {
        synchronized(CONTROLLERS) {
            for (controller in CONTROLLERS.values) {
                try {
                    controller.cancel()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private class Controller(
        private val tv: TextView,
        private var takeLongPress: Boolean,
        private var overlayHost: ViewGroup?,
    ) : View.OnTouchListener, View.OnLongClickListener, OwnListener {


        private var prevTouch: View.OnTouchListener? = readListener(tv, "mOnTouchListener")
        private val prevLongClick: View.OnLongClickListener? = readListener(tv, "mOnLongClickListener")

        private val accentColor: Int = ThemeUtils.resolveAccent(tv.context)
        private val density: Float = tv.resources.displayMetrics.density

        private var selecting = false
        private var anchor = -1
        private var selStart = -1
        private var selEnd = -1

        var downX = 0f
        var downY = 0f
        private var hasDown = false

        private var overlay: HighlightOverlay? = null
        private var startHandle: SelectionHandle? = null
        private var endHandle: SelectionHandle? = null
        private var menuScrim: View? = null
        private var menuBubble: LinearLayout? = null
        private var selectAllItem: TextView? = null
        private var selectAllDivider: View? = null
        private var menuAbove = false
        private var draggingHandle = 0

        private val scrollListener = object : ViewTreeObserver.OnScrollChangedListener {
            override fun onScrollChanged() {
                syncOverlays(false)
            }
        }

        private val layoutListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?,
                l: Int,
                t: Int,
                r: Int,
                b: Int,
                ol: Int,
                ot: Int,
                or: Int,
                ob: Int,
            ) {
                if (l != ol || t != ot || r != or || b != ob) {
                    syncOverlays(false)
                }
            }
        }

        fun updateOverlayHost(host: ViewGroup?) {
            if (host != null) {
                overlayHost = host
            }
        }

        private fun overlayHost(): ViewGroup? {
            return overlayHost ?: ViewUtils.findDecor(tv)
        }

        fun attach() {
            tv.setOnTouchListener(this)
            if (takeLongPress) {
                if (tv.isTextSelectable) {
                    tv.setTextIsSelectable(false)
                }
                tv.setMovementMethod(null)
                tv.setOnLongClickListener(this)
            }
            tv.addOnLayoutChangeListener(layoutListener)
        }

        fun rebind(takeLongPressNow: Boolean) {
            if (takeLongPress && !takeLongPressNow) {
                tv.setOnLongClickListener(prevLongClick)
            }
            takeLongPress = takeLongPressNow
            val current: View.OnTouchListener? = readListener(tv, "mOnTouchListener")
            if (current != null && current !is OwnListener) {
                prevTouch = current
            }
            attach()
        }

        fun detach() {
            cancel()
            tv.removeOnLayoutChangeListener(layoutListener)
            tv.setOnTouchListener(prevTouch)
            if (takeLongPress) {
                tv.setOnLongClickListener(prevLongClick)
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    hasDown = true
                    if (selecting || menuScrim != null) {
                        cancel()
                    }
                    val pt = prevTouch
                    pt != null && pt.onTouch(v, event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (selecting) {
                        updateSelection(event.x, event.y)
                        true
                    } else {
                        val pt = prevTouch
                        pt != null && pt.onTouch(v, event)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (selecting) {
                        finishSelection()
                        true
                    } else {
                        val pt = prevTouch
                        pt != null && pt.onTouch(v, event)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (selecting) {
                        cancel()
                        true
                    } else {
                        val pt = prevTouch
                        pt != null && pt.onTouch(v, event)
                    }
                }
                else -> {
                    val pt = prevTouch
                    pt != null && pt.onTouch(v, event)
                }
            }
        }

        fun beginSelectionAt(x: Float, y: Float): Boolean {
            try {
                tv.postDelayed(Runnable { selectAt(x, y) }, MENU_DISMISS_DELAY_MS)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "安排选择态失败: " + t)
                return false
            }
        }

        fun beginSelectionNow(x: Float, y: Float): Boolean {
            selectAt(x, y)
            return selStart >= 0 && selEnd > selStart
        }

        private fun selectAt(x: Float, y: Float) {
            try {
                if (!tv.isShown || tv.windowToken == null ||
                    tv.layout == null || tv.width <= 0
                ) {
                    return
                }
                cancelAll()
                val text: CharSequence? = tv.text
                val len = if (text == null) 0 else text.length
                if (len == 0) {
                    return
                }
                var start = 0
                var end = len
                if (x >= 0 && y >= 0) {
                    val word = wordBoundary(tv.getOffsetForPosition(x, y))
                    if (word[1] > word[0]) {
                        start = word[0]
                        end = word[1]
                    }
                }
                startSelection(start, end)
                finishSelection()
            } catch (t: Throwable) {
                Log.w(TAG, "进入选择态失败: " + t)
            }
        }

        fun hasDownPoint(): Boolean {
            return hasDown
        }

        override fun onLongClick(v: View): Boolean {
            if (selecting) {
                return true
            }
            val layout = tv.layout
            val text: CharSequence? = tv.text
            if (layout == null || text == null || text.length == 0) {
                val plc = prevLongClick
                return plc != null && plc.onLongClick(v)
            }
            cancelAll()
            var offset = tv.getOffsetForPosition(downX, downY)
            if (offset < 0) {
                offset = 0
            }
            val word = wordBoundary(offset)
            startSelection(word[0], word[1])
            return true
        }

        private fun startSelection(start: Int, end: Int) {
            selecting = true
            anchor = start
            selStart = start
            selEnd = end
            tv.parent?.requestDisallowInterceptTouchEvent(true)
            try {
                tv.viewTreeObserver.addOnScrollChangedListener(scrollListener)
            } catch (ignored: Throwable) {
            }
            updateHighlight()
            showHandles()
        }

        private fun updateSelection(x: Float, y: Float) {
            val layout = tv.layout
            if (layout == null) {
                return
            }
            var offset = tv.getOffsetForPosition(x, y)
            val t = tv.text
            val len = if (t == null) 0 else t.length
            if (offset < 0) {
                offset = 0
            }
            if (offset > len) {
                offset = len
            }
            selStart = Math.min(anchor, offset)
            selEnd = Math.max(anchor, offset)
            updateHighlight()
            updateHandlePositions()
        }

        private fun finishSelection() {
            tv.parent?.requestDisallowInterceptTouchEvent(false)
            if (!selecting) {
                return
            }
            selecting = false
            if (selStart < 0 || selEnd <= selStart) {
                cancel()
                return
            }
            showMenu()
        }

        private fun syncOverlays(animateMenu: Boolean) {
            if (!selecting && menuBubble == null) {
                return
            }
            if (tv.windowToken == null || !tv.isShown) {
                return
            }
            if (selecting && selStart >= 0 && selEnd > selStart) {
                ensureOverlay()
                positionOverlay()
                updateHandlePositions()
            }
            if (menuBubble?.visibility == View.VISIBLE) {
                positionMenuBubble(animateMenu)
            }
        }

        private fun updateHighlight() {
            if (selStart >= 0 && selEnd > selStart) {
                ensureOverlay()
                val o = overlay
                if (o != null) {
                    o.setSelection(selStart, selEnd)
                    positionOverlay()
                }
            } else {
                removeOverlay()
            }
        }

        private fun ensureOverlay() {
            if (overlay != null) {
                return
            }
            val decor = overlayHost()
            if (decor == null) {
                return
            }
            val o = HighlightOverlay(tv.context, tv, accentColor)
            decor.addView(o, FrameLayout.LayoutParams(1, 1))
            overlay = o
        }

        private fun positionOverlay() {
            val o = overlay
            if (o == null) {
                return
            }
            if (tv.windowToken == null || !tv.isShown || tv.width <= 0) {
                return
            }
            val loc = IntArray(2)
            tv.getLocationInWindow(loc)
            val lp = o.layoutParams as FrameLayout.LayoutParams
            val parent = o.parent as ViewGroup?
            val padLeft = parent?.paddingLeft ?: 0
            val padTop = parent?.paddingTop ?: 0
            if (lp.width != tv.width || lp.height != tv.height ||
                lp.leftMargin != loc[0] - padLeft || lp.topMargin != loc[1] - padTop
            ) {
                lp.width = tv.width
                lp.height = tv.height
                lp.leftMargin = loc[0] - padLeft
                lp.topMargin = loc[1] - padTop
                o.layoutParams = lp
            }
            o.invalidate()
        }

        private fun removeOverlay() {
            val o = overlay
            if (o != null) {
                val parent: ViewParent? = o.parent
                if (parent is ViewGroup) {
                    parent.removeView(o)
                }
                overlay = null
            }
        }

        private val handleTouch = object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val isStart = v === startHandle
                return when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        draggingHandle = if (isStart) 1 else 2
                        hideMenuBubble()
                        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(70L)
                            .setInterpolator(DecelerateInterpolator()).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (draggingHandle != 0) {
                            adjustByHandle(draggingHandle == 1, event.rawX, event.rawY)
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (draggingHandle != 0) {
                            draggingHandle = 0
                            v.animate().scaleX(1f).scaleY(1f).setDuration(90L)
                                .setInterpolator(DecelerateInterpolator()).start()
                            showMenuBubble()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }

        private fun showHandles() {
            if (tv.windowToken == null) {
                return
            }
            if (startHandle == null || endHandle == null) {
                val decor = overlayHost()
                if (decor == null) {
                    return
                }
                val size = ThemeUtils.dp(tv.context, 38f)
                val sh = SelectionHandle(tv.context, tv, accentColor)
                val eh = SelectionHandle(tv.context, tv, accentColor)
                startHandle = sh
                endHandle = eh
                sh.setOnTouchListener(handleTouch)
                eh.setOnTouchListener(handleTouch)
                decor.addView(sh, FrameLayout.LayoutParams(size, size))
                decor.addView(eh, FrameLayout.LayoutParams(size, size))
            }
            updateHandlePositions()
        }

        private fun updateHandlePositions() {
            val sh = startHandle
            val eh = endHandle
            if (sh == null || eh == null) {
                return
            }
            val layout = tv.layout
            if (layout == null || tv.windowToken == null || !tv.isShown) {
                return
            }
            val loc = IntArray(2)
            tv.getLocationInWindow(loc)
            positionHandle(sh, loc, layout, selStart, true)
            positionHandle(eh, loc, layout, selEnd, false)
        }

        private fun endAnchorX(layout: Layout, text: CharSequence, end: Int, outLine: IntArray): Float {
            return endAnchor(layout, text, end, outLine)
        }

        private fun positionHandle(
            handle: SelectionHandle,
            tvLoc: IntArray,
            layout: Layout,
            offset: Int,
            isStart: Boolean,
        ) {
            var off = offset
            if (off < 0) {
                off = 0
            }
            val text: CharSequence? = tv.text
            if (text == null) {
                return
            }
            var line = layout.getLineForOffset(off)
            val x: Float
            if (isStart) {
                x = layout.getPrimaryHorizontal(off)
            } else {
                val out = IntArray(1)
                x = endAnchorX(layout, text, off, out)
                line = out[0]
            }
            val baseY = layout.getLineBottom(line)
            val winX = tvLoc[0] + tv.totalPaddingLeft - tv.scrollX + x
            val winY = tvLoc[1] + tv.totalPaddingTop - tv.scrollY + baseY
            val lp = handle.layoutParams as FrameLayout.LayoutParams
            val size = lp.width
            val parent = handle.parent as ViewGroup?
            val padLeft = parent?.paddingLeft ?: 0
            val padTop = parent?.paddingTop ?: 0
            val left = (winX - padLeft - size / 2f).toInt()
            val top = (winY - padTop - density).toInt()
            if (lp.leftMargin != left || lp.topMargin != top) {
                lp.leftMargin = left
                lp.topMargin = top
                handle.layoutParams = lp
            }
            handle.invalidate()
        }

        private fun adjustByHandle(isStart: Boolean, rawX: Float, rawY: Float) {
            val layout = tv.layout
            val text: CharSequence? = tv.text
            if (layout == null || text == null) {
                return
            }
            val loc = IntArray(2)
            tv.getLocationOnScreen(loc)
            var offset = tv.getOffsetForPosition(rawX - loc[0], rawY - loc[1])
            val len = text.length
            if (offset < 0) {
                offset = 0
            }
            if (offset > len) {
                offset = len
            }
            if (isStart) {
                val maxStart = Math.max(0, selEnd - 1)
                selStart = Math.min(offset, maxStart)
            } else {
                val minEnd = Math.min(len, selStart + 1)
                selEnd = Math.max(offset, minEnd)
            }
            updateHighlight()
            updateHandlePositions()
        }

        private fun removeHandles() {
            removeView(startHandle)
            removeView(endHandle)
            startHandle = null
            endHandle = null
        }

        private fun showMenu() {
            val context: Context? = tv.context
            if (context == null) {
                cancel()
                return
            }
            removeMenu()
            val decor = overlayHost()
            if (decor == null) {
                cancel()
                return
            }

            val scrim = FrameLayout(context)
            scrim.isClickable = true
            scrim.setOnClickListener { cancel() }
            scrim.setFocusableInTouchMode(true)
            scrim.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                    cancel()
                    true
                } else {
                    false
                }
            }
            decor.addView(
                scrim,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            scrim.requestFocus()
            menuScrim = scrim

            val bubble = buildMenuBar(context)
            menuBubble = bubble
            decor.addView(
                bubble,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            updateMenuItemsState()
            positionMenuBubble(false)
            animateMenuIn()

            val sh = startHandle
            if (sh != null) {
                decor.bringChildToFront(sh)
            }
            val eh = endHandle
            if (eh != null) {
                decor.bringChildToFront(eh)
            }
        }

        private fun positionMenuBubble(animate: Boolean) {
            val bubble = menuBubble
            if (bubble == null) {
                return
            }
            val layout = tv.layout
            val text: CharSequence? = tv.text
            if (layout == null || text == null) {
                return
            }
            val context = tv.context
            val parent = bubble.parent as ViewGroup?
            if (parent == null) {
                return
            }
            bubble.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val w = bubble.measuredWidth
            val h = bubble.measuredHeight

            val padLeft = parent.paddingLeft
            val padTop = parent.paddingTop
            val availRight = padLeft + parent.width - parent.paddingRight
            val availBottom = padTop + parent.height - parent.paddingBottom
            val margin = ThemeUtils.dp(context, 4f)
            val topLimit = padTop + margin
            val leftLimit = padLeft + margin

            val loc = IntArray(2)
            tv.getLocationInWindow(loc)
            val textLeft: Float = (loc[0] + tv.totalPaddingLeft - tv.scrollX).toFloat()
            val textTop: Float = (loc[1] + tv.totalPaddingTop - tv.scrollY).toFloat()

            val startLine = layout.getLineForOffset(selStart)
            val endLineOut = IntArray(1)
            val endX = endAnchorX(layout, text, selEnd, endLineOut)
            val selCenterX = textLeft +
                (layout.getPrimaryHorizontal(selStart) + endX) / 2f
            val selTop = (textTop + layout.getLineTop(startLine)).toInt()
            val selBottom = (textTop + layout.getLineBottom(endLineOut[0])).toInt()

            var px = (selCenterX - w / 2f).toInt()
            val py: Int
            val above = selTop - h - ThemeUtils.dp(context, 6f)
            val below = selBottom + ThemeUtils.dp(context, 6f)
            if (above >= topLimit) {
                py = above
                menuAbove = true
            } else if (below + h <= availBottom - margin) {
                py = below
                menuAbove = false
            } else {
                py = Math.max(topLimit, Math.min(above, availBottom - margin - h))
                menuAbove = py <= selTop
            }
            if (px < leftLimit) {
                px = leftLimit
            }
            if (px + w > availRight - margin) {
                px = availRight - margin - w
            }

            val lp = bubble.layoutParams as FrameLayout.LayoutParams
            val oldLeft = lp.leftMargin
            val oldTop = lp.topMargin
            val moved = lp.leftMargin != px || lp.topMargin != py ||
                lp.width != w || lp.height != h
            lp.width = w
            lp.height = h
            lp.leftMargin = px
            lp.topMargin = py
            if (moved) {
                bubble.layoutParams = lp
            }
            if (animate && moved) {
                bubble.translationX = (oldLeft - px).toFloat()
                bubble.translationY = (oldTop - py).toFloat()
                bubble.animate().translationX(0f).translationY(0f).setDuration(140L)
                    .setInterpolator(DecelerateInterpolator(1.5f)).start()
            } else {
                bubble.translationX = 0f
                bubble.translationY = 0f
            }
        }

        private fun animateMenuIn() {
            val bubble = menuBubble
            if (bubble == null) {
                return
            }
            val lp = bubble.layoutParams as FrameLayout.LayoutParams
            bubble.pivotX = lp.width / 2f
            bubble.pivotY = if (menuAbove) lp.height.toFloat() else 0f
            bubble.scaleX = 0.86f
            bubble.scaleY = 0.86f
            bubble.alpha = 0f
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130L)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }

        private fun hideMenuBubble() {
            val bubble = menuBubble
            if (bubble != null) {
                bubble.animate().cancel()
                bubble.visibility = View.GONE
            }
        }

        private fun showMenuBubble() {
            val bubble = menuBubble
            if (bubble != null) {
                updateMenuItemsState()
                bubble.visibility = View.VISIBLE
                positionMenuBubble(true)
            }
        }

        private fun updateMenuItemsState() {
            val item = selectAllItem
            if (item == null) {
                return
            }
            val text: CharSequence? = tv.text
            val canSelectAll = text != null && text.length > 0 &&
                (selStart > 0 || selEnd < text.length)
            if (item.visibility != (if (canSelectAll) View.VISIBLE else View.GONE)) {
                item.visibility = if (canSelectAll) View.VISIBLE else View.GONE
                val divider = selectAllDivider
                if (divider != null) {
                    divider.visibility = if (canSelectAll) View.VISIBLE else View.GONE
                }
            }
        }

        private fun removeMenu() {
            removeView(menuScrim)
            removeView(menuBubble)
            menuScrim = null
            menuBubble = null
            selectAllItem = null
            selectAllDivider = null
        }

        private fun removeView(view: View?) {
            if (view != null) {
                val parent: ViewParent? = view.parent
                if (parent is ViewGroup) {
                    parent.removeView(view)
                }
            }
        }

        private fun buildMenuBar(context: Context): LinearLayout {
            val dark = ThemeUtils.isDarkMode(context)
            val bgColor: Int = if (dark) 0xFF2A2A2E.toInt() else 0xFFFFFFFF.toInt()
            val textColor: Int = if (dark) 0xDEFFFFFF.toInt() else 0xDD000000.toInt()
            val dividerColor = if (dark) 0x24FFFFFF else 0x1F000000
            val rippleColor = if (dark) 0x2EFFFFFF else 0x1A000000

            val bar = LinearLayout(context)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.isClickable = true
            bar.elevation = ThemeUtils.dp(context, 8f).toFloat()

            val bg = GradientDrawable()
            bg.setColor(bgColor)
            bg.cornerRadius = ThemeUtils.dp(context, 16f).toFloat()
            bar.background = bg

            val selectAll = menuItem(context, "全选", textColor, rippleColor, dark)
            selectAll.setOnClickListener { doSelectAll() }
            bar.addView(selectAll)
            selectAllItem = selectAll
            selectAllDivider = addDivider(bar, context, dividerColor)

            val share = menuItem(context, "分享", textColor, rippleColor, dark)
            share.setOnClickListener { doShare() }
            bar.addView(share)
            addDivider(bar, context, dividerColor)

            val copy = menuItem(context, "复制", textColor, rippleColor, dark)
            copy.setOnClickListener { doCopy() }
            bar.addView(copy)
            return bar
        }

        private fun addDivider(bar: LinearLayout, context: Context, color: Int): View {
            val divider = View(context)
            divider.setBackgroundColor(color)
            val lp = LinearLayout.LayoutParams(
                ThemeUtils.dp(context, 1f), ThemeUtils.dp(context, 20f),
            )
            lp.gravity = Gravity.CENTER_VERTICAL
            divider.layoutParams = lp
            bar.addView(divider)
            return divider
        }

        private fun menuItem(
            context: Context,
            label: String,
            textColor: Int,
            rippleColor: Int,
            @Suppress("UNUSED_PARAMETER") dark: Boolean,
        ): TextView {
            val item = TextView(context)
            item.text = label
            item.setTextSize(14f)
            item.setTextColor(textColor)
            item.gravity = Gravity.CENTER
            item.minWidth = ThemeUtils.dp(context, 56f)
            item.minHeight = ThemeUtils.dp(context, 44f)
            item.setPadding(ThemeUtils.dp(context, 14f), 0, ThemeUtils.dp(context, 14f), 0)
            item.isClickable = true
            item.isFocusable = true
            val mask = GradientDrawable()
            mask.setColor(Color.WHITE)
            mask.cornerRadius = ThemeUtils.dp(context, 12f).toFloat()
            item.foreground = RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
            return item
        }

        private fun doSelectAll() {
            val text: CharSequence? = tv.text
            if (text == null || text.length == 0) {
                return
            }
            selStart = 0
            selEnd = text.length
            updateHighlight()
            updateHandlePositions()
            updateMenuItemsState()
            val bubble = menuBubble
            if (bubble != null && bubble.visibility == View.VISIBLE) {
                positionMenuBubble(true)
            }
        }

        private fun doCopy() {
            val text: CharSequence? = tv.text
            val context = tv.context
            if (text == null || selStart < 0 || selEnd <= selStart || selEnd > text.length) {
                cancel()
                return
            }
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                if (cm != null) {
                    cm.setPrimaryClip(
                        ClipData.newPlainText(
                            "BetterHeybox",
                            copyText(text.subSequence(selStart, selEnd)),
                        ),
                    )
                }
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "自绘制选择复制失败: " + t)
            }
            cancel()
        }

        private fun doShare() {
            val text: CharSequence? = tv.text
            val context = tv.context
            if (text == null || selStart < 0 || selEnd <= selStart || selEnd > text.length) {
                return
            }
            try {
                val send = Intent(Intent.ACTION_SEND)
                send.type = "text/plain"
                send.putExtra(
                    Intent.EXTRA_TEXT,
                    copyText(text.subSequence(selStart, selEnd)).toString(),
                )
                val chooser = Intent.createChooser(send, null)
                val activity = ViewUtils.findActivity(context)
                if (activity != null) {
                    activity.startActivity(chooser)
                } else {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "自绘制选择分享失败: " + t)
                Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
            }
        }

        fun cancel() {
            tv.parent?.requestDisallowInterceptTouchEvent(false)
            selecting = false
            draggingHandle = 0
            anchor = -1
            selStart = -1
            selEnd = -1
            try {
                tv.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            } catch (ignored: Throwable) {
            }
            removeOverlay()
            removeHandles()
            removeMenu()
        }

        private fun wordBoundary(offset: Int): IntArray {
            val text: CharSequence? = tv.text
            if (text == null || text.length == 0) {
                return intArrayOf(0, 0)
            }
            val len = text.length
            var o = offset
            if (o < 0) {
                o = 0
            }
            if (o > len) {
                o = len
            }
            val c = if (o < len) text[o] else text[len - 1]
            if (Character.isWhitespace(c)) {
                var probe = o
                while (probe > 0 && Character.isWhitespace(text[probe - 1])) {
                    probe--
                }
                if (probe > 0) {
                    return wordBoundary(probe - 1)
                }
                probe = o
                while (probe < len && Character.isWhitespace(text[probe])) {
                    probe++
                }
                if (probe < len) {
                    return wordBoundary(probe)
                }
                return intArrayOf(Math.max(0, o - 1), Math.min(len, o + 1))
            }
            val cjk = isCjkIdeograph(c)
            var s = o
            var e = o
            while (s > 0 && isWordChar(text[s - 1], cjk)) {
                s--
            }
            while (e < len && isWordChar(text[e], cjk)) {
                e++
            }
            if (s == e) {
                e = Math.min(len, o + 1)
            }
            return intArrayOf(s, e)
        }

        private companion object {

            private fun copyText(src: CharSequence?): CharSequence {
                if (src == null) {
                    return ""
                }
                val sb = StringBuilder(src.length)
                for (i in 0 until src.length) {
                    val ch = src[i]
                    if (ch == '\uFEFF' || ch == '\u200B' || ch == '\u200C' ||
                        ch == '\u200D' || ch == '\u2060'
                    ) {
                        continue
                    }
                    sb.append(ch)
                }
                return sb.toString()
            }

            private fun isWordChar(ch: Char, cjk: Boolean): Boolean {
                if (Character.isWhitespace(ch)) {
                    return false
                }
                if (cjk) {
                    return isCjkIdeograph(ch)
                }
                return Character.isLetterOrDigit(ch) || ch == '_' || ch == '\''
            }

            private fun isCjkIdeograph(ch: Char): Boolean {
                val block: Character.UnicodeBlock? = Character.UnicodeBlock.of(ch)
                return block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                    block === Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            }

            @Suppress("UNCHECKED_CAST")
            private fun <T> readListener(view: View, fieldName: String): T? {
                return try {
                    val field = View::class.java.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.get(view) as T?
                } catch (ignored: Throwable) {
                    null
                }
            }
        }
    }

    private class HighlightOverlay(context: Context, tv: TextView, accentColor: Int) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val linePath = Path()
        private val lineRect = RectF()
        private val tvRef: WeakReference<TextView> = WeakReference(tv)
        private val radius: Float = 3 * context.resources.displayMetrics.density
        private var start = -1
        private var end = -1

        init {
            this.paint.color = 0x59000000 or (accentColor and 0x00FFFFFF)
            this.paint.style = Paint.Style.FILL
        }

        fun setSelection(start: Int, end: Int) {
            if (this.start == start && this.end == end) {
                return
            }
            this.start = start
            this.end = end
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val tv = tvRef.get()
            if (tv == null || start < 0 || end <= start) {
                return
            }
            val layout = tv.layout
            val text: CharSequence? = tv.text
            if (layout == null || text == null) {
                return
            }
            val first = layout.getLineForOffset(start)
            val lastLine = IntArray(1)
            val lastRight = endAnchor(layout, text, end, lastLine)
            val last = lastLine[0]
            path.rewind()
            for (i in first..last) {
                var left = if (i == first) layout.getPrimaryHorizontal(start) else layout.getLineLeft(i)
                var right = if (i == last) lastRight else layout.getLineRight(i)
                if (right < left) {
                    val t = left
                    left = right
                    right = t
                }
                if (right - left < radius * 2f) {
                    right = Math.min(layout.getLineRight(i), left + radius * 2f)
                }
                lineRect.set(left, layout.getLineTop(i).toFloat(), right, layout.getLineBottom(i).toFloat())
                linePath.rewind()
                linePath.addRoundRect(lineRect, radius, radius, Path.Direction.CW)
                if (path.isEmpty) {
                    path.set(linePath)
                } else {
                    path.op(linePath, Path.Op.UNION)
                }
            }
            if (path.isEmpty) {
                return
            }
            canvas.save()
            canvas.translate(
                (tv.totalPaddingLeft - tv.scrollX).toFloat(),
                (tv.totalPaddingTop - tv.scrollY).toFloat(),
            )
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }

    private class SelectionHandle(context: Context, tv: TextView, accentColor: Int) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tvRef: WeakReference<TextView> = WeakReference(tv)
        private val density: Float = context.resources.displayMetrics.density
        private val dropPath = Path()

        init {
            paint.style = Paint.Style.FILL
            paint.color = accentColor
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            paint.setShadowLayer(1.5f * density, 0f, density, 0x30000000)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildDrop(w, h)
            pivotX = w / 2f
            pivotY = 0f
        }

        private fun rebuildDrop(w: Int, h: Int) {
            if (w <= 0 || h <= 0) {
                return
            }
            val r = w * 0.30f
            val cx = w / 2f
            val tipGap = 1f * density
            val neckGap = 3f * density
            val tipY = tipGap
            val cy = tipY + neckGap + r
            dropPath.reset()
            dropPath.addCircle(cx, cy, r, Path.Direction.CW)
            val tip = Path()
            tip.moveTo(cx, tipY)
            val baseY = cy - r * 0.30f
            tip.lineTo(cx - r * 0.62f, baseY)
            tip.lineTo(cx + r * 0.62f, baseY)
            tip.close()
            dropPath.op(tip, Path.Op.UNION)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val tv = tvRef.get()
            if (tv == null || dropPath.isEmpty) {
                return
            }
            canvas.drawPath(dropPath, paint)
        }
    }
}
