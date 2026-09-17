package com.better.heybox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import com.better.heybox.hooks.GameLibraryCleanHook;
import com.better.heybox.hooks.PromoteDetector;

public final class HeyboxTargets {

    private static final String TAG = MainModule.TAG;

    private static final String CACHE_PREFIX = "targets_";

    private static final String[] SEARCH_PACKAGES = new String[]{
            "com.max.xiaoheihe", "com.max.hbcommon", "com.max.data", "com.max.feature"};

    private static final int MAX_RELAXED = 4;

    /** Candidate list adapters. */
    private static final String[] BBS_LIST_ADAPTERS = new String[]{
            "com.max.xiaoheihe.module.bbs.adapter.t",
            "com.max.xiaoheihe.module.bbs.LinkRankingFragment$a",
            "com.max.xiaoheihe.module.bbs.UserBBSInfoFragment$p",
            "com.max.xiaoheihe.module.search.viewholderbinder.a",
            "com.max.xiaoheihe.module.search.page.b$a",
            "com.max.xiaoheihe.module.search.page.d$b",
            "com.max.xiaoheihe.module.news.adapter.d",
            "com.max.xiaoheihe.module.news.viewholderbinder.f$d",
    };

    private static volatile Class<?> sViewHolderClass;

    public interface Validator {
        boolean accept(Method method);
    }

    public interface MethodHook {
        void hook(Method method);
    }

    public static final class Target {
        public final String key;
        public final String[] classes;
        public final String[] methods;
        public final String[] classAnchors;
        public final String[] methodAnchors;
        public final int minParams;
        public final int maxParams;
        public final Validator validator;

        Target(String key, String[] classes, String[] methods, String[] classAnchors,
               String[] methodAnchors, int minParams, int maxParams, Validator validator) {
            this.key = key;
            this.classes = classes == null ? new String[0] : classes;
            this.methods = methods == null ? new String[0] : methods;
            this.classAnchors = classAnchors == null ? new String[0] : classAnchors;
            this.methodAnchors = methodAnchors == null ? new String[0] : methodAnchors;
            this.minParams = minParams;
            this.maxParams = maxParams;
            this.validator = validator;
        }
    }

    private static final ConcurrentHashMap<String, Target> TARGETS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<Method>> RESOLVED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> SOURCE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<MethodHook>> PENDING = new ConcurrentHashMap<>();
    private static final Set<String> HOOKED = ConcurrentHashMap.newKeySet();

    private static final List<Target> ORDER = new CopyOnWriteArrayList<>();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bhx-targets");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile ClassLoader sCl;
    private static volatile Context sContext;
    private static volatile Handler sMain;
    private static volatile long sVersionCode;
    private static volatile boolean sStarted;

    private HeyboxTargets() {
    }

    public static ClassLoader hostClassLoader() {
        return sCl;
    }

    public static synchronized void init(ClassLoader cl, Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        sCl = cl;
        sContext = context == null ? App.resolveAppContext() : context;
        try {
            sMain = new Handler(Looper.getMainLooper());
        } catch (Throwable ignored) {
            sMain = null;
        }
        Target[] all = definitions();
        for (Target target : all) {
            TARGETS.put(target.key, target);
            ORDER.add(target);
        }
        sVersionCode = versionCode(sContext);
        loadCache(sVersionCode);
        List<Target> missing = new ArrayList<>();
        for (Target target : all) {
            if (RESOLVED.containsKey(target.key)) {
                continue;
            }
            List<Method> hit = resolveCandidates(target);
            if (!hit.isEmpty()) {
                RESOLVED.put(target.key, hit);
                SOURCE.put(target.key, "候选");
            } else if (target.methodAnchors.length > 0 || target.classAnchors.length > 0) {
                missing.add(target);
            }
        }
        LOG("目标解析完成: " + summary());
        if (!missing.isEmpty()) {
            final List<Target> todo = missing;
            EXECUTOR.execute(() -> scanWithDexKit(todo));
        }
    }

    public static String sourceOf(String key) {
        String source = SOURCE.get(key);
        return source == null ? "\u672a\u89e3\u6790" : source;
    }

    public static List<Method> methods(String key) {
        List<Method> list = RESOLVED.get(key);
        return list == null ? Collections.<Method>emptyList() : list;
    }

    public static void install(String key, MethodHook hook) {
        PENDING.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(hook);
        for (Method method : methods(key)) {
            hookOnce(key, method, hook);
        }
    }

