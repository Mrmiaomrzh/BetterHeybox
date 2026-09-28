package com.better.heybox

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean

object ForegroundTracker {

    private val sRegistered = AtomicBoolean(false)
    private val sFirstResume = AtomicBoolean(false)

    @Volatile private var sActiveCount = 0

    @Volatile private var sApp: Application? = null

    @Volatile private var sCallbacks: Application.ActivityLifecycleCallbacks? = null

    @JvmStatic
    fun shutdown() {
        val app = sApp
        val callbacks = sCallbacks
        if (app != null && callbacks != null) {
            try {
                app.unregisterActivityLifecycleCallbacks(callbacks)
            } catch (ignored: Throwable) {
            }
        }
        sApp = null
        sCallbacks = null
        sRegistered.set(false)
        sFirstResume.set(false)
        sActiveCount = 0
    }

    @JvmStatic
    fun onActivityResumed(activity: Activity) {
        if (!BuildFlags.DEBUG) {
            return
        }
        registerIfNeeded(activity)
        if (sActiveCount == 0 && sFirstResume.compareAndSet(false, true)) {
            sActiveCount = 1
            Checkpoint.mark("应用打开（前台）: %s", activity.javaClass.simpleName)
        }
    }

    private fun registerIfNeeded(context: Context) {
        if (!sRegistered.compareAndSet(false, true)) {
            return
        }
        try {
            val app = context.applicationContext
            if (app !is Application) {
                return
            }
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {
                    if (++sActiveCount == 1) {
                        Checkpoint.mark("应用打开（前台）: %s", activity.javaClass.simpleName)
                    }
                }

                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {
                    if (sActiveCount > 0) {
                        sActiveCount--
                    }
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            sApp = app
            sCallbacks = callbacks
            Checkpoint.mark("前台跟踪已注册")
        } catch (t: Throwable) {
            sRegistered.set(false)
            Checkpoint.mark("前台跟踪注册失败: %s", t)
        }
    }
}
