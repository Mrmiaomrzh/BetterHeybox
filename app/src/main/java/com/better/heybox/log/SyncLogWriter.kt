package com.better.heybox.log

import java.util.concurrent.atomic.AtomicLong

class SyncLogWriter(private val storage: LogStorage) : LogWriter {

    private val written = AtomicLong()
    private val dropped = AtomicLong()

    override fun write(entry: LogEntry) {
        try {
            if (storage.appendLine(LogLineFormatter.format(entry))) {
                written.incrementAndGet()
            } else {
                dropped.incrementAndGet()
            }
        } catch (ignored: Throwable) {
            dropped.incrementAndGet()
        } finally {
            storage.flush()
        }
    }

    override fun flush() {
        storage.flush()
    }

    override fun getStats(): LogWriterStats =
        LogWriterStats(0, 0, written.get(), dropped.get(), true)
}
