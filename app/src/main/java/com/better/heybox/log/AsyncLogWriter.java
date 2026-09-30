package com.better.heybox.log;

import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class AsyncLogWriter implements LogWriter {

    private final LogStorage storage;
    private final LogConfig config;

    private final LinkedBlockingQueue<LogEntry> queue;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private final Object writerLock = new Object();
    private volatile Thread writerThread;

    public AsyncLogWriter(LogStorage storage, LogConfig config) {
        this.storage = storage;
        this.config = config;
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity);
    }

    @Override
    public void write(LogEntry entry) {
        try {
            ensureWriter();
            if (queue.offer(entry)) {
                pending.incrementAndGet();
                if (entry.level >= Log.WARN) {
                    Thread w = writerThread;
                    if (w != null) {
                        w.interrupt();
                    }
                }
            } else {
                dropped.incrementAndGet();
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureWriter() {
        if (writerThread != null) {
            return;
        }
        synchronized (writerLock) {
            if (writerThread != null) {
                return;
            }
            Thread t = new Thread(this::writeLoop, "betterheybox-log");
            t.setDaemon(true);
            writerThread = t;
            t.start();
        }
    }

    private void writeLoop() {
        while (true) {
            LogEntry entry;
            try {
                entry = queue.poll(config.idlePollMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                entry = queue.poll();
            }
            if (entry == null) {
                storage.flush();
                continue;
            }
            int batch = 0;
            do {
                writeOne(entry);
                batch++;
            } while (batch < config.maxBatch && (entry = queue.poll()) != null);
            storage.flush();
        }
    }

    private void writeOne(LogEntry entry) {
        try {
            if (storage.appendLine(LogLineFormatter.format(entry))) {
                written.incrementAndGet();
            } else {
                dropped.incrementAndGet();
            }
        } catch (Throwable ignored) {
            dropped.incrementAndGet();
        } finally {
            pending.decrementAndGet();
        }
    }

    @Override
    public void flush() {
        long deadline = android.os.SystemClock.uptimeMillis() + config.flushWaitMs;
        while (pending.get() > 0 && android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        storage.flush();
    }

    @Override
    public LogWriterStats getStats() {
        Thread w = writerThread;
        return new LogWriterStats(queue.size(), pending.get(), written.get(), dropped.get(),
                w != null && w.isAlive());
    }
}
