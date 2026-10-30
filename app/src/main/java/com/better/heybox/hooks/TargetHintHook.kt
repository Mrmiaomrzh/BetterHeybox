package com.better.heybox.hooks

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

class TargetHintHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        try {
            val activityCls = Class.forName("android.app.Activity", false, cl)
            module.hook(activityCls.getDeclaredMethod("onResume")).intercept { chain ->
                val result = chain.proceed()
                try {
                    val activity = chain.instanceOrNull as? Activity
                    if (activity != null && !activity.isFinishing) {
                        attach(activity, cl)
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "提示信息挂载失败: $t")
                }
                result
            }
            hookUidCapture(cl)
            module.logd(Log.INFO, MainModule.TAG, "✔ 提示信息 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 提示信息 Hook 安装失败", t)
        }
    }

    private fun attach(activity: Activity, cl: ClassLoader) {
        val decor = activity.window.decorView as ViewGroup
        if (decor.findViewWithTag<View>(TAG_OVERLAY) != null) {
            return
        }
        val view = HintView(activity, null, sUidCache)
        view.tag = TAG_OVERLAY
        decor.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        if (sUidCache == null && sResolving.compareAndSet(false, true)) {
            val target = view
            Thread({
                val uid = resolveUid(cl)
                if (uid != null) {
                    target.post {
                        target.setHiddenText(uid)
                        target.invalidate()
                    }
                }
            }, "bhb-uid-resolve").start()
        }
    }

    private fun resolveUid(cl: ClassLoader): String? {
        sUidCache?.let { return it }
        try {
            val userCls = Class.forName("com.max.xiaoheihe.bean.account.User", false, cl)
            val detailCls =
                Class.forName("com.max.xiaoheihe.bean.account.AccountDetailObj", false, cl)
            val util = Class.forName("com.max.xiaoheihe.utils.u0", false, cl)
            for (m in util.declaredMethods) {
                if (m.parameterTypes.size != 0 || !Modifier.isStatic(m.modifiers) ||
                    !userCls.equals(m.returnType)
                ) {
                    continue
                }
                try {
                    val user = m.invoke(null) ?: continue
                    val login = userCls.getMethod("isLoginFlag")
                    if (!(login.invoke(user) as Boolean)) {
                        continue
                    }
                    val detail = userCls.getMethod("getAccount_detail").invoke(user) ?: continue
                    val uid = detailCls.getMethod("getUserid").invoke(detail)
                    if (uid is String && uid.isNotEmpty()) {
                        sUidCache = uid
                        return sUidCache
                    }
                } catch (ignored: Throwable) {
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "反射读取部分数据失败: $t")
        }
        return sCapturedUid
    }

    private fun hookUidCapture(cl: ClassLoader) {
        try {
            val x0 = Class.forName("com.max.xiaoheihe.utils.x0", false, cl)
            val extract = x0.getDeclaredMethod("U", String::class.java, String::class.java)
            module.hook(extract).intercept { chain ->
                val result = chain.proceed()
                try {
                    val key = chain.args[1]
                    if ("heybox_id" == key && result is String && result.isNotEmpty() &&
                        result != "-1"
                    ) {
                        sCapturedUid = result
                    }
                } catch (ignored: Throwable) {
                }
                result
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "提示信息被动 Hook 未安装: $t")
        }
    }

    private class HintView(context: Context, visibleText: String?, hiddenText: String?) :
        View(context) {

        private val mVisiblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mHiddenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mVisibleText: String? = visibleText
        @Volatile private var mHiddenText: String? = hiddenText
        private val mDensity = context.resources.displayMetrics.density

        fun setHiddenText(text: String?) {
            mHiddenText = text
        }

        private fun dp(v: Float): Float = v * mDensity

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width
            val h = height
            if (w == 0 || h == 0) {
                return
            }
            canvas.save()
            canvas.rotate(ROTATION_DEG, w / 2f, h / 2f)
            val half = hypot(w.toDouble(), h.toDouble()).toFloat() / 2f
            mVisibleText?.let {
                drawLayer(canvas, w / 2f, h / 2f, half, it, mVisiblePaint, true)
            }
            mHiddenText?.let {
                drawLayer(canvas, w / 2f, h / 2f, half, it, mHiddenPaint, false)
            }
            canvas.restore()
        }

        private fun drawLayer(
            canvas: Canvas, cx: Float, cy: Float, half: Float,
            text: String, paint: Paint, staggered: Boolean
        ) {
            val stepX = paint.measureText(text) + dp(80f)
            val stepY = dp(90f)
            var row = 0
            var y = cy - half
            while (y < cy + half) {
                val offset = if (staggered && (row and 1) == 1) stepX / 2f else 0f
                var x = cx - half + offset
                while (x < cx + half) {
                    canvas.drawText(text, x, y, paint)
                    x += stepX
                }
                y += stepY
                row++
            }
        }

        companion object {
            private const val VISIBLE_ALPHA = 36
            private const val HIDDEN_ALPHA = 3
            private const val ROTATION_DEG = -25f
        }
    }

    companion object {
        private const val TAG_OVERLAY = "betterheybox_target_hint"
        private const val VISIBLE_TEXT = "免费模块 请勿在小黑盒宣传"

        @Volatile private var sUidCache: String? = null
        @Volatile private var sCapturedUid: String? = null
        private val sResolving = AtomicBoolean(false)

        @JvmStatic
        fun createVisibleHint(context: Context): View =
            HintView(context, VISIBLE_TEXT, null)
    }
}
