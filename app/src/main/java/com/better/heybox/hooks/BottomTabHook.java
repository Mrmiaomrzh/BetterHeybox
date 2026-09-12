package com.better.heybox.hooks;

import android.app.Activity;
import android.util.Log;
import android.view.View;

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
        applyBottomTabSettings(activityObj, true);
    }

    private void applyBottomTabSettings(Object activityObj, boolean reschedule) {
        try {
            Activity activity = activityObj instanceof Activity ? (Activity) activityObj : null;
            Object binding = findViewBinding(activityObj);
            android.widget.RadioGroup group = findTabGroup(activity, binding);
            if (group == null) {
                module.logd(Log.WARN, module.TAG, "未找到底部导航栏 rg_main");
                return;
            }
            String labelHome = cacheRuntimeLabel(group, binding, 0, "rb_1", "j");
            String labelSlot2 = cacheRuntimeLabel(group, binding, 1, "rb_2", "k");
            String labelSlot4 = cacheRuntimeLabel(group, binding, 2, "rb_4", "m");
            sAnyTabHidden = false;
            if (module.isEnabled(App.KEY_HIDE_TAB_HOME, false)) {
                hideTabSlot(group, binding, "rb_1", "j", labelHome);
                sAnyTabHidden = true;
            }
            if (module.isEnabled(App.KEY_HIDE_TAB_HOT, false)) {
                hideTabSlot(group, binding, "rb_2", "k", labelSlot2);
                sAnyTabHidden = true;
            }
            if (module.isEnabled(App.KEY_HIDE_TAB_GAME, false)) {
                hideTabSlot(group, binding, "rb_4", "m", labelSlot4);
                sAnyTabHidden = true;
            }
            View plus = findPlusButton(activity, binding);
            if (module.isEnabled(App.KEY_HIDE_ADD, false)) {
                hideView(findPlaceholder(activity, binding, group), "推荐占位");
            } else if (plus != null && plus.getVisibility() == View.VISIBLE
                    && !LiquidGlassInstaller.isGlassBarActive()) {
                alignPlusToPlaceholder(activity, binding, group, plus);
            }
            normalizeVisibleTabs(group);
            if (reschedule) {
                group.addOnLayoutChangeListener((v, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> normalizeVisibleTabs(group));
                retryDelayed(group, () -> applyBottomTabSettings(activityObj, false),
                        100, 500, 1500, 3000);
            }
            ensureVisibleTabSelected(group);
            LiquidGlassInstaller.syncTabVisibility();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "底部导航栏设置应用失败: " + t);
        }
    }

    private static final String[] sRuntimeLabels = new String[3];

    private static volatile boolean sAnyTabHidden;

    public static String runtimeTabLabel(int slot) {
        return slot >= 0 && slot < sRuntimeLabels.length ? sRuntimeLabels[slot] : null;
    }

    public static boolean isAnyTabHidden() {
        return sAnyTabHidden;
    }

    private String cacheRuntimeLabel(android.widget.RadioGroup group, Object binding,
                                     int slot, String rbName, String fallbackField) {
        View v = slotView(group, rbName, fallbackField);
        if (v == null && binding != null) {
            v = bindingView(binding, fallbackField);
        }
        if (v instanceof android.widget.RadioButton) {
            CharSequence text = ((android.widget.RadioButton) v).getText();
            if (text != null && text.length() > 0) {
                sRuntimeLabels[slot] = text.toString();
            }
        }
        return sRuntimeLabels[slot];
    }

    private void hideTabSlot(android.widget.RadioGroup group, Object binding,
                             String rbName, String fallbackField, String label) {
        View v = slotView(group, rbName, fallbackField);
        if (v == null && binding != null) {
            v = bindingView(binding, fallbackField);
        }
        if (v != null) {
            hideView(v, label != null ? label : rbName);
            return;
        }
        module.logd(Log.WARN, module.TAG, "未找到 tab（" + rbName + " / 字段 " + fallbackField + "）");
    }

    private View slotView(android.widget.RadioGroup group, String rbName, String fallbackField) {
        try {
            int id = group.getResources().getIdentifier(rbName, "id", MainModule.TARGET_PKG);
            if (id != 0) {
                return group.findViewById(id);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int hostId(Activity activity, String name) {
        if (activity == null) {
            return 0;
        }
        try {
            return activity.getResources().getIdentifier(name, "id", MainModule.TARGET_PKG);
        } catch (Throwable t) {
            return 0;
        }
    }

    private android.widget.RadioGroup findTabGroup(Activity activity, Object binding) {
        int id = hostId(activity, "rg_main");
        View v = id != 0 ? activity.findViewById(id) : null;
        if (v instanceof android.widget.RadioGroup) {
            return (android.widget.RadioGroup) v;
        }
        return tabGroup(binding);
    }

    private View findPlusButton(Activity activity, Object binding) {
        int id = hostId(activity, "vg_mid_tab");
        View v = id != 0 ? activity.findViewById(id) : null;
        return v != null ? v : bindingView(binding, "r");
    }

    private View findPlaceholder(Activity activity, Object binding,
                                 android.widget.RadioGroup group) {
        int id = hostId(activity, "rb_3");
        View v = id != 0 ? group.findViewById(id) : null;
        return v != null ? v : bindingView(binding, "l");
    }

    private View bindingView(Object binding, String fieldName) {
        try {
            Field field = binding.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object obj = field.get(binding);
            return obj instanceof View ? (View) obj : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void hideView(View v, String label) {
        if (v == null) {
            return;
        }
        v.setVisibility(View.GONE);
        retryDelayed(v, () -> v.setVisibility(View.GONE), 500, 1500, 3000);
        module.logd(Log.INFO, module.TAG, "隐藏 " + label + ": " + v.getVisibility());
    }

    private void alignPlusToPlaceholder(Activity activity, Object binding,
                                        android.widget.RadioGroup group, View plus) {
        View slot = findPlaceholder(activity, binding, group);
        plus.post(() -> {
            try {
                if (LiquidGlassInstaller.isGlassBarActive()
                        || slot == null || slot.getVisibility() == View.GONE
                        || plus.getVisibility() != View.VISIBLE
                        || plus.getWidth() == 0 || slot.getWidth() == 0) {
                    plus.setTranslationX(0);
                    return;
                }
                int[] slotLoc = new int[2];
                slot.getLocationOnScreen(slotLoc);
                int[] plusLoc = new int[2];
                plus.getLocationOnScreen(plusLoc);
                float target = slotLoc[0] + slot.getWidth() / 2f;
                float current = plusLoc[0] - plus.getTranslationX() + plus.getWidth() / 2f;
                if (Math.abs(target - current) > 1f) {
                    plus.setTranslationX(target - current);
                }
            } catch (Throwable ignored) {
            }
        });
    }

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

    private void normalizeVisibleTabs(android.widget.RadioGroup group) {
        try {
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

    private void ensureVisibleTabSelected(android.widget.RadioGroup group) {
        try {
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
                retryDelayed(v, () -> v.setVisibility(View.GONE), 500, 1500, 3000);
                module.logd(Log.INFO, module.TAG, "隐藏 " + label + ": " + v.getVisibility());
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG,
                    "隐藏 tab 失败 (" + label + ")，字段 " + fieldName + " 可能被 Robust 重命名");
        }
    }
}
