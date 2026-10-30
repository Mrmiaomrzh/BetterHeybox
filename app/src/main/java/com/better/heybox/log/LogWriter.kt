package com.better.heybox.log

interface LogWriter {

    fun write(entry: LogEntry)

    fun flush()

    fun getStats(): LogWriterStats
}
