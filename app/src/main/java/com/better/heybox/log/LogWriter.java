package com.better.heybox.log;

public interface LogWriter {

    void write(LogEntry entry);

    void flush();

    LogWriterStats getStats();
}
