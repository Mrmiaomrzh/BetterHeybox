package com.better.heybox.liquidglass;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

final class LiquidGlassHostLayout extends FrameLayout {

    static final Object GLASS_TAG = new Object();

    /** Light frost fallback for devices without RuntimeShader. */
    private static final float SAMPLE_SCALE_LEGACY = 0.4f;
    private static final int BLUR_RADIUS_LEGACY = 3;
    private static final float SATURATION_BOOST = 1.08f;

    private final ViewGroup mSampleRoot;
    private final float mDensity;
    private boolean mDarkMode;
    private int mCaptureCount;

    private final boolean mUseAgsl;
    interface GlassTuner {
        void onSize(int w, int h, float cornerRadius);
        void onTheme(boolean dark);
    }

    private GlassTuner mTuner;

    private final Paint mBackdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();

    private float mCornerRadius;
    private Bitmap mRegionBuf;
    private boolean mCapturing;

    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;

    LiquidGlassHostLayout(Context context, ViewGroup sampleRoot, ViewGroup bar) {
        super(context);
        mSampleRoot = sampleRoot;
        mDensity = context.getResources().getDisplayMetrics().density;
        mDarkMode = isSystemNight(context);
        mUseAgsl = Build.VERSION.SDK_INT >= 33;
        setTag(GLASS_TAG);
        setWillNotDraw(false);
        setupPaints();
        LiquidGlassLog.log(android.util.Log.INFO,
                "host created: sdk=" + Build.VERSION.SDK_INT
                        + " path=" + (mUseAgsl ? "agsl" : "legacy-frost")
                        + " dark=" + mDarkMode + " source=uiMode");
    }

    void setGlassTuner(GlassTuner tuner) {
        mTuner = tuner;
    }

    @SuppressWarnings("unused")
    private static boolean isSystemNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setupPaints() {
        if (!mUseAgsl) {
            if (mDarkMode) {
                mTintPaint.setColor(0x33000000);
                mBorderPaint.setColor(0x1FFFFFFF);
                mBackdropPaint.setColor(0x40000000);
            } else {
                mTintPaint.setColor(0x4DFFFFFF);
                mBorderPaint.setColor(0x2EFFFFFF);
                mBackdropPaint.setColor(0x8CFFFFFF);
            }
            mBorderPaint.setStyle(Paint.Style.STROKE);
            mBorderPaint.setStrokeWidth(Math.max(mDensity * 0.8f, 0.75f));
            updateGlossShader(getHeight());
        }
    }

