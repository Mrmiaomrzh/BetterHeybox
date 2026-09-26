package com.better.heybox.hooks

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import com.better.heybox.App
import com.better.heybox.MainModule
import com.better.heybox.ViewUtils
import com.better.heybox.liquidglass.LiquidGlassInstaller
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

class BottomTabHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        hookBottomTabs(cl)
    }

    private fun hookBottomTabs(cl: ClassLoader) {
        try {
            val clazz = Class.forName("com.max.xiaoheihe.MainActivity", false, cl)
            val onCreate = clazz.getDeclaredMethod("onCreate", Bundle::class.java)
            module.hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                try {
                    applyBottomTabSettings(chain.instanceOrNull)
                } catch (t: Throwable) {
                    module.logd(Log.ERROR, MainModule.TAG, "应用底部导航栏设置异常", t)
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 底部导航栏 Hook 已安装")

            try {
                val onResume = clazz.getDeclaredMethod("onResume")
                module.hook(onResume).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        applyBottomTabSettings(chain.instanceOrNull)
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "onResume 应用底栏设置失败: $t")
                    }
                    result
                }
                module.logd(Log.INFO, MainModule.TAG, "✔ 底栏 onResume Hook 已安装")
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "底栏 onResume Hook 失败: $t")
            }

            try {
                val observerCls = Class.forName("com.max.xiaoheihe.MainActivity\$j", false, cl)
                val b = ViewUtils.findMethod(observerCls, "b", java.lang.Boolean::class.java)
                if (b != null) {
                    module.hook(b).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val mainActivity = ViewUtils.findOuter(chain.instanceOrNull, clazz)
                            if (mainActivity != null) {
                                applyBottomTabSettings(mainActivity)
                            }
                        } catch (t: Throwable) {
                            module.logd(Log.WARN, MainModule.TAG, "底栏状态回调后重新隐藏失败: $t")
                        }
                        result
                    }
                    module.logd(Log.INFO, MainModule.TAG, "✔ 底栏状态回调 Hook 已安装")
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "底栏状态回调 Hook 安装失败: $t")
            }
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 底部导航栏 Hook 失败", t)
        }
    }

    private fun applyBottomTabSettings(activityObj: Any?) = applyBottomTabSettings(activityObj, true)

    private fun applyBottomTabSettings(activityObj: Any?, reschedule: Boolean) {
        try {
            val activity = activityObj as? Activity
            val binding = findViewBinding(activityObj)
            val group = findTabGroup(activity, binding)
            if (group == null) {
                module.logd(Log.WARN, MainModule.TAG, "未找到底部导航栏 rg_main")
                return
            }
            val labelHome = cacheRuntimeLabel(group, binding, 0, "rb_1", "j")
            val labelSlot2 = cacheRuntimeLabel(group, binding, 1, "rb_2", "k")
            val labelSlot4 = cacheRuntimeLabel(group, binding, 2, "rb_4", "m")
            sAnyTabHidden = false
            if (module.isEnabled(App.KEY_HIDE_TAB_HOME, false)) {
                hideTabSlot(group, binding, "rb_1", "j", labelHome)
                sAnyTabHidden = true
            }
            if (module.isEnabled(App.KEY_HIDE_TAB_HOT, false)) {
                hideTabSlot(group, binding, "rb_2", "k", labelSlot2)
                sAnyTabHidden = true
            }
            if (module.isEnabled(App.KEY_HIDE_TAB_GAME, false)) {
                hideTabSlot(group, binding, "rb_4", "m", labelSlot4)
                sAnyTabHidden = true
            }
            val plus = findPlusButton(activity, binding)
            if (module.isEnabled(App.KEY_HIDE_ADD, false)) {
                hideView(findPlaceholder(activity, binding, group), "推荐占位")
                if (plus != null && !LiquidGlassInstaller.isGlassBarActive()) {
                    hideView(plus, "加号")
                }
            } else if (plus != null && plus.visibility == View.VISIBLE &&
                !LiquidGlassInstaller.isGlassBarActive()
            ) {
                alignPlusToPlaceholder(activity, binding, group, plus)
            }
            normalizeVisibleTabs(group)
            if (reschedule) {
                group.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    normalizeVisibleTabs(group)
                }
                retryDelayed(
                    group,
                    { applyBottomTabSettings(activityObj, false) },
                    100L, 500L, 1500L, 3000L
                )
            }
            ensureVisibleTabSelected(group)
            LiquidGlassInstaller.syncTabVisibility()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "底部导航栏设置应用失败: $t")
        }
    }

    private fun cacheRuntimeLabel(
        group: RadioGroup, binding: Any?, slot: Int, rbName: String, fallbackField: String
    ): String? {
        var v = slotView(group, rbName, fallbackField)
        if (v == null && binding != null) {
            v = bindingView(binding, fallbackField)
        }
        if (v is RadioButton) {
            val text = v.text
            if (text != null && text.isNotEmpty()) {
                sRuntimeLabels[slot] = text.toString()
            }
        }
        return sRuntimeLabels[slot]
    }

    private fun hideTabSlot(
        group: RadioGroup, binding: Any?, rbName: String, fallbackField: String, label: String?
    ) {
        var v = slotView(group, rbName, fallbackField)
        if (v == null && binding != null) {
            v = bindingView(binding, fallbackField)
        }
        if (v != null) {
            hideView(v, label ?: rbName)
            return
        }
        module.logd(Log.WARN, MainModule.TAG, "未找到 tab（$rbName / 字段 $fallbackField）")
    }

    private fun slotView(group: RadioGroup, rbName: String, fallbackField: String): View? {
        try {
            val id = group.resources.getIdentifier(rbName, "id", MainModule.TARGET_PKG)
            if (id != 0) {
                return group.findViewById<View>(id)
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun hostId(activity: Activity?, name: String): Int {
        if (activity == null) {
            return 0
        }
        return try {
            activity.resources.getIdentifier(name, "id", MainModule.TARGET_PKG)
        } catch (t: Throwable) {
            0
        }
    }

    private fun findTabGroup(activity: Activity?, binding: Any?): RadioGroup? {
        val id = hostId(activity, "rg_main")
        val v = if (id != 0) activity?.findViewById<View>(id) else null
        if (v is RadioGroup) {
            return v
        }
        return tabGroup(binding)
    }

    private fun findPlusButton(activity: Activity?, binding: Any?): View? {
        val id = hostId(activity, "vg_mid_tab")
        val v = if (id != 0) activity?.findViewById<View>(id) else null
        return v ?: bindingView(binding, "r")
    }

    private fun findPlaceholder(
        activity: Activity?, binding: Any?, group: RadioGroup
    ): View? {
        val id = hostId(activity, "rb_3")
        val v = if (id != 0) group.findViewById<View>(id) else null
        return v ?: bindingView(binding, "l")
    }

    private fun bindingView(binding: Any?, fieldName: String): View? {
        if (binding == null) return null
        return try {
            val field = binding.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val obj = field.get(binding)
            obj as? View
        } catch (t: Throwable) {
            null
        }
    }

    private fun hideView(v: View?, label: String) {
        if (v == null) {
            return
        }
        v.visibility = View.GONE
        retryDelayed(v, { v.visibility = View.GONE }, 500L, 1500L, 3000L)
        module.logd(Log.INFO, MainModule.TAG, "隐藏 $label: ${v.visibility}")
    }

    private fun alignPlusToPlaceholder(
        activity: Activity?, binding: Any?, group: RadioGroup, plus: View
    ) {
        val slot = findPlaceholder(activity, binding, group)
        plus.post {
            try {
                if (LiquidGlassInstaller.isGlassBarActive() || slot == null ||
                    slot.visibility == View.GONE || plus.visibility != View.VISIBLE ||
                    plus.width == 0 || slot.width == 0
                ) {
                    plus.translationX = 0f
                    return@post
                }
                val slotLoc = IntArray(2)
                slot.getLocationOnScreen(slotLoc)
                val plusLoc = IntArray(2)
                plus.getLocationOnScreen(plusLoc)
                val target = slotLoc[0] + slot.width / 2f
                val current = plusLoc[0] - plus.translationX + plus.width / 2f
                if (abs(target - current) > 1f) {
                    plus.translationX = target - current
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun tabGroup(binding: Any?): RadioGroup? {
        if (binding == null) return null
        return try {
            val f = binding.javaClass.getDeclaredField("o")
            f.isAccessible = true
            f.get(binding) as? RadioGroup
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun normalizeVisibleTabs(group: RadioGroup?) {
        try {
            if (group == null) return
            var visible = 0
            for (i in 0 until group.childCount) {
                if (group.getChildAt(i).visibility == View.VISIBLE) visible++
            }
            if (visible == 0) return
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val lp = child.layoutParams as? LinearLayout.LayoutParams
                if (lp != null && (lp.width != 0 || lp.weight != 1f)) {
                    lp.width = 0
                    lp.weight = 1f
                    child.layoutParams = lp
                }
            }
            group.requestLayout()
        } catch (ignored: Throwable) {
        }
    }

    private fun ensureVisibleTabSelected(group: RadioGroup?) {
        try {
            if (group == null) return
            val checkedId = group.checkedRadioButtonId
            if (checkedId != -1) {
                val checked = group.findViewById<View>(checkedId)
                if (checked != null && checked.visibility == View.VISIBLE) {
                    return
                }
            }
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is RadioButton && child.visibility == View.VISIBLE) {
                    val id = child.id
                    if (id != -1 && id != checkedId) {
                        group.check(id)
                        module.logd(
                            Log.INFO, MainModule.TAG,
                            "选中 tab 已隐藏，切换到可见 tab id=$id"
                        )
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "纠正底栏选中项失败: $t")
        }
    }

    private fun retryDelayed(view: View, action: Runnable, vararg delays: Long) {
        for (delay in delays) {
            view.postDelayed(action, delay)
        }
    }

    private fun findViewBinding(activity: Any?): Any? {
        if (activity == null) return null
        return try {
            for (f in activity.javaClass.declaredFields) {
                if (f.type.name.endsWith(".i1")) {
                    f.isAccessible = true
                    return f.get(activity)
                }
            }
            null
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "查找 ViewBinding 失败: $t")
            null
        }
    }

    private fun hideTabField(binding: Any?, fieldName: String, label: String) {
        if (binding == null) return
        try {
            val field = binding.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val obj = field.get(binding)
            if (obj is View) {
                obj.visibility = View.GONE
                retryDelayed(obj, { obj.visibility = View.GONE }, 500L, 1500L, 3000L)
                module.logd(Log.INFO, MainModule.TAG, "隐藏 $label: ${obj.visibility}")
            }
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "隐藏 tab 失败 ($label)，字段 $fieldName 可能被 Robust 重命名"
            )
        }
    }

    companion object {
        private val sRuntimeLabels = arrayOfNulls<String>(3)

        @Volatile private var sAnyTabHidden = false

        @JvmStatic
        fun runtimeTabLabel(slot: Int): String? =
            if (slot >= 0 && slot < sRuntimeLabels.size) sRuntimeLabels[slot] else null

        @JvmStatic
        fun isAnyTabHidden(): Boolean = sAnyTabHidden
    }
}
