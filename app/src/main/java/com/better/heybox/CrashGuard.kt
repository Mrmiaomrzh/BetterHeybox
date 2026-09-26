package com.better.heybox

import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object CrashGuard {

    private const val TAG = "BetterHeybox"

    @Volatile private var sInstalled = false

    private val sReporting = AtomicBoolean()

    @JvmStatic
    fun install() {
        if (sInstalled) {
            return
        }
        sInstalled = true
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    report(thread, throwable)
                } catch (ignored: Throwable) {
                }
                previous?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "崩溃取证安装失败: $t")
        }
    }

    private fun report(thread: Thread?, throwable: Throwable) {
        if (!sReporting.compareAndSet(false, true)) {
            return
        }
        try {
            val head = "💥 未捕获异常（进程=" + App.currentProcessName() +
                    " pid=" + Process.myPid() +
                    " 线程=" + (thread?.name ?: "?") + "）"
            LogRecorder.record(Log.ERROR, TAG, head, throwable)
            LogRecorder.record(Log.ERROR, TAG, "模块统计: " + ModuleStats.snapshot())
        } catch (ignored: Throwable) {
        }
    }
}
