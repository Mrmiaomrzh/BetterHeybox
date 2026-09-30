package com.better.heybox.hooks;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.HeyboxTargets;
import com.better.heybox.MainModule;

public final class FakeUsageAccessHook {

    public static final String GRANT_CLASS = "com.max.xiaoheihe.module.account.utils.f";

    public static final String USAGE_CLASS = "com.max.xiaoheihe.module.account.utils.UsageManager";

    public static final String TARGET_FAKE_USAGE_GRANTED = "fake.usage.granted";

    public static final String TARGET_FAKE_USAGE_LIST = "fake.usage.list";

    public static final String TARGET_FAKE_USAGE_UPLOAD = "fake.usage.upload";

    private final MainModule module;

    private static volatile FakeUsageAccessHook sInstance;

    private static volatile boolean sFakePermission;
    private static volatile boolean sEmptyData;

    public FakeUsageAccessHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    public static void refresh() {
        FakeUsageAccessHook instance = sInstance;
        if (instance == null) {
            return;
        }
        sFakePermission = instance.module.isEnabled(App.KEY_FAKE_USAGE_ACCESS, false);
        sEmptyData = instance.module.isEnabled(App.KEY_FAKE_USAGE_EMPTY_DATA, false);
    }

    public void install(ClassLoader cl) {
        refresh();
        int hooked = 0;
        hooked += hookGrant(cl);
        hooked += hookUsageManager(cl);
        if (hooked == 0) {
            module.logd(Log.WARN, module.TAG,
                    "伪装使用情况权限：未挂上任何宿主方法，功能不生效（可等待 DexKit 兜底）");
        } else {
            module.logd(Log.INFO, module.TAG, "✔ 伪装使用情况权限 Hook 已安装（" + hooked + " 处）"
                    + " | 伪装权限=" + (sFakePermission ? "开" : "关")
                    + " 空数据=" + (sEmptyData ? "开" : "关"));
        }
    }

    private int hookGrant(ClassLoader cl) {
        Class<?> clazz;
        try {
            clazz = Class.forName(GRANT_CLASS, false, cl);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "伪装使用情况权限：未找到 " + GRANT_CLASS);
            return 0;
        }
        int count = 0;
        for (String name : new String[]{"i", "a"}) {
            Method method = findBooleanContextMethod(clazz, name);
            if (method == null) {
                continue;
            }
            try {
                module.hook(method).intercept(chain -> {
                    if (!sFakePermission) {
                        return chain.proceed();
                    }
                    long hits = ++sGrantHits;
                    if (hits <= 3) {
                        module.logd(Log.INFO, module.TAG,
                                "伪装使用情况权限：判定 " + name + "(Context) 已按「已授权」放行");
                    }
                    return Boolean.TRUE;
                });
                count++;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG,
                        "伪装使用情况权限：" + name + " Hook 失败: " + t);
            }
        }
        return count;
    }

    private static volatile long sGrantHits;

    private static Method findBooleanContextMethod(Class<?> clazz, String name) {
        try {
            Method method = clazz.getDeclaredMethod(name, android.content.Context.class);
            if (method.getReturnType() != boolean.class) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    private int hookUsageManager(ClassLoader cl) {
        Class<?> clazz;
        try {
            clazz = Class.forName(USAGE_CLASS, false, cl);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "伪装使用情况权限：未找到 " + USAGE_CLASS);
            return 0;
        }
        int count = 0;
        Method listGetter = findListContextMethod(clazz, "r");
        if (listGetter != null) {
            try {
                module.hook(listGetter).intercept(chain -> {
                    if (!sFakePermission || !sEmptyData) {
                        return chain.proceed();
                    }
                    return Collections.emptyList();
                });
                count++;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "伪装使用情况权限：UsageManager.r Hook 失败: " + t);
            }
        }
        Method upload = findUploadMethod(clazz);
        if (upload != null) {
            try {
                module.hook(upload).intercept(chain -> {
                    if (!sFakePermission || !sEmptyData) {
                        return chain.proceed();
                    }
                    return null;
                });
                count++;
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "伪装使用情况权限：UsageManager.h Hook 失败: " + t);
            }
        }
        return count;
    }

    private static Method findListContextMethod(Class<?> clazz, String name) {
        try {
            Method method = clazz.getDeclaredMethod(name, android.content.Context.class);
            if (!List.class.isAssignableFrom(method.getReturnType())) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findUploadMethod(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getParameterCount() != 5 || method.getReturnType() != void.class) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types[0] != android.content.Context.class
                    || types[2] != boolean.class
                    || types[3] != boolean.class) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }


    public static String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("伪装使用情况权限: ").append(sFakePermission ? "开" : "关").append('\n');
        sb.append("返回空数据: ").append(sEmptyData ? "开" : "关").append('\n');
        sb.append("判定类: ").append(GRANT_CLASS).append('\n');
        sb.append("数据类: ").append(USAGE_CLASS).append('\n');
        sb.append("命中次数: ").append(sGrantHits).append('\n');
        sb.append("DexKit 解析:\n");
        for (String key : new String[]{
                TARGET_FAKE_USAGE_GRANTED,
                TARGET_FAKE_USAGE_LIST,
                TARGET_FAKE_USAGE_UPLOAD}) {
            sb.append("  ").append(key).append(" = ")
                    .append(HeyboxTargets.sourceOf(key)).append('\n');
        }
        return sb.toString();
    }
}
