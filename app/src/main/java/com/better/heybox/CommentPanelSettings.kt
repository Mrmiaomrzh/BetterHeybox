package com.better.heybox

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object CommentPanelSettings {
    fun show(activity: Activity) {
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
            })
            if (actions.isEmpty()) {
                container.addView(TextView(activity).apply { text = "尚未发现按钮。请先长按一条评论打开面板，再返回此处。" })
            }
            actions.forEachIndexed { index, action ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                val check = CheckBox(activity).apply {
                    text = action.name
                    contentDescription = "显示 ${action.name}"
                    isChecked = action.id !in hidden
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) hidden.remove(action.id) else hidden.add(action.id)
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun moveButton(label: String, target: Int): Button = Button(activity).apply {
                    text = label
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
        }
        render()
        val scroll = ScrollView(activity).apply { addView(container) }
        AlertDialog.Builder(activity).setTitle("评论长按面板按钮")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ -> CommentPanelConfig.save(actions.map { it.id }, hidden) }
            .setNeutralButton("恢复默认") { _, _ -> CommentPanelConfig.reset() }
            .setNegativeButton("取消", null)
            .show()
    }
}