    /** Installs every target sharing the key prefix. */
    public static void installGroup(String keyPrefix, MethodHook hook) {
        boolean matched = false;
        for (Target target : ORDER) {
            if (target.key.equals(keyPrefix) || target.key.startsWith(keyPrefix + ".")) {
                install(target.key, hook);
                matched = true;
            }
        }
        if (!matched) {
            install(keyPrefix, hook);
        }
    }

    public static boolean invokeBoolean(String key, String arg) {
        if (arg == null) {
            return false;
        }
        for (Method method : methods(key)) {
            if (method.getParameterCount() != 1) {
                continue;
            }
            try {
                Object value = method.invoke(null, arg);
                if (value instanceof Boolean && (Boolean) value) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("宿主版本码: ").append(sVersionCode).append("\n");
        for (Target target : ORDER) {
            List<Method> list = RESOLVED.get(target.key);
            String source = SOURCE.get(target.key);
            sb.append("\n").append(target.key).append("  [")
                    .append(source == null ? "未解析" : source).append("]\n");
            if (list == null || list.isEmpty()) {
                sb.append("    —\n");
                continue;
            }
            for (Method method : list) {
                sb.append("    ").append(method.getDeclaringClass().getName())
                        .append("#").append(method.getName())
                        .append("/").append(method.getParameterCount()).append("\n");
            }
        }
        return sb.toString();
    }

    private static String summary() {
        StringBuilder sb = new StringBuilder();
        for (Target target : ORDER) {
            String source = SOURCE.get(target.key);
            sb.append(target.key).append("=").append(source == null ? "-" : source).append(" ");
        }
        return sb.toString().trim();
    }

    private static void flushPending() {
        for (Map.Entry<String, List<MethodHook>> entry : PENDING.entrySet()) {
            for (MethodHook hook : entry.getValue()) {
                for (Method method : methods(entry.getKey())) {
                    hookOnce(entry.getKey(), method, hook);
                }
            }
        }
    }

    private static void hookOnce(String key, Method method, MethodHook hook) {
        if (!HOOKED.add(key + "|" + signature(method))) {
            return;
        }
        try {
            hook.hook(method);
        } catch (Throwable t) {
            LOG("目标挂载失败 " + key + " -> " + signature(method) + ": " + t);
        }
    }

    private static List<Method> resolveCandidates(Target target) {
        ClassLoader cl = sCl;
        if (cl == null) {
            return Collections.emptyList();
        }
        for (String name : target.classes) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                List<Method> hit = match(cls, target, true);
                if (!hit.isEmpty()) {
                    return hit;
                }
            } catch (Throwable ignored) {
            }
        }
        return Collections.emptyList();
    }

