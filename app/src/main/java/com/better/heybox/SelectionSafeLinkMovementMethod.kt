package com.better.heybox

import android.text.method.LinkMovementMethod
import android.text.Spannable
import android.view.MotionEvent
import android.widget.TextView

class SelectionSafeLinkMovementMethod : LinkMovementMethod() {

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean =
        super.onTouchEvent(widget, buffer, event)

    companion object {
        @Volatile
        private var sInstance: SelectionSafeLinkMovementMethod? = null

        @JvmStatic
        fun getInstance(): SelectionSafeLinkMovementMethod =
            sInstance ?: SelectionSafeLinkMovementMethod().also { sInstance = it }
    }
}
