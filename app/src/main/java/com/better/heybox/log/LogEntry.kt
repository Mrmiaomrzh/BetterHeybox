package com.better.heybox.log

class LogEntry(
    val timestampMs: Long,
    val pid: Int,
    val level: Int,
    val tag: String,
    val message: String,
    val throwable: Throwable?
)
