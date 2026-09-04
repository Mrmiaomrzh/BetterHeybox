package com.better.heybox;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

/**
 * DexKit 字节码特征解析：宿主更新致混淆名变化后自动重新定位 HeyBoxDialog。
 */
public final class DexKitResolver {

    private static final String TAG = MainModule.TAG;
    private static final String DIALOG_ANCHOR_STRING = "HeyBoxDialog.RainbowPositiveButtonLine";
    private static final String CACHE_KEY_PREFIX = "dexkit_dialog_spec_";
    private static final String PROBE_TEXT_PREFIX = "BH_PROBE_";

    private DexKitResolver() {
    }

    public static final class HeyboxDialogSpec {
        final Constructor<?> builderCtor;
        final Method setTitle;
        final Method setCenterView;
        final Method setPositiveButton;
        final Method setNegativeButton;
        final Method buildMethod;

        HeyboxDialogSpec(Constructor<?> builderCtor, Method setTitle, Method setCenterView,
                         Method setPositiveButton, Method setNegativeButton, Method buildMethod) {
            this.builderCtor = builderCtor;
            this.setTitle = setTitle;
            this.setCenterView = setCenterView;
            this.setPositiveButton = setPositiveButton;
            this.setNegativeButton = setNegativeButton;
            this.buildMethod = buildMethod;
        }
        public Dialog buildAndShow(Context ctx, CharSequence title, View content,
                                   CharSequence posText, DialogInterface.OnClickListener posListener,
                                   CharSequence negText, DialogInterface.OnClickListener negListener) throws Exception {
            Object builder = builderCtor.newInstance(ctx);
            if (title != null) {
                setTitle.invoke(builder, title);
            }
            if (content != null) {
                setCenterView.invoke(builder, content);
            }
            if (posText != null) {
                setPositiveButton.invoke(builder, posText, posListener);
            }
            if (negText != null) {
                setNegativeButton.invoke(builder, negText, negListener);
            }
            Object dialog = buildMethod.invoke(builder);
            if (!(dialog instanceof Dialog)) {
                throw new IllegalStateException("builder 未返回 Dialog 实例");
            }
            Dialog d = (Dialog) dialog;
            if (!d.isShowing()) {
                d.show();
            }
            return d;
        }
    }

    public interface SpecCallback {
        void onReady(HeyboxDialogSpec spec);

