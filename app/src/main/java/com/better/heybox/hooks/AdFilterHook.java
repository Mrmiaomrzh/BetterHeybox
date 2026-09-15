package com.better.heybox.hooks;

import android.util.Log;

import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.HeyboxTargets;
import com.better.heybox.MainModule;

import io.github.libxposed.api.XposedInterface;

public final class AdFilterHook {

    private final MainModule module;

    public AdFilterHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookOpenScreenAd();
        hookFeedAds(cl);
        hookBubbleAd();
        hookCornerAd();
    }

    private void hookOpenScreenAd() {
        HeyboxTargets.install(PromoteDetector.TARGET_ADS_SPLASH, method -> {
            module.hook(method).intercept(chain -> {
                if (module.isEnabled(App.KEY_OPEN_SCREEN, true)) {
                    module.logd(Log.INFO, module.TAG, "\u5c4f\u853d\u5f00\u5c4f\u5e7f\u544a | \u76ee\u6807=" + name(method)
                            + " [\u6765\u6e90=" + HeyboxTargets.sourceOf(PromoteDetector.TARGET_ADS_SPLASH) + "]");
                    return null;
                }
                return chain.proceed();
            });
            module.logd(Log.INFO, module.TAG, "\u2714 \u5f00\u5c4f\u5e7f\u544a Hook \u5df2\u5b89\u88c5 " + name(method));
        });
    }

    private void hookBubbleAd() {
        HeyboxTargets.install(PromoteDetector.TARGET_ADS_BUBBLE, method -> {
            module.hook(method).intercept(chain -> {
                if (module.isEnabled(App.KEY_BUBBLE_AD, true)) {
                    module.logd(Log.INFO, module.TAG, "\u5c4f\u853d\u6c14\u6ce1\u5e7f\u544a | \u76ee\u6807=" + name(method)
                            + " [\u6765\u6e90=" + HeyboxTargets.sourceOf(PromoteDetector.TARGET_ADS_BUBBLE) + "]");
                    return null;
                }
                return chain.proceed();
            });
            module.logd(Log.INFO, module.TAG, "\u2714 \u6c14\u6ce1\u5e7f\u544a Hook \u5df2\u5b89\u88c5 " + name(method));
        });
    }

    private void hookCornerAd() {
        HeyboxTargets.install(PromoteDetector.TARGET_ADS_CORNER, method -> {
            module.hook(method).intercept(chain -> {
                if (module.isEnabled(App.KEY_CORNER_AD, true)) {
                    module.logd(Log.INFO, module.TAG, "\u5c4f\u853d\u89d2\u6807\u5e7f\u544a\u62c9\u53d6 | \u76ee\u6807=" + name(method)
                            + " [\u6765\u6e90=" + HeyboxTargets.sourceOf(PromoteDetector.TARGET_ADS_CORNER) + "]");
                    return null;
                }
                return chain.proceed();
            });
            module.logd(Log.INFO, module.TAG, "\u2714 \u89d2\u6807\u5e7f\u544a Hook \u5df2\u5b89\u88c5 " + name(method));
        });
    }

    private void hookFeedAds(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.network.gson.FeedsContentDeserializer", false, cl);
            Class<?> jsonElement = Class.forName("com.google.gson.JsonElement", false, cl);
            Class<?> type = Class.forName("java.lang.reflect.Type", false, cl);
            Class<?> ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl);
            int installed = 0;
            for (String methodName : new String[]{"a", "deserialize"}) {
                try {
                    Method method = clazz.getDeclaredMethod(methodName, jsonElement, type, ctx);
                    module.hook(method).intercept(chain -> filterFeedAd(chain));
                    installed++;
                } catch (NoSuchMethodException ignored) {
                }
            }
            module.logd(Log.INFO, module.TAG, "\u2714 \u4fe1\u606f\u6d41\u5e7f\u544a Hook \u5df2\u5b89\u88c5 (" + installed + " \u5904)");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "\u2718 \u4fe1\u606f\u6d41\u5e7f\u544a Hook \u5931\u8d25", t);
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
                            if (PromoteDetector.contentTypes().contains(ctStr)) {
                                String detail = module.isEnabled(App.KEY_VERBOSE_LOG, false)
                                        ? " | " + describeFeedEntry(obj) : "";
                                module.logd(Log.INFO, module.TAG,
                                        "\u5c4f\u853d\u4fe1\u606f\u6d41\u5e7f\u544a\u6761\u76ee \u539f\u56e0=content_type=" + ctStr
                                                + " \u5c5e\u4e8e\u5bbf\u4e3b\u5e7f\u544a\u5e38\u91cf\u8868" + detail);
                                return createEmptyFeedObj(chain.getThisObject());
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "\u4fe1\u606f\u6d41\u5e7f\u544a\u5224\u65ad\u5f02\u5e38\uff0c\u653e\u884c: " + t);
            }
        }
        Object result = chain.proceed();
        PostFilterHook postFilter = PostFilterHook.get();
        if (postFilter != null && result != null) {
            Object replacement = postFilter.onDeserialized(result);
            if (replacement != null) {
                return replacement;
            }
        }
        return result;
    }

    private String describeFeedEntry(Object jsonObject) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u6807\u9898=").append(jsonField(jsonObject, "title"));
        sb.append(", \u4f5c\u8005=").append(jsonField(jsonObject, "author"));
        sb.append(", link_id=").append(jsonField(jsonObject, "link_id"));
        return sb.toString();
    }

    private String jsonField(Object jsonObject, String name) {
        if (jsonObject == null) {
            return "?";
        }
        try {
            Object field = jsonObject.getClass().getMethod("get", String.class)
                    .invoke(jsonObject, name);
            if (field == null) {
                return "-";
            }
            Object text = field.getClass().getMethod("getAsString").invoke(field);
            return PromoteDetector.abbreviate(text == null ? null : String.valueOf(text));
        } catch (Throwable t) {
            return "-";
        }
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
            module.logd(Log.WARN, module.TAG, "\u521b\u5efa\u7a7a FeedsContentBaseObj \u5931\u8d25: " + t);
            return null;
        }
    }

    private static String name(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + "/" + method.getParameterCount();
    }
}
