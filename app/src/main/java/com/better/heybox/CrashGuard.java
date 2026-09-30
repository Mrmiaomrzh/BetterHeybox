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
            LogRecorder.setEnabled(true);

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
            Logs.i(TAG, "CrashGuard installed: previous_handler=" +
                   (previous == null ? "none" : previous.getClass().getSimpleName()));
        } catch (Throwable t) {
            Logs.w(TAG, "CrashGuard install failed: " + t);
        }
    }

    public static void installLate() {
        sInstalled = false;
        install();
    }

    private static void report(Thread thread, Throwable throwable) {
        if (!sReporting.compareAndSet(false, true)) {
            return;
        }
        try {
            String head = "Uncaught exception: process=" + App.currentProcessName()
                    + " pid=" + Process.myPid()
                    + " thread=" + (thread == null ? "unknown" : thread.getName());
            LogRecorder.recordBlocking(Log.ERROR, TAG, head, throwable);
            LogRecorder.recordBlocking(Log.ERROR, TAG,
                    "Module stats: " + ModuleStats.crashSnapshot());
            LogRecorder.recordBlocking(Log.ERROR, TAG, "Memory: " + memorySnapshot());
            SessionGuard.noteJavaCrash();
        } catch (Throwable ignored) {
        }
    }

    public static String memorySnapshot() {
        StringBuilder sb = new StringBuilder(160);
        try {
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            long total = rt.totalMemory();
            long free = rt.freeMemory();
            sb.append("heapUsed=").append((total - free) / 1024L / 1024L).append("MB")
                    .append(" heapTotal=").append(total / 1024L / 1024L).append("MB")
                    .append(" heapMax=").append(max / 1024L / 1024L).append("MB");
        } catch (Throwable ignored) {
        }
        try {
            android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(mi);
            sb.append(" pss=").append(mi.getTotalPss() / 1024L).append("MB")
                    .append(" javaHeap=").append(mi.dalvikPss / 1024L).append("MB")
                    .append(" nativeHeap=").append(mi.nativePss / 1024L).append("MB");
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
