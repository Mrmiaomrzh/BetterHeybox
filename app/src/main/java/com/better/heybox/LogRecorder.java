package com.better.heybox;

import android.content.Context;
import android.util.Log;

import com.better.heybox.log.AsyncLogWriter;
import com.better.heybox.log.CrashWriteExecutor;
import com.better.heybox.log.FileLogStorage;
import com.better.heybox.log.LogConfig;
import com.better.heybox.log.LogEntry;
import com.better.heybox.log.LogStorage;
import com.better.heybox.log.LogWriter;
import com.better.heybox.log.LogWriterStats;
import com.better.heybox.log.SyncLogWriter;

public final class LogRecorder {

    private static final String TAG = "BetterHeybox";

    private static volatile Context sContext;
    private static volatile boolean sEnabled;
    private static volatile boolean sVerbose;

    private static final LogConfig CONFIG = LogConfig.defaultConfig();
    private static final LogStorage STORAGE = new FileLogStorage(LogRecorder::resolveContext, CONFIG);
    private static final LogWriter ASYNC_WRITER = new AsyncLogWriter(STORAGE, CONFIG);
    private static final LogWriter SYNC_WRITER = new SyncLogWriter(STORAGE);
    private static final CrashWriteExecutor CRASH_EXECUTOR = new CrashWriteExecutor();

    private LogRecorder() {
    }

    public static void setContext(Context context) {
        if (context != null && sContext == null) {
            sContext = context.getApplicationContext();
        }
    }

    private static Context resolveContext() {
        Context ctx = sContext;
        if (ctx != null) {
            return ctx;
        }
        ctx = App.resolveAppContext();
        if (ctx != null) {
            sContext = ctx;
        }
        return ctx;
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static void setVerbose(boolean verbose) {
        sVerbose = verbose;
    }

    public static boolean isVerbose() {
        return sVerbose;
    }

    public static void record(int level, String tag, String msg) {
        record(level, tag, msg, null);
    }

    public static void record(int level, String tag, String msg, Throwable tr) {
        if (!sEnabled || msg == null) {
            return;
        }
        if (!sVerbose && level < Log.WARN) {
            return;
        }
        ASYNC_WRITER.write(newEntry(level, tag, msg, tr));
    }

    public static void recordEvent(String msg) {
        record(Log.INFO, TAG, msg);
    }

    public static void recordBlocking(boolean force, int level, String tag, String msg,
                                       Throwable tr) {
        if (!force) {
            if (!sEnabled) {
                return;
            }
            if (!sVerbose && level < Log.WARN) {
                return;
            }
        }
        LogEntry entry = newEntry(level, tag, msg, tr);
        CRASH_EXECUTOR.runAndAwait(() -> SYNC_WRITER.write(entry));
    }

    public static void recordBlocking(int level, String tag, String msg, Throwable tr) {
        recordBlocking(true, level, tag, msg, tr);
    }

    public static void recordBlocking(int level, String tag, String msg) {
        recordBlocking(level, tag, msg, null);
    }

    public static void recordBlockingGated(int level, String tag, String msg) {
        recordBlocking(false, level, tag, msg, null);
    }

    private static LogEntry newEntry(int level, String tag, String msg, Throwable tr) {
        return new LogEntry(System.currentTimeMillis(), android.os.Process.myPid(), level,
                tag == null ? TAG : tag, msg == null ? "" : msg, tr);
    }

    public static void flush() {
        ASYNC_WRITER.flush();
    }

    public static String dropInfo() {
        long d = ASYNC_WRITER.getStats().droppedCount;
        return d == 0 ? null : ("丢弃 " + d + " 行");
    }

    public static LogWriterStats stats() {
        return ASYNC_WRITER.getStats();
    }

    public static String getLogFilePath() {
        return STORAGE.getPrimaryPath();
    }

    public static String getLogBackupFilePath() {
        return STORAGE.getBackupPath();
    }

    public static String readTail(int maxLines) {
        flush();
        return STORAGE.readTail(maxLines);
    }

    public static String sizeInfo() {
        flush();
        return STORAGE.sizeInfo();
    }

    public static String clear() {
        flush();
        return STORAGE.clear();
    }
}
