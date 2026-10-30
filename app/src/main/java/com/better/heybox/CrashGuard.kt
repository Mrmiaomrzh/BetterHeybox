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
            LogRecorder.setEnabled(true)

            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    report(thread, throwable)
                } catch (ignored: Throwable) {
                }
                previous?.uncaughtException(thread, throwable)
            }
            Logs.i(
                TAG, "CrashGuard installed: previous_handler=" +
                        (previous?.javaClass?.simpleName ?: "none")
            )
        } catch (t: Throwable) {
            Logs.w(TAG, "CrashGuard install failed: $t")
        }
    }

    @JvmStatic
    fun installLate() {
        sInstalled = false
        install()
    }

    private fun report(thread: Thread?, throwable: Throwable) {
        if (!sReporting.compareAndSet(false, true)) {
            return
        }
        try {
            val head = "Uncaught exception: process=" + App.currentProcessName() +
                    " pid=" + Process.myPid() +
                    " thread=" + (thread?.name ?: "unknown")
            LogRecorder.recordBlocking(Log.ERROR, TAG, head, throwable)
            LogRecorder.recordBlocking(
                Log.ERROR, TAG,
                "Module stats: " + ModuleStats.crashSnapshot()
            )
            LogRecorder.recordBlocking(Log.ERROR, TAG, "Memory: " + memorySnapshot())
            SessionGuard.noteJavaCrash()
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun memorySnapshot(): String {
        val sb = StringBuilder(160)
        try {
            val rt = Runtime.getRuntime()
            val max = rt.maxMemory()
            val total = rt.totalMemory()
            val free = rt.freeMemory()
            sb.append("heapUsed=").append((total - free) / 1024L / 1024L).append("MB")
                .append(" heapTotal=").append(total / 1024L / 1024L).append("MB")
                .append(" heapMax=").append(max / 1024L / 1024L).append("MB")
        } catch (ignored: Throwable) {
        }
        try {
            val mi = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(mi)
            sb.append(" pss=").append(mi.totalPss / 1024L).append("MB")
                .append(" javaHeap=").append(mi.dalvikPss / 1024L).append("MB")
                .append(" nativeHeap=").append(mi.nativePss / 1024L).append("MB")
        } catch (ignored: Throwable) {
        }
        return sb.toString()
    }
}
