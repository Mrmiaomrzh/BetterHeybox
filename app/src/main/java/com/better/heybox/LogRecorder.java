package com.better.heybox;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块文件日志（受「记录日志」开关控制）：写入 <filesDir>/betterheybox/log.txt，超限滚动为 log.1.txt（最多 2 个）。
 * 上下文：优先 setContext，兜底 ActivityThread.currentApplication()，拿不到则跳过文件写入
 */
public final class LogRecorder {

    private static final String TAG = "BetterHeybox";
    private static final String DIR_NAME = "betterheybox";
    private static final String FILE_NAME = "log.txt";
    private static final String BACKUP_NAME = "log.1.txt";
    private static final long MAX_BYTES = 512 * 1024;

    private static volatile Context sContext;
    private static volatile boolean sEnabled;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private LogRecorder() {
    }

    public static void setContext(Context context) {
        if (context != null && sContext == null) {
            sContext = context.getApplicationContext();
        }
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static void record(int level, String tag, String msg) {
        if (!sEnabled || msg == null) {
            return;
        }
        if (!Logs.shouldLog(level)) {
            return;
        }
        recordLocked(level, tag, msg, null);
    }

    public static void record(int level, String tag, String msg, Throwable tr) {
        if (!sEnabled) {
            return;
        }
        if (!Logs.shouldLog(level)) {
            return;
        }
        recordLocked(level, tag, msg, tr);
    }

    public static void recordEvent(String msg) {
        record(Log.INFO, TAG, msg);
    }

    public static String getLogFilePath() {
        Context ctx = sContext != null ? sContext : App.resolveAppContext();
        if (ctx == null) {
            return null;
        }
        return new File(new File(ctx.getFilesDir(), DIR_NAME), FILE_NAME).getAbsolutePath();
    }

    /** log.1.txt 备份路径 */
    public static String getLogBackupFilePath() {
        Context ctx = sContext != null ? sContext : App.resolveAppContext();
        if (ctx == null) {
            return null;
        }
        return new File(new File(ctx.getFilesDir(), DIR_NAME), BACKUP_NAME).getAbsolutePath();
    }

    private static void recordLocked(int level, String tag, String msg, Throwable tr) {
        synchronized (LOCK) {
            try {
                Context ctx = sContext;
                if (ctx == null) {
                    ctx = App.resolveAppContext();
                    if (ctx == null) {
                        return;
                    }
                    sContext = ctx;
                }
                File dir = new File(ctx.getFilesDir(), DIR_NAME);
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                File file = new File(dir, FILE_NAME);
                if (file.length() > MAX_BYTES) {
                    File backup = new File(dir, BACKUP_NAME);
                    //noinspection ResultOfMethodCallIgnored
                    backup.delete();
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(backup);
                }
                String line = formatLine(level, tag, msg, tr);
                try (FileOutputStream fos = new FileOutputStream(file, true);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    writer.write(line);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String formatLine(int level, String tag, String msg, Throwable tr) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(TIME_FORMAT.format(new Date()));
        sb.append(' ').append(levelChar(level));
        sb.append('/').append(tag == null ? TAG : tag);
        sb.append(" [pid=").append(android.os.Process.myPid()).append("] ");
        sb.append(msg).append('\n');
        if (tr != null) {
            StringWriter sw = new StringWriter();
            tr.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static char levelChar(int level) {
        switch (level) {
            case Log.VERBOSE:
                return 'V';
            case Log.DEBUG:
                return 'D';
            case Log.INFO:
                return 'I';
            case Log.WARN:
                return 'W';
            case Log.ERROR:
                return 'E';
            case Log.ASSERT:
                return 'A';
            default:
                return '?';
        }
    }
}
