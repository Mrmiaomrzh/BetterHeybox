package com.better.heybox.log

class LogConfig(
    val maxFileBytes: Long,
    val queueCapacity: Int,
    val idlePollMs: Long,
    val maxBatch: Int,
    val flushWaitMs: Long,
    val minFreeSpaceBytes: Long
) {
    companion object {
        const val DIR_NAME = "betterheybox"
        const val FILE_NAME = "log.txt"
        const val BACKUP_NAME = "log.1.txt"

        @JvmStatic
        fun defaultConfig(): LogConfig = LogConfig(
            512 * 1024L,
            4096,
            500L,
            256,
            1500L,
            10L * 1024 * 1024
        )
    }
}
