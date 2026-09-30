package com.better.heybox.log;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class FileLogStorage implements LogStorage {

    private static final String TAG = "BetterHeybox.LogStorage";

    private final Object lock = new Object();
    private final ContextProvider contextProvider;
    private final LogConfig config;

    private BufferedOutputStream out;
    private long writtenBytes;
    private volatile boolean lowSpaceWarned;
    private volatile long lowSpaceDropCount;

    public interface ContextProvider {
        Context get();
    }

    public FileLogStorage(ContextProvider contextProvider, LogConfig config) {
        this.contextProvider = contextProvider;
        this.config = config;
    }

    @Override
    public boolean appendLine(String line) {
        try {
            Context ctx = contextProvider.get();
            if (ctx == null) {
                return false;
            }
            File dir = new File(ctx.getFilesDir(), LogConfig.DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File file = new File(dir, LogConfig.FILE_NAME);
            byte[] data = line.getBytes(StandardCharsets.UTF_8);
            synchronized (lock) {
                if (writtenBytes > config.maxFileBytes) {
                    rotateLocked(dir, file);
                }
                if (!hasEnoughSpaceLocked(dir, data.length)) {
                    return false;
                }
                if (out == null) {
                    if (writtenBytes == 0 && file.isFile()) {
                        writtenBytes = file.length();
                    }
                    out = new BufferedOutputStream(new FileOutputStream(file, true), 16 * 1024);
                }
                out.write(data);
                writtenBytes += data.length;
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasEnoughSpaceLocked(File dir, int incomingBytes) {
        try {
            long usable = dir.getUsableSpace();
            if (usable > 0 && usable - incomingBytes < config.minFreeSpaceBytes) {
                lowSpaceDropCount++;
                if (!lowSpaceWarned) {
                    lowSpaceWarned = true;
                    Log.w(TAG, "剩余空间不足，暂停写日志: usable=" + usable
                            + "B threshold=" + config.minFreeSpaceBytes
                            + "B dir=" + dir.getAbsolutePath());
                }
                return false;
            }
            if (lowSpaceWarned) {
                lowSpaceWarned = false;
                Log.i(TAG, "剩余空间已恢复，继续写日志: usable=" + usable + "B");
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    long lowSpaceDropCount() {
        return lowSpaceDropCount;
    }

    @Override
    public void flush() {
        synchronized (lock) {
            try {
                if (out != null) {
                    out.flush();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void rotateLocked(File dir, File file) {
        closeStreamLocked();
        File backup = new File(dir, LogConfig.BACKUP_NAME);
        backup.delete();
        file.renameTo(backup);
        writtenBytes = 0;
    }

    private void closeStreamLocked() {
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (Throwable ignored) {
        }
        out = null;
        writtenBytes = 0;
    }

    @Override
    public String getPrimaryPath() {
        Context ctx = contextProvider.get();
        if (ctx == null) {
            return null;
        }
        return new File(new File(ctx.getFilesDir(), LogConfig.DIR_NAME), LogConfig.FILE_NAME)
                .getAbsolutePath();
    }

    @Override
    public String getBackupPath() {
        Context ctx = contextProvider.get();
        if (ctx == null) {
            return null;
        }
        return new File(new File(ctx.getFilesDir(), LogConfig.DIR_NAME), LogConfig.BACKUP_NAME)
                .getAbsolutePath();
    }

    @Override
    public String readTail(int maxLines) {
        flush();
        Context ctx = contextProvider.get();
        if (ctx == null) {
            return null;
        }
        File file = new File(new File(ctx.getFilesDir(), LogConfig.DIR_NAME), LogConfig.FILE_NAME);
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

    @Override
    public String sizeInfo() {
        flush();
        Context ctx = contextProvider.get();
        if (ctx == null) {
            return "?";
        }
        File dir = new File(ctx.getFilesDir(), LogConfig.DIR_NAME);
        long total = 0;
        int count = 0;
        for (String name : new String[]{LogConfig.FILE_NAME, LogConfig.BACKUP_NAME}) {
            File file = new File(dir, name);
            if (file.isFile()) {
                total += file.length();
                count++;
            }
        }
        String base = count == 0 ? "无日志文件" : count + " 个文件 / " + (total / 1024) + " KB";
        return lowSpaceDropCount == 0
                ? base
                : base + "（剩余空间不足丢弃 " + lowSpaceDropCount + " 次）";
    }

    @Override
    public String clear() {
        flush();
        synchronized (lock) {
            closeStreamLocked();
            Context ctx = contextProvider.get();
            if (ctx == null) {
                return null;
            }
            File dir = new File(ctx.getFilesDir(), LogConfig.DIR_NAME);
            long freed = 0;
            int removed = 0;
            for (String name : new String[]{LogConfig.FILE_NAME, LogConfig.BACKUP_NAME}) {
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
}
