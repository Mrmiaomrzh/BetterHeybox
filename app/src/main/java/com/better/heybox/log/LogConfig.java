package com.better.heybox.log;

public final class LogConfig {

    public static final String DIR_NAME = "betterheybox";
    public static final String FILE_NAME = "log.txt";
    public static final String BACKUP_NAME = "log.1.txt";

    public final long maxFileBytes;
    public final int queueCapacity;
    public final long idlePollMs;
    public final int maxBatch;
    public final long flushWaitMs;
    public final long minFreeSpaceBytes;

    public LogConfig(long maxFileBytes, int queueCapacity, long idlePollMs, int maxBatch,
                      long flushWaitMs, long minFreeSpaceBytes) {
        this.maxFileBytes = maxFileBytes;
        this.queueCapacity = queueCapacity;
        this.idlePollMs = idlePollMs;
        this.maxBatch = maxBatch;
        this.flushWaitMs = flushWaitMs;
        this.minFreeSpaceBytes = minFreeSpaceBytes;
    }

    public static LogConfig defaultConfig() {
        return new LogConfig(
                512 * 1024L,
                4096,
                500L,
                256,
                1500L,
                10L * 1024 * 1024
        );
    }
}
