package com.better.heybox

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object LogRecorder {

    private const val TAG = "BetterHeybox"
    private const val DIR_NAME = "betterheybox"
    private const val FILE_NAME = "log.txt"
    private const val BACKUP_NAME = "log.1.txt"
    private const val MAX_BYTES = 512 * 1024

    private const val QUEUE_CAPACITY = 4096
    private const val IDLE_POLL_MS = 500L
    private const val MAX_BATCH = 256
    private const val FLUSH_WAIT_MS = 1500L

    @Volatile private var sContext: Context? = null
    @Volatile private var sEnabled = false
    @Volatile private var sVerbose = false

    private val IO_LOCK = Any()
    private var sOut: BufferedOutputStream? = null
    private var sWrittenBytes = 0L

    private val sQueue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private val sPending = AtomicLong()
    private val sDropped = AtomicLong()

    @Volatile private var sWriter: Thread? = null
    @Volatile private var sWriterStop = false

    private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @JvmStatic
    fun setContext(context: Context?) {
        if (context != null && sContext == null) {
            sContext = context.applicationContext
        }
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
        if (!sEnabled || msg == null) {
            return
        }
        if (!sVerbose && level < Log.WARN) {
            return
        }
        enqueue(level, tag, msg, null)
    }

    @JvmStatic
    fun record(level: Int, tag: String?, msg: String?, tr: Throwable?) {
        if (!sEnabled) {
            return
        }
        if (!sVerbose && level < Log.WARN) {
            return
        }
        enqueue(level, tag, msg, tr)
    }

    @JvmStatic
    fun recordEvent(msg: String) {
        record(Log.INFO, TAG, msg)
    }

    private fun enqueue(level: Int, tag: String?, msg: String?, tr: Throwable?) {
        try {
            ensureWriter()
            val line = formatLine(level, tag, msg, tr)
            if (sQueue.offer(line)) {
                sPending.incrementAndGet()
                if (level >= Log.WARN) {
                    sWriter?.interrupt()
                }
            } else {
                sDropped.incrementAndGet()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun ensureWriter() {
        if (sWriter != null) {
            return
        }
        synchronized(IO_LOCK) {
            if (sWriter != null) {
                return
            }
            sWriterStop = false
            val t = Thread({ writeLoop() }, "betterheybox-log")
            t.isDaemon = true
            sWriter = t
            t.start()
        }
    }

    private fun writeLoop() {
        while (!sWriterStop) {
            var line: String?
            try {
                line = sQueue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                line = sQueue.poll()
            }
            if (line == null) {
                flushStream()
                continue
            }
            var batch = 0
            do {
                writeOne(line!!)
                batch++
                line = sQueue.poll()
            } while (batch < MAX_BATCH && line != null)
            flushStream()
        }
        var rest: String?
        while (sQueue.poll().also { rest = it } != null) {
            writeOne(rest!!)
        }
        flushStream()
        synchronized(IO_LOCK) {
            closeStreamLocked()
        }
    }

    private fun writeOne(line: String) {
        try {
            var ctx = sContext
            if (ctx == null) {
                ctx = App.resolveAppContext() ?: return
                sContext = ctx
            }
            val dir = File(ctx.filesDir, DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                return
            }
            val file = File(dir, FILE_NAME)
            synchronized(IO_LOCK) {
                if (sWrittenBytes > MAX_BYTES) {
                    rotateLocked(dir, file)
                }
                if (sOut == null) {
                    if (sWrittenBytes == 0L && file.isFile) {
                        sWrittenBytes = file.length()
                    }
                    sOut = BufferedOutputStream(FileOutputStream(file, true), 16 * 1024)
                }
                val data = line.toByteArray(StandardCharsets.UTF_8)
                sOut?.write(data)
                sWrittenBytes += data.size
            }
        } catch (ignored: Throwable) {
        } finally {
            sPending.decrementAndGet()
        }
    }

    private fun flushStream() {
        synchronized(IO_LOCK) {
            try {
                sOut?.flush()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun rotateLocked(dir: File, file: File) {
        closeStreamLocked()
        val backup = File(dir, BACKUP_NAME)
        backup.delete()
        file.renameTo(backup)
        sWrittenBytes = 0
    }

    private fun closeStreamLocked() {
        try {
            sOut?.flush()
            sOut?.close()
        } catch (ignored: Throwable) {
        }
        sOut = null
        sWrittenBytes = 0
    }

    @JvmStatic
    fun flush() {
        val deadline = SystemClock.uptimeMillis() + FLUSH_WAIT_MS
        while (sPending.get() > 0 && SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(5L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        flushStream()
    }

    @JvmStatic
    fun dropInfo(): String? {
        val d = sDropped.get()
        return if (d == 0L) null else "丢弃 $d 行"
    }

    @JvmStatic
    fun getLogFilePath(): String? {
        val ctx = sContext ?: App.resolveAppContext() ?: return null
        return File(File(ctx.filesDir, DIR_NAME), FILE_NAME).absolutePath
    }

    @JvmStatic
    fun getLogBackupFilePath(): String? {
        val ctx = sContext ?: App.resolveAppContext() ?: return null
        return File(File(ctx.filesDir, DIR_NAME), BACKUP_NAME).absolutePath
    }

    @JvmStatic
    fun readTail(maxLines: Int): String? {
        flush()
        val ctx = sContext ?: App.resolveAppContext() ?: return null
        val file = File(File(ctx.filesDir, DIR_NAME), FILE_NAME)
        if (!file.isFile || file.length() == 0L) {
            return null
        }
        return try {
            val data: ByteArray = file.inputStream().use { input ->
                val skip = maxOf(0L, file.length() - 256L * 1024L)
                var skipped = 0L
                while (skipped < skip) {
                    val step = input.skip(skip - skipped)
                    if (step <= 0L) {
                        break
                    }
                    skipped += step
                }
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            val text = String(data, StandardCharsets.UTF_8)
            val lines = text.split("\n")
            if (lines.size <= maxLines) {
                text
            } else {
                val sb = StringBuilder()
                for (i in lines.size - maxLines until lines.size) {
                    sb.append(lines[i]).append('\n')
                }
                sb.toString()
            }
        } catch (t: Throwable) {
            null
        }
    }

    @JvmStatic
    fun sizeInfo(): String {
        flush()
        val ctx = sContext ?: App.resolveAppContext() ?: return "?"
        val dir = File(ctx.filesDir, DIR_NAME)
        var total = 0L
        var count = 0
        for (name in arrayOf(FILE_NAME, BACKUP_NAME)) {
            val file = File(dir, name)
            if (file.isFile) {
                total += file.length()
                count++
            }
        }
        return if (count == 0) "无日志文件" else "$count 个文件 / ${total / 1024} KB"
    }

    @JvmStatic
    fun clear(): String? {
        flush()
        synchronized(IO_LOCK) {
            closeStreamLocked()
            val ctx = sContext ?: App.resolveAppContext() ?: return null
            val dir = File(ctx.filesDir, DIR_NAME)
            var freed = 0L
            var removed = 0
            for (name in arrayOf(FILE_NAME, BACKUP_NAME)) {
                val file = File(dir, name)
                if (!file.isFile) {
                    continue
                }
                freed += file.length()
                if (file.delete()) {
                    removed++
                }
            }
            return if (removed == 0) null else "$removed 个文件 / ${freed / 1024} KB"
        }
    }

    private fun formatLine(level: Int, tag: String?, msg: String?, tr: Throwable?): String {
        val sb = StringBuilder(160)
        sb.append(TIME_FORMAT.get().format(Date()))
        sb.append(' ').append(levelChar(level))
        sb.append('/').append(tag ?: TAG)
        sb.append(" [pid=").append(Process.myPid()).append("] ")
        sb.append(msg).append('\n')
        if (tr != null) {
            val sw = StringWriter()
            tr.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun levelChar(level: Int): Char = when (level) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }
}
