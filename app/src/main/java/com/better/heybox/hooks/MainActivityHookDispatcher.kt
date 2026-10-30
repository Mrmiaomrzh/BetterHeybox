package com.better.heybox.hooks

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.better.heybox.MainModule
import com.better.heybox.ViewUtils
import java.util.concurrent.CopyOnWriteArrayList

   
                                                                     
  
                                        
                             
   
object MainActivityHookDispatcher {

    private val lock = Any()

    private val onCreateCallbacks = CopyOnWriteArrayList<NamedCallback>()
    private val onResumeCallbacks = CopyOnWriteArrayList<NamedCallback>()
    private val tabObserverCallbacks = CopyOnWriteArrayList<NamedCallback>()

    private var mainClass: Class<*>? = null

    @Volatile private var onCreateHooked = false
    @Volatile private var onResumeHooked = false
    @Volatile private var tabObserverHooked = false

    private class NamedCallback(val name: String, val block: (Activity) -> Unit)

    fun addOnCreate(module: MainModule, cl: ClassLoader, name: String, block: (Activity) -> Unit) {
        onCreateCallbacks.add(NamedCallback(name, block))
        ensureLifecycle(module, cl)
    }

    fun addOnResume(module: MainModule, cl: ClassLoader, name: String, block: (Activity) -> Unit) {
        onResumeCallbacks.add(NamedCallback(name, block))
        ensureLifecycle(module, cl)
    }

    fun addTabObserver(module: MainModule, cl: ClassLoader, name: String, block: (Activity) -> Unit) {
        tabObserverCallbacks.add(NamedCallback(name, block))
        ensureLifecycle(module, cl)
        ensureTabObserver(module, cl)
    }

    private fun resolveMain(module: MainModule, cl: ClassLoader): Class<*>? {
        mainClass?.let { return it }
        return try {
            Class.forName("com.max.xiaoheihe.MainActivity", false, cl).also { mainClass = it }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "共享 MainActivity 挂点：类解析失败: $t")
            null
        }
    }

    private fun ensureLifecycle(module: MainModule, cl: ClassLoader) {
        synchronized(lock) {
            val main = resolveMain(module, cl) ?: return
            if (!onCreateHooked) {
                try {
                    val onCreate = main.getDeclaredMethod("onCreate", Bundle::class.java)
                    module.hook(onCreate).intercept { chain ->
                        val result = chain.proceed()
                        dispatch(module, onCreateCallbacks, chain.instanceOrNull as? Activity)
                        result
                    }
                    onCreateHooked = true
                    module.logd(Log.INFO, MainModule.TAG, "✔ 共享 MainActivity#onCreate 挂点已安装")
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "共享 MainActivity#onCreate 挂点失败: $t")
                }
            }
            if (!onResumeHooked) {
                try {
                                                           
                    var owner: Class<*>? = main
                    var onResume: java.lang.reflect.Method? = null
                    while (owner != null && onResume == null) {
                        onResume = try {
                            owner.getDeclaredMethod("onResume")
                        } catch (ignored: NoSuchMethodException) {
                            null
                        }
                        owner = owner.superclass
                    }
                    val method = onResume ?: throw NoSuchMethodException("onResume")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val instance = chain.instanceOrNull
                        if (main.isInstance(instance)) {
                            dispatch(module, onResumeCallbacks, instance as? Activity)
                        }
                        result
                    }
                    onResumeHooked = true
                    module.logd(Log.INFO, MainModule.TAG, "✔ 共享 MainActivity#onResume 挂点已安装")
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "共享 MainActivity#onResume 挂点失败: $t")
                }
            }
        }
    }

    private fun ensureTabObserver(module: MainModule, cl: ClassLoader) {
        synchronized(lock) {
            if (tabObserverHooked) {
                return
            }
            val main = resolveMain(module, cl) ?: return
            val observer = try {
                Class.forName("com.max.xiaoheihe.MainActivity\$j", false, cl)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "共享 MainActivity\$j 挂点：类解析失败: $t")
                return
            }
                                                         
            val b = ViewUtils.findMethod(observer, "b", Boolean::class.javaPrimitiveType!!)
                ?: ViewUtils.findMethod(observer, "b", java.lang.Boolean::class.java)
            if (b == null) {
                module.logd(Log.WARN, MainModule.TAG, "共享 MainActivity\$j#b 挂点未找到")
                return
            }
            try {
                module.hook(b).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val outer = ViewUtils.findOuter(chain.instanceOrNull, main) as? Activity
                        if (outer != null) {
                            dispatch(module, tabObserverCallbacks, outer)
                        }
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "共享 MainActivity\$j 挂点分发失败: $t")
                    }
                    result
                }
                tabObserverHooked = true
                module.logd(Log.INFO, MainModule.TAG, "✔ 共享 MainActivity\$j#b 状态回调挂点已安装")
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "共享 MainActivity\$j#b 挂点失败: $t")
            }
        }
    }

    private fun dispatch(module: MainModule, callbacks: List<NamedCallback>, activity: Activity?) {
        if (activity == null || callbacks.isEmpty()) {
            return
        }
        for (cb in callbacks) {
            try {
                cb.block(activity)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "MainActivity 挂点回调 ${cb.name} 异常: $t")
            }
        }
    }
}
