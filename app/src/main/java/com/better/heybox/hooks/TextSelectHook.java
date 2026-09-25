package com.better.heybox.hooks;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.CustomTextSelection;
import com.better.heybox.MainModule;
import com.better.heybox.LogRecorder;
import com.better.heybox.SelectionSafeLinkMovementMethod;

public final class TextSelectHook {

    private final MainModule module;

    private static volatile TextSelectHook sInstance;

    private static final List<WeakReference<View>> sRegisteredRoots = new ArrayList<>();

    public TextSelectHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    public static void refresh() {
        TextSelectHook instance = sInstance;
        if (instance != null) {
            instance.refreshAll();
        }
    }

    public void install(ClassLoader cl) {
        hookTextSelectHandler(cl);
        hookPostTextSelect(cl);
    }

    private void hookTextSelectHandler(ClassLoader cl) {
        try {
            Class<?> handler = Class.forName(
                    "com.max.common.common.selecthandler.TextSelectHandler",
                    false,
                    cl
            );

            Method onTouch = null;

            for (Method m : handler.getDeclaredMethods()) {
                if ("onTouch".equals(m.getName())
                        && m.getParameterCount() == 2) {
                    onTouch = m;
                    break;
                }
            }

            if (onTouch == null) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "✘ 未找到 TextSelectHandler.onTouch"
                );
                return;
            }
            module.hook(onTouch).intercept(chain -> false);

            module.logd(
                    Log.INFO,
                    module.TAG,
                    "✔ TextSelectHandler 防复制拦截已解除"
            );

        } catch (Throwable t) {
            module.logd(
                    Log.ERROR,
                    module.TAG,
                    "✘ TextSelectHandler 解除失败",
                    t
            );
        }
    }

    private void hookPostTextSelect(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName(
                    "com.max.xiaoheihe.module.bbs.post.ui.fragments.v2.PostPictureFragmentV2",
                    false,
                    cl
            );

            Method target = null;
            Method renamed = null;
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == View.class
                        && m.getName().startsWith("installViews")) {
                    if ("installViews".equals(m.getName())) {
                        target = m;
                        break;
                    }
                    if (renamed == null) {
                        renamed = m;
                    }
                }
            }
            if (target == null) {
                target = renamed;
            }

            if (target == null) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "✘ 未找到 PostPictureFragmentV2.installViews"
                );
                return;
            }

            module.hook(target).intercept(chain -> {

                Object result = chain.proceed();

                try {
                    Object arg = chain.getArg(0);

                    if (arg instanceof View) {
                        scheduleEnableTextSelect((View) arg, 0);
                        registerRoot((View) arg);
                    }

                } catch (Throwable t) {
                    module.logd(
                            Log.WARN,
                            module.TAG,
                            "正文选择调度异常: " + t
                    );
                }

                return result;
            });

            module.logd(
                    Log.INFO,
                    module.TAG,
                    "✔ 帖子正文原生文本选择 Hook 已安装"
            );

        } catch (Throwable t) {
            module.logd(
                    Log.ERROR,
                    module.TAG,
                    "✘ 帖子正文复制 Hook 失败",
                    t
            );
        }
    }

    private void registerRoot(View root) {
        if (root == null) {
            return;
        }
        synchronized (sRegisteredRoots) {
            for (WeakReference<View> ref : sRegisteredRoots) {
                if (ref.get() == root) {
                    return;
                }
            }
            sRegisteredRoots.add(new WeakReference<View>(root));
        }
    }

    private void refreshAll() {
        synchronized (sRegisteredRoots) {
            for (WeakReference<View> ref : sRegisteredRoots) {
                View root = ref.get();
                if (root == null) {
                    continue;
                }
                try {
                    enablePostTextSelect(root);
                } catch (Throwable t) {
                    module.logd(
                            Log.WARN,
                            module.TAG,
                            "刷新文本选择设置异常: " + t
                    );
                }
            }
        }
    }

    private void scheduleEnableTextSelect(
            final View content,
            final int attempt
    ) {
        if (content == null) {
            return;
        }
        if (attempt > 15) {
            module.logd(
                    Log.WARN,
                    module.TAG,
                    "正文 View 长时间未就绪，放弃开启文本选择"
            );
            return;
        }

        long delay = attempt == 0 ? 200L : 150L;

        content.postDelayed(() -> {
            try {
                if (content.isShown()
                        && content.getWidth() > 0
                        && content.getHeight() > 0) {

                    enablePostTextSelect(content);

                } else {
                    scheduleEnableTextSelect(
                            content,
                            attempt + 1
                    );
                }

            } catch (Throwable t) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "正文选择调度异常: " + t
                );
            }
        }, delay);
    }

    private void enablePostTextSelect(View root) {
        if (root == null) {
            return;
        }
        LogRecorder.setContext(root.getContext());
        if (!module.isEnabled(App.KEY_COPY_POST, true)) {
            return;
        }
        boolean customSelect = module.isEnabled(App.KEY_CUSTOM_TEXT_SELECT, false);

        applyTextSelectByIds(root, customSelect,
                new String[]{"tv_title", "tv_desc"}, true, "设置文本选择失败");

        applyTextSelectByIds(root, customSelect,
                new String[]{"bbs_name", "bbs_username", "bbs_user_name", "tv_post_author",
                        "tv_author", "tv_username", "tv_nickname", "tv_user_name",
                        "tv_userinfo", "tv_user_info", "author_name", "username",
                        "tv_name", "tv_user", "tv_author_name"}, false, "设置用户名长按选择失败");
    }

    private void applyTextSelectByIds(View root, boolean customSelect,
                                      String[] idNames, boolean body, String logLabel) {
        for (String idName : idNames) {
            try {
                int id = root.getResources().getIdentifier(idName, "id", MainModule.TARGET_PKG);
                if (id == 0) {
                    continue;
                }
                View v = root.findViewById(id);
                if (!(v instanceof TextView)) {
                    continue;
                }
                applyTextSelect((TextView) v, idName, body, customSelect);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, logLabel + " (" + idName + "): " + t);
            }
        }
    }

    private void applyTextSelect(TextView tv, String idName, boolean body, boolean customSelect) {
        CustomTextSelection.detach(tv);

        if (customSelect) {
            if (tv.isTextSelectable()) {
                tv.setTextIsSelectable(false);
            }
            tv.setMovementMethod(null);
            CustomTextSelection.attach(tv);
            module.logd(
                    Log.INFO,
                    module.TAG,
                    "✔ 已启用自绘制文本选择: " + idName
            );
            return;
        }

        if (!tv.isTextSelectable()) {
            tv.setTextIsSelectable(true);
            module.logd(
                    Log.INFO,
                    module.TAG,
                    "✔ 已开启标准文本选择: " + idName
            );
        }
        if (body) {
            tv.setLinksClickable(true);
            tv.setMovementMethod(SelectionSafeLinkMovementMethod.getInstance());
        }
    }
}
