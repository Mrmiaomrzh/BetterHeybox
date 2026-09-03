package com.better.heybox.liquidglass;

import com.better.heybox.MainModule;
import io.github.libxposed.api.XposedInterface;

public final class LiquidGlassHookBridge {
    private static volatile MainModule module;
    private LiquidGlassHookBridge() { }
    public static void setModule(MainModule value) { module = value; }
    public static void hookExecutable(java.lang.reflect.Executable executable,
                               ChainFunction function) {
        MainModule m = module;
        if (m == null) return;
        m.hook(executable).intercept(chain -> {
            try { return function.apply(chain); }
            catch (Throwable t) { return chain.proceed(); }
        });
    }
    interface ChainFunction { Object apply(XposedInterface.Chain chain) throws Throwable; }
}
