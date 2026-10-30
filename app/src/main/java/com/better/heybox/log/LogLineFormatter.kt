package com.better.heybox.log

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogLineFormatter {

    private const val DEFAULT_TAG = "BetterHeybox"

    private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @JvmStatic
    fun format(entry: LogEntry): String {
        val sb = StringBuilder(160)
        sb.append(TIME_FORMAT.get()!!.format(Date(entry.timestampMs)))
        sb.append(' ').append(levelChar(entry.level))
        sb.append('/').append(if (entry.tag.isEmpty()) DEFAULT_TAG else entry.tag)
        sb.append(" [pid=").append(entry.pid).append("] ")
        sb.append(entry.message).append('\n')
        if (entry.throwable != null) {
            val sw = StringWriter()
            entry.throwable.printStackTrace(PrintWriter(sw))
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
