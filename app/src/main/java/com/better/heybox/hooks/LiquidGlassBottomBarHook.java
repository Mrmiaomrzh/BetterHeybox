package com.better.heybox.hooks;

import android.app.Activity;
import android.os.Bundle;
import java.lang.reflect.Method;
import com.better.heybox.MainModule;
import com.better.heybox.ViewUtils;
import com.better.heybox.liquidglass.LiquidGlassInstaller;

/** Hooks host lifecycle and delegates to the complete ported glass installer. */
public final class LiquidGlassBottomBarHook {
    private final MainModule module;
    public LiquidGlassBottomBarHook(MainModule module) { this.module = module; }
    public void install(ClassLoader cl) {
        // 底部 Toast/应用内通知抬升避让玻璃栏；进程级一次，与玻璃是否启用无关
        //（preLift 只在玻璃 host 存活时才动 y）
        com.better.heybox.liquidglass.BottomToastLifter.install();
        LiquidGlassInstaller.installSettingsEntries(cl);
        try {
            Class<?> main = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            hook(main.getDeclaredMethod("onCreate", Bundle.class));
            hook(main.getDeclaredMethod("onResume"));
            try {
                Class<?> observer = Class.forName("com.max.xiaoheihe.MainActivity$j", false, cl);
                Method b = ViewUtils.findMethod(observer, "b", Boolean.class);
                if (b != null) {
                    hook(b, true, main);
                }
            } catch (Throwable ignored) { }
        } catch (Throwable t) {
            module.logd(android.util.Log.ERROR, module.TAG, "液态玻璃生命周期 Hook 安装失败", t);
        }
    }
    private void hook(Method method) { hook(method, false, null); }
    private void hook(Method method, boolean inner, Class<?> main) {
        module.hook(method).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Activity activity = inner ? (Activity) ViewUtils.findOuter(chain.getThisObject(), main) :
                        (chain.getThisObject() instanceof Activity ? (Activity) chain.getThisObject() : null);
                if (activity != null) LiquidGlassInstaller.scheduleInstall(activity);
            } catch (Throwable t) {
                module.logd(android.util.Log.WARN, module.TAG, "液态玻璃安装调度失败", t);
            }
            return result;
        });
    }
}
