package com.better.heybox.hooks

import android.content.Intent
import android.util.Log
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

   
                                          
  
                                      
                                       
                                                      
   
object ActivityStartDispatcher {

                                           
    class Veto(val value: Any?)

    private val lock = Any()
    private val handlers = CopyOnWriteArrayList<NamedHandler>()

    private val installedMethods = mutableSetOf<Method>()

    private class NamedHandler(
        val name: String,
        val recheckAfterHandlers: Boolean,
        val handler: (Method, Intent) -> Veto?
    )

       
                                           
                                                                         
       
    fun add(
        module: MainModule,
        name: String,
        first: Boolean = false,
        recheckAfterHandlers: Boolean = false,
        handler: (method: Method, intent: Intent) -> Veto?
    ): Boolean {
        synchronized(lock) {
            if (handlers.none { it.name == name }) {
                val named = NamedHandler(name, recheckAfterHandlers, handler)
                if (first) handlers.add(0, named) else handlers.add(named)
            }
            return ensureInstalled(module)
        }
    }

    private fun ensureInstalled(module: MainModule): Boolean {
        synchronized(lock) {
            var installed = 0
            for (method in android.app.Instrumentation::class.java.declaredMethods) {
                if ("execStartActivity" != method.name) {
                    continue
                }
                val params = method.parameterTypes
                if (params.size < 7 || params[4] != Intent::class.java) {
                    continue
                }
                if (method in installedMethods) continue
                try {
                    module.hook(method).intercept { chain ->
                        try {
                            val args = chain.args
                            if (args.size > 4 && args[4] is Intent) {
                                val intent = args[4] as Intent
                                val snapshot = handlers.toList()
                                fun dispatch(h: NamedHandler): Veto? = try {
                                    h.handler(method, intent)
                                } catch (t: Throwable) {
                                    module.logd(
                                        Log.WARN, MainModule.TAG,
                                        "启动拦截 ${h.name} 处理异常，已放行: $t"
                                    )
                                    null
                                }
                                for (h in snapshot) {
                                    val veto = dispatch(h)
                                    if (veto != null) return@intercept veto.value
                                }
                                                                  
                                for (h in snapshot) {
                                    if (!h.recheckAfterHandlers) continue
                                    val veto = dispatch(h)
                                    if (veto != null) return@intercept veto.value
                                }
                            }
                        } catch (t: Throwable) {
                            module.logd(Log.WARN, MainModule.TAG, "启动拦截分发异常: $t")
                        }
                        chain.proceed()
                    }
                    installedMethods.add(method)
                    installed++
                } catch (t: Throwable) {
                                       
                    module.logd(
                        Log.WARN, MainModule.TAG, "✘ 共享 execStartActivity 重载安装失败", t
                    )
                }
            }
            if (installed > 0) {
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "✔ 共享 Instrumentation 启动拦截已安装: $installed 个 execStartActivity"
                )
            }
            return installedMethods.isNotEmpty()
        }
    }
}
