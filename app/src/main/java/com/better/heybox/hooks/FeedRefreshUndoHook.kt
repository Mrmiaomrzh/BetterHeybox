package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.App
import com.better.heybox.Checkpoint
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class FeedRefreshUndoHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        var undo = false
        var keep = false
        try {
            val vm = Class.forName(VM_CLASS, false, cl)
            val uiState = Class.forName(UI_STATE_CLASS, false, cl)
            val loadType = Class.forName(LOAD_TYPE_CLASS, false, cl)
            val listCls = MutableList::class.java

            var tips: Method? = null
            var merge: Method? = null
            for (m in vm.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) {
                    continue
                }
                val p = m.parameterTypes
                if (p.size != 4 || p[3] != java.lang.Boolean.TYPE || p[0] != uiState || p[1] != loadType) {
                    continue
                }
                if (p[2] == uiState && m.returnType == uiState) {
                    tips = pick(tips, m)
                } else if (p[2] == listCls && m.returnType == listCls) {
                    merge = pick(merge, m)
                }
            }

            if (tips != null) {
                module.hook(tips).intercept { chain ->
                    val args = chain.args
                    if (args.size == 4 && module.isEnabled(App.KEY_FEED_REFRESH_UNDO, false)) {
                        val copy = args.toTypedArray()
                        copy[3] = java.lang.Boolean.TRUE
                        return@intercept chain.proceed(copy)
                    }
                    chain.proceed()
                }
                undo = true
            }

            if (merge != null) {
                module.hook(merge).intercept { chain ->
                    val args = chain.args
                    if (args.size == 4 && module.isEnabled(App.KEY_FEED_KEEP_PREVIOUS, false)) {
                        val copy = args.toTypedArray()
                        copy[3] = java.lang.Boolean.TRUE
                        return@intercept chain.proceed(copy)
                    }
                    chain.proceed()
                }
                keep = true
            }
        } catch (t: Throwable) {
            Checkpoint.mark("首页信息流 Hook 安装失败: %s", t.toString())
            module.logd(Log.WARN, MainModule.TAG, "✘ 首页信息流 Hook 失败: $t")
        }
        Checkpoint.mark(
            "首页信息流 Hook 安装: 气泡=" + (if (undo) "ok" else "未匹配")
                    + " keep_previous=" + (if (keep) "ok" else "未匹配")
        )
        module.logd(
            Log.INFO, MainModule.TAG, "✔ 首页信息流 Hook: 撤销气泡="
                    + (if (undo) "ok" else "未匹配") + " 保留上次内容=" + (if (keep) "ok" else "未匹配")
                    + " 开关[撤销=" + module.isEnabled(App.KEY_FEED_REFRESH_UNDO, false)
                    + ", 保留=" + module.isEnabled(App.KEY_FEED_KEEP_PREVIOUS, false) + "]"
        )
    }

    companion object {

        private const val VM_CLASS =
            "com.max.feature.feeds.viewmodel.RecommendFeedsFlowViewModel"
        private const val UI_STATE_CLASS =
            "com.max.feature.feeds.uistate.RecommendFeedsFlowUiState"
        private const val LOAD_TYPE_CLASS =
            "com.max.common.common.datasource.FeedsLoadType"

        private fun pick(current: Method?, candidate: Method): Method =
            current ?: candidate
    }
}
