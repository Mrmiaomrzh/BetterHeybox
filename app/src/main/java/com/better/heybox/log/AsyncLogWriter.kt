package com.better.heybox.log

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AsyncLogWriter(private val storage: LogStorage, private val config: LogConfig) : LogWriter {

    private val queue = LinkedBlockingQueue<LogEntry>(config.queueCapacity)
    private val pending = AtomicLong()
    private val written = AtomicLong()
    private val dropped = AtomicLong()

    private val writerLock = Any()

    @Volatile
    private var writerThread: Thread? = null

    override fun write(entry: LogEntry) {
        try {
            ensureWriter()
            pending.incrementAndGet()
            if (queue.offer(entry)) {
                if (entry.level >= Log.WARN) {
                    writerThread?.interrupt()
                }
            } else {
                pending.decrementAndGet()
                dropped.incrementAndGet()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun ensureWriter() {
        if (writerThread != null) {
            return
        }
        synchronized(writerLock) {
            if (writerThread != null) {
                return
            }
            val t = Thread(this::writeLoop, "betterheybox-log")
            t.isDaemon = true
            writerThread = t
            t.start()
        }
    }

    private fun writeLoop() {
        while (true) {
            var entry: LogEntry? = null
            try {
                entry = queue.poll(config.idlePollMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                entry = queue.poll()
            }
            if (entry == null) {
                storage.flush()
                continue
            }
            var batch = 0
            while (batch < config.maxBatch && entry != null) {
                writeOne(entry)
                batch++
                entry = queue.poll()
            }
            storage.flush()
        }
    }

    private fun writeOne(entry: LogEntry) {
        try {
            if (storage.appendLine(LogLineFormatter.format(entry))) {
                written.incrementAndGet()
            } else {
                dropped.incrementAndGet()
            }
        } catch (ignored: Throwable) {
            dropped.incrementAndGet()
        } finally {
            pending.decrementAndGet()
        }
    }

    override fun flush() {
        val deadline = android.os.SystemClock.uptimeMillis() + config.flushWaitMs
        while (pending.get() > 0 && android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(5L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        storage.flush()
    }

    override fun getStats(): LogWriterStats {
        val w = writerThread
        return LogWriterStats(
            queue.size.toLong(), pending.get(), written.get(), dropped.get(),
            w != null && w.isAlive
        )
    }
}
