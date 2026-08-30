package com.better.heybox.liquidglass;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.ThemeUtils;
import com.better.heybox.hooks.VideoDownloadHook;

public final class GlassSettingsSheet {

    private static final String SHEET_TAG = "betterheybox_glass_sheet";
    private static final int[] DARK_PRESETS = {
            0xFF000000, 0xFF1C1C1E, 0xFF2C2C2E, 0xFF10141A
    };
    private static final int[] LIGHT_PRESETS = {
            0xFFFFFFFF, 0xFFF7F5F0, 0xFFEDEDED, 0xFFE8EEF4
    };

    private GlassSettingsSheet() {
    }

    public static void show(Activity activity) {
        try {
            View existing = activity.getWindow().getDecorView().findViewWithTag(SHEET_TAG);
            if (existing != null) {
                return;
            }
            VideoDownloadHook.setGlassSettingsVisible(true);
            HeyboxPrefs.init(activity);
            GlassConfig.load(activity);

            float density = activity.getResources().getDisplayMetrics().density;
            int pageBg = ThemeUtils.surfaceVariantColor(activity);
            int cardBg = ThemeUtils.surfaceColor(activity);
            int textPrimary = ThemeUtils.textPrimaryColor(activity);
            int textSecondary = ThemeUtils.textSecondaryColor(activity);
            int accent = ThemeUtils.resolveAccent(activity);
            int divider = ThemeUtils.outlineColor(activity);

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setTag(SHEET_TAG);
            overlay.setClickable(true);
            overlay.setBackgroundColor(0x33000000);
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(150).start();

            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setColor(pageBg);
            float r = 24f * density;
            panelBg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
            panel.setBackground(panelBg);
            panel.setElevation(0f);
            panel.setPadding(0, (int) (14f * density), 0, (int) (12f * density));

            TextView title = new TextView(activity);
            title.setText("液态玻璃底栏");
            title.setTextColor(textPrimary);
            title.setTextSize(17f);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            panel.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (40f * density)));

