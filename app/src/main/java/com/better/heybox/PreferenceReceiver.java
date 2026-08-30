package com.better.heybox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;

/**
 * 接收小黑盒进程（内嵌设置面板）的开关写请求，写入模块进程 RemotePreferences。
 * 修复「模块进程未运行时切换无效」：广播带 FLAG_INCLUDE_STOPPED_PACKAGES 唤醒进程；goAsync() 等待服务绑定（最多 6 秒）后补交；待提交缓存 commit() 同步落盘
 */
public class PreferenceReceiver extends BroadcastReceiver {

    public static final String ACTION_SET_BOOLEAN = "com.better.heybox.SET_BOOLEAN";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_VALUE = "value";
    private static final String PENDING_PREFS = App.PENDING_PREFS;
    private static final long WAIT_SERVICE_BIND_MS = 6000;

    /** 派生自 {@link App#BOOLEAN_DEFAULTS} */
    private static final Set<String> ALLOWED_KEYS = new HashSet<>(App.BOOLEAN_DEFAULTS.keySet());

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Logs.w("BetterHeybox", "广播接收: intent=null");
            return;
        }
        String action = intent.getAction();
        String key = intent.getStringExtra(EXTRA_KEY);
        boolean value = intent.getBooleanExtra(EXTRA_VALUE, false);
        Checkpoint.mark("广播接收: action=%s key=%s value=%s", action, key, value);
        Logs.i("BetterHeybox", "广播接收: action=" + action + ", key=" + key
                + ", value=" + value + ", pid=" + android.os.Process.myPid());
        if (!ACTION_SET_BOOLEAN.equals(action)) {
            Logs.w("BetterHeybox", "广播忽略: action 不匹配, action=" + action);
            return;
        }
        if (!isAllowedKey(key)) {
            Logs.w("BetterHeybox", "广播拒绝: key 不允许, key=" + key);
            return;
        }

        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences pending = context.getSharedPreferences(PENDING_PREFS,
                            Context.MODE_PRIVATE);
                    pending.edit().putBoolean(key, value).commit();
                    Logs.i("BetterHeybox", "广播已写入待提交缓存: key=" + key + ", value=" + value
                            + ", pendingCount=" + pending.getAll().size());
                    LogRecorder.recordEvent("开关变更已写入待提交缓存: key=" + key + ", value=" + value);

                    // 冷启动时框架服务绑定是异步的：等待绑定后立即补交，确保设置不丢
                    XposedService service = App.getService();
                    long deadline = System.currentTimeMillis() + WAIT_SERVICE_BIND_MS;
                    while (service == null && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                        service = App.getService();
                    }
                    if (service == null) {
                        Logs.w("BetterHeybox", "等待框架服务绑定超时，保留待提交缓存: key=" + key
                                + "（服务绑定后会自动补交）");
                    }
                    PreferenceReceiver.tryFlush(context, pending);
                } finally {
                    result.finish();
                }
            }
        }, "bhx-pref-flush").start();
    }

    public static void tryFlush(Context context, SharedPreferences pending) {
        if (pending == null) {
            Logs.e("BetterHeybox", "远程提交跳过: pending=null");
            return;
        }
        Map<String, ?> values = pending.getAll();
        Logs.i("BetterHeybox", "远程提交开始: pendingCount=" + values.size()
                + ", pid=" + android.os.Process.myPid());
        try {
            SharedPreferences remote = App.getPrefs();
            if (remote == null) {
                Logs.w("BetterHeybox", "远程提交等待: RemotePreferences 不可用，保留待提交缓存");
                return;
            }
            Logs.i("BetterHeybox", "远程偏好已获取，开始构造 Editor: group=" + App.PREFS_GROUP);
            SharedPreferences.Editor remoteEditor = remote.edit();
            if (remoteEditor == null) {
                Logs.e("BetterHeybox", "远程提交失败: RemotePreferences.edit 返回 null");
                return;
            }
            int acceptedCount = 0;
            for (String key : values.keySet()) {
                Object value = values.get(key);
                if (value instanceof Boolean && isAllowedKey(key)) {
                    remoteEditor.putBoolean(key, (Boolean) value);
                    acceptedCount++;
                    Logs.i("BetterHeybox", "远程提交加入变更: key=" + key + ", value=" + value);
                } else {
                    Logs.w("BetterHeybox", "远程提交跳过无效缓存: key=" + key
                            + ", valueType=" + (value == null ? "null" : value.getClass().getName()));
                }
            }
            boolean committed = remoteEditor.commit();
            Logs.i("BetterHeybox", "远程提交 commit 已返回: success=" + committed
                    + ", acceptedCount=" + acceptedCount);
            LogRecorder.recordEvent("远程提交完成: success=" + committed + ", count=" + acceptedCount);
            if (committed) {
                pending.edit().clear().commit();
                Logs.i("BetterHeybox", "待提交缓存已清理: pendingCount=" + pending.getAll().size());
            } else {
                Logs.w("BetterHeybox", "远程提交未成功，保留待提交缓存");
            }
        } catch (Throwable t) {
            Logs.e("BetterHeybox", "远程提交异常，保留待提交缓存", t);
        }
    }

    private static boolean isAllowedKey(String key) {
        return key != null && ALLOWED_KEYS.contains(key);
    }
}
