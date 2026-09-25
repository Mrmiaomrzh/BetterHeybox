package com.better.heybox.hooks;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.MainModule;

public final class SingleColumnFeedHook {

    private static final String CONTAINER_CLASS =
            "com.max.feature.feeds.view.itemview.WaterfallPairGroupContainer";
    private static final String CARD_CLASS =
            "com.max.feature.feeds.view.itemview.WaterfallFeedsFlowItemViewV2";

    private static final float COVER_RATIO = 210f / 375f;

    private final MainModule module;
    private Class<?> coverClass;

    public SingleColumnFeedHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        installLegacyWaterfallHook(cl);
        installEpoxyPairHook(cl);
    }

    private boolean enabled() {
        return module.isEnabled(App.KEY_SINGLE_COLUMN_FEED, false);
    }

    private void installLegacyWaterfallHook(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.bbs.utils.b", false, cl);
            Class<?> rvClass = Class.forName("androidx.recyclerview.widget.RecyclerView", false, cl);
            Class<?> lmClass = Class.forName(
                    "androidx.recyclerview.widget.RecyclerView$LayoutManager", false, cl);
            Class<?> sglmClass = Class.forName(
                    "androidx.recyclerview.widget.StaggeredGridLayoutManager", false, cl);
            Class<?> llmClass = Class.forName(
                    "androidx.recyclerview.widget.LinearLayoutManager", false, cl);
            Class<?> ctxClass = Class.forName("android.content.Context", false, cl);

            final Method setLayoutManager = rvClass.getMethod("setLayoutManager", lmClass);
            final Method getLayoutManager = rvClass.getMethod("getLayoutManager");
            final Constructor<?> llmCtor = llmClass.getConstructor(ctxClass);

            Method target = null;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!"X".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5 && p[0] == ctxClass && p[1] == rvClass
                        && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到双列瀑布流布局方法 b.X(Context,RecyclerView,III)");
                return;
            }

            module.hook(target).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (enabled()) {
                        Object rv = chain.getArg(1);
                        Object cur = getLayoutManager.invoke(rv);
                        if (cur != null && sglmClass.isInstance(cur)) {
                            setLayoutManager.invoke(rv, llmCtor.newInstance(chain.getArg(0)));
                            module.logd(Log.INFO, module.TAG, "已屏蔽双列瀑布流，切换为单列布局");
                        }
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "切换单列布局失败: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 双列瀑布流屏蔽 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 双列瀑布流屏蔽 Hook 失败", t);
        }
    }

    private void installEpoxyPairHook(ClassLoader cl) {
        try {
            Class<?> container = Class.forName(CONTAINER_CLASS, false, cl);
            Class<?> card = Class.forName(CARD_CLASS, false, cl);
            try {
                coverClass = Class.forName(CARD_CLASS + "$RoundedCoverContainer", false, cl);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "未找到 RoundedCoverContainer，封面比例修正不可用");
            }
            int installed = 0;

            Method onViewAdded = container.getDeclaredMethod("onViewAdded", View.class);
            module.hook(onViewAdded).intercept(chain -> {
                try {
                    if (enabled()) {
                        ((LinearLayout) chain.getThisObject()).setOrientation(LinearLayout.VERTICAL);
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "配对容器纵向化失败: " + t);
                }
                Object result = chain.proceed();
                try {
                    if (enabled()) {
                        View child = (View) chain.getArg(0);
                        child.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "配对子项全宽化失败: " + t);
                }
                return result;
            });
            installed++;

            Method setTargetHeight = container.getDeclaredMethod("setChildrenTargetHeight", int.class);
            module.hook(setTargetHeight).intercept(chain ->
                    enabled() ? null : chain.proceed());
            installed++;

            Method onAttached = card.getDeclaredMethod("onAttachedToWindow");
            module.hook(onAttached).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (enabled()) {
                        View self = (View) chain.getThisObject();
                        ViewGroup.LayoutParams lp = self.getLayoutParams();
                        if (lp instanceof LinearLayout.LayoutParams && lp.width == 0) {
                            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                            ((LinearLayout.LayoutParams) lp).weight = 0f;
                            self.setLayoutParams(lp);
                        }
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "卡片全宽化失败: " + t);
                }
                return result;
            });
            installed++;

            Method setCardWidth = card.getDeclaredMethod("setCardWidthPx", int.class);
            module.hook(setCardWidth).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (enabled()) {
                        int width = (Integer) chain.getArg(0);
                        if (width > 0) {
                            resizeCover((ViewGroup) chain.getThisObject(), width);
                        }
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "封面比例修正失败: " + t);
                }
                return result;
            });
            installed++;

            module.logd(Log.INFO, module.TAG,
                    "✔ 首页推荐流单列 Hook 已安装（" + installed + " 处）");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG,
                    "✘ 首页推荐流单列 Hook 安装失败（当前版本可能无此样式）: " + t);
        }
    }

    private void resizeCover(ViewGroup card, int widthPx) {
        View cover = findCover(card);
        if (cover == null) {
            return;
        }
        ViewGroup.LayoutParams lp = cover.getLayoutParams();
        int target = (int) (widthPx * COVER_RATIO);
        if (lp != null && lp.height != target) {
            lp.height = target;
            cover.setLayoutParams(lp);
        }
    }

    private View findCover(ViewGroup root) {
        if (coverClass == null) {
            return null;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (coverClass.isInstance(child)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findCover((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