        void onFailed(String reason);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void getHeyboxDialogSpec(MainModule module, Activity activity, SpecCallback cb) {
        ClassLoader cl = activity.getClassLoader();
        String cacheKey;
        try {
            cacheKey = CACHE_KEY_PREFIX + activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            cacheKey = CACHE_KEY_PREFIX + "0";
        }
        HeyboxDialogSpec cached = readCache(activity, cl, cacheKey);
        if (cached != null) {
            module.logd(Log.INFO, TAG, "✔ HeyBoxDialog 解析命中缓存");
            cb.onReady(cached);
            return;
        }
        final String key = cacheKey;
        EXECUTOR.execute(() -> {
            Analysis a;
            try {
                a = analyzeWithDexKit(module, cl, activity);
            } catch (Throwable t) {
                module.logd(Log.WARN, TAG, "DexKit 分析异常: " + t);
                a = null;
            }
            final Analysis analysis = a;
            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    cb.onFailed("activity 已销毁");
                    return;
                }
                if (analysis == null) {
                    cb.onFailed("DexKit 未定位到 HeyBoxDialog");
                    return;
                }
                try {
                    probeAndClassify(module, activity, analysis, key, cb);
                } catch (Throwable t) {
                    module.logd(Log.WARN, TAG, "HeyBoxDialog 探针异常: " + t);
                    cb.onFailed("探针异常: " + t);
                }
            });
        });
    }

    private static final class Analysis {
        Class<?> dialogClass;
        Class<?> builderClass;
        Constructor<?> builderCtor;
        List<Method> charSeqCands;
        List<Method> viewCands;
        List<Method> buttonCands;
        List<Method> buildCands;
    }

    private static Analysis analyzeWithDexKit(MainModule module, ClassLoader cl, Activity activity) {
        Class<?> dialogClass = findDialogClassByAnchor(module, cl, activity);
        if (dialogClass == null) {
            return null;
        }
        Class<?> builderClass = findBuilderClass(dialogClass);
        if (builderClass == null) {
            module.logd(Log.WARN, TAG, "HeyBoxDialog 已定位但未找到 Builder 形态的内部类: "
                    + dialogClass.getName());
            return null;
        }
        Analysis a = new Analysis();
        a.dialogClass = dialogClass;
        a.builderClass = builderClass;
        try {
            a.builderCtor = builderClass.getConstructor(Context.class);
        } catch (NoSuchMethodException e) {
            module.logd(Log.WARN, TAG, "Builder 缺少 (Context) 构造器: " + builderClass.getName());
            return null;
        }
        a.charSeqCands = new ArrayList<>();
        a.viewCands = new ArrayList<>();
        a.buttonCands = new ArrayList<>();
        a.buildCands = new ArrayList<>();
        for (Method m : builderClass.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            switch (classifyBuilderMethod(m, builderClass, dialogClass)) {
                case 1: a.charSeqCands.add(m); break;
                case 2: a.viewCands.add(m); break;
                case 3: a.buttonCands.add(m); break;
                case 4: a.buildCands.add(m); break;
                default: break;
            }
        }
        if (a.charSeqCands.isEmpty() || a.viewCands.isEmpty()
                || a.buttonCands.isEmpty() || a.buildCands.isEmpty()) {
            module.logd(Log.WARN, TAG, "Builder 方法族不完整: charSeq=" + a.charSeqCands.size()
                    + " view=" + a.viewCands.size() + " button=" + a.buttonCands.size()
                    + " build=" + a.buildCands.size());
            return null;
        }
        return a;
    }

    private static Class<?> findDialogClassByAnchor(MainModule module, ClassLoader cl, Activity activity) {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            module.logd(Log.ERROR, TAG, "DexKit native 库加载失败", t);
            return null;
        }
        DexKitBridge bridge = null;
        try {
            bridge = DexKitBridge.create(activity.getApplicationInfo().sourceDir);
            ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings(DIALOG_ANCHOR_STRING)));
            for (ClassData cd : classes) {
                try {
                    Class<?> c = cd.getInstance(cl);
                    if (Dialog.class.isAssignableFrom(c)) {
                        return c;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, TAG, "DexKit 扫描失败: " + t);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
 * Builder 流式方法分类：1=CharSequence、2=View、3=按钮、4=build、0=其它
 */
    private static int classifyBuilderMethod(Method m, Class<?> builderClass, Class<?> dialogClass) {
        Class<?>[] ps = m.getParameterTypes();
        if (m.getReturnType() == builderClass) {
            if (ps.length == 1 && ps[0] == CharSequence.class) {
                return 1;
            }
            if (ps.length == 1 && ps[0] == View.class) {
                return 2;
            }
            if (ps.length == 2 && ps[0] == CharSequence.class
                    && ps[1] == DialogInterface.OnClickListener.class) {
                return 3;
            }
        } else if (ps.length == 0 && m.getReturnType() == dialogClass) {
            return 4;
        }
        return 0;
    }

    private static Class<?> findBuilderClass(Class<?> dialogClass) {
        for (Class<?> inner : dialogClass.getDeclaredClasses()) {
            if (!Modifier.isStatic(inner.getModifiers()) || Modifier.isInterface(inner.getModifiers())) {
                continue;
            }
            try {
                inner.getConstructor(Context.class);
            } catch (NoSuchMethodException e) {
                continue;
            }
            int charSeq = 0, view = 0, button = 0, build = 0;
            for (Method m : inner.getDeclaredMethods()) {
                switch (classifyBuilderMethod(m, inner, dialogClass)) {
                    case 1: charSeq++; break;
                    case 2: view++; break;
                    case 3: button++; break;
                    case 4: build++; break;
                    default: break;
                }
            }
            if (charSeq >= 2 && view >= 2 && button >= 2 && build >= 1) {
                return inner;
            }
        }
        return null;
    }

    private static final class RoundResult {
        Method title;
        Method center;
        Method positive;
        Method negative;
        Method buildUsed;
        Method replacer;
    }

    private static void probeAndClassify(MainModule module, Activity activity, Analysis a,
                                         String key, SpecCallback cb) {
        probeRound(module, activity, a, 0, key, cb);
    }

    private static void probeRound(MainModule module, Activity activity, Analysis a,
                                   final int round, final String key, final SpecCallback cb) {
        if (round >= 3) {
            cb.onFailed("探针超过最大轮数仍未分类完成");
            return;
        }
        probeOnce(activity, a,
                new ArrayList<>(a.charSeqCands), new ArrayList<>(a.viewCands),
                new ArrayList<>(a.buttonCands), r -> {
                    module.logd(Log.INFO, TAG, "探针第" + round + "轮: title=" + name(r.title)
                            + " center=" + name(r.center) + " pos=" + name(r.positive)
                            + " neg=" + name(r.negative) + " replacer=" + name(r.replacer)
                            + " build=" + name(r.buildUsed));
                    if (r.replacer != null) {
                        a.viewCands.remove(r.replacer);
                        probeRound(module, activity, a, round + 1, key, cb);
                        return;
                    }
                    if (r.title == null || r.center == null || r.positive == null
                            || r.negative == null || r.buildUsed == null) {
                        cb.onFailed("探针未能分类 Builder 方法（"
                                + (r.title == null ? "title " : "")
                                + (r.center == null ? "center " : "")
                                + (r.positive == null ? "pos " : "")
                                + (r.negative == null ? "neg" : "") + "缺位）");
                        return;
                    }
                    HeyboxDialogSpec spec = new HeyboxDialogSpec(a.builderCtor,
                            r.title, r.center, r.positive, r.negative, r.buildUsed);
                    writeCache(activity, key, a, spec);
                    module.logd(Log.INFO, TAG, "✔ HeyBoxDialog 自动解析完成: dialog="
                            + a.dialogClass.getName() + " builder=" + a.builderClass.getName()
                            + " title=" + name(spec.setTitle) + " view=" + name(spec.setCenterView)
                            + " pos=" + name(spec.setPositiveButton) + " neg=" + name(spec.setNegativeButton)
                            + " build=" + name(spec.buildMethod));
                    cb.onReady(spec);
                });
    }

    private static String name(Method m) {
        return m == null ? "null" : m.getName();
    }
    private interface RoundCallback {
        void onRound(RoundResult r);
    }

    private static void probeOnce(Activity activity, Analysis a,
                                  List<Method> charSeq, List<Method> view, List<Method> button,
                                  final RoundCallback done) {
        final RoundResult r = new RoundResult();
        final Map<String, Method> textByMethod = new HashMap<>();
        final Map<String, Method> buttonByText = new HashMap<>();
        Dialog dialog = null;
        View decor = null;
        try {
            Object builder = a.builderCtor.newInstance(activity);

            for (int i = 0; i < charSeq.size(); i++) {
                String text = PROBE_TEXT_PREFIX + "C" + i;
                textByMethod.put(text, charSeq.get(i));
                charSeq.get(i).invoke(builder, text);
            }
            for (int i = 0; i < view.size(); i++) {
                String text = PROBE_TEXT_PREFIX + "V" + i;
                TextView marker = new TextView(activity);
                marker.setText(text);
                view.get(i).invoke(builder, marker);
            }
            for (int i = 0; i < button.size(); i++) {
                String text = PROBE_TEXT_PREFIX + "B" + i;
                buttonByText.put(text, button.get(i));
                button.get(i).invoke(builder, text, (DialogInterface.OnClickListener) (d, w) -> {
                });
            }
            for (Method c : a.buildCands) {
                try {
                    Object d = c.invoke(builder);
                    if (d instanceof Dialog) {
                        dialog = (Dialog) d;
                        r.buildUsed = c;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (dialog != null) {
                Window w = dialog.getWindow();
                if (w != null) {
                    WindowManager.LayoutParams wp = w.getAttributes();
                    wp.windowAnimations = 0;
                    wp.alpha = 0f;
                    wp.dimAmount = 0f;
                    w.setAttributes(wp);
                    decor = w.getDecorView();
                }
                if (!dialog.isShowing()) {
                    dialog.show();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "probeOnce 构建异常: " + t);
        }
        if (dialog == null || decor == null) {
            if (dialog != null) {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
            }
            done.onRound(r);
            return;
        }
        final Dialog probeDialog = dialog;
        final View capturedDecor = decor;
        capturedDecor.postDelayed(() -> {
            try {
                classify(capturedDecor, view, textByMethod, buttonByText, r);
            } catch (Throwable t) {
                Log.w(TAG, "classify 异常: " + t);
            }
            try {
                probeDialog.dismiss();
            } catch (Throwable ignored) {
            }
            done.onRound(r);
        }, 50);
    }
    private static void classify(View decor, List<Method> viewCands,
                                 Map<String, Method> textByMethod,
                                 Map<String, Method> buttonByText, RoundResult r) {
        List<TextView> all = new ArrayList<>();
        collectTextViews(decor, all);
        Map<String, TextView> rendered = new HashMap<>();
        for (TextView tv : all) {
            CharSequence cs = tv.getText();
            if (cs != null && textByMethod.containsKey(cs.toString())) {
                rendered.put(cs.toString(), tv);
            }
        }
        Method titleCandidate = null;
        TextView titleView = null;
        for (Map.Entry<String, TextView> e : rendered.entrySet()) {
            if (titleView == null || screenY(e.getValue()) < screenY(titleView)) {
                titleView = e.getValue();
                titleCandidate = textByMethod.get(e.getKey());
            }
        }
        if (titleView != null) {
            r.title = titleCandidate;
        }
        for (TextView tv : all) {
            CharSequence cs = tv.getText();
            if (cs == null || !cs.toString().startsWith(PROBE_TEXT_PREFIX + "V")) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(cs.toString().substring((PROBE_TEXT_PREFIX + "V").length()));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (idx < 0 || idx >= viewCands.size()) {
                continue;
            }
            Method cand = viewCands.get(idx);
            if (titleView == null) {
                r.replacer = cand;
            } else if (screenY(tv) > screenY(titleView)) {
                r.center = cand;
            }
        }
        TextView posView = null, negView = null;
        Method posCandidate = null, negCandidate = null;
        for (TextView tv : all) {
            CharSequence cs = tv.getText();
            if (cs == null) {
                continue;
            }
            Method cand = buttonByText.get(cs.toString());
            if (cand == null) {
                continue;
            }
            if (posView == null || screenX(tv) < screenX(posView)) {
                if (posView != null) {
                    negView = posView;
                    negCandidate = posCandidate;
                }
                posView = tv;
                posCandidate = cand;
            } else if (negView == null || screenX(tv) < screenX(negView)) {
                negView = tv;
                negCandidate = cand;
            }
        }
        if (posView != null && negView != null) {
            r.positive = posCandidate;
            r.negative = negCandidate;
        }
    }

    private static int screenY(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[1];
    }

    private static int screenX(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[0];
    }

    private static void collectTextViews(View root, List<TextView> out) {
        if (root instanceof TextView) {
            out.add((TextView) root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectTextViews(vg.getChildAt(i), out);
            }
        }
    }

    private static void writeCache(Context ctx, String key, Analysis a, HeyboxDialogSpec s) {
        try {
            String v = a.dialogClass.getName() + ";" + a.builderClass.getName() + ";"
                    + s.setTitle.getName() + ";" + s.setCenterView.getName() + ";"
                    + s.setPositiveButton.getName() + ";" + s.setNegativeButton.getName() + ";"
                    + s.buildMethod.getName();
            Properties entries = readCacheEntries(ctx);
            entries.setProperty(key, v);
            try (FileOutputStream fos = new FileOutputStream(cacheFile(ctx))) {
                entries.store(fos, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "HeyBoxDialog 缓存写入失败: " + t);
        }
    }

    private static File cacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), "bh_dexkit_dialog_cache.txt");
    }

    private static Properties readCacheEntries(Context ctx) {
        Properties entries = new Properties();
        try {
            File f = cacheFile(ctx);
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    entries.load(fis);
                }
            }
        } catch (Throwable ignored) {
        }
        return entries;
    }

    private static HeyboxDialogSpec readCache(Context ctx, ClassLoader cl, String key) {
        try {
            String v = readCacheEntries(ctx).getProperty(key);
            if (v == null || v.isEmpty()) {
                return null;
            }
            String[] p = v.split(";");
            if (p.length != 7) {
                return null;
            }
            Class<?> dialogClass = Class.forName(p[0], false, cl);
            Class<?> builderClass = Class.forName(p[1], false, cl);
            Constructor<?> ctor = builderClass.getConstructor(Context.class);
            Method title = builderClass.getDeclaredMethod(p[2], CharSequence.class);
            Method view = builderClass.getDeclaredMethod(p[3], View.class);
            Method pos = builderClass.getDeclaredMethod(p[4],
                    CharSequence.class, DialogInterface.OnClickListener.class);
            Method neg = builderClass.getDeclaredMethod(p[5],
                    CharSequence.class, DialogInterface.OnClickListener.class);
            Method build = builderClass.getDeclaredMethod(p[6]);
            if (title.getReturnType() != builderClass || view.getReturnType() != builderClass
                    || pos.getReturnType() != builderClass || neg.getReturnType() != builderClass
                    || !dialogClass.isAssignableFrom(build.getReturnType())) {
                return null;
            }
            return new HeyboxDialogSpec(ctor, title, view, pos, neg, build);
        } catch (Throwable t) {
            return null;
        }
    }
}
