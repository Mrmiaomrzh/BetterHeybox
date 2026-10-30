package com.better.heybox.log

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CrashWriteExecutor {

    private val lock = Any()
    private val queue = LinkedBlockingQueue<Runnable>()

    @Volatile
    private var thread: Thread? = null

    fun runAndAwait(task: Runnable) {
        val latch = CountDownLatch(1)
        synchronized(lock) {
            ensureThreadLocked()
        }
        queue.offer {
            try {
                task.run()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun ensureThreadLocked() {
        val current = thread
        if (current != null && current.isAlive) {
            return
        }
        val t = Thread(this::loop, "betterheybox-crash-writer")
        t.isDaemon = true
        thread = t
        t.start()
    }

    private fun loop() {
        while (true) {
            val task: Runnable
            try {
                task = queue.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            try {
                task.run()
            } catch (ignored: Throwable) {
            }
        }
    }

    companion object {
        private const val AWAIT_TIMEOUT_MS = 3000L
    }
}
