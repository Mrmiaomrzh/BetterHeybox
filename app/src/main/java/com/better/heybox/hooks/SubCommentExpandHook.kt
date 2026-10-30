package com.better.heybox.hooks

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.better.heybox.App
import com.better.heybox.Checkpoint
import com.better.heybox.MainModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque

class SubCommentExpandHook(private val module: MainModule) {

    private val main = Handler(Looper.getMainLooper())

    private val autoExpanded = LinkedHashSet<String>()

    private val warned = HashSet<String>()

    private var expandLogCount = 0
    private var renderLogCount = 0

    private val queueLock = Any()

    private val pending = ArrayDeque<Pending>()

    private var drainScheduled = false

    private class Pending(val subCommentView: View, val commentId: String)

    fun install(cl: ClassLoader) {
        try {
            val adapterClass = Class.forName(ADAPTER_CLASS, false, cl)
            val holderClass = Class.forName(HOLDER_CLASS, false, cl)
            val commentsObjClass = Class.forName(COMMENTS_OBJ_CLASS, false, cl)
            val commentObjClass = Class.forName(COMMENT_OBJ_CLASS, false, cl)
            val subCommentViewClass = Class.forName(SUB_COMMENT_VIEW_CLASS, false, cl)

            sFieldHolderSub = findField(holderClass, FIELD_HOLDER_SUB)
            sFieldHasMore = findField(subCommentViewClass, FIELD_HAS_MORE)
            sFieldLoading = findField(subCommentViewClass, FIELD_LOADING)
            sFieldTotalList = findField(subCommentViewClass, FIELD_TOTAL_LIST)

            sFieldFooter = findField(subCommentViewClass, FIELD_FOOTER)
            sMethodHasMore = findMethod(subCommentViewClass, METHOD_HAS_MORE)
            sMethodTotalList = findMethod(commentsObjClass, METHOD_TOTAL_LIST)
            sMethodCommentId = findMethod(commentObjClass, METHOD_COMMENT_ID)
            sMethodCommentsObjComment = findMethod(commentsObjClass, METHOD_TOTAL_LIST)
            sMethodCommentObjHasMoreComment = findMethod(commentObjClass, METHOD_HAS_MORE_COMMENT)
            sMethodLoadPageCnt = findMethod(commentsObjClass, METHOD_LOAD_PAGE_CNT)
            sMethodHasMoreSubComments = findMethod(commentsObjClass, METHOD_HAS_MORE_SUB)
            sMethodIsNotEmpty = findStaticStringBool(cl, IS_NOT_EMPTY_CLASS, IS_NOT_EMPTY_METHOD)
            sMethodRenderFooter = subCommentViewClass.getDeclaredMethod(
                METHOD_RENDER_FOOTER, java.lang.Boolean.TYPE
            )
            sMethodRenderFooter!!.isAccessible = true

            val bind = adapterClass.getDeclaredMethod(
                BIND_METHOD,
                holderClass, commentsObjClass
            )
            bind.isAccessible = true
            module.hook(bind).intercept { chain ->
                val holder = chain.arg(0)
                val commentsObj = chain.arg(1)
                val result = chain.proceed()
                try {
                    if (module.isEnabled(App.KEY_COMMENT_AUTO_EXPAND, false)) {
                        onCommentBound(holder, commentsObj)
                    }
                } catch (t: Throwable) {
                    warnOnce("bound", "自动展开回复：绑定后处理异常，已放行: $t")
                }
                result
            }

            val ready = sFieldHolderSub != null
                    && (sFieldHasMore != null || sMethodHasMore != null)
                    && sFieldFooter != null
                    && sMethodTotalList != null
                    && sMethodCommentId != null
            Checkpoint.mark("自动展开回复安装: %b", ready)
            module.logd(
                if (ready) Log.INFO else Log.WARN, MainModule.TAG,
                (if (ready) "✔ 自动展开回复 Hook 已安装：" else "△ 自动展开回复 Hook 已挂，但字段解析不全：")
                        + ADAPTER_CLASS + "#" + BIND_METHOD + "(" + HOLDER_CLASS + ", "
                        + COMMENTS_OBJ_CLASS + ")"
                        + " [hasMore=" + (sFieldHasMore != null || sMethodHasMore != null)
                        + " loading=" + (sFieldLoading != null)
                        + " footer=" + (sFieldFooter != null)
                        + " list=" + (sMethodTotalList != null)
                        + " id=" + (sMethodCommentId != null) + "]"
            )
        } catch (e: ClassNotFoundException) {
            Checkpoint.mark("自动展开回复安装: 跳过（本版本无该挂点）")
            module.logd(
                Log.WARN, MainModule.TAG,
                "△ 自动展开回复 Hook 跳过：当前版本无 " + ADAPTER_CLASS + "#" + BIND_METHOD
                        + "(" + HOLDER_CLASS + ", " + COMMENTS_OBJ_CLASS + ") 挂点"
            )
        } catch (e: NoSuchMethodException) {
            Checkpoint.mark("自动展开回复安装: 跳过（本版本无该挂点）")
            module.logd(
                Log.WARN, MainModule.TAG,
                "△ 自动展开回复 Hook 跳过：当前版本无 " + ADAPTER_CLASS + "#" + BIND_METHOD
                        + "(" + HOLDER_CLASS + ", " + COMMENTS_OBJ_CLASS + ") 挂点"
            )
        } catch (t: Throwable) {
            Checkpoint.mark("自动展开回复安装: 失败")
            module.logd(Log.ERROR, MainModule.TAG, "✘ 自动展开回复 Hook 安装失败", t)
        }
    }

