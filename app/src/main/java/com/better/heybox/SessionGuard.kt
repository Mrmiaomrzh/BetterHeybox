package com.better.heybox

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.util.concurrent.atomic.AtomicInteger

object SessionGuard {

    private const val TAG = "BetterHeybox"

    private const val KEY_PID = "session_pid"
    private const val KEY_STARTED_AT = "session_started_at"
    private const val KEY_FOREGROUND = "session_foreground"
    private const val KEY_JAVA_CRASH = "session_java_crash"
    private const val KEY_PROCESS_ID = "session_process_id"

    @Volatile
    private var sStarted = false

    @Volatile
    private var sForeground = false

    private val sStartedActivities = AtomicInteger()

    @Volatile
    private var sCallbacksRegistered = false

    @Volatile
    private var sApplication: Application? = null

    @Volatile
    private var sActivityCallbacks: Application.ActivityLifecycleCallbacks? = null

    private fun resolveProcessName(): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val name = Application.getProcessName()
                if (name.isNotEmpty()) {
                    return name
                }
            }
        } catch (ignored: Throwable) {
        }
        var reader: BufferedReader? = null
        try {
            reader = BufferedReader(FileReader("/proc/self/cmdline"))
            val line = reader.readLine()
            if (!line.isNullOrEmpty()) {
                return line
            }
        } catch (ignored: Throwable) {
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (ignored: Throwable) {
                }
            }
        }
        return null
    }

    @JvmStatic
    internal fun isMainProcess(): Boolean {
        val process = resolveProcessName()
        if (process == null) {
            return false
        }
        return process.indexOf(':') < 0
    }

    private fun processIdentity(): String? {
        val pid = Process.myPid()
        var reader: BufferedReader? = null
        try {
            reader = BufferedReader(FileReader("/proc/self/stat"))
            val stat = reader.readLine()
            if (stat != null) {
                val close = stat.lastIndexOf(')')
                if (close > 0 && close + 2 < stat.length) {
                    val rest = stat.substring(close + 2).trim().split("\\s+".toRegex())
                        .dropLastWhile { it.isEmpty() }.toTypedArray()
                    if (rest.size >= 20) {
                        return "$pid:" + rest[19]
                    }
                }
            }
        } catch (ignored: Throwable) {
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (ignored: Throwable) {
                }
            }
        }
        return null
    }

    @JvmStatic
    fun onProcessStart(context: Context?) {
        if (sStarted) {
            return
        }
        if (!isMainProcess()) {
            return
        }
        try {
            LogRecorder.setEnabled(true)

            HeyboxPrefs.init(context)
            val prefs: SharedPreferences = HeyboxPrefs.get() ?: return
            sStarted = true
            val now = System.currentTimeMillis()
            val currentIdentity = processIdentity()
            val previousIdentity = prefs.getString(KEY_PROCESS_ID, null)
            val previousPid = prefs.getInt(KEY_PID, -1)
            val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
            val wasForeground = prefs.getBoolean(KEY_FOREGROUND, false)
            val javaCrash = prefs.getBoolean(KEY_JAVA_CRASH, false)
            val currentPid = Process.myPid()

            val newSession: Boolean
            if (startedAt <= 0L) {
                newSession = false
            } else if (previousIdentity != null && currentIdentity != null) {
                newSession = previousIdentity != currentIdentity
            } else {
                newSession = previousPid > 0 && previousPid != currentPid
            }
            if (newSession) {
                reportPrevious(previousPid, startedAt, wasForeground, javaCrash, now)
            }

            prefs.edit()
                .putInt(KEY_PID, currentPid)
                .putString(KEY_PROCESS_ID, currentIdentity)
                .putLong(KEY_STARTED_AT, now)
                .putBoolean(KEY_FOREGROUND, false)
                .putBoolean(KEY_JAVA_CRASH, false)
                .commit()
            registerActivityCallbacks(context)

            Logs.i(TAG, "SessionGuard initialized: pid=$currentPid identity=$currentIdentity")
        } catch (t: Throwable) {
            Logs.w(TAG, "SessionGuard init failed: prefs write error: $t")
        }
    }

    private fun registerActivityCallbacks(context: Context?): Boolean {
        if (sCallbacksRegistered) {
            return true
        }
        var app = context?.applicationContext
        if (app !is Application) {
            val resolved = App.resolveAppContext()
            app = if (resolved is Application) resolved else null
        }
        if (app !is Application) {
            return false
        }
        return try {
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
                }

                override fun onActivityStarted(activity: Activity) {
                    if (sStartedActivities.incrementAndGet() == 1) {
                        setForeground(true)
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    setForeground(true)
                }

                override fun onActivityPaused(activity: Activity) {
                }

                override fun onActivityStopped(activity: Activity) {
                    if (sStartedActivities.decrementAndGet() <= 0) {
                        sStartedActivities.set(0)
                        setForeground(false)
                    }
                }

                override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {
                }

                override fun onActivityDestroyed(activity: Activity) {
                }
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            sApplication = app
            sActivityCallbacks = callbacks
            sCallbacksRegistered = true
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun setForeground(foreground: Boolean) {
        if (sForeground == foreground) {
            return
        }
        sForeground = foreground
        try {
            val prefs = HeyboxPrefs.get()
            if (prefs != null) {
                prefs.edit().putBoolean(KEY_FOREGROUND, foreground).commit()
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun heartbeat() {
        if (!sStarted) {
            return
        }
        if (!sCallbacksRegistered && !registerActivityCallbacks(App.resolveAppContext())) {
            return
        }
        if (!sForeground) {
            setForeground(true)
        }
    }

    private fun reportPrevious(
        previousPid: Int, startedAt: Long, wasForeground: Boolean,
        javaCrash: Boolean, now: Long
    ) {
        val livedMs = maxOf(0L, now - startedAt)
        if (javaCrash) {
            LogRecorder.recordBlocking(
                Log.WARN, TAG,
                "Previous session terminated: pid=$previousPid cause=java_exception" +
                        " uptime=" + (livedMs / 1000L) + "s"
            )
            return
        }
        if (wasForeground) {
            ModuleStats.abnormalExits.incrementAndGet()
            LogRecorder.recordBlocking(
                Log.WARN, TAG,
                "Previous session abnormal exit: pid=$previousPid state=foreground" +
                        " uptime=" + (livedMs / 1000L) + "s java_crash=false" +
                        " likely_cause=native_crash|ANR|LMK memory=" + CrashGuard.memorySnapshot()
            )
        } else {
            LogRecorder.recordBlockingGated(
                Log.INFO, TAG,
                "Previous session background exit: pid=$previousPid state=background" +
                        " uptime=" + (livedMs / 1000L) + "s cause=system_reclaim|user_kill"
            )
        }
    }

    @JvmStatic
    fun shutdown() {
        val app = sApplication
        val callbacks = sActivityCallbacks
        sApplication = null
        sActivityCallbacks = null
        sCallbacksRegistered = false
        sStarted = false
        sForeground = false
        sStartedActivities.set(0)
        if (app != null && callbacks != null) {
            try {
                app.unregisterActivityLifecycleCallbacks(callbacks)
            } catch (ignored: Throwable) {
            }
        }
    }

    @JvmStatic
    fun noteJavaCrash() {
        if (!isMainProcess()) {
            return
        }
        try {
            val prefs = HeyboxPrefs.get()
            if (prefs != null) {
                prefs.edit().putBoolean(KEY_JAVA_CRASH, true).commit()
            }
        } catch (ignored: Throwable) {
        }
    }
}
