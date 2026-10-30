package com.better.heybox.log

class LogWriterStats(
    val queueSize: Long,
    val pendingCount: Long,
    val writtenCount: Long,
    val droppedCount: Long,
    val writerAlive: Boolean
) {
    override fun toString(): String =
        "LogWriterStats{queue=$queueSize, pending=$pendingCount" +
                ", written=$writtenCount, dropped=$droppedCount" +
                ", writerAlive=$writerAlive}"
}
