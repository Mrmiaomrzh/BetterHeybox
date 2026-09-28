package com.better.heybox.watch

import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object WatchSeen {

    private val LOCK = Any()

    private var sSeen: MutableSet<String>? = null
    private var sModule: MainModule? = null
    private var sDirty = false

    private val sWriter: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            val t = Thread(r, "betterheybox-seen")
            t.isDaemon = true
            t
        }

    private const val PERSIST_DELAY_MS = 2000L

    private val sPersistScheduled = AtomicBoolean()

    private fun schedulePersist() {
        if (sPersistScheduled.compareAndSet(false, true)) {
            try {
                sWriter.schedule(
                    Runnable {
                        sPersistScheduled.set(false)
                        flushNow()
                    },
                    PERSIST_DELAY_MS, TimeUnit.MILLISECONDS
                )
            } catch (ignored: Throwable) {
                sPersistScheduled.set(false)
            }
        }
    }

    @JvmStatic
    fun flushNow() {
        val joined = synchronized(LOCK) {
            val seen = sSeen
            if (!sDirty || seen == null) {
                return
            }
            sDirty = false
            var list: MutableList<String> = ArrayList(seen)
            if (list.size > WatchConfig.SEEN_LIMIT) {
                list = list.subList(list.size - WatchConfig.SEEN_LIMIT, list.size)
                seen.clear()
                seen.addAll(list)
            }
            list.joinToString(",")
        }
        HeyboxPrefs.setString(App.KEY_WATCH_SEEN, joined)
    }

    @JvmStatic
    fun init(module: MainModule) {
        sModule = module
    }

    private fun seen(): MutableSet<String> {
        val cur = sSeen
        if (cur != null) {
            return cur
        }
        val set: MutableSet<String> = LinkedHashSet()
        val raw = HeyboxPrefs.getString(App.KEY_WATCH_SEEN, "")!!
        for (s in raw.split(Regex(","))) {
            val v = s.trim()
            if (!v.isEmpty()) {
                set.add(v)
            }
        }
        sSeen = set
        return set
    }

    @JvmStatic
    fun markNew(linkId: String?): Boolean {
        if (linkId == null || linkId.isEmpty()) {
            return false
        }
        synchronized(LOCK) {
            val set = seen()
            if (set.contains(linkId)) {
                return false
            }
            set.add(linkId)
            sDirty = true
        }
        schedulePersist()
        return true
    }

    @JvmStatic
    fun contains(linkId: String?): Boolean {
        synchronized(LOCK) {
            return seen().contains(linkId)
        }
    }

    @JvmStatic
    fun size(): Int {
        synchronized(LOCK) {
            return seen().size
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(LOCK) {
            sSeen = LinkedHashSet()
            sDirty = true
        }
        flushNow()
    }

    @JvmStatic
    fun shutdown() {
        try {
            flushNow()
        } catch (ignored: Throwable) {
        }
        synchronized(LOCK) {
            sSeen = null
            sBaselined = null
            sDirty = false
            sModule = null
        }
        try {
            sWriter.shutdownNow()
        } catch (ignored: Throwable) {
        }
    }


    private var sBaselined: MutableSet<String>? = null

    private fun baselined(): MutableSet<String> {
        val cur = sBaselined
        if (cur != null) {
            return cur
        }
        val set: MutableSet<String> = LinkedHashSet()
        val raw = HeyboxPrefs.getString(App.KEY_WATCH_BASELINED, "")!!
        for (s in raw.split(Regex(","))) {
            val v = s.trim()
            if (!v.isEmpty()) {
                set.add(v)
            }
        }
        sBaselined = set
        return set
    }

    @JvmStatic
    fun isBaselined(userId: String?): Boolean {
        if (userId == null || userId.isEmpty()) {
            return true
        }
        synchronized(LOCK) {
            return baselined().contains(userId)
        }
    }

    @JvmStatic
    fun markBaselined(userId: String?) {
        if (userId == null || userId.isEmpty()) {
            return
        }
        val joined = synchronized(LOCK) {
            val set = baselined()
            set.add(userId)
            set.joinToString(",")
        }
        HeyboxPrefs.setString(App.KEY_WATCH_BASELINED, joined)
    }

    @JvmStatic
    fun clearBaselines() {
        synchronized(LOCK) {
            sBaselined = LinkedHashSet()
        }
        HeyboxPrefs.setString(App.KEY_WATCH_BASELINED, "")
    }

    @JvmStatic
    fun touchCheck() {
        HeyboxPrefs.setString(App.KEY_WATCH_LAST_CHECK, stringify(System.currentTimeMillis()))
    }

    @JvmStatic
    fun lastCheck(): Long {
        try {
            return HeyboxPrefs.getString(App.KEY_WATCH_LAST_CHECK, "0")!!.trim().toLong()
        } catch (ignored: Throwable) {
            return 0L
        }
    }
}

private fun stringify(v: Any?): String = if (v == null) "null" else v.toString()
