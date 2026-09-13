package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.better.heybox.MainModule;
import com.better.heybox.watch.HttpBridge;
import com.better.heybox.watch.WatchEngine;
import com.better.heybox.watch.WatchFetcher;
import com.better.heybox.watch.WatchOutput;
import com.better.heybox.watch.WatchSeen;

import java.lang.reflect.Method;

/**
 * 动态推送 Hook：把「检查时机」和「网络能力」接到宿主上。
 *
 * <ul>
 *   <li>打开小黑盒（MainActivity onCreate / onResume）→ 主动检查一次（引擎内自带节流）</li>
 *   <li>宿主收到推送（HBGTIntentService 回调）→ 搭便车检查一次；这是唯一能借到
 *       「系统级唤醒」的合法手段，无需任何凭据</li>
 *   <li>OkHttpClient.newCall → 捕获宿主的网络栈，供后续复用其签名能力</li>
 *   <li>信息流反序列化 → 直接读原始 JSON，命中关注作者/关键词就地提醒（零网络）</li>
 * </ul>
 *
 * <p>全部 fail-open：钩子安装失败只记日志，绝不影响宿主。
 */
public final class WatchHook {

    private final MainModule module;

    public WatchHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        WatchEngine.init(module);
        WatchFetcher.init(module);
        WatchOutput.init(module);
        HttpBridge.init(module);
        WatchSeen.init(module);
        hookAppOpen(cl);
        hookPushArrive(cl);
        hookOkHttp(cl);
        hookFeedJson(cl);
    }

    // ------------------------------------------------------------ 打开小黑盒

    private void hookAppOpen(ClassLoader cl) {
        try {
            Class<?> main = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onCreate = findMethod(main, "onCreate", android.os.Bundle.class);
            if (onCreate != null) {
                module.hook(onCreate).intercept(chain -> {
                    Object result = chain.proceed();
                    notifyOpen(chain.getThisObject());
                    return result;
                });
            }
            Method onResume = findMethod(main, "onResume");
            if (onResume != null) {
                module.hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    notifyOpen(chain.getThisObject());
                    return result;
                });
            }
            module.logd(Log.INFO, module.TAG, "✔ 动态推送：打开检查 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 动态推送：打开检查 Hook 失败: " + t);
        }
    }

    private void notifyOpen(Object self) {
        try {
            if (self instanceof Activity) {
                WatchEngine.onAppOpen((Activity) self);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "动态推送：打开检查异常 " + t);
        }
    }

    // ------------------------------------------------------------ 推送搭便车

    private void hookPushArrive(ClassLoader cl) {
        try {
            Class<?> svc = Class.forName("com.max.hbcommon.push.HBGTIntentService", false, cl);
            int installed = 0;
            for (Method m : svc.getDeclaredMethods()) {
                String n = m.getName();
                if (!"onReceiveMessageData".equals(n) && !"onNotificationMessageArrived".equals(n)
                        && !"onNotificationMessageClicked".equals(n)) {
                    continue;
                }
                module.hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object ctx = chain.getArg(0);
                        if (ctx instanceof Context) {
                            WatchEngine.onPushArrived((Context) ctx);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
                installed++;
            }
            module.logd(Log.INFO, module.TAG, "✔ 动态推送：推送搭便车 Hook 已安装 (" + installed + " 处)");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 动态推送：推送搭便车 Hook 失败: " + t);
        }
    }

    // ------------------------------------------------------------ 捕获宿主网络栈

    /**
     * 捕获宿主的 HTTP 客户端。
     *
     * <p><b>不按类名</b>找 OkHttpClient —— 实测小黑盒 1.3.395 的 R8 已把
     * {@code okhttp3.OkHttpClient} 改名（类定义里没有这个名字，方法名也不再是 newCall），
     * 所以改为 hook {@code okhttp3.internal.connection.RealCall} 的构造函数：
     * R8 能改名字，但<b>改不了参数顺序</b>，第一个参数就是客户端实例。
     */
    private void hookOkHttp(ClassLoader cl) {
        int installed = 0;
        for (String cn : HttpBridge.CLIENT_HOLDERS) {
            try {
                Class<?> holder = Class.forName(cn, false, cl);
                for (java.lang.reflect.Constructor<?> ctor : holder.getDeclaredConstructors()) {
                    if (ctor.getParameterTypes().length < 2) {
                        continue;
                    }
                    module.hook(ctor).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            if (!HttpBridge.ready()) {
                                HttpBridge.captureIfClient(chain.getArg(0), chain.getArg(1), cl);
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
                    installed++;
                    break;
                }
                if (installed > 0) {
                    module.logd(Log.INFO, module.TAG, "✔ 动态推送：HTTP 客户端捕获 Hook 已安装 (" + cn + ")");
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (installed == 0) {
            module.logd(Log.WARN, module.TAG, "✘ 动态推送：未找到可用的 OkHttp 载体类，主动拉取将不可用");
        }
    }

    // ------------------------------------------------------------ 信息流被动命中

    private void hookFeedJson(ClassLoader cl) {
        String[] classes = {
                "com.max.data.deserializer.FeedsFlowItemModelDeserializer",
                "com.max.xiaoheihe.network.gson.FeedsContentDeserializer",
        };
        int installed = 0;
        for (String cn : classes) {
            try {
                Class<?> d = Class.forName(cn, false, cl);
                Class<?> jsonElement = Class.forName("com.google.gson.JsonElement", false, cl);
                Class<?> type = Class.forName("java.lang.reflect.Type", false, cl);
                Class<?> ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl);
                for (String name : new String[]{"a", "deserialize"}) {
                    try {
                        Method m = d.getDeclaredMethod(name, jsonElement, type, ctx);
                        module.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                WatchEngine.onFeedJson(chain.getArg(0));
                            } catch (Throwable ignored) {
                            }
                            return result;
                        });
                        installed++;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        module.logd(Log.INFO, module.TAG, "✔ 动态推送：信息流命中 Hook 已安装 (" + installed + " 处)");
    }

    // ------------------------------------------------------------ 工具

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        Class<?> walk = c;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod(name, params);
                if (m != null) {
                    return m;
                }
            } catch (Throwable ignored) {
            }
            walk = walk.getSuperclass();
        }
        return null;
    }
}
