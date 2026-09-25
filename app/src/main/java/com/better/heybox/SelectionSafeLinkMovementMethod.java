package com.better.heybox;

import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.widget.TextView;

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
