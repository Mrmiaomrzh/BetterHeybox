package com.better.heybox.hooks

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

   
                        
  
                                           
                                          
   
object WebViewLoadUrlDispatcher {

    private val lock = Any()
    private val handlers = CopyOnWriteArrayList<NamedHandler>()

    private val hookedMethods = HashSet<Method>()
    private var hooked = false

    private class NamedHandler(val name: String, val handler: (WebView?, String) -> Boolean)

       
                                              
                        
       
    fun add(
        module: MainModule,
        name: String,
        first: Boolean = false,
        handler: (webView: WebView?, url: String) -> Boolean
    ): Boolean {
        val named = NamedHandler(name, handler)
        if (first) {
            handlers.add(0, named)
        } else {
            handlers.add(named)
        }
        return ensureInstalled(module)
    }

    private fun ensureInstalled(module: MainModule): Boolean {
        synchronized(lock) {
            if (hooked) {
                return true
            }
            var loads = 0
            var failed = false
            try {
                for (method in WebView::class.java.declaredMethods) {
                    if ("loadUrl" != method.name) {
                        continue
                    }
                    val params = method.parameterTypes
                    if (params.size < 1 || params[0] != String::class.java) {
                        continue
                    }
                    loads++
                    if (method in hookedMethods) continue
                    try {
                        module.hook(method).intercept { chain ->
                            val arg = chain.arg(0)
                            if (arg is String) {
                                val webView = chain.instanceOrNull as? WebView
                                for (h in handlers) {
                                    val consume = try {
                                        h.handler(webView, arg)
                                    } catch (t: Throwable) {
                                        module.logd(
                                            Log.WARN, MainModule.TAG,
                                            "loadUrl ${h.name} 处理异常，已放行: $t"
                                        )
                                        false
                                    }
                                    if (consume) {
                                        return@intercept null
                                    }
                                }
                            }
                            chain.proceed()
                        }
                        hookedMethods.add(method)
                    } catch (t: Throwable) {
                        failed = true
                        module.logd(Log.WARN, MainModule.TAG, "共享 loadUrl 重载 Hook 失败: $method", t)
                    }
                }
            } catch (t: Throwable) {
                failed = true
                module.logd(Log.WARN, MainModule.TAG, "✘ 共享 loadUrl 拦截 Hook 失败", t)
            }
            hooked = loads > 0 && !failed
            if (hooked) {
                module.logd(Log.INFO, MainModule.TAG, "✔ 共享 loadUrl 拦截已安装: $loads 个重载")
            }
            return hooked
        }
    }
}

   
                                               
  
                                                  
                                                 
                                               
                                                           
   
object WebViewClientOverrideDispatcher {

    interface Handler {
                                                
        fun onClientClass(clientClass: Class<*>) {}

                                                 
        fun onClientClassRevisited(clientClass: Class<*>) {}

           
                  
                                           
           
        fun onUrlLoading(method: Method, args: List<Any?>): Boolean
    }

    private val lock = Any()
    private val handlers = CopyOnWriteArrayList<NamedHandler>()
    private val hookedClients = HashSet<Class<*>>()
    private val hookedMethods = HashSet<Method>()

    @Volatile private var setterHooked = false

    private class NamedHandler(val name: String, val handler: Handler)

    fun add(
        module: MainModule,
        name: String,
        first: Boolean = false,
        handler: Handler
    ): Boolean {
        val named = NamedHandler(name, handler)
                                                
        val known = synchronized(hookedClients) {
            if (first) handlers.add(0, named) else handlers.add(named)
            hookedClients.toList()
        }
        val ok = ensureInstalled(module)
        for (clientClass in known) {
            try {
                handler.onClientClass(clientClass)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "共享 WebViewClient $name 回放异常: $t")
            }
        }
        return ok
    }

    private fun isFrameworkClass(name: String): Boolean =
        name.startsWith("android.") || name.startsWith("androidx.") ||
                name.startsWith("com.android.") || name.startsWith("org.chromium.") ||
                name.startsWith("java.")

    private fun ensureInstalled(module: MainModule): Boolean {
        synchronized(lock) {
            if (setterHooked) {
                return true
            }
            try {
                val setter = WebView::class.java.getDeclaredMethod(
                    "setWebViewClient", android.webkit.WebViewClient::class.java
                )
                module.hook(setter).intercept { chain ->
                    val client = chain.arg(0)
                    if (client != null) {
                        try {
                            hookClientClass(module, client.javaClass)
                        } catch (t: Throwable) {
                            module.logd(
                                Log.WARN, MainModule.TAG, "共享 WebViewClient 代理失败: $t"
                            )
                        }
                    }
                    chain.proceed()
                }
                setterHooked = true
                module.logd(
                    Log.INFO, MainModule.TAG, "✔ 共享 WebViewClient 代理已安装"
                )
                return true
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 共享 WebViewClient 代理 Hook 失败", t)
                return false
            }
        }
    }

    private fun hookClientClass(module: MainModule, clientClass: Class<*>) {
        if (clientClass == WebViewClient::class.java || isFrameworkClass(clientClass.name)) {
            return
        }
        val (firstDiscovery, notify) = synchronized(hookedClients) {
            hookedClients.add(clientClass) to handlers.toList()
        }
        for (h in notify) {
            try {
                if (firstDiscovery) h.handler.onClientClass(clientClass)
                else h.handler.onClientClassRevisited(clientClass)
            } catch (t: Throwable) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "共享 WebViewClient ${h.name} onClientClass 异常: $t"
                )
            }
        }
        hookShouldOverrideUrlLoading(module, clientClass)
    }

    private fun hookShouldOverrideUrlLoading(module: MainModule, clientClass: Class<*>) {
        val name = clientClass.name
                                                    
                                                                  
        var c: Class<*>? = clientClass
        while (c != null && !isFrameworkClass(c.name)) {
            for (method in c.declaredMethods) {
                if ("shouldOverrideUrlLoading" != method.name) {
                    continue
                }
                val params = method.parameterTypes
                if (params.size != 2 || params[0] != WebView::class.java ||
                    method.returnType != Boolean::class.javaPrimitiveType
                ) {
                    continue
                }
                if (params[1] != String::class.java &&
                    params[1] != android.webkit.WebResourceRequest::class.java
                ) {
                    continue
                }
                synchronized(hookedMethods) {
                    if (!hookedMethods.add(method)) {
                        continue
                    }
                }
                try {
                    module.hook(method).intercept { chain ->
                        val args = chain.args
                        for (h in handlers) {
                            val consume = try {
                                h.handler.onUrlLoading(method, args)
                            } catch (t: Throwable) {
                                module.logd(
                                    Log.WARN, MainModule.TAG,
                                    "shouldOverrideUrlLoading ${h.name} 处理异常，已放行: $t"
                                )
                                false
                            }
                            if (consume) {
                                return@intercept java.lang.Boolean.TRUE
                            }
                        }
                        chain.proceed()
                    }
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "✔ 共享 WebViewClient 已代理: ${c.name}#${method.name}"
                    )
                } catch (t: Throwable) {
                                             
                    synchronized(hookedMethods) {
                        hookedMethods.remove(method)
                    }
                    module.logd(
                        Log.WARN, MainModule.TAG,
                        "共享 shouldOverrideUrlLoading Hook 失败: $name", t
                    )
                }
            }
            c = c.superclass
        }
    }
}
