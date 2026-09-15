package com.better.heybox.hooks;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.better.heybox.App;
import com.better.heybox.HeyboxTargets;
import com.better.heybox.MainModule;

import io.github.libxposed.api.XposedInterface;

public final class PromotePostHook {

    private final MainModule module;

    private static final ConcurrentHashMap<Class<?>, Boolean> ITEM_VIEW_CLASSES = new ConcurrentHashMap<>();

    public PromotePostHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        HeyboxTargets.install(PromoteDetector.TARGET_BBS_RENDER, this::hookOne);
    }

    private void hookOne(Method method) {
        module.hook(method).intercept(this::onRender);
        module.logd(Log.INFO, module.TAG, "\u2714 \u63a8\u5e7f\u5e16 Hook \u5df2\u5b89\u88c5: "
                + method.getDeclaringClass().getName() + "#" + method.getName()
                + "/" + method.getParameterCount());
    }

    private Object onRender(XposedInterface.Chain chain) throws Throwable {
        List<Object> args = chain.getArgs();
        Object bbsLink = findBbsLink(args);
        Object viewHolder = findViewHolder(args);
        try {
            if (bbsLink != null && module.isEnabled(App.KEY_PROMOTE_AD, true)
                    && PromoteDetector.isPromote(bbsLink)) {
                String reason = PromoteDetector.matchReason(bbsLink);
                String detail = module.isEnabled(App.KEY_VERBOSE_LOG, false)
                        ? " | " + PromoteDetector.describe(bbsLink) : "";
                module.logd(Log.INFO, module.TAG,
                        "\u5c4f\u853d\u5185\u5bb9[\u65e7 BBS \u5217\u8868] \u539f\u56e0=" + (reason == null ? "\u63a8\u5e7f\u5185\u5bb9" : reason)
                                + detail);
                FeedItemHider.hide(FeedItemHider.getItemView(viewHolder));
                return null;
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "\u63a8\u5e7f\u5e16\u5224\u65ad\u5f02\u5e38\uff0c\u653e\u884c: " + t);
        }
        PostFilterHook postFilter = PostFilterHook.get();
        if (postFilter != null && bbsLink != null
                && postFilter.onRenderBind(bbsLink, viewHolder)) {
            return null;
        }
        FeedItemHider.restore(viewHolder);
        return chain.proceed();
    }

    private static Object findBbsLink(List<Object> args) {
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (isInstanceNamed(arg.getClass(), PromoteDetector.BBS_LINK_OBJ)) {
                return arg;
            }
        }
        return null;
    }

    private static Object findViewHolder(List<Object> args) {
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (hasItemView(arg.getClass()) && FeedItemHider.getItemView(arg) != null) {
                return arg;
            }
        }
        return null;
    }

    private static boolean hasItemView(Class<?> cls) {
        Boolean cached = ITEM_VIEW_CLASSES.get(cls);
        if (cached != null) {
            return cached;
        }
        boolean found;
        try {
            cls.getField("itemView");
            found = true;
        } catch (Throwable t) {
            found = false;
        }
        ITEM_VIEW_CLASSES.put(cls, found);
        return found;
    }

    private static boolean isInstanceNamed(Class<?> cls, String name) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            if (name.equals(walk.getName())) {
                return true;
            }
            walk = walk.getSuperclass();
        }
        return false;
    }
}
