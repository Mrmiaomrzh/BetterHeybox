package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.ThemeUtils;
import com.better.heybox.util.ScreenshotCleanupHelper;
import com.better.heybox.util.ScreenshotExporter;
import com.better.heybox.util.ScreenshotIds;
import com.better.heybox.util.ScreenshotRenderer;
import com.better.heybox.util.ScreenshotSelectionManager;
import com.better.heybox.util.ScreenshotUnit;
import com.better.heybox.util.WebViewHider;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScreenshotCaptureHook {

    private static final String TAG = "BetterHeybox";
    private static final long POLL_INTERVAL_MS = 1200L;
    private static final int ACCENT = 0xFF3B82F6;

    private static volatile ScreenshotCaptureHook instance;

    private final MainModule module;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private WeakReference<Activity> barHost = new WeakReference<>(null);
    private View entryButton;
    private LinearLayout actionBar;
    private TextView countView;
    private TextView postChip;
    private boolean polling;
    private boolean idsLogged;

    private boolean capturing;

    public ScreenshotCaptureHook(MainModule module) {
        this.module = module;
    }

    public static ScreenshotCaptureHook getInstance() {
        return instance;
    }

    public void install(ClassLoader classLoader) {
        if (!module.isEnabled(App.KEY_SCREENSHOT_ENABLED, true)) {
            module.logd(Log.INFO, TAG, "截图功能已关闭，跳过安装");
            return;
        }
        instance = this;
        ScreenshotSelectionManager.get().setListener(new ScreenshotSelectionManager.Listener() {
            @Override
            public void onSelectionChanged(int count) {
                updateBarState();
            }

            @Override
            public void onModeChanged(boolean active) {
                onSelectionModeChanged(active);
            }
        });
        try {
            hookActivityLifecycle();
            module.logd(Log.INFO, TAG, "截图捕获已安装");
        } catch (Throwable throwable) {
            module.logd(Log.ERROR, TAG, "截图捕获安装失败: " + throwable, throwable);
        }
    }

    private void hookActivityLifecycle() throws Throwable {
        Method onResume = Activity.class.getDeclaredMethod("onResume");
        module.hook(onResume).intercept(chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            if (self instanceof Activity) {
                final Activity activity = (Activity) self;
                mainHandler.post(() -> refreshFor(activity));
            }
            return result;
        });
        Method onPause = Activity.class.getDeclaredMethod("onPause");
        module.hook(onPause).intercept(chain -> {
            Object self = chain.getThisObject();
            if (self instanceof Activity) {
                final Activity activity = (Activity) self;
                mainHandler.post(() -> onPaused(activity));
            }
            return chain.proceed();
        });

        Method dispatch = Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
        module.hook(dispatch).intercept(chain -> {
            Object self = chain.getThisObject();
            Object arg = chain.getArg(0);
            if (self instanceof Activity && arg instanceof MotionEvent) {
                try {
                    if (ScreenshotSelectionManager.get()
                            .onActivityTouch((Activity) self, (MotionEvent) arg)) {
                        return Boolean.TRUE;
                    }
                } catch (Throwable throwable) {
                    module.logd(Log.DEBUG, TAG, "截图触摸处理失败: " + throwable);
                }
            }
            return chain.proceed();
        });
    }

    private void onPaused(Activity activity) {
        if (!isTarget(activity)) {
            return;
        }
        if (resumedActivity.get() != activity) {
            return;
        }
        polling = false;
        mainHandler.removeCallbacks(pollRunnable);
        if (ScreenshotSelectionManager.get().isActive()) {
            ScreenshotSelectionManager.get().exit();
        }
        removeActionBar();
        removeEntryButton();
    }

    private void refreshFor(Activity activity) {
        if (!isTarget(activity)) {
            return;
        }
        resumedActivity = new WeakReference<>(activity);
        verifyIdsOnce(activity);
        polling = true;
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.post(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            Activity activity = resumedActivity.get();
            if (activity == null || activity.isFinishing()) {
                polling = false;
                return;
            }
            try {
                refreshEntry(activity);
            } catch (Throwable throwable) {
                module.logd(Log.DEBUG, TAG, "截图入口刷新失败: " + throwable);
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void refreshEntry(Activity activity) {
        if (capturing) {

            removeEntryButton();
            return;
        }
        if (ScreenshotSelectionManager.get().isActive()) {
            removeEntryButton();
            ensureActionBar();
            return;
        }
        if (hasTargets(activity)) {
            ensureEntryButton(activity);
        } else {
            removeEntryButton();
        }
    }

    private void ensureActionBar() {
        Activity activity = barHost.get();
        if (activity == null || activity.isFinishing()) {
            activity = resumedActivity.get();
        }
        if (activity == null || activity.isFinishing()) {
            return;
        }
        LinearLayout bar = actionBar;
        if (bar != null && bar.getParent() instanceof ViewGroup) {
            return;
        }
        module.logd(Log.INFO, TAG, "截图工具条已脱离父容器，重新挂载 activity="
                + activity.getClass().getSimpleName());
        barHost = new WeakReference<>(activity);
        showActionBar(activity);
    }

    private boolean hasTargets(Activity activity) {
        if (activity.getWindow() == null) {
            return false;
        }
        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            return false;
        }
        View body = ScreenshotSelectionManager.get().findPostBody(activity);
        boolean comment = matchesCommentBlock(decor, activity);
        boolean targets = body != null || comment;
        if (targets && ScreenshotSelectionManager.get().isDebug()) {
            android.util.Log.i("BetterHeybox", "截图诊断 入口判定 正文=" + describe(body)
                    + " 评论块=" + comment + " activity=" + activity.getClass().getSimpleName());
        }
        return targets;
    }

    private String describe(View view) {
        if (view == null) {
            return "null";
        }
        return view.getClass().getSimpleName() + "#" + ScreenshotIds.entryName(view)
                + "(w=" + view.getWidth() + ",h=" + view.getHeight() + ")";
    }

    private boolean matchesCommentBlock(View decor, Context context) {
        int id = ScreenshotIds.id(context, ScreenshotIds.COMMENT_BLOCK);
        if (id == 0) {
            return false;
        }
        View block = ScreenshotIds.findDescendantById(decor, id);
        return block != null && block.isShown() && block.getHeight() > 100;
    }

    private void ensureEntryButton(Activity activity) {
        ViewGroup content = contentOf(activity);
        if (content == null) {
            return;
        }
        if (entryButton != null && entryButton.getParent() == content) {
            return;
        }
        removeEntryButton();
        Context context = activity;
        boolean dark = ThemeUtils.isDarkMode(context);
        TextView button = new TextView(context);
        button.setText("截图");
        button.setTextSize(13f);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setCompoundDrawablesWithIntrinsicBounds(
                new CameraIconDrawable(0xFFFFFFFF, context.getResources()
                        .getDisplayMetrics().density), null, null, null);
        button.setCompoundDrawablePadding(ThemeUtils.dp(context, 5));
        int padH = ThemeUtils.dp(context, 12);
        int padV = ThemeUtils.dp(context, 8);
        button.setPadding(padH, padV, padH, padV);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(ThemeUtils.dp(context, 20));
        background.setColor(dark ? 0xE6333338 : 0xE61F2937);
        background.setStroke(ThemeUtils.dp(context, 1), 0x55FFFFFF);
        button.setBackground(background);
        button.setAlpha(0.92f);
        button.setElevation(ThemeUtils.dp(context, 4));
        button.setOnClickListener(view -> {
            module.logd(Log.INFO, TAG, "截图入口被点击");

            Activity current = resumedActivity.get();
            if (current == null || current.isFinishing()) {
                current = activity;
            }
            enterSelectionMode(current);
        });
        int margin = ThemeUtils.dp(context, 12);
        ViewGroup.LayoutParams params;
        if (content instanceof FrameLayout) {
            FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            frameParams.rightMargin = margin;
            frameParams.bottomMargin = ThemeUtils.dp(context, 140);
            params = frameParams;
        } else {
            params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        try {
            content.addView(button, params);
            entryButton = button;
            ScreenshotSelectionManager.get().ignore(button);
        } catch (Throwable throwable) {
            module.logd(Log.DEBUG, TAG, "截图入口按钮添加失败: " + throwable);
        }
    }

    private void removeEntryButton() {
        View button = entryButton;
        entryButton = null;
        if (button != null) {
            ScreenshotSelectionManager.get().unignore(button);
        }
        if (button != null && button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
        }
    }

    public void enterSelectionMode(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        boolean entered = ScreenshotSelectionManager.get().enter(activity);
        module.logd(Log.INFO, TAG, "进入多选模式 entered=" + entered + " activity="
                + (activity == null ? "null" : activity.getClass().getSimpleName()));
        if (!entered) {
            toast(activity, "当前页面没有可截图的内容");
            return;
        }
        barHost = new WeakReference<>(activity);
        ScreenshotSelectionManager.get().logBodySearch(activity);
    }

    private void onSelectionModeChanged(boolean active) {
        if (active) {
            Activity activity = barHost.get();
            if (activity == null || activity.isFinishing()) {
                activity = resumedActivity.get();
            }
            if (activity == null || activity.isFinishing()) {
                ScreenshotSelectionManager.get().exit();
                return;
            }
            barHost = new WeakReference<>(activity);
            removeEntryButton();
            showActionBar(activity);
        } else {
            removeActionBar();
            Activity activity = resumedActivity.get();
            if (activity != null && !activity.isFinishing()) {
                refreshEntry(activity);
            }
        }
    }

    private void showActionBar(Activity activity) {
        ViewGroup content = contentOf(activity);
        if (content == null) {
            return;
        }
        removeActionBar();
        Context context = activity;
        boolean dark = ThemeUtils.isDarkMode(context);
        int surface = dark ? 0xFF232327 : 0xFFFFFFFF;
        int textColor = dark ? 0xFFECECEC : 0xFF1F1F1F;

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ThemeUtils.dp(context, 12), ThemeUtils.dp(context, 8),
                ThemeUtils.dp(context, 12), ThemeUtils.dp(context, 8));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(ThemeUtils.dp(context, 14));
        background.setColor(surface);
        background.setStroke(ThemeUtils.dp(context, 1), dark ? 0x33FFFFFF : 0x14000000);
        bar.setBackground(background);
        bar.setElevation(ThemeUtils.dp(context, 8));

        countView = new TextView(context);
        countView.setTextColor(textColor);
        countView.setTextSize(13f);
        countView.setText("已选 0");
        bar.addView(countView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        postChip = newChip(context, "帖子", textColor);
        postChip.setOnClickListener(view -> {
            Activity host = barHost.get();
            if (host == null) {
                return;
            }
            View postBody = ScreenshotSelectionManager.get().findPostBody(host);
            if (postBody != null) {
                ScreenshotSelectionManager.get().togglePostBody(postBody);
            }
        });
        bar.addView(postChip);

        TextView selectAll = newChip(context, "全选", textColor);
        selectAll.setOnClickListener(view -> ScreenshotSelectionManager.get().selectAll());
        bar.addView(selectAll);

        TextView save = newChip(context, "保存", ACCENT);
        save.setOnClickListener(view -> requestCapture(false));
        bar.addView(save);

        TextView share = newChip(context, "分享", ACCENT);
        share.setOnClickListener(view -> requestCapture(true));
        bar.addView(share);

        TextView exit = newChip(context, "退出", textColor);
        exit.setOnClickListener(view -> ScreenshotSelectionManager.get().exit());
        bar.addView(exit);

        int margin = ThemeUtils.dp(context, 10);
        ViewGroup.LayoutParams params;
        if (content instanceof FrameLayout) {
            FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            frameParams.leftMargin = margin;
            frameParams.rightMargin = margin;
            frameParams.bottomMargin = margin;
            params = frameParams;
        } else {
            params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        try {
            content.addView(bar, params);
            actionBar = bar;
            ScreenshotSelectionManager.get().ignore(bar);
            module.logd(Log.INFO, TAG, "截图工具条已显示");
        } catch (Throwable throwable) {
            module.logd(Log.DEBUG, TAG, "截图工具条添加失败: " + throwable);
            return;
        }
        updateBarState();
    }

    private void updateBarState() {
        if (countView != null) {
            countView.setText("已选 " + ScreenshotSelectionManager.get().count());
        }
        if (postChip == null) {
            return;
        }
        Activity activity = barHost.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        View postBody = ScreenshotSelectionManager.get().findPostBody(activity);
        if (postBody == null) {
            postChip.setVisibility(View.GONE);
            return;
        }
        postChip.setVisibility(View.VISIBLE);
        applyChipBackground(postChip, ScreenshotSelectionManager.get().isSelected(postBody), activity);
    }

    private void removeActionBar() {
        LinearLayout bar = actionBar;
        actionBar = null;
        countView = null;
        postChip = null;
        if (bar != null) {
            ScreenshotSelectionManager.get().unignore(bar);
        }
        if (bar != null && bar.getParent() instanceof ViewGroup) {
            ((ViewGroup) bar.getParent()).removeView(bar);
        }
    }

    private TextView newChip(Context context, String label, int textColor) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(13f);
        chip.setTextColor(textColor);
        chip.setGravity(Gravity.CENTER);
        chip.setClickable(true);
        chip.setPadding(ThemeUtils.dp(context, 12), ThemeUtils.dp(context, 6),
                ThemeUtils.dp(context, 12), ThemeUtils.dp(context, 6));
        applyChipBackground(chip, false, context);
        return chip;
    }

    private void applyChipBackground(TextView chip, boolean selected, Context context) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(ThemeUtils.dp(context, 10));
        background.setColor(selected ? ACCENT : 0x00000000);
        background.setStroke(ThemeUtils.dp(context, 1), selected ? ACCENT : 0x22FFFFFF);
        chip.setBackground(background);
    }

    private void requestCapture(final boolean share) {
        final Activity activity = barHost.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final ScreenshotSelectionManager manager = ScreenshotSelectionManager.get();
        final List<ScreenshotSelectionManager.Selection> selections = manager.orderedValidSelections();
        final int wanted = manager.count();
        if (selections.isEmpty()) {
            toast(activity, "请先选择要截图的贴文或留言");
            return;
        }
        final List<View> units = new ArrayList<>();
        for (ScreenshotSelectionManager.Selection selection : selections) {
            units.add(selection.view);
        }
        manager.exit();

        final ScreenshotRenderer.FoldedText folded = ScreenshotRenderer.expandFoldedTexts(units);
        boolean hasWebView = false;
        for (View unit : units) {
            if (ScreenshotSelectionManager.isWebView(unit)) {
                hasWebView = true;
                break;
            }
        }
        if (folded.clicked || hasWebView) {

            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finishCapture(activity, units, wanted, share, folded);
                }
            }, folded.clicked ? 400L : 260L);
        } else {
            finishCapture(activity, units, wanted, share, folded);
        }
    }

    private void finishCapture(final Activity activity, final List<View> units, final int wanted,
            final boolean share, final ScreenshotRenderer.FoldedText folded) {
        capturing = true;
        removeEntryButton();
        captureUnitsAsync(activity, units, new ScreenshotRenderer.BitmapCallback() {
            @Override
            public void onCaptured(Bitmap image) {
                capturing = false;
                if (folded != null) {
                    folded.restore();
                }
                if (image == null) {
                    toast(activity, "没有可截图的内容");
                    return;
                }
                if (wanted > units.size()) {
                    toast(activity, "有 " + (wanted - units.size()) + " 项已划出屏幕，未包含在截图中");
                }
                saveAndShare(activity.getApplicationContext(), image, share);
            }
        });
    }

    private void captureUnitsAsync(Activity activity, List<View> units, ScreenshotRenderer.BitmapCallback callback) {
        captureUnitAt(activity, units, 0, ScreenshotRenderer.backgroundColor(activity),
                new ArrayList<Bitmap>(), callback);
    }

    private void captureUnitAt(final Activity activity, final List<View> units, final int index,
            final int background, final List<Bitmap> parts, final ScreenshotRenderer.BitmapCallback callback) {
        if (index >= units.size()) {
            Bitmap composed = null;
            if (!parts.isEmpty()) {
                if (parts.size() == 1) {

                    composed = parts.get(0);
                } else {
                    composed = ScreenshotRenderer.composeVertical(activity, parts, background);
                    for (Bitmap part : parts) {
                        if (part != null && !part.isRecycled()) {
                            part.recycle();
                        }
                    }
                }
            }
            callback.onCaptured(composed);
            return;
        }
        final View unit = units.get(index);
        final ScreenshotCleanupHelper cleanup = new ScreenshotCleanupHelper();

        final int[] originalBounds = new int[] {
                unit.getLeft(), unit.getTop(), unit.getRight(), unit.getBottom()
        };
        try {
            cleanup.hideInside(unit);
        } catch (Throwable throwable) {
            module.logd(Log.DEBUG, TAG, "隐藏无关内容失败: " + throwable);
        }
        if (!ScreenshotSelectionManager.isWebView(unit)) {

            try {
                ScreenshotRenderer.layoutForCapture(unit);
                int again = cleanup.hideInside(unit);
                if (again > 0 && ScreenshotSelectionManager.get().isDebug()) {
                    android.util.Log.i("BetterHeybox", "截图诊断 补隐藏=" + again);
                }
            } catch (Throwable throwable) {
                module.logd(Log.DEBUG, TAG, "补隐藏失败: " + throwable);
            }
        }
        final ScreenshotRenderer.BitmapCallback next = new ScreenshotRenderer.BitmapCallback() {
            @Override
            public void onCaptured(Bitmap part) {
                try {
                    WebViewHider.restore(unit);
                } catch (Throwable throwable) {
                    module.logd(Log.DEBUG, TAG, "还原网页隐藏失败: " + throwable);
                }
                try {
                    cleanup.restore();
                } catch (Throwable throwable) {
                    module.logd(Log.DEBUG, TAG, "还原隐藏内容失败: " + throwable);
                }
                if (part != null) {
                    if (ScreenshotSelectionManager.get().isDebug()) {
                        android.util.Log.i("BetterHeybox", "截图诊断 单元视图 h=" + unit.getHeight()
                                + " w=" + unit.getWidth() + " -> 位图 " + part.getWidth() + "x"
                                + part.getHeight());
                    }
                    parts.add(part);
                }
                captureUnitAt(activity, units, index + 1, background, parts, callback);
            }
        };
        if (ScreenshotSelectionManager.isWebView(unit)) {

            final boolean[] started = new boolean[1];
            Runnable start = new Runnable() {
                @Override
                public void run() {
                    if (!started[0]) {
                        started[0] = true;
                        ScreenshotRenderer.captureWebViewAsync(activity, unit, background, next);
                    }
                }
            };
            boolean injected = false;
            try {
                injected = WebViewHider.hide(unit, ScreenshotCleanupHelper.currentRules(), start);
            } catch (Throwable throwable) {
                module.logd(Log.DEBUG, TAG, "注入隐藏脚本失败: " + throwable);
            }
            if (!injected) {
                start.run();
            }
            return;
        }
        Bitmap part = null;
        try {
            part = ScreenshotRenderer.captureUnit(unit, background);
        } catch (Throwable throwable) {
            module.logd(Log.ERROR, TAG, "截图失败: " + throwable, throwable);
        } finally {

            try {
                unit.layout(originalBounds[0], originalBounds[1], originalBounds[2], originalBounds[3]);
                unit.requestLayout();
            } catch (Throwable ignored) {

            }
        }
        next.onCaptured(part);
    }

    private void saveAndShare(final Context context, final Bitmap image, final boolean share) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Uri saved = null;
                String failure = null;
                try {
                    saved = ScreenshotExporter.saveScreenshot(image, context);
                } catch (Throwable throwable) {
                    failure = String.valueOf(throwable);
                }
                final Uri uri = saved;
                final String error = failure;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri == null) {
                            module.logd(Log.ERROR, TAG, "保存截图失败: " + error);
                            toast(context, "保存截图失败");
                            return;
                        }
                        if (share) {
                            try {
                                ScreenshotExporter.shareScreenshot(context, uri);
                            } catch (Throwable throwable) {
                                module.logd(Log.ERROR, TAG, "分享截图失败: " + throwable, throwable);
                                toast(context, "分享失败");
                            }
                        } else {
                            toast(context, "已保存到相册 Pictures/Heybox");
                        }
                    }
                });
            }
        }, "heybox-screenshot-save").start();
    }

    public void captureCommentDirectly(final Activity activity, final View anyViewInComment) {
        if (activity == null || anyViewInComment == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ScreenshotUnit unit = ScreenshotUnit.resolve(anyViewInComment);
                if (unit == null) {
                    toast(activity, "没有识别到这条留言");
                    return;
                }
                if (ScreenshotSelectionManager.get().isActive()) {
                    ScreenshotSelectionManager.get().exit();
                }
                capturing = true;
                removeEntryButton();
                captureUnitsAsync(activity, Collections.singletonList(unit.view),
                        new ScreenshotRenderer.BitmapCallback() {
                            @Override
                            public void onCaptured(Bitmap image) {
                                capturing = false;
                                if (image == null) {
                                    toast(activity, "截图失败");
                                    return;
                                }
                                saveAndShare(activity.getApplicationContext(), image, false);
                            }
                        });
            }
        });
    }

    private ViewGroup contentOf(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        return content instanceof ViewGroup ? (ViewGroup) content : null;
    }

    private boolean isTarget(Activity activity) {
        try {
            return MainModule.TARGET_PKG.equals(activity.getPackageName());
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void toast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {

        }
    }

    private void verifyIdsOnce(Activity activity) {
        if (idsLogged) {
            return;
        }
        idsLogged = true;
        try {
            int found = 0;
            StringBuilder missing = new StringBuilder();
            for (String name : ScreenshotIds.VERIFY) {
                if (ScreenshotIds.id(activity, name) != 0) {
                    found++;
                } else {
                    missing.append(name).append(' ');
                }
            }
            module.logd(Log.INFO, TAG, "截图资源 id 校验: 命中 " + found + "/" + ScreenshotIds.VERIFY.length
                    + (missing.length() > 0 ? " 缺失=" + missing : ""));
        } catch (Throwable throwable) {
            module.logd(Log.DEBUG, TAG, "截图资源 id 校验异常: " + throwable);
        }
    }

    private static final class CameraIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        CameraIconDrawable(int color, float density) {
            this.density = density;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.4f, 1.5f * density));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float width = bounds.width();
            float height = bounds.height();
            float left = bounds.left + width * 0.06f;
            float right = bounds.right - width * 0.06f;
            float top = bounds.top + height * 0.26f;
            float bottom = bounds.bottom - height * 0.10f;
            float radius = height * 0.18f;
            canvas.drawRoundRect(new RectF(left, top, right, bottom), radius, radius, paint);

            canvas.drawLine(left + width * 0.30f, top, left + width * 0.30f,
                    bounds.top + height * 0.12f, paint);
            canvas.drawLine(right - width * 0.30f, top, right - width * 0.30f,
                    bounds.top + height * 0.12f, paint);

            canvas.drawCircle(bounds.centerX(), (top + bottom) / 2f, height * 0.20f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.max(1, (int) (16 * density));
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.max(1, (int) (16 * density));
        }
    }
}


