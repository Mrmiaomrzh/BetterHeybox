package com.better.heybox.log;

public interface LogStorage {

    boolean appendLine(String line);

    void flush();

    String getPrimaryPath();

    String getBackupPath();

    String readTail(int maxLines);

    String sizeInfo();

    String clear();
}
