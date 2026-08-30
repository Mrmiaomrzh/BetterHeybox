package com.better.heybox;

import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * 透明 LinkMovementMethod：不干预事件与 Selection。正文保留 @提及点击跳转，长按选择交给系统原生处理
 */
public final class SelectionSafeLinkMovementMethod extends LinkMovementMethod {

    private static SelectionSafeLinkMovementMethod sInstance;

    public static SelectionSafeLinkMovementMethod getInstance() {
        if (sInstance == null) {
            sInstance = new SelectionSafeLinkMovementMethod();
        }
        return sInstance;
    }

    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        return super.onTouchEvent(widget, buffer, event);
    }
}
