package com.better.heybox;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程存活取证：回答「上一次到底是崩了还是被系统杀了」。
 *
 * <p>{@link CrashGuard} 只能拿到 Java 层未捕获异常。而用户反馈的「玩一段时间后崩溃」在实际日志里
 * 常常一条 ERROR 都没有 —— 因为 native 崩溃（SIGSEGV）、ANR 被系统杀死、被 LMK 因内存回收
 * 都不会经过 Java 异常处理器，进程直接消失，什么都没留下。
 *
 * <p>判定依据是「**前台状态标记**」而不是心跳时间间隔：
 * <ul>
 *   <li>进程启动时登记 pid / 启动时间，并把前台标记置为 false；</li>
 *   <li>通过 {@link Application.ActivityLifecycleCallbacks} 跟踪前台：有 Activity resumed 即置 true，
 *       全部 paused 置 false（该标记在状态变化时落盘）；</li>
 *   <li>下次启动时结算上一次会话：只有「上次结束时仍处于前台」且「没有 Java 崩溃记录」才判定为
 *       异常退出（native / ANR / LMK）；后台结束只记 INFO。</li>
 * </ul>
 *
 * <p>为什么不用「心跳距今多久」：用户正常退出后马上再打开，与崩溃重启在时间上无法区分，
 * 会造成大量误报。而「进程死时是否在前台」能把两者分开 —— 崩溃恰恰发生在前台使用过程中。
 */
public final class SessionGuard {

    private static final String TAG = "BetterHeybox";

    private static final String KEY_PID = "session_pid";
    private static final String KEY_STARTED_AT = "session_started_at";
    private static final String KEY_FOREGROUND = "session_foreground";
    private static final String KEY_JAVA_CRASH = "session_java_crash";
    /** 进程身份 = pid + /proc/self/stat starttime，用于区分「新进程」与「同进程热重载」 */
    private static final String KEY_PROCESS_ID = "session_process_id";

    private static volatile boolean sStarted;
    private static volatile boolean sForeground;
    private static final AtomicInteger sStartedActivities = new AtomicInteger();
    private static volatile boolean sCallbacksRegistered;

    private SessionGuard() {
    }

