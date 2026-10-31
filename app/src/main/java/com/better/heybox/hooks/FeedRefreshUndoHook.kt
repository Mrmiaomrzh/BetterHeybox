package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.App
import com.better.heybox.Checkpoint
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

class FeedRefreshUndoHook(private val module: MainModule) {

    private data class RefreshSnapshot(val state: Any, val hasNewItems: Boolean)

    fun install(cl: ClassLoader) {
        var undo = false
        var keep = false
        try {
            val vm = Class.forName(VM_CLASS, false, cl)
            val uiState = Class.forName(UI_STATE_CLASS, false, cl)
            val loadType = Class.forName(LOAD_TYPE_CLASS, false, cl)
            val listCls = List::class.java
            val refresh = requireNotNull(loadType.enumConstants).first { (it as Enum<*>).name == "REFRESH" }
            val getItems = uiState.getMethod("getFeedsFlowList")
            val copyState = uiState.declaredMethods.singleOrNull { method ->
                method.name == "copy\$default" && Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 21 && method.parameterTypes[0] == uiState &&
                    method.parameterTypes[8] == listCls && method.returnType == uiState
            }?.apply { isAccessible = true }
            val snapshots = WeakHashMap<Any, RefreshSnapshot>()

            val methods = vm.declaredMethods.filter { method ->
                val p = method.parameterTypes
                !Modifier.isStatic(method.modifiers) && p.size == 4 &&
                    p[0] == uiState && p[1] == loadType && p[3] == java.lang.Boolean.TYPE
            }
            val tips = methods.singleOrNull { it.parameterTypes[2] == uiState && it.returnType == uiState }
            val merge = methods.singleOrNull { it.parameterTypes[2] == listCls && it.returnType == listCls }
            if (merge != null) {
                module.hook(merge).intercept { chain ->
                    val args = chain.args
                    val self = chain.instanceOrNull
                    var replacement: Any? = null
                    if (args.size == 4 && args[1] == refresh && self != null) {
                        try {
                            val state = args[0]
                            val oldItems = getItems.invoke(state) as? List<*>
                            val newItems = args[2] as? List<*>
                            if (state != null && oldItems != null && newItems != null) {
                                synchronized(snapshots) {
                                    snapshots[self] = RefreshSnapshot(state, newItems.isNotEmpty())
                                }
                                if (module.isEnabled(App.KEY_FEED_KEEP_PREVIOUS, false) &&
                                    copyState != null && oldItems.isNotEmpty() && newItems.isNotEmpty()
                                ) {
                                    replacement = copyWithPreviousItems(copyState, state, oldItems)
                                }
                            }
                        } catch (t: Throwable) {
                            module.logd(Log.WARN, MainModule.TAG, "首页刷新快照失败: $t")
                        }
                    }
                    if (replacement != null) {
                        val copy = args.toTypedArray()
                        copy[0] = replacement
                        copy[3] = true
                        chain.proceed(copy)
                    } else {
                        chain.proceed()
                    }
                }
                keep = copyState != null
            }

            if (tips != null && merge != null) {
                module.hook(tips).intercept { chain ->
                    val args = chain.args
                    val self = chain.instanceOrNull
                    val snapshot = if (self != null && args.size == 4 && args[1] == refresh) {
                        synchronized(snapshots) { snapshots.remove(self) }
                    } else null
                    var oldState: Any? = null
                    if (snapshot != null && snapshot.hasNewItems &&
                        module.isEnabled(App.KEY_FEED_REFRESH_UNDO, false)
                    ) {
                        try {
                            if (!(getItems.invoke(snapshot.state) as List<*>).isEmpty()) {
                                oldState = snapshot.state
                            }
                        } catch (t: Throwable) {
                            module.logd(Log.WARN, MainModule.TAG, "首页撤销快照读取失败: $t")
                        }
                    }
                    if (oldState != null) {
                        val copy = args.toTypedArray()
                        copy[2] = oldState
                        copy[3] = true
                        chain.proceed(copy)
                    } else {
                        chain.proceed()
                    }
                }
                undo = true
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
        private const val VM_CLASS = "com.max.feature.feeds.viewmodel.RecommendFeedsFlowViewModel"
        private const val UI_STATE_CLASS = "com.max.feature.feeds.uistate.RecommendFeedsFlowUiState"
        private const val LOAD_TYPE_CLASS = "com.max.common.common.datasource.FeedsLoadType"

        private fun copyWithPreviousItems(copy: Method, state: Any, items: List<*>): Any {
            val args = arrayOfNulls<Any>(21)
            args[0] = state
            for (index in 1..18) {
                args[index] = when (copy.parameterTypes[index]) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
            args[8] = ArrayList(items)
            args[19] = 262143 xor 128
            return requireNotNull(copy.invoke(null, *args))
        }
    }
}
