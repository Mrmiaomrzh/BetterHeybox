package com.better.heybox.log

interface LogStorage {

    fun appendLine(line: String): Boolean

    fun flush()

    fun getPrimaryPath(): String?

    fun getBackupPath(): String?

    fun readTail(maxLines: Int): String?

    fun sizeInfo(): String

    fun clear(): String?
}
