package com.better.heybox;

import android.os.Process;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CrashGuard {

    private static final String TAG = "BetterHeybox";

    private static volatile boolean sInstalled;

    private static final AtomicBoolean sReporting = new AtomicBoolean();

    private CrashGuard() {
    }

    public static void install() {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    report(thread, throwable);
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            });
        } catch (Throwable t) {
            Logs.w(TAG, "崩溃取证安装失败: " + t);
        }
    }

    private static void report(Thread thread, Throwable throwable) {
        if (!sReporting.compareAndSet(false, true)) {
            return;
        }
        try {
            String head = "💥 未捕获异常（进程=" + App.currentProcessName()
                    + " pid=" + Process.myPid()
                    + " 线程=" + (thread == null ? "?" : thread.getName()) + "）";
            LogRecorder.record(Log.ERROR, TAG, head, throwable);
            LogRecorder.record(Log.ERROR, TAG, "模块统计: " + ModuleStats.snapshot());
        } catch (Throwable ignored) {
        }
    }
}
