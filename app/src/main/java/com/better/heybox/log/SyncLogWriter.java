package com.better.heybox.log;

import java.util.concurrent.atomic.AtomicLong;

public final class SyncLogWriter implements LogWriter {

    private final LogStorage storage;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public SyncLogWriter(LogStorage storage) {
        this.storage = storage;
    }

    @Override
    public void write(LogEntry entry) {
        try {
            if (storage.appendLine(LogLineFormatter.format(entry))) {
                written.incrementAndGet();
            } else {
                dropped.incrementAndGet();
            }
        } catch (Throwable ignored) {
            dropped.incrementAndGet();
        } finally {
            storage.flush();
        }
    }

    @Override
    public void flush() {
        storage.flush();
    }

    @Override
    public LogWriterStats getStats() {
        return new LogWriterStats(0, 0, written.get(), dropped.get(), true);
    }
}
