package com.better.heybox

import android.content.Context
import android.os.Process
import android.util.Log
import com.better.heybox.log.AsyncLogWriter
import com.better.heybox.log.CrashWriteExecutor
import com.better.heybox.log.FileLogStorage
import com.better.heybox.log.LogConfig
import com.better.heybox.log.LogEntry
import com.better.heybox.log.LogStorage
import com.better.heybox.log.LogWriter
import com.better.heybox.log.LogWriterStats
import com.better.heybox.log.SyncLogWriter

object LogRecorder {

    private const val TAG = "BetterHeybox"

    @Volatile
    private var sContext: Context? = null

    @Volatile
    private var sEnabled = false

    @Volatile
    private var sVerbose = false

    private val CONFIG: LogConfig = LogConfig.defaultConfig()
    private val STORAGE: LogStorage = FileLogStorage(::resolveContext, CONFIG)
    private val ASYNC_WRITER: LogWriter = AsyncLogWriter(STORAGE, CONFIG)
    private val SYNC_WRITER: LogWriter = SyncLogWriter(STORAGE)
    private val CRASH_EXECUTOR = CrashWriteExecutor()

    @JvmStatic
    fun setContext(context: Context?) {
        if (context != null && sContext == null) {
            sContext = context.applicationContext
        }
    }

    private fun resolveContext(): Context? {
        var ctx = sContext
        if (ctx != null) {
            return ctx
        }
        ctx = App.resolveAppContext()
        if (ctx != null) {
            sContext = ctx
        }
        return ctx
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        sEnabled = enabled
    }

    @JvmStatic
    fun setVerbose(verbose: Boolean) {
        sVerbose = verbose
    }

    @JvmStatic
    fun isVerbose(): Boolean = sVerbose

    @JvmStatic
    fun record(level: Int, tag: String?, msg: String?) {
        record(level, tag, msg, null)
    }

    @JvmStatic
    fun record(level: Int, tag: String?, msg: String?, tr: Throwable?) {
        if (!sEnabled || msg == null) {
            return
        }
        if (!sVerbose && level < Log.WARN) {
            return
        }
        ASYNC_WRITER.write(newEntry(level, tag, msg, tr))
    }

    @JvmStatic
    fun recordEvent(msg: String) {
        record(Log.INFO, TAG, msg)
    }

    @JvmStatic
    fun recordBlocking(force: Boolean, level: Int, tag: String?, msg: String?, tr: Throwable?) {
        if (!force) {
            if (!sEnabled) {
                return
            }
            if (!sVerbose && level < Log.WARN) {
                return
            }
        }
        val entry = newEntry(level, tag, msg, tr)
        CRASH_EXECUTOR.runAndAwait { SYNC_WRITER.write(entry) }
    }

    @JvmStatic
    fun recordBlocking(level: Int, tag: String?, msg: String?, tr: Throwable?) {
        recordBlocking(true, level, tag, msg, tr)
    }

    @JvmStatic
    fun recordBlocking(level: Int, tag: String?, msg: String?) {
        recordBlocking(level, tag, msg, null)
    }

    @JvmStatic
    fun recordBlockingGated(level: Int, tag: String?, msg: String?) {
        recordBlocking(false, level, tag, msg, null)
    }

    private fun newEntry(level: Int, tag: String?, msg: String?, tr: Throwable?): LogEntry =
        LogEntry(
            System.currentTimeMillis(), Process.myPid(), level,
            tag ?: TAG, msg ?: "", tr
        )

    @JvmStatic
    fun flush() {
        ASYNC_WRITER.flush()
    }

    @JvmStatic
    fun shutdown() {
        flush()
    }

    @JvmStatic
    fun dropInfo(): String? {
        val d = ASYNC_WRITER.getStats().droppedCount
        return if (d == 0L) null else "丢弃 $d 行"
    }

    @JvmStatic
    fun stats(): LogWriterStats = ASYNC_WRITER.getStats()

    @JvmStatic
    fun getLogFilePath(): String? = STORAGE.getPrimaryPath()

    @JvmStatic
    fun getLogBackupFilePath(): String? = STORAGE.getBackupPath()

    @JvmStatic
    fun readTail(maxLines: Int): String? {
        flush()
        return STORAGE.readTail(maxLines)
    }

    @JvmStatic
    fun sizeInfo(): String {
        flush()
        return STORAGE.sizeInfo()
    }

    @JvmStatic
    fun clear(): String? {
        flush()
        return STORAGE.clear()
    }
}
