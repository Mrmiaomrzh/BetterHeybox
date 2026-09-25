package com.better.heybox.hooks;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.better.heybox.App;
import com.better.heybox.Checkpoint;
import com.better.heybox.MainModule;

public final class FavourAutoCleanHook {

    private static final String[] FRAGMENT_CLASSES = {
            "com.max.xiaoheihe.module.favour.FavourCollectionContentFragment",
            "com.max.xiaoheihe.module.favour.FavourLinkFolderFragment",
    };

    private static final String COMPANION_CLASS =
            "com.max.xiaoheihe.module.bbs.utils.BBSKtUtils$Companion";

    private static final String ARG_FOLDER_ID = "folder_id";

    private static final long MIN_INTERVAL_MS = 30_000L;

    private final MainModule module;

    private final Handler main = new Handler(Looper.getMainLooper());

    private final Map<Object, Long> lastTrigger = new WeakHashMap<>();

    private volatile ConfirmSpec confirmSpec;
    private volatile boolean confirmResolved;

    public FavourAutoCleanHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        int installed = 0;
        for (String name : FRAGMENT_CLASSES) {
            if (hookFragment(cl, name)) {
                installed++;
            }
        }
        Checkpoint.mark("自动清理失效收藏安装: %d 处", installed);
        if (installed == 0) {
            module.logd(Log.WARN, module.TAG, "✘ 自动清理失效收藏未安装：收藏列表页均未命中");
        }
    }


    private boolean hookFragment(ClassLoader cl, String className) {
        try {
            Class<?> fragment = Class.forName(className, false, cl);
            int installed = 0;
            Method setter = findListSetter(fragment);
            if (setter != null && hookListMethod(setter, fragment, false)) {
                installed++;
            }
            Method wrapper = findStaticListSetter(fragment);
            if (wrapper != null && hookListMethod(wrapper, fragment, true)) {
                installed++;
            }
            if (installed == 0) {
                Checkpoint.mark("自动清理失效收藏: %s 未找到列表装载方法", className);
                module.logd(Log.WARN, module.TAG, "✘ 未找到列表装载方法: " + className);
                return false;
            }
            Checkpoint.mark("自动清理失效收藏: %s 安装 %d 处", className, installed);
            return true;
        } catch (Throwable t) {
            Checkpoint.mark("自动清理失效收藏: %s 安装失败 %s", className, String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 自动清理失效收藏 Hook 失败: " + className, t);
            return false;
        }
    }

    private boolean hookListMethod(Method method, Class<?> fragment, boolean staticWrapper) {
        try {
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object target = staticWrapper ? chain.getArg(0) : chain.getThisObject();
                    Object list = staticWrapper ? chain.getArg(1) : chain.getArg(0);
                    onListLoaded(target, list, method.getDeclaringClass().getClassLoader());
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "失效收藏检查异常，放行: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 自动清理失效收藏 Hook 已安装: "
                    + fragment.getSimpleName() + "." + method.getName()
                    + (staticWrapper ? "(fragment,List)" : "(List)"));
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 列表装载 Hook 失败: "
                    + fragment.getSimpleName() + "." + method.getName(), t);
            return false;
        }
    }

    private Method findStaticListSetter(Class<?> fragment) {
        Method found = null;
        for (Method m : fragment.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2 || ps[0] != fragment || ps[1] != List.class
                    || m.getReturnType() != void.class
                    || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = m;
        }
        return found;
    }

    private Method findListSetter(Class<?> fragment) {
        Method found = null;
        for (Method m : fragment.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != List.class || m.getReturnType() != void.class
                    || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (found != null) {
                module.logd(Log.WARN, module.TAG, "列表装载方法不唯一，放弃该挂点: " + fragment.getName());
                return null;
            }
            found = m;
        }
        return found;
    }

    private void onListLoaded(Object fragment, Object listArg, ClassLoader cl) {
        if (fragment == null || !module.isEnabled(App.KEY_FAVOUR_AUTO_CLEAN, false)) {
            return;
        }
        int invalid = countInvalid(listArg);
        if (invalid <= 0) {
            return;
        }
        if (!allowTrigger(fragment)) {
            return;
        }
        module.logd(Log.INFO, module.TAG, "收藏列表含 " + invalid + " 条失效内容，自动清理");
        triggerClean(fragment, cl);
    }

    private int countInvalid(Object listArg) {
        if (!(listArg instanceof List)) {
            return 0;
        }
        int count = 0;
        for (Object item : (List<?>) listArg) {
            if ("1".equals(safeGet(item, "getIs_deleted"))) {
                count++;
            }
        }
        return count;
    }

    private boolean allowTrigger(Object fragment) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (lastTrigger) {
            Long last = lastTrigger.get(fragment);
            if (last != null && now - last < MIN_INTERVAL_MS) {
                return false;
            }
            lastTrigger.put(fragment, now);
        }
        return true;
    }


    private void triggerClean(final Object fragment, ClassLoader cl) {
        try {
            ConfirmSpec spec = resolveConfirmSpec(cl);
            if (spec == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未定位到宿主清理确认监听器，跳过自动清理");
                return;
            }
            Object disposable = newInstance(spec.disposableType);
            if (disposable == null) {
                module.logd(Log.WARN, module.TAG, "✘ 无法创建 CompositeDisposable，跳过自动清理");
                return;
            }
            String folderId = folderIdOf(fragment);
            final WeakReference<Object> ref = new WeakReference<>(fragment);
            Object isActive = newProxy(cl, spec.activeType, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return defaultValue(method.getReturnType());
                }
            });
            Object onFinish = newProxy(cl, spec.finishType, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if (method.getDeclaringClass() != Object.class) {
                        refreshLater(ref);
                    }
                    return defaultValue(method.getReturnType());
                }
            });
            if (isActive == null || onFinish == null) {
                module.logd(Log.WARN, module.TAG, "✘ 无法创建宿主回调代理，跳过自动清理");
                return;
            }
            Object listener = spec.ctor.newInstance(folderId, disposable, isActive, onFinish);
            Object dialog = Proxy.newProxyInstance(cl, new Class<?>[]{DialogInterface.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            return defaultValue(method.getReturnType());
                        }
                    });
            spec.onClick.invoke(listener, dialog, -1);
            module.logd(Log.INFO, module.TAG, "✔ 已触发宿主清理失效内容请求 (folder_id="
                    + (folderId == null ? "null" : folderId) + ")");
        } catch (Throwable t) {
            Checkpoint.mark("自动清理失效收藏触发失败: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 自动清理失效收藏触发失败: " + t);
        }
    }

    private void refreshLater(WeakReference<Object> ref) {
        main.post(() -> {
            Object fragment = ref.get();
            if (fragment == null) {
                return;
            }
            try {
                Object added = fragment.getClass().getMethod("isAdded").invoke(fragment);
                if (added instanceof Boolean && !((Boolean) added)) {
                    return;
                }
                fragment.getClass().getMethod("onRefresh").invoke(fragment);
                module.logd(Log.INFO, module.TAG, "自动清理完成，已刷新收藏列表");
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "清理后刷新收藏列表失败: " + t);
            }
        });
    }

    private String folderIdOf(Object fragment) {
        try {
            Object args = fragment.getClass().getMethod("getArguments").invoke(fragment);
            if (args instanceof Bundle) {
                return ((Bundle) args).getString(ARG_FOLDER_ID);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }


    private static final class ConfirmSpec {
        final Constructor<?> ctor;
        final Method onClick;
        final Class<?> disposableType;
        final Class<?> activeType;
        final Class<?> finishType;

        ConfirmSpec(Constructor<?> ctor, Method onClick, Class<?> disposableType,
                    Class<?> activeType, Class<?> finishType) {
            this.ctor = ctor;
            this.onClick = onClick;
            this.disposableType = disposableType;
            this.activeType = activeType;
            this.finishType = finishType;
        }
    }

    private ConfirmSpec resolveConfirmSpec(ClassLoader cl) {
        if (confirmResolved) {
            return confirmSpec;
        }
        synchronized (this) {
            if (confirmResolved) {
                return confirmSpec;
            }
            confirmSpec = findConfirmSpec(cl);
            confirmResolved = true;
            return confirmSpec;
        }
    }

    private ConfirmSpec findConfirmSpec(ClassLoader cl) {
        try {
            Class<?> companion = Class.forName(COMPANION_CLASS, false, cl);
            Class<?> onClickIface = DialogInterface.OnClickListener.class;
            for (Class<?> inner : companion.getDeclaredClasses()) {
                if (inner.isInterface() || !onClickIface.isAssignableFrom(inner)) {
                    continue;
                }
                Method onClick;
                try {
                    onClick = inner.getMethod("onClick", DialogInterface.class, int.class);
                } catch (Throwable t) {
                    continue;
                }
                for (Constructor<?> ctor : inner.getDeclaredConstructors()) {
                    Class<?>[] ps = ctor.getParameterTypes();
                    if (ps.length != 4 || ps[0] != String.class
                            || !ps[2].isInterface() || !ps[3].isInterface()) {
                        continue;
                    }
                    try {
                        ctor.setAccessible(true);
                    } catch (Throwable t) {
                        continue;
                    }
                    module.logd(Log.INFO, module.TAG, "已定位宿主清理确认监听器: " + inner.getName());
                    return new ConfirmSpec(ctor, onClick, ps[1], ps[2], ps[3]);
                }
            }
        } catch (Throwable t) {
            Checkpoint.mark("自动清理失效收藏: 确认监听器解析失败 %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "确认监听器解析失败: " + t);
        }
        return null;
    }


    private Object newInstance(Class<?> type) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private Object newProxy(ClassLoader cl, Class<?> iface, InvocationHandler handler) {
        try {
            return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, handler);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Boolean.TRUE;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == int.class || returnType == Integer.class) {
            return 0;
        }
        if (returnType == long.class || returnType == Long.class) {
            return 0L;
        }
        if (returnType == float.class || returnType == Float.class) {
            return 0f;
        }
        if (returnType == double.class || returnType == Double.class) {
            return 0d;
        }
        if (returnType == short.class || returnType == Short.class) {
            return (short) 0;
        }
        if (returnType == byte.class || returnType == Byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class || returnType == Character.class) {
            return (char) 0;
        }
        return null;
    }

    private String safeGet(Object item, String getter) {
        try {
            if (item == null) {
                return "";
            }
            Object v = item.getClass().getMethod(getter).invoke(item);
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
