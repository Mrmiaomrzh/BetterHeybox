package com.better.heybox;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LogRecorder {

    private static final String TAG = "BetterHeybox";
    private static final String DIR_NAME = "betterheybox";
    private static final String FILE_NAME = "log.txt";
    private static final String BACKUP_NAME = "log.1.txt";
    private static final long MAX_BYTES = 512 * 1024;

    private static volatile Context sContext;
    private static volatile boolean sEnabled;
    private static volatile boolean sVerbose;
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

    public static void setVerbose(boolean verbose) {
        sVerbose = verbose;
    }

    public static boolean isVerbose() {
        return sVerbose;
    }

    public static void record(int level, String tag, String msg) {
        if (!sEnabled || msg == null) {
            return;
        }
        if (!sVerbose && level < Log.WARN) {
            return;
        }
        recordLocked(level, tag, msg, null);
    }

    public static void record(int level, String tag, String msg, Throwable tr) {
        if (!sEnabled) {
            return;
        }
        if (!sVerbose && level < Log.WARN) {
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

    public static String readTail(int maxLines) {
        Context ctx = sContext != null ? sContext : App.resolveAppContext();
        if (ctx == null) {
            return null;
        }
        File file = new File(new File(ctx.getFilesDir(), DIR_NAME), FILE_NAME);
        if (!file.isFile() || file.length() == 0) {
            return null;
        }
        try {
            byte[] data;
            try (InputStream in = new FileInputStream(file)) {
                long skip = Math.max(0L, file.length() - 256L * 1024L);
                long skipped = 0;
                while (skipped < skip) {
                    long step = in.skip(skip - skipped);
                    if (step <= 0) {
                        break;
                    }
                    skipped += step;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                data = buffer.toByteArray();
            }
            String text = new String(data, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            if (lines.length <= maxLines) {
                return text;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - maxLines; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String sizeInfo() {
        Context ctx = sContext != null ? sContext : App.resolveAppContext();
        if (ctx == null) {
            return "?";
        }
        File dir = new File(ctx.getFilesDir(), DIR_NAME);
        long total = 0;
        int count = 0;
        for (String name : new String[]{FILE_NAME, BACKUP_NAME}) {
            File file = new File(dir, name);
            if (file.isFile()) {
                total += file.length();
                count++;
            }
        }
        return count == 0 ? "\u65e0\u65e5\u5fd7\u6587\u4ef6" : count + " \u4e2a\u6587\u4ef6 / " + (total / 1024) + " KB";
    }

    public static String clear() {
        synchronized (LOCK) {
            Context ctx = sContext != null ? sContext : App.resolveAppContext();
            if (ctx == null) {
                return null;
            }
            File dir = new File(ctx.getFilesDir(), DIR_NAME);
            long freed = 0;
            int removed = 0;
            for (String name : new String[]{FILE_NAME, BACKUP_NAME}) {
                File file = new File(dir, name);
                if (!file.isFile()) {
                    continue;
                }
                freed += file.length();
                if (file.delete()) {
                    removed++;
                }
            }
            return removed == 0 ? null : removed + " 个文件 / " + (freed / 1024) + " KB";
        }
    }

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
