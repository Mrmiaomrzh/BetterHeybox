package com.better.heybox.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.PixelCopy;
import android.view.ViewParent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;


public final class ScreenshotRenderer {

    
    private static final int GAP_DP = 10;
    
    private static final int PADDING_DP = 10;
    
    
    private static final int MAX_OUTPUT_HEIGHT = 20000;
    
    private static final int EXPAND_NODES = 600;
    
    private static final int SCROLL_SETTLE_MS = 300;
    
    private static final int SETTLE_EXTRA_MS = 140;
    
    private static final int CHUNK_OVERLAP = 16;
    
    private static final int MAX_STITCH_CHUNKS = 12;

    private ScreenshotRenderer() {
    }

    
    public interface BitmapCallback {
        void onCaptured(Bitmap bitmap);
    }

    
    public static final class FoldedText {
        private final List<TextView> relaxed = new ArrayList<>();
        private final List<Integer> maxLines = new ArrayList<>();
        private final List<TextUtils.TruncateAt> ellipsize = new ArrayList<>();
        
        public boolean clicked;

        
        public void restore() {
            for (int i = 0; i < relaxed.size(); i++) {
                try {
                    relaxed.get(i).setMaxLines(maxLines.get(i));
                    relaxed.get(i).setEllipsize(ellipsize.get(i));
                } catch (Throwable ignored) {

                }
            }
            relaxed.clear();
            maxLines.clear();
            ellipsize.clear();
        }
    }

    
    public static FoldedText expandFoldedTexts(List<View> units) {
        FoldedText state = new FoldedText();
        if (units == null) {
            return state;
        }
        for (View unit : units) {
            if (unit == null) {
                continue;
            }
            try {
                relaxFoldedText(unit, state);
                if (clickExpandControl(unit)) {
                    state.clicked = true;
                }
            } catch (Throwable ignored) {

            }
        }
        return state;
    }

