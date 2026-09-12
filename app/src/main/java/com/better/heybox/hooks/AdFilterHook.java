package com.better.heybox.hooks;

import android.util.Log;
import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import io.github.libxposed.api.XposedInterface;

/**
 * 广告过滤：开屏广告 / 信息流广告（Gson 反序列化过滤）/ 气泡广告 / 角标广告。
 */
public final class AdFilterHook {

    private final MainModule module;

    public AdFilterHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookOpenScreenAd(cl);
        hookFeedAds(cl);
        hookBubbleAndCornerAds(cl);
    }

    private void hookOpenScreenAd(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.ads.e", false, cl);
            Method g = clazz.getDeclaredMethod("g", boolean.class);
            module.hook(g).intercept(chain -> {
                if (module.isEnabled(App.KEY_OPEN_SCREEN, true)) {
                    module.logd(Log.INFO, module.TAG, "拦截开屏广告 e.g()");
                    return null;
                }
                return chain.proceed();
            });
            module.logd(Log.INFO, module.TAG, "✔ 开屏广告 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 开屏广告 Hook 失败", t);
        }
    }

    private void hookFeedAds(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.network.gson.FeedsContentDeserializer", false, cl);
            // 必须用小黑盒的 classloader 加载 gson（单参 Class.forName 会用模块自己的 classloader）
            Class<?> jsonElement = Class.forName("com.google.gson.JsonElement", false, cl);
            Class<?> type = Class.forName("java.lang.reflect.Type", false, cl);
            Class<?> ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl);

            try {
                Method a = clazz.getDeclaredMethod("a", jsonElement, type, ctx);
                module.hook(a).intercept(chain -> filterFeedAd(chain));
                module.logd(Log.INFO, module.TAG, "✔ 信息流广告 Hook 已安装 (a)");
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Method deserialize = clazz.getDeclaredMethod("deserialize", jsonElement, type, ctx);
                module.hook(deserialize).intercept(chain -> filterFeedAd(chain));
                module.logd(Log.INFO, module.TAG, "✔ 信息流广告 Hook 已安装 (deserialize)");
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 信息流广告 Hook 失败", t);
        }
    }

    private Object filterFeedAd(XposedInterface.Chain chain) throws Throwable {
        if (module.isEnabled(App.KEY_FEED_AD, true)) {
            try {
                Object elem = chain.getArg(0);
                if (elem != null) {
                    Object obj = elem.getClass().getMethod("getAsJsonObject").invoke(elem);
                    if (obj != null) {
                        Object ct = obj.getClass().getMethod("get", String.class).invoke(obj, "content_type");
                        if (ct != null) {
                            String ctStr = (String) ct.getClass().getMethod("getAsString").invoke(ct);
                            if ("23".equals(ctStr)) {
                                module.logd(Log.INFO, module.TAG, "过滤信息流广告条目 (content_type=23)");
                                return createEmptyFeedObj(chain.getThisObject());
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "信息流广告判断异常，放行: " + t);
            }
        }
        Object result = chain.proceed();
        // 委托发帖过滤
        PostFilterHook postFilter = PostFilterHook.get();
        if (postFilter != null && result != null) {
            Object replacement = postFilter.onDeserialized(result);
            if (replacement != null) {
                return replacement;
            }
        }
        return result;
    }

    private Object createEmptyFeedObj(Object thisObj) {
        try {
            ClassLoader cl = thisObj != null ? thisObj.getClass().getClassLoader()
                    : getClass().getClassLoader();
            Class<?> base = Class.forName("com.max.xiaoheihe.bean.news.FeedsContentBaseObj", false, cl);
            Object empty = base.getDeclaredConstructor().newInstance();
            base.getMethod("setContent_type", String.class).invoke(empty, "0");
            try {
                base.getMethod("setShowDivider", boolean.class).invoke(empty, false);
            } catch (Throwable ignored) {
            }
            return empty;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "创建空 FeedsContentBaseObj 失败: " + t);
            return null;
        }
    }

    private void hookBubbleAndCornerAds(ClassLoader cl) {
        Class<?> clazz;
        Class<?> callback;
        try {
            clazz = Class.forName("com.max.xiaoheihe.module.ads.h", false, cl);
            callback = Class.forName("com.max.xiaoheihe.utils.x0$g", false, cl);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 未找到 module.ads.h / x0$g，气泡与角标广告 Hook 跳过", t);
            return;
        }
        hookBubbleAd(clazz, cl);
        hookCornerAd(clazz, callback);
    }

    /**
     * 跨版本候选：{方法名, 回调内部类全名}
     * <p>1.3.395 做了一次成段混淆重排，module.ads.h 的方法从 10 个扩到 30 个：
     * <pre>
     *   394 h(x0$g)  -> 395 i(x0$g)      广告拉取（签名不变，可靠）
     *   394 l(h$g)   -> 395 s(h$i)       气泡展示（方法名与内部类同时改名）
     * </pre>
     * 注意 395 里 h$g 仍然存在，但已是角标广告相关的新类型，因此旧签名不会误命中，只会落空。
     */
    private void hookBubbleAd(Class<?> clazz, ClassLoader cl) {
        final String[][] candidates = {
                {"s", "com.max.xiaoheihe.module.ads.h$i"},   // 1.3.395
                {"l", "com.max.xiaoheihe.module.ads.h$g"},   // 1.3.393 / 1.3.394
        };
        for (String[] cand : candidates) {
            final String methodName = cand[0];
            final String innerName = cand[1];
            try {
                Class<?> inner = Class.forName(innerName, false, cl);
                Method m = clazz.getDeclaredMethod(methodName, inner);
                module.hook(m).intercept(chain -> {
                    if (module.isEnabled(App.KEY_BUBBLE_AD, true)) {
                        module.logd(Log.INFO, module.TAG, "拦截气泡广告 h." + methodName + "()");
                        return null;
                    }
                    return chain.proceed();
                });
                module.logd(Log.INFO, module.TAG, "✔ 气泡广告 Hook 已安装 ("
                        + methodName + "(" + inner.getSimpleName() + "))");
                return;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                // 该版本没有这个候选，继续试下一个
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "气泡广告候选 " + methodName + " 安装异常: " + t);
            }
        }
        module.logd(Log.WARN, module.TAG,
                "✘ 气泡广告 Hook 未安装：s(h$i) / l(h$g) 均不可用，该版本可能又改名了，气泡广告过滤将失效");
    }

    /** 广告拉取入口：阻断后角标数据源消失。394 = h(x0$g)，395 = i(x0$g) */
    private void hookCornerAd(Class<?> clazz, Class<?> callback) {
        final String[] candidates = {"i", "h"};
        for (String cand : candidates) {
            final String methodName = cand;
            try {
                Method m = clazz.getDeclaredMethod(methodName, callback);
                module.hook(m).intercept(chain -> {
                    if (module.isEnabled(App.KEY_CORNER_AD, true)) {
                        module.logd(Log.INFO, module.TAG, "拦截广告拉取 h." + methodName + "()");
                        return null;
                    }
                    return chain.proceed();
                });
                module.logd(Log.INFO, module.TAG, "✔ 角标广告拉取 Hook 已安装 (" + methodName + "(x0$g))");
                return;
            } catch (NoSuchMethodException e) {
                // 该版本没有这个候选，继续试下一个
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "角标广告候选 " + methodName + " 安装异常: " + t);
            }
        }
        module.logd(Log.WARN, module.TAG,
                "✘ 角标广告 Hook 未安装：i(x0$g) / h(x0$g) 均不可用，该版本可能又改名了，角标广告过滤将失效");
    }
}
