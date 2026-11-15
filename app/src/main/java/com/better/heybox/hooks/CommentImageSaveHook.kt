package com.better.heybox.hooks

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.MainModule
import java.lang.ref.WeakReference

class CommentImageSaveHook(private val module: MainModule) {
    private val current = ThreadLocal<List<String>>()
    private val snapshots = ArrayList<Pair<WeakReference<Any>, List<String>>>()

    fun install(cl: ClassLoader) {
        try {
            val forwardType = Class.forName("com.max.data.model.community.CommentForwardModel", false, cl)
            val dispatcher = Class.forName("com.max.common.common.share.ShareChannelDispatcher", false, cl)
            val actionType = Class.forName("com.max.data.model.share.IAction", false, cl)
            val baseModel = Class.forName("com.max.data.model.share.BaseActionModel", false, cl)
            val dispatch = dispatcher.getDeclaredMethod("e", Context::class.java, actionType, baseModel)
            module.hook(dispatch).intercept { chain ->
                val tag = call(chain.arg(1), "getActionTag") as? String
                if (tag != ACTION_TAG) {
                    chain.proceed()
                } else {
                    try {
                        val context = chain.arg(0) as? Context
                        val model = chain.arg(2)
                        val urls = if (model == null) null else snapshotFor(model)
                        if (context != null && !urls.isNullOrEmpty()) {
                            BatchImageSaveHook.start(context, urls, cl)
                        } else if (context != null) {
                            Toast.makeText(context, "图片信息已失效，请重新打开分享菜单", Toast.LENGTH_SHORT).show()
                        }
                    } catch (t: Throwable) {
                        log("评论图片保存失败", t)
                    }
                    null
                }
            }

            val manager = Class.forName("com.max.common.common.share.ShareViewManager", false, cl)
            val forward = Class.forName("com.max.data.model.share.IForwardModel", false, cl)
            val build = manager.getDeclaredMethod("m", Context::class.java, forward, List::class.java)
            module.hook(build).intercept { chain ->
                val result = chain.proceed()
                try {
                    val model = chain.arg(1)
                    val actions = chain.arg(2)
                    if (module.isEnabled(App.KEY_COMMENT_BATCH_IMAGES, true) &&
                        model != null && forwardType.isInstance(model) && actions is MutableList<*>) {
                        val urls = snapshotFor(model) ?: current.get()
                        if (!urls.isNullOrEmpty()) {
                            @Suppress("UNCHECKED_CAST")
                            appendAction(actions as MutableList<Any?>, chain.arg(0) as? Context, cl)
                            remember(model, urls)
                        }
                    }
                } catch (t: Throwable) {
                    log("评论图片保存入口构建失败", t)
                }
                result
            }

            for (name in ADAPTERS) hookAdapter(cl, name, forwardType)
        } catch (t: Throwable) {
            log("评论图片保存入口安装失败", t)
        }
    }

    private fun hookAdapter(cl: ClassLoader, name: String, forwardType: Class<*>) {
        try {
            val adapter = Class.forName(name, false, cl)
            var count = 0
            for (method in adapter.declaredMethods) {
                val types = method.parameterTypes
                val commentIndex = when {
                    types.size == 4 && types[0].simpleName == "LinkInfoObj" &&
                        types[1].simpleName == "BBSCommentObj" && types[2].simpleName == "BBSUserInfoObj" &&
                        types[3] == Boolean::class.javaPrimitiveType -> 1
                    types.size == 4 && types[0].simpleName == "LinkInfoObj" &&
                        types[1].simpleName == "BBSCommentObj" && types[2] == Boolean::class.javaPrimitiveType &&
                        types[3] == String::class.java && name.endsWith(".n") -> 1
                    types.size == 5 && types[0] == View::class.java && types[1].simpleName == "LinkInfoObj" &&
                        types[2].simpleName == "BBSCommentObj" && types[3] == Boolean::class.javaPrimitiveType &&
                        types[4] == String::class.java -> 2
                    else -> continue
                }
                if (name.endsWith("CommentAdapterV2") && method.name !in setOf("C0", "D0", "E0", "G0")) continue
                module.hook(method).intercept { chain ->
                    val previous = current.get()
                    val urls = try { commentImages(chain.arg(commentIndex)) } catch (t: Throwable) {
                        log("提取评论图片失败", t)
                        emptyList()
                    }
                    current.set(urls)
                    try {
                        val result = chain.proceed()
                        try {
                            if (result != null && forwardType.isInstance(result) && urls.isNotEmpty()) remember(result, urls)
                        } catch (t: Throwable) {
                            log("记录评论图片失败", t)
                        }
                        result
                    } finally {
                        if (previous == null) current.remove() else current.set(previous)
                    }
                }
                count++
            }
            if (count == 0) log("评论适配器分享方法未找到: $name", null)
        } catch (t: Throwable) {
            log("评论适配器分享挂点不可用: $name", t)
        }
    }

    private fun commentImages(comment: Any?): List<String> {
        val images = call(comment, "getImgs") as? List<*> ?: return emptyList()
        return images.mapNotNull { image ->
            (call(image, "getUrl") as? String)?.trim()?.takeIf {
                it.startsWith("https://", true) || it.startsWith("http://", true)
            }
        }.distinct()
    }

    private fun remember(model: Any, urls: List<String>) = synchronized(snapshots) {
        val iterator = snapshots.iterator()
        while (iterator.hasNext()) {
            val existing = iterator.next().first.get()
            if (existing == null || existing === model) iterator.remove()
        }
        snapshots.add(WeakReference(model) to urls.toList())
    }

    private fun snapshotFor(model: Any): List<String>? = synchronized(snapshots) {
        var found: List<String>? = null
        val iterator = snapshots.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val existing = entry.first.get()
            if (existing == null) iterator.remove() else if (existing === model) found = entry.second
        }
        found
    }

    private fun appendAction(actions: MutableList<Any?>, context: Context?, cl: ClassLoader) {
        if (actions.any { call(call(it, "getAction"), "getActionTag") == ACTION_TAG }) return
        val actionType = Class.forName("com.max.data.model.share.IAction", false, cl)
        val customType = Class.forName("com.max.data.model.share.IAction\$CustomAction", false, cl)
        val itemType = Class.forName("com.max.data.bean.share.ActionObj", false, cl)
        val icon = context?.resources?.getIdentifier("bbs_sharebutton_download_46x46", "drawable", MainModule.TARGET_PKG) ?: 0
        val action = customType.getConstructor(String::class.java).newInstance(ACTION_TAG)
        val item = itemType.getConstructor(String::class.java, Int::class.javaObjectType, String::class.java,
            String::class.java, String::class.java, String::class.java, actionType)
            .newInstance("批量保存原图", icon, null, null, null, null, action)
        actions.add(item)
    }

    private fun call(receiver: Any?, method: String): Any? = try {
        receiver?.javaClass?.getMethod(method)?.invoke(receiver)
    } catch (_: Throwable) { null }

    private fun log(message: String, t: Throwable?) {
        try { module.logd(Log.WARN, MainModule.TAG, "$message: $t") } catch (_: Throwable) { }
    }

    companion object {
        private const val ACTION_TAG = "BetterHeyboxCommentBatchSaveImages"
        private val ADAPTERS = arrayOf(
            "com.max.xiaoheihe.module.bbs.adapter.CommentAdapterV2",
            "com.max.xiaoheihe.module.bbs.adapter.n"
        )
    }
}
