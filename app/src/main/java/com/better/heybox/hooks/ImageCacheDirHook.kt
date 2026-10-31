package com.better.heybox.hooks

import android.content.Context
import android.util.Log
import com.better.heybox.App
import com.better.heybox.MainModule
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

class ImageCacheDirHook(private val module: MainModule) {

    private val appliedLogged = AtomicBoolean(false)

    fun install(cl: ClassLoader) {
        try {
            val configured = module.getString(App.KEY_IMAGE_CACHE_DIR, "")
            val path = configured?.trim() ?: ""
            if (path.isEmpty()) {
                module.logv(MainModule.TAG, "图片缓存目录: 未配置自定义目录，保持宿主默认")
                return
            }
            val directory = File(path)
            if (!directory.isAbsolute) {
                throw IllegalArgumentException("图片缓存目录不是绝对路径: $path")
            }
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IllegalStateException("图片缓存目录创建失败: $path")
            }
            if (!directory.canWrite()) {
                throw IllegalStateException("图片缓存目录不可写: $path")
            }

            val builderClass = Class.forName(GLIDE_BUILDER, false, cl)
            val factoryInterface = Class.forName(DISK_CACHE_FACTORY, false, cl)
            val factoryImplClass = Class.forName(DISK_LRU_CACHE_FACTORY, false, cl)

            val factory = newDiskCacheFactory(factoryImplClass, directory.absolutePath)
            val build = findBuildMethod(builderClass)
            if (build == null) {
                module.logd(Log.WARN, MainModule.TAG, "图片缓存目录: 未找到 GlideBuilder.build，跳过")
                return
            }
            val setter = findFactorySetter(builderClass, factoryInterface)
            val field = if (setter == null) findFactoryField(builderClass, factoryInterface) else null
            if (setter == null && field == null) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "图片缓存目录: 未找到磁盘缓存工厂注入点（字段/ setter），跳过"
                )
                return
            }

            val factoryRef = factory
            val setterRef = setter
            val fieldRef = field
            val targetPath = directory.absolutePath
            module.hook(build).intercept { chain ->
                try {
                    val builder = chain.instanceOrNull
                    if (builder != null) {
                        if (setterRef != null) {
                            setterRef.invoke(builder, factoryRef)
                        } else if (fieldRef != null) {
                            fieldRef.set(builder, factoryRef)
                        }
                    }
                    if (appliedLogged.compareAndSet(false, true)) {
                        module.logd(
                            Log.INFO, MainModule.TAG,
                            "图片缓存目录: 已接管 Glide 磁盘缓存 -> $targetPath"
                        )
                    }
                } catch (t: Throwable) {
                    if (appliedLogged.compareAndSet(false, true)) {
                        module.logd(
                            Log.WARN, MainModule.TAG,
                            "图片缓存目录: 替换 Glide 磁盘缓存工厂失败，本次沿用宿主默认", t
                        )
                    }
                }
                chain.proceed()
            }

            if (isGlideInitialized(cl)) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "图片缓存目录: Glide 已初始化，新目录将在下次启动宿主后生效: $targetPath"
                )
            } else {
                module.logd(
                    Log.INFO, MainModule.TAG, "✔ 图片缓存目录 Hook 已安装: $targetPath"
                            + " (上限 " + (DISK_CACHE_SIZE_BYTES / (1024L * 1024L)) + "MB)"
                )
            }
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 图片缓存目录 Hook 失败", t)
            throw t
        }
    }

    companion object {

        private const val GLIDE_BUILDER = "com.bumptech.glide.c"
        private const val GLIDE = "com.bumptech.glide.Glide"
        private const val DISK_CACHE_FACTORY = "com.bumptech.glide.load.engine.cache.a\$a"
        private const val DISK_LRU_CACHE_FACTORY = "com.bumptech.glide.load.engine.cache.d"

        private const val DISK_CACHE_SIZE_BYTES = 250L * 1024L * 1024L

        @Throws(Exception::class)
        private fun newDiskCacheFactory(factoryImplClass: Class<*>, path: String): Any {
            val constructor: Constructor<*> =
                factoryImplClass.getDeclaredConstructor(String::class.java, java.lang.Long.TYPE)
            constructor.isAccessible = true
            return constructor.newInstance(path, DISK_CACHE_SIZE_BYTES)
        }

        private fun findBuildMethod(builderClass: Class<*>): Method? {
            var fallback: Method? = null
            for (method in builderClass.declaredMethods) {
                val params = method.parameterTypes
                if (params.isEmpty() || params[0] !== Context::class.java) {
                    continue
                }
                if (GLIDE != method.returnType.name) {
                    continue
                }
                if (params.size == 3) {
                    return method
                }
                if (fallback == null) {
                    fallback = method
                }
            }
            return fallback
        }

        private fun findFactorySetter(builderClass: Class<*>, factoryInterface: Class<*>): Method? {
            for (method in builderClass.declaredMethods) {
                val params = method.parameterTypes
                if (params.size != 1 || params[0] !== factoryInterface) {
                    continue
                }
                method.isAccessible = true
                return method
            }
            return null
        }

        private fun findFactoryField(builderClass: Class<*>, factoryInterface: Class<*>): Field? {
            for (field in builderClass.declaredFields) {
                if (field.type !== factoryInterface) {
                    continue
                }
                field.isAccessible = true
                return field
            }
            return null
        }

        private fun isGlideInitialized(cl: ClassLoader): Boolean {
            return try {
                val glideClass = Class.forName(GLIDE, false, cl)
                for (field in glideClass.declaredFields) {
                    if (!Modifier.isStatic(field.modifiers) || field.type !== glideClass) {
                        continue
                    }
                    field.isAccessible = true
                    if (field.get(null) != null) {
                        return true
                    }
                }
                false
            } catch (ignored: Throwable) {
                false
            }
        }
    }
}
