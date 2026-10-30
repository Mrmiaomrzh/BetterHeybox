package com.better.heybox.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object HostFileProvider {

    private const val PROVIDER_CLASS = "androidx.core.content.FileProvider"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    @Volatile
    private var cachedMethod: Method? = null

    @JvmStatic
    fun authority(context: Context?): String? {
        if (context == null) {
            return null
        }
        return try {
            val pkg = context.packageName
            if (pkg.isNullOrEmpty()) null else pkg + AUTHORITY_SUFFIX
        } catch (ignored: Throwable) {
            null
        }
    }

    @JvmStatic
    fun getUriForFile(context: Context?, file: File?): Uri? {
        if (context == null || file == null) {
            return null
        }
        val authority = authority(context) ?: return null
        return try {
            val method = resolve(context.classLoader) ?: return null
            val uri = method.invoke(null, context, authority, file)
            uri as? Uri
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun resolve(loader: ClassLoader?): Method? {
        val cached = cachedMethod
        if (cached != null) {
            return cached
        }
        synchronized(HostFileProvider::class.java) {
            if (cachedMethod != null) {
                return cachedMethod
            }
            val found: Method
            try {
                val provider = Class.forName(PROVIDER_CLASS, false, loader)
                var m = findByName(provider, "getUriForFile")
                if (m == null) {
                    m = findBySignature(provider)
                }
                found = m ?: return null
            } catch (ignored: Throwable) {
                return null
            }
            cachedMethod = found
            return found
        }
    }

    private fun findByName(provider: Class<*>, name: String): Method? {
        return try {
            val method = provider.getDeclaredMethod(name, Context::class.java, String::class.java, File::class.java)
            if (matches(method)) method else null
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun findBySignature(provider: Class<*>): Method? {
        val methods: Array<Method>
        try {
            methods = provider.declaredMethods
        } catch (ignored: Throwable) {
            return null
        }
        for (method in methods) {
            if (!matches(method)) {
                continue
            }
            val params = method.parameterTypes
            if (params.size == 3
                && Context::class.java == params[0]
                && String::class.java == params[1]
                && File::class.java == params[2]
            ) {
                return method
            }
        }
        return null
    }

    private fun matches(method: Method): Boolean {
        return Modifier.isStatic(method.modifiers)
                && Modifier.isPublic(method.modifiers)
                && Uri::class.java == method.returnType
    }
}
