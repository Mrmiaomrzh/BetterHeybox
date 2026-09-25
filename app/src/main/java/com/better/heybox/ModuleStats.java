package com.better.heybox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ModuleStats {

    public static final AtomicInteger commentCopyHelperCalls = new AtomicInteger();
    public static final AtomicInteger commentCopyWithRecord = new AtomicInteger();
    public static final AtomicInteger commentCopyNoRecord = new AtomicInteger();
    public static final AtomicInteger commentDfsRuns = new AtomicInteger();
    public static final AtomicInteger commentDfsBudgetHits = new AtomicInteger();
    public static final AtomicLong commentDfsNodes = new AtomicLong();
    public static final AtomicLong commentDfsMillis = new AtomicLong();
    public static final AtomicInteger commentCopyMatched = new AtomicInteger();
    public static final AtomicInteger commentCopyNoMatch = new AtomicInteger();

    public static final AtomicInteger dailyTaskResumeChecks = new AtomicInteger();
    public static final AtomicInteger dailyTaskNoLink = new AtomicInteger();

    public static final AtomicInteger slowOps = new AtomicInteger();

    private static final long SLOW_MS = 50L;
    private static final Map<String, AtomicInteger> SLOW_BY_NAME = new ConcurrentHashMap<>();

    private ModuleStats() {
    }

    public static void slow(String name, long ms) {
        if (ms < SLOW_MS || name == null) {
            return;
        }
        slowOps.incrementAndGet();
        AtomicInteger counter = SLOW_BY_NAME.get(name);
        if (counter == null) {
            counter = SLOW_BY_NAME.computeIfAbsent(name, k -> new AtomicInteger());
        }
        counter.incrementAndGet();
    }

    public static String snapshot() {
        StringBuilder sb = new StringBuilder(320);
        sb.append("评论自由复制：助手调用=").append(commentCopyHelperCalls.get())
                .append("（有长按记录=").append(commentCopyWithRecord.get())
                .append(" / 无长按记录放行=").append(commentCopyNoRecord.get())
                .append(" / 命中=").append(commentCopyMatched.get())
                .append(" / 未命中=").append(commentCopyNoMatch.get()).append("）");
        sb.append(" / 整树遍历=").append(commentDfsRuns.get())
                .append(" 次，访问 ").append(commentDfsNodes.get())
                .append(" 个 View，累计 ").append(commentDfsMillis.get()).append(" ms");
        sb.append('\n').append("每日任务：onResume 检查=").append(dailyTaskResumeChecks.get())
                .append(" / 未配置链接=").append(dailyTaskNoLink.get());
        sb.append('\n').append("慢操作(>").append(SLOW_MS).append("ms)=").append(slowOps.get());
        if (!SLOW_BY_NAME.isEmpty()) {
            sb.append("：");
            boolean first = true;
            for (Map.Entry<String, AtomicInteger> entry : SLOW_BY_NAME.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(entry.getKey()).append('×').append(entry.getValue().get());
            }
        }
        return sb.toString();
    }
}
