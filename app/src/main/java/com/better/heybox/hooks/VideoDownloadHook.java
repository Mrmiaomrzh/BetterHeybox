package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.ThemeUtils;
import com.better.heybox.VideoDownloadManager;
import com.better.heybox.ViewUtils;

/**
 * 视频下载入口：捕获 AbsVideoView URL 设置（setVideoRes/W），在窗口 Decor 挂圆形下载按钮，点击弹底部抽屉。
 * 按钮按 Decor 去重，跟随「最近捕获且当前可见」的视频（Candidate 竞选）；只做捕获+UI，下载全委托 VideoDownloadManager
 */
public final class VideoDownloadHook {

    /** 液态玻璃调节面板打开时暂停窗口级 FAB，避免它覆盖面板内容。 */
    private static volatile boolean glassSettingsVisible;

    public static void setGlassSettingsVisible(boolean visible) {
        glassSettingsVisible = visible;
        synchronized (EntryController.CONTROLLERS) {
            for (EntryController controller : EntryController.CONTROLLERS.values()) {
                if (controller != null) {
                    controller.sync();
                }
            }
        }
    }

    private final MainModule module;

    public VideoDownloadHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookW(cl);
    }

    private void hookW(ClassLoader cl) {
        Throwable firstError = null;
        // 三个入口（setVideoRes(String)/setVideoRes(String,Map)/W(String,Map)）都只在「设置 URL」处捕获，不碰播放；按 getArgs() 实际个数取参（getArg(1) 在单参重载曾越界丢捕获）
        String[] methods = {
                "setVideoRes",
                "setVideoRes",
                "W"
        };
        Class<?>[][] signatures = new Class<?>[][]{
                new Class<?>[]{String.class},
                new Class<?>[]{String.class, java.util.Map.class},
                new Class<?>[]{String.class, java.util.Map.class}
        };
        for (int i = 0; i < methods.length; i++) {
            try {
                Class<?> absVideoView = Class.forName("com.max.video.AbsVideoView", false, cl);
                java.lang.reflect.Method method = absVideoView.getDeclaredMethod(methods[i], signatures[i]);
                final String label = methods[i] + (signatures[i].length == 2 ? "(String, Map)" : "(String)");
                module.hook(method).intercept(chain -> {
                    // 后置：等宿主设置完 URL 再捕获，不影响播放流程
                    Object result = chain.proceed();
                    try {
                        List<?> args = chain.getArgs();
                        Object thisObj = chain.getThisObject();
                        String url = !args.isEmpty() && args.get(0) instanceof String
                                ? (String) args.get(0) : null;
                        Map<String, String> headers = args.size() > 1 && args.get(1) instanceof Map
                                ? (Map<String, String>) args.get(1) : null;
                        if (url != null && !url.isEmpty()) {
                            module.logd(Log.INFO, MainModule.TAG,
                                    "捕获视频URL[" + label + "]: " + shorten(url));
                            onVideoUrl(thisObj, url, headers);
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, MainModule.TAG, "视频 URL 捕获处理异常: " + t);
                    }
                    return result;
                });
                module.logd(Log.INFO, MainModule.TAG, "✔ 视频下载入口 Hook 已安装: AbsVideoView." + label);
            } catch (Throwable t) {
                if (firstError == null) {
                    firstError = t;
                }
                module.logd(Log.WARN, MainModule.TAG, "✘ 视频 URL 入口 Hook 失败: " + methods[i], t);
            }
        }
        if (firstError != null) {
            module.logd(Log.WARN, MainModule.TAG, "部分视频 URL 入口未安装（见上）");
        }
    }

    /** 日志用 URL 精简（避免超长 token 刷屏） */
    private static String shorten(String url) {
        if (url.length() <= 120) {
            return url;
        }
        return url.substring(0, 117) + "...";
    }

    /** 专职视频播放页白名单：仅这些宿主页面挂下载入口（首页信息流/我的/游戏页等一律不挂） */
    private static final java.util.Set<String> VIDEO_PAGE_HOSTS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.max.xiaoheihe.module.video.VideoActivity",
                    "com.max.xiaoheihe.module.story.StoryActivity"));

    private static boolean isVideoPageHost(View videoView) {
        Activity activity = ViewUtils.findActivity(videoView);
        return activity != null && VIDEO_PAGE_HOSTS.contains(activity.getClass().getName());
    }

    private void onVideoUrl(Object viewObject, String url, Map<String, String> headers) {
        boolean enabled;
        try {
            enabled = module.isEnabled(App.KEY_VIDEO_DOWNLOAD, true);
        } catch (Throwable t) {
            enabled = true;
        }
        if (!(viewObject instanceof View)) {
            return;
        }
        View videoView = (View) viewObject;
        // 非专职播放页（信息流/我的等）不初始化下载管理器、不注册候选
        if (!isVideoPageHost(videoView)) {
            return;
        }
        // 惰性初始化下载管理器（宿主进程内拿到 Context 后注册通知广播/恢复任务历史）
        VideoDownloadManager.get().init(videoView.getContext());
        ViewGroup decor = ViewUtils.findDecor(videoView);
        if (decor == null) {
            return;
        }
        EntryController controller = EntryController.get(decor);
        if (!enabled || !VideoDownloadManager.isSupportedUrl(url)) {
            // 开关关闭或不可下载：该视频不再参与竞选（其余可见视频的入口不受影响）
            if (controller != null) {
                controller.removeCandidate(videoView);
            }
            return;
        }
        if (controller == null) {
            controller = new EntryController(module, decor);
            EntryController.put(decor, controller);
        }
        controller.addCandidate(videoView, url, headers);
        controller.sync();
    }
    /**
 * 窗口级入口控制器：每 Decor 一个下载按钮，跟随「最近捕获且当前可见」的候选（预加载不可见时不抢占）
 */
    private static final class EntryController {

        private static final Map<ViewGroup, EntryController> CONTROLLERS =
                new java.util.WeakHashMap<>();

        private static final class Candidate {
            final WeakReference<View> video;
            final String url;
            final Map<String, String> headers;

            Candidate(View video, String url, Map<String, String> headers) {
                this.video = new WeakReference<>(video);
                this.url = url;
                this.headers = headers;
            }
        }

        private final ViewGroup decor;
        private final MainModule module;
        /** 候选列表，按捕获顺序（末尾最新） */
        private final List<Candidate> candidates = new ArrayList<>();

        private final ViewTreeObserver.OnScrollChangedListener decorScrollListener =
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        sync();
                    }
                };
        private final ViewTreeObserver.OnDrawListener decorDrawListener =
                new ViewTreeObserver.OnDrawListener() {
                    @Override
                    public void onDraw() {
                        sync();
                    }
                };
        private boolean listenersAttached;

        private DownloadFab entry;
        private DownloadSheet sheet;
        /** 当前参与竞选的候选（最新可见者），sync 中更新 */
        private Candidate active;

        EntryController(MainModule module, ViewGroup decor) {
            this.module = module;
            this.decor = decor;
        }

        static EntryController get(ViewGroup decor) {
            synchronized (CONTROLLERS) {
                return CONTROLLERS.get(decor);
            }
        }

        static void put(ViewGroup decor, EntryController controller) {
            synchronized (CONTROLLERS) {
                CONTROLLERS.put(decor, controller);
            }
        }

        /** 登记候选：同一 View 重复捕获（RecyclerView 复用/重新绑定）时更新其候选 */
        void addCandidate(View video, String url, Map<String, String> headers) {
            removeCandidateInternal(video);
            candidates.add(new Candidate(video, url, headers));
            module.logd(Log.INFO, MainModule.TAG, "入口候选登记: " + shorten(url)
                    + " (共" + candidates.size() + "个候选)");
            // 布局帧兜底：万一 decor 无重绘帧，800ms 后强制同步一次
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sync();
                }
            }, 800);
        }

        /** 移除某视频的候选 */
        void removeCandidate(View video) {
            if (removeCandidateInternal(video)) {
                sync();
            }
        }

        private boolean removeCandidateInternal(View video) {
            boolean removed = false;
            for (int i = candidates.size() - 1; i >= 0; i--) {
                View v = candidates.get(i).video.get();
                if (v == null || v == video) {
                    candidates.remove(i);
                    removed = true;
                }
            }
            return removed;
        }

        /**
 * 竞选 + 位置同步（每帧调用，轻量）：选最新可见候选并定位按钮，无候选则隐藏
 */
        void sync() {
            try {
                syncInternal();
            } catch (Throwable t) {
                module.logd(Log.WARN, MainModule.TAG, "入口同步异常: " + t);
            }
        }

        private void syncInternal() {
            ensureEntry();
            if (glassSettingsVisible) {
                active = null;
                if (entry != null && entry.getVisibility() != View.GONE) {
                    entry.setVisibility(View.GONE);
                }
                return;
            }
            Candidate chosen = null;
            View chosenAnchor = null;
            int decorH = decor.getHeight();
            for (int i = candidates.size() - 1; i >= 0; i--) {
                Candidate c = candidates.get(i);
                View v = c.video.get();
                if (v == null) {
                    candidates.remove(i); // 弱引用已死，顺手清理
                    continue;
                }
                // 信息流自动播放的 AbsVideoView 宽高可为 0，位置/可见性判定用「向上第一个有尺寸的祖先」作锚点
                View anchor = anchorOf(v);
                if (chosen == null && anchor.isShown() && anchor.getWidth() > 0
                        && anchor.getWindowToken() != null
                        && intersectsDecor(anchor, decorH)
                        && !coveredBySiblingLayer(anchor, decor)) {
                    chosen = c;
                    chosenAnchor = anchor;
                }
            }
            active = chosen;
            if (entry == null) {
                return;
            }
            if (chosen == null) {
                if (entry.getVisibility() != View.GONE) {
                    entry.setVisibility(View.GONE);
                    module.logd(Log.INFO, MainModule.TAG,
                            "入口隐藏（无可见候选，候选数=" + candidates.size() + "）");
                }
                return;
            }
            entry.bind(chosen.url);
            View video = chosenAnchor;
            int[] loc = new int[2];
            video.getLocationInWindow(loc);
            int margin = ThemeUtils.dp(video.getContext(), 10);
            int entryW = entry.getMeasuredWidth();
            int entryH = entry.getMeasuredHeight();
            if (entryW <= 0) {
                entry.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                entryW = entry.getMeasuredWidth();
                entryH = entry.getMeasuredHeight();
            }
            int left = loc[0] + video.getWidth() - margin - entryW;
            int top = loc[1] + margin;
            int maxLeft = decor.getWidth() - entryW - margin;
            int maxTop = decor.getHeight() - entryH - margin;
            if (left < margin) {
                left = margin;
            }
            if (left > maxLeft) {
                left = maxLeft;
            }
            if (top < margin) {
                top = margin;
            }
            if (top > maxTop) {
                top = maxTop;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) entry.getLayoutParams();
            if (entry.getVisibility() != View.VISIBLE
                    || lp.leftMargin != left || lp.topMargin != top) {
                entry.setVisibility(View.VISIBLE);
                lp.leftMargin = left;
                lp.topMargin = top;
                entry.setLayoutParams(lp);
            }
            entry.invalidate();
        }

        /** 向上找第一个自身有尺寸的祖先（AbsVideoView 宽高可为 0，锚点用其容器） */
        private static View anchorOf(View v) {
            View cur = v;
            ViewParent p = v.getParent();
            while (cur.getWidth() <= 0 && p instanceof ViewGroup) {
                cur = (ViewGroup) p;
                p = cur.getParent();
            }
            return cur;
        }

        /** 视频区域与 Decor 实质相交（≥64dp）判断，防屏外预加载抢占入口 */
        private static boolean intersectsDecor(View v, int decorH) {
            int[] xy = new int[2];
            v.getLocationInWindow(xy);
            int top = xy[1];
            int bottom = top + v.getHeight();
            int visible = Math.min(bottom, decorH) - Math.max(top, 0);
            return visible >= ThemeUtils.dp(v.getContext(), 64);
        }

        /** 被后绘制且不透明的兄弟页面层盖住时判为遮挡（叠层下 isShown 恒真） */
        private static boolean coveredBySiblingLayer(View anchor, ViewGroup decor) {
            float px = anchor.getWidth() / 2f;
            float py = anchor.getHeight() / 2f;
            View cur = anchor;
            ViewParent par = anchor.getParent();
            while (par instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) par;
                if (parent == decor) {
                    break;
                }
                px += cur.getLeft() + cur.getTranslationX();
                py += cur.getTop() + cur.getTranslationY();
                int index = parent.indexOfChild(cur);
                for (int j = parent.getChildCount() - 1; j > index; j--) {
                    View sib = parent.getChildAt(j);
                    if (sib.getVisibility() != View.VISIBLE) {
                        continue;
                    }
                    float sx = px - sib.getLeft() - sib.getTranslationX();
                    float sy = py - sib.getTop() - sib.getTranslationY();
                    if (sx >= 0 && sy >= 0 && sx < sib.getWidth() && sy < sib.getHeight()
                            && occludesAt(sib, sx, sy)) {
                        return true;
                    }
                }
                cur = parent;
                par = parent.getParent();
            }
            return false;
        }

        /** 探测点处最上层内容是否不透明 */
        private static boolean occludesAt(View v, float px, float py) {
            if (v.isOpaque()) {
                return true;
            }
            if (!(v instanceof ViewGroup)) {
                return false;
            }
            ViewGroup vg = (ViewGroup) v;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                View child = vg.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                float cx = px - child.getLeft() - child.getTranslationX();
                float cy = py - child.getTop() - child.getTranslationY();
                if (cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight()) {
                    if (occludesAt(child, cx, cy)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void ensureEntry() {
            if (entry != null && entry.getParent() == decor) {
                if (!listenersAttached) {
                    attachListeners();
                }
                return;
            }
            entry = new DownloadFab(decor.getContext());
            entry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSheet();
                }
            });
            decor.addView(entry, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            attachListeners();
            entry.bringToFront();
        }

        private void attachListeners() {
            try {
                ViewTreeObserver vto = decor.getViewTreeObserver();
                vto.addOnScrollChangedListener(decorScrollListener);
                vto.addOnDrawListener(decorDrawListener);
                listenersAttached = true;
            } catch (Throwable ignored) {
            }
        }

        private void showSheet() {
            Candidate current = active;
            if (current == null || !VideoDownloadManager.isSupportedUrl(current.url)) {
                return;
            }
            dismissSheet();
            sheet = new DownloadSheet(decor.getContext(), decor);
            View videoView = current.video.get();
            sheet.show(current.url, current.headers,
                    videoView != null ? cardTitle(videoView) : null);
        }

        /**
 * 视频卡片标题（下载文件名用）：祖先链找 tv_title/tvTitle，回退 Activity 标题
 */
        private static String cardTitle(View video) {
            for (String idName : new String[]{"tv_title", "tvTitle"}) {
                String title = titleFromAncestors(video, idName, false);
                if (title != null) {
                    return title;
                }
            }
            String byTag = titleFromAncestors(video, "tvTitle", true);
            if (byTag != null) {
                return byTag;
            }
            return BottomSheetFallback.videoTitle(video.getContext());
        }

        private static String titleFromAncestors(View video, String idName, boolean byTag) {
            try {
                Context context = video.getContext();
                int titleId = byTag ? 0
                        : context.getResources().getIdentifier(
                        idName, "id", context.getPackageName());
                if (!byTag && titleId == 0) {
                    return null;
                }
                ViewParent p = video.getParent();
                while (p instanceof ViewGroup) {
                    ViewGroup container = (ViewGroup) p;
                    View title = byTag
                            ? container.findViewWithTag(idName)
                            : container.findViewById(titleId);
                    if (title instanceof TextView) {
                        CharSequence text = ((TextView) title).getText();
                        if (text != null && text.toString().trim().length() > 0) {
                            return text.toString().trim();
                        }
                    }
                    p = container.getParent();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private void dismissSheet() {
            if (sheet != null) {
                sheet.dismiss();
                sheet = null;
            }
        }
    }

    /** 供 cardTitle 回退使用 */
        private static final class BottomSheetFallback {
            static String videoTitle(Context context) {
                Activity activity = ViewUtils.findActivity(context);
            if (activity == null) {
                return null;
            }
            CharSequence raw = activity.getTitle();
            if (raw == null) {
                return null;
            }
            String title = raw.toString().trim();
            int sep = title.indexOf(" - ");
            if (sep > 0) {
                title = title.substring(0, sep).trim();
            }
            if (title.isEmpty() || title.contains("小黑盒")) {
                return null;
            }
            return title;
        }
    }

    /**
 * 圆形 Monet 渐变下载按钮：accent→accent2 渐变底 + 白色图标；四态（空闲/下载中/完成/失败）+ 按压缩放与 Ripple 反馈
 */
    private static final class DownloadFab extends View {

        private static final int PHASE_IDLE = 0;
        private static final int PHASE_RUNNING = 1;
        private static final int PHASE_DONE = 2;
        private static final int PHASE_RETRY = 3;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF ringRect = new RectF();
        private final Path path = new Path();
        private String boundUrl;
        private int phase = PHASE_IDLE;
        private int progress = -1;

        /**
         * 任务状态由监听器驱动（attach/detach 配对增删，随 FAB 生命周期），onDraw 只画不查表
         */
        private final VideoDownloadManager.TaskListener taskListener =
                new VideoDownloadManager.TaskListener() {
                    @Override
                    public void onTasksChanged() {
                        refreshTaskState();
                        invalidate();
                    }
                };

        DownloadFab(Context context) {
            super(context);
            int size = ThemeUtils.dp(context, 44);
            setClickable(true);
            setFocusable(true);
            setContentDescription("下载视频");
            setElevation(ThemeUtils.dp(context, 4));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, getWidth(), getHeight());
                }
            });
            setClipToOutline(true);
            setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                    circleMask()));
            setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            animate().scaleX(0.9f).scaleY(0.9f)
                                    .setDuration(ThemeUtils.ANIM_PRESS_MS)
                                    .setInterpolator(new DecelerateInterpolator()).start();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            animate().scaleX(1f).scaleY(1f)
                                    .setDuration(100L)
                                    .setInterpolator(new DecelerateInterpolator()).start();
                            break;
                    }
                    return false; // 不消费，保留点击
                }
            });
        }

        private android.graphics.drawable.Drawable circleMask() {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            return d;
        }

        void bind(String url) {
            this.boundUrl = url;
            refreshTaskState();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            VideoDownloadManager.get().addListener(taskListener);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            VideoDownloadManager.get().removeListener(taskListener);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int d = ThemeUtils.dp(getContext(), 40);
            setMeasuredDimension(d, d);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0) {
                return;
            }
            Context context = getContext();

            // 底：强档位 accent → accent2 的 45° 轻渐变（任意视频画面上都有足够对比度）
            if (!(paint.getShader() instanceof LinearGradient)) {
                paint.setShader(new LinearGradient(0, 0, w, h,
                        ThemeUtils.resolveAccentStrong(context),
                        ThemeUtils.resolveAccent2Strong(context), Shader.TileMode.CLAMP));
            }
            canvas.drawCircle(w / 2f, h / 2f, w / 2f, paint);

            int iconColor = ThemeUtils.readableForegroundOn(
                    ThemeUtils.resolveAccentStrong(context));
            paint.setShader(null);
            paint.setColor(iconColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ThemeUtils.dp(context, 2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float cx = w / 2f;
            float cy = h / 2f;
            path.rewind();

            if (phase == PHASE_RUNNING) {
                float ringRadius = w / 2f - ThemeUtils.dp(context, 2.5f);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ThemeUtils.dp(context, 2.5f));
                ringPaint.setColor(ThemeUtils.withAlpha(iconColor, 0x4D));
                ringRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
                canvas.drawArc(ringRect, 0, 360, false, ringPaint);
                ringPaint.setColor(iconColor);
                int pct = Math.max(0, Math.min(100, progress));
                canvas.drawArc(ringRect, -90, 360f * pct / 100f, false, ringPaint);
                drawDownloadGlyph(canvas, cx, cy, w * 0.16f, paint, path);
            } else if (phase == PHASE_DONE) {
                float r = w * 0.20f;
                path.moveTo(cx - r, cy);
                path.lineTo(cx - r * 0.25f, cy + r * 0.7f);
                path.lineTo(cx + r, cy - r * 0.55f);
                canvas.drawPath(path, paint);
            } else if (phase == PHASE_RETRY) {
                float r = w * 0.2f;
                ringRect.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(ringRect, -60, 285, false, paint);
                float ax = cx + (float) (r * Math.cos(Math.toRadians(135)));
                float ay = cy - (float) (r * Math.sin(Math.toRadians(135)));
                path.moveTo(ax - r * 0.28f, ay - r * 0.05f);
                path.lineTo(ax + r * 0.12f, ay - r * 0.38f);
                path.lineTo(ax + r * 0.3f, ay + r * 0.22f);
                canvas.drawPath(path, paint);
            } else {
                drawDownloadGlyph(canvas, cx, cy, w * 0.2f, paint, path);
            }
        }

        private void drawDownloadGlyph(Canvas canvas, float cx, float cy, float r,
                                       Paint paint, Path path) {
            path.moveTo(cx, cy - r);
            path.lineTo(cx, cy + r * 0.45f);
            path.moveTo(cx - r * 0.7f, cy - r * 0.15f);
            path.lineTo(cx, cy + r * 0.45f);
            path.lineTo(cx + r * 0.7f, cy - r * 0.15f);
            canvas.drawPath(path, paint);
            canvas.drawLine(cx - r * 0.85f, cy + r, cx + r * 0.85f, cy + r, paint);
        }

        private void refreshTaskState() {
            VideoDownloadManager.DownloadTask task =
                    VideoDownloadManager.get().findTask(boundUrl);
            if (task == null) {
                phase = PHASE_IDLE;
                progress = -1;
                return;
            }
            switch (task.state) {
                case PENDING:
                case DOWNLOADING:
                case PAUSED:
                    phase = PHASE_RUNNING;
                    progress = task.percent();
                    break;
                case COMPLETED:
                    phase = PHASE_DONE;
                    break;
                case FAILED:
                    phase = PHASE_RETRY;
                    break;
                default:
                    phase = PHASE_IDLE;
                    break;
            }
        }
    }

    /**
 * Material 底部抽屉下载面板：下拉/点外部关闭；五状态由任务注册表驱动，进度原地更新不重建视图
 */
    private static final class DownloadSheet {

        private final Context context;
        private final ViewGroup decor;
        private final int accent;

        private FrameLayout scrim;
        private LinearLayout panel;
        private FrameLayout content;
        private String url;
        private Map<String, String> headers;
        private String title;
        private volatile boolean dismissed;
        /** 面板当前展示的状态桶（null=无任务空闲）；PENDING 归并入 DOWNLOADING 避免重复重建 */
        private VideoDownloadManager.State shownState;

        /** 探测到的预计大小（-1 未知）与 HLS 分段数（-1 非 HLS） */
        private volatile long probeTotal = -1;
        private volatile int probeSegments = -1;
        private boolean probing;

        // 下载中/暂停内容引用（原地更新，不重建）
        private View progressBar;
        private TextView pctText;
        private final int[] progressHolder = new int[1];

        private final VideoDownloadManager.TaskListener listener =
                new VideoDownloadManager.TaskListener() {
                    @Override
                    public void onTasksChanged() {
                        if (dismissed || content == null) {
                            return;
                        }
                        VideoDownloadManager.DownloadTask task =
                                VideoDownloadManager.get().findTask(url);
                        VideoDownloadManager.State s = shownStateOf(task);
                        if (s != shownState) {
                            rebuild(task);
                        } else if (s == VideoDownloadManager.State.DOWNLOADING
                                || s == VideoDownloadManager.State.PAUSED) {
                            updateProgress(task);
                        }
                    }
                };

        DownloadSheet(Context context, ViewGroup decor) {
            this.context = context;
            this.decor = decor;
            this.accent = ThemeUtils.resolveAccentStrong(context);
        }

        void show(String url, Map<String, String> headers, String title) {
            this.url = url;
            this.headers = headers;
            this.title = title;
            this.dismissed = false;
            this.shownState = null;
            removeExisting();

            scrim = new FrameLayout(context);
            scrim.setBackgroundColor(0x52000000);
            scrim.setClickable(true);
            scrim.setAlpha(0f);
            scrim.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
            decor.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrim.animate().alpha(1f).setDuration(ThemeUtils.ANIM_SCRIM_IN_MS).start();

            panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setElevation(ThemeUtils.dp(context, 16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ThemeUtils.surfaceColor(context));
            bg.setCornerRadii(new float[]{
                    ThemeUtils.dp(context, ThemeUtils.RADIUS_SHEET_DP),
                    ThemeUtils.dp(context, ThemeUtils.RADIUS_SHEET_DP),
                    ThemeUtils.dp(context, ThemeUtils.RADIUS_SHEET_DP),
                    ThemeUtils.dp(context, ThemeUtils.RADIUS_SHEET_DP),
                    0, 0, 0, 0});
            panel.setBackground(bg);

            View handle = new View(context);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setColor(ThemeUtils.outlineColor(context));
            handleBg.setCornerRadius(ThemeUtils.dp(context, 2));
            handle.setBackground(handleBg);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                    ThemeUtils.dp(context, 32), ThemeUtils.dp(context, 4));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.setMargins(0, ThemeUtils.dp(context, 10), 0, ThemeUtils.dp(context, 6));
            panel.addView(handle, handleLp);
            attachDragToClose(handle);

            content = new FrameLayout(context);
            panel.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            decor.addView(panel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM));

            VideoDownloadManager.DownloadTask task =
                    VideoDownloadManager.get().findTask(url);
            rebuild(task);
            panel.setTranslationY(panel.getHeight() + ThemeUtils.dp(context, 40));
            panel.animate().translationY(0f).setDuration(ThemeUtils.ANIM_SHEET_IN_MS)
                    .setInterpolator(new DecelerateInterpolator(1.1f)).start();

            VideoDownloadManager.get().addListener(listener);
        }

        /** 拖动指示条下拉关闭（超阈值 dismiss，否则回弹） */
        private void attachDragToClose(View dragArea) {
            final float[] downY = {0};
            final float[] lastY = {0};
            final long[] lastT = {0};
            dragArea.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downY[0] = event.getRawY();
                            lastY[0] = event.getRawY();
                            lastT[0] = System.currentTimeMillis();
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dy = event.getRawY() - downY[0];
                            if (dy > 0) {
                                panel.setTranslationY(dy);
                            }
                            lastY[0] = event.getRawY();
                            lastT[0] = System.currentTimeMillis();
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            float dy = event.getRawY() - downY[0];
                            long dt = Math.max(1, System.currentTimeMillis() - lastT[0]);
                            float velocity = (event.getRawY() - lastY[0]) / dt; // px/ms
                            if (dy > ThemeUtils.dp(context, 96) || (dy > 24 && velocity > 1.4f)) {
                                dismiss();
                            } else {
                                panel.animate().translationY(0f)
                                        .setDuration(ThemeUtils.ANIM_STATE_MS)
                                        .setInterpolator(new DecelerateInterpolator()).start();
                            }
                            return true;
                        }
                    }
                    return false;
                }
            });
        }

        private static VideoDownloadManager.State shownStateOf(
                VideoDownloadManager.DownloadTask task) {
            if (task == null) {
                return null;
            }
            return task.state == VideoDownloadManager.State.PENDING
                    ? VideoDownloadManager.State.DOWNLOADING : task.state;
        }

        /** 状态切换时整块重建内容；同状态进度变化走 updateProgress 原地更新 */
        private void rebuild(final VideoDownloadManager.DownloadTask task) {
            if (dismissed || content == null) {
                return;
            }
            content.removeAllViews();
            shownState = shownStateOf(task);
            Context c = context;
            VideoDownloadManager.State state = task != null ? task.state
                    : VideoDownloadManager.State.PENDING;

            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(ThemeUtils.dp(c, 20), ThemeUtils.dp(c, 8),
                    ThemeUtils.dp(c, 20), ThemeUtils.dp(c, 16));
            content.addView(box, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (state == VideoDownloadManager.State.COMPLETED) {
                buildDoneState(c, box, task);
            } else if (state == VideoDownloadManager.State.FAILED) {
                buildFailedState(c, box, task);
            } else {
                buildHeader(c, box, task);

                if (state == VideoDownloadManager.State.DOWNLOADING) {
                    buildProgressBlock(c, box, task);
                    addActiveButtons(c, box, false, "暂停下载",
                            () -> VideoDownloadManager.get().pause(url));
                } else if (state == VideoDownloadManager.State.PAUSED) {
                    buildProgressBlock(c, box, task);
                    addActiveButtons(c, box, true, "继续下载",
                            () -> VideoDownloadManager.get().resume(url));
                } else {
                    final TextView sizeLine = secondaryText(c, "小黑盒 · 计算大小中…");
                    box.addView(sizeLine);
                    maybeProbe();
                    if (probeTotal > 0) {
                        sizeLine.setText("小黑盒 · 预计 " + VideoDownloadManager
                                .formatSize(probeTotal));
                    } else if (probeSegments > 0) {
                        sizeLine.setText("小黑盒 · " + probeSegments + " 个分段");
                    }
                    addButtons(c, box,
                            sheetButton(c, ButtonStyle.PRIMARY, "开始下载",
                                    () -> VideoDownloadManager.get().startDownload(url, headers, title),
                                    false),
                            sheetButton(c, ButtonStyle.TEXT, "取消", null, true));
                }
            }
        }

        /** 下载中/暂停：原地更新进度文本与进度条（不重建视图，按钮点击不受影响） */
        private void updateProgress(VideoDownloadManager.DownloadTask task) {
            progressHolder[0] = Math.max(0, task.percent());
            if (progressBar != null) {
                progressBar.invalidate();
            }
            if (pctText != null) {
                pctText.setText(task.statusLine() + " · " + Math.max(0, task.percent()) + "%");
            }
        }

        /** 异步探测大小（一次） */
        private void maybeProbe() {
            if (probing) {
                return;
            }
            probing = true;
            VideoDownloadManager.get().probeInfo(url, headers,
                    new VideoDownloadManager.ProbeCallback() {
                        @Override
                        public void onInfo(long totalBytes, int segments) {
                            if (dismissed) {
                                return;
                            }
                            probeTotal = totalBytes;
                            probeSegments = segments;
                            VideoDownloadManager.DownloadTask task =
                                    VideoDownloadManager.get().findTask(url);
                            if (task == null) {
                                rebuild(task);
                            }
                        }
                    });
        }

        private void buildHeader(Context c, LinearLayout box,
                                 VideoDownloadManager.DownloadTask task) {
            TextView titleView = new TextView(c);
            titleView.setText(title != null ? title
                    : (task != null ? task.displayTitle() : "视频"));
            titleView.setTextSize(16);
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            titleView.setTextColor(ThemeUtils.textPrimaryColor(c));
            titleView.setMaxLines(2);
            box.addView(titleView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(space(c, 4));
            box.addView(secondaryText(c, "小黑盒 · MP4 / HLS"));
            box.addView(space(c, 14));
        }

        private void buildProgressBlock(Context c, LinearLayout box,
                                        VideoDownloadManager.DownloadTask task) {
            int pct = Math.max(0, task.percent());
            LinearLayout pctRow = new LinearLayout(c);
            pctRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView statusText = secondaryText(c, task.statusLine());
            statusText.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pctRow.addView(statusText);
            pctText = new TextView(c);
            pctText.setText((task.state == VideoDownloadManager.State.PAUSED ? "已暂停 " : "")
                    + (pct >= 0 ? pct + "%" : "…"));
            pctText.setTextSize(13);
            pctText.setTypeface(null, android.graphics.Typeface.BOLD);
            pctText.setTextColor(accent);
            pctRow.addView(pctText);
            box.addView(pctRow);
            box.addView(space(c, 8));

            progressHolder[0] = pct;
            progressBar = new View(c) {
                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    float radius = getHeight() / 2f;
                    p.setColor(ThemeUtils.surfaceVariantColor(c));
                    canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, p);
                    p.setColor(accent);
                    int pw = (int) (getWidth() * (progressHolder[0] / 100f));
                    if (pw > 0) {
                        canvas.drawRoundRect(new RectF(0, 0, Math.max(pw, getHeight()), getHeight()),
                                radius, radius, p);
                    }
                }
            };
            box.addView(progressBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ThemeUtils.dp(c, 6)));
            box.addView(space(c, 14));
        }

        private void buildDoneState(Context c, LinearLayout box,
                                    VideoDownloadManager.DownloadTask task) {
            TextView done = new TextView(c);
            done.setText("✓ 下载完成");
            done.setTextSize(16);
            done.setTypeface(null, android.graphics.Typeface.BOLD);
            done.setTextColor(accent);
            box.addView(done, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(space(c, 4));
            TextView pathText = secondaryText(c, (task.savedPath != null ? task.savedPath : "")
                    + (task.total > 0 ? " · " + VideoDownloadManager.formatSize(task.total) : ""));
            pathText.setMaxLines(2);
            box.addView(pathText, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(space(c, 12));

            final VideoDownloadManager.DownloadTask t = task;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            View play = sheetButton(c, ButtonStyle.PRIMARY, "播放",
                    () -> t.open(context), true);
            LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            playLp.rightMargin = ThemeUtils.dp(c, 6);
            row.addView(play, playLp);
            View share = sheetButton(c, ButtonStyle.SECONDARY, "分享",
                    () -> t.share(context), true);
            LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            shareLp.leftMargin = ThemeUtils.dp(c, 6);
            row.addView(share, shareLp);
            box.addView(row);
            box.addView(space(c, 6));
            addButtons(c, box, sheetButton(c, ButtonStyle.TEXT, "完成", null, true));
        }

        private void buildFailedState(Context c, LinearLayout box,
                                      VideoDownloadManager.DownloadTask task) {
            buildHeader(c, box, task);
            TextView err = new TextView(c);
            err.setText("下载失败" + (task.errorMsg != null ? " · " + task.errorMsg : ""));
            err.setTextSize(14);
            err.setTextColor(0xFFE53935);
            box.addView(err);
            box.addView(space(c, 12));
            addButtons(c, box,
                    sheetButton(c, ButtonStyle.PRIMARY, "重新下载",
                            () -> VideoDownloadManager.get().resume(url), false),
                    sheetButton(c, ButtonStyle.TEXT, "取消", null, true));
        }


        private enum ButtonStyle { PRIMARY, SECONDARY, TEXT }

        private View space(Context c, int dp) {
            View v = new View(c);
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ThemeUtils.dp(c, dp)));
            return v;
        }

        private TextView secondaryText(Context c, String text) {
            TextView tv = new TextView(c);
            tv.setText(text);
            tv.setTextSize(13);
            tv.setTextColor(ThemeUtils.textSecondaryColor(c));
            return tv;
        }

        private void applyStyle(TextView button, ButtonStyle style) {
            int bgColor;
            int rippleOverlay;
            int textColor;
            switch (style) {
                case PRIMARY:
                    bgColor = accent;
                    rippleOverlay = ThemeUtils.withAlpha(
                            ThemeUtils.readableForegroundOn(accent), 0x33);
                    textColor = ThemeUtils.readableForegroundOn(accent);
                    break;
                case SECONDARY:
                    bgColor = ThemeUtils.surfaceVariantColor(context);
                    rippleOverlay = ThemeUtils.withAlpha(
                            ThemeUtils.textPrimaryColor(context), 0x1F);
                    textColor = ThemeUtils.textPrimaryColor(context);
                    break;
                default:
                    bgColor = Color.TRANSPARENT;
                    rippleOverlay = ThemeUtils.withAlpha(
                            ThemeUtils.textPrimaryColor(context), 0x1F);
                    textColor = ThemeUtils.textSecondaryColor(context);
                    break;
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(bgColor);
            bg.setCornerRadius(ThemeUtils.dp(button.getContext(), ThemeUtils.RADIUS_BUTTON_DP));
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleOverlay), bg, null));
            button.setTextColor(textColor);
        }

        /** dismissAfter：true 时执行动作后关闭抽屉（开始/暂停/继续为 false，保持面板查看进度） */
        private TextView sheetButton(Context c, ButtonStyle style, String text,
                                     final Runnable action, final boolean dismissAfter) {
            TextView button = new TextView(c);
            button.setText(text);
            button.setTextSize(15);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(ThemeUtils.dp(c, 48));
            button.setClickable(true);
            button.setFocusable(true);
            applyStyle(button, style);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        if (action != null) {
                            action.run();
                        }
                    } catch (Throwable ignored) {
                    }
                    if (dismissAfter) {
                        dismiss();
                    }
                }
            });
            return button;
        }

        private void addButtons(Context c, LinearLayout box, View... buttons) {
            for (View b : buttons) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = ThemeUtils.dp(c, 8);
                box.addView(b, lp);
            }
        }

        private void addActiveButtons(Context c, LinearLayout box, boolean primary,
                                      String actionLabel, Runnable primaryAction) {
            addButtons(c, box,
                    sheetButton(c, primary ? ButtonStyle.PRIMARY : ButtonStyle.SECONDARY,
                            actionLabel, primaryAction, false),
                    sheetButton(c, ButtonStyle.TEXT, "取消下载",
                            () -> VideoDownloadManager.get().cancel(url), true));
        }

        private void removeExisting() {
            if (scrim != null && scrim.getParent() != null) {
                ((ViewGroup) scrim.getParent()).removeView(scrim);
            }
            if (panel != null && panel.getParent() != null) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
            scrim = null;
            panel = null;
            content = null;
        }

        void dismiss() {
            if (dismissed) {
                return;
            }
            dismissed = true;
            VideoDownloadManager.get().removeListener(listener);
            if (panel != null) {
                final View p = panel;
                final FrameLayout s = scrim;
                p.animate().translationY(p.getHeight() + ThemeUtils.dp(context, 40))
                        .setDuration(180L)
                        .setInterpolator(new DecelerateInterpolator(1.4f)).start();
                if (s != null) {
                    s.animate().alpha(0f).setDuration(120L).start();
                }
                p.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        removeExisting();
                    }
                }, 200L);
            } else {
                removeExisting();
            }
        }
    }
}
