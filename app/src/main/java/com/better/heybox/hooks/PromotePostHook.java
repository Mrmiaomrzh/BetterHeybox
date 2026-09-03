package com.better.heybox.hooks;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import com.better.heybox.App;
import com.better.heybox.MainModule;

/**
 * 推广贴屏蔽：按 content_type 28/29 及指定官方账号过滤帖子。
 *
 * <p>宿主 BBSLinkObj 列表有两条渲染链，缺一不可：
 * b.L（话题详情/概念链的 WaterfallLinkAdapter 专用）与
 * b.N（社区/搜索/新闻/频道列表的共用核心，b.M 及各 viewholderbinder 均转调它）。
 * 两方法参数不同处仅在：L 的第 2 参是独立的用户对象、ViewHolder 在第 3 参；
 * N 无用户参数、ViewHolder 在第 0 参。</p>
 */
public final class PromotePostHook {

    private final MainModule module;
    /** 被隐藏 itemView 的原始高度（复用给正常帖子时恢复），弱引用避免泄漏 */
    private final WeakHashMap<View, Integer> hiddenHeights = new WeakHashMap<>();

    public PromotePostHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookRenderMethod(cl, "L", 5);
        hookRenderMethod(cl, "N", 7);
    }

    private void hookRenderMethod(ClassLoader cl, String methodName, int paramCount) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.bbs.utils.b", false, cl);
            for (Method m : clazz.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (methodName.equals(m.getName()) && p.length == paramCount
                        && "BBSLinkObj".equals(p[1].getSimpleName())) {
                    final boolean hasUserArg = "L".equals(methodName);
                    module.hook(m).intercept(chain -> {
                        try {
                            if (!module.isEnabled(App.KEY_PROMOTE_AD, true)) {
                                return chain.proceed();
                            }
                            Object bbsLink = chain.getArg(1);
                            Object viewHolder = chain.getArg(hasUserArg ? 3 : 0);
                            String ct = getContentType(bbsLink);
                            if ("28".equals(ct) || "29".equals(ct)) {
                                module.logd(Log.INFO, module.TAG, "屏蔽推广贴 (content_type=" + ct + ")");
                                hideItemView(viewHolder);
                                return null; // 跳过原渲染
                            }
                            Object userInfo = hasUserArg ? chain.getArg(2) : getUser(bbsLink);
                            String username = getUsername(userInfo);
                            if ("小黑盒推广".equals(username) || "商城看板娘".equals(username)) {
                                module.logd(Log.INFO, module.TAG, "屏蔽账号帖子: " + username);
                                hideItemView(viewHolder);
                                return null;
                            }
                            restoreItemView(viewHolder);
                        } catch (Throwable t) {
                            module.logd(Log.WARN, module.TAG, "推广贴判断异常，放行: " + t);
                        }
                        return chain.proceed();
                    });
                    module.logd(Log.INFO, module.TAG, "✔ 推广贴屏蔽 Hook 已安装（" + methodName + "）");
                    return;
                }
            }
            module.logd(Log.WARN, module.TAG, "✘ 未找到推广贴渲染方法 " + methodName);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 推广贴屏蔽 Hook 失败（" + methodName + "）", t);
        }
    }

    private String getContentType(Object bbsLink) {
        try {
            Method getter = bbsLink.getClass().getMethod("getContent_type");
            Object v = getter.invoke(bbsLink);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getUser(Object bbsLink) {
        try {
            return bbsLink == null ? null : bbsLink.getClass().getMethod("getUser").invoke(bbsLink);
        } catch (Throwable t) {
            return null;
        }
    }

    private String getUsername(Object userInfo) {
        try {
            if (userInfo == null) {
                return null;
            }
            Method getter = userInfo.getClass().getMethod("getUsername");
            Object v = getter.invoke(userInfo);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private View getItemView(Object viewHolder) {
        try {
            Object v = viewHolder.getClass().getField("itemView").get(viewHolder);
            return v instanceof View ? (View) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void hideItemView(Object viewHolder) {
        try {
            View itemView = getItemView(viewHolder);
            if (itemView == null) {
                return;
            }
            if (!hiddenHeights.containsKey(itemView)) {
                ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                hiddenHeights.put(itemView, lp != null ? lp.height : null);
            }
            itemView.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                itemView.setLayoutParams(lp);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "隐藏 itemView 失败: " + t);
        }
    }

    /** 被隐藏的 ViewHolder 复用给正常帖子时恢复可见性，否则该帖子连带消失 */
    private void restoreItemView(Object viewHolder) {
        View itemView = getItemView(viewHolder);
        if (itemView == null || !hiddenHeights.containsKey(itemView)) {
            return;
        }
        Integer height = hiddenHeights.remove(itemView);
        itemView.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = itemView.getLayoutParams();
        if (lp != null && height != null) {
            lp.height = height;
            itemView.setLayoutParams(lp);
        }
    }
}
