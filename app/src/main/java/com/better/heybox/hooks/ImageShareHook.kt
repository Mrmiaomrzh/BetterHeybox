package com.better.heybox.hooks

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.better.heybox.App
import com.better.heybox.LogRecorder
import com.better.heybox.MainModule
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ImageShareHook(private val module: MainModule) {

    @Volatile
    private var pendingImageShareMediaData: Any? = null
    private val showingBatchDialog = ThreadLocal<Boolean>()
    private val batchSaveRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install(cl: ClassLoader) {
        BatchImageSaveHook.handler = { context, urls, loader -> startBatchSaveUrls(context, urls, loader) }
        hookImageLongPressMenu(cl)
    }

    private fun hookImageLongPressMenu(cl: ClassLoader) {
        try {
            val customizer: Class<*> = Class.forName(
                "com.max.xiaoheihe.utils.imageviewer.ui.BaseResUICustomizer", false, cl
            )
            val mediaData: Class<*> = Class.forName(
                "com.max.xiaoheihe.utils.imageviewer.MediaData", false, cl
            )
            val getLocalHandlers = customizer.getDeclaredMethod("r", customizer, mediaData)
            module.hook(getLocalHandlers).intercept { chain ->
                val result = chain.proceed()
                if (result is List<*>) {
                    val currentMediaData = chain.arg(1)
                    appendBatchSaveHandler(result, currentMediaData, cl)
                    appendSystemShareHandler(result, currentMediaData, cl)
                } else {
                    module.logd(
                        Log.WARN, MainModule.TAG, "图片长按处理器返回值不是 List: " +
                                (if (result == null) "null" else result.javaClass.name)
                    )
                }
                result
            }

            val openShare = customizer.getDeclaredMethod("h0", mediaData)
            module.hook(openShare).intercept { chain ->
                pendingImageShareMediaData = chain.arg(0)
                val pending = pendingImageShareMediaData
                module.logd(
                    Log.INFO, MainModule.TAG, "图片长按分享入口命中: mediaData=" +
                            (if (pending == null) "null" else pending.javaClass.name)
                )
                chain.proceed()
            }

            val dialogBuilder: Class<*> = Class.forName(
                "com.max.xiaoheihe.accelworld.HBShareDialog\$a", false, cl
            )
            val addHandlers = dialogBuilder.getDeclaredMethod("c", List::class.java)
            module.hook(addHandlers).intercept { chain ->
                val pending = pendingImageShareMediaData
                if (pending == null) {
                    chain.proceed()
                } else {
                    pendingImageShareMediaData = null
                    val handlers = chain.arg(0)
                    if (handlers is List<*>) {
                        module.logd(
                            Log.INFO, MainModule.TAG,
                            "HBShareDialog 处理器列表命中（图片会话）: count=" + handlers.size
                        )
                        appendBatchSaveHandler(handlers, pending, cl)
                        appendSystemShareHandler(handlers, pending, cl)
                    }
                    chain.proceed()
                }
            }

            val shareDialog: Class<*> = Class.forName(
                "com.max.xiaoheihe.accelworld.HBShareDialog", false, cl
            )
            val showDialog = shareDialog.getDeclaredMethod("g")
            module.hook(showDialog).intercept { chain ->
                val dialog = chain.instanceOrNull
                if (isImageForward(readForwardModel(dialog, cl), cl)) {
                    val actions = readShareDialogActions(dialog)
                    if (actions is List<*>) {
                        appendSystemShareAction(actions, cl, findDialogContext(dialog))
                    }
                }
                val previous = showingBatchDialog.get()
                showingBatchDialog.set(hasBatchHandler(dialog))
                try { chain.proceed() } finally { showingBatchDialog.set(previous) }
            }

            val shareViewManager: Class<*> = Class.forName(
                "com.max.common.common.share.ShareViewManager", false, cl
            )
            val forwardModel: Class<*> = Class.forName(
                "com.max.data.model.share.IForwardModel", false, cl
            )
            val buildForwardActions = shareViewManager.getDeclaredMethod(
                "m", Context::class.java, forwardModel, List::class.java
            )
            module.hook(buildForwardActions).intercept { chain ->
                val result = chain.proceed()
                val actions = chain.arg(2)
                if (actions is List<*> && isImageForward(chain.arg(1), cl)) {
                    val ctxArg = chain.arg(0)
                    val context = ctxArg as? Context
                    if (showingBatchDialog.get() == true) appendBatchSaveAction(actions, cl, context)
                    appendSystemShareAction(actions, cl, context)
                }
                result
            }
            module.logd(
                Log.INFO, MainModule.TAG,
                "✔ 图片长按真实分享面板 Hook 已安装: BaseResUICustomizer.r"
            )
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 图片长按真实分享面板 Hook 失败", t)
        }
    }

    private fun hasBatchHandler(dialog: Any?): Boolean {
        if (dialog == null) return false
        return try {
            dialog.javaClass.declaredFields.any { field ->
                field.isAccessible = true
                val list = field.get(dialog) as? List<*> ?: return@any false
                list.any { item ->
                    if (item == null) false else try {
                        item.javaClass.getMethod("getTarget").invoke(item) == "BatchSaveImages"
                    } catch (_: Throwable) { false }
                }
            }
        } catch (_: Throwable) { false }
    }

    // 1153: Components.e().c() is the data set shown by the image viewer.
    private fun snapshotViewerImages(cl: ClassLoader, current: Any?): List<Any> {
        val components = Class.forName("com.heybox.imageviewer.core.Components", false, cl)
        val singleton = components.declaredFields.first { it.type == components }.apply { isAccessible = true }.get(null)
        val provider = components.getMethod("e").invoke(singleton)
        val providerType = Class.forName("com.heybox.imageviewer.core.a", false, cl)
        val items = providerType.getMethod("c").invoke(provider) as? List<*> ?: return emptyList()
        if (items.none { it === current }) return emptyList()
        val mediaType = Class.forName("com.max.xiaoheihe.utils.imageviewer.MediaData", false, cl)
        return items.filterNotNull().filter { mediaType.isInstance(it) }.toList()
    }

    private fun appendBatchSaveHandler(handlers: List<*>, mediaData: Any?, cl: ClassLoader) {
        try {
            if (handlers.filterNotNull().any { it.javaClass.getMethod("getTarget").invoke(it) == "BatchSaveImages" }) return
            val callbackType = findLocalHandlerCallback(cl) ?: return
            val localHandler = Class.forName("com.max.common.common.share.local.c", false, cl)
            val callback = Proxy.newProxyInstance(cl, arrayOf(callbackType)) { _, method, _ ->
                if (method.name == "invoke") startBatchSave(mediaData, cl)
                if (method.returnType == Void.TYPE) null else batchKotlinUnit(cl)
            }
            val handler = localHandler.getConstructor(String::class.java, callbackType)
                .newInstance("BatchSaveImages", callback)
            @Suppress("UNCHECKED_CAST")
            (handlers as MutableList<Any?>).add(handler)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "追加批量保存处理器失败", t)
        }
    }

    private fun batchKotlinUnit(cl: ClassLoader): Any? {
        for (name in arrayOf("kotlin.a2", "kotlin.b2", "kotlin.Unit")) {
            try {
                val type = Class.forName(name, false, cl)
                val field = type.declaredFields.first { it.type == type && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                field.isAccessible = true
                return field.get(null)
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun appendBatchSaveAction(actions: List<*>, cl: ClassLoader, context: Context?) {
        try {
            if (actions.filterNotNull().any {
                val action = it.javaClass.getMethod("getAction").invoke(it)
                action != null && action.javaClass.getMethod("getActionTag").invoke(action) == "BatchSaveImages"
            }) return
            val actionType = Class.forName("com.max.data.model.share.IAction", false, cl)
            val custom = Class.forName("com.max.data.model.share.IAction\$CustomAction", false, cl)
                .getConstructor(String::class.java).newInstance("BatchSaveImages")
            val action = Class.forName("com.max.data.bean.share.ActionObj", false, cl).getConstructor(
                String::class.java, Integer::class.javaObjectType, String::class.java,
                String::class.java, String::class.java, String::class.java, actionType
            ).newInstance("批量保存原图", resolveShareArrowIcon(context), null, null, null, null, custom)
            @Suppress("UNCHECKED_CAST")
            (actions as MutableList<Any?>).add(action)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "追加批量保存动作失败", t)
        }
    }

    private fun startBatchSave(current: Any?, cl: ClassLoader) {
        try {
            if (current == null) return
            val context = current.javaClass.getMethod("n").invoke(current) as Context
            val images = snapshotViewerImages(cl, current)
            if (images.isEmpty()) {
                Toast.makeText(context, "无法获取当前图片列表", Toast.LENGTH_SHORT).show()
                return
            }
            startBatchSaveImages(context, images, cl)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "启动批量保存失败", t)
        }
    }

    private fun startBatchSaveUrls(context: Context, urls: List<String>, cl: ClassLoader) {
        try {
            val ctor = Class.forName("com.max.xiaoheihe.utils.imageviewer.MediaData", false, cl)
                .getConstructor(Context::class.java, java.lang.Long.TYPE, String::class.java)
            val images = urls.filter { it.isNotBlank() }.mapIndexed { index, url ->
                ctor.newInstance(context.applicationContext ?: context, index.toLong(), url)
            }
            if (images.isEmpty()) {
                Toast.makeText(context, "当前帖子没有可保存的图片", Toast.LENGTH_SHORT).show()
                return
            }
            startBatchSaveImages(context, images, cl)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "启动帖子批量保存失败", t)
            Toast.makeText(context, "无法启动图片保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBatchSaveImages(context: Context, images: List<Any>, cl: ClassLoader) {
        try {
            val appContext = context.applicationContext ?: context
            if (!batchSaveRunning.compareAndSet(false, true)) {
                Toast.makeText(context, "正在保存图片，请稍候", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                Thread({
                    try { saveBatch(appContext, images, cl) } finally { batchSaveRunning.set(false) }
                }, "BetterHeybox-batch-save").start()
            } catch (t: Throwable) {
                batchSaveRunning.set(false)
                throw t
            }
            Toast.makeText(context, "开始批量保存原图（${images.size} 张）", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "启动批量保存失败", t)
        }
    }

    private fun saveBatch(context: Context, images: List<Any>, cl: ClassLoader) {
        var success = 0
        for (media in images) {
            var file: File? = null
            try {
                val type = media.javaClass
                val current = type.getMethod("U").invoke(media) as? String ?: error("图片 URL 为空")
                val cached = type.getMethod("j").invoke(media) as? String
                val isOrigin = type.getMethod("m").invoke(media) as Boolean
                val url = when {
                    isOrigin -> current
                    !cached.isNullOrBlank() -> cached
                    else -> {
                        val service = Class.forName("com.max.xiaoheihe.network.i", false, cl).getMethod("a").invoke(null)
                        val api = Class.forName("com.max.xiaoheihe.network.e", false, cl)
                        val observable = api.getMethod("N9", String::class.java).invoke(service, current)
                        // Subscribe off the batch thread so a stalled host request cannot block the whole batch.
                        val rx = Class.forName("io.reactivex.z", false, cl)
                        val schedulerType = Class.forName("io.reactivex.h0", false, cl)
                        val scheduler = Class.forName("io.reactivex.schedulers.b", false, cl).getMethod("d").invoke(null)
                        val async = rx.getMethod("I5", schedulerType).invoke(observable, scheduler)
                        val timed = rx.getMethod("E6", java.lang.Long.TYPE, java.util.concurrent.TimeUnit::class.java)
                            .invoke(async, 20L, java.util.concurrent.TimeUnit.SECONDS)
                        val result = rx.getMethod("o").invoke(timed)
                        val info = result.javaClass.getMethod("getResult").invoke(result) ?: error("原图信息为空")
                        info.javaClass.getMethod("getImgs").invoke(info) as? String ?: error("原图 URL 为空")
                    }
                }
                val original = if (url.isBlank() && !isOrigin) originalFromKnownThumbnail(current)
                    ?: error("原图接口未返回地址") else url
                file = downloadOriginalImage(context, original)
                if (publishBatchImage(context, file) == null) error("写入相册失败")
                success++
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "批量保存单张图片失败", t)
            } finally {
                file?.delete()
            }
        }
        val saved = success
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, "原图保存完成：成功 $saved 张，失败 ${images.size - saved} 张", Toast.LENGTH_LONG).show()
        }
    }

    // This unsigned max-c thumbnail query only applies an image transformation.
    // Never strip other query parameters, which may contain authentication or signatures.
    private fun originalFromKnownThumbnail(url: String): String? {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme != "https" || !(host == "max-c.com" || host.endsWith(".max-c.com"))) return null
        if (uri.lastPathSegment?.startsWith("thumb.", ignoreCase = true) == true) return null
        val query = uri.encodedQuery ?: return null
        val thumbnail = query.matches(Regex("imageMogr2/auto-orient/ignore-error/1/(?:format/webp/)?thumbnail/[0-9]+x[0-9]+(?:>|%3[eE])"))
        val webpConversion = query == "imageMogr2/format/webp/quality/75/ignore-error/1/auto-orient"
        if (!thumbnail && !webpConversion) return null
        return url.substringBefore('?')
    }

    private fun downloadOriginalImage(context: Context, raw: String): File {
        fun isHttp(value: String) = value.startsWith("https://") || value.startsWith("http://")
        val candidates = if (isHttp(raw)) listOf(raw) else try {
            val obj = org.json.JSONObject(raw)
            listOf(obj.optString("normal", ""), obj.optString("backup", ""))
                .filter { isHttp(it) }.distinct()
        } catch (_: org.json.JSONException) { emptyList() }
        if (candidates.isEmpty()) error("不支持的原图 URL")
        var failure: Exception? = null
        for (candidate in candidates) {
            try { return downloadBatchImage(context, candidate) }
            catch (e: Exception) { failure = e }
        }
        throw failure ?: IllegalStateException("原图下载失败")
    }

    private fun downloadBatchImage(context: Context, imageUrl: String): File {
        val connection = URL(imageUrl).openConnection() as HttpURLConnection
        var tmp: File? = null
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 heybox")
            connection.setRequestProperty("Referer", "https://api.xiaoheihe.cn/")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val dir = File(context.cacheDir, "betterheybox-batch")
            if (!dir.exists() && !dir.mkdirs()) error("无法创建缓存目录")
            tmp = File.createTempFile("image-", ".tmp", dir)
            connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() == 0L) error("图片为空")
            val ext = sniffImageExtension(tmp) ?: run {
                val mime = connection.contentType?.substringBefore(';')?.lowercase(Locale.ROOT)
                when (mime) {
                    "image/heic" -> "heic"
                    "image/heif" -> "heif"
                    else -> error("无法识别图片格式")
                }
            }
            val renamed = File(dir, tmp.nameWithoutExtension + "." + ext)
            if (!tmp.renameTo(renamed)) error("无法准备图片文件")
            tmp = null
            return renamed
        } finally {
            tmp?.delete()
            connection.disconnect()
        }
    }

    private fun publishBatchImage(context: Context, file: File): Uri? {
        var uri: Uri? = null
        try {
            if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return null
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, guessMimeType(file.name))
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BetterHeybox")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val output = context.contentResolver.openOutputStream(uri) ?: error("无法打开相册")
            output.use { file.inputStream().use { input -> input.copyTo(it) } }
            if (Build.VERSION.SDK_INT >= 29) {
                val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                if (context.contentResolver.update(uri, published, null, null) != 1) error("无法完成相册发布")
            }
            return uri
        } catch (t: Throwable) {
            uri?.let { try { context.contentResolver.delete(it, null, null) } catch (_: Throwable) {} }
            module.logd(Log.WARN, MainModule.TAG, "批量写入相册失败", t)
            return null
        }
    }

    private fun appendSystemShareAction(actions: List<*>, cl: ClassLoader, context: Context?) {
        try {
            if (!module.isEnabled(App.KEY_SYSTEM_SHARE, true)) {
                return
            }
            for (actionObject in actions) {
                if (actionObject == null) {
                    continue
                }
                val getAction = actionObject.javaClass.getMethod("getAction")
                val action = getAction.invoke(actionObject)
                val getActionTag = action!!.javaClass.getMethod("getActionTag")
                if ("SystemShare" == stringify(getActionTag.invoke(action))) {
                    return
                }
            }

            val actionObjClass: Class<*> = Class.forName(
                "com.max.data.bean.share.ActionObj", false, cl
            )
            val actionClass: Class<*> = Class.forName(
                "com.max.data.model.share.IAction", false, cl
            )
            val customActionClass: Class<*> = Class.forName(
                "com.max.data.model.share.IAction\$CustomAction", false, cl
            )
            val customAction = customActionClass.getConstructor(String::class.java)
                .newInstance("SystemShare")

            val actionObject = actionObjClass.getConstructor(
                String::class.java, Integer::class.javaObjectType, String::class.java,
                String::class.java, String::class.java, String::class.java, actionClass
            ).newInstance(
                "系统分享", resolveShareArrowIcon(context), null, null, null, null, customAction
            )
            @Suppress("UNCHECKED_CAST")
            val mutableActions = actions as MutableList<Any?>
            mutableActions.add(actionObject)
            module.logd(
                Log.INFO, MainModule.TAG,
                "图片长按菜单动作已追加系统分享: count=" + mutableActions.size
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "追加图片系统分享动作失败", t)
        }
    }

    private fun appendSystemShareHandler(handlers: List<*>, mediaData: Any?, cl: ClassLoader) {
        try {
            if (!module.isEnabled(App.KEY_SYSTEM_SHARE, true)) {
                return
            }
            for (handler in handlers) {
                if (handler == null) {
                    continue
                }
                val getTarget = handler.javaClass.getMethod("getTarget")
                if ("SystemShare" == stringify(getTarget.invoke(handler))) {
                    return
                }
            }

            val localHandler: Class<*> = Class.forName(
                "com.max.common.common.share.local.c", false, cl
            )
            val callbackType = findLocalHandlerCallback(cl)
            if (callbackType == null) {
                module.logd(
                    Log.WARN, MainModule.TAG, "未找到本地分享回调接口，跳过系统分享处理器"
                )
                return
            }
            val callback = InvocationHandler { _, method, _ ->
                if ("invoke" == method.name) {
                    module.logd(Log.INFO, MainModule.TAG, "图片系统分享处理器已命中")
                    shareImageWithSystemChooser(mediaData)
                }
                if (method.returnType === Void.TYPE) {
                    null
                } else {
                    readKotlinUnit(cl)
                }
            }
            val callbackProxy = Proxy.newProxyInstance(
                cl, arrayOf<Class<*>>(callbackType), callback
            )
            val action = localHandler.getConstructor(String::class.java, callbackType)
                .newInstance("SystemShare", callbackProxy)
            @Suppress("UNCHECKED_CAST")
            val mutableHandlers = handlers as MutableList<Any?>
            mutableHandlers.add(action)
            module.logd(
                Log.INFO, MainModule.TAG,
                "图片长按处理器已追加系统分享: count=" + mutableHandlers.size
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "追加图片系统分享处理器失败", t)
        }
    }

    private fun readKotlinUnit(cl: ClassLoader): Any? {
        return try {
            val unit: Class<*> = Class.forName("kotlin.b2", false, cl)
            val instance = try {
                unit.getDeclaredField("f140421a")
            } catch (ignored: NoSuchFieldException) {
                unit.getDeclaredField("f140881a")
            }
            instance.setAccessible(true)
            instance.get(null)
        } catch (t: Throwable) {
            null
        }
    }

    private fun readShareDialogActions(dialog: Any?): Any? {
        if (dialog == null) {
            return null
        }
        var actions = readField(dialog, "f83135h")
        if (actions == null) {
            actions = readField(dialog, "f83116h")
        }
        return actions
    }

    private fun findLocalHandlerCallback(cl: ClassLoader): Class<*>? {
        try {
            val localHandler: Class<*> = Class.forName(
                "com.max.common.common.share.local.c", false, cl
            )
            for (ctor in localHandler.declaredConstructors) {
                val params = ctor.parameterTypes
                if (params.size == 2 && params[0] === String::class.java && params[1].isInterface) {
                    return params[1]
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "解析本地分享回调接口失败", t)
        }
        return null
    }

    private fun findDialogContext(dialog: Any?): Context? {
        if (dialog == null) {
            return null
        }
        try {
            for (f in dialog.javaClass.declaredFields) {
                if (f.type === Context::class.java) {
                    f.setAccessible(true)
                    val v = f.get(dialog)
                    return if (v is Context) v else null
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun stringify(value: Any?): String = if (value == null) "null" else value.toString()

    private fun readField(target: Any?, name: String): Any? {
        if (target == null) {
            return null
        }
        try {
            val field = target.javaClass.getDeclaredField(name)
            field.setAccessible(true)
            return field.get(target)
        } catch (ignored: Throwable) {
            return null
        }
    }

    companion object {

        private fun resolveShareArrowIcon(context: Context?): Int {
            if (context == null) {
                return 0
            }
            return try {
                context.resources.getIdentifier(
                    "bbs_sharebutton_forward_46x46", "drawable", MainModule.TARGET_PKG
                )
            } catch (t: Throwable) {
                0
            }
        }

        private fun isImageForward(forward: Any?, cl: ClassLoader): Boolean {
            if (forward == null) {
                return false
            }
            return try {
                Class.forName("com.max.data.model.common.ImageForwardModel", false, cl)
                    .isInstance(forward)
            } catch (t: Throwable) {
                false
            }
        }

        private fun readForwardModel(dialog: Any?, cl: ClassLoader): Any? {
            if (dialog == null) {
                return null
            }
            try {
                val forwardType: Class<*> = Class.forName(
                    "com.max.data.model.share.IForwardModel", false, cl
                )
                for (f in dialog.javaClass.declaredFields) {
                    if (f.type === forwardType) {
                        f.setAccessible(true)
                        return f.get(dialog)
                    }
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        private fun sniffImageExtension(file: File): String? {
            try {
                FileInputStream(file).use { input ->
                    val head = ByteArray(12)
                    val read = input.read(head)
                    if (read >= 3 && (head[0].toInt() and 0xFF) == 0xFF &&
                        (head[1].toInt() and 0xFF) == 0xD8 && (head[2].toInt() and 0xFF) == 0xFF
                    ) {
                        return "jpg"
                    }
                    if (read >= 8 && (head[0].toInt() and 0xFF) == 0x89 &&
                        head[1].toInt() == 0x50 && head[2].toInt() == 0x4E && head[3].toInt() == 0x47
                    ) {
                        return "png"
                    }
                    if (read >= 6 && head[0].toInt() == 'G'.code && head[1].toInt() == 'I'.code &&
                        head[2].toInt() == 'F'.code && head[3].toInt() == '8'.code
                    ) {
                        return "gif"
                    }
                    if (read >= 12 && head[0].toInt() == 'R'.code && head[1].toInt() == 'I'.code &&
                        head[2].toInt() == 'F'.code && head[3].toInt() == 'F'.code &&
                        head[8].toInt() == 'W'.code && head[9].toInt() == 'E'.code &&
                        head[10].toInt() == 'B'.code && head[11].toInt() == 'P'.code
                    ) {
                        return "webp"
                    }
                    if (read >= 2 && head[0].toInt() == 'B'.code && head[1].toInt() == 'M'.code) {
                        return "bmp"
                    }
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        private fun guessExtensionFromUrl(imageUrl: String): String {
            try {
                val path = URL(imageUrl).path
                val dot = path.lastIndexOf('.')
                if (dot >= 0 && dot < path.length - 1) {
                    val ext = path.substring(dot + 1).lowercase(Locale.getDefault())
                    if (ext.matches(Regex("(jpg|jpeg|png|gif|webp|bmp|heic|heif)"))) {
                        return if (ext == "jpeg") "jpg" else ext
                    }
                }
            } catch (ignored: Throwable) {
            }
            return "jpg"
        }

        private fun guessMimeType(name: String?): String {
            val n = if (name == null) "" else name.lowercase(Locale.getDefault())
            if (n.endsWith(".png")) {
                return "image/png"
            }
            if (n.endsWith(".gif")) {
                return "image/gif"
            }
            if (n.endsWith(".webp")) {
                return "image/webp"
            }
            if (n.endsWith(".bmp")) {
                return "image/bmp"
            }
            if (n.endsWith(".heic") || n.endsWith(".heif")) {
                return "image/heic"
            }
            return "image/jpeg"
        }
    }

    private fun shareImageWithSystemChooser(mediaData: Any?): Boolean {
        if (mediaData == null) {
            return false
        }
        return try {
            val urlMethod = mediaData.javaClass.getMethod("U")
            val contextMethod = mediaData.javaClass.getMethod("n")
            val imageUrl = stringify(urlMethod.invoke(mediaData))
            val contextObject = contextMethod.invoke(mediaData)
            if (contextObject !is Context || imageUrl.length == 0 || "null" == imageUrl) {
                module.logd(
                    Log.WARN, MainModule.TAG, "图片分享跳过: MediaData 缺少 URL 或 Context"
                )
                return false
            }
            val context = contextObject
            LogRecorder.setContext(context)
            module.logd(Log.INFO, MainModule.TAG, "图片分享开始下载: url=$imageUrl")
            Thread(Runnable { shareDownloadedImage(context, imageUrl) },
                "BetterHeybox-image-share").start()
            true
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "图片分享接管失败，回退原分享", t)
            false
        }
    }

    private fun shareDownloadedImage(context: Context, imageUrl: String) {
        var output: File? = null
        try {
            val downloaded = downloadImage(context, imageUrl)
            output = downloaded
            val mime = guessMimeType(downloaded.name)
            val galleryUri = publishToGallery(context, downloaded)
            val uri: Uri? = if (galleryUri != null) {
                downloaded.delete()
                module.logd(Log.INFO, MainModule.TAG, "图片已保存到系统相册: uri=$galleryUri")
                galleryUri
            } else {
                getTargetFileUri(context, downloaded)
            }
            if (uri == null) {
                throw IllegalStateException("无法生成图片分享 URI")
            }
            val finalOutput: File? = downloaded
            context.mainExecutor.execute {
                try {
                    val share = Intent(Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val chooser = Intent.createChooser(share, "分享图片")
                    if (context !is Activity) {
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "图片分享 chooser 已唤起: uri=$uri mime=$mime"
                    )
                } catch (t: Throwable) {
                    module.logd(Log.ERROR, MainModule.TAG, "图片分享 chooser 启动失败", t)
                    if (finalOutput != null) {
                        finalOutput.delete()
                    }
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "图片分享准备失败，回退原分享", t)
            output?.delete()
            context.mainExecutor.execute {
                Toast.makeText(context, "图片暂时无法分享", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Throws(Exception::class)
    private fun downloadImage(context: Context, imageUrl: String): File {
        val connection = URL(imageUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 heybox"
        )
        connection.setRequestProperty("Referer", "https://api.xiaoheihe.cn/")
        connection.connect()
        if (connection.responseCode < 200 || connection.responseCode >= 300) {
            throw IllegalStateException("HTTP " + connection.responseCode)
        }
        val dir = context.externalCacheDir ?: context.cacheDir
        val shareDir = File(dir, "betterheybox-share")
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            throw IllegalStateException("无法创建分享缓存目录")
        }
        val tmp = File(shareDir, "image-" + System.currentTimeMillis() + ".tmp")
        try {
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { file ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) {
                            break
                        }
                        file.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val ext = sniffImageExtension(tmp) ?: guessExtensionFromUrl(imageUrl)
        var output = File(shareDir, "image-" + System.currentTimeMillis() + "." + ext)
        if (!tmp.renameTo(output)) {
            output = tmp
        }
        return output
    }

    private fun publishToGallery(context: Context, file: File): Uri? {
        return try {
            if (Build.VERSION.SDK_INT < 29) {
                if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    module.logd(
                        Log.WARN, MainModule.TAG, "无 WRITE_EXTERNAL_STORAGE 权限，回退 FileProvider"
                    )
                    return null
                }
            }
            val mime = guessMimeType(file.name)
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            values.put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/BetterHeybox"
                )
                values.put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri == null) {
                return null
            }
            val os = context.contentResolver.openOutputStream(uri)
            if (os == null) {
                return null
            }
            os.use { output ->
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) {
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val published = ContentValues()
                published.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, published, null, null)
            }
            uri
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG, "写入系统相册失败，回退 FileProvider: " + t
            )
            null
        }
    }

    @Throws(Exception::class)
    private fun getTargetFileUri(context: Context, file: File): Uri? {
        val provider: Class<*> = Class.forName(
            "androidx.core.content.FileProvider", true, context.classLoader
        )
        val authority = MainModule.TARGET_PKG + ".fileprovider"
        val methodNames = arrayOf("getUriForFile", "h", "i")
        for (methodName in methodNames) {
            try {
                val method = provider.getDeclaredMethod(
                    methodName, Context::class.java, String::class.java, File::class.java
                )
                method.setAccessible(true)
                return method.invoke(null, context, authority, file) as? Uri
            } catch (ignored: NoSuchMethodException) {
            }
        }
        throw NoSuchMethodException("FileProvider URI method not found")
    }
}