    /**
     * 当前进程名，用于排除宿主子进程（:pushservice 等）。
     *
     * <p>拿不到时返回 {@code null}，调用方必须**放弃**处理（fail-closed）：
     * 若退化成 pid 字符串，子进程会被误当成主进程并覆盖会话标记。
     */
    private static String resolveProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                String name = Application.getProcessName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                return line;
            }
        } catch (Throwable ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isMainProcess() {
        String process = resolveProcessName();
        if (process == null) {
            return false; // 无法确定进程名 → 不碰会话状态，避免子进程污染主进程记录
        }
        return process.indexOf(':') < 0;
    }

    /**
     * 进程身份：{@code pid:starttime}。
     *
     * <p>{@code starttime} 取自 {@code /proc/self/stat} 第 22 个字段（自开机起的时钟节拍数），
     * 同一进程内恒定、进程重启后必变 —— 因此即使 Android 复用了 pid，身份仍然不同。
     * 读不到时退回 pid，并在调用处以 null 表达「不可用」。
     *
     * <p>注意 {@code /proc/self/stat} 的第 2 个字段（comm）可能含空格与括号，因此从**最后一个
     * 右括号之后**开始切分，不能简单 split。
     */
    private static String processIdentity() {
        int pid = android.os.Process.myPid();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/self/stat"));
            String stat = reader.readLine();
            if (stat != null) {
                int close = stat.lastIndexOf(')');
                if (close > 0 && close + 2 < stat.length()) {
                    // close 之后是 " <state> <ppid> ..."，starttime 是其中第 20 个字段
                    String[] rest = stat.substring(close + 2).trim().split("\\s+");
                    if (rest.length >= 20) {
                        return pid + ":" + rest[19];
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 进程启动时调用一次：结算上一次会话并登记本次会话 */
    public static void onProcessStart(Context context) {
        if (sStarted) {
            return;
        }
        if (!isMainProcess()) {
            return;
        }
        try {
            // 确保LogRecorder已启用，否则会话日志无法记录
            LogRecorder.setEnabled(true);

            HeyboxPrefs.init(context);
            android.content.SharedPreferences prefs = HeyboxPrefs.get();
            if (prefs == null) {
                return; // 拿不到 prefs 时不置 sStarted，下次仍有补救机会
            }
            sStarted = true;
            long now = System.currentTimeMillis();
            String currentIdentity = processIdentity();
            String previousIdentity = prefs.getString(KEY_PROCESS_ID, null);
            int previousPid = prefs.getInt(KEY_PID, -1);
            long startedAt = prefs.getLong(KEY_STARTED_AT, 0L);
            boolean wasForeground = prefs.getBoolean(KEY_FOREGROUND, false);
            boolean javaCrash = prefs.getBoolean(KEY_JAVA_CRASH, false);
            int currentPid = android.os.Process.myPid();

            /*
             * 是否要结算上一次会话？
             *
             * 只看 pid 会漏报：Android 复用 pid，崩溃后重启若拿到同一 pid，就永远不结算（假阴性）。
             * 完全不看 pid 会误报：模块热重载会在**同一进程**重建类加载器、静态字段复位，
             * 于是 onProcessStart 再次执行，把「同一进程仍在运行」当成新会话（假阳性）。
             *
             * 用「进程身份 = pid + /proc/self/stat 的 starttime」精确区分：
             * starttime 是进程创建时刻（自开机起的时钟节拍数），同一进程内恒定，
             * 而重启后的新进程即使 pid 相同也必然不同。
             * 拿不到身份时退回 pid 比较 —— 宁可偶尔漏报，也不要误报。
             */
            boolean newSession;
            if (startedAt <= 0L) {
                newSession = false; // 没有上次记录，无需结算
            } else if (previousIdentity != null && currentIdentity != null) {
                newSession = !previousIdentity.equals(currentIdentity);
            } else {
                newSession = previousPid > 0 && previousPid != currentPid;
            }
            if (newSession) {
                reportPrevious(previousPid, startedAt, wasForeground, javaCrash, now);
            }

            prefs.edit()
                    .putInt(KEY_PID, currentPid)
                    .putString(KEY_PROCESS_ID, currentIdentity)
                    .putLong(KEY_STARTED_AT, now)
                    .putBoolean(KEY_FOREGROUND, false)
                    .putBoolean(KEY_JAVA_CRASH, false)
                    .commit();
            registerActivityCallbacks(context);

            Logs.i(TAG, "SessionGuard initialized: pid=" + currentPid + " identity=" + currentIdentity);
        } catch (Throwable t) {
            Logs.w(TAG, "SessionGuard init failed: prefs write error: " + t);
        }
    }

    /**
     * 注册前台跟踪：不依赖额外 hook，直接观察宿主 Activity 生命周期。
     *
     * <p>返回是否已可用。**必须**用返回值决定能否依赖前台标记：
     * 若注册失败，`onActivityStopped` 永远不会回调，`setForeground(false)` 也就永不执行，
     * 而 {@link #heartbeat()} 只会置 true —— 前台标记会永久停在 true，
     * 于是下次启动把「干净退出」误报成崩溃，且每次启动自我延续。
     */
    private static boolean registerActivityCallbacks(Context context) {
        if (sCallbacksRegistered) {
            return true;
        }
        Context app = context != null ? context.getApplicationContext() : null;
        if (!(app instanceof Application)) {
            Context resolved = App.resolveAppContext();
            app = resolved instanceof Application ? resolved : null;
        }
        if (!(app instanceof Application)) {
            return false;
        }
        try {
            ((Application) app).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override
                        public void onActivityCreated(Activity activity, Bundle bundle) {
                        }

                        @Override
                        public void onActivityStarted(Activity activity) {
                            if (sStartedActivities.incrementAndGet() == 1) {
                                setForeground(true);
                            }
                        }

                        @Override
                        public void onActivityResumed(Activity activity) {
                            setForeground(true);
                        }

                        @Override
                        public void onActivityPaused(Activity activity) {
                        }

                        @Override
                        public void onActivityStopped(Activity activity) {
                            if (sStartedActivities.decrementAndGet() <= 0) {
                                sStartedActivities.set(0);
                                setForeground(false);
                            }
                        }

                        @Override
                        public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
                        }

                        @Override
                        public void onActivityDestroyed(Activity activity) {
                        }
                    });
            sCallbacksRegistered = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 前台状态变化时落盘（仅在真正变化时写，避免频繁 IO） */
    private static void setForeground(boolean foreground) {
        if (sForeground == foreground) {
            return;
        }
        sForeground = foreground;
        try {
            android.content.SharedPreferences prefs = HeyboxPrefs.get();
            if (prefs != null) {
                prefs.edit().putBoolean(KEY_FOREGROUND, foreground).commit();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 由前台 Activity 的 onResume 路径调用：确认「当前处于前台」。
     *
     * <p>名称保留 {@code heartbeat} 是为了少改调用点，但它**不是**定时心跳 ——
     * 本类已改为前台状态标记模型（见 {@link #setForeground}），没有时间间隔概念。
     *
     * <p>只有在前台跟踪回调**确实已注册**时才允许置 true：否则这个「只会置 true」的入口
     * 会把前台标记永久钉在 true（没有配对的 onActivityStopped 把它改回 false），
     * 导致每次干净退出都被误报成崩溃。注册失败时宁可没有前台判定，也不要误报。
     *
     * <p>顺带补一次注册重试：首次注册可能因为拿不到 Application 而失败，
     * 而本方法在每次 onResume 都会被调用，是天然的重试点。
     */
    public static void heartbeat() {
        if (!sStarted) {
            return;
        }
        if (!sCallbacksRegistered && !registerActivityCallbacks(App.resolveAppContext())) {
            return;
        }
        if (!sForeground) {
            setForeground(true);
        }
    }

    private static void reportPrevious(int previousPid, long startedAt, boolean wasForeground,
                                       boolean javaCrash, long now) {
        long livedMs = Math.max(0L, now - startedAt);
        if (javaCrash) {
            LogRecorder.recordBlocking(Log.WARN, TAG,
                    "Previous session terminated: pid=" + previousPid + " cause=java_exception"
                            + " uptime=" + (livedMs / 1000L) + "s");
            return;
        }
        if (wasForeground) {
            ModuleStats.abnormalExits.incrementAndGet();
            LogRecorder.recordBlocking(Log.WARN, TAG,
                    "Previous session abnormal exit: pid=" + previousPid + " state=foreground"
                            + " uptime=" + (livedMs / 1000L) + "s java_crash=false"
                            + " likely_cause=native_crash|ANR|LMK memory=" + CrashGuard.memorySnapshot());
        } else {
            LogRecorder.recordBlockingGated(Log.INFO, TAG,
                    "Previous session background exit: pid=" + previousPid + " state=background"
                            + " uptime=" + (livedMs / 1000L) + "s cause=system_reclaim|user_kill");
        }
    }

    /** CrashGuard 记录 Java 崩溃后调用，供下次启动区分「有堆栈」与「无堆栈」 */
    public static void noteJavaCrash() {
        // 同样只在主进程记录：子进程崩溃不应影响主进程的会话判定
        if (!isMainProcess()) {
            return;
        }
        try {
            android.content.SharedPreferences prefs = HeyboxPrefs.get();
            if (prefs != null) {
                prefs.edit().putBoolean(KEY_JAVA_CRASH, true).commit();
            }
        } catch (Throwable ignored) {
        }
    }
}