            ScrollView scroller = new ScrollView(activity) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int screenH = activity.getResources().getDisplayMetrics().heightPixels;
                    int reserve = (int) Math.min(240f * density, screenH * 0.42f);
                    super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                            Math.max(screenH - reserve, (int) (200f * density)),
                            View.MeasureSpec.AT_MOST));
                }
            };
            scroller.setVerticalScrollBarEnabled(false);
            scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding((int) (14f * density), 0, (int) (14f * density),
                    (int) (6f * density));
            scroller.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            content.addView(sectionLabel(activity, "开关", textSecondary, density));
            content.addView(buildSwitchCard(activity, textPrimary, textSecondary,
                    accent, divider, cardBg, density));
            content.addView(sectionLabel(activity, "外观", textSecondary, density));
            content.addView(buildLookCard(activity, textPrimary, textSecondary,
                    accent, divider, cardBg, density));
            content.addView(sectionLabel(activity, "布局", textSecondary, density));
            content.addView(buildLayoutCard(activity, textPrimary, textSecondary,
                    accent, divider, cardBg, density));
            content.addView(buildResetCard(activity, cardBg, density));

            panel.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View grabber = new View(activity);
            GradientDrawable grabberBg = new GradientDrawable();
            grabberBg.setColor(textSecondary);
            grabberBg.setCornerRadius(2f * density);
            grabber.setBackground(grabberBg);
            LinearLayout.LayoutParams grabberLp = new LinearLayout.LayoutParams(
                    (int) (36f * density), (int) (4f * density));
            grabberLp.gravity = Gravity.CENTER_HORIZONTAL;
            grabberLp.topMargin = (int) (8f * density);
            panel.addView(grabber, grabberLp);

            FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP);
            overlay.addView(panel, panelLp);
            panel.setTranslationY(-400f * density);
            overlay.post(() -> panel.animate().translationY(0).setDuration(220).start());

            overlay.setOnClickListener(v -> dismiss(activity));
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass settings sheet failed", t);
        }
    }

    private static LinearLayout buildSwitchCard(Activity activity, int textPrimary, int textSecondary,
                                                int accent, int divider, int cardBg, float density) {
        LinearLayout switchCard = card(activity, cardBg, density);
        switchCard.addView(switchRow(activity, "启用液态玻璃", "关闭后需重启小黑盒生效",
                HeyboxPrefs.getBoolean(App.KEY_LIQUID_GLASS, true),
                textPrimary, textSecondary, accent, density,
                (buttonView, isChecked) -> {
                    HeyboxPrefs.setBoolean(App.KEY_LIQUID_GLASS, isChecked);
                    Toast.makeText(activity, "需重启小黑盒生效", Toast.LENGTH_SHORT).show();
                }));
        switchCard.addView(divider(activity, divider, density));
        switchCard.addView(switchRow(activity, "沉浸式小白条",
                "玻璃条贴合屏幕底部，内容延伸至手势区",
                GlassConfig.immersiveGestureNavigation, textPrimary, textSecondary, accent, density,
                (buttonView, isChecked) -> {
                    GlassConfig.immersiveGestureNavigation = isChecked;
                    GlassConfig.save(activity);
                    LiquidGlassInstaller.refreshGlass();
                }));
        switchCard.addView(divider(activity, divider, density));
        switchCard.addView(switchRow(activity, "自适应反色",
                "标签文字随背景亮度切换黑白",
                GlassConfig.adaptiveChrome, textPrimary, textSecondary, accent, density,
                (buttonView, isChecked) -> {
                    GlassConfig.adaptiveChrome = isChecked;
                    GlassConfig.save(activity);
                    LiquidGlassInstaller.refreshGlass();
                }));
        switchCard.addView(divider(activity, divider, density));
        switchCard.addView(switchRow(activity, "玻璃宽度自适应",
                "隐藏标签后底栏宽度随可见标签数收缩，选中项加长",
                GlassConfig.fitTabs, textPrimary, textSecondary, accent, density,
                (buttonView, isChecked) -> {
                    GlassConfig.fitTabs = isChecked;
                    GlassConfig.save(activity);
                    LiquidGlassInstaller.syncTabVisibility();
                }));
        return switchCard;
    }

    private static LinearLayout buildLookCard(Activity activity, int textPrimary, int textSecondary,
                                              int accent, int divider, int cardBg, float density) {
        LinearLayout lookCard = card(activity, cardBg, density);
        lookCard.addView(tintGroup(activity, "暗色模式底色", DARK_PRESETS, true,
                textPrimary, textSecondary, accent, density));
        lookCard.addView(divider(activity, divider, density));
        lookCard.addView(sliderRow(activity, "暗色模式不透明度",
                GlassConfig.darkAlphaPct + "%", textPrimary, textSecondary, accent,
                10, 95, GlassConfig.darkAlphaPct, density, value -> {
                    GlassConfig.darkAlphaPct = value;
                    GlassConfig.save(activity);
                    LiquidGlassInstaller.refreshGlass();
                    return value + "%";
                }));
        lookCard.addView(divider(activity, divider, density));
        lookCard.addView(tintGroup(activity, "亮色模式底色", LIGHT_PRESETS, false,
                textPrimary, textSecondary, accent, density));
        lookCard.addView(divider(activity, divider, density));
        lookCard.addView(sliderRow(activity, "亮色模式不透明度",
                GlassConfig.lightAlphaPct + "%", textPrimary, textSecondary, accent,
                10, 95, GlassConfig.lightAlphaPct, density, value -> {
                    GlassConfig.lightAlphaPct = value;
                    GlassConfig.save(activity);
                    LiquidGlassInstaller.refreshGlass();
                    return value + "%";
                }));
        return lookCard;
    }

    private static LinearLayout buildLayoutCard(Activity activity, int textPrimary, int textSecondary,
                                                int accent, int divider, int cardBg, float density) {
        LinearLayout layoutCard = card(activity, cardBg, density);
        layoutCard.addView(sliderRow(activity, "玻璃条高度",
                heightLabel(GlassConfig.barHeightDp), textPrimary, textSecondary, accent,
                0, 48, GlassConfig.barHeightDp == 0 ? 0 : GlassConfig.barHeightDp - 51,
                density, value -> {
                    GlassConfig.barHeightDp = value == 0 ? 0 : value + 51;
                    GlassConfig.save(activity);
                    LiquidGlassInstaller.applyBarGeometry();
                    return heightLabel(GlassConfig.barHeightDp);
                }));
        layoutCard.addView(divider(activity, divider, density));
        layoutCard.addView(sliderRow(activity, "距屏幕底部",
                GlassConfig.barOffsetDp + "dp", textPrimary, textSecondary, accent,
                0, 40, GlassConfig.barOffsetDp, density, value -> {
                    GlassConfig.barOffsetDp = value;
                    GlassConfig.save(activity);
                    LiquidGlassInstaller.applyBarGeometry();
                    return value + "dp";
                }));
        return layoutCard;
    }

    private static LinearLayout buildResetCard(Activity activity, int cardBg, float density) {
        LinearLayout resetCard = card(activity, cardBg, density);
        TextView reset = new TextView(activity);
        reset.setText("恢复默认");
        reset.setTextColor(0xFFE53935);
        reset.setTextSize(15f);
        reset.setGravity(Gravity.CENTER);
        reset.setOnClickListener(v -> {
            GlassConfig.resetDefaults();
            GlassConfig.save(activity);
            LiquidGlassInstaller.applyBarGeometry();
            LiquidGlassInstaller.refreshGlass();
            Toast.makeText(activity, "已恢复默认", Toast.LENGTH_SHORT).show();
            dismiss(activity);
            show(activity);
        });
        resetCard.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (44f * density)));
        return resetCard;
    }

    public static void dismiss(Activity activity) {
        VideoDownloadHook.setGlassSettingsVisible(false);
        try {
            View overlay = activity.getWindow().getDecorView().findViewWithTag(SHEET_TAG);
            if (overlay != null && overlay.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) overlay.getParent();
                overlay.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    try {
                        parent.removeView(overlay);
                    } catch (Throwable ignored) {
                    }
                }).start();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String heightLabel(int dp) {
        return dp == 0 ? "自动" : dp + "dp";
    }

    private static TextView sectionLabel(Activity activity, String text, int color,
                                         float density) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) (8f * density), (int) (14f * density), 0, (int) (2f * density));
        label.setLayoutParams(lp);
        return label;
    }

    private static LinearLayout card(Activity activity, int cardBg, float density) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(22f * density);
        card.setBackground(bg);
        card.setPadding((int) (16f * density), (int) (4f * density),
                (int) (16f * density), (int) (4f * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8f * density);
        card.setLayoutParams(lp);
        return card;
    }

    private static View divider(Activity activity, int color, float density) {
        View d = new View(activity);
        d.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (0.5f * density)));
        lp.setMargins((int) (16f * density), 0, (int) (16f * density), 0);
        d.setLayoutParams(lp);
        return d;
    }

    private static View switchRow(Activity activity, String titleText, String desc,
                                  boolean checked, int textPrimary, int textSecondary,
                                  int accent, float density,
                                  CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (12f * density), 0, (int) (12f * density));
        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(textPrimary);
        title.setTextSize(15f);
        textCol.addView(title);
        if (desc != null) {
            TextView descView = new TextView(activity);
            descView.setText(desc);
            descView.setTextColor(textSecondary);
            descView.setTextSize(12f);
            textCol.addView(descView);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(activity);
        sw.setChecked(checked);
        applyMiuiSwitchStyle(sw, accent, density);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void applyMiuiSwitchStyle(Switch sw, int accent, float density) {
        int trackOn = (accent & 0x00FFFFFF) | 0xCC000000;

        GradientDrawable onTrack = new GradientDrawable();
        onTrack.setShape(GradientDrawable.RECTANGLE);
        onTrack.setCornerRadius(14f * density);
        onTrack.setColor(trackOn);
        onTrack.setSize((int) (50f * density), (int) (28f * density));

        GradientDrawable offTrack = new GradientDrawable();
        offTrack.setShape(GradientDrawable.RECTANGLE);
        offTrack.setCornerRadius(14f * density);
        offTrack.setColor(0x1F000000);
        offTrack.setSize((int) (50f * density), (int) (28f * density));

        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, onTrack);
        track.addState(new int[]{}, offTrack);

        GradientDrawable onThumb = new GradientDrawable();
        onThumb.setShape(GradientDrawable.OVAL);
        onThumb.setColor(0xFFFFFFFF);
        onThumb.setSize((int) (20f * density), (int) (20f * density));

        GradientDrawable offThumb = new GradientDrawable();
        offThumb.setShape(GradientDrawable.OVAL);
        offThumb.setColor(0xFFFFFFFF);
        offThumb.setSize((int) (20f * density), (int) (20f * density));

        GradientDrawable shadow = new GradientDrawable();
        shadow.setShape(GradientDrawable.OVAL);
        shadow.setColor(0x30000000);
        shadow.setSize((int) (22f * density), (int) (22f * density));

        int pad = (int) (3f * density);
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked},
                new android.graphics.drawable.InsetDrawable(thumbLayer(
                        shadow, onThumb, density), pad, pad, pad, pad));
        thumb.addState(new int[]{},
                new android.graphics.drawable.InsetDrawable(thumbLayer(
                        shadow, offThumb, density), pad, pad, pad, pad));

        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        sw.setShowText(false);
    }

    private static LayerDrawable thumbLayer(GradientDrawable shadow,
                                            GradientDrawable circle, float density) {
        int gap = (int) (1f * density);
        return new LayerDrawable(new android.graphics.drawable.Drawable[]{
                shadow, new android.graphics.drawable.InsetDrawable(circle, gap, gap, gap, gap)});
    }

    private interface OnSlide {
        String onSlide(int value);
    }

    private static View sliderRow(Activity activity, String titleText, String valueText,
                                  int textPrimary, int textSecondary, int accent,
                                  int min, int max, int current, float density,
                                  OnSlide onSlide) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) (10f * density), 0, (int) (6f * density));
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(textPrimary);
        title.setTextSize(15f);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(activity);
        value.setText(valueText);
        value.setTextColor(textSecondary);
        value.setTextSize(13f);
        head.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        GlassSlider seek = new GlassSlider(activity, accent, density, min, max);
        seek.setProgress(Math.max(0, Math.min(max, current) - min));
        seek.setOnProgressChangedListener((slider, progress, fromUser) -> {
            if (fromUser) {
                value.setText(onSlide.onSlide(progress + min));
            }
        });
        row.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (48f * density)));
        return row;
    }

    private static final class GlassSlider extends View {
        private final android.graphics.Paint trackPaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint progressPaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint thumbOuterPaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint thumbInnerPaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int min;
        private final int max;
        private int progress;
        private float thumbScale = 1f;
        private boolean dragging;
        private OnProgressChangedListener listener;

        GlassSlider(android.content.Context context, int accent, float density,
                    int min, int max) {
            super(context);
            this.density = density;
            this.min = min;
            this.max = Math.max(min + 1, max);
            this.progress = min;
            boolean dark = ThemeUtils.isDarkMode(context);
            trackPaint.setColor(dark ? 0x3DFFFFFF : 0x24000000);
            progressPaint.setColor(accent);
            thumbOuterPaint.setColor(accent);
            thumbInnerPaint.setColor(ThemeUtils.surfaceColor(context));
            setFocusable(true);
            setClickable(true);
            setContentDescription("可调节值");
        }

        interface OnProgressChangedListener {
            void onProgressChanged(GlassSlider slider, int progress, boolean fromUser);
        }

        void setOnProgressChangedListener(OnProgressChangedListener listener) {
            this.listener = listener;
        }

        void setProgress(int value) {
            progress = Math.max(min, Math.min(max, value));
            invalidate();
        }

        int getProgress() {
            return progress;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float left = getPaddingLeft() + 10f * density;
            float right = getWidth() - getPaddingRight() - 10f * density;
            float centerY = getHeight() / 2f;
            float trackRadius = 2.5f * density;
            float outerRadius = 9f * density * thumbScale;
            float innerRadius = 5.5f * density * thumbScale;
            float fraction = (progress - min) / (float) (max - min);
            float thumbX = left + (right - left) * fraction;

            canvas.drawRoundRect(left, centerY - trackRadius, right,
                    centerY + trackRadius, trackRadius, trackRadius, trackPaint);
            float progressEnd = Math.max(left, thumbX);
            if (progressEnd > left) {
                canvas.drawRoundRect(left, centerY - trackRadius, progressEnd,
                        centerY + trackRadius, trackRadius, trackRadius, progressPaint);
            }
            canvas.drawCircle(thumbX, centerY, outerRadius, thumbOuterPaint);
            canvas.drawCircle(thumbX, centerY, innerRadius, thumbInnerPaint);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    dragging = true;
                    animateThumb(1.08f);
                    updateFromTouch(event.getX(), true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        updateFromTouch(event.getX(), true);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        updateFromTouch(event.getX(), true);
                    }
                    dragging = false;
                    animateThumb(1f);
                    performClick();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private void updateFromTouch(float x, boolean fromUser) {
            float left = getPaddingLeft() + 10f * density;
            float right = getWidth() - getPaddingRight() - 10f * density;
            float fraction = (x - left) / Math.max(1f, right - left);
            int next = min + Math.round(Math.max(0f, Math.min(1f, fraction)) * (max - min));
            if (next != progress) {
                progress = next;
                invalidate();
                if (listener != null) {
                    listener.onProgressChanged(this, progress, fromUser);
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    setStateDescription(String.valueOf(progress));
                }
            }
        }

        private void animateThumb(float target) {
            thumbScale = target;
            invalidate();
        }
    }
    private static View tintGroup(Activity activity, String titleText, int[] presets,
                                  boolean dark, int textPrimary, int textSecondary,
                                  int accent, float density) {
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, (int) (10f * density), 0, (int) (6f * density));
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(textPrimary);
        title.setTextSize(15f);
        group.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (10f * density), 0, (int) (2f * density));
        int current = dark ? GlassConfig.darkColor : GlassConfig.lightColor;
        for (int i = 0; i < presets.length; i++) {
            final int index = i;
            View swatch = new View(activity);
            boolean selected = sameColor(presets[i], current);
            swatch.setBackground(swatchDrawable(presets[i], selected, accent, density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (30f * density), (int) (30f * density));
            lp.rightMargin = (int) (16f * density);
            swatch.setOnClickListener(v -> {
                if (dark) {
                    GlassConfig.darkColor = presets[index];
                } else {
                    GlassConfig.lightColor = presets[index];
                }
                GlassConfig.save(activity);
                LiquidGlassInstaller.refreshGlass();
                markSelection(row, presets, dark, accent, density);
            });
            row.addView(swatch, lp);
        }
        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return group;
    }

    private static void markSelection(LinearLayout row, int[] presets,
                                      boolean dark, int accent, float density) {
        int now = dark ? GlassConfig.darkColor : GlassConfig.lightColor;
        for (int i = 0; i < row.getChildCount() && i < presets.length; i++) {
            row.getChildAt(i).setBackground(swatchDrawable(presets[i],
                    sameColor(presets[i], now), accent, density));
        }
    }

    private static boolean sameColor(int a, int b) {
        return (a & 0xFFFFFF) == (b & 0xFFFFFF);
    }

    private static GradientDrawable swatchDrawable(int color, boolean selected,
                                                   int accent, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (selected) {
            d.setStroke((int) (2.5f * density), accent);
        } else {
            d.setStroke((int) (1f * density), 0x33000000);
        }
        return d;
    }
}
