package com.better.heybox.log;

public final class LogWriterStats {

    public final long queueSize;
    public final long pendingCount;
    public final long writtenCount;
    public final long droppedCount;
    public final boolean writerAlive;

    public LogWriterStats(long queueSize, long pendingCount, long writtenCount,
                           long droppedCount, boolean writerAlive) {
        this.queueSize = queueSize;
        this.pendingCount = pendingCount;
        this.writtenCount = writtenCount;
        this.droppedCount = droppedCount;
        this.writerAlive = writerAlive;
    }

    @Override
    public String toString() {
        return "LogWriterStats{queue=" + queueSize + ", pending=" + pendingCount
                + ", written=" + writtenCount + ", dropped=" + droppedCount
                + ", writerAlive=" + writerAlive + "}";
    }
}
