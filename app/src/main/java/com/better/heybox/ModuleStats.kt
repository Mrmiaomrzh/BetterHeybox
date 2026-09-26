package com.better.heybox

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ModuleStats {

    @JvmField val commentCopyHelperCalls = AtomicInteger()

    @JvmField val commentCopyWithRecord = AtomicInteger()

    @JvmField val commentCopyNoRecord = AtomicInteger()

    @JvmField val commentDfsRuns = AtomicInteger()

    @JvmField val commentDfsBudgetHits = AtomicInteger()

    @JvmField val commentDfsNodes = AtomicLong()

    @JvmField val commentDfsMillis = AtomicLong()

    @JvmField val commentCopyMatched = AtomicInteger()

    @JvmField val commentCopyNoMatch = AtomicInteger()

    @JvmField val dailyTaskResumeChecks = AtomicInteger()

    @JvmField val dailyTaskNoLink = AtomicInteger()

    @JvmField val slowOps = AtomicInteger()

    private const val SLOW_MS = 50L

    private val SLOW_BY_NAME = ConcurrentHashMap<String, AtomicInteger>()

    @JvmStatic
    fun slow(name: String?, ms: Long) {
        if (ms < SLOW_MS || name == null) {
            return
        }
        slowOps.incrementAndGet()
        val counter = SLOW_BY_NAME.computeIfAbsent(name) { AtomicInteger() }
        counter.incrementAndGet()
    }

    @JvmStatic
    fun snapshot(): String {
        val sb = StringBuilder(320)
        sb.append("评论自由复制：助手调用=").append(commentCopyHelperCalls.get())
            .append("（有长按记录=").append(commentCopyWithRecord.get())
            .append(" / 无长按记录放行=").append(commentCopyNoRecord.get())
            .append(" / 命中=").append(commentCopyMatched.get())
            .append(" / 未命中=").append(commentCopyNoMatch.get()).append("）")
        sb.append(" / 整树遍历=").append(commentDfsRuns.get())
            .append(" 次，访问 ").append(commentDfsNodes.get())
            .append(" 个 View，累计 ").append(commentDfsMillis.get()).append(" ms")
        sb.append('\n').append("每日任务：onResume 检查=").append(dailyTaskResumeChecks.get())
            .append(" / 未配置链接=").append(dailyTaskNoLink.get())
        sb.append('\n').append("慢操作(>").append(SLOW_MS).append("ms)=").append(slowOps.get())
        if (SLOW_BY_NAME.isNotEmpty()) {
            sb.append("：")
            var first = true
            for ((key, value) in SLOW_BY_NAME) {
                if (!first) {
                    sb.append(", ")
                }
                first = false
                sb.append(key).append('×').append(value.get())
            }
        }
        return sb.toString()
    }
}
