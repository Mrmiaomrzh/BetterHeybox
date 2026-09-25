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

public final class WatchHook {

    private final MainModule module;

    public WatchHook(MainModule module) {
        this.module = module;
    }

    private final StringBuilder hits = new StringBuilder();

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
        module.logd(Log.INFO, module.TAG, "✔ 动态推送 Hook 安装完成：" + (hits.length() == 0 ? "无命中" : hits.toString()));
    }

    private void hit(String label, String target) {
        if (hits.length() > 0) {
            hits.append(" / ");
        }
        hits.append(label).append('=').append(target);
    }


    private static final String[] OPEN_HOLDERS = {
            "com.max.xiaoheihe.MainActivity",
            "com.max.hbcommon.base.BaseActivity",
    };

    private void hookAppOpen(ClassLoader cl) {
        for (String cn : OPEN_HOLDERS) {
            try {
                Class<?> main = Class.forName(cn, false, cl);
                if (installOpenHooks(main)) {
                    hit("打开检查", cn);
                    module.logd(Log.INFO, module.TAG, "✔ 动态推送：打开检查 Hook 已安装 (" + cn + ")");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        module.logd(Log.WARN, module.TAG, "✘ 动态推送：打开检查 Hook 失败（候选全部落空）");
    }

    private boolean installOpenHooks(Class<?> main) {
        boolean any = false;
        try {
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
                any = true;
            }
        } catch (Throwable ignored) {
        }
        return any;
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


    private static final String[] PUSH_HOLDERS = {
            "com.max.hbcommon.push.HBGTIntentService",
            "com.igexin.sdk.GTIntentService",
    };

    private void hookPushArrive(ClassLoader cl) {
        for (String cn : PUSH_HOLDERS) {
            if (installPushHooks(cl, cn)) {
                return;
            }
        }
        module.logd(Log.WARN, module.TAG, "✘ 动态推送：推送搭便车 Hook 失败（候选全部落空）");
    }

    private boolean installPushHooks(ClassLoader cl, String cn) {
        try {
            Class<?> svc = Class.forName(cn, false, cl);
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
            if (installed > 0) {
                hit("推送搭便车", cn + "×" + installed);
                module.logd(Log.INFO, module.TAG, "✔ 动态推送：推送搭便车 Hook 已安装 ("
                        + cn + ", " + installed + " 处)");
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }


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
                            HttpBridge.captureIfClient(chain.getArg(0), chain.getArg(1), cl);
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
                    installed++;
                    break;
                }
                if (installed > 0) {
                    hit("网络栈", cn);
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


    private static final String[] FEED_DESERIALIZERS = {
            "com.max.data.deserializer.FeedsFlowItemModelDeserializer",
            "com.max.xiaoheihe.network.gson.FeedsContentDeserializer",
    };

    private void hookFeedJson(ClassLoader cl) {
        int installed = 0;
        for (String cn : FEED_DESERIALIZERS) {
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
        if (installed > 0) {
            hit("信息流", installed + " 处");
        }
        module.logd(Log.INFO, module.TAG, "✔ 动态推送：信息流命中 Hook 已安装 (" + installed + " 处)");
    }


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
