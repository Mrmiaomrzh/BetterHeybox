package com.better.heybox.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.DexKitResolver
import com.better.heybox.HeyboxPrefs
import com.better.heybox.HeyboxTargets
import com.better.heybox.MainModule
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class GameLibraryCleanHook(private val module: MainModule) {

    init {
        sInstance = this
    }

    fun install(cl: ClassLoader) {
        refresh()
        try {
            val ctx = App.resolveAppContext()
            if (ctx != null) {
                val info = ctx.packageManager
                    .getPackageInfo(MainModule.TARGET_PKG, 0)
                setHostVersionCode(
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        info.longVersionCode
                    } else {
                        info.versionCode.toLong()
                    }
                )
            }
        } catch (ignored: Throwable) {
        }
        HeyboxTargets.install(TARGET_GAME_REC_BIND, this::hookAdapterBind)
        HeyboxTargets.install(TARGET_GAME_REC_WRAPPER, this::hookWrapperBind)
        HeyboxTargets.install(TARGET_GAME_REC_BB, this::hookDelegateBind)
        hookTouchDispatch(cl)
        hookClickListeners(cl)
        hookLongPress(cl)
        hookLongClickable(cl)
        if (HeyboxTargets.methods(TARGET_GAME_REC_BIND).isEmpty() &&
            HeyboxTargets.methods(TARGET_GAME_REC_WRAPPER).isEmpty() &&
            HeyboxTargets.methods(TARGET_GAME_REC_BB).isEmpty() &&
            sMissingLogged.compareAndSet(false, true)
        ) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "游戏库精简：未解析到推荐列表绑定入口（等待 DexKit 兜底）"
            )
        }
    }


    private fun hookAdapterBind(method: Method) {
        sAdapterClass = method.declaringClass
        sInstalledInner = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                val adapter = chain.getThisObject()
                val list = dataListOf(adapter)
                val item = chain.getArg(1)
                handle(item, chain.getArg(0), "内层", list, indexOf(list, item))
            } catch (t: Throwable) {
                logOnce(t)
            }
            result
        }
        module.logd(
            Log.INFO, MainModule.TAG, "✔ 游戏库精简 Hook 已安装（内层） " +
                    method.declaringClass.name + "#" + method.name +
                    "/" + method.parameterCount +
                    " [来源=" + HeyboxTargets.sourceOf(TARGET_GAME_REC_BIND) + "]"
        )
    }


    private fun hookWrapperBind(method: Method) {
        sInstalledWrapper = true
        module.hook(method).intercept { chain ->
            val wrapper = chain.getThisObject()
            val inner = innerAdapterOf(wrapper)
            if (inner == null) {
                if (sScopeMissingLogged.compareAndSet(false, true)) {
                    module.logd(
                        Log.WARN, MainModule.TAG,
                        "游戏库精简：外层作用域暂未取到内层适配器（适配器可能还没创建）"
                    )
                }
                return@intercept chain.proceed()
            }
            if (!isGameRecommendAdapter(inner)) {
                return@intercept chain.proceed()
            }
            if (sScopeLogged.compareAndSet(false, true)) {
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "游戏库精简：外层作用域生效（内层=" + inner.javaClass.name + "）"
                )
            }
            val result = chain.proceed()
            try {
                val position = if (chain.getArg(1) is Int) chain.getArg(1) as Int else -1
                handle(
                    itemAt(inner, position), chain.getArg(0), "外层",
                    dataListOf(inner), position
                )
            } catch (t: Throwable) {
                logOnce(t)
            }
            result
        }
        module.logd(
            Log.INFO, MainModule.TAG, "✔ 游戏库精简 Hook 已安装（外层） " +
                    method.declaringClass.name + "#" + method.name +
                    "/" + method.parameterCount +
                    " [来源=" + HeyboxTargets.sourceOf(TARGET_GAME_REC_WRAPPER) + "]" +
                    " | 横幅=" + onOff(sHideBanner) +
                    " 小分区=" + onOff(sHideSmall) +
                    " 推荐分区=" + onOff(sHideContent) +
                    " 自定义=" + sCustomTypes.size + " 条"
        )
    }


    private fun hookDelegateBind(method: Method) {
        sInstalledDelegate = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                val item = chain.getArg(2)
                val list = dataListOf(chain.getArg(1))
                handle(item, chain.getArg(0), "预绑定", list, indexOf(list, item))
            } catch (t: Throwable) {
                logOnce(t)
            }
            result
        }
        module.logd(
            Log.INFO, MainModule.TAG, "✔ 游戏库精简 Hook 已安装（预绑定） " +
                    method.declaringClass.name + "#" + method.name +
                    "/" + method.parameterCount +
                    " [来源=" + HeyboxTargets.sourceOf(TARGET_GAME_REC_BB) + "]" +
                    " | 横幅=" + onOff(sHideBanner) +
                    " 小分区=" + onOff(sHideSmall) +
                    " 推荐分区=" + onOff(sHideContent) +
                    " 自定义=" + sCustomTypes.size + " 条"
        )
    }

    private fun handle(item: Any?, viewHolder: Any?, path: String, list: List<*>?, index: Int) {
        if (item == null) {
            return
        }
        val type = typeOf(item) ?: return
        remember(type)
        val boundView = FeedItemHider.getItemView(viewHolder)
        if (isEntryBoard(item, type)) {
            hookChildList(item)
            if (boundView != null) {
                sBoardViews.add(boundView)
                attachEntryLongPress(boundView)
                val pageRecycler = nearestRecyclerView(boundView)
                if (pageRecycler != null) {
                    attachTouchListener(pageRecycler)
                }
            }
        }
        val section = sectionNameOf(item, type, list, index)
        if ("title" == type && section != null) {
            rememberName(sObservedSections, KEY_OBSERVED_SECTIONS, section)
        }
        val itemView = FeedItemHider.getItemView(viewHolder)
        if (itemView == null) {
            return
        }
        sBound.put(itemView, arrayOf(type, section))
        apply(itemView, type, section)
        if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
            val hidden = isHiddenNow(type, section)
            val decision = path + "|" + type + "|" + hidden + "|" + section
            if (decision != sLogged.get(itemView)) {
                sLogged.put(itemView, decision)
                module.logd(
                    Log.INFO, MainModule.TAG, "游戏库精简：" + path + " type=" + type +
                            (if (section == null) "" else " 分区=" + section) +
                            " → " + (if (hidden) "隐藏" else "保留")
                )
            }
        }
    }

    private fun logOnce(t: Throwable) {
        if (sErrorLogged.compareAndSet(false, true)) {
            module.logd(Log.WARN, MainModule.TAG, "游戏库精简：条目隐藏异常: " + t)
        }
    }

    private fun notifyBoundList() {
        try {
            var target: View? = null
            for (view in ArrayList(sBound.keys)) {
                if (view != null) {
                    target = view
                    break
                }
            }
            if (target == null) {
                return
            }
            var node: View? = target
            var i = 0
            while (i < 40) {
                val current = node
                if (current == null ||
                    "androidx.recyclerview.widget.RecyclerView" == current.javaClass.name
                ) {
                    break
                }
                val parent = current.parent
                node = if (parent is View) parent else null
                i++
            }
            if (node == null ||
                "androidx.recyclerview.widget.RecyclerView" != node.javaClass.name
            ) {
                return
            }
            val recyclerView = node
            val adapter: Any = recyclerView.javaClass.getMethod("getAdapter")
                .invoke(recyclerView) ?: return
            recyclerView.post {
                try {
                    adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }


    private fun hookLongPress(cl: ClassLoader) {
        try {
            val viewClass = Class.forName("android.view.View", false, cl)
            val performLongClick = viewClass.getDeclaredMethod("performLongClick")
            module.hook(performLongClick).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (maybePromptHideEntry(chain.getThisObject())) {
                        return@intercept java.lang.Boolean.TRUE
                    }
                } catch (ignored: Throwable) {
                }
                result
            }
            sInstalledPress = true
            module.logd(Log.INFO, MainModule.TAG, "✔ 游戏库精简 Hook 已安装（长按入口卡片提示）")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "游戏库精简：长按提示 Hook 失败: " + t)
        }
    }


    private fun hookTouchDispatch(cl: ClassLoader) {
        try {
            val groupClass = Class.forName("android.view.ViewGroup", false, cl)
            val eventClass = Class.forName("android.view.MotionEvent", false, cl)
            val dispatch = groupClass.getDeclaredMethod("dispatchTouchEvent", eventClass)
            module.hook(dispatch).intercept { chain ->
                val event = chain.getArg(0)
                if (event !is MotionEvent || chain.getThisObject() !is View) {
                    return@intercept chain.proceed()
                }
                val dispatcher = chain.getThisObject() as View
                if (!isTopLevelViewGroup(dispatcher)) {
                    return@intercept chain.proceed()
                }
                val action = event.actionMasked
                if (sTouchFired) {
                    if (action == MotionEvent.ACTION_MOVE) {
                        return@intercept java.lang.Boolean.TRUE
                    }
                    if (action == MotionEvent.ACTION_UP ||
                        action == MotionEvent.ACTION_CANCEL
                    ) {
                        sTouchFired = false
                        sTouchDownAt = 0L
                        sTouchTarget = null
                        return@intercept java.lang.Boolean.TRUE
                    }
                }
                val result = chain.proceed()
                try {
                    handleDispatchTouch(dispatcher, event, action)
                } catch (ignored: Throwable) {
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 游戏库精简 Hook 已安装（长按自检·分发层）")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "游戏库精简：长按自检（分发层）Hook 失败: " + t)
        }
    }

    private fun handleDispatchTouch(dispatcher: View, event: MotionEvent, action: Int) {
        if (sBoardViews.isEmpty()) {
            return
        }
        if (action == MotionEvent.ACTION_DOWN) {
            sTouchFired = false
            if (sTouchDownAt != 0L) {
                return
            }
            val pressed = findDeepestChildAt(dispatcher, event.rawX, event.rawY)
            if (pressed == null || !insideBoardView(pressed)) {
                return
            }
            if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                module.logd(Log.INFO, MainModule.TAG, "游戏库精简：长按自检收到按下")
            }
            sTouchTarget = dispatcher
            sTouchDownAt = System.currentTimeMillis()
            sTouchDownX = event.rawX
            sTouchDownY = event.rawY
            sTouchFired = false
            dispatcher.removeCallbacks(TOUCH_LONG_PRESS)
            dispatcher.postDelayed(TOUCH_LONG_PRESS, LONG_PRESS_MS)
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (sTouchDownAt == 0L) {
                return
            }
            var slop = sTouchSlop
            if (slop <= 0f) {
                slop = Math.max(24f, dispatcher.resources.displayMetrics.density * 24f)
                sTouchSlop = slop
            }
            if (Math.hypot(
                    (event.rawX - sTouchDownX).toDouble(),
                    (event.rawY - sTouchDownY).toDouble()
                ) > slop
            ) {
                sTouchDownAt = 0L
                dispatcher.removeCallbacks(TOUCH_LONG_PRESS)
            }
        } else if (action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL
        ) {
            sTouchDownAt = 0L
            dispatcher.removeCallbacks(TOUCH_LONG_PRESS)
            if (!sTouchFired) {
                sTouchTarget = null
            }
        }
    }


    private fun hookClickListeners(cl: ClassLoader) {
        try {
            val viewClass = Class.forName("android.view.View", false, cl)
            val listenerClass = Class.forName("android.view.View\$OnClickListener", false, cl)
            val setOnClickListener = viewClass.getDeclaredMethod("setOnClickListener", listenerClass)
            module.hook(setOnClickListener).intercept { chain ->
                val result = chain.proceed()
                try {
                    val target = chain.getThisObject()
                    if (target is View && insideBoardView(target)) {
                        bindLongPressTree(target, 0)
                    }
                } catch (ignored: Throwable) {
                }
                result
            }
            sInstalledAttach = true
            module.logd(Log.INFO, MainModule.TAG, "✔ 游戏库精简 Hook 已安装（卡片长按挂载）")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "游戏库精简：卡片长按挂载 Hook 失败: " + t)
        }
    }


    private fun hookLongClickable(cl: ClassLoader) {
        try {
            val viewClass = Class.forName("android.view.View", false, cl)
            val isLongClickable = viewClass.getDeclaredMethod("isLongClickable")
            module.hook(isLongClickable).intercept { chain ->
                val result = chain.proceed()
                if (java.lang.Boolean.TRUE == result) {
                    return@intercept result
                }
                if (insideBoardView(chain.getThisObject())) java.lang.Boolean.TRUE else result
            }
            sInstalledClickable = true
            module.logd(Log.INFO, MainModule.TAG, "✔ 游戏库精简 Hook 已安装（长按可达）")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "游戏库精简：长按可达 Hook 失败: " + t)
        }
    }

    private fun maybePromptHideEntry(viewObject: Any?): Boolean {
        if (viewObject !is View || sBound.isEmpty()) {
            return false
        }
        if (!sharesRootWithBoundView(viewObject)) {
            return false
        }
        return promptEntryHide(viewObject)
    }

    private fun promptEntryHide(view: View): Boolean {
        val candidates = entryCandidates()
        if (candidates.isEmpty()) {
            sLastPress = nowText() + " 无候选卡片名（该分区还没读到子项列表）"
            if (sEmptyCandidateLogged.compareAndSet(false, true)) {
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "游戏库精简：长按但还没有候选卡片名（该分区还没读到子项列表）"
                )
            }
            return false
        }
        var label = findEntryLabel(view, candidates)
        if (label == null) {
            label = fallbackLabel(view)
        }
        if (label == null) {
            logPressMiss(view, candidates.size)
            return false
        }
        return showHideEntryPrompt(view, label)
    }

    private fun attachEntryLongPress(boardItemView: View) {
        val recyclerViews = ArrayList<View>()
        collectRecyclerViews(boardItemView, recyclerViews, 0)
        for (recyclerView in recyclerViews) {
            attachToRecycler(recyclerView)
        }
        if (sLongPressRescan.add(boardItemView)) {
            boardItemView.postDelayed({
                try {
                    val again = ArrayList<View>()
                    collectRecyclerViews(boardItemView, again, 0)
                    for (recyclerView in again) {
                        attachToRecycler(recyclerView)
                    }
                } catch (ignored: Throwable) {
                }
                sLongPressRescan.remove(boardItemView)
            }, 400L)
        }
    }

    private fun attachToRecycler(recyclerView: View) {
        attachTouchListener(recyclerView)
        if (!sLongPressAttached.add(recyclerView)) {
            return
        }
        if (recyclerView is ViewGroup) {
            for (i in 0 until recyclerView.childCount) {
                bindLongPressTree(recyclerView.getChildAt(i), 0)
            }
        }
        try {
            val loader = recyclerView.javaClass.classLoader
            val rvClass = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader)
            val listenerClass = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$OnChildAttachStateChangeListener",
                false, loader
            )
            val listener = Proxy.newProxyInstance(
                loader, arrayOf<Class<*>>(listenerClass),
                InvocationHandler { _, _, args ->
                    if (args != null && args.size > 0 && args[0] is View) {
                        try {
                            bindLongPressTree(args[0] as View, 0)
                        } catch (ignored: Throwable) {
                        }
                    }
                    null
                }
            )
            rvClass.getMethod("addOnChildAttachStateChangeListener", listenerClass)
                .invoke(recyclerView, listener)
        } catch (ignored: Throwable) {
        }
    }

    private fun attachTouchListener(recyclerView: View) {
        if (!sTouchAttached.add(recyclerView)) {
            return
        }
        try {
            val loader = recyclerView.javaClass.classLoader
            val rvClass = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader)
            val listenerClass = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$OnItemTouchListener", false, loader
            )
            val listener = Proxy.newProxyInstance(
                loader,
                arrayOf<Class<*>>(listenerClass),
                InvocationHandler { _, method, args ->
                    try {
                        onRecyclerTouch(recyclerView, method.name, args)
                    } catch (t: Throwable) {
                        java.lang.Boolean.FALSE
                    }
                }
            )
            rvClass.getMethod("addOnItemTouchListener", listenerClass).invoke(recyclerView, listener)
            module.logd(
                Log.INFO, MainModule.TAG, "✔ 游戏库精简 Hook 已安装（长按自检） " +
                        recyclerView.javaClass.name
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "游戏库精简：长按自检挂载失败: " + t)
        }
    }

    private fun onRecyclerTouch(
        recyclerView: View,
        methodName: String,
        args: Array<out Any?>?
    ): Any? {
        if ("onInterceptTouchEvent" != methodName && "onTouchEvent" != methodName) {
            return null
        }
        val raw = if (args != null && args.size > 1) args[1] else null
        if (raw !is MotionEvent) {
            return java.lang.Boolean.FALSE
        }
        val action = raw.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            val instance = sInstance
            if (instance != null && instance.module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                instance.module.logd(
                    Log.INFO, MainModule.TAG,
                    "游戏库精简：长按自检收到按下（" + recyclerView.javaClass.simpleName + "）"
                )
            }
            sTouchTarget = recyclerView
            sTouchDownAt = System.currentTimeMillis()
            sTouchDownX = raw.rawX
            sTouchDownY = raw.rawY
            sTouchFired = false
            recyclerView.removeCallbacks(TOUCH_LONG_PRESS)
            recyclerView.postDelayed(TOUCH_LONG_PRESS, LONG_PRESS_MS)
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (sTouchDownAt != 0L) {
                var slop = sTouchSlop
                if (slop <= 0f) {
                    slop = Math.max(24f, recyclerView.resources.displayMetrics.density * 24f)
                    sTouchSlop = slop
                }
                if (Math.hypot(
                        (raw.rawX - sTouchDownX).toDouble(),
                        (raw.rawY - sTouchDownY).toDouble()
                    ) > slop
                ) {
                    sTouchDownAt = 0L
                    recyclerView.removeCallbacks(TOUCH_LONG_PRESS)
                }
            }
        } else if (action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL
        ) {
            sTouchDownAt = 0L
            recyclerView.removeCallbacks(TOUCH_LONG_PRESS)
            if (sTouchFired) {
                sTouchFired = false
                sLastPress = nowText() + " 已弹确认，吞掉松手事件"
                return java.lang.Boolean.TRUE
            }
        }
        return if (sTouchFired) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
    }

    private fun bindLongPressTree(view: View?, depth: Int) {
        if (view == null || depth > 6) {
            return
        }
        try {
            view.setOnLongClickListener { v ->
                try {
                    promptEntryHide(v)
                } catch (t: Throwable) {
                    false
                }
            }
        } catch (ignored: Throwable) {
        }
        if (view is ViewGroup) {
            val count = Math.min(view.childCount, 30)
            for (i in 0 until count) {
                bindLongPressTree(view.getChildAt(i), depth + 1)
            }
        }
    }

    private fun showHideEntryPrompt(view: View, label: String): Boolean {
        val activity = activityOf(view.context)
        if (activity == null) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "游戏库精简：长按「" + label + "」但取不到 Activity，无法弹窗"
            )
            return false
        }
        if (!markPrompt()) {
            sLastPress = nowText() + " 冷却窗口内忽略「" + label + "」"
            module.logd(
                Log.INFO, MainModule.TAG,
                "游戏库精简：长按「" + label + "」在冷却窗口内被忽略"
            )
            return false
        }
        val shown = booleanArrayOf(false)
        try {
            DexKitResolver.getHeyboxDialogSpec(module, activity, object : DexKitResolver.SpecCallback {
                override fun onReady(spec: DexKitResolver.HeyboxDialogSpec) {
                    if (shown[0]) {
                        return
                    }
                    shown[0] = true
                    activity.runOnUiThread {
                        try {
                            val content = buildPromptContent(activity, label)
                            spec.buildAndShow(
                                activity, "隐藏入口卡片", content, "隐藏",
                                DialogInterface.OnClickListener { d, _ ->
                                    d.dismiss()
                                    clearPrompt()
                                    hideEntryByName(activity, label)
                                },
                                "取消",
                                DialogInterface.OnClickListener { d, _ ->
                                    d.dismiss()
                                    clearPrompt()
                                }
                            )
                            logPrompt(label, "原生")
                        } catch (t: Throwable) {
                            logPrompt(label, "系统（原生构建失败）")
                            showHideEntryPromptFallback(activity, label)
                        }
                    }
                }

                override fun onFailed(reason: String) {
                    if (shown[0]) {
                        return
                    }
                    shown[0] = true
                    activity.runOnUiThread {
                        logPrompt(label, "系统（" + reason + "）")
                        showHideEntryPromptFallback(activity, label)
                    }
                }
            })
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "游戏库精简：原生弹窗通道异常，改用系统弹窗: " + t)
        }
        if (!shown[0]) {
            shown[0] = true
            logPrompt(label, "系统（原生弹窗未缓存）")
            showHideEntryPromptFallback(activity, label)
        }
        return true
    }

    private fun logPrompt(label: String, which: String) {
        sLastPress = nowText() + " 命中「" + label + "」→ " + which
        module.logd(Log.INFO, MainModule.TAG, "游戏库精简：长按 " + label + " → " + which + "确认框")
    }

    private fun logPressMiss(view: View, candidateCount: Int) {
        try {
            if (sPressTextsLogged.size >= 12) {
                return
            }
            val text = firstText(view, 0)
            sLastPress = nowText() + " 未命中（附近文本=" + text + "，候选=" + candidateCount + " 个）"
            if (text != null && sPressTextsLogged.add(text)) {
                module.logd(
                    Log.INFO, MainModule.TAG, "游戏库精简：长按未命中入口卡片（附近文本=" +
                            text + "，候选=" + candidateCount + " 个）"
                )
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun buildPromptContent(activity: Activity, label: String): View {
        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL
        val pad = module.dp(activity, 10f)
        val message = TextView(activity)
        message.text = "隐藏「" + label + "」？"
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        message.setPadding(pad, pad, pad, pad)
        content.addView(
            message,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return content
    }

    private fun showHideEntryPromptFallback(activity: Activity, label: String) {
        try {
            AlertDialog.Builder(activity)
                .setTitle("隐藏入口卡片")
                .setMessage("隐藏「" + label + "」？")
                .setPositiveButton(
                    "隐藏",
                    DialogInterface.OnClickListener { _, _ -> hideEntryByName(activity, label) }
                )
                .setNegativeButton("取消", null)
                .setOnDismissListener(DialogInterface.OnDismissListener { clearPrompt() })
                .show()
        } catch (t: Throwable) {
            clearPrompt()
            module.logd(Log.WARN, MainModule.TAG, "游戏库精简：长按提示弹窗失败: " + t)
        }
    }

    private fun hideEntryByName(activity: Activity, label: String) {
        val picked = LinkedHashSet(sHiddenEntries)
        if (!picked.add(label)) {
            return
        }
        setSelectedNames(PICK_ENTRY, picked)
        module.logd(Log.INFO, MainModule.TAG, "游戏库精简：长按隐藏入口卡片 " + label)
        try {
            Toast.makeText(activity, "已隐藏「" + label + "」（设置里可取消）", Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
        }
    }


    private fun hookChildList(item: Any) {
        val itemClass = item.javaClass
        if (sChildFiltersHooked.contains(itemClass)) {
            return
        }
        val getItems = itemsMethodOf(itemClass)
        if (getItems == null || !sChildFiltersHooked.add(itemClass)) {
            return
        }
        try {
            val current = getItems.invoke(item)
            if (current is List<*>) {
                for (child in current) {
                    val label = labelOf(child)
                    if (label != null) {
                        rememberName(sObservedEntries, KEY_OBSERVED_ENTRIES, label)
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        module.hook(getItems).intercept { chain ->
            val result = chain.proceed()
            if (result !is List<*>) {
                return@intercept result
            }
            if (result.isEmpty()) {
                return@intercept result
            }
            val visible: MutableList<Any?> = ArrayList(result.size)
            var filtered = false
            for (child in result) {
                val label = labelOf(child)
                if (label != null) {
                    rememberName(sObservedEntries, KEY_OBSERVED_ENTRIES, label)
                }
                if (isHidden(sHiddenEntries, label)) {
                    filtered = true
                    if (module.isEnabled(App.KEY_VERBOSE_LOG, false) &&
                        label != null && sSeenEntries.add(label)
                    ) {
                        module.logd(Log.INFO, MainModule.TAG, "游戏库精简：入口卡片 " + label + " → 隐藏")
                    }
                    continue
                }
                visible.add(child)
            }
            if (filtered) visible else result
        }
        module.logd(
            Log.INFO, MainModule.TAG,
            "✔ 游戏库精简 Hook 已安装（入口卡片过滤） " + itemClass.name + "#getItems"
        )
    }

    companion object {

        const val TARGET_GAME_REC_BIND = "game.rec.list.bind"

        const val TARGET_GAME_REC_WRAPPER = "game.rec.list.wrapper"

        const val TARGET_GAME_REC_BB = "game.rec.list.bb"

        const val ADAPTER_CLASS =
            "com.max.xiaoheihe.module.game.adapter.recommend.GameRecommendAdapter"

        const val WRAPPER_CLASS = "com.max.hbcommon.base.adapter.BigBrotherAdapterWrapper"

        @JvmField
        val CLASS_ANCHORS: Array<String> = arrayOf(
            "mall_newcomer",
            "big_game_card_scroll_v2",
            "game_comment_multi"
        )

        const val BB_DELEGATE_CLASS =
            "com.max.xiaoheihe.module.game.adapter.recommend.b"

        @JvmField
        val BB_CLASS_ANCHORS: Array<String> = arrayOf(
            "mini_app_v2",
            "game_comment_multi",
            "mall_newcomer"
        )

        private val TYPES_BANNER: Set<String> =
            Collections.unmodifiableSet(HashSet<String>(listOf("header")))

        private val TYPES_SMALL: Set<String> =
            Collections.unmodifiableSet(
                HashSet<String>(
                    listOf(
                        "menu", "menu_v2",
                        "mini_app", "mini_app_v2", "mini_app_v3"
                    )
                )
            )

        private val TYPES_CONTENT: Set<String> =
            Collections.unmodifiableSet(
                HashSet<String>(
                    listOf(
                        "title", "space",
                        "game_card_duo", "game_card_duo_release_date",
                        "game_card_single", "game_card_single_with_tab",
                        "game_card_scroll", "big_game_card",
                        "big_game_card_scroll", "big_game_card_scroll_v2",
                        "big_game_series_card_scroll",
                        "middle_game_card", "middle_game_card_video", "middle_game_scroll",
                        "game_list_rectangle", "game_list_square",
                        "game_comment", "game_comments", "game_comment_multi",
                        "factory_list", "rec_goods", "mall_newcomer"
                    )
                )
            )

        @JvmField
        val TYPE_CATALOG: Array<Array<String>> = arrayOf(
            arrayOf("header", "顶端横幅（三图）"),
            arrayOf("menu", "小分区入口"),
            arrayOf("menu_v2", "小分区入口 v2"),
            arrayOf("mini_app", "小程序推荐"),
            arrayOf("mini_app_v2", "小程序推荐 v2"),
            arrayOf("mini_app_v3", "小程序推荐 v3"),
            arrayOf("title", "分区标题（为你推荐…）"),
            arrayOf("space", "留白"),
            arrayOf("game_card_duo", "双列游戏卡"),
            arrayOf("game_card_duo_release_date", "双列游戏卡（发售日）"),
            arrayOf("game_card_single", "单列游戏卡"),
            arrayOf("game_card_single_with_tab", "带页签游戏卡"),
            arrayOf("game_card_scroll", "横滑游戏卡"),
            arrayOf("big_game_card", "大卡"),
            arrayOf("big_game_card_scroll", "横滑大卡"),
            arrayOf("big_game_card_scroll_v2", "横滑大卡 v2"),
            arrayOf("big_game_series_card_scroll", "系列横滑大卡"),
            arrayOf("middle_game_card", "中卡"),
            arrayOf("middle_game_card_video", "中卡（视频）"),
            arrayOf("middle_game_scroll", "横滑中卡"),
            arrayOf("game_list_rectangle", "游戏列表（矩形）"),
            arrayOf("game_list_square", "游戏列表（方形）"),
            arrayOf("game_comment", "游戏评价卡"),
            arrayOf("game_comments", "游戏评价卡 v2"),
            arrayOf("game_comment_multi", "多列评价卡"),
            arrayOf("factory_list", "厂商列表"),
            arrayOf("rec_goods", "推荐商品"),
            arrayOf("mall_newcomer", "新人券")
        )

        private const val KEY_OBSERVED_TYPES = "game_lib_seen_types"
        private const val KEY_OBSERVED_ENTRIES = "game_lib_seen_entries"
        private const val KEY_OBSERVED_SECTIONS = "game_lib_seen_sections"
        private const val MAX_OBSERVED = 60

        private val TYPES_ENTRY_BOARDS: Set<String> =
            Collections.unmodifiableSet(
                HashSet<String>(
                    listOf(
                        "menu", "menu_v2",
                        "mini_app", "mini_app_v2", "mini_app_v3"
                    )
                )
            )

        const val PICK_TYPE = 0
        const val PICK_ENTRY = 1
        const val PICK_SECTION = 2

        private val NO_ACCESSOR = Any()

        @Volatile
        private var sInstance: GameLibraryCleanHook? = null

        @Volatile
        private var sHideBanner = false

        @Volatile
        private var sHideSmall = false

        @Volatile
        private var sHideContent = false

        @Volatile
        private var sCustomTypes: Set<String> = Collections.emptySet()

        @Volatile
        private var sObserved: MutableSet<String> = LinkedHashSet()

        @Volatile
        private var sHiddenEntries: Set<String> = Collections.emptySet()

        @Volatile
        private var sHiddenSections: Set<String> = Collections.emptySet()

        @Volatile
        private var sLastHiddenEntries: Set<String> = Collections.emptySet()

        @Volatile
        private var sObservedEntries: MutableSet<String> = LinkedHashSet()

        @Volatile
        private var sObservedSections: MutableSet<String> = LinkedHashSet()

        private val sErrorLogged = AtomicBoolean(false)
        private val sMissingLogged = AtomicBoolean(false)
        private val sScopeLogged = AtomicBoolean(false)
        private val sScopeMissingLogged = AtomicBoolean(false)
        private val sEmptyCandidateLogged = AtomicBoolean(false)

        @Volatile
        private var sInstalledInner = false

        @Volatile
        private var sInstalledWrapper = false

        @Volatile
        private var sInstalledDelegate = false

        @Volatile
        private var sInstalledPress = false

        @Volatile
        private var sInstalledClickable = false

        @Volatile
        private var sInstalledAttach = false

        @Volatile
        private var sLastPress: String = "还没长按过"

        private val sBound: MutableMap<View, Array<String?>?> =
            Collections.synchronizedMap(WeakHashMap<View, Array<String?>?>())

        private val sAccessors: MutableMap<Class<*>, Any> = ConcurrentHashMap<Class<*>, Any>()

        private val sLogged: MutableMap<View, String> =
            Collections.synchronizedMap(WeakHashMap<View, String>())

        @Volatile
        private var sAdapterClass: Class<*>? = null

        private val sInnerAccessors: MutableMap<Class<*>, Any> = ConcurrentHashMap<Class<*>, Any>()

        private val sDataListMethods: MutableMap<Class<*>, Method> =
            ConcurrentHashMap<Class<*>, Method>()

        private val sPromptLock = Any()
        private const val PROMPT_COOLDOWN_MS = 1200L

        @Volatile
        private var sPromptAtMs = 0L

        private val sBoardViews: MutableSet<View> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

        private val sLongPressAttached: MutableSet<View> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

        private val sLongPressRescan: MutableSet<View> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

        private val sChildFiltersHooked: MutableSet<Class<*>> =
            ConcurrentHashMap.newKeySet<Class<*>>()

        private val sItemsMethods: MutableMap<Class<*>, Method> =
            ConcurrentHashMap<Class<*>, Method>()

        private val sLabelAccessors: MutableMap<Class<*>, Any> = ConcurrentHashMap<Class<*>, Any>()

        private val sSeenEntries: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

        private val sPressTextsLogged: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

        private const val LONG_PRESS_MS = 450L

        private val sTouchAttached: MutableSet<View> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

        @Volatile
        private var sTouchTarget: View? = null

        @Volatile
        private var sTouchDownAt = 0L

        @Volatile
        private var sTouchDownX = 0f

        @Volatile
        private var sTouchDownY = 0f

        @Volatile
        private var sTouchSlop = 0f

        @Volatile
        private var sTouchFired = false

        private val TOUCH_LONG_PRESS: Runnable = Runnable {
            val target = sTouchTarget
            val instance = sInstance
            if (target == null || instance == null || sTouchDownAt == 0L || sTouchFired) {
                return@Runnable
            }
            val pressed = findDeepestChildAt(target, sTouchDownX, sTouchDownY)
            if (pressed == null) {
                return@Runnable
            }
            val candidates = entryCandidates()
            var label = findEntryLabel(pressed, candidates)
            if (label == null) {
                label = fallbackLabel(pressed)
            }
            if (label == null) {
                sLastPress = nowText() + " 长按自检未取到卡片名"
                instance.logPressMiss(pressed, candidates.size)
                return@Runnable
            }
            sTouchFired = true
            instance.module.logd(
                Log.INFO, MainModule.TAG,
                "游戏库精简：长按自检触发「" + label + "」"
            )
            instance.showHideEntryPrompt(pressed, label)
        }

        @JvmStatic
        fun refresh() {
            val instance = sInstance
            if (instance == null) {
                return
            }
            val m = instance.module
            sHideBanner = m.isEnabled(App.KEY_GAME_LIB_HIDE_BANNER, false)
            sHideSmall = m.isEnabled(App.KEY_GAME_LIB_HIDE_MENU, false)
            sHideContent = m.isEnabled(App.KEY_GAME_LIB_HIDE_SECTIONS, false)
            sCustomTypes = parseTypes(m.getString(App.KEY_GAME_LIB_HIDE_TYPES, ""))
            val previousEntries = sLastHiddenEntries
            sHiddenEntries = parseNames(m.getString(App.KEY_GAME_LIB_HIDE_ENTRIES, ""))
            val entriesChanged = previousEntries != sHiddenEntries
            sLastHiddenEntries = sHiddenEntries
            sHiddenSections = parseNames(m.getString(App.KEY_GAME_LIB_HIDE_SECTION_NAMES, ""))
            sObserved = LinkedHashSet(
                parseTypes(HeyboxPrefs.getString(KEY_OBSERVED_TYPES, ""))
            )
            sObservedEntries = LinkedHashSet(
                parseNames(HeyboxPrefs.getString(KEY_OBSERVED_ENTRIES, ""))
            )
            sObservedSections = LinkedHashSet(
                parseNames(HeyboxPrefs.getString(KEY_OBSERVED_SECTIONS, ""))
            )
            for (entry in ArrayList(sBound.entries)) {
                val info = entry.value
                if (info != null) {
                    apply(entry.key, info[0], info[1])
                }
            }
            if (entriesChanged) {
                instance.notifyBoundList()
            }
        }

        @JvmStatic
        fun diagnostics(): String {
            val sb = StringBuilder()
            sb.append("宿主版本: code=").append(sHostVersionCode).append('\n')
            sb.append("目标解析:\n")
            sb.append("  内层 ").append(TARGET_GAME_REC_BIND).append(" = ")
                .append(HeyboxTargets.sourceOf(TARGET_GAME_REC_BIND)).append('\n')
            sb.append("  外层 ").append(TARGET_GAME_REC_WRAPPER).append(" = ")
                .append(HeyboxTargets.sourceOf(TARGET_GAME_REC_WRAPPER)).append('\n')
            sb.append("  预绑定 ").append(TARGET_GAME_REC_BB).append(" = ")
                .append(HeyboxTargets.sourceOf(TARGET_GAME_REC_BB)).append('\n')
            sb.append("Hook 安装: 内层=").append(yes(sInstalledInner))
                .append(" 外层=").append(yes(sInstalledWrapper))
                .append(" 预绑定=").append(yes(sInstalledDelegate)).append('\n')
            sb.append("           长按兜底=").append(yes(sInstalledPress))
                .append(" 卡片挂载=").append(yes(sInstalledAttach))
                .append(" 长按可达=").append(yes(sInstalledClickable)).append('\n')
            sb.append("已绑定条目=").append(sBound.size)
                .append(" 已记录分区条目=").append(sBoardViews.size).append('\n')
            sb.append("候选: type=").append(sObserved.size)
                .append(" 入口卡片=").append(sObservedEntries.size)
                .append(" 分区=").append(sObservedSections.size).append('\n')
            sb.append("已选隐藏: type=").append(sCustomTypes.size)
                .append(" 入口卡片=").append(sHiddenEntries.size)
                .append(" 分区=").append(sHiddenSections.size).append('\n')
            sb.append("开关: 横幅=").append(onOff(sHideBanner))
                .append(" 小分区=").append(onOff(sHideSmall))
                .append(" 推荐分区=").append(onOff(sHideContent)).append('\n')
            sb.append("最近长按: ").append(sLastPress)
            return sb.toString()
        }

        private var sHostVersionCode: String = "-"

        @JvmStatic
        fun setHostVersionCode(code: Long) {
            sHostVersionCode = code.toString()
        }

        @JvmStatic
        fun pickerEntries(): List<@JvmSuppressWildcards Array<String>> {
            val out: MutableList<Array<String>> = ArrayList()
            val known: MutableSet<String> = LinkedHashSet()
            for (entry in TYPE_CATALOG) {
                out.add(arrayOf(entry[0], entry[1]))
                known.add(entry[0])
            }
            for (type in ArrayList(sObserved)) {
                if (!known.contains(type)) {
                    out.add(arrayOf(type, ""))
                }
            }
            return out
        }

        @JvmStatic
        fun selectedTypes(): Set<@JvmSuppressWildcards String> {
            return LinkedHashSet(sCustomTypes)
        }

        @JvmStatic
        fun setSelectedTypes(types: Collection<String?>?) {
            val sb = StringBuilder()
            if (types != null) {
                for (type in types) {
                    if (type == null || type.trim().isEmpty()) {
                        continue
                    }
                    if (sb.length > 0) {
                        sb.append('\n')
                    }
                    sb.append(type.trim().lowercase(Locale.ROOT))
                }
            }
            try {
                HeyboxPrefs.setString(App.KEY_GAME_LIB_HIDE_TYPES, sb.toString())
            } catch (ignored: Throwable) {
            }
            refresh()
        }

        @JvmStatic
        fun pickerEntries(kind: Int): List<@JvmSuppressWildcards Array<String>> {
            if (kind == PICK_ENTRY) {
                return nameEntries(sHiddenEntries, sObservedEntries)
            }
            if (kind == PICK_SECTION) {
                return nameEntries(sHiddenSections, sObservedSections)
            }
            return pickerEntries()
        }

        private fun nameEntries(
            hidden: Set<String>,
            observed: Set<String>
        ): List<@JvmSuppressWildcards Array<String>> {
            val out: MutableList<Array<String>> = ArrayList()
            val seen: MutableSet<String> = LinkedHashSet()
            for (name in ArrayList(hidden)) {
                if (seen.add(name)) {
                    out.add(arrayOf(name, ""))
                }
            }
            for (name in ArrayList(observed)) {
                if (seen.add(name)) {
                    out.add(arrayOf(name, ""))
                }
            }
            return out
        }

        @JvmStatic
        fun selectedNames(kind: Int): Set<@JvmSuppressWildcards String> {
            return LinkedHashSet(if (kind == PICK_ENTRY) sHiddenEntries else sHiddenSections)
        }

        @JvmStatic
        fun setSelectedNames(kind: Int, names: Collection<String?>?) {
            val sb = StringBuilder()
            if (names != null) {
                for (name in names) {
                    if (name == null || name.trim().isEmpty()) {
                        continue
                    }
                    if (sb.length > 0) {
                        sb.append('\n')
                    }
                    sb.append(name.trim())
                }
            }
            try {
                HeyboxPrefs.setString(
                    if (kind == PICK_ENTRY) {
                        App.KEY_GAME_LIB_HIDE_ENTRIES
                    } else {
                        App.KEY_GAME_LIB_HIDE_SECTION_NAMES
                    },
                    sb.toString()
                )
            } catch (ignored: Throwable) {
            }
            refresh()
        }

        @JvmStatic
        fun coverageHint(type: String?): String? {
            if (type == null) {
                return null
            }
            val normalized = type.lowercase(Locale.ROOT)
            if (TYPES_BANNER.contains(normalized)) {
                return "已在「隐藏游戏库横幅」"
            }
            if (TYPES_SMALL.contains(normalized)) {
                return "已在「隐藏游戏库小分区」"
            }
            if (TYPES_CONTENT.contains(normalized)) {
                return "已在「隐藏游戏库推荐分区」"
            }
            return null
        }

        @JvmStatic
        @JvmName("touchState")
        internal fun touchState(): String {
            val target = sTouchTarget
            return "down=" + sTouchDownAt + " fired=" + sTouchFired + " target=" +
                    (if (target == null) "-" else target.javaClass.simpleName)
        }

        private fun onOff(enabled: Boolean): String = if (enabled) "隐藏" else "保留"

        private fun yes(value: Boolean): String {
            return if (value) "✔" else "✘"
        }

        private fun nowText(): String {
            return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(java.util.Date())
        }

        private fun parseTypes(raw: String?): Set<String> {
            if (raw == null || raw.trim().isEmpty()) {
                return Collections.emptySet()
            }
            val out: MutableSet<String> = LinkedHashSet()
            for (token in raw.split(Regex("[\\s,，;；]+"))) {
                val type = token.trim().lowercase(Locale.ROOT)
                if (type.isNotEmpty() && !type.startsWith("#")) {
                    out.add(type)
                }
            }
            return out
        }

        private fun parseNames(raw: String?): Set<String> {
            if (raw == null || raw.trim().isEmpty()) {
                return Collections.emptySet()
            }
            val out: MutableSet<String> = LinkedHashSet()
            for (line in raw.split("\n")) {
                val name = line.trim()
                if (name.isNotEmpty() && !name.startsWith("#")) {
                    out.add(name)
                }
            }
            return out
        }

        private fun persistNames(prefsKey: String, values: Set<String>) {
            try {
                val sb = StringBuilder()
                for (value in values) {
                    if (sb.length > 0) {
                        sb.append('\n')
                    }
                    sb.append(value)
                }
                HeyboxPrefs.setString(prefsKey, sb.toString())
            } catch (ignored: Throwable) {
            }
        }

        private fun rememberName(observed: MutableSet<String>, prefsKey: String, name: String?) {
            if (name == null || name.trim().isEmpty()) {
                return
            }
            val value = name.trim()
            if (!observed.add(value)) {
                return
            }
            val iterator = observed.iterator()
            while (observed.size > MAX_OBSERVED && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
            persistNames(prefsKey, observed)
        }

        private fun isEntryBoard(item: Any, type: String?): Boolean {
            if (type != null && TYPES_ENTRY_BOARDS.contains(type)) {
                return true
            }
            return itemsMethodOf(item.javaClass) != null
        }

        private fun innerAdapterOf(wrapper: Any?): Any? {
            if (wrapper == null) {
                return null
            }
            val wrapperClass = wrapper.javaClass
            var accessor = sInnerAccessors.get(wrapperClass)
            if (accessor == null) {
                accessor = resolveInnerAccessor(wrapper, wrapperClass)
                if (accessor != null) {
                    sInnerAccessors.put(wrapperClass, accessor)
                }
            }
            if (accessor == null) {
                return null
            }
            return try {
                if (accessor is Field) {
                    accessor.get(wrapper)
                } else {
                    (accessor as Method).invoke(wrapper)
                }
            } catch (t: Throwable) {
                null
            }
        }

        private fun resolveInnerAccessor(wrapper: Any, wrapperClass: Class<*>): Any? {
            val adapterCls = adapterClass() ?: return null
            for (field in wrapperClass.declaredFields) {
                if (!field.type.isAssignableFrom(adapterCls)) {
                    continue
                }
                try {
                    field.isAccessible = true
                    if (adapterCls.isInstance(field.get(wrapper))) {
                        return field
                    }
                } catch (ignored: Throwable) {
                }
            }
            for (method in wrapperClass.declaredMethods) {
                if (method.parameterCount != 0 ||
                    !method.returnType.isAssignableFrom(adapterCls)
                ) {
                    continue
                }
                try {
                    method.isAccessible = true
                    if (adapterCls.isInstance(method.invoke(wrapper))) {
                        return method
                    }
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        private fun isGameRecommendAdapter(inner: Any?): Boolean {
            val adapterCls = adapterClass()
            return adapterCls != null && adapterCls.isInstance(inner)
        }

        private fun adapterClass(): Class<*>? {
            val cached = sAdapterClass
            if (cached != null) {
                return cached
            }
            for (method in HeyboxTargets.methods(TARGET_GAME_REC_BIND)) {
                val declaring = method.declaringClass
                if (declaring != null) {
                    sAdapterClass = declaring
                    return declaring
                }
            }
            return null
        }

        private fun itemAt(inner: Any?, position: Int): Any? {
            val list = dataListOf(inner)
            return if (list != null && position >= 0 && position < list.size) {
                list[position]
            } else {
                null
            }
        }

        private fun dataListOf(adapter: Any?): List<*>? {
            if (adapter == null) {
                return null
            }
            val adapterCls = adapter.javaClass
            val cached = sDataListMethods.get(adapterCls)
            val getter: Method = if (cached != null) {
                cached
            } else {
                val resolved = resolveDataListMethod(adapterCls) ?: return null
                sDataListMethods.put(adapterCls, resolved)
                resolved
            }
            return try {
                val list = getter.invoke(adapter)
                if (list is List<*>) list else null
            } catch (t: Throwable) {
                null
            }
        }

        private fun indexOf(list: List<*>?, item: Any?): Int {
            if (list == null || item == null) {
                return -1
            }
            return try {
                list.indexOf(item)
            } catch (t: Throwable) {
                -1
            }
        }

        private fun sectionNameOf(item: Any?, type: String?, list: List<*>?, index: Int): String? {
            if ("title" == type) {
                return labelOf(item)
            }
            if (list == null || index <= 0) {
                return null
            }
            for (i in Math.min(index, list.size) - 1 downTo 0) {
                val prev = list[i]
                if (prev != null && "title" == typeOf(prev)) {
                    return labelOf(prev)
                }
            }
            return null
        }

        private fun resolveDataListMethod(innerClass: Class<*>): Method? {
            try {
                val getter = innerClass.getMethod("getDataList")
                getter.isAccessible = true
                return getter
            } catch (ignored: Throwable) {
            }
            var cls: Class<*>? = innerClass
            while (cls != null && cls != Any::class.java) {
                for (method in cls.declaredMethods) {
                    if (method.parameterCount == 0 &&
                        List::class.java.isAssignableFrom(method.returnType)
                    ) {
                        try {
                            method.isAccessible = true
                            return method
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                cls = cls.superclass
            }
            return null
        }

        private fun insideBoardView(viewObject: Any?): Boolean {
            if (viewObject !is View || sBoardViews.isEmpty()) {
                return false
            }
            var node: View? = viewObject
            var i = 0
            while (i < 24) {
                val current = node ?: break
                if (sBoardViews.contains(current)) {
                    return true
                }
                val parent = current.parent
                node = if (parent is View) parent else null
                i++
            }
            return false
        }

        private fun nearestRecyclerView(view: View): View? {
            var node: View? = view
            var i = 0
            while (i < 24) {
                val current = node ?: break
                if (isRecyclerView(current)) {
                    return current
                }
                val parent = current.parent
                node = if (parent is View) parent else null
                i++
            }
            return null
        }

        private fun isRecyclerView(view: View): Boolean {
            var cls: Class<*>? = view.javaClass
            while (cls != null) {
                if ("androidx.recyclerview.widget.RecyclerView" == cls.name) {
                    return true
                }
                cls = cls.superclass
            }
            return false
        }

        private fun collectRecyclerViews(node: View?, out: MutableList<View>, depth: Int) {
            if (node == null || depth > 8 || out.size >= 4) {
                return
            }
            if ("androidx.recyclerview.widget.RecyclerView" == node.javaClass.name) {
                out.add(node)
                return
            }
            if (node is ViewGroup) {
                val count = Math.min(node.childCount, 30)
                for (i in 0 until count) {
                    collectRecyclerViews(node.getChildAt(i), out, depth + 1)
                }
            }
        }

        private fun isTopLevelViewGroup(view: View): Boolean {
            return view.parent !is View
        }

        private fun findDeepestChildAt(view: View, rawX: Float, rawY: Float): View? {
            if (view !is ViewGroup) {
                return null
            }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = rawX - location[0]
            val y = rawY - location[1]
            if (x < 0 || y < 0 || x > view.width || y > view.height) {
                return null
            }
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child == null || child.visibility != View.VISIBLE) {
                    continue
                }
                val deeper = findDeepestChildAt(child, rawX, rawY)
                if (deeper != null) {
                    return deeper
                }
            }
            return view
        }

        private fun sharesRootWithBoundView(view: View): Boolean {
            val root = view.rootView
            if (root == null) {
                return false
            }
            for (bound in ArrayList(sBound.keys)) {
                if (bound != null && bound.rootView === root) {
                    return true
                }
            }
            return false
        }

        private fun findEntryLabel(view: View, candidates: Set<String>): String? {
            var node: View? = view
            var level = 0
            while (level < 4) {
                val current = node ?: break
                val label = matchLabelIn(current, candidates, 0)
                if (label != null) {
                    return label
                }
                val parent = current.parent
                node = if (parent is View) parent else null
                level++
            }
            return null
        }

        private fun matchLabelIn(node: View, candidates: Set<String>, depth: Int): String? {
            if (depth > 6) {
                return null
            }
            if (node is TextView) {
                val text = node.text
                val label = matchCandidate(text, candidates)
                if (label != null) {
                    return label
                }
            }
            if (node is ViewGroup) {
                val count = Math.min(node.childCount, 30)
                for (i in 0 until count) {
                    val label = matchLabelIn(node.getChildAt(i), candidates, depth + 1)
                    if (label != null) {
                        return label
                    }
                }
            }
            return null
        }

        private fun matchCandidate(text: CharSequence?, candidates: Set<String>): String? {
            if (text == null) {
                return null
            }
            val value = text.toString().trim()
            if (value.isEmpty()) {
                return null
            }
            if (candidates.contains(value)) {
                return value
            }
            for (candidate in candidates) {
                if (candidate.length >= 2 && value.contains(candidate)) {
                    return candidate
                }
            }
            return null
        }

        private fun fallbackLabel(view: View): String? {
            var node: View? = view
            var level = 0
            while (level < 4) {
                val current = node ?: break
                val text = firstText(current, 0)
                if (text != null && text.length >= 2 && text.length <= 14) {
                    return text
                }
                val parent = current.parent
                node = if (parent is View) parent else null
                level++
            }
            return null
        }

        private fun firstText(node: View?, depth: Int): String? {
            if (node == null || depth > 6) {
                return null
            }
            if (node is TextView) {
                val text = node.text
                if (text != null && text.toString().trim().isNotEmpty()) {
                    return text.toString().trim()
                }
            }
            if (node is ViewGroup) {
                val count = Math.min(node.childCount, 30)
                for (i in 0 until count) {
                    val text = firstText(node.getChildAt(i), depth + 1)
                    if (text != null) {
                        return text
                    }
                }
            }
            return null
        }

        private fun activityOf(context: Context?): Activity? {
            var ctx = context
            var i = 0
            while (i < 10 && ctx != null) {
                val current = ctx
                if (current is Activity) {
                    return current
                }
                if (current is ContextWrapper) {
                    ctx = current.baseContext
                } else {
                    return null
                }
                i++
            }
            return null
        }

        private fun markPrompt(): Boolean = synchronized(sPromptLock) {
            val now = System.currentTimeMillis()
            if (now - sPromptAtMs < PROMPT_COOLDOWN_MS) {
                false
            } else {
                sPromptAtMs = now
                true
            }
        }

        private fun clearPrompt() {
            synchronized(sPromptLock) {
                sPromptAtMs = 0L
            }
        }

        private fun entryCandidates(): Set<String> {
            val candidates = LinkedHashSet(sObservedEntries)
            candidates.addAll(sHiddenEntries)
            return candidates
        }

        private fun itemsMethodOf(itemClass: Class<*>): Method? {
            val cached = sItemsMethods.get(itemClass)
            if (cached != null) {
                return cached
            }
            try {
                val method = itemClass.getMethod("getItems")
                if (method.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(method.returnType)
                ) {
                    method.isAccessible = true
                    sItemsMethods.put(itemClass, method)
                    return method
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        private fun labelOf(child: Any?): String? {
            if (child == null) {
                return null
            }
            val childClass = child.javaClass
            var accessor = sLabelAccessors.get(childClass)
            if (accessor == null) {
                accessor = resolveLabelAccessor(childClass)
                sLabelAccessors.put(childClass, accessor ?: NO_ACCESSOR)
            }
            if (accessor === NO_ACCESSOR) {
                return null
            }
            try {
                val value = (accessor as Method).invoke(child)
                if (value is String && value.trim().isNotEmpty()) {
                    return value.trim()
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        private fun resolveLabelAccessor(childClass: Class<*>): Method? {
            for (methodName in arrayOf("getName", "getDesc", "getKey", "getTitle", "getText")) {
                var cls: Class<*>? = childClass
                while (cls != null && cls != Any::class.java) {
                    try {
                        val method = cls.getDeclaredMethod(methodName)
                        if (method.parameterCount == 0 &&
                            method.returnType == String::class.java
                        ) {
                            method.isAccessible = true
                            return method
                        }
                    } catch (ignored: Throwable) {
                    }
                    cls = cls.superclass
                }
            }
            return null
        }

        private fun remember(type: String) {
            val normalized = type.lowercase(Locale.ROOT)
            if (isKnown(normalized)) {
                return
            }
            val observed = sObserved
            if (!observed.add(normalized)) {
                return
            }
            val iterator = observed.iterator()
            while (observed.size > MAX_OBSERVED && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
            try {
                val sb = StringBuilder()
                for (item in observed) {
                    if (sb.length > 0) {
                        sb.append('\n')
                    }
                    sb.append(item)
                }
                HeyboxPrefs.setString(KEY_OBSERVED_TYPES, sb.toString())
            } catch (ignored: Throwable) {
            }
        }

        private fun isKnown(type: String): Boolean {
            for (entry in TYPE_CATALOG) {
                if (entry[0] == type) {
                    return true
                }
            }
            return TYPES_BANNER.contains(type) || TYPES_SMALL.contains(type) ||
                    TYPES_CONTENT.contains(type)
        }

        private fun apply(itemView: View?, type: String?, section: String?) {
            if (itemView == null || type == null) {
                return
            }
            if (isHiddenNow(type, section)) {
                FeedItemHider.hide(itemView)
            } else {
                FeedItemHider.restore(itemView)
            }
        }

        private fun isHiddenNow(type: String, section: String?): Boolean {
            return shouldHide(type) || isHidden(sHiddenSections, section)
        }

        private fun shouldHide(type: String): Boolean {
            val normalized = type.lowercase(Locale.ROOT)
            val custom = sCustomTypes
            if (custom.isNotEmpty() && custom.contains(normalized)) {
                return true
            }
            return (sHideBanner && TYPES_BANNER.contains(normalized)) ||
                    (sHideSmall && TYPES_SMALL.contains(normalized)) ||
                    (sHideContent && TYPES_CONTENT.contains(normalized))
        }

        private fun isHidden(hidden: Set<String>, name: String?): Boolean {
            return name != null && hidden.isNotEmpty() &&
                    hidden.contains(name.lowercase(Locale.ROOT))
        }

        private fun typeOf(item: Any?): String? {
            if (item == null) {
                return null
            }
            val itemClass = item.javaClass
            var accessor = sAccessors.get(itemClass)
            if (accessor == null) {
                accessor = resolveAccessor(itemClass)
                sAccessors.put(itemClass, accessor ?: NO_ACCESSOR)
            }
            if (accessor === NO_ACCESSOR) {
                return null
            }
            return try {
                val value = if (accessor is Method) {
                    accessor.invoke(item)
                } else {
                    (accessor as Field).get(item)
                }
                if (value is String) value else null
            } catch (t: Throwable) {
                null
            }
        }

        private fun resolveAccessor(itemClass: Class<*>): Any? {
            var cls: Class<*>? = itemClass
            while (cls != null && cls != Any::class.java) {
                try {
                    val method = cls.getDeclaredMethod("getType")
                    if (method.parameterCount == 0 && method.returnType == String::class.java) {
                        method.isAccessible = true
                        return method
                    }
                } catch (ignored: Throwable) {
                }
                cls = cls.superclass
            }
            cls = itemClass
            while (cls != null && cls != Any::class.java) {
                try {
                    val field = cls.getDeclaredField("type")
                    if (field.type == String::class.java) {
                        field.isAccessible = true
                        return field
                    }
                } catch (ignored: Throwable) {
                }
                cls = cls.superclass
            }
            return null
        }
    }
}
