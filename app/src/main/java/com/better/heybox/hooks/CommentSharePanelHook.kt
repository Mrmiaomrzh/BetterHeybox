package com.better.heybox.hooks

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.CommentPanelConfig
import com.better.heybox.MainModule
import com.better.heybox.ViewUtils
import com.better.heybox.arrangePanelActions
import com.better.heybox.uniquePanelActionIds
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

class CommentSharePanelHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        var actions = false
        var click = false
        var capture = false
        var blocked: Int
        try {
            resolveClasses(cl)
            hookPanelActions(cl)
            hookPanelLayout(cl)
            hookPanelClick(cl)
            capture = hookReplyHandlerCapture(cl)
            blocked = hookTapReply(cl)
            actions = true
            click = true
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 评论分享面板安装失败", t)
            blocked = 0
        }
        module.logd(
            Log.WARN, MainModule.TAG, "[评论分享面板] Hook 安装结果：面板目标=" + (if (actions) "✔" else "✘")
                    + " / 点击分发=" + (if (click) "✔" else "✘")
                    + " / 回复回调捕获=" + (if (capture) "✔" else "✘")
                    + " / 单击回复拦截=" + blocked + " 处"
                    + " / 状态=" + (if (module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)) "开启" else "关闭")
        )
    }

    private fun resolveClasses(cl: ClassLoader) {
        sCommentTextClass = tryForName(COMMENT_TEXT_CLASS, cl)
        sSubCommentViewClass = tryForName(SUB_COMMENT_VIEW_CLASS, cl)
        sCommentObjClass = tryForName(COMMENT_OBJ_CLASS, cl)
        sReplyIntentClass = tryForName(REPLY_INTENT_CLASS, cl)
        sFunction1Class = resolveFunction1Class(cl)
        if (sCommentTextClass == null || sCommentObjClass == null || sReplyIntentClass == null) {
            module.logd(
                Log.WARN, MainModule.TAG, "评论分享面板类型解析缺失: text=" + (sCommentTextClass != null)
                        + " obj=" + (sCommentObjClass != null)
                        + " intent=" + (sReplyIntentClass != null)
            )
        }
        if (sFunction1Class == null) {
            module.logd(
                Log.WARN, MainModule.TAG, "评论分享面板类型解析缺失: function1=null"
                        + "（候选 " + FUNCTION1_CLASS_CANDIDATES.joinToString(" / ") + " 全部失效，"
                        + "回复回调捕获将降级）"
            )
        }
    }

    private fun resolveFunction1Class(cl: ClassLoader): Class<*>? {
        for (name in FUNCTION1_CLASS_CANDIDATES) {
            val c = tryForName(name, cl) ?: continue
            if (!c.isInterface) {
                continue
            }
            try {
                val m = c.getDeclaredMethod("invoke", Any::class.java)
                if (m.returnType == Any::class.java) {
                    return c
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    @Throws(Throwable::class)
    private fun hookPanelActions(cl: ClassLoader) {
        val manager = Class.forName("com.max.common.common.share.ShareViewManager", false, cl)
        val forwardType = Class.forName("com.max.data.model.share.IForwardModel", false, cl)
        val build = manager.getDeclaredMethod("m", Context::class.java, forwardType, MutableList::class.java)
        module.hook(build).intercept { chain ->
            val result = chain.proceed()
            try {
                val forward = chain.arg(1)
                val actions = chain.arg(2)
                val context = chain.arg(0) as? Context
                val commentForward = isCommentForward(forward, cl)
                val recorded = recordedComment()
                module.logd(
                    Log.INFO, MainModule.TAG, "评论分享面板构建：forward="
                            + (forward?.javaClass?.name ?: "null")
                            + " / 评论转发=" + commentForward
                            + " / 长按记录=" + (recorded != null)
                            + " / actions=" + (if (actions is List<*>) actions.size else -1)
                            + " / 回复=" + module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)
                            + " / 截图=" + module.isEnabled(App.KEY_SCREENSHOT_ENABLED, true)
                )
                if (actions is MutableList<*> && commentForward) {
                    if (recorded == null) {
                        module.logd(
                            Log.WARN, MainModule.TAG,
                            "评论分享面板：没有可用的长按记录，跳过「回复/截图此评论」注入"
                                    + "（长按记录 hook 是否安装见启动日志「评论自由复制」）"
                        )
                    } else {
                        appendReplyAction(actions as MutableList<Any?>, cl, context)
                        appendScreenshotAction(actions as MutableList<Any?>, cl, context)
                    }
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "追加评论面板目标失败: $t")
            }
            result
        }
    }

    private fun hookPanelLayout(cl: ClassLoader) {
        try {
            val manager = Class.forName("com.max.common.common.share.ShareViewManager", false, cl)
            val model = Class.forName("com.max.data.model.share.BaseActionModel", false, cl)
            val method = manager.declaredMethods.single {
                val p = it.parameterTypes
                it.name == "i" && p.size == 10 && p[0] == Context::class.java &&
                    p[1] == List::class.java && p[8] == model
            }
            module.hook(method).intercept { chain ->
                try {
                    val actions = chain.arg(1)
                    if (actions is MutableList<*> && isCommentForward(chain.arg(8), cl)) {
                        @Suppress("UNCHECKED_CAST")
                        configurePanelActions(actions as MutableList<Any?>)
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "评论面板自定义失败: $t")
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "评论面板自定义挂点不可用: $t")
        }
    }

    private fun configurePanelActions(actions: MutableList<Any?>) {
        val original = actions.toList()
        val ids = original.map { actionId(it) }
        val uniqueIds = uniquePanelActionIds(ids)
        val discovered = original.mapIndexedNotNull { index, item ->
            val id = uniqueIds[index] ?: return@mapIndexedNotNull null
            CommentPanelConfig.Action(id, actionName(item) ?: id)
        }
        CommentPanelConfig.recordDiscovered(discovered)
        val visible = CommentPanelConfig.orderedVisibleIds(uniqueIds.filterNotNull())
        val result = arrangePanelActions(original, uniqueIds, visible)
        if (result == original) return
        try {
            actions.clear()
            actions.addAll(result)
        } catch (t: Throwable) {
            try {
                actions.clear()
                actions.addAll(original)
            } catch (_: Throwable) { }
            module.logd(Log.WARN, MainModule.TAG, "评论面板排序失败，尝试恢复原列表: $t")
        }
    }

    private fun actionId(item: Any?): String? {
        return try {
            val action = item?.javaClass?.getMethod("getAction")?.invoke(item) ?: return null
            (action.javaClass.getMethod("getActionTag").invoke(action) as? String)
                ?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) { null }
    }

    private fun actionName(item: Any?): String? {
        if (item == null) return null
        for (name in arrayOf("getName", "getTitle", "getLabel", "getText")) {
            try {
                val value = item.javaClass.getMethod(name).invoke(item) as? CharSequence
                if (!value.isNullOrBlank()) return value.toString()
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun isCommentForward(forward: Any?, cl: ClassLoader): Boolean {
        if (forward == null) {
            return false
        }
        for (name in arrayOf(
            "com.max.data.model.community.CommentForwardModel",
            "com.max.data.model.community.GameCommentForwardModel"
        )) {
            try {
                if (Class.forName(name, false, cl).isInstance(forward)) {
                    return true
                }
            } catch (ignored: Throwable) {
            }
        }
        return false
    }

    private fun appendReplyAction(actions: MutableList<Any?>, cl: ClassLoader, context: Context?) {
        if (!module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true) || replyHandler() == null) {
            module.logd(
                Log.INFO, MainModule.TAG, "跳过「回复」：开关="
                        + module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)
                        + " 回复回调=" + (replyHandler() != null)
            )
            return
        }
        if (sCommentObjClass == null || sReplyIntentClass == null || sFunction1Class == null) {
            module.logd(
                Log.INFO, MainModule.TAG, "跳过「回复」：类型缺失 obj="
                        + (sCommentObjClass != null) + " intent=" + (sReplyIntentClass != null)
                        + " function1=" + (sFunction1Class != null)
            )
            return
        }
        val comment = commentObjOf(recordedComment()) ?: return
        if (hasAction(actions, cl, REPLY_ACTION_TAG)) {
            return
        }
        appendCustomAction(
            actions, cl, context, REPLY_ACTION_TAG, REPLY_ACTION_LABEL,
            resolvePanelIcon(context, REPLY_ICON_NAME)
        )
    }

    private fun appendScreenshotAction(actions: MutableList<Any?>, cl: ClassLoader, context: Context?) {
        if (!module.isEnabled(App.KEY_SCREENSHOT_ENABLED, true)) {
            module.logd(Log.INFO, MainModule.TAG, "跳过「截图此评论」：截图总开关关闭")
            return
        }
        if (hasAction(actions, cl, SCREENSHOT_ACTION_TAG)) {
            return
        }
        appendCustomAction(
            actions, cl, context, SCREENSHOT_ACTION_TAG, SCREENSHOT_ACTION_LABEL,
            resolvePanelIcon(context, "bbs_sharebutton_download_46x46")
        )
    }

    private fun appendCustomAction(
        actions: MutableList<Any?>, cl: ClassLoader, context: Context?,
        tag: String, label: String, iconRes: Int
    ) {
        val actionObjClass: Class<*>
        val actionClass: Class<*>
        val customActionClass: Class<*>
        try {
            actionObjClass = Class.forName("com.max.data.bean.share.ActionObj", false, cl)
            actionClass = Class.forName("com.max.data.model.share.IAction", false, cl)
            customActionClass = Class.forName("com.max.data.model.share.IAction\$CustomAction", false, cl)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "评论面板目标类型不可用: $t")
            return
        }
        try {
            val customAction = customActionClass.getConstructor(String::class.java)
                .newInstance(tag)
            val actionObject = actionObjClass.getConstructor(
                String::class.java, Int::class.javaObjectType, String::class.java, String::class.java,
                String::class.java, String::class.java, actionClass
            ).newInstance(
                label, iconRes,
                null, null, null, null, customAction
            )
            actions.add(actionObject)
            module.logd(
                Log.INFO, MainModule.TAG, "评论分享面板已追加「$label"
                        + "」: count=" + actions.size
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "构造评论面板目标「$label」失败: $t")
        }
    }

    private fun hasAction(actions: List<Any?>, cl: ClassLoader, tag: String): Boolean {
        try {
            for (actionObject in actions) {
                val action = actionObject?.javaClass?.getMethod("getAction")?.invoke(actionObject)
                    ?: continue
                val actionTag = action.javaClass.getMethod("getActionTag").invoke(action)
                if (tag == actionTag.toString()) {
                    return true
                }
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    @Throws(Throwable::class)
    private fun hookPanelClick(cl: ClassLoader) {
        val dispatcher = Class.forName("com.max.common.common.share.ShareChannelDispatcher", false, cl)
        val actionType = Class.forName("com.max.data.model.share.IAction", false, cl)
        val modelType = Class.forName("com.max.data.model.share.BaseActionModel", false, cl)
        val dispatch = dispatcher.getDeclaredMethod("e", Context::class.java, actionType, modelType)
        module.hook(dispatch).intercept { chain ->
            try {
                val action = chain.arg(1)
                val tag = customActionTag(action, cl)
                if (SCREENSHOT_ACTION_TAG == tag && recordedComment() != null) {
                    captureRecordedComment()
                    return@intercept null
                }
                if (REPLY_ACTION_TAG == tag) {
                    triggerReply()
                    return@intercept null
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "评论面板目标点击处理失败: $t")
            }
            chain.proceed()
        }
    }

    private fun customActionTag(action: Any?, cl: ClassLoader): String? {
        if (action == null) {
            return null
        }
        return try {
            val customActionClass = Class.forName(
                "com.max.data.model.share.IAction\$CustomAction", false, cl
            )
            if (!customActionClass.isInstance(action)) {
                return null
            }
            val getActionTag = customActionClass.getMethod("getActionTag")
            getActionTag.invoke(action).toString()
        } catch (t: Throwable) {
            null
        }
    }

    private fun captureRecordedComment() {
        if (!module.isEnabled(App.KEY_SCREENSHOT_ENABLED, true)) {
            return
        }
        val comment = recordedComment()
        if (comment == null) {
            module.logd(Log.WARN, MainModule.TAG, "评论截图目标被点击但没有可用的评论记录")
            return
        }
        val activity = ViewUtils.findActivity(comment)
        if (activity == null || activity.isFinishing) {
            return
        }
        val screenshotHook = ScreenshotCaptureHook.getInstance() ?: return
        module.logd(Log.INFO, MainModule.TAG, "评论分享面板截图目标命中")
        screenshotHook.captureCommentDirectly(activity, comment, ScreenshotCaptureHook.SOURCE_COMMENT)
    }

    private fun hookReplyHandlerCapture(cl: ClassLoader): Boolean {
        return try {
            val adapter = Class.forName(ADAPTER_CLASS, false, cl)
            var hooked = 0
            for (ctor in adapter.declaredConstructors) {
                var lambdaIdx = -1
                val params = ctor.parameterTypes
                for (i in params.indices) {
                    if (sFunction1Class != null && sFunction1Class!!.isAssignableFrom(params[i])) {
                        lambdaIdx = i
                        break
                    }
                }
                if (lambdaIdx < 0) {
                    continue
                }
                val idx = lambdaIdx
                module.hook(ctor).intercept { chain ->
                    try {
                        val lambda = chain.arg(idx)
                        if (lambda != null) {
                            sReplyHandler = WeakReference(lambda)
                            sReplyReceiver = WeakReference(receiverOf(lambda))
                        }
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "记录评论回复回调失败: $t")
                    }
                    chain.proceed()
                }
                hooked++
            }
            if (hooked == 0) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 未找到评论适配器回调参数: $ADAPTER_CLASS")
            }
            hooked > 0
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "评论回复回调捕获安装失败: $t")
            false
        }
    }

    private fun triggerReply() {
        if (!module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)) {
            return
        }
        val handler = replyHandler()
        if (handler == null) {
            module.logd(Log.WARN, MainModule.TAG, "「回复」被点击但没有可用的回复回调")
            toast(recordedComment(), "回复入口已失效，请重新长按评论")
            return
        }
        val receiver = sReplyReceiver?.get()
        if (receiver == null || !receiverAlive(receiver)) {
            module.logd(Log.WARN, MainModule.TAG, "「回复」被点击但评论页已不可用")
            toast(recordedComment(), "评论页已刷新，请重新长按评论")
            return
        }
        val comment = recordedComment()
        val target = commentObjOf(comment)
        if (target == null) {
            module.logd(Log.WARN, MainModule.TAG, "「回复」被点击但评论对象不可用")
            toast(comment, "没有识别到这条评论，请重新长按")
            return
        }
        val root = resolveRootComment(comment, target)
        if (root == null || sReplyIntentClass == null || sCommentObjClass == null) {
            module.logd(Log.WARN, MainModule.TAG, "「回复」被点击但回复目标不完整")
            toast(comment, "回复失败，请重试")
            return
        }
        try {
            val intent = sReplyIntentClass!!.getConstructor(sCommentObjClass, sCommentObjClass)
                .newInstance(root, target)
            handler.javaClass.getMethod("invoke", Any::class.java).invoke(handler, intent)
            module.logd(
                Log.INFO, MainModule.TAG, "评论分享面板「回复」已触发 root="
                        + (if (root === target) "自身" else "主评论")
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "触发评论回复失败: $t")
            toast(comment, "回复失败，请重试")
        }
    }

    private fun receiverAlive(receiver: Any): Boolean {
        return try {
            val added = receiver.javaClass.getMethod("isAdded")
            val result = added.invoke(receiver)
            result !is Boolean || result
        } catch (ignored: NoSuchMethodException) {
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun commentObjOf(view: View?): Any? {
        if (view == null || sCommentObjClass == null) {
            return null
        }
        val tag: Any?
        try {
            tag = view.tag
        } catch (t: Throwable) {
            return null
        }
        return if (sCommentObjClass!!.isInstance(tag)) tag else null
    }

    private fun resolveRootComment(recorded: View?, target: Any?): Any? {
        if (recorded == null || sSubCommentViewClass == null) {
            return target
        }
        var parent: ViewParent? = recorded.parent
        var depth = 0
        while (parent is View && depth++ < 30) {
            val current = parent as View
            if (sSubCommentViewClass!!.isInstance(current)) {
                val main = findMainCommentAbove(current)
                val root = if (main == null) null else commentObjOf(main)
                return root ?: target
            }
            parent = current.parent
        }
        return target
    }

    private fun findMainCommentAbove(start: View): View? {
        var parent: ViewParent? = start.parent
        var depth = 0
        while (parent is View && depth++ < 20) {
            val current = parent as View
            val found = ArrayList<View>()
            collectMainCommentViews(current, found, 0, intArrayOf(0))
            if (found.size == 1) {
                return found[0]
            }
            if (found.size > 1) {
                return null
            }
            parent = current.parent
        }
        return null
    }

    private fun collectMainCommentViews(node: View?, out: MutableList<View>, depth: Int, budget: IntArray) {
        if (node == null || depth > 40 || budget[0] > 2000) {
            return
        }
        budget[0]++
        if (isCommentTextView(node)) {
            if (node.tag != null && !isInsideSubCommentView(node)) {
                out.add(node)
            }
            return
        }
        if (node is ViewGroup) {
            val group = node
            for (i in 0 until group.childCount) {
                collectMainCommentViews(group.getChildAt(i), out, depth + 1, budget)
            }
        }
    }

    private fun isInsideSubCommentView(view: View): Boolean {
        if (sSubCommentViewClass == null) {
            return false
        }
        var parent: ViewParent? = view.parent
        var depth = 0
        while (parent is View && depth++ < 30) {
            if (sSubCommentViewClass!!.isInstance(parent)) {
                return true
            }
            parent = (parent as View).parent
        }
        return false
    }

    private fun hookTapReply(cl: ClassLoader): Int {
        var blocked = 0
        blocked += blockTapReply(cl, ADAPTER_CLASS + "\$f", "onClick", 1)
        blocked += blockTapReply(cl, ADAPTER_CLASS + "\$i", "onClick", 1)
        blocked += blockTapReply(cl, ADAPTER_CLASS + "\$k", "onClick", 1)
        blocked += blockTapReply(cl, ADAPTER_CLASS + "\$q", "b", 3)
        return blocked
    }

    private fun blockTapReply(cl: ClassLoader, className: String, methodName: String, paramCount: Int): Int {
        return try {
            val clazz = Class.forName(className, false, cl)
            var target: Method? = null
            for (method in clazz.declaredMethods) {
                if (methodName == method.name
                    && method.parameterTypes.size == paramCount
                ) {
                    target = method
                    break
                }
            }
            if (target == null) {
                module.logd(
                    Log.WARN, MainModule.TAG, "✘ 单击回复拦截挂点缺失: "
                            + className + "#" + methodName
                )
                return 0
            }
            module.hook(target).intercept { chain ->
                if (module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)) {
                    return@intercept null
                }
                chain.proceed()
            }
            1
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "单击回复拦截安装失败 $className#$methodName: $t"
            )
            0
        }
    }

    companion object {

        private const val SCREENSHOT_ACTION_TAG = "CommentScreenshot"
        private const val SCREENSHOT_ACTION_LABEL = "截图此评论"
        private const val REPLY_ACTION_TAG = "CommentReply"
        private const val REPLY_ACTION_LABEL = "回复"
        private const val REPLY_ICON_NAME = "bbs_sharebutton_edit_tie_46x46"

        private const val ADAPTER_CLASS =
            "com.max.xiaoheihe.module.bbs.adapter.CommentAdapterV2"
        private const val COMMENT_TEXT_CLASS =
            "com.max.xiaoheihe.view.CustomLongPressExpressionTextView"
        private const val SUB_COMMENT_VIEW_CLASS =
            "com.max.xiaoheihe.view.SubCommentView"
        private const val COMMENT_OBJ_CLASS = "com.max.basebbs.bean.BBSCommentObj"
        private const val REPLY_INTENT_CLASS = "hd.b\$c"

        private val FUNCTION1_CLASS_CANDIDATES = arrayOf("vn.l", "xn.l", "wn.l")

        private var sCommentTextClass: Class<*>? = null
        private var sSubCommentViewClass: Class<*>? = null
        private var sCommentObjClass: Class<*>? = null
        private var sReplyIntentClass: Class<*>? = null
        private var sFunction1Class: Class<*>? = null

        @Volatile
        private var sReplyHandler: WeakReference<Any>? = null

        @Volatile
        private var sReplyReceiver: WeakReference<Any>? = null

        private fun tryForName(name: String, cl: ClassLoader): Class<*>? {
            return try {
                Class.forName(name, false, cl)
            } catch (t: Throwable) {
                null
            }
        }

        private fun receiverOf(lambda: Any): Any? {
            var type: Class<*>? = lambda.javaClass
            while (type != null && type != Any::class.java) {
                try {
                    val field: Field = type.getDeclaredField("receiver")
                    field.isAccessible = true
                    return field.get(lambda)
                } catch (ignored: Throwable) {
                    type = type.superclass
                }
            }
            return null
        }

        private fun replyHandler(): Any? {
            val ref = sReplyHandler
            return ref?.get()
        }

        private fun toast(anchor: View?, message: String) {
            if (anchor == null) {
                return
            }
            try {
                val context = anchor.context ?: return
                val app = context.applicationContext
                Toast.makeText(app ?: context, message, Toast.LENGTH_SHORT).show()
            } catch (ignored: Throwable) {
            }
        }

        private fun isCommentTextView(view: View): Boolean {
            val clazz = sCommentTextClass
            if (clazz != null) {
                return clazz.isInstance(view)
            }
            return COMMENT_TEXT_CLASS == view.javaClass.name
        }

        private fun resolvePanelIcon(context: Context?, name: String): Int {
            if (context == null) {
                return 0
            }
            return try {
                context.resources.getIdentifier(
                    name, "drawable", MainModule.TARGET_PKG
                )
            } catch (t: Throwable) {
                0
            }
        }

        private fun recordedComment(): View? {
            return CommentCopyHook.recordedCommentForSharePanel()
        }
    }
}
