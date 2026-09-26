package com.better.heybox

import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.Locale

object Checkpoint {

    private const val TAG = "BHX-CKPT"
    private const val MAX = 256

    private val LOCK = Any()
    private val sEntries = mutableListOf<String>()

    @Volatile
    private var sStart = -1L

    @JvmStatic
    fun mark(msg: String) {
        mark("%s", msg)
    }

    @JvmStatic
    fun mark(fmt: String, vararg args: Any?) {
        if (!BuildFlags.DEBUG) {
            return
        }
        val msg = if (args.isEmpty()) fmt else String.format(fmt, *args)
        val now = SystemClock.elapsedRealtime()
        synchronized(LOCK) {
            if (sStart < 0) {
                sStart = now
                sEntries.add(header())
            }
            sEntries.add(
                String.format(
                    Locale.US, "[%8dms][pid=%d][%s] %s",
                    now - sStart, android.os.Process.myPid(),
                    Thread.currentThread().name, msg
                )
            )
            while (sEntries.size > MAX) {
                sEntries.removeAt(0)
            }
        }
        Log.i(TAG, msg)
        LogRecorder.recordEvent("检查点: " + msg)
    }

    @JvmStatic
    fun clear() {
        synchronized(LOCK) {
            sEntries.clear()
            sStart = -1
        }
    }

    @JvmStatic
    fun dump(): String = dump(Int.MAX_VALUE)

    @JvmStatic
    fun dump(maxLines: Int): String {
        if (!BuildFlags.DEBUG) {
            return "（Release 构建未启用检查点）"
        }
        synchronized(LOCK) {
            if (sEntries.isEmpty()) {
                return "（暂无检查点）"
            }
            if (sEntries.size <= maxLines) {
                return sEntries.joinToString("\n")
            }
            return sEntries.subList(sEntries.size - maxLines, sEntries.size)
                .joinToString("\n")
        }
    }

    private fun header(): String = String.format(
        Locale.US,
        "===== BetterHeybox 运行检查点 =====\n构建=%s, SDK=%d, 设备=%s %s, 进程=%s",
        if (BuildFlags.DEBUG) "debug" else "release",
        Build.VERSION.SDK_INT,
        Build.MANUFACTURER, Build.MODEL,
        App.currentProcessName()
    )
}
