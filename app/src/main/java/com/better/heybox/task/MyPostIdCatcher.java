package com.better.heybox.task;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import com.better.heybox.MainModule;

public final class MyPostIdCatcher {

    public static final String ACTION_POST_SUCCESS =
            "com.max.xiaoheihe.BROADCAST_POST_SUCCESS_JS_CALLBACK";
    public static final String EXTRA_LINK_ID = "link_id";

    public interface Listener {
        void onPostCreated(String linkId);
    }

    private static volatile Context sContext;
    private static volatile Listener sListener;
    private static volatile BroadcastReceiver sReceiver;
    private static volatile String sLastLinkId = "";
    private static volatile String sLastSource = "";

    private MyPostIdCatcher() {
    }

    public static void register(Context context, MainModule module, Listener listener) {
        if (context == null || listener == null) {
            return;
        }
        sListener = listener;
        synchronized (MyPostIdCatcher.class) {
            if (sReceiver != null) {
                return;
            }
            try {
                Context app = context.getApplicationContext();
                sContext = app;
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        if (intent == null) {
                            return;
                        }
                        String id = intent.getStringExtra(EXTRA_LINK_ID);
                        publish(id, "广播 " + ACTION_POST_SUCCESS);
                    }
                };
                IntentFilter filter = new IntentFilter(ACTION_POST_SUCCESS);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(receiver, filter);
                }
                sReceiver = receiver;
            } catch (Throwable t) {
                if (module != null) {
                    module.logd(Log.WARN, module.TAG,
                            "我的任务：发帖成功广播注册失败（将只靠帖子页兜底）: " + t);
                }
            }
        }
    }

    public static void report(String linkId, String source) {
        publish(linkId, source);
    }

    private static void publish(String linkId, String source) {
        if (linkId == null || linkId.trim().isEmpty()) {
            return;
        }
        String id = linkId.trim();
        sLastLinkId = id;
        sLastSource = source == null ? "" : source;
        Listener listener = sListener;
        if (listener != null) {
            try {
                listener.onPostCreated(id);
            } catch (Throwable ignored) {
            }
        }
    }

    public static String lastLinkId() {
        return sLastLinkId;
    }

    public static String lastSource() {
        return sLastSource;
    }
}
