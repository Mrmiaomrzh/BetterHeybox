package com.better.heybox.log

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class FileLogStorage(
    private val contextProvider: ContextProvider,
    private val config: LogConfig
) : LogStorage {

    fun interface ContextProvider {
        fun get(): Context?
    }

    private val lock = Any()
    private var out: BufferedOutputStream? = null
    private var writtenBytes = 0L

    @Volatile
    private var lowSpaceWarned = false

    @Volatile
    private var lowSpaceDropCount = 0L

    override fun appendLine(line: String): Boolean {
        try {
            val ctx = contextProvider.get() ?: return false
            val dir = File(ctx.filesDir, LogConfig.DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                return false
            }
            val file = File(dir, LogConfig.FILE_NAME)
            val data = line.toByteArray(StandardCharsets.UTF_8)
            synchronized(lock) {
                if (out == null && writtenBytes == 0L && file.isFile) {
                    writtenBytes = file.length()
                }
                if (writtenBytes > 0L && writtenBytes + data.size > config.maxFileBytes) {
                    rotateLocked(dir, file)
                }
                if (!hasEnoughSpaceLocked(dir, data.size)) {
                    return false
                }
                if (out == null) {
                    out = BufferedOutputStream(FileOutputStream(file, true), 16 * 1024)
                }
                out!!.write(data)
                writtenBytes += data.size.toLong()
                return true
            }
        } catch (ignored: Throwable) {
            return false
        }
    }

    private fun hasEnoughSpaceLocked(dir: File, incomingBytes: Int): Boolean {
        try {
            val usable = dir.usableSpace
            if (usable > 0 && usable - incomingBytes < config.minFreeSpaceBytes) {
                lowSpaceDropCount++
                if (!lowSpaceWarned) {
                    lowSpaceWarned = true
                    Log.w(
                        TAG, "剩余空间不足，暂停写日志: usable=" + usable +
                                "B threshold=" + config.minFreeSpaceBytes +
                                "B dir=" + dir.absolutePath
                    )
                }
                return false
            }
            if (lowSpaceWarned) {
                lowSpaceWarned = false
                Log.i(TAG, "剩余空间已恢复，继续写日志: usable=" + usable + "B")
            }
            return true
        } catch (ignored: Throwable) {
            return true
        }
    }

    fun lowSpaceDropCount(): Long = lowSpaceDropCount

    override fun flush() {
        synchronized(lock) {
            try {
                out?.flush()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun rotateLocked(dir: File, file: File) {
        closeStreamLocked()
        val backup = File(dir, LogConfig.BACKUP_NAME)
        backup.delete()
        file.renameTo(backup)
        writtenBytes = 0
    }

    private fun closeStreamLocked() {
        try {
            out?.let {
                it.flush()
                it.close()
            }
        } catch (ignored: Throwable) {
        }
        out = null
        writtenBytes = 0
    }

    override fun getPrimaryPath(): String? {
        val ctx = contextProvider.get() ?: return null
        return File(File(ctx.filesDir, LogConfig.DIR_NAME), LogConfig.FILE_NAME)
            .absolutePath
    }

    override fun getBackupPath(): String? {
        val ctx = contextProvider.get() ?: return null
        return File(File(ctx.filesDir, LogConfig.DIR_NAME), LogConfig.BACKUP_NAME)
            .absolutePath
    }

    override fun readTail(maxLines: Int): String? {
        flush()
        val ctx = contextProvider.get() ?: return null
        val file = File(File(ctx.filesDir, LogConfig.DIR_NAME), LogConfig.FILE_NAME)
        if (!file.isFile || file.length() == 0L) {
            return null
        }
        return try {
            val data: ByteArray
            FileInputStream(file).use { input ->
                val skip = maxOf(0L, file.length() - 256L * 1024L)
                var skipped = 0L
                while (skipped < skip) {
                    val step = input.skip(skip - skipped)
                    if (step <= 0) {
                        break
                    }
                    skipped += step
                }
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var read: Int
                while (input.read(chunk).also { read = it } != -1) {
                    buffer.write(chunk, 0, read)
                }
                data = buffer.toByteArray()
            }
            val text = String(data, StandardCharsets.UTF_8)
            val lines = text.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (lines.size <= maxLines) {
                return text
            }
            val sb = StringBuilder()
            for (i in lines.size - maxLines until lines.size) {
                sb.append(lines[i]).append('\n')
            }
            sb.toString()
        } catch (t: Throwable) {
            null
        }
    }

    override fun sizeInfo(): String {
        flush()
        val ctx = contextProvider.get() ?: return "?"
        val dir = File(ctx.filesDir, LogConfig.DIR_NAME)
        var total = 0L
        var count = 0
        for (name in arrayOf(LogConfig.FILE_NAME, LogConfig.BACKUP_NAME)) {
            val file = File(dir, name)
            if (file.isFile) {
                total += file.length()
                count++
            }
        }
        val base = if (count == 0) "无日志文件" else "$count 个文件 / " + (total / 1024) + " KB"
        return if (lowSpaceDropCount == 0L) base
        else base + "（剩余空间不足丢弃 " + lowSpaceDropCount + " 次）"
    }

    override fun clear(): String? {
        flush()
        synchronized(lock) {
            closeStreamLocked()
            val ctx = contextProvider.get() ?: return null
            val dir = File(ctx.filesDir, LogConfig.DIR_NAME)
            var freed = 0L
            var removed = 0
            for (name in arrayOf(LogConfig.FILE_NAME, LogConfig.BACKUP_NAME)) {
                val file = File(dir, name)
                if (!file.isFile) {
                    continue
                }
                freed += file.length()
                if (file.delete()) {
                    removed++
                }
            }
            return if (removed == 0) null else "$removed 个文件 / " + (freed / 1024) + " KB"
        }
    }

    companion object {
        private const val TAG = "BetterHeybox.LogStorage"
    }
}
