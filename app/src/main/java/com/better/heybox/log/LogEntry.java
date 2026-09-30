package com.better.heybox.log;

public final class LogEntry {

    public final long timestampMs;
    public final int pid;
    public final int level;
    public final String tag;
    public final String message;
    public final Throwable throwable;

    public LogEntry(long timestampMs, int pid, int level, String tag, String message, Throwable throwable) {
        this.timestampMs = timestampMs;
        this.pid = pid;
        this.level = level;
        this.tag = tag;
        this.message = message;
        this.throwable = throwable;
    }
}
