package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import com.better.heybox.ModuleStats
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

class TrackerBlockHook(private val module: MainModule) {
    private val hits = StringBuilder()

    fun install(cl: ClassLoader) {
        installUrlHook()
        try {
            val chainClass = Class.forName("okhttp3.internal.http.RealInterceptorChain", false, cl)
            val method = chainClass.declaredMethods.firstOrNull {
                it.name == "proceed" && it.parameterCount == 1 && it.returnType.name.startsWith("okhttp3.")
            } ?: throw NoSuchMethodException("RealInterceptorChain.proceed")
            val responseFactory = ResponseFactory(cl, method.parameterTypes[0], method.returnType)
            val exchangeMethod = chainClass.methods.firstOrNull { it.name == "getExchange\$okhttp" && it.parameterCount == 0 }
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                try {
                    val request = chain.args[0]
                    if (request != null && exchangeMethod?.invoke(chain.instanceOrNull) == null) {
                        val url = responseFactory.urlMethod.invoke(request)
                        val host = url?.let { java.net.URI(it.toString()).host?.lowercase(Locale.ROOT) }
                        if (isBlocked(host)) {
                            val response = responseFactory.create(request)
                            ModuleStats.trackerBlocked.incrementAndGet()
                            module.logd(Log.INFO, MainModule.TAG, "屏蔽追踪请求 | host=$host")
                            return@intercept response
                        }
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "追踪请求屏蔽判断或构造响应失败，放行: $t")
                }
                chain.proceed()
            }
            hit("网络栈", "RealInterceptorChain.proceed")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "追踪请求屏蔽：OkHttp 拦截不可用: $t")
        }
        if (hits.isEmpty()) throw IllegalStateException("追踪请求屏蔽 Hook 未安装")
        module.logd(Log.INFO, MainModule.TAG, "✔ 追踪请求屏蔽 Hook 已安装：$hits")
    }

    private fun isBlocked(host: String?): Boolean = when (host?.lowercase(Locale.ROOT)) {
        in LOG_HOSTS -> HeyboxPrefs.getBoolean(App.KEY_TRACKER_BLOCK_LOGS, false)
        in RISK_HOSTS -> HeyboxPrefs.getBoolean(App.KEY_TRACKER_BLOCK_RISK, false)
        else -> false
    }

    private fun installUrlHook() {
        for (method in java.net.URL::class.java.declaredMethods.filter {
            it.name == "openConnection" && (it.parameterCount == 0 ||
                (it.parameterCount == 1 && it.parameterTypes[0] == java.net.Proxy::class.java))
        }) {
            try {
                module.hook(method).intercept { chain ->
                    val url = chain.instanceOrNull as? java.net.URL
                    if (url != null && isBlocked(url.host) && (url.protocol == "https" || url.protocol == "http")) {
                        val response = if (url.protocol == "https") EmptyHttpsConnection(url) else EmptyHttpConnection(url)
                        ModuleStats.trackerBlocked.incrementAndGet()
                        module.logd(Log.INFO, MainModule.TAG, "屏蔽追踪请求 | host=${url.host}")
                        return@intercept response
                    }
                    chain.proceed()
                }
                hit("URL", method.toString())
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "追踪请求屏蔽：URL 连接 Hook 失败: $t")
            }
        }
    }

    private class EmptyHttpConnection(url: java.net.URL) : java.net.HttpURLConnection(url) {
        private val output = java.io.ByteArrayOutputStream()
        override fun connect() { connected = true }
        override fun disconnect() { connected = false }
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream = output
        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getResponseCode() = 204
        override fun getResponseMessage() = "No Content"
        override fun getContentLength() = 0
        override fun getContentLengthLong() = 0L
    }

    private class EmptyHttpsConnection(url: java.net.URL) : javax.net.ssl.HttpsURLConnection(url) {
        private val output = java.io.ByteArrayOutputStream()
        override fun connect() { connected = true }
        override fun disconnect() { connected = false }
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream = output
        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getResponseCode() = 204
        override fun getResponseMessage() = "No Content"
        override fun getContentLength() = 0
        override fun getContentLengthLong() = 0L
        override fun getCipherSuite() = ""
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
    }

    private fun hit(label: String, target: String) {
        if (hits.isNotEmpty()) hits.append(" / ")
        hits.append(label).append('=').append(target)
    }

    private class ResponseFactory(cl: ClassLoader, private val requestClass: Class<*>, responseClass: Class<*>) {
        private val obfuscated = responseClass.name == "okhttp3.c0" && requestClass.name == "okhttp3.a0"
        private val bodyClass = Class.forName(if (obfuscated) "okhttp3.d0" else "okhttp3.ResponseBody", false, cl)
        private val mediaTypeClass = Class.forName(if (obfuscated) "okhttp3.v" else "okhttp3.MediaType", false, cl)
        private val protocolClass = Class.forName("okhttp3.Protocol", false, cl)
        private val builderClass = Class.forName(if (obfuscated) "okhttp3.c0\$a" else "okhttp3.Response\$Builder", false, cl)
        val urlMethod: Method = requestClass.getMethod(if (obfuscated) "q" else "url")
        private val builderConstructor = builderClass.getConstructor()
        private val requestSetter = builderClass.getMethod(if (obfuscated) "E" else "request", requestClass)
        private val protocolSetter = builderClass.getMethod(if (obfuscated) "B" else "protocol", protocolClass)
        private val codeSetter = builderClass.getMethod(if (obfuscated) "g" else "code", Int::class.javaPrimitiveType)
        private val messageSetter = builderClass.getMethod(if (obfuscated) "y" else "message", String::class.java)
        private val bodySetter = builderClass.getMethod(if (obfuscated) "b" else "body", bodyClass)
        private val buildMethod = builderClass.getMethod(if (obfuscated) "c" else "build")
        private val http11 = protocolClass.getField("HTTP_1_1").get(null)
        private val bodyFactory: Method
        private val bodyFactoryReceiver: Any?
        private val bytesFirst: Boolean

        init {
            val signature1 = arrayOf(mediaTypeClass, ByteArray::class.java)
            val signature2 = arrayOf(ByteArray::class.java, mediaTypeClass)
            val staticFactory = bodyClass.methods.firstOrNull {
                it.name == "create" && Modifier.isStatic(it.modifiers) &&
                    (it.parameterTypes.contentEquals(signature1) || it.parameterTypes.contentEquals(signature2))
            }
            if (staticFactory != null) {
                bodyFactory = staticFactory
                bodyFactoryReceiver = null
            } else {
                val companion = bodyClass.getField("Companion").get(null)
                bodyFactory = companion.javaClass.methods.firstOrNull {
                    it.name == "create" &&
                        (it.parameterTypes.contentEquals(signature1) || it.parameterTypes.contentEquals(signature2))
                } ?: throw NoSuchMethodException("ResponseBody.create(MediaType, byte[]) / create(byte[], MediaType)")
                bodyFactoryReceiver = companion
            }
            bytesFirst = bodyFactory.parameterTypes[0] == ByteArray::class.java
            emptyBody() 
        }

        private fun emptyBody(): Any {
            val body = if (bytesFirst) bodyFactory.invoke(bodyFactoryReceiver, ByteArray(0), null)
            else bodyFactory.invoke(bodyFactoryReceiver, null, ByteArray(0))
            return body ?: throw IllegalStateException("ResponseBody.create 返回 null")
        }

        fun create(request: Any): Any {
            val builder = builderConstructor.newInstance()
            requestSetter.invoke(builder, request)
            protocolSetter.invoke(builder, http11)
            codeSetter.invoke(builder, 204)
            messageSetter.invoke(builder, "No Content")
            bodySetter.invoke(builder, emptyBody())
            return buildMethod.invoke(builder) ?: throw IllegalStateException("Response.Builder.build 返回 null")
        }
    }

    companion object {
        private val LOG_HOSTS = setOf("errnewlog.umeng.com", "apmplus.volces.com", "lf-event-manager.oceanengine.com")
        private val RISK_HOSTS = setOf("fp-it.fengkongcloud.com", "log-auth.zztfly.com", "upc.zztfly.com")
    }
}
