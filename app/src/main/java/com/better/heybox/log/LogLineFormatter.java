package com.better.heybox.log;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LogLineFormatter {

    private static final String DEFAULT_TAG = "BetterHeybox";

    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    private LogLineFormatter() {
    }

    public static String format(LogEntry entry) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(TIME_FORMAT.get().format(new Date(entry.timestampMs)));
        sb.append(' ').append(levelChar(entry.level));
        sb.append('/').append(entry.tag == null ? DEFAULT_TAG : entry.tag);
        sb.append(" [pid=").append(entry.pid).append("] ");
        sb.append(entry.message).append('\n');
        if (entry.throwable != null) {
            StringWriter sw = new StringWriter();
            entry.throwable.printStackTrace(new PrintWriter(sw));
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
