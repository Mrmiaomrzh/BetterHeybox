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
        if (!module.isEnabled(App.KEY_FEED_AD, true)) {
            return chain.proceed();
        }
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
        return chain.proceed();
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
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.ads.h", false, cl);
            Class<?> callback = Class.forName("com.max.xiaoheihe.utils.x0$g", false, cl);

            // 气泡展示检查入口：l() 的参数是内部类 h$g（注意：不是 x0$g）
            try {
                Class<?> innerG = Class.forName("com.max.xiaoheihe.module.ads.h$g", false, cl);
                Method l = clazz.getDeclaredMethod("l", innerG);
                module.hook(l).intercept(chain -> {
                    if (module.isEnabled(App.KEY_BUBBLE_AD, true)) {
                        module.logd(Log.INFO, module.TAG, "拦截气泡广告 h.l()");
                        return null;
                    }
                    return chain.proceed();
                });
                module.logd(Log.INFO, module.TAG, "✔ 气泡广告 Hook 已安装");
            } catch (NoSuchMethodException ignored) {
            }

            // 广告拉取入口：阻断后 f86785b 恒为 null，角标数据源消失
            try {
                Method h = clazz.getDeclaredMethod("h", callback);
                module.hook(h).intercept(chain -> {
                    if (module.isEnabled(App.KEY_CORNER_AD, true)) {
                        module.logd(Log.INFO, module.TAG, "拦截广告拉取 h.h()");
                        return null;
                    }
                    return chain.proceed();
                });
                module.logd(Log.INFO, module.TAG, "✔ 角标广告拉取 Hook 已安装");
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 气泡/角标广告 Hook 失败", t);
        }
    }
}