    private fun onCommentBound(holder: Any?, commentsObj: Any?) {
        if (holder == null || commentsObj == null) {
            return
        }
        val raw = readObject(sFieldHolderSub, holder)
        if (raw !is View) {
            return
        }
        val subCommentView = raw
        val commentId = firstCommentId(commentList(commentsObj))
        if (commentId.isNullOrEmpty()) {
            return
        }
        if (isExpanded(commentId)) {
            return
        }
        if (!hasRemainingSubComments(commentsObj)) {
            return
        }
        if (readBoolean(sFieldLoading, subCommentView, false)) {
            return
        }
        if (!hasLoadedSubComments(commentsObj)) {
            enqueueExpand(subCommentView, commentId)
            return
        }
        if (subCommentView.visibility != View.VISIBLE) {
            return
        }
        if (!hasMoreOrUnknown(subCommentView)) {
            return
        }
        enqueueExpand(subCommentView, commentId)
    }

    private fun enqueueExpand(subCommentView: View, commentId: String) {
        val schedule: Boolean
        synchronized(queueLock) {
            if (pending.size >= MAX_PENDING) {
                return
            }
            pending.addLast(Pending(subCommentView, commentId))
            schedule = !drainScheduled
            if (schedule) {
                drainScheduled = true
            }
        }
        if (schedule) {
            main.post { drainPending() }
        }
    }

    private fun drainPending() {
        val next: Pending?
        synchronized(queueLock) {
            next = pending.pollFirst()
        }
        if (next != null) {
            expandPosted(next.subCommentView, next.commentId)
        }
        val more: Boolean
        synchronized(queueLock) {
            more = !pending.isEmpty()
            if (!more) {
                drainScheduled = false
            }
        }
        if (more) {
            main.postDelayed({ drainPending() }, EXPAND_GAP_MS)
        }
    }

    private fun expandPosted(subCommentView: View, commentId: String) {
        try {
            if (!module.isEnabled(App.KEY_COMMENT_AUTO_EXPAND, false)) {
                return
            }
            if (subCommentView.visibility != View.VISIBLE) {
                return
            }
            if (readBoolean(sFieldLoading, subCommentView, false)) {
                return
            }
            if (hasLoadedSubComments(subCommentView) && !hasMoreOrUnknown(subCommentView)) {
                return
            }
            val currentList = readObject(sFieldTotalList, subCommentView)
            if (currentList !is List<*>) {
                return
            }
            val currentId = firstCommentId(currentList)
            if (commentId != currentId) {
                return
            }
            if (!hasLoadedSubComments(subCommentView)) {
                renderFooterAndClick(subCommentView, commentId)
                return
            }
            val footer = resolveFooter(subCommentView)
            if (footer == null || footer.visibility != View.VISIBLE) {
                return
            }
            if (!footer.hasOnClickListeners()) {
                return
            }
            if (!markExpanded(commentId)) {
                return
            }
            footer.performClick()
            logExpanded(commentId)
        } catch (t: Throwable) {
            warnOnce("expand", "自动展开回复：点击 footer 异常，已放行: $t")
        }
    }

    private fun renderFooterAndClick(subCommentView: View, commentId: String): Boolean {
        return try {
            if (sMethodRenderFooter == null) {
                return false
            }
            if (!hasFragmentCommentList(subCommentView)) {
                return false
            }
            if (sFieldHasMore != null) {
                sFieldHasMore!!.setBoolean(subCommentView, true)
            }
            invoke(sMethodRenderFooter, subCommentView, false)
            val footer = resolveFooter(subCommentView)
            if (footer == null || footer.visibility != View.VISIBLE
                || !footer.hasOnClickListeners()
            ) {
                warnOnce("renderFooter", "自动展开回复：footer 补渲染失败（宿主版本可能已变）")
                return false
            }
            if (!markExpanded(commentId)) {
                return false
            }
            logRendered(commentId)
            footer.performClick()
            true
        } catch (t: Throwable) {
            warnOnce("renderFooter", "自动展开回复：footer 补渲染异常，已放行: $t")
            false
        }
    }

