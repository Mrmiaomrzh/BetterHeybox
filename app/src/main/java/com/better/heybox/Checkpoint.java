package com.better.heybox;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 运行时检查点（Debug 构建）：在生命周期/Hook 安装关键节点打点，记录相对耗时、pid、线程名，输出到 logcat 与文件日志并保留最近快照供设置页查看/导出。
 * 小黑盒进程检查点在安装后写入 RemotePreferences 供设置页跨进程读取；Release 构建全部空操作
 */
public final class Checkpoint {

    private static final String TAG = "BHX-CKPT";
    private static final int MAX = 256;

    private static final Object LOCK = new Object();
    private static final List<String> sEntries = new ArrayList<>();
    private static volatile long sStart = -1;

    private Checkpoint() {
    }

    public static void mark(String msg) {
        mark("%s", msg);
    }

    public static void mark(String fmt, Object... args) {
        if (!BuildFlags.DEBUG) {
            return;
        }
        String msg = args == null || args.length == 0 ? fmt : String.format(fmt, args);
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (sStart < 0) {
                sStart = now;
                sEntries.add(header());
            }
            sEntries.add(String.format(Locale.US, "[%8dms][pid=%d][%s] %s",
                    now - sStart, android.os.Process.myPid(), Thread.currentThread().getName(), msg));
            while (sEntries.size() > MAX) {
                sEntries.remove(0);
            }
        }
        Log.i(TAG, msg);
        LogRecorder.recordEvent("检查点: " + msg);
    }

    public static String dump() {
        return dump(Integer.MAX_VALUE);
    }

    public static String dump(int maxLines) {
        if (!BuildFlags.DEBUG) {
            return "（Release 构建未启用检查点）";
        }
        synchronized (LOCK) {
            if (sEntries.isEmpty()) {
                return "（暂无检查点）";
            }
            if (sEntries.size() <= maxLines) {
                return String.join("\n", sEntries);
            }
            return String.join("\n", sEntries.subList(sEntries.size() - maxLines, sEntries.size()));
        }
    }

    private static String header() {
        return String.format(Locale.US,
                "===== BetterHeybox 运行检查点 =====\n构建=%s, SDK=%d, 设备=%s %s, 进程=%s",
                BuildFlags.DEBUG ? "debug" : "release",
                Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL,
                App.currentProcessName());
    }
}