    private static List<Method> match(Class<?> cls, Target target, boolean useNames) {
        List<Method> out = new ArrayList<>();
        Method[] declared;
        try {
            declared = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return out;
        }
        for (Method method : declared) {
            if (Modifier.isAbstract(method.getModifiers())) {
                continue;
            }
            if (useNames && target.methods.length > 0 && !contains(target.methods, method.getName())) {
                continue;
            }
            int count = method.getParameterCount();
            if (count < target.minParams || count > target.maxParams) {
                continue;
            }
            if (target.validator != null && !accept(target.validator, method)) {
                continue;
            }
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
            }
            out.add(method);
        }
        return out;
    }

    private static List<Method> matchWithFallback(Class<?> cls, Target target) {
        List<Method> strict = match(cls, target, true);
        if (!strict.isEmpty()) {
            return strict;
        }
        List<Method> relaxed = match(cls, target, false);
        if (relaxed.size() > MAX_RELAXED) {
            LOG("目标 " + target.key + " 在 " + cls.getName() + " 上宽松匹配到 "
                    + relaxed.size() + " 个方法，超出上限已放弃");
            return Collections.emptyList();
        }
        return relaxed;
    }

    private static boolean accept(Validator validator, Method method) {
        try {
            return validator.accept(method);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void scanWithDexKit(List<Target> missing) {
        long start = android.os.SystemClock.elapsedRealtime();
        String path = apkPath(sContext);
        if (path == null) {
            LOG("DexKit 跳过: 取不到小黑盒 APK 路径");
            return;
        }
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            LOG("DexKit native 加载失败: " + t);
            return;
        }
        DexKitBridge bridge = null;
        LinkedHashMap<String, List<Method>> found = new LinkedHashMap<>();
        try {
            bridge = DexKitBridge.create(path);
            if (bridge == null) {
                LOG("DexKit 初始化失败");
                return;
            }
            try {
                int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
                bridge.setThreadNum(threads);
            } catch (Throwable ignored) {
            }
            for (Target target : missing) {
                LinkedHashMap<String, Method> hit = new LinkedHashMap<>();
                collectByMethodAnchors(bridge, target, hit);
                collectByClassAnchors(bridge, target, hit);
                if (!hit.isEmpty()) {
                    found.put(target.key, new ArrayList<>(hit.values()));
                }
            }
        } catch (Throwable t) {
            LOG("DexKit 扫描异常: " + t);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        long cost = android.os.SystemClock.elapsedRealtime() - start;
        if (found.isEmpty()) {
            LOG("DexKit 未补到任何目标 (" + cost + "ms)");
            return;
        }
        for (Map.Entry<String, List<Method>> entry : found.entrySet()) {
            RESOLVED.put(entry.getKey(), entry.getValue());
            SOURCE.put(entry.getKey(), "DexKit");
        }
        writeCache(sVersionCode);
        LOG("DexKit 补挂 " + found.keySet() + " (" + cost + "ms)");
        Handler main = sMain;
        if (main != null) {
            main.post(HeyboxTargets::flushPending);
        } else {
            flushPending();
        }
    }

    private static void collectByMethodAnchors(DexKitBridge bridge, Target target,
                                               LinkedHashMap<String, Method> out) {
        if (target.methodAnchors.length == 0) {
            return;
        }
        try {
            MethodMatcher matcher = MethodMatcher.create()
                    .usingStrings(Arrays.asList(target.methodAnchors))
                    .paramCount(target.minParams, target.maxParams);
            FindMethod query = FindMethod.create().matcher(matcher).searchPackages(SEARCH_PACKAGES);
            for (MethodData data : bridge.findMethod(query)) {
                Method method = instance(data);
                if (method == null) {
                    continue;
                }
                if (target.methods.length > 0 && !contains(target.methods, method.getName())) {
                    continue;
                }
                if (target.validator != null && !accept(target.validator, method)) {
                    continue;
                }
                out.put(signature(method), method);
            }
        } catch (Throwable t) {
            LOG("DexKit 方法锚点查询失败 " + target.key + ": " + t);
        }
    }

    private static void collectByClassAnchors(DexKitBridge bridge, Target target,
                                              LinkedHashMap<String, Method> out) {
        if (target.classAnchors.length == 0) {
            return;
        }
        try {
            ClassMatcher matcher = ClassMatcher.create()
                    .usingStrings(Arrays.asList(target.classAnchors));
            FindClass query = FindClass.create().matcher(matcher).searchPackages(SEARCH_PACKAGES);
            for (ClassData data : bridge.findClass(query)) {
                Class<?> cls;
                try {
                    cls = data.getInstance(sCl);
                } catch (Throwable t) {
                    continue;
                }
                for (Method method : matchWithFallback(cls, target)) {
                    out.put(signature(method), method);
                }
            }
        } catch (Throwable t) {
            LOG("DexKit 类锚点查询失败 " + target.key + ": " + t);
        }
    }

    private static Method instance(MethodData data) {
        try {
            Method method = data.getMethodInstance(sCl);
            if (method == null || Modifier.isAbstract(method.getModifiers())) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void loadCache(long version) {
        if (version <= 0) {
            return;
        }
        String raw = HeyboxPrefs.getString(CACHE_PREFIX + version, "");
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String line : raw.split("\n")) {
            int eq = line.indexOf(61);
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            Target target = TARGETS.get(key);
            if (target == null) {
                continue;
            }
            Method method = decode(value, target);
            if (method == null) {
                continue;
            }
            List<Method> list = RESOLVED.get(key);
            if (list == null) {
                list = new ArrayList<>();
                RESOLVED.put(key, list);
                SOURCE.put(key, "缓存");
            }
            list.add(method);
        }
    }

    private static void writeCache(long version) {
        if (version <= 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Method>> entry : RESOLVED.entrySet()) {
            for (Method method : entry.getValue()) {
                sb.append(entry.getKey()).append("=").append(signature(method)).append("\n");
            }
        }
        HeyboxPrefs.setString(CACHE_PREFIX + version, sb.toString());
    }

    private static Method decode(String value, Target target) {
        if (value == null || sCl == null) {
            return null;
        }
        String[] parts = value.split("#");
        if (parts.length != 3) {
            return null;
        }
        try {
            Class<?> cls = Class.forName(parts[0], false, sCl);
            int count = Integer.parseInt(parts[2]);
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(parts[1]) || method.getParameterCount() != count) {
                    continue;
                }
                if (target.validator != null && !accept(target.validator, method)) {
                    continue;
                }
                method.setAccessible(true);
                return method;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + "#" + method.getParameterCount();
    }

    private static long versionCode(Context context) {
        if (context == null) {
            return 0L;
        }
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(MainModule.TARGET_PKG, 0);
            return android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String apkPath(Context context) {
        if (context == null) {
            return null;
        }
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(MainModule.TARGET_PKG, 0);
            return info == null ? null : info.sourceDir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean contains(String[] array, String value) {
        for (String item : array) {
            if (item.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasParam(Method method, String paramClassName) {
        for (Class<?> type : method.getParameterTypes()) {
            if (paramClassName.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStringPredicate(Method method) {
        return method.getParameterCount() == 1
                && method.getParameterTypes()[0] == String.class
                && method.getReturnType() == boolean.class;
    }

    private static boolean isInnerParam(Method method) {
        if (method.getParameterCount() != 1) {
            return false;
        }
        String owner = method.getDeclaringClass().getName();
        return method.getParameterTypes()[0].getName().startsWith(owner + "$");
    }

    private static boolean isUtilParam(Method method) {
        return method.getParameterCount() == 1
                && method.getParameterTypes()[0].getName().startsWith("com.max.xiaoheihe.utils.");
    }

    private static boolean isBooleanFlagIn(Method method) {
        return method.getParameterCount() == 1
                && method.getParameterTypes()[0] == boolean.class
                && method.getReturnType() != void.class;
    }

    private static boolean isFeedsBinder(Method method) {
        return method.getParameterCount() == 2
                && "com.max.xiaoheihe.bean.news.FeedsContentBaseObj"
                .equals(method.getParameterTypes()[1].getName());
    }

    /** List bind method signature. */
    private static boolean isBbsLinkBinder(Method method) {
        if (method.isBridge() || method.isSynthetic()) {
            return false;
        }
        if (method.getReturnType() != void.class || method.getParameterCount() != 2) {
            return false;
        }
        Class<?>[] types = method.getParameterTypes();
        if (!PromoteDetector.BBS_LINK_OBJ.equals(types[1].getName())) {
            return false;
        }
        return isViewHolderParam(types[0]);
    }

    /** First parameter must be a view holder. */
    private static boolean isViewHolderParam(Class<?> type) {
        Class<?> holder = sViewHolderClass;
        if (holder == null) {
            try {
                holder = Class.forName("androidx.recyclerview.widget.RecyclerView$ViewHolder",
                        false, sCl);
            } catch (Throwable ignored) {
            }
            if (holder != null) {
                sViewHolderClass = holder;
            }
        }
        if (holder != null && holder.isAssignableFrom(type)) {
            return true;
        }
        return type.getName().startsWith("com.max.hbcommon.base.adapter.s$");
    }

    private static boolean isRecommendBinder(Method method) {
        if (method.getReturnType() != void.class || method.getParameterCount() != 2) {
            return false;
        }
        Class<?>[] types = method.getParameterTypes();
        return types[1] == Object.class && isViewHolderParam(types[0]);
    }

    private static boolean isBigBrotherBinder(Method method) {
        if (method.getReturnType() != void.class || method.getParameterCount() != 2) {
            return false;
        }
        Class<?>[] types = method.getParameterTypes();
        return types[1] == int.class
                && isViewHolderParam(types[0])
                && !"androidx.recyclerview.widget.RecyclerView$ViewHolder"
                .equals(types[0].getName());
    }

    private static boolean isBBDelegateBinder(Method method) {
        if (method.getReturnType() != void.class || method.getParameterCount() != 3) {
            return false;
        }
        Class<?>[] types = method.getParameterTypes();
        if (types[2] == Object.class || !isViewHolderParam(types[0])) {
            return false;
        }
        for (Class<?> cls = types[1]; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            if ("com.max.hbcommon.base.adapter.s".equals(cls.getName())) {
                return true;
            }
        }
        return false;
    }

    /** List getter signature. */
    private static boolean isLinksGetter(Method method) {
        return !method.isBridge()
                && method.getParameterCount() == 0
                && java.util.List.class.isAssignableFrom(method.getReturnType());
    }

    private static Target[] definitions() {
        java.util.List<Target> list = new java.util.ArrayList<>();
        java.util.Collections.addAll(list, baseDefinitions());
        for (int i = 0; i < BBS_LIST_ADAPTERS.length; i++) {
            list.add(new Target(PromoteDetector.TARGET_BBS_LIST_BIND + "." + i,
                    new String[]{BBS_LIST_ADAPTERS[i]},
                    new String[0],
                    new String[0],
                    new String[0],
                    2, 2,
                    HeyboxTargets::isBbsLinkBinder));
        }
        return list.toArray(new Target[0]);
    }

    private static Target[] baseDefinitions() {
        return new Target[]{
                new Target(GameLibraryCleanHook.TARGET_GAME_REC_BIND,
                        new String[]{GameLibraryCleanHook.ADAPTER_CLASS},
                        new String[0],
                        GameLibraryCleanHook.CLASS_ANCHORS,
                        new String[0],
                        2, 2,
                        HeyboxTargets::isRecommendBinder),

                new Target(GameLibraryCleanHook.TARGET_GAME_REC_WRAPPER,
                        new String[]{GameLibraryCleanHook.WRAPPER_CLASS},
                        new String[0],
                        new String[0],
                        new String[0],
                        2, 2,
                        HeyboxTargets::isBigBrotherBinder),

                new Target(GameLibraryCleanHook.TARGET_GAME_REC_BB,
                        new String[]{GameLibraryCleanHook.BB_DELEGATE_CLASS},
                        new String[0],
                        GameLibraryCleanHook.BB_CLASS_ANCHORS,
                        new String[0],
                        3, 3,
                        HeyboxTargets::isBBDelegateBinder),

                new Target(PromoteDetector.TARGET_BBS_RENDER,
                        new String[]{
                                "com.max.xiaoheihe.module.bbs.utils.b",
                                "com.max.xiaoheihe.module.bbs.utils.BBSKtUtils"},
                        new String[]{"L", "N", "o"},
                        new String[0],
                        new String[]{"\u63a8\u5e7f"},
                        1, 8,
                        method -> method.getReturnType() == void.class
                                && hasParam(method, "com.max.xiaoheihe.bean.bbs.BBSLinkObj")),

                new Target(PromoteDetector.TARGET_BBS_PRED_PROMOTE,
                        new String[]{"com.max.xiaoheihe.module.bbs.utils.b"},
                        new String[]{"w"},
                        new String[0],
                        new String[]{"28", "29"},
                        1, 1,
                        HeyboxTargets::isStringPredicate),

                new Target(PromoteDetector.TARGET_BBS_PRED_AD,
                        new String[]{"com.max.xiaoheihe.module.bbs.utils.b"},
                        new String[]{"z"},
                        new String[0],
                        new String[]{"23"},
                        1, 1,
                        HeyboxTargets::isStringPredicate),

                new Target(PromoteDetector.TARGET_ADS_SPLASH,
                        new String[]{"com.max.xiaoheihe.module.ads.e"},
                        new String[]{"g"},
                        new String[]{"AdsImgDownLoad"},
                        new String[0],
                        1, 1,
                        HeyboxTargets::isBooleanFlagIn),

                new Target(PromoteDetector.TARGET_ADS_BUBBLE,
                        new String[]{"com.max.xiaoheihe.module.ads.h"},
                        new String[]{"s", "l"},
                        new String[]{"KEY_HOME_CORNER_AD_STATE"},
                        new String[0],
                        1, 1,
                        HeyboxTargets::isInnerParam),

                new Target(PromoteDetector.TARGET_ADS_CORNER,
                        new String[]{"com.max.xiaoheihe.module.ads.h"},
                        new String[]{"i", "h"},
                        new String[]{"KEY_HOME_CORNER_AD_STATE"},
                        new String[0],
                        1, 1,
                        HeyboxTargets::isUtilParam),

                new Target(PromoteDetector.TARGET_FEEDS_BIND,
                        new String[]{"com.max.xiaoheihe.module.news.adapter.a"},
                        new String[]{"y"},
                        new String[0],
                        new String[0],
                        2, 2,
                        HeyboxTargets::isFeedsBinder),

                new Target(PromoteDetector.TARGET_BBS_LINKS_GETTER,
                        new String[]{"com.max.xiaoheihe.bean.bbs.BBSLinkListResultObj"},
                        new String[]{"getLinks"},
                        new String[0],
                        new String[0],
                        0, 0,
                        HeyboxTargets::isLinksGetter),
        };
    }

    private static void LOG(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }
}
