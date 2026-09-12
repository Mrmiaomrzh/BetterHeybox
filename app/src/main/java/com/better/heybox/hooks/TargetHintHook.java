package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import com.better.heybox.MainModule;

public final class TargetHintHook {

    private static final String TAG_OVERLAY = "betterheybox_target_hint";
    private static final String VISIBLE_TEXT = "免费模块 请勿在小黑盒宣传";

    private final MainModule module;

    private static volatile String sUidCache;

    private static volatile String sCapturedUid;

    private static final AtomicBoolean sResolving = new AtomicBoolean(false);

    public TargetHintHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        try {
            Class<?> activityCls = Class.forName("android.app.Activity", false, cl);
            module.hook(activityCls.getDeclaredMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity && !((Activity) self).isFinishing()) {
                        attach((Activity) self, cl);
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, MainModule.TAG, "提示信息挂载失败: " + t);
                }
                return result;
            });
            hookUidCapture(cl);
            module.logd(Log.INFO, MainModule.TAG, "✔ 提示信息 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 提示信息 Hook 安装失败", t);
        }
    }

    public static View createVisibleHint(Context context) {
        return new HintView(context, VISIBLE_TEXT, null);
    }

    private void attach(Activity activity, ClassLoader cl) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (decor.findViewWithTag(TAG_OVERLAY) != null) {
            return;
        }
        HintView view = new HintView(activity, null, sUidCache);
        view.setTag(TAG_OVERLAY);
        decor.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (sUidCache == null && sResolving.compareAndSet(false, true)) {
            final HintView target = view;
            new Thread(() -> {
                String uid = resolveUid(cl);
                if (uid != null) {
                    target.post(() -> {
                        target.setHiddenText(uid);
                        target.invalidate();
                    });
                }
            }, "bhb-uid-resolve").start();
        }
    }

    private String resolveUid(ClassLoader cl) {
        String cached = sUidCache;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> userCls = Class.forName("com.max.xiaoheihe.bean.account.User", false, cl);
            Class<?> detailCls = Class.forName(
                    "com.max.xiaoheihe.bean.account.AccountDetailObj", false, cl);
            Class<?> util = Class.forName("com.max.xiaoheihe.utils.u0", false, cl);
            for (Method m : util.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0
                        || !Modifier.isStatic(m.getModifiers())
                        || !userCls.equals(m.getReturnType())) {
                    continue;
                }
                try {
                    Object user = m.invoke(null);
                    if (user == null) {
                        continue;
                    }
                    Method login = userCls.getMethod("isLoginFlag");
                    if (!(Boolean) login.invoke(user)) {
                        continue;
                    }
                    Object detail = userCls.getMethod("getAccount_detail").invoke(user);
                    if (detail == null) {
                        continue;
                    }
                    Object uid = detailCls.getMethod("getUserid").invoke(detail);
                    if (uid instanceof String && !((String) uid).isEmpty()) {
                        sUidCache = (String) uid;
                        return sUidCache;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, MainModule.TAG, "反射读取部分数据失败: " + t);
        }
        return sCapturedUid;
    }

    private void hookUidCapture(ClassLoader cl) {
        try {
            Class<?> x0 = Class.forName("com.max.xiaoheihe.utils.x0", false, cl);
            Method extract = x0.getDeclaredMethod("U", String.class, String.class);
            module.hook(extract).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object key = chain.getArgs().get(1);
                    if ("heybox_id".equals(key) && result instanceof String
                            && !((String) result).isEmpty() && !"-1".equals(result)) {
                        sCapturedUid = (String) result;
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            module.logd(Log.WARN, MainModule.TAG, "提示信息被动 Hook 未安装: " + t);
        }
    }

    private static final class HintView extends View {

        private static final int VISIBLE_ALPHA = 36;
        private static final int HIDDEN_ALPHA = 3;
        private static final float ROTATION_DEG = -25f;

        private final Paint mVisiblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHiddenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String mVisibleText;
        private volatile String mHiddenText;
        private final float mDensity;

        HintView(Context context, String visibleText, String hiddenText) {
            super(context);
            mVisibleText = visibleText;
            mHiddenText = hiddenText;
            mDensity = context.getResources().getDisplayMetrics().density;
            float size = context.getResources().getDisplayMetrics().scaledDensity * 14;
            boolean night = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int base = night ? Color.WHITE : Color.BLACK;
            mVisiblePaint.setColor(base);
            mVisiblePaint.setAlpha(VISIBLE_ALPHA);
            mVisiblePaint.setTextSize(size);
            mHiddenPaint.setColor(base);
            mHiddenPaint.setAlpha(HIDDEN_ALPHA);
            mHiddenPaint.setTextSize(size);
        }

        void setHiddenText(String text) {
            mHiddenText = text;
        }

        private float dp(float v) {
            return v * mDensity;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            canvas.save();
            canvas.rotate(ROTATION_DEG, w / 2f, h / 2f);
            float half = (float) Math.hypot(w, h) / 2f;
            if (mVisibleText != null) {
                drawLayer(canvas, w / 2f, h / 2f, half, mVisibleText, mVisiblePaint, true);
            }
            String hidden = mHiddenText;
            if (hidden != null) {
                drawLayer(canvas, w / 2f, h / 2f, half, hidden, mHiddenPaint, false);
            }
            canvas.restore();
        }

        private void drawLayer(Canvas canvas, float cx, float cy, float half,
                               String text, Paint paint, boolean staggered) {
            float stepX = paint.measureText(text) + dp(80);
            float stepY = dp(90);
            int row = 0;
            for (float y = cy - half; y < cy + half; y += stepY, row++) {
                float offset = staggered && (row & 1) == 1 ? stepX / 2f : 0f;
                for (float x = cx - half + offset; x < cx + half; x += stepX) {
                    canvas.drawText(text, x, y, paint);
                }
            }
        }
    }
}
