package com.better.heybox.liquidglass

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.ThemeUtils
import com.better.heybox.hooks.VideoDownloadHook

object GlassSettingsSheet {

    private const val SHEET_TAG = "betterheybox_glass_sheet"

    private val DARK_PRESETS = intArrayOf(
        0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFF2C2C2E.toInt(), 0xFF10141A.toInt()
    )
    private val LIGHT_PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF7F5F0.toInt(), 0xFFEDEDED.toInt(), 0xFFE8EEF4.toInt()
    )

    @JvmStatic
    fun show(activity: Activity) {
        try {
            val existing = activity.window.decorView.findViewWithTag<View>(SHEET_TAG)
            if (existing != null) {
                return
            }
            VideoDownloadHook.setGlassSettingsVisible(true)
            HeyboxPrefs.init(activity)
            GlassConfig.load(activity)

            val density = activity.resources.displayMetrics.density
            val pageBg = ThemeUtils.surfaceVariantColor(activity)
            val cardBg = ThemeUtils.surfaceColor(activity)
            val textPrimary = ThemeUtils.textPrimaryColor(activity)
            val textSecondary = ThemeUtils.textSecondaryColor(activity)
            val accent = ThemeUtils.resolveAccent(activity)
            val divider = ThemeUtils.outlineColor(activity)

            val overlay = FrameLayout(activity)
            overlay.tag = SHEET_TAG
            overlay.isClickable = true
            overlay.setBackgroundColor(0x33000000)
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(150).start()

            val panel = LinearLayout(activity)
            panel.orientation = LinearLayout.VERTICAL
            val panelBg = GradientDrawable()
            panelBg.setColor(pageBg)
            val r = 24f * density
            panelBg.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            panel.background = panelBg
            panel.elevation = 0f
            panel.setPadding(0, (14f * density).toInt(), 0, (12f * density).toInt())

            val title = TextView(activity)
            title.text = "液态玻璃底栏"
            title.setTextColor(textPrimary)
            title.textSize = 17f
            title.setTypeface(null, Typeface.BOLD)
            title.gravity = Gravity.CENTER
            panel.addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (40f * density).toInt()
                )
            )

            val scroller = object : ScrollView(activity) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val screenH = activity.resources.displayMetrics.heightPixels
                    val reserve = Math.min(240f * density, screenH * 0.42f).toInt()
                    super.onMeasure(
                        widthSpec,
                        View.MeasureSpec.makeMeasureSpec(
                            Math.max(screenH - reserve, (200f * density).toInt()),
                            View.MeasureSpec.AT_MOST
                        )
                    )
                }
            }
            scroller.isVerticalScrollBarEnabled = false
            scroller.overScrollMode = View.OVER_SCROLL_NEVER
            val content = LinearLayout(activity)
            content.orientation = LinearLayout.VERTICAL
            content.setPadding(
                (14f * density).toInt(), 0, (14f * density).toInt(), (6f * density).toInt()
            )
            scroller.addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            content.addView(sectionLabel(activity, "开关", textSecondary, density))
            content.addView(
                buildSwitchCard(
                    activity, textPrimary, textSecondary, accent, divider, cardBg, density
                )
            )
            content.addView(sectionLabel(activity, "外观", textSecondary, density))
            content.addView(
                buildLookCard(
                    activity, textPrimary, textSecondary, accent, divider, cardBg, density
                )
            )
            content.addView(sectionLabel(activity, "布局", textSecondary, density))
            content.addView(
                buildLayoutCard(
                    activity, textPrimary, textSecondary, accent, divider, cardBg, density
                )
            )
            content.addView(buildResetCard(activity, cardBg, density))

            panel.addView(
                scroller,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val grabber = View(activity)
            val grabberBg = GradientDrawable()
            grabberBg.setColor(textSecondary)
            grabberBg.cornerRadius = 2f * density
            grabber.background = grabberBg
            val grabberLp = LinearLayout.LayoutParams(
                (36f * density).toInt(), (4f * density).toInt()
            )
            grabberLp.gravity = Gravity.CENTER_HORIZONTAL
            grabberLp.topMargin = (8f * density).toInt()
            panel.addView(grabber, grabberLp)

            val panelLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            overlay.addView(panel, panelLp)
            panel.translationY = -400f * density
            overlay.post { panel.animate().translationY(0f).setDuration(220).start() }

            overlay.setOnClickListener { dismiss(activity) }
            val decor = activity.window.decorView as ViewGroup
            decor.addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } catch (t: Throwable) {
            LiquidGlassLog.logErr("glass settings sheet failed", t)
        }
    }

    private fun buildSwitchCard(
        activity: Activity, textPrimary: Int, textSecondary: Int,
        accent: Int, divider: Int, cardBg: Int, density: Float
    ): LinearLayout {
        val switchCard = card(activity, cardBg, density)
        switchCard.addView(
            switchRow(
                activity, "启用液态玻璃", "关闭后立即恢复经典底栏",
                HeyboxPrefs.getBoolean(App.KEY_LIQUID_GLASS, true),
                textPrimary, textSecondary, accent, density
            ) { _, isChecked ->
                HeyboxPrefs.setBoolean(App.KEY_LIQUID_GLASS, isChecked)
                LiquidGlassInstaller.applyGlassEnabled(activity)
            }
        )
        switchCard.addView(divider(activity, divider, density))
        switchCard.addView(
            switchRow(
                activity, "沉浸式小白条",
                "玻璃条贴合屏幕底部，内容延伸至手势区",
                GlassConfig.immersiveGestureNavigation, textPrimary, textSecondary, accent, density
            ) { _, isChecked ->
                GlassConfig.immersiveGestureNavigation = isChecked
                GlassConfig.save(activity)
                LiquidGlassInstaller.refreshGlassWith(activity)
            }
        )
        switchCard.addView(divider(activity, divider, density))
        switchCard.addView(
            switchRow(
                activity, "自适应反色",
                "标签文字随背景亮度切换黑白",
                GlassConfig.adaptiveChrome, textPrimary, textSecondary, accent, density
            ) { _, isChecked ->
                GlassConfig.adaptiveChrome = isChecked
                GlassConfig.save(activity)
                LiquidGlassInstaller.refreshGlass()
            }
        )
        switchCard.addView(divider(activity, divider, density))
        switchCard.addView(
            switchRow(
                activity, "加长选中 Tab",
                "隐藏标签后选中项加长，底栏随可见数量收缩；建议隐藏加号后使用",
                GlassConfig.fitTabs, textPrimary, textSecondary, accent, density
            ) { _, isChecked ->
                GlassConfig.fitTabs = isChecked
                GlassConfig.save(activity)
                LiquidGlassInstaller.syncTabVisibility()
            }
        )
        return switchCard
    }

    private fun buildLookCard(
        activity: Activity, textPrimary: Int, textSecondary: Int,
        accent: Int, divider: Int, cardBg: Int, density: Float
    ): LinearLayout {
        val lookCard = card(activity, cardBg, density)
        lookCard.addView(
            tintGroup(
                activity, "暗色模式底色", DARK_PRESETS, true,
                textPrimary, textSecondary, accent, density
            )
        )
        lookCard.addView(divider(activity, divider, density))
        lookCard.addView(
            sliderRow(
                activity, "暗色模式不透明度",
                "${GlassConfig.darkAlphaPct}%", textPrimary, textSecondary, accent,
                10, 95, GlassConfig.darkAlphaPct, density
            ) { value ->
                GlassConfig.darkAlphaPct = value
                GlassConfig.save(activity)
                LiquidGlassInstaller.refreshGlass()
                "$value%"
            }
        )
        lookCard.addView(divider(activity, divider, density))
        lookCard.addView(
            tintGroup(
                activity, "亮色模式底色", LIGHT_PRESETS, false,
                textPrimary, textSecondary, accent, density
            )
        )
        lookCard.addView(divider(activity, divider, density))
        lookCard.addView(
            sliderRow(
                activity, "亮色模式不透明度",
                "${GlassConfig.lightAlphaPct}%", textPrimary, textSecondary, accent,
                10, 95, GlassConfig.lightAlphaPct, density
            ) { value ->
                GlassConfig.lightAlphaPct = value
                GlassConfig.save(activity)
                LiquidGlassInstaller.refreshGlass()
                "$value%"
            }
        )
        return lookCard
    }

    private fun buildLayoutCard(
        activity: Activity, textPrimary: Int, textSecondary: Int,
        accent: Int, divider: Int, cardBg: Int, density: Float
    ): LinearLayout {
        val layoutCard = card(activity, cardBg, density)
        layoutCard.addView(
            sliderRow(
                activity, "玻璃条高度",
                heightLabel(GlassConfig.barHeightDp), textPrimary, textSecondary, accent,
                0, 48,
                if (GlassConfig.barHeightDp == 0) 0 else GlassConfig.barHeightDp - 51,
                density
            ) { value ->
                GlassConfig.barHeightDp = if (value == 0) 0 else value + 51
                GlassConfig.save(activity)
                LiquidGlassInstaller.applyBarGeometry()
                heightLabel(GlassConfig.barHeightDp)
            }
        )
        layoutCard.addView(divider(activity, divider, density))
        layoutCard.addView(
            sliderRow(
                activity, "距屏幕底部",
                "${GlassConfig.barOffsetDp}dp", textPrimary, textSecondary, accent,
                0, 40, GlassConfig.barOffsetDp, density
            ) { value ->
                GlassConfig.barOffsetDp = value
                GlassConfig.save(activity)
                LiquidGlassInstaller.applyBarGeometry()
                "$value" + "dp"
            }
        )
        layoutCard.addView(divider(activity, divider, density))
        layoutCard.addView(
            sliderRow(
                activity, "左右边距",
                "${GlassConfig.barSideMarginDp}dp", textPrimary, textSecondary, accent,
                0, 40, GlassConfig.barSideMarginDp, density
            ) { value ->
                GlassConfig.barSideMarginDp = value
                GlassConfig.save(activity)
                LiquidGlassInstaller.applyBarGeometry()
                "$value" + "dp"
            }
        )
        layoutCard.addView(divider(activity, divider, density))
        layoutCard.addView(
            widthModeGroup(activity, textPrimary, textSecondary, accent, divider, density)
        )
        layoutCard.addView(divider(activity, divider, density))
        layoutCard.addView(
            sliderRow(
                activity, "Tab 宽度",
                "${GlassConfig.tabWidthPct}%", textPrimary, textSecondary, accent,
                50, 150, GlassConfig.tabWidthPct, density
            ) { value ->
                GlassConfig.tabWidthPct = value
                GlassConfig.save(activity)
                LiquidGlassInstaller.applyBarGeometry()
                "$value%"
            }
        )
        layoutCard.addView(divider(activity, divider, density))
        layoutCard.addView(
            barLayoutGroup(activity, textPrimary, textSecondary, accent, divider, density)
        )
        return layoutCard
    }

    private fun widthModeGroup(
        activity: Activity, textPrimary: Int,
        textSecondary: Int, accent: Int, hairline: Int,
        density: Float
    ): View {
        val group = LinearLayout(activity)
        group.orientation = LinearLayout.VERTICAL
        group.setPadding(0, (10f * density).toInt(), 0, (6f * density).toInt())
        val title = TextView(activity)
        title.text = "玻璃条宽度与留白"
        title.setTextColor(textPrimary)
        title.textSize = 15f
        group.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val slider = sliderRow(
            activity, "自定义宽度",
            "${GlassConfig.barWidthPct}%", textPrimary, textSecondary, accent,
            40, 100, GlassConfig.barWidthPct, density
        ) { value ->
            GlassConfig.barWidthPct = value
            GlassConfig.save(activity)
            LiquidGlassInstaller.applyBarGeometry()
            "$value%"
        }
        val chips = LinearLayout(activity)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(0, (10f * density).toInt(), 0, 0)
        val names = arrayOf("自适应", "占满", "自定义")
        val sync = Runnable {
            val custom = GlassConfig.barWidthMode == 2
            slider.visibility = if (custom) View.VISIBLE else View.GONE
            markChips(
                chips, WIDTH_MODE_VALUES, GlassConfig.barWidthMode,
                accent, hairline, density
            )
        }
        for (i in names.indices) {
            val index = i
            val chip = chip(activity, names[i], textPrimary, density)
            chip.setOnClickListener {
                GlassConfig.barWidthMode = WIDTH_MODE_VALUES[index]
                GlassConfig.save(activity)
                LiquidGlassInstaller.applyBarGeometry()
                sync.run()
            }
            chips.addView(chip, chipParams(density))
        }
        group.addView(
            chips,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val hint = TextView(activity)
        hint.text = "自适应：按标签内容取宽并居中；占满：撑满可用宽度；" +
                "自定义：可用下方滑杆。三者都保留「左右边距」"
        hint.setTextColor(textSecondary)
        hint.textSize = 12f
        hint.setPadding(0, (6f * density).toInt(), 0, 0)
        group.addView(
            hint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        group.addView(
            slider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sync.run()
        return group
    }

    private fun barLayoutGroup(
        activity: Activity, textPrimary: Int,
        textSecondary: Int, accent: Int, hairline: Int,
        density: Float
    ): View {
        val group = LinearLayout(activity)
        group.orientation = LinearLayout.VERTICAL
        group.setPadding(0, (10f * density).toInt(), 0, (6f * density).toInt())
        val title = TextView(activity)
        title.text = "底栏形态"
        title.setTextColor(textPrimary)
        title.textSize = 15f
        group.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val chips = LinearLayout(activity)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(0, (10f * density).toInt(), 0, 0)
        val names = arrayOf("经典居中", "右侧圆钮", "自动")
        for (i in names.indices) {
            val index = i
            val chip = chip(activity, names[i], textPrimary, density)
            chip.setOnClickListener {
                GlassConfig.barLayoutMode = BAR_LAYOUT_VALUES[index]
                GlassConfig.save(activity)
                LiquidGlassInstaller.applyBarGeometry()
                markChips(
                    chips, BAR_LAYOUT_VALUES, GlassConfig.barLayoutMode,
                    accent, hairline, density
                )
            }
            chips.addView(chip, chipParams(density))
        }
        group.addView(
            chips,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val hint = TextView(activity)
        hint.text = "自动：tab 为奇数时自动切到右侧圆钮形态"
        hint.setTextColor(textSecondary)
        hint.textSize = 12f
        hint.setPadding(0, (6f * density).toInt(), 0, 0)
        group.addView(
            hint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        markChips(
            chips, BAR_LAYOUT_VALUES, GlassConfig.barLayoutMode,
            accent, hairline, density
        )
        return group
    }

    private fun chip(activity: Activity, text: String, textPrimary: Int, density: Float): TextView {
        val chip = TextView(activity)
        chip.text = text
        chip.textSize = 13f
        chip.setTextColor(textPrimary)
        chip.setPadding(
            (12f * density).toInt(), (6f * density).toInt(),
            (12f * density).toInt(), (6f * density).toInt()
        )
        return chip
    }

    private fun chipParams(density: Float): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = (8f * density).toInt()
        return lp
    }

    private fun markChips(
        chips: LinearLayout, values: IntArray, current: Int,
        accent: Int, hairline: Int, density: Float
    ) {
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i)
            val gd = GradientDrawable()
            gd.cornerRadius = 14f * density
            val selected = i < values.size && values[i] == current
            gd.setColor(
                if (selected) (0x14000000 or (accent and 0x00FFFFFF))
                else Color.TRANSPARENT
            )
            gd.setStroke(
                Math.max(1, (1f * density).toInt()),
                if (selected) accent else hairline
            )
            c.background = gd
        }
    }

    private fun buildResetCard(activity: Activity, cardBg: Int, density: Float): LinearLayout {
        val resetCard = card(activity, cardBg, density)
        val reset = TextView(activity)
        reset.text = "恢复默认"
        reset.setTextColor(0xFFE53935.toInt())
        reset.textSize = 15f
        reset.gravity = Gravity.CENTER
        reset.setOnClickListener {
            GlassConfig.resetDefaults()
            GlassConfig.save(activity)
            LiquidGlassInstaller.applyBarGeometry()
            LiquidGlassInstaller.refreshGlass()
            Toast.makeText(activity, "已恢复默认", Toast.LENGTH_SHORT).show()
            dismiss(activity)
            show(activity)
        }
        resetCard.addView(
            reset,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (44f * density).toInt()
            )
        )
        return resetCard
    }

    @JvmStatic
    fun dismiss(activity: Activity) {
        VideoDownloadHook.setGlassSettingsVisible(false)
        try {
            val overlay = activity.window.decorView.findViewWithTag<View>(SHEET_TAG)
            val parent = overlay?.parent
            if (overlay != null && parent is ViewGroup) {
                overlay.animate().alpha(0f).setDuration(150).withEndAction {
                    try {
                        parent.removeView(overlay)
                    } catch (ignored: Throwable) {
                    }
                }.start()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun heightLabel(dp: Int): String = if (dp == 0) "自动" else "$dp" + "dp"

    private fun sectionLabel(
        activity: Activity, text: String, color: Int, density: Float
    ): TextView {
        val label = TextView(activity)
        label.text = text
        label.setTextColor(color)
        label.textSize = 13f
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(
            (8f * density).toInt(), (14f * density).toInt(), 0, (2f * density).toInt()
        )
        label.layoutParams = lp
        return label
    }

    private fun card(activity: Activity, cardBg: Int, density: Float): LinearLayout {
        val card = LinearLayout(activity)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(cardBg)
        bg.cornerRadius = 22f * density
        card.background = bg
        card.setPadding(
            (16f * density).toInt(), (4f * density).toInt(),
            (16f * density).toInt(), (4f * density).toInt()
        )
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (8f * density).toInt()
        card.layoutParams = lp
        return card
    }

    private fun divider(activity: Activity, color: Int, density: Float): View {
        val d = View(activity)
        d.setBackgroundColor(color)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (0.5f * density).toInt())
        )
        lp.setMargins((16f * density).toInt(), 0, (16f * density).toInt(), 0)
        d.layoutParams = lp
        return d
    }

    private fun switchRow(
        activity: Activity, titleText: String, desc: String?,
        checked: Boolean, textPrimary: Int, textSecondary: Int,
        accent: Int, density: Float,
        listener: CompoundButton.OnCheckedChangeListener
    ): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, (12f * density).toInt(), 0, (12f * density).toInt())
        val textCol = LinearLayout(activity)
        textCol.orientation = LinearLayout.VERTICAL
        val title = TextView(activity)
        title.text = titleText
        title.setTextColor(textPrimary)
        title.textSize = 15f
        textCol.addView(title)
        if (desc != null) {
            val descView = TextView(activity)
            descView.text = desc
            descView.setTextColor(textSecondary)
            descView.textSize = 12f
            textCol.addView(descView)
        }
        row.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sw = Switch(activity)
        sw.isChecked = checked
        applyMiuiSwitchStyle(sw, accent, density)
        sw.setOnCheckedChangeListener(listener)
        row.addView(
            sw,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return row
    }

    private fun applyMiuiSwitchStyle(sw: Switch, accent: Int, density: Float) {
        val trackOn = (accent and 0x00FFFFFF) or 0xCC000000.toInt()

        val onTrack = GradientDrawable()
        onTrack.shape = GradientDrawable.RECTANGLE
        onTrack.cornerRadius = 14f * density
        onTrack.setColor(trackOn)
        onTrack.setSize((50f * density).toInt(), (28f * density).toInt())

        val offTrack = GradientDrawable()
        offTrack.shape = GradientDrawable.RECTANGLE
        offTrack.cornerRadius = 14f * density
        offTrack.setColor(0x1F000000)
        offTrack.setSize((50f * density).toInt(), (28f * density).toInt())

        val track = StateListDrawable()
        track.addState(intArrayOf(android.R.attr.state_checked), onTrack)
        track.addState(intArrayOf(), offTrack)

        val onThumb = GradientDrawable()
        onThumb.shape = GradientDrawable.OVAL
        onThumb.setColor(0xFFFFFFFF.toInt())
        onThumb.setSize((20f * density).toInt(), (20f * density).toInt())

        val offThumb = GradientDrawable()
        offThumb.shape = GradientDrawable.OVAL
        offThumb.setColor(0xFFFFFFFF.toInt())
        offThumb.setSize((20f * density).toInt(), (20f * density).toInt())

        val shadow = GradientDrawable()
        shadow.shape = GradientDrawable.OVAL
        shadow.setColor(0x30000000)
        shadow.setSize((22f * density).toInt(), (22f * density).toInt())

        val pad = (3f * density).toInt()
        val thumb = StateListDrawable()
        thumb.addState(
            intArrayOf(android.R.attr.state_checked),
            InsetDrawable(thumbLayer(shadow, onThumb, density), pad, pad, pad, pad)
        )
        thumb.addState(
            intArrayOf(),
            InsetDrawable(thumbLayer(shadow, offThumb, density), pad, pad, pad, pad)
        )

        sw.trackDrawable = track
        sw.thumbDrawable = thumb
        sw.setShowText(false)
    }

    private fun thumbLayer(
        shadow: GradientDrawable, circle: GradientDrawable, density: Float
    ): LayerDrawable {
        val gap = (1f * density).toInt()
        return LayerDrawable(
            arrayOf<Drawable>(shadow, InsetDrawable(circle, gap, gap, gap, gap))
        )
    }

    private fun interface OnSlide {
        fun onSlide(value: Int): String
    }

    private fun sliderRow(
        activity: Activity, titleText: String, valueText: String,
        textPrimary: Int, textSecondary: Int, accent: Int,
        min: Int, max: Int, current: Int, density: Float,
        onSlide: OnSlide
    ): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, (10f * density).toInt(), 0, (6f * density).toInt())
        val head = LinearLayout(activity)
        head.orientation = LinearLayout.HORIZONTAL
        val title = TextView(activity)
        title.text = titleText
        title.setTextColor(textPrimary)
        title.textSize = 15f
        head.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val value = TextView(activity)
        value.text = valueText
        value.setTextColor(textSecondary)
        value.textSize = 13f
        head.addView(
            value,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        row.addView(
            head,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val seek = GlassSlider(activity, accent, density, min, max)
        seek.setProgress(Math.max(0, Math.min(max, current) - min))
        seek.setOnProgressChangedListener { _, progress, fromUser ->
            if (fromUser) {
                value.text = onSlide.onSlide(progress + min)
            }
        }
        row.addView(
            seek,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (48f * density).toInt()
            )
        )
        return row
    }

    private class GlassSlider(
        context: Context, accent: Int, private val density: Float,
        private val min: Int, max: Int
    ) : View(context) {

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val max: Int = Math.max(min + 1, max)
        private var progress: Int = min
        private var thumbScale = 1f
        private var dragging = false
        private var listener: OnProgressChangedListener? = null

        init {
            val dark = ThemeUtils.isDarkMode(context)
            trackPaint.setColor(if (dark) 0x3DFFFFFF else 0x24000000)
            progressPaint.setColor(accent)
            thumbOuterPaint.setColor(accent)
            thumbInnerPaint.setColor(ThemeUtils.surfaceColor(context))
            isFocusable = true
            isClickable = true
            contentDescription = "可调节值"
        }

        fun interface OnProgressChangedListener {
            fun onProgressChanged(slider: GlassSlider, progress: Int, fromUser: Boolean)
        }

        fun setOnProgressChangedListener(listener: OnProgressChangedListener?) {
            this.listener = listener
        }

        fun setProgress(value: Int) {
            progress = Math.max(min, Math.min(max, value))
            invalidate()
        }

        fun getProgress(): Int = progress

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = paddingLeft + 10f * density
            val right = width - paddingRight - 10f * density
            val centerY = height / 2f
            val trackRadius = 2.5f * density
            val outerRadius = 9f * density * thumbScale
            val innerRadius = 5.5f * density * thumbScale
            val fraction = (progress - min) / (max - min).toFloat()
            val thumbX = left + (right - left) * fraction

            canvas.drawRoundRect(
                left, centerY - trackRadius, right,
                centerY + trackRadius, trackRadius, trackRadius, trackPaint
            )
            val progressEnd = Math.max(left, thumbX)
            if (progressEnd > left) {
                canvas.drawRoundRect(
                    left, centerY - trackRadius, progressEnd,
                    centerY + trackRadius, trackRadius, trackRadius, progressPaint
                )
            }
            canvas.drawCircle(thumbX, centerY, outerRadius, thumbOuterPaint)
            canvas.drawCircle(thumbX, centerY, innerRadius, thumbInnerPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    animateThumb(1.08f)
                    updateFromTouch(event.x, true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        updateFromTouch(event.x, true)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        updateFromTouch(event.x, true)
                    }
                    dragging = false
                    animateThumb(1f)
                    performClick()
                    return true
                }

                else -> return true
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun updateFromTouch(x: Float, fromUser: Boolean) {
            val left = paddingLeft + 10f * density
            val right = width - paddingRight - 10f * density
            val fraction = (x - left) / Math.max(1f, right - left)
            val next = min + Math.round(Math.max(0f, Math.min(1f, fraction)) * (max - min))
            if (next != progress) {
                progress = next
                invalidate()
                listener?.onProgressChanged(this, progress, fromUser)
                if (Build.VERSION.SDK_INT >= 30) {
                    stateDescription = progress.toString()
                }
            }
        }

        private fun animateThumb(target: Float) {
            thumbScale = target
            invalidate()
        }
    }

    private fun tintGroup(
        activity: Activity, titleText: String, presets: IntArray,
        dark: Boolean, textPrimary: Int, textSecondary: Int,
        accent: Int, density: Float
    ): View {
        val group = LinearLayout(activity)
        group.orientation = LinearLayout.VERTICAL
        group.setPadding(0, (10f * density).toInt(), 0, (6f * density).toInt())
        val title = TextView(activity)
        title.text = titleText
        title.setTextColor(textPrimary)
        title.textSize = 15f
        group.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, (10f * density).toInt(), 0, (2f * density).toInt())
        val current = if (dark) GlassConfig.darkColor else GlassConfig.lightColor
        for (i in presets.indices) {
            val index = i
            val swatch = View(activity)
            val selected = sameColor(presets[i], current)
            swatch.background = swatchDrawable(presets[i], selected, accent, density)
            val lp = LinearLayout.LayoutParams(
                (30f * density).toInt(), (30f * density).toInt()
            )
            lp.rightMargin = (16f * density).toInt()
            swatch.setOnClickListener {
                if (dark) {
                    GlassConfig.darkColor = presets[index]
                } else {
                    GlassConfig.lightColor = presets[index]
                }
                GlassConfig.save(activity)
                LiquidGlassInstaller.refreshGlass()
                markSelection(row, presets, dark, accent, density)
            }
            row.addView(swatch, lp)
        }
        group.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return group
    }

    private fun markSelection(
        row: LinearLayout, presets: IntArray,
        dark: Boolean, accent: Int, density: Float
    ) {
        val now = if (dark) GlassConfig.darkColor else GlassConfig.lightColor
        var i = 0
        while (i < row.childCount && i < presets.size) {
            row.getChildAt(i).background = swatchDrawable(
                presets[i], sameColor(presets[i], now), accent, density
            )
            i++
        }
    }

    private fun sameColor(a: Int, b: Int): Boolean = (a and 0xFFFFFF) == (b and 0xFFFFFF)

    private fun swatchDrawable(
        color: Int, selected: Boolean, accent: Int, density: Float
    ): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        if (selected) {
            d.setStroke((2.5f * density).toInt(), accent)
        } else {
            d.setStroke((1f * density).toInt(), 0x33000000)
        }
        return d
    }

    private val WIDTH_MODE_VALUES = intArrayOf(0, 1, 2)

    private val BAR_LAYOUT_VALUES = intArrayOf(1, 2, 0)
}