    private static void relaxFoldedText(View root, FoldedText state) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < EXPAND_NODES) {
            View current = queue.poll();
            if (current instanceof TextView) {
                TextView text = (TextView) current;
                int lines = text.getMaxLines();
                if (lines > 0 && lines != Integer.MAX_VALUE) {
                    state.relaxed.add(text);
                    state.maxLines.add(lines);
                    state.ellipsize.add(text.getEllipsize());
                    text.setMaxLines(Integer.MAX_VALUE);
                    text.setEllipsize(null);
                }
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
    }

    private static boolean clickExpandControl(View root) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < EXPAND_NODES) {
            View current = queue.poll();
            if (current instanceof TextView && current.isShown() && current.isClickable()) {
                CharSequence raw = ((TextView) current).getText();
                String text = raw == null ? "" : raw.toString().trim();
                if (text.length() > 0 && text.length() <= 12
                        && (text.contains("展开全文") || text.contains("展开剩余")
                        || "全文".equals(text))) {
                    current.performClick();
                    return true;
                }
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return false;
    }

    
    public static int backgroundColor(Context context) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data;
                }
                if (value.resourceId != 0) {
                    return context.getResources().getColor(value.resourceId);
                }
            }
        } catch (Throwable ignored) {

        }
        return Color.WHITE;
    }

    
    
    public static void layoutForCapture(View unit) {
        if (unit == null) {
            return;
        }
        int width = unit.getWidth();
        if (width <= 0) {
            width = unit.getMeasuredWidth();
        }
        if (width <= 0) {
            return;
        }
        try {
            unit.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int measured = unit.getMeasuredHeight();
            int height = measured > 0 ? measured : unit.getHeight();
            if (height > 0) {
                unit.layout(0, 0, width, height);
            }
        } catch (Throwable ignored) {

        }
    }

    public static Bitmap captureUnit(View unit, int bgColor) {
        if (unit == null) {
            return null;
        }
        int width = unit.getWidth();
        if (width <= 0) {
            return null;
        }
        final boolean fixedHeight = ScreenshotSelectionManager.isWebView(unit);

        int savedLeft = unit.getLeft();
        int savedTop = unit.getTop();
        int savedRight = unit.getRight();
        int savedBottom = unit.getBottom();
        int height = unit.getHeight();

        try {
            unit.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int measured = unit.getMeasuredHeight();
            if (measured > 0 && !fixedHeight) {
                height = measured;
            }
            if (height <= 0) {
                return null;
            }
            unit.layout(0, 0, width, height);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(bgColor);
            unit.draw(canvas);
            return bitmap;
        } catch (OutOfMemoryError error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                unit.layout(savedLeft, savedTop, savedRight, savedBottom);
                unit.requestLayout();
            } catch (Throwable ignored) {

            }
        }
    }

    
    public static void captureWebViewAsync(Activity activity, View web, int bgColor, BitmapCallback callback) {
        if (web == null || callback == null) {
            if (callback != null) {
                callback.onCaptured(null);
            }
            return;
        }
        int width = web.getWidth();
        int viewHeight = web.getHeight();
        if (width <= 0 || viewHeight <= 0) {
            callback.onCaptured(null);
            return;
        }

        int total = Math.min(MAX_OUTPUT_HEIGHT, viewHeight * MAX_STITCH_CHUNKS);
        try {
            long budget = Runtime.getRuntime().maxMemory() / 2;
            int byMemory = (int) Math.max(viewHeight, Math.min(Integer.MAX_VALUE, budget / (4L * width)));
            total = Math.min(total, byMemory);
        } catch (Throwable ignored) {

        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                new WebStitcher(activity, web, width, viewHeight, total, bgColor, callback).start();
                return;
            } catch (OutOfMemoryError error) {
                total /= 2;
                if (total < viewHeight) {
                    break;
                }
            }
        }
        callback.onCaptured(null);
    }

    
    private static int contentHeight(View web) {
        try {
            Object raw = web.getClass().getMethod("getContentHeight").invoke(web);
            float scale = 1f;
            try {
                Object value = web.getClass().getMethod("getScale").invoke(web);
                if (value instanceof Number) {
                    scale = ((Number) value).floatValue();
                }
            } catch (Throwable ignored) {

            }
            int content = (int) (((Number) raw).intValue() * (scale <= 0f ? 1f : scale));
            return Math.max(content, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    
    private static Bitmap drawChunk(View web, int width, int height, int bgColor) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        try {
            Bitmap chunk = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(chunk);
            canvas.drawColor(bgColor);
            canvas.save();
            canvas.clipRect(0, 0, width, height);
            web.draw(canvas);
            canvas.restore();
            return chunk;
        } catch (OutOfMemoryError error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        }
    }

    
    private static boolean isUniform(Bitmap chunk) {
        int width = chunk.getWidth();
        int height = chunk.getHeight();
        int step = 8;
        int reference = chunk.getPixel(0, 0);
        int sampled = 0;
        int different = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                sampled++;
                if (chunk.getPixel(x, y) != reference) {
                    different++;
                }
            }
        }
        return sampled == 0 || different * 100 < sampled;
    }

    
    private static long signature(Bitmap chunk) {
        int width = chunk.getWidth();
        int height = chunk.getHeight();
        long hash = 1125899906842597L;
        for (int gy = 0; gy < 16; gy++) {
            int y = Math.min(height - 1, gy * Math.max(1, height / 16));
            for (int gx = 0; gx < 16; gx++) {
                int x = Math.min(width - 1, gx * Math.max(1, width / 16));
                hash = hash * 31 + chunk.getPixel(x, y);
            }
        }
        return hash;
    }

    
    private static final class WebStitcher {
        private final Activity activity;
        private final View web;
        private final int width;
        private final int viewHeight;
        private final int total;
        private final int bgColor;
        private final BitmapCallback callback;
        private final int savedScrollY;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Bitmap output;
        private Canvas canvas;
        private int drawn;
        
        private int lastSkip;
        
        private final java.util.List<View> scrolledAncestors = new java.util.ArrayList<>();
        private final java.util.List<Integer> ancestorScrolls = new java.util.ArrayList<>();
        private int offset;
        private int lastTop = -1;
        
        private int lastActualScroll;
        private boolean scrollLive;
        private long lastSignature;

        WebStitcher(Activity activity, View web, int width, int viewHeight, int total, int bgColor,
                BitmapCallback callback) {
            this.activity = activity;
            this.web = web;
            this.width = width;
            this.viewHeight = viewHeight;
            this.total = total;
            this.bgColor = bgColor;
            this.callback = callback;
            this.savedScrollY = web.getScrollY();
            this.lastActualScroll = this.savedScrollY;
            this.output = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888);
            this.canvas = new Canvas(output);
            this.canvas.drawColor(bgColor);
        }

        void start() {

            bringIntoView();
            step();
        }

        
        private void bringIntoView() {
            try {
                int[] location = new int[2];
                web.getLocationOnScreen(location);
                int[] windowLocation = new int[2];
                activity.getWindow().getDecorView().getLocationOnScreen(windowLocation);
                int top = location[1] - windowLocation[1];
                int windowHeight = activity.getWindow().getDecorView().getHeight();
                if (top >= 0 && top + web.getHeight() <= windowHeight) {
                    return;
                }
                ViewParent parent = web.getParent();
                for (int depth = 0; depth < 20 && parent instanceof View; depth++) {
                    View p = (View) parent;
                    if (p.getScrollY() != 0) {
                        scrolledAncestors.add(p);
                        ancestorScrolls.add(p.getScrollY());
                        p.scrollTo(0, 0);
                    }
                    parent = p.getParent();
                }
            } catch (Throwable ignored) {

            }
        }

        private void step() {
            if (offset >= total || drawn >= total) {
                finish();
                return;
            }
            final int requested = offset;
            try {
                web.scrollTo(0, requested);
            } catch (Throwable ignored) {
                captureByDraw(requested);
                return;
            }
            web.postDelayed(new Runnable() {
                @Override
                public void run() {

                    int actual = requested;
                    try {
                        actual = web.getScrollY();
                    } catch (Throwable ignored) {
                        actual = requested;
                    }
                    if (actual != lastActualScroll) {
                        scrollLive = true;
                        lastActualScroll = actual;
                    }
                    if (actual < 0) {
                        actual = requested;
                    }
                    if (drawn > 0 && scrollLive && actual <= lastTop) {

                        finish();
                        return;
                    }
                    capture(scrollLive ? actual : requested);
                }
            }, SCROLL_SETTLE_MS);
        }

        
        private void capture(final int current) {
            final int height = Math.min(viewHeight, total - drawn);
            if (activity == null || activity.isFinishing()) {
                captureByDraw(current);
                return;
            }
            final Rect rect = onScreenRect(height);
            if (rect == null) {
                captureByDraw(current);
                return;
            }
            pixelCopy(current, height, rect, false);
        }

        
        private void pixelCopy(final int current, final int height, final Rect rect, final boolean second) {
            final Bitmap chunk;
            try {
                chunk = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError error) {
                throw error;
            } catch (Throwable ignored) {
                if (second) {
                    captureByDraw(current);
                } else {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            pixelCopy(current, height, rect, true);
                        }
                    }, SETTLE_EXTRA_MS);
                }
                return;
            }
            try {
                PixelCopy.request(activity.getWindow(), rect, chunk, new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override
                    public void onPixelCopyFinished(int copyResult) {
                        if (copyResult != PixelCopy.SUCCESS) {
                            chunk.recycle();
                            if (second) {
                                captureByDraw(current);
                            } else {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        pixelCopy(current, height, rect, true);
                                    }
                                }, SETTLE_EXTRA_MS);
                            }
                            return;
                        }
                        if (second) {
                            accept(current, height, chunk, true);
                            return;
                        }
                        chunk.recycle();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                pixelCopy(current, height, rect, true);
                            }
                        }, SETTLE_EXTRA_MS);
                    }
                }, handler);
            } catch (OutOfMemoryError error) {
                throw error;
            } catch (Throwable ignored) {
                chunk.recycle();
                if (second) {
                    captureByDraw(current);
                } else {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            pixelCopy(current, height, rect, true);
                        }
                    }, SETTLE_EXTRA_MS);
                }
            }
        }

        
        private void captureByDraw(int current) {
            int height = Math.min(viewHeight, total - drawn);
            Bitmap chunk = drawChunk(web, width, height, bgColor);
            if (chunk == null) {
                finish();
                return;
            }
            accept(current, height, chunk, false);
        }

        private void accept(int current, int height, Bitmap chunk, boolean copied) {
            boolean uniform = isUniform(chunk);
            long signature = uniform ? 0L : signature(chunk);
            if (ScreenshotSelectionManager.get().isDebug()) {
                android.util.Log.i("BetterHeybox", "截图诊断 WebView 分块 y=" + current
                        + " 高=" + chunk.getHeight() + " 像素拷贝=" + copied
                        + " 空白=" + uniform + " 同上=" + (drawn > 0 && signature == lastSignature)
                        + " 已拼=" + drawn);
            }
            if (drawn > 0 && (uniform || signature == lastSignature)) {

                chunk.recycle();
                finish();
                return;
            }
            lastSignature = signature;
            lastTop = current;

            final int skip = Math.max(0, lastSkip);
            final int at = current + skip;
            canvas.drawBitmap(chunk, 0, at, null);
            final int captured = chunk.getHeight();
            drawn = Math.min(total, Math.max(drawn, at + captured));
            chunk.recycle();
            offset = at + Math.max(1, captured - CHUNK_OVERLAP);
            step();
        }

        
        private Rect onScreenRect(int height) {
            try {
                int[] location = new int[2];
                web.getLocationOnScreen(location);
                int[] windowLocation = new int[2];
                activity.getWindow().getDecorView().getLocationOnScreen(windowLocation);
                int windowHeight = activity.getWindow().getDecorView().getHeight();
                int left = Math.max(0, location[0] - windowLocation[0]);
                int rawTop = location[1] - windowLocation[1];
                int right = Math.min(left + width, activity.getWindow().getDecorView().getWidth());

                int top = Math.max(0, rawTop);
                int bottom = Math.min(rawTop + height, windowHeight);
                lastSkip = top - rawTop;
                if (right - left <= 0 || bottom - top <= 0) {
                    return null;
                }
                return new Rect(left, top, right, bottom);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void finish() {
            try {
                web.scrollTo(0, savedScrollY);
            } catch (Throwable ignored) {

            }

            for (int i = 0; i < scrolledAncestors.size(); i++) {
                try {
                    scrolledAncestors.get(i).scrollTo(0, ancestorScrolls.get(i));
                } catch (Throwable ignored) {

                }
            }
            Bitmap result = output;
            try {
                if (drawn <= 0) {
                    result = null;
                } else if (drawn < output.getHeight()) {
                    result = Bitmap.createBitmap(output, 0, 0, width, drawn);
                }
            } catch (OutOfMemoryError error) {
                result = output;
            } catch (Throwable ignored) {
                result = output;
            }
            if (result != output) {
                output.recycle();
            }
            output = null;
            canvas = null;
            callback.onCaptured(result);
        }
    }

    
    public static Bitmap composeVertical(Context context, List<Bitmap> parts, int bgColor) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            Bitmap only = parts.get(0);
            return only == null ? null : only.copy(Bitmap.Config.ARGB_8888, false);
        }

        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.max(1, (int) (GAP_DP * density));
        int padding = Math.max(0, (int) (PADDING_DP * density));

        int contentWidth = 0;
        int contentHeight = gap * (parts.size() - 1);
        for (Bitmap part : parts) {
            if (part == null) {
                continue;
            }
            contentWidth = Math.max(contentWidth, part.getWidth());
            contentHeight += part.getHeight();
        }
        if (contentWidth <= 0 || contentHeight <= 0) {
            return null;
        }

        int outWidth = contentWidth + padding * 2;
        int outHeight = contentHeight + padding * 2;

        float scale = 1f;
        if (outHeight > MAX_OUTPUT_HEIGHT) {
            scale = MAX_OUTPUT_HEIGHT / (float) outHeight;
            outWidth = Math.max(1, (int) (outWidth * scale));
            outHeight = MAX_OUTPUT_HEIGHT;
        }

        Bitmap output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(bgColor);
        canvas.save();
        canvas.scale(scale, scale);

        int y = padding;
        for (Bitmap part : parts) {
            if (part == null || part.isRecycled()) {
                continue;
            }
            float x = padding + (contentWidth - part.getWidth()) / 2f;
            canvas.drawBitmap(part, x, y, null);
            y += part.getHeight() + gap;
        }
        canvas.restore();
        return output;
    }
}

