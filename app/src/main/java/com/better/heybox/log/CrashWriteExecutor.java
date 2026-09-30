package com.better.heybox.log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class CrashWriteExecutor {

    private static final long AWAIT_TIMEOUT_MS = 3000L;

    private final Object lock = new Object();
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private volatile Thread thread;

    public void runAndAwait(Runnable task) {
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (lock) {
            ensureThreadLocked();
        }
        queue.offer(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureThreadLocked() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        Thread t = new Thread(this::loop, "betterheybox-crash-writer");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void loop() {
        while (true) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                task.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
