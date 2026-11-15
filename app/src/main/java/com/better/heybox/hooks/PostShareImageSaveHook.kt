package com.better.heybox.hooks

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.better.heybox.MainModule
import java.util.IdentityHashMap

class PostShareImageSaveHook(private val module: MainModule) {
    private val snapshots = IdentityHashMap<Any, List<String>>()

    fun install(cl: ClassLoader) {
        capturePost(cl, "com.max.xiaoheihe.accelworld.BBSShareDialogManager", "Z", "com.max.hbutils.bean.Result", false)
        capturePost(cl, "com.max.xiaoheihe.accelworld.LinkPostShareDialogManager", "A", "com.max.data.model.community.CommunityLinkPostModel", true)
        try {
            val manager = Class.forName("com.max.common.common.share.ShareViewManager", false, cl)
            val forward = Class.forName("com.max.data.model.share.IForwardModel", false, cl)
            val build = manager.getDeclaredMethod("m", Context::class.java, forward, List::class.java)
            module.hook(build).intercept { chain ->
                val result = chain.proceed()
                try {
                    val model = chain.arg(1)
                    val actions = chain.arg(2)
                    if (model != null && actions is MutableList<*> && imagesFor(model, chain.arg(0) as? Context).isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        appendAction(actions as MutableList<Any?>, chain.arg(0) as? Context, cl)
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "帖子批量保存入口构建失败: $t")
                }
                result
            }
            val dispatcher = Class.forName("com.max.common.common.share.ShareChannelDispatcher", false, cl)
            val actionType = Class.forName("com.max.data.model.share.IAction", false, cl)
            val modelType = Class.forName("com.max.data.model.share.BaseActionModel", false, cl)
            val dispatch = dispatcher.getDeclaredMethod("e", Context::class.java, actionType, modelType)
            module.hook(dispatch).intercept { chain ->
                val action = chain.arg(1)
                val tag = try { action?.javaClass?.getMethod("getActionTag")?.invoke(action) as? String } catch (_: Throwable) { null }
                if (tag == ACTION_TAG) {
                    val context = chain.arg(0) as? Context
                    val model = chain.arg(2)
                    val urls = if (model == null) emptyList() else imagesFor(model, context)
                    if (context != null && urls.isNotEmpty()) {
                        BatchImageSaveHook.start(context, urls, cl)
                    } else if (context != null) {
                        Toast.makeText(context, "图片信息已失效，请重新打开分享菜单", Toast.LENGTH_SHORT).show()
                    }
                    return@intercept null
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "帖子批量保存分享入口安装失败: $t")
        }
    }

    private fun capturePost(cl: ClassLoader, managerName: String, methodName: String, fieldType: String, modern: Boolean) {
        try {
            val manager = Class.forName(managerName, false, cl)
            val modelType = Class.forName(fieldType, false, cl)
            val field = manager.declaredFields.single { it.type == modelType }.apply { isAccessible = true }
            val forward = Class.forName("com.max.data.model.community.LinkPostForwardModel", false, cl)
            val method = manager.declaredMethods.single { it.name == methodName && it.returnType == forward }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val source = chain.instanceOrNull?.let { field.get(it) }
                    val urls = if (modern) modernImages(source) else legacyImages(source)
                    if (result != null) synchronized(snapshots) {
                        if (snapshots.size >= 64) snapshots.clear()
                        snapshots[result] = urls
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "提取帖子图片失败: $t")
                }
                result
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "帖子分享模型挂点不可用 $managerName: $t")
        }
    }

    private fun imagesFor(model: Any, context: Context?): List<String> {
        val captured = synchronized(snapshots) { snapshots[model] } ?: return emptyList()
        val linkId = call(model, "getLinkId")?.toString()
        val current = if (context == null) emptyList() else PostImageSaveButtonHook.currentImages(context, linkId)
        return (captured + current).distinct()
    }

    private fun legacyImages(result: Any?): List<String> {
        val tree = call(result, "getResult")
        val link = call(tree, "getLink")
        return urls(call(link, "getImgs") as? List<*>)
    }

    private fun modernImages(post: Any?): List<String> {
        val section = call(post, "getPostContentSection")
        val content = call(section, "getContent")
        val items = call(content, "getContentModelList") as? List<*> ?: return emptyList()
        return urls(items.filter { it?.javaClass?.name == "com.max.data.model.community.TypedTextContentModel\$ImageTextContent" })
    }

    private fun urls(items: List<*>?): List<String> = items.orEmpty().mapNotNull {
        (call(it, "getUrl") as? String)?.trim()?.takeIf { url ->
            url.startsWith("https://", true) || url.startsWith("http://", true)
        }
    }.distinct()

    private fun call(receiver: Any?, method: String): Any? = try {
        receiver?.javaClass?.getMethod(method)?.invoke(receiver)
    } catch (_: Throwable) { null }

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

    companion object { private const val ACTION_TAG = "BetterHeyboxPostBatchSaveImages" }
}
