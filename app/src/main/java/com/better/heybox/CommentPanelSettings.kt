package com.better.heybox

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.view.Gravity
import com.better.heybox.hooks.SettingsEntryHook
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object CommentPanelSettings {
    fun show(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec? = null) {
        var nativeDialog: Dialog? = null
        val primary = SettingsEntryHook.hostColor(activity, "color_text_primary_day_night", 0xFF333333.toInt())
        val accent = SettingsEntryHook.hostColor(activity, "color_text_link_day_night", 0xFF1677FF.toInt())
        val actions = CommentPanelConfig.discovered().toMutableList()
        val hidden = CommentPanelConfig.hiddenIds().toMutableSet()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun render() {
            container.removeAllViews()
            container.addView(TextView(activity).apply {
                text = "隐藏「回复」不会恢复单击评论回复；如需恢复，请关闭「回复改长按面板」。"
                if (spec != null) setTextColor(primary)
            })
            if (actions.isEmpty()) {
                container.addView(TextView(activity).apply {
                    text = "尚未发现按钮。请先长按一条评论打开面板，再返回此处。"
                    if (spec != null) setTextColor(primary)
                })
            }
            actions.forEachIndexed { index, action ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                val check = CheckBox(activity).apply {
                    text = action.name
                    if (spec != null) setTextColor(primary)
                    contentDescription = "显示 ${action.name}"
                    isChecked = action.id !in hidden
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) hidden.remove(action.id) else hidden.add(action.id)
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun moveButton(label: String, target: Int): Button = Button(activity).apply {
                    text = label
                    if (spec != null) {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setTextColor(accent)
                        minWidth = 0
                        minimumWidth = 0
                        alpha = if (target in actions.indices) 1f else 0.35f
                    }
                    contentDescription = "$label ${action.name}"
                    isEnabled = target in actions.indices
                    setOnClickListener {
                        java.util.Collections.swap(actions, index, target)
                        render()
                    }
                }
                row.addView(moveButton("上移", index - 1))
                row.addView(moveButton("下移", index + 1))
                container.addView(row)
            }
            if (spec != null) {
                container.addView(TextView(activity).apply {
                    text = "恢复默认"
                    setTextColor(accent)
                    gravity = Gravity.CENTER
                    val padding = (14 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        CommentPanelConfig.reset()
                        nativeDialog?.dismiss()
                    }
                })
            }
        }
        render()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
                val height = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, height)
            }
        }.apply { addView(container) }
        if (spec != null) {
            nativeDialog = spec.buildAndShow(
                activity, "评论长按面板按钮", scroll,
                "保存", DialogInterface.OnClickListener { dialog, _ ->
                    CommentPanelConfig.save(actions.map { it.id }, hidden)
                    dialog.dismiss()
                },
                "取消", DialogInterface.OnClickListener { dialog, _ -> dialog.dismiss() }
            )
            return
        }
        AlertDialog.Builder(activity).setTitle("评论长按面板按钮")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ -> CommentPanelConfig.save(actions.map { it.id }, hidden) }
            .setNeutralButton("恢复默认") { _, _ -> CommentPanelConfig.reset() }
            .setNegativeButton("取消", null)
            .show()
    }
}
