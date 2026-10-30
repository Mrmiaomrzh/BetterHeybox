package com.better.heybox.hooks

import android.util.Log
import com.better.heybox.MainModule
import java.util.concurrent.CopyOnWriteArrayList

   
                                                                
  
                                  
                                    
   
object BaseAdapterBindDispatcher {

    private const val BASE_ADAPTER_CLASS = "com.max.hbcommon.base.adapter.s"
    private const val HOLDER_CLASS = "com.max.hbcommon.base.adapter.s\$e"

    interface Callback {
        val name: String

                                         
        fun beforeBind(holder: Any?, position: Int)

                                         
        fun afterBind(holder: Any?, position: Int, adapter: Any?)
    }

    private val lock = Any()
    private val callbacks = CopyOnWriteArrayList<Callback>()

    @Volatile private var hooked = false

                                          
    fun add(module: MainModule, cl: ClassLoader, callback: Callback): Boolean {
        synchronized(lock) {
            if (!ensureInstalled(module, cl)) {
                return false
            }
            callbacks.add(callback)
            return true
        }
    }

    private fun ensureInstalled(module: MainModule, cl: ClassLoader): Boolean {
        synchronized(lock) {
            if (hooked) {
                return true
            }
            try {
                val base = Class.forName(BASE_ADAPTER_CLASS, false, cl)
                val holder = Class.forName(HOLDER_CLASS, false, cl)
                val bind = base.getDeclaredMethod("onBindViewHolder", holder, Integer.TYPE)
                module.hook(bind).intercept { chain ->
                    val viewHolder = chain.arg(0)
                    val position = (chain.arg(1) as java.lang.Number).intValue()
                    val snapshot = callbacks.toList()
                    for (cb in snapshot) {
                        try {
                            cb.beforeBind(viewHolder, position)
                        } catch (t: Throwable) {
                            module.logd(
                                Log.WARN, MainModule.TAG,
                                "通用列表挂点 ${cb.name} 前置异常，已放行: $t"
                            )
                        }
                    }
                    val result = chain.proceed()
                    val adapter = chain.instanceOrNull
                    for (cb in snapshot) {
                        try {
                            cb.afterBind(viewHolder, position, adapter)
                        } catch (t: Throwable) {
                            module.logd(
                                Log.WARN, MainModule.TAG,
                                "通用列表挂点 ${cb.name} 后置异常，已放行: $t"
                            )
                        }
                    }
                    result
                }
                hooked = true
                module.logd(
                    Log.INFO, MainModule.TAG,
                    "✔ 共享列表挂点已安装: $BASE_ADAPTER_CLASS#onBindViewHolder"
                )
                return true
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 共享列表挂点安装失败: $t")
                return false
            }
        }
    }
}
