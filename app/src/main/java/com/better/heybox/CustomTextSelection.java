package com.better.heybox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Layout;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 自绘制文本选择：长按选中、拖动手柄、高亮、菜单全由本类实现，不触发系统与小黑盒的选择 UI。
 * 要点：Monet 取色回退链；逐行圆角高亮 UNION 合并；菜单浮层挂 Decor；反射链式调用原有 Touch/长按监听
 */
public final class CustomTextSelection {

    private static final String TAG = "BetterHeybox";
    private static final int DEFAULT_ACCENT = 0xFF1677FF;
    private static final Map<TextView, Controller> CONTROLLERS =
            Collections.synchronizedMap(new WeakHashMap<TextView, Controller>());

    private CustomTextSelection() {
    }
    public static void attach(TextView tv) {
        if (tv == null) {
            return;
        }
        synchronized (CONTROLLERS) {
            if (CONTROLLERS.containsKey(tv)) {
                return;
            }
            Controller controller = new Controller(tv);
            CONTROLLERS.put(tv, controller);
            controller.attach();
        }
    }
    public static void detach(TextView tv) {
        if (tv == null) {
            return;
        }
        Controller controller;
        synchronized (CONTROLLERS) {
            controller = CONTROLLERS.remove(tv);
        }
        if (controller != null) {
            controller.detach();
        }
    }
        public static void cancelAll() {
        synchronized (CONTROLLERS) {
            for (Controller controller : CONTROLLERS.values()) {
                try {
                    controller.cancel();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class Controller implements View.OnTouchListener, View.OnLongClickListener {

        private final TextView tv;
        private final View.OnTouchListener prevTouch;
        private final View.OnLongClickListener prevLongClick;

        private final int accentColor;
        private final float density;

        private boolean selecting;
        private int anchor = -1;
        private int selStart = -1;
        private int selEnd = -1;
        private float downX;
        private float downY;

        private HighlightOverlay overlay;
        private SelectionHandle startHandle;
        private SelectionHandle endHandle;
        private View menuScrim;
        private LinearLayout menuBubble;
        private TextView selectAllItem;
        private View selectAllDivider;
        private boolean menuAbove;
        private int draggingHandle;

        private final ViewTreeObserver.OnScrollChangedListener scrollListener =
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        syncOverlays(false);
                    }
                };

        private final View.OnLayoutChangeListener layoutListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (l != ol || t != ot || r != or || b != ob) {
                    syncOverlays(false);
                }
            }
        };

        Controller(TextView tv) {
            this.tv = tv;
            this.prevTouch = readListener(tv, "mOnTouchListener");
            this.prevLongClick = readListener(tv, "mOnLongClickListener");
            this.accentColor = ThemeUtils.resolveAccent(tv.getContext());
            this.density = tv.getResources().getDisplayMetrics().density;
        }

        @SuppressWarnings("unchecked")
        private static <T> T readListener(View view, String fieldName) {
            try {
                java.lang.reflect.Field field = View.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(view);
            } catch (Throwable ignored) {
                return null;
            }
        }

        void attach() {
            tv.setOnTouchListener(this);
            tv.setOnLongClickListener(this);
            tv.addOnLayoutChangeListener(layoutListener);
            if (tv.isTextSelectable()) {
                tv.setTextIsSelectable(false);
            }
            tv.setMovementMethod(null);
        }

        void detach() {
            cancel();
            tv.removeOnLayoutChangeListener(layoutListener);
            tv.setOnTouchListener(prevTouch);
            tv.setOnLongClickListener(prevLongClick);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = event.getX();
                    downY = event.getY();
                    if (selecting || menuScrim != null) {
                        cancel();
                    }
                    return prevTouch != null && prevTouch.onTouch(v, event);
                }
                case MotionEvent.ACTION_MOVE: {
                    if (selecting) {
                        updateSelection(event.getX(), event.getY());
                        return true;
                    }
                    return prevTouch != null && prevTouch.onTouch(v, event);
                }
                case MotionEvent.ACTION_UP: {
                    if (selecting) {
                        finishSelection();
                        return true;
                    }
                    return prevTouch != null && prevTouch.onTouch(v, event);
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (selecting) {
                        cancel();
                        return true;
                    }
                    return prevTouch != null && prevTouch.onTouch(v, event);
                }
                default:
                    return prevTouch != null && prevTouch.onTouch(v, event);
            }
        }

        @Override
        public boolean onLongClick(View v) {
            if (selecting) {
                return true;
            }
            Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || text == null || text.length() == 0) {
                return prevLongClick != null && prevLongClick.onLongClick(v);
            }
            cancelAll();
            int offset = tv.getOffsetForPosition(downX, downY);
            if (offset < 0) {
                offset = 0;
            }
            int[] word = wordBoundary(offset);
            startSelection(word[0], word[1]);
            return true;
        }

        private void startSelection(int start, int end) {
            selecting = true;
            anchor = start;
            selStart = start;
            selEnd = end;
            if (tv.getParent() != null) {
                tv.getParent().requestDisallowInterceptTouchEvent(true);
            }
            try {
                tv.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
            } catch (Throwable ignored) {
            }
            updateHighlight();
            showHandles();
        }

        private void updateSelection(float x, float y) {
            Layout layout = tv.getLayout();
            if (layout == null) {
                return;
            }
            int offset = tv.getOffsetForPosition(x, y);
            int len = tv.getText() == null ? 0 : tv.getText().length();
            if (offset < 0) {
                offset = 0;
            }
            if (offset > len) {
                offset = len;
            }
            selStart = Math.min(anchor, offset);
            selEnd = Math.max(anchor, offset);
            updateHighlight();
            updateHandlePositions();
        }

        private void finishSelection() {
            if (tv.getParent() != null) {
                tv.getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (!selecting) {
                return;
            }
            selecting = false;
            if (selStart < 0 || selEnd <= selStart) {
                cancel();
                return;
            }
            showMenu();
        }

        private void syncOverlays(boolean animateMenu) {
            if (!selecting && menuBubble == null) {
                return;
            }
            if (tv.getWindowToken() == null || !tv.isShown()) {
                return;
            }
            if (selecting && selStart >= 0 && selEnd > selStart) {
                ensureOverlay();
                positionOverlay();
                updateHandlePositions();
            }
            if (menuBubble != null && menuBubble.getVisibility() == View.VISIBLE) {
                positionMenuBubble(animateMenu);
            }
        }

        private void updateHighlight() {
            if (selStart >= 0 && selEnd > selStart) {
                ensureOverlay();
                if (overlay != null) {
                    overlay.setSelection(selStart, selEnd);
                    positionOverlay();
                }
            } else {
                removeOverlay();
            }
        }

        private void ensureOverlay() {
            if (overlay != null) {
                return;
            }
            ViewGroup decor = ViewUtils.findDecor(tv);
            if (decor == null) {
                return;
            }
            HighlightOverlay o = new HighlightOverlay(tv.getContext(), tv, accentColor);
            decor.addView(o, new FrameLayout.LayoutParams(1, 1));
            overlay = o;
        }

        private void positionOverlay() {
            if (overlay == null) {
                return;
            }
            if (tv.getWindowToken() == null || !tv.isShown() || tv.getWidth() <= 0) {
                return;
            }
            int[] loc = new int[2];
            tv.getLocationInWindow(loc);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlay.getLayoutParams();
            ViewGroup parent = (ViewGroup) overlay.getParent();
            int padLeft = parent != null ? parent.getPaddingLeft() : 0;
            int padTop = parent != null ? parent.getPaddingTop() : 0;
            if (lp.width != tv.getWidth() || lp.height != tv.getHeight()
                    || lp.leftMargin != loc[0] - padLeft || lp.topMargin != loc[1] - padTop) {
                lp.width = tv.getWidth();
                lp.height = tv.getHeight();
                lp.leftMargin = loc[0] - padLeft;
                lp.topMargin = loc[1] - padTop;
                overlay.setLayoutParams(lp);
            }
            overlay.invalidate();
        }

        private void removeOverlay() {
            if (overlay != null) {
                ViewParent parent = overlay.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(overlay);
                }
                overlay = null;
            }
        }

        private final View.OnTouchListener handleTouch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                boolean isStart = v == startHandle;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        draggingHandle = isStart ? 1 : 2;
                        hideMenuBubble();
                        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(70L)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (draggingHandle != 0) {
                            adjustByHandle(draggingHandle == 1, event.getRawX(), event.getRawY());
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (draggingHandle != 0) {
                            draggingHandle = 0;
                            v.animate().scaleX(1f).scaleY(1f).setDuration(90L)
                                    .setInterpolator(new DecelerateInterpolator()).start();
                            showMenuBubble();
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        };

        private void showHandles() {
            if (tv.getWindowToken() == null) {
                return;
            }
            if (startHandle == null || endHandle == null) {
                ViewGroup decor = ViewUtils.findDecor(tv);
                if (decor == null) {
                    return;
                }
                int size = ThemeUtils.dp(tv.getContext(), 38);
                startHandle = new SelectionHandle(tv.getContext(), tv, accentColor);
                endHandle = new SelectionHandle(tv.getContext(), tv, accentColor);
                startHandle.setOnTouchListener(handleTouch);
                endHandle.setOnTouchListener(handleTouch);
                decor.addView(startHandle, new FrameLayout.LayoutParams(size, size));
                decor.addView(endHandle, new FrameLayout.LayoutParams(size, size));
            }
            updateHandlePositions();
        }

        private void updateHandlePositions() {
            if (startHandle == null || endHandle == null) {
                return;
            }
            Layout layout = tv.getLayout();
            if (layout == null || tv.getWindowToken() == null || !tv.isShown()) {
                return;
            }
            int[] loc = new int[2];
            tv.getLocationInWindow(loc);
            positionHandle(startHandle, loc, layout, selStart, true);
            positionHandle(endHandle, loc, layout, selEnd, false);
        }

        private float endAnchorX(Layout layout, CharSequence text, int end, int[] outLine) {
            int line = layout.getLineForOffset(end);
            float x = layout.getPrimaryHorizontal(end);
            if (line > 0 && end == layout.getLineStart(line)) {
                line = line - 1;
                x = layout.getLineRight(line);
            } else if (end > 0 && end <= text.length()
                    && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
                x = layout.getLineRight(line);
            }
            outLine[0] = line;
            return x;
        }

        private void positionHandle(SelectionHandle handle, int[] tvLoc, Layout layout,
                                    int offset, boolean isStart) {
            if (offset < 0) {
                offset = 0;
            }
            CharSequence text = tv.getText();
            if (text == null) {
                return;
            }
            int line = layout.getLineForOffset(offset);
            float x;
            if (isStart) {
                x = layout.getPrimaryHorizontal(offset);
            } else {
                int[] out = new int[1];
                x = endAnchorX(layout, text, offset, out);
                line = out[0];
            }
            float baseY = layout.getLineBottom(line);
            float winX = tvLoc[0] + tv.getTotalPaddingLeft() - tv.getScrollX() + x;
            float winY = tvLoc[1] + tv.getTotalPaddingTop() - tv.getScrollY() + baseY;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) handle.getLayoutParams();
            int size = lp.width;
            ViewGroup parent = (ViewGroup) handle.getParent();
            int padLeft = parent != null ? parent.getPaddingLeft() : 0;
            int padTop = parent != null ? parent.getPaddingTop() : 0;
            int left = (int) (winX - padLeft - size / 2f);
            int top = (int) (winY - padTop - density);
            if (lp.leftMargin != left || lp.topMargin != top) {
                lp.leftMargin = left;
                lp.topMargin = top;
                handle.setLayoutParams(lp);
            }
            handle.invalidate();
        }

        private void adjustByHandle(boolean isStart, float rawX, float rawY) {
            Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || text == null) {
                return;
            }
            int[] loc = new int[2];
            tv.getLocationOnScreen(loc);
            int offset = tv.getOffsetForPosition(rawX - loc[0], rawY - loc[1]);
            int len = text.length();
            if (offset < 0) {
                offset = 0;
            }
            if (offset > len) {
                offset = len;
            }
            if (isStart) {
                int maxStart = Math.max(0, selEnd - 1);
                selStart = Math.min(offset, maxStart);
            } else {
                int minEnd = Math.min(len, selStart + 1);
                selEnd = Math.max(offset, minEnd);
            }
            updateHighlight();
            updateHandlePositions();
        }

        private void removeHandles() {
            removeView(startHandle);
            removeView(endHandle);
            startHandle = null;
            endHandle = null;
        }

        private void showMenu() {
            Context context = tv.getContext();
            if (context == null) {
                cancel();
                return;
            }
            removeMenu();
            ViewGroup decor = ViewUtils.findDecor(tv);
            if (decor == null) {
                cancel();
                return;
            }

            FrameLayout scrim = new FrameLayout(context);
            scrim.setClickable(true);
            scrim.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancel();
                }
            });
            scrim.setFocusableInTouchMode(true);
            scrim.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                        cancel();
                        return true;
                    }
                    return false;
                }
            });
            decor.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrim.requestFocus();
            menuScrim = scrim;

            menuBubble = buildMenuBar(context);
            decor.addView(menuBubble, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            updateMenuItemsState();
            positionMenuBubble(false);
            animateMenuIn();

            if (startHandle != null) {
                decor.bringChildToFront(startHandle);
            }
            if (endHandle != null) {
                decor.bringChildToFront(endHandle);
            }
        }

        private void positionMenuBubble(boolean animate) {
            if (menuBubble == null) {
                return;
            }
            Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || text == null) {
                return;
            }
            Context context = tv.getContext();
            ViewGroup parent = (ViewGroup) menuBubble.getParent();
            if (parent == null) {
                return;
            }
            menuBubble.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int w = menuBubble.getMeasuredWidth();
            int h = menuBubble.getMeasuredHeight();

            int padLeft = parent.getPaddingLeft();
            int padTop = parent.getPaddingTop();
            int availRight = padLeft + parent.getWidth() - parent.getPaddingRight();
            int availBottom = padTop + parent.getHeight() - parent.getPaddingBottom();
            int margin = ThemeUtils.dp(context, 4);
            int topLimit = padTop + margin;
            int leftLimit = padLeft + margin;

            int[] loc = new int[2];
            tv.getLocationInWindow(loc);
            float textLeft = loc[0] + tv.getTotalPaddingLeft() - tv.getScrollX();
            float textTop = loc[1] + tv.getTotalPaddingTop() - tv.getScrollY();

            int startLine = layout.getLineForOffset(selStart);
            int[] endLineOut = new int[1];
            float endX = endAnchorX(layout, text, selEnd, endLineOut);
            float selCenterX = textLeft
                    + (layout.getPrimaryHorizontal(selStart) + endX) / 2f;
            int selTop = (int) (textTop + layout.getLineTop(startLine));
            int selBottom = (int) (textTop + layout.getLineBottom(endLineOut[0]));

            int px = (int) (selCenterX - w / 2f);
            int py;
            int above = selTop - h - ThemeUtils.dp(context, 6);
            int below = selBottom + ThemeUtils.dp(context, 6);
            if (above >= topLimit) {
                py = above;
                menuAbove = true;
            } else if (below + h <= availBottom - margin) {
                py = below;
                menuAbove = false;
            } else {
                py = Math.max(topLimit, Math.min(above, availBottom - margin - h));
                menuAbove = py <= selTop;
            }
            if (px < leftLimit) {
                px = leftLimit;
            }
            if (px + w > availRight - margin) {
                px = availRight - margin - w;
            }

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuBubble.getLayoutParams();
            int oldLeft = lp.leftMargin;
            int oldTop = lp.topMargin;
            boolean moved = lp.leftMargin != px || lp.topMargin != py
                    || lp.width != w || lp.height != h;
            lp.width = w;
            lp.height = h;
            lp.leftMargin = px;
            lp.topMargin = py;
            if (moved) {
                menuBubble.setLayoutParams(lp);
            }
            if (animate && moved) {
                menuBubble.setTranslationX(oldLeft - px);
                menuBubble.setTranslationY(oldTop - py);
                menuBubble.animate().translationX(0f).translationY(0f).setDuration(140L)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            } else {
                menuBubble.setTranslationX(0f);
                menuBubble.setTranslationY(0f);
            }
        }

        private void animateMenuIn() {
            if (menuBubble == null) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuBubble.getLayoutParams();
            menuBubble.setPivotX(lp.width / 2f);
            menuBubble.setPivotY(menuAbove ? lp.height : 0f);
            menuBubble.setScaleX(0.86f);
            menuBubble.setScaleY(0.86f);
            menuBubble.setAlpha(0f);
            menuBubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130L)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }

        private void hideMenuBubble() {
            if (menuBubble != null) {
                menuBubble.animate().cancel();
                menuBubble.setVisibility(View.GONE);
            }
        }

        private void showMenuBubble() {
            if (menuBubble != null) {
                updateMenuItemsState();
                menuBubble.setVisibility(View.VISIBLE);
                positionMenuBubble(true);
            }
        }
        private void updateMenuItemsState() {
            if (selectAllItem == null) {
                return;
            }
            CharSequence text = tv.getText();
            boolean canSelectAll = text != null && text.length() > 0
                    && (selStart > 0 || selEnd < text.length());
            if (selectAllItem.getVisibility() != (canSelectAll ? View.VISIBLE : View.GONE)) {
                selectAllItem.setVisibility(canSelectAll ? View.VISIBLE : View.GONE);
                if (selectAllDivider != null) {
                    selectAllDivider.setVisibility(canSelectAll ? View.VISIBLE : View.GONE);
                }
            }
        }

        private void removeMenu() {
            removeView(menuScrim);
            removeView(menuBubble);
            menuScrim = null;
            menuBubble = null;
            selectAllItem = null;
            selectAllDivider = null;
        }

        private void removeView(View view) {
            if (view != null) {
                ViewParent parent = view.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(view);
                }
            }
        }

        private LinearLayout buildMenuBar(Context context) {
            boolean dark = ThemeUtils.isDarkMode(context);
            int bgColor = dark ? 0xFF2A2A2E : 0xFFFFFFFF;
            int textColor = dark ? 0xDEFFFFFF : 0xDD000000;
            int dividerColor = dark ? 0x24FFFFFF : 0x1F000000;
            int rippleColor = dark ? 0x2EFFFFFF : 0x1A000000;

            LinearLayout bar = new LinearLayout(context);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setClickable(true);
            bar.setElevation(ThemeUtils.dp(context, 8));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(bgColor);
            bg.setCornerRadius(ThemeUtils.dp(context, 16));
            bar.setBackground(bg);

            TextView selectAll = menuItem(context, "全选", textColor, rippleColor, dark);
            selectAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doSelectAll();
                }
            });
            bar.addView(selectAll);
            selectAllItem = selectAll;
            selectAllDivider = addDivider(bar, context, dividerColor);

            TextView share = menuItem(context, "分享", textColor, rippleColor, dark);
            share.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doShare();
                }
            });
            bar.addView(share);
            addDivider(bar, context, dividerColor);

            TextView copy = menuItem(context, "复制", textColor, rippleColor, dark);
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doCopy();
                }
            });
            bar.addView(copy);
            return bar;
        }

        private View addDivider(LinearLayout bar, Context context, int color) {
            View divider = new View(context);
            divider.setBackgroundColor(color);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ThemeUtils.dp(context, 1), ThemeUtils.dp(context, 20));
            lp.gravity = Gravity.CENTER_VERTICAL;
            divider.setLayoutParams(lp);
            bar.addView(divider);
            return divider;
        }

        private TextView menuItem(Context context, String label, int textColor,
                                  int rippleColor, boolean dark) {
            TextView item = new TextView(context);
            item.setText(label);
            item.setTextSize(14);
            item.setTextColor(textColor);
            item.setGravity(Gravity.CENTER);
            item.setMinWidth(ThemeUtils.dp(context, 56));
            item.setMinHeight(ThemeUtils.dp(context, 44));
            item.setPadding(ThemeUtils.dp(context, 14), 0, ThemeUtils.dp(context, 14), 0);
            item.setClickable(true);
            item.setFocusable(true);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(ThemeUtils.dp(context, 12));
            item.setForeground(new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask));
            return item;
        }

        private void doSelectAll() {
            CharSequence text = tv.getText();
            if (text == null || text.length() == 0) {
                return;
            }
            selStart = 0;
            selEnd = text.length();
            updateHighlight();
            updateHandlePositions();
            updateMenuItemsState();
            if (menuBubble != null && menuBubble.getVisibility() == View.VISIBLE) {
                positionMenuBubble(true);
            }
        }

        private void doCopy() {
            CharSequence text = tv.getText();
            Context context = tv.getContext();
            if (text == null || selStart < 0 || selEnd <= selStart || selEnd > text.length()) {
                cancel();
                return;
            }
            try {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("BetterHeybox", text.subSequence(selStart, selEnd)));
                }
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Log.w(TAG, "自绘制选择复制失败: " + t);
            }
            cancel();
        }

        private void doShare() {
            CharSequence text = tv.getText();
            Context context = tv.getContext();
            if (text == null || selStart < 0 || selEnd <= selStart || selEnd > text.length()) {
                return;
            }
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text.subSequence(selStart, selEnd).toString());
                Intent chooser = Intent.createChooser(send, null);
                Activity activity = ViewUtils.findActivity(context);
                if (activity != null) {
                    activity.startActivity(chooser);
                } else {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(chooser);
                }
            } catch (Throwable t) {
                Log.w(TAG, "自绘制选择分享失败: " + t);
                Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show();
            }
        }

        void cancel() {
            if (tv.getParent() != null) {
                tv.getParent().requestDisallowInterceptTouchEvent(false);
            }
            selecting = false;
            draggingHandle = 0;
            anchor = -1;
            selStart = -1;
            selEnd = -1;
            try {
                tv.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            } catch (Throwable ignored) {
            }
            removeOverlay();
            removeHandles();
            removeMenu();
        }

        private int[] wordBoundary(int offset) {
            CharSequence text = tv.getText();
            int len = text == null ? 0 : text.length();
            if (len == 0) {
                return new int[]{0, 0};
            }
            int o = offset;
            if (o < 0) {
                o = 0;
            }
            if (o > len) {
                o = len;
            }
            char c = o < len ? text.charAt(o) : text.charAt(len - 1);
            if (Character.isWhitespace(c)) {
                int probe = o;
                while (probe > 0 && Character.isWhitespace(text.charAt(probe - 1))) {
                    probe--;
                }
                if (probe > 0) {
                    return wordBoundary(probe - 1);
                }
                probe = o;
                while (probe < len && Character.isWhitespace(text.charAt(probe))) {
                    probe++;
                }
                if (probe < len) {
                    return wordBoundary(probe);
                }
                return new int[]{Math.max(0, o - 1), Math.min(len, o + 1)};
            }
            boolean cjk = isCjkIdeograph(c);
            int s = o;
            int e = o;
            while (s > 0 && isWordChar(text.charAt(s - 1), cjk)) {
                s--;
            }
            while (e < len && isWordChar(text.charAt(e), cjk)) {
                e++;
            }
            if (s == e) {
                e = Math.min(len, o + 1);
            }
            return new int[]{s, e};
        }

        private static boolean isWordChar(char ch, boolean cjk) {
            if (Character.isWhitespace(ch)) {
                return false;
            }
            if (cjk) {
                return isCjkIdeograph(ch);
            }
            return Character.isLetterOrDigit(ch) || ch == '_' || ch == 0x27;
        }

        private static boolean isCjkIdeograph(char ch) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
        }
    }

    private static final class HighlightOverlay extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path linePath = new Path();
        private final RectF lineRect = new RectF();
        private final WeakReference<TextView> tvRef;
        private final float radius;
        private int start = -1;
        private int end = -1;

        HighlightOverlay(Context context, TextView tv, int accentColor) {
            super(context);
            this.tvRef = new WeakReference<TextView>(tv);
            this.paint.setColor(0x59000000 | (accentColor & 0x00FFFFFF));
            this.paint.setStyle(Paint.Style.FILL);
            this.radius = 3 * context.getResources().getDisplayMetrics().density;
        }

        void setSelection(int start, int end) {
            if (this.start == start && this.end == end) {
                return;
            }
            this.start = start;
            this.end = end;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            TextView tv = tvRef.get();
            if (tv == null || start < 0 || end <= start) {
                return;
            }
            Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || text == null) {
                return;
            }
            int first = layout.getLineForOffset(start);
            int last = layout.getLineForOffset(end);
            float lastRight = layout.getPrimaryHorizontal(end);
            if (last > 0 && end == layout.getLineStart(last)) {
                last--;
                lastRight = layout.getLineRight(last);
            } else if (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
                lastRight = layout.getLineRight(last);
            }
            path.rewind();
            for (int i = first; i <= last; i++) {
                float left = (i == first) ? layout.getPrimaryHorizontal(start) : layout.getLineLeft(i);
                float right = (i == last) ? lastRight : layout.getLineRight(i);
                if (right < left) {
                    float t = left;
                    left = right;
                    right = t;
                }
                if (right - left < radius * 2f) {
                    right = Math.min(layout.getLineRight(i), left + radius * 2f);
                }
                lineRect.set(left, layout.getLineTop(i), right, layout.getLineBottom(i));
                linePath.rewind();
                linePath.addRoundRect(lineRect, radius, radius, Path.Direction.CW);
                if (path.isEmpty()) {
                    path.set(linePath);
                } else {
                    path.op(linePath, Path.Op.UNION);
                }
            }
            if (path.isEmpty()) {
                return;
            }
            canvas.save();
            canvas.translate(
                    tv.getTotalPaddingLeft() - tv.getScrollX(),
                    tv.getTotalPaddingTop() - tv.getScrollY());
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }

    private static final class SelectionHandle extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final WeakReference<TextView> tvRef;
        private final float density;
        private final Path dropPath = new Path();

        SelectionHandle(Context context, TextView tv, int accentColor) {
            super(context);
            this.tvRef = new WeakReference<TextView>(tv);
            this.density = context.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accentColor);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            paint.setShadowLayer(1.5f * density, 0f, density, 0x30000000);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rebuildDrop(w, h);
            setPivotX(w / 2f);
            setPivotY(0f);
        }
        
        private void rebuildDrop(int w, int h) {
            if (w <= 0 || h <= 0) {
                return;
            }
            float r = w * 0.30f;
            float cx = w / 2f;
            float tipGap = 1f * density;
            float neckGap = 3f * density;
            float tipY = tipGap;
            float cy = tipY + neckGap + r;
            dropPath.reset();
            dropPath.addCircle(cx, cy, r, Path.Direction.CW);
            Path tip = new Path();
            tip.moveTo(cx, tipY);
            float baseY = cy - r * 0.30f;
            tip.lineTo(cx - r * 0.62f, baseY);
            tip.lineTo(cx + r * 0.62f, baseY);
            tip.close();
            dropPath.op(tip, Path.Op.UNION);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            TextView tv = tvRef.get();
            if (tv == null || dropPath.isEmpty()) {
                return;
            }
            canvas.drawPath(dropPath, paint);
        }
    }
}
