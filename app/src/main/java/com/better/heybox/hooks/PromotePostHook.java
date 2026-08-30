package com.better.heybox.hooks;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.MainModule;

/**
 * 推广贴屏蔽：按 content_type 28/29 及指定官方账号过滤帖子。
 */
public final class PromotePostHook {

    private final MainModule module;

    public PromotePostHook(MainModule module) {
        this.module = module;
    }
    public void install(ClassLoader cl) {
        hookPromotePosts(cl);
    }

    private void hookPromotePosts(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.bbs.utils.b", false, cl);
            // 找渲染帖子的静态方法
            for (Method m : clazz.getDeclaredMethods()) {
                if ("L".equals(m.getName()) && m.getParameterTypes().length == 5) {
                    module.hook(m).intercept(chain -> {
                        try {
                            if (!module.isEnabled(App.KEY_PROMOTE_AD, true)) {
                                return chain.proceed();
                            }
                            Object bbsLink = chain.getArg(1);
                            String ct = getContentType(bbsLink);
                            if ("28".equals(ct) || "29".equals(ct)) {
                                module.logd(Log.INFO, module.TAG, "屏蔽推广贴 (content_type=" + ct + ")");
                                hideItemView(chain.getArg(3));
                                return null; // 跳过原渲染
                            }
                            String username = getUsername(chain.getArg(2));
                            if ("小黑盒推广".equals(username) || "商城看板娘".equals(username)) {
                                module.logd(Log.INFO, module.TAG, "屏蔽账号帖子: " + username);
                                hideItemView(chain.getArg(3));
                                return null;
                            }
                        } catch (Throwable t) {
                            module.logd(Log.WARN, module.TAG, "推广贴判断异常，放行: " + t);
                        }
                        return chain.proceed();
                    });
                    module.logd(Log.INFO, module.TAG, "✔ 推广贴屏蔽 Hook 已安装");
                    return;
                }
            }
            module.logd(Log.WARN, module.TAG, "✘ 未找到推广贴渲染方法 L");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 推广贴屏蔽 Hook 失败", t);
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
    private void hideItemView(Object viewHolder) {
        try {
            Field itemViewField = viewHolder.getClass().getField("itemView");
            Object v = itemViewField.get(viewHolder);
            if (v instanceof View) {
                View itemView = (View) v;
                itemView.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                if (lp != null) {
                    lp.height = 0;
                    itemView.setLayoutParams(lp);
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "隐藏 itemView 失败: " + t);
        }
    }
}
