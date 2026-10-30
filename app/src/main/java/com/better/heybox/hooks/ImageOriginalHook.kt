package com.better.heybox.hooks

import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import com.better.heybox.App
import com.better.heybox.MainModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class ImageOriginalHook(private val module: MainModule) {

    private val upgradeCount = AtomicInteger()

    fun install(cl: ClassLoader) {
        if (installSchemaHook(cl)) {
            return
        }
        installLegacyHook(cl)
    }

    private fun installSchemaHook(cl: ClassLoader): Boolean {
        try {
            val extClass = Class.forName(IMAGE_UTIL_EXT, false, cl)
            val schemaClass = Class.forName(IMG_URL_SCHEMA, false, cl)
            val target = findResolveUrlMethod(extClass, schemaClass) ?: return false
            val schemaCtor: Constructor<*> = schemaClass.getDeclaredConstructor(String::class.java)
            schemaCtor.isAccessible = true
            val ctor = schemaCtor
            module.hook(target).intercept { chain ->
                val result = chain.proceed()
                try {
                    val args = chain.args
                    if (args.size < 2) {
                        return@intercept result
                    }
                    val viewObject = args[1]
                    val schema = args[0]
                    if (viewObject !is ImageView || schema == null) {
                        return@intercept result
                    }
                    val originalUrl = resolveOriginalUrl(viewObject, result as String)
                        ?: return@intercept result
                    val replacement = ctor.newInstance(originalUrl)
                    (chain.args as MutableList<Any?>)[0] = replacement
                    logUpgrade(result, originalUrl)
                    originalUrl
                } catch (t: Throwable) {
                    module.logv(MainModule.TAG, "图片自动原图: 替换 URL 失败，放行: $t")
                    result
                }
            }
            module.logd(
                Log.INFO, MainModule.TAG, "✔ 图片自动原图 Hook 已安装: ImageUtilExt#"
                        + target.name + "(" + describe(target) + ")"
            )
            return true
        } catch (t: Throwable) {
            module.logv(MainModule.TAG, "图片自动原图: 主入口不可用，回退旧入口: $t")
            return false
        }
    }

    private fun installLegacyHook(cl: ClassLoader) {
        try {
            val loaderClass = Class.forName(HB_IMAGE_LOADER, false, cl)
            val target = findLoadMethod(loaderClass)
            if (target == null) {
                module.logd(Log.WARN, MainModule.TAG, "图片自动原图: 未找到图片加载入口，跳过")
                return
            }
            val expectedArgCount = target.parameterCount
            module.hook(target).intercept { chain ->
                val args: List<Any?>
                try {
                    args = chain.args
                } catch (t: Throwable) {
                    return@intercept chain.proceed()
                }
                if (args.size != expectedArgCount) {
                    return@intercept chain.proceed()
                }
                val viewObject = args[0]
                val urlObject = args[1]
                if (viewObject !is ImageView || urlObject !is String) {
                    return@intercept chain.proceed()
                }
                val originalUrl: String
                try {
                    originalUrl = resolveOriginalUrl(viewObject, urlObject)
                        ?: return@intercept chain.proceed()
                } catch (t: Throwable) {
                    module.logv(MainModule.TAG, "图片自动原图: 解析 URL 失败，放行: $t")
                    return@intercept chain.proceed()
                }
                (chain.args as MutableList<Any?>)[1] = originalUrl
                logUpgrade(urlObject, originalUrl)
                chain.proceed()
            }
            module.logd(
                Log.INFO, MainModule.TAG, "✔ 图片自动原图 Hook 已安装(旧入口): "
                        + target.declaringClass.simpleName + "#" + target.name
                        + "(" + describe(target) + ")"
            )
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 图片自动原图 Hook 失败", t)
        }
    }

    private fun resolveOriginalUrl(view: ImageView, url: String): String? {
        if (url.isEmpty()) {
            return null
        }
        if (!module.isEnabled(App.KEY_IMAGE_AUTO_ORIGINAL, false)) {
            return null
        }
        val queryIndex = url.indexOf('?')
        if (queryIndex <= 0 || queryIndex >= url.length - 1) {
            return null
        }
        val query = url.substring(queryIndex + 1)
        if (!isImageProcessQuery(query)) {
            return null
        }
        val thumbSize = findThumbnailSize(query) ?: return null
        if (!shouldUpgrade(thumbSize, displaySize(view), view)) {
            return null
        }
        return url.substring(0, queryIndex)
    }

    private fun logUpgrade(from: String, to: String) {
        val count = upgradeCount.incrementAndGet()
        if (count <= 10 || count % 100 == 0) {
            module.logd(
                Log.DEBUG, MainModule.TAG, "图片自动原图: 已切换原图 (#$count) "
                        + shorten(from) + " -> " + shorten(to)
            )
        }
    }

    companion object {

        private const val HB_IMAGE_LOADER = "com.max.hbimage.image.HBImageLoader"

        private const val IMAGE_UTIL_EXT = "com.max.hbimage.ImageUtilExt"

        private const val IMG_URL_SCHEMA = "com.max.hbimage.f"

        private const val LARGE_THUMB_MIN_PX = 480

        private fun findResolveUrlMethod(extClass: Class<*>, schemaClass: Class<*>): Method? {
            var shapeMatch: Method? = null
            for (method in extClass.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)
                    || method.returnType != String::class.java
                ) {
                    continue
                }
                val params = method.parameterTypes
                if (params.isEmpty() || params[0] !== schemaClass) {
                    continue
                }
                var hasImageView = false
                for (param in params) {
                    if (param === ImageView::class.java) {
                        hasImageView = true
                        break
                    }
                }
                if (!hasImageView) {
                    continue
                }
                if ("r" == method.name) {
                    return method
                }
                if (shapeMatch == null) {
                    shapeMatch = method
                }
            }
            return shapeMatch
        }

        private fun findLoadMethod(loaderClass: Class<*>): Method? {
            var shapeMatch: Method? = null
            for (method in loaderClass.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) {
                    continue
                }
                if (method.returnType !== Void.TYPE) {
                    continue
                }
                val params = method.parameterTypes
                if (params.size != 3 || params[0] !== ImageView::class.java || params[1] !== String::class.java) {
                    continue
                }
                if ("m" == method.name) {
                    return method
                }
                if (shapeMatch == null && params[2].isInterface) {
                    shapeMatch = method
                }
            }
            return shapeMatch
        }

        private fun isImageProcessQuery(query: String): Boolean =
            indexOfIgnoreCase(query, "imageMogr2") >= 0
                    || indexOfIgnoreCase(query, "imageView2") >= 0

        private fun findThumbnailSize(query: String): IntArray? {
            for (operation in query.split("&".toRegex())) {
                val parts = operation.split("/".toRegex())
                var i = 0
                while (i + 1 < parts.size) {
                    if (!"thumbnail".equals(parts[i], ignoreCase = true)) {
                        i++
                        continue
                    }
                    val size = parseGeometry(parts[i + 1])
                    if (size != null) {
                        return size
                    }
                    i++
                }
            }
            return null
        }

        private fun parseGeometry(geometry: String?): IntArray? {
            if (geometry.isNullOrEmpty()) {
                return null
            }
            var value = geometry.replace("%3E", ">").replace("%3e", ">").replace("%21", "!")
            var end = value.length
            while (end > 0) {
                val last = value[end - 1]
                if (last == '>' || last == '!') {
                    end--
                } else {
                    break
                }
            }
            value = value.substring(0, end)
            while (value.startsWith("!")) {
                value = value.substring(1)
            }
            val x = value.indexOf('x')
            if (x < 0) {
                return null
            }
            val width = parseIntOrZero(value.substring(0, x))
            val height = parseIntOrZero(value.substring(x + 1))
            if (width <= 0 && height <= 0) {
                return null
            }
            return intArrayOf(width, height)
        }

        private fun displaySize(view: ImageView): IntArray {
            val padLeft = view.paddingLeft
            val padTop = view.paddingTop
            val padRight = view.paddingRight
            val padBottom = view.paddingBottom
            var width = view.width - padLeft - padRight
            var height = view.height - padTop - padBottom
            if (width <= 0) {
                width = view.measuredWidth - padLeft - padRight
            }
            if (height <= 0) {
                height = view.measuredHeight - padTop - padBottom
            }
            val params: ViewGroup.LayoutParams? = view.layoutParams
            if (params != null) {
                if (width <= 0 && params.width > 0) {
                    width = params.width
                }
                if (height <= 0 && params.height > 0) {
                    height = params.height
                }
            }
            return intArrayOf(max(width, 0), max(height, 0))
        }

        private fun shouldUpgrade(thumbSize: IntArray, viewSize: IntArray, view: ImageView): Boolean {
            val thumbWidth = thumbSize[0]
            val thumbHeight = thumbSize[1]
            val viewWidth = viewSize[0]
            val viewHeight = viewSize[1]
            if (viewWidth > 0 && thumbWidth > 0 && viewWidth > thumbWidth) {
                return true
            }
            if (viewHeight > 0 && thumbHeight > 0 && viewHeight > thumbHeight) {
                return true
            }
            if (viewWidth > 0 || viewHeight > 0) {
                return false
            }
            val thumbMax = max(thumbWidth, thumbHeight)
            if (thumbMax < LARGE_THUMB_MIN_PX) {
                return false
            }
            return true
        }

        private fun indexOfIgnoreCase(text: String, needle: String): Int {
            val max = text.length - needle.length
            for (i in 0..max) {
                if (text.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) {
                    return i
                }
            }
            return -1
        }

        private fun parseIntOrZero(value: String?): Int {
            if (value.isNullOrEmpty()) {
                return 0
            }
            return try {
                value.trim().toInt()
            } catch (e: NumberFormatException) {
                0
            }
        }

        private fun shorten(url: String?): String {
            if (url == null) {
                return "null"
            }
            return if (url.length <= 120) url else url.substring(0, 120) + "..."
        }

        private fun describe(method: Method): String {
            val builder = StringBuilder()
            val params = method.parameterTypes
            for (i in params.indices) {
                if (i > 0) {
                    builder.append(", ")
                }
                builder.append(params[i].simpleName)
            }
            return builder.toString()
        }
    }
}
