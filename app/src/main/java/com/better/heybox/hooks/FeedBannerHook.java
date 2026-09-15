package com.better.heybox.hooks;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.MainModule;

public final class FeedBannerHook {

    private final MainModule module;

    public FeedBannerHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.data.model.feeds.FeedsBannerModel", false, cl);
            Method getter = clazz.getDeclaredMethod("getAdBannerList");
            module.hook(getter).intercept(chain -> {
                Object result = chain.proceed();
                if (!module.isEnabled(App.KEY_PROMOTE_AD, true) || !(result instanceof List)) {
                    return result;
                }
                List<?> list = (List<?>) result;
                if (list.isEmpty()) {
                    return result;
                }
                String detail = module.isEnabled(App.KEY_VERBOSE_LOG, false)
                        ? ", \u6761\u76ee=" + describeBanners(list) : "";
                module.logd(Log.INFO, module.TAG, "\u5c4f\u853d\u9996\u9875\u5e7f\u544a\u6a2a\u5e45 \u539f\u56e0=FeedsBannerModel.adBannerList \u975e\u7a7a"
                        + " | \u6570\u91cf=" + list.size() + detail);
                return Collections.emptyList();
            });
            module.logd(Log.INFO, module.TAG, "\u2714 \u9996\u9875\u5e7f\u544a\u6a2a\u5e45 Hook \u5df2\u5b89\u88c5");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "\u2718 \u9996\u9875\u5e7f\u544a\u6a2a\u5e45 Hook \u5931\u8d25: " + t);
        }
    }

    private String describeBanners(List<?> list) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(list.size(), 3);
        for (int i = 0; i < limit; i++) {
            Object item = list.get(i);
            if (item == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(item.getClass().getSimpleName());
            sb.append("(ads_id=").append(readString(item, "getAds_id"));
            sb.append(", \u6807\u9898=").append(readString(item, "getTitle")).append(')');
        }
        if (list.size() > limit) {
            sb.append(" \u7b49 ").append(list.size()).append(" \u6761");
        }
        return sb.toString();
    }

    private String readString(Object item, String name) {
        try {
            Object value = item.getClass().getMethod(name).invoke(item);
            return value == null ? "-" : value.toString();
        } catch (Throwable t) {
            return "-";
        }
    }
}