    private void updateGlossShader(int h) {
        if (getWidth() <= 0 || h <= 0) {
            return;
        }
        mGlossPaint.setShader(new LinearGradient(
                0f, 0f, 0f, h * 0.45f,
                mDarkMode ? 0x1FFFFFFF : 0x40FFFFFF,
                0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    void attach() {
        detach();
        mPreDrawListener = () -> {
            if (!mCapturing && isAttachedToWindow()
                    && getVisibility() == VISIBLE
                    && getWidth() > 0 && getHeight() > 0) {
                capture();
            }
            return true;
        };
        mSampleRoot.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        invalidate();
        playRevealAnimation();
    }

    void detach() {
        if (mPreDrawListener != null) {
            mSampleRoot.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
            mPreDrawListener = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        detach();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0f, 0f, w, h);
        mCornerRadius = Math.min(h * 0.46f, 30f * mDensity);
        if (mTuner != null) {
            mTuner.onSize(w, h, mCornerRadius);
            return;
        }
        if (!mUseAgsl) {
            updateGlossShader(h);
        }
    }

    private float sampleScale() {
        return mUseAgsl ? 1.0f : SAMPLE_SCALE_LEGACY;
    }

    private void capture() {
        try {
            mCapturing = true;
            maybeRefreshTheme();
            if (mTuner != null) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || mSampleRoot.getWidth() <= 0) {
                return;
            }
            ensureRegionBuffer(w, h);

            Canvas c = new Canvas(mRegionBuf);
            float scale = sampleScale();
            int[] rootLoc = new int[2];
            int[] selfLoc = new int[2];
            mSampleRoot.getLocationOnScreen(rootLoc);
            getLocationOnScreen(selfLoc);
            float dx = selfLoc[0] - rootLoc[0];
            float dy = selfLoc[1] - rootLoc[1];

            c.save();
            c.clipRect(0f, 0f, w, h);
            c.scale(scale, scale);
            c.translate(-dx, -dy);
            int vis = getVisibility();
            setVisibility(INVISIBLE);
            try {
                mSampleRoot.draw(c);
            } finally {
                setVisibility(vis);
                c.restore();
            }

            applySaturationBoost(mRegionBuf);
            if (!mUseAgsl) {
                StackBlur.blur(mRegionBuf, BLUR_RADIUS_LEGACY);
            }
            invalidate();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("capture failed", t);
        } finally {
            mCapturing = false;
        }
    }

    private void ensureRegionBuffer(int w, int h) {
        float scale = sampleScale();
        int bw = Math.max(Math.round(w * scale), 1);
        int bh = Math.max(Math.round(h * scale), 1);
        if (mRegionBuf == null
                || mRegionBuf.isRecycled()
                || mRegionBuf.getWidth() != bw
                || mRegionBuf.getHeight() != bh) {
            Bitmap old = mRegionBuf;
            mRegionBuf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
        } else {
            mRegionBuf.eraseColor(android.graphics.Color.TRANSPARENT);
        }
    }

    private void maybeRefreshTheme() {
        mCaptureCount++;
        if (mCaptureCount % 20 != 1) {
            return;
        }
        boolean detected = isSystemNight(getContext());
        if (mCaptureCount == 1) {
            LiquidGlassLog.log(android.util.Log.INFO,
                    "theme probe first sample: dark=" + detected
                            + " current=" + mDarkMode);
        }
        if (detected != mDarkMode) {
            mDarkMode = detected;
            if (mTuner != null) {
                mTuner.onTheme(mDarkMode);
            }
            setupPaints();
            invalidate();
            LiquidGlassLog.log(android.util.Log.INFO,
                    "theme switched: dark=" + mDarkMode);
        }
    }

    private void applySaturationBoost(Bitmap bmp) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(SATURATION_BOOST);
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(bmp).drawBitmap(bmp, 0f, 0f, p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mTuner != null) {
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        drawLegacyFrost(canvas);
    }

    private void drawLegacyFrost(Canvas canvas) {
        float r = mCornerRadius;

        if (mRegionBuf != null && !mRegionBuf.isRecycled()) {
            BitmapShader shader = new BitmapShader(
                    mRegionBuf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(
                    getWidth() / (float) mRegionBuf.getWidth(),
                    getHeight() / (float) mRegionBuf.getHeight());
            shader.setLocalMatrix(m);
            mBackdropPaint.setShader(shader);
        } else {
            mBackdropPaint.setShader(null);
            mBackdropPaint.setColor(mDarkMode ? 0x50000000 : 0x8CFFFFFF);
        }
        canvas.drawRoundRect(mBounds, r, r, mBackdropPaint);

        canvas.drawRoundRect(mBounds, r, r, mTintPaint);
        canvas.drawRoundRect(mBounds, r, r, mGlossPaint);

        float half = mBorderPaint.getStrokeWidth() * 0.5f;
        RectF border = new RectF(half, half,
                getWidth() - half, getHeight() - half);
        canvas.drawRoundRect(border, r - half, r - half, mBorderPaint);
    }

    private void playRevealAnimation() {
        try {
            setPivotX(getWidth() * 0.5f);
            setPivotY(getHeight());
            setScaleY(0.86f);
            setAlpha(0f);
            animate().alpha(1f).scaleY(1f)
                    .setDuration(380L)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .start();
        } catch (Throwable ignored) {
        }
    }

    void popChild(View child) {
        if (child == null || !isAttachedToWindow()) {
            return;
        }
        try {
            child.setPivotX(child.getWidth() * 0.5f);
            child.setPivotY(child.getHeight() * 0.62f);
            AnimatorSet set = new AnimatorSet();
            ObjectAnimator up = ObjectAnimator.ofFloat(
                    child, View.SCALE_X, 1f, 1.16f);
            up.setDuration(90L);
            up.setInterpolator(new OvershootInterpolator(0.6f));
            ObjectAnimator downX = ObjectAnimator.ofFloat(
                    child, View.SCALE_X, 1.16f, 1f);
            ObjectAnimator downY = ObjectAnimator.ofFloat(
                    child, View.SCALE_Y, 1.16f, 1f);
            downX.setDuration(240L);
            downY.setDuration(240L);
            OvershootInterpolator back = new OvershootInterpolator(2.2f);
            downX.setInterpolator(back);
            downY.setInterpolator(back);
            AnimatorSet downSet = new AnimatorSet();
            downSet.playTogether(downX, downY);
            set.playSequentially(up, downSet);
            set.start();
        } catch (Throwable ignored) {
        }
    }
}
