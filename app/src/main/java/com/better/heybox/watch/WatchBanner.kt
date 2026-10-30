package com.better.heybox.watch

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Deque

object WatchBanner {

    private const val AUTO_DISMISS_MS = 3500L
    private const val MAX_QUEUE = 3
    private val LOCK = Any()

    private class Pending(
        a: Activity,
        val title: String,
        val sub: String?,
        val onClick: Runnable?
    ) {
        val activity: WeakReference<Activity> = WeakReference(a)
    }

    private val QUEUE: Deque<Pending> = ArrayDeque()
    private var sShowing = false
    private var sCurrent: WeakReference<View?> = WeakReference(null)

    @JvmStatic
    fun show(activity: Activity?, item: WatchItem?, onClick: Runnable?) {
        if (activity == null || item == null) {
            return
        }
        enqueue(activity, "🔔 " + item.displayTitle(), item.displayText().replace('\n', ' '), onClick)
    }

    @JvmStatic
    fun showSummary(activity: Activity?, title: String, sub: String?, onClick: Runnable?) {
        if (activity == null) {
            return
        }
        enqueue(activity, title, sub, onClick)
    }

    private fun enqueue(activity: Activity, title: String, sub: String?, onClick: Runnable?) {
        synchronized(LOCK) {
            if (QUEUE.size >= MAX_QUEUE) {
                QUEUE.pollFirst()
            }
            QUEUE.addLast(Pending(activity, title, sub, onClick))
        }
        pump()
    }

    private fun pump() {
        val next = synchronized(LOCK) {
            if (sShowing) {
                return
            }
            var n: Pending? = null
            while (!QUEUE.isEmpty()) {
                val p = QUEUE.pollFirst()
                if (p.activity.get() != null) {
                    n = p
                    break
                }
            }
            if (n == null) {
                return
            }
            sShowing = true
            n
        }
        val p = next
        val activity = p.activity.get()
        if (activity == null) {
            finishOne()
            return
        }
        activity.runOnUiThread {
            try {
                showInternal(activity, p)
            } catch (t: Throwable) {
                finishOne()
            }
        }
    }

    private fun finishOne() {
        synchronized(LOCK) {
            sShowing = false
        }
        pump()
    }

    private fun showInternal(activity: Activity, p: Pending) {
        val root: ViewGroup? = activity.findViewById(android.R.id.content)
        if (root == null) {
            finishOne()
            return
        }
        val pad = dp(activity, 12)
        val card = LinearLayout(activity)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setPadding(pad * 2, pad, pad * 2, pad)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2202124"))
        bg.setCornerRadius(dp(activity, 14).toFloat())
        bg.setStroke(dp(activity, 1), Color.parseColor("#33FFFFFF"))
        card.setBackground(bg)
        card.setElevation(dp(activity, 6).toFloat())

        val title = TextView(activity)
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        title.setMaxLines(2)
        title.setText(p.title)
        card.addView(
            title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val sub0 = p.sub
        if (!sub0.isNullOrEmpty()) {
            val sub = TextView(activity)
            sub.setTextColor(Color.parseColor("#B3FFFFFF"))
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            sub.setMaxLines(2)
            sub.setText(sub0)
            val subLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            subLp.topMargin = dp(activity, 2)
            card.addView(sub, subLp)
        }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP
        lp.setMargins(pad, dp(activity, 24), pad, 0)
        card.setLayoutParams(lp)
        card.setAlpha(0f)
        card.setTranslationY(-dp(activity, 24).toFloat())

        card.setOnClickListener {
            dismiss(card)
            val cb = p.onClick
            if (cb != null) {
                try {
                    cb.run()
                } catch (ignored: Throwable) {
                }
            }
        }

        root.addView(card)
        sCurrent = WeakReference(card)
        card.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        Handler(Looper.getMainLooper()).postDelayed({
            if (sCurrent.get() === card) {
                dismiss(card)
            } else if (sCurrent.get() == null) {
                finishOne()
            }
        }, AUTO_DISMISS_MS)
    }

    private fun dismiss(v: View?) {
        if (v == null) {
            return
        }
        sCurrent = WeakReference(null)
        v.post {
            try {
                v.animate().alpha(0f).translationY(-v.height.toFloat()).setDuration(160L)
                    .withEndAction {
                        detach(v)
                        finishOne()
                    }.start()
            } catch (t: Throwable) {
                detach(v)
                finishOne()
            }
        }
    }

    private fun detach(v: View) {
        try {
            val parent = v.parent as? ViewGroup
            if (parent != null) {
                parent.removeView(v)
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(LOCK) {
            QUEUE.clear()
        }
        val v = sCurrent.get()
        if (v != null) {
            dismiss(v)
        } else {
            finishOne()
        }
    }

    private fun dp(a: Activity, v: Int): Int {
        return (v * a.resources.displayMetrics.density + 0.5f).toInt()
    }
}
