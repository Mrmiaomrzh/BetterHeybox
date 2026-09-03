package com.better.heybox.hooks;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.ViewUtils;
import com.better.heybox.liquidglass.LiquidGlassInstaller;

/**
 * 底部导航栏屏蔽：按开关隐藏首页 / 热点 / 游戏库 / 加号（需重启小黑盒生效）。
 */
public final class BottomTabHook {

    private final MainModule module;

    public BottomTabHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookBottomTabs(cl);
    }

    private void hookBottomTabs(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onCreate = clazz.getDeclaredMethod("onCreate", android.os.Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    applyBottomTabSettings(chain.getThisObject());
                } catch (Throwable t) {
                    module.logd(Log.ERROR, module.TAG, "应用底部导航栏设置异常", t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 底部导航栏 Hook 已安装");

            // hook onResume：热重载后切回小黑盒立即重新应用底栏设置
            try {
                Method onResume = clazz.getDeclaredMethod("onResume");
                module.hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        applyBottomTabSettings(chain.getThisObject());
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "onResume 应用底栏设置失败: " + t);
                    }
                    return result;
                });
                module.logd(Log.INFO, module.TAG, "✔ 底栏 onResume Hook 已安装");
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "底栏 onResume Hook 失败: " + t);
            }

            // 底栏会被 MainActivity$j.b(Boolean) 回调重新显示，hook 该回调后重新应用隐藏
            try {
                Class<?> observerCls = Class.forName("com.max.xiaoheihe.MainActivity$j", false, cl);
                Method b = ViewUtils.findMethod(observerCls, "b", Boolean.class);
                if (b != null) {
                    module.hook(b).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object mainActivity = ViewUtils.findOuter(chain.getThisObject(), clazz);
                            if (mainActivity != null) {
                                applyBottomTabSettings(mainActivity);
                            }
                        } catch (Throwable t) {
                            module.logd(Log.WARN, module.TAG, "底栏状态回调后重新隐藏失败: " + t);
                        }
                        return result;
                    });
                    module.logd(Log.INFO, module.TAG, "✔ 底栏状态回调 Hook 已安装");
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "底栏状态回调 Hook 安装失败: " + t);
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 底部导航栏 Hook 失败", t);
        }
    }

    /** 隐藏 tab 与加号 */
    private void applyBottomTabSettings(Object activityObj) {
        try {
            Object binding = findViewBinding(activityObj);
            if (binding == null) {
                module.logd(Log.WARN, module.TAG, "未找到 ViewBinding 字段（fi.i1 / hi.i1）");
                return;
            }
            boolean anyTabHidden = false;
            // tab 名称按小黑盒资源动态解析（版本自适应：发现/游戏库/社区）
            String labelHome = MainModule.getHeyboxTabLabel(
                    activityObj instanceof Activity ? (Activity) activityObj : null, "discover", "发现");
            String labelHot = MainModule.getHeyboxTabLabel(
                    activityObj instanceof Activity ? (Activity) activityObj : null, "game_store", "游戏库");
            String labelBbs = MainModule.getHeyboxTabLabel(
                    activityObj instanceof Activity ? (Activity) activityObj : null, "bbs", "社区");
            if (module.isEnabled(App.KEY_HIDE_TAB_HOME, false)) {
                hideTabField(binding, "j", labelHome);
                anyTabHidden = true;
            }
            if (module.isEnabled(App.KEY_HIDE_TAB_HOT, false)) {
                hideTabField(binding, "k", labelHot);
                anyTabHidden = true;
            }
            if (module.isEnabled(App.KEY_HIDE_TAB_GAME, false)) {
                hideTabField(binding, "m", labelBbs);
                anyTabHidden = true;
            }
            // 加号：独立开关，或隐藏了任意 tab 时联动隐藏（保持底栏布局对称）
            if (module.isEnabled(App.KEY_HIDE_ADD, false) || anyTabHidden) {
                hideTabField(binding, "r", "加号");
                // 同时去掉「推荐」占位（rb_3 在部分版本为 INVISIBLE，隐藏加号后去掉其槽位让剩余 tab 等分）
                hideTabField(binding, "l", "推荐占位");
            }
            normalizeVisibleTabs(binding);
            ViewGroup group = tabGroup(binding);
            if (group != null) {
                group.addOnLayoutChangeListener((v, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> normalizeVisibleTabs(binding));
                // 小黑盒会在启动/生命周期回调中延迟重新显示 tab，延迟多次重新应用以覆盖
                retryDelayed(group, () -> normalizeVisibleTabs(binding), 100, 500, 1500, 3000);
            }
            ensureVisibleTabSelected(binding);
            LiquidGlassInstaller.syncTabVisibility();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "底部导航栏设置应用失败: " + t);
        }
    }

    /** 反射取绑定里的 tab 组字段 "o"（混淆名） */
    private android.widget.RadioGroup tabGroup(Object binding) {
        try {
            Field f = binding.getClass().getDeclaredField("o");
            f.setAccessible(true);
            Object value = f.get(binding);
            return value instanceof android.widget.RadioGroup ? (android.widget.RadioGroup) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void normalizeVisibleTabs(Object binding) {
        try {
            android.widget.RadioGroup group = tabGroup(binding);
            if (group == null) return;
            int visible = 0;
            for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i).getVisibility() == View.VISIBLE) visible++;
            if (visible == 0) return;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                android.widget.LinearLayout.LayoutParams lp = child.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams
                        ? (android.widget.LinearLayout.LayoutParams) child.getLayoutParams() : null;
                if (lp != null && (lp.width != 0 || lp.weight != 1f)) { lp.width = 0; lp.weight = 1f; child.setLayoutParams(lp); }
            }
            group.requestLayout();
        } catch (Throwable ignored) { }
    }

    private void ensureVisibleTabSelected(Object binding) {
        try {
            android.widget.RadioGroup group = tabGroup(binding);
            if (group == null) return;
            int checkedId = group.getCheckedRadioButtonId();
            if (checkedId != -1) {
                View checked = group.findViewById(checkedId);
                if (checked != null && checked.getVisibility() == View.VISIBLE) {
                    return;
                }
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.RadioButton
                        && child.getVisibility() == View.VISIBLE) {
                    int id = child.getId();
                    if (id != -1 && id != checkedId) {
                        group.check(id);
                        module.logd(Log.INFO, module.TAG, "选中 tab 已隐藏，切换到可见 tab id=" + id);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "纠正底栏选中项失败: " + t);
        }
    }

    private void retryDelayed(View view, Runnable action, long... delays) {
        for (long delay : delays) {
            view.postDelayed(action, delay);
        }
    }

    private Object findViewBinding(Object activity) {
        try {
            for (Field f : activity.getClass().getDeclaredFields()) {
                if (f.getType().getName().endsWith(".i1")) {
                    f.setAccessible(true);
                    return f.get(activity);
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "查找 ViewBinding 失败: " + t);
        }
        return null;
    }

    private void hideTabField(Object binding, String fieldName, String label) {
        try {
            Field field = binding.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object obj = field.get(binding);
            if (obj instanceof View) {
                final View v = (View) obj;
                v.setVisibility(View.GONE);
                // 小黑盒会延迟重新显示 tab/加号，延迟多次重新隐藏覆盖（否则 tab 与加号重合）
                retryDelayed(v, () -> v.setVisibility(View.GONE), 500, 1500, 3000);
                module.logd(Log.INFO, module.TAG, "隐藏 " + label + ": " + v.getVisibility());
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG,
                    "隐藏 tab 失败 (" + label + ")，字段 " + fieldName + " 可能被 Robust 重命名");
        }
    }
}
