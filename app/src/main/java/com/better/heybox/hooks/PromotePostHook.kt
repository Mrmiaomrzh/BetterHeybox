package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.App
import com.better.heybox.HeyboxTargets
import com.better.heybox.MainModule
import com.better.heybox.yuki.YukiChain
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class PromotePostHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        HeyboxTargets.install(PromoteDetector.TARGET_BBS_RENDER, this::hookOne)
    }

    private fun hookOne(method: Method) {
        module.hook(method).intercept(this::onRender)
        module.logd(
            Log.INFO, MainModule.TAG,
            "✔ 推广帖 Hook 已安装: ${method.declaringClass.name}#${method.name}/${method.parameterCount}"
        )
    }

    private fun onRender(chain: YukiChain): Any? {
        val args = chain.getArgs()
        val bbsLink = findBbsLink(args)
        val viewHolder = findViewHolder(args)
        try {
            if (bbsLink != null && module.isEnabled(App.KEY_PROMOTE_AD, true) &&
                PromoteDetector.isPromote(bbsLink)
            ) {
                val reason = PromoteDetector.matchReason(bbsLink)
                val detail = if (module.isEnabled(App.KEY_VERBOSE_LOG, false)) {
                    " | " + PromoteDetector.describe(bbsLink)
                } else {
                    ""
                }
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "屏蔽内容[旧 BBS 列表] 原因=" + (reason ?: "推广内容") + detail
                )
                FeedItemHider.hide(FeedItemHider.getItemView(viewHolder))
                return null
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "推广帖判断异常，放行: $t")
        }
        val postFilter = PostFilterHook.get()
        if (postFilter != null && bbsLink != null && postFilter.onRenderBind(bbsLink, viewHolder)) {
            return null
        }
        FeedItemHider.restore(viewHolder)
        return chain.proceed()
    }

    private fun findBbsLink(args: List<Any?>): Any? =
        args.firstOrNull { arg ->
            arg != null && isInstanceNamed(arg.javaClass, PromoteDetector.BBS_LINK_OBJ)
        }

    private fun findViewHolder(args: List<Any?>): Any? =
        args.firstOrNull { arg ->
            arg != null && hasItemView(arg.javaClass) && FeedItemHider.getItemView(arg) != null
        }

    private fun hasItemView(cls: Class<*>): Boolean {
        ITEM_VIEW_CLASSES[cls]?.let { return it }
        val found = try {
            cls.getField("itemView")
            true
        } catch (t: Throwable) {
            false
        }
        ITEM_VIEW_CLASSES[cls] = found
        return found
    }

    private fun isInstanceNamed(cls: Class<*>, name: String): Boolean {
        var walk: Class<*>? = cls
        while (walk != null && walk != Any::class.java) {
            if (name == walk.name) {
                return true
            }
            walk = walk.superclass
        }
        return false
    }

    companion object {
        private val ITEM_VIEW_CLASSES = ConcurrentHashMap<Class<*>, Boolean>()
    }
}