    private fun isExpanded(commentId: String): Boolean {
        synchronized(autoExpanded) {
            return autoExpanded.contains(commentId)
        }
    }

    private fun markExpanded(commentId: String): Boolean {
        synchronized(autoExpanded) {
            if (autoExpanded.contains(commentId)) {
                return false
            }
            if (autoExpanded.size >= MAX_TRACKED) {
                val iterator = autoExpanded.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            autoExpanded.add(commentId)
            return true
        }
    }

    private fun logExpanded(commentId: String) {
        val log: Boolean
        synchronized(autoExpanded) {
            log = expandLogCount < MAX_EXPAND_LOGS
            if (log) {
                expandLogCount++
            }
        }
        if (log) {
            module.logv(MainModule.TAG, "自动展开回复：评论 $commentId 已触发「查看更多回复」")
        }
    }

    private fun logRendered(commentId: String) {
        val log: Boolean
        synchronized(autoExpanded) {
            log = renderLogCount < MAX_EXPAND_LOGS
            if (log) {
                renderLogCount++
            }
        }
        if (log) {
            module.logv(MainModule.TAG, "自动展开回复：评论 $commentId 子评论区未渲染，已补渲染 footer")
        }
    }

    private fun warnOnce(key: String, message: String) {
        synchronized(warned) {
            if (!warned.add(key)) {
                return
            }
        }
        module.logd(Log.WARN, MainModule.TAG, message)
    }

    companion object {

        private const val ADAPTER_CLASS =
            "com.max.xiaoheihe.module.bbs.adapter.CommentAdapterV2"
        private const val HOLDER_CLASS = "ii.xf0"
        private const val COMMENTS_OBJ_CLASS = "com.max.basebbs.bean.BBSCommentsObj"
        private const val COMMENT_OBJ_CLASS = "com.max.basebbs.bean.BBSCommentObj"
        private const val SUB_COMMENT_VIEW_CLASS = "com.max.xiaoheihe.view.SubCommentView"
        private const val BIND_METHOD = "m0"

        private const val FIELD_HOLDER_SUB = "f"
        private const val FIELD_HAS_MORE = "p"
        private const val FIELD_LOADING = "s"
        private const val FIELD_TOTAL_LIST = "o"
        private const val FIELD_FOOTER = "m"

        private const val METHOD_HAS_MORE = "u"
        private const val METHOD_TOTAL_LIST = "getComment"
        private const val METHOD_COMMENT_ID = "getCommentid"

        private const val METHOD_HAS_MORE_COMMENT = "hasMoreComment"

        private const val METHOD_HAS_MORE_SUB = "isHasMoreSubComments"

        private const val METHOD_LOAD_PAGE_CNT = "getSubCommentLoadPageCnt"

        private const val METHOD_RENDER_FOOTER = "s"
        private const val IS_NOT_EMPTY_CLASS = "com.max.hbcommon.utils.c"
        private const val IS_NOT_EMPTY_METHOD = "y"

        private const val EXPAND_BUTTON_CLASS =
            "com.max.hbcommon.component.ExpandMoreButton"

        private const val MAX_TRACKED = 400

        private const val MAX_EXPAND_LOGS = 5

        private const val MAX_FALLBACK_DEPTH = 3

        private const val EXPAND_GAP_MS = 250L

        private const val MAX_PENDING = 80

        @Volatile
        private var sFieldHolderSub: Field? = null

        @Volatile
        private var sFieldHasMore: Field? = null

        @Volatile
        private var sFieldLoading: Field? = null

        @Volatile
        private var sFieldTotalList: Field? = null

        @Volatile
        private var sFieldFooter: Field? = null

        @Volatile
        private var sMethodHasMore: Method? = null

        @Volatile
        private var sMethodTotalList: Method? = null

        @Volatile
        private var sMethodCommentId: Method? = null

        @Volatile
        private var sMethodCommentsObjComment: Method? = null

        @Volatile
        private var sMethodCommentObjHasMoreComment: Method? = null

        @Volatile
        private var sMethodRenderFooter: Method? = null

        @Volatile
        private var sMethodLoadPageCnt: Method? = null

        @Volatile
        private var sMethodIsNotEmpty: Method? = null

        @Volatile
        private var sMethodHasMoreSubComments: Method? = null

        private fun hasMoreOrUnknown(subCommentView: View): Boolean {
            val read = rawHasMore(subCommentView)
            return read == null || read
        }

        private fun hasRemainingSubComments(commentsObj: Any?): Boolean {
            val list = commentList(commentsObj)
            if (list.isNullOrEmpty() || list[0] == null) {
                return false
            }
            val text = invoke(sMethodCommentObjHasMoreComment, list[0])
            if (text is String && text.isNotEmpty()) {
                val notEmpty = invoke(sMethodIsNotEmpty, null, text)
                if (notEmpty is Boolean) {
                    return notEmpty
                }
                return true
            }
            if (invoke(sMethodHasMoreSubComments, commentsObj) != null) {
                return java.lang.Boolean.TRUE == invoke(sMethodHasMoreSubComments, commentsObj)
            }
            return java.lang.Boolean.TRUE == invoke(sMethodHasMoreSubComments, commentsObj)
        }

        private fun hasLoadedSubComments(commentsObj: Any?): Boolean {
            val list = commentList(commentsObj)
            return list != null && list.size > 1 && list[0] != null
        }

        private fun invoke(method: Method?, target: Any?, vararg args: Any?): Any? {
            if (method == null) {
                return null
            }
            return try {
                method.invoke(target, *args)
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun hasLoadedSubComments(subCommentView: View): Boolean {
            val list = readObject(sFieldTotalList, subCommentView)
            return list is List<*> && list.size > 1
        }

        private fun hasFragmentCommentList(subCommentView: View): Boolean {
            val list = readObject(sFieldTotalList, subCommentView)
            return list is List<*> && list.isNotEmpty() && list[0] != null
        }

        private fun resolveFooter(subCommentView: View): View? {
            val footer = readObject(sFieldFooter, subCommentView)
            if (footer is View && footer.parent === subCommentView) {
                return footer
            }
            if (subCommentView is ViewGroup) {
                val group = subCommentView
                for (i in group.childCount - 1 downTo 0) {
                    val child = group.getChildAt(i)
                    if (child != null && containsExpandButton(child, MAX_FALLBACK_DEPTH)) {
                        return child
                    }
                }
            }
            return null
        }

        private fun containsExpandButton(view: View?, depth: Int): Boolean {
            if (view == null) {
                return false
            }
            if (EXPAND_BUTTON_CLASS == view.javaClass.name) {
                return true
            }
            if (depth <= 0 || view !is ViewGroup) {
                return false
            }
            val group = view
            for (i in 0 until group.childCount) {
                if (containsExpandButton(group.getChildAt(i), depth - 1)) {
                    return true
                }
            }
            return false
        }

        private fun hasMore(subCommentView: View): Boolean {
            val read = rawHasMore(subCommentView)
            return read != null && read
        }

        private fun rawHasMore(subCommentView: View): Boolean? {
            val field = sFieldHasMore
            if (field != null) {
                try {
                    return field.getBoolean(subCommentView)
                } catch (ignored: Throwable) {
                }
            }
            val method = sMethodHasMore
            if (method != null) {
                try {
                    val value = method.invoke(subCommentView)
                    return value as? Boolean
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        private fun commentList(commentsObj: Any?): List<*>? {
            val method = sMethodTotalList
            if (method == null || commentsObj == null) {
                return null
            }
            return try {
                val result = method.invoke(commentsObj)
                result as? List<*>
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun firstCommentId(list: List<*>?): String? {
            if (list.isNullOrEmpty()) {
                return null
            }
            val first = list[0]
            val method = sMethodCommentId
            if (first == null || method == null) {
                return null
            }
            return try {
                val id = method.invoke(first)
                id as? String
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun findField(cls: Class<*>, name: String): Field? {
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    val field = c.getDeclaredField(name)
                    field.isAccessible = true
                    return field
                } catch (ignored: NoSuchFieldException) {
                } catch (ignored: Throwable) {
                }
                c = c.superclass
            }
            return null
        }

        private fun findMethod(cls: Class<*>, name: String): Method? {
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    val method = c.getDeclaredMethod(name)
                    method.isAccessible = true
                    return method
                } catch (ignored: NoSuchMethodException) {
                } catch (ignored: Throwable) {
                }
                c = c.superclass
            }
            return null
        }

        private fun findStaticStringBool(cl: ClassLoader, className: String, methodName: String): Method? {
            return try {
                val cls = Class.forName(className, false, cl)
                for (method in cls.declaredMethods) {
                    if (methodName != method.name) {
                        continue
                    }
                    val params = method.parameterTypes
                    if (params.size == 1 && params[0] === String::class.java
                        && method.returnType === java.lang.Boolean.TYPE
                    ) {
                        method.isAccessible = true
                        return method
                    }
                }
                null
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun readObject(field: Field?, target: Any?): Any? {
            if (field == null || target == null) {
                return null
            }
            return try {
                field.get(target)
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun readBoolean(field: Field?, target: Any?, defaultValue: Boolean): Boolean {
            if (field == null || target == null) {
                return defaultValue
            }
            return try {
                field.getBoolean(target)
            } catch (ignored: Throwable) {
                defaultValue
            }
        }
    }
}
