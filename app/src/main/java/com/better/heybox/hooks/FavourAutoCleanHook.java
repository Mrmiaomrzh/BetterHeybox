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

/**
 * 自动清理失效收藏：收藏列表装载后若含失效条目（宿主 BBSLinkObj.is_deleted == "1"），
 * 自动发起宿主自带的「清理失效内容」请求——等价于用户点列表底部「点击清理」并在确认框点确认。
 *
 * <p>不重复造接口：直接复用宿主 {@code BBSKtUtils$Companion} 的确认回调，
 * 由宿主自己发 {@code bbs/app/profile/fav/folder/clean} 并处理返回，模块只负责「发现失效 + 触发」。
 * 页面类名未混淆，方法名逐版本变化（1.3.394 与 1.3.395 已不同），故一律按方法签名定位。</p>
 */
public final class FavourAutoCleanHook {

    /** 宿主收藏列表页：总列表（含 tab 页）与单收藏夹页 */
    private static final String[] FRAGMENT_CLASSES = {
            "com.max.xiaoheihe.module.favour.FavourCollectionContentFragment",
            "com.max.xiaoheihe.module.favour.FavourLinkFolderFragment",
    };

    private static final String COMPANION_CLASS =
            "com.max.xiaoheihe.module.bbs.utils.BBSKtUtils$Companion";

    /** 收藏夹 id：宿主两个页面都用这个 key 从 arguments 取 */
    private static final String ARG_FOLDER_ID = "folder_id";

    /** 同一页面实例两次自动清理的最小间隔：接口失败时不至于反复重试 */
    private static final long MIN_INTERVAL_MS = 30_000L;

    private final MainModule module;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** 页面实例 → 上次触发时间（WeakHashMap 防泄漏） */
    private final Map<Object, Long> lastTrigger = new WeakHashMap<>();

    /** 宿主确认监听器结构，惰性解析并缓存 */
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

    // ---------- 挂点：收藏列表页的数据装载 ----------

    private boolean hookFragment(ClassLoader cl, String className) {
        try {
            Class<?> fragment = Class.forName(className, false, cl);
            int installed = 0;
            // 主挂点：私有实例方法（数据装载后 is_deleted 已回写到 BBSLinkObj）
            Method setter = findListSetter(fragment);
            if (setter != null && hookListMethod(setter, fragment, false)) {
                installed++;
            }
            // 兜底挂点：public static synthetic 包装方法（同一份列表，重复触发由节流挡住）
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

    /** 兜底：静态合成包装方法 (页面, List) */
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

    /** 列表装载方法：唯一一个「单 java.util.List 参数、void、非静态」的方法 */
    private Method findListSetter(Class<?> fragment) {
        Method found = null;
        for (Method m : fragment.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != List.class || m.getReturnType() != void.class
                    || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (found != null) {
                // 出现多个候选说明结构已变：宁可不挂，也不挂错方法
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

    /** 失效判定沿用宿主逻辑：BBSLinkObj.is_deleted == "1"（渲染层同样按此灰化） */
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

    // ---------- 触发宿主的清理请求 ----------

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

    /** 清理成功后刷新列表，与宿主确认回调行为一致 */
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

    /** 收藏夹 id：宿主页面从 arguments 的 folder_id 取（总列表可能为 null，与宿主「点击清理」一致） */
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

    // ---------- 宿主确认监听器解析（结构匹配，不依赖混淆名） ----------

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

    /**
     * 宿主确认监听器形态（1.3.394 / 1.3.395 一致）：
     * 实现 DialogInterface.OnClickListener、构造器 (String, CompositeDisposable, Function0, Function0)。
     */
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

    // ---------- 通用工具 ----------

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

    /** 代理返回值：布尔真、基本类型零值、其余 null（Function0<Boolean> 必须返回非 null） */
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
