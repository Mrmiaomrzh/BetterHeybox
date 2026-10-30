package com.better.heybox

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object DexKitResolver {

    private val TAG = MainModule.TAG
    private const val DIALOG_ANCHOR_STRING = "HeyBoxDialog.RainbowPositiveButtonLine"
    private const val CACHE_NS = "dialog"
    private const val PROBE_TEXT_PREFIX = "BH_PROBE_"

    class HeyboxDialogSpec internal constructor(
        internal val builderCtor: Constructor<*>,
        internal val setTitle: Method,
        internal val setCenterView: Method,
        internal val setPositiveButton: Method,
        internal val setNegativeButton: Method,
        internal val buildMethod: Method
    ) {

        @Volatile
        internal var fromCache = false

        @Throws(Exception::class)
        fun buildAndShow(
            ctx: Context, title: CharSequence?, content: View?,
            posText: CharSequence?, posListener: DialogInterface.OnClickListener?,
            negText: CharSequence?, negListener: DialogInterface.OnClickListener?
        ): Dialog {
            val d = try {
                val builder = builderCtor.newInstance(ctx)
                if (title != null) {
                    setTitle.invoke(builder, title)
                }
                if (content != null) {
                    setCenterView.invoke(builder, content)
                }
                if (posText != null) {
                    setPositiveButton.invoke(builder, posText, posListener)
                }
                if (negText != null) {
                    setNegativeButton.invoke(builder, negText, negListener)
                }
                val dialog = buildMethod.invoke(builder)
                if (dialog !is Dialog) {
                    throw IllegalStateException("builder 未返回 Dialog 实例")
                }
                dialog
            } catch (t: Throwable) {

                if (fromCache) {
                    fromCache = false
                    Log.w(TAG, "HeyBoxDialog 缓存构建失败: $t")
                    DexKit.drop(ctx, CACHE_NS)
                }
                throw t
            }
            if (!d.isShowing) {
                d.show()
            }
            return d
        }
    }

    interface SpecCallback {
        fun onReady(spec: HeyboxDialogSpec)
        fun onFailed(reason: String)
    }

    private val LOCK = Any()
    private var sExecutor: ExecutorService? = null

    @Volatile
    private var sGeneration = 0L

    private val MAIN = Handler(Looper.getMainLooper())

    private fun executor(): ExecutorService = synchronized(LOCK) {
        val current = sExecutor
        if (current == null || current.isShutdown || current.isTerminated) {
            Executors.newSingleThreadExecutor().also { sExecutor = it }
        } else {
            current
        }
    }

    @JvmStatic
    fun shutdown() {
        MAIN.removeCallbacksAndMessages(null)
        val executor = synchronized(LOCK) {
            sGeneration++
            sExecutor.also { sExecutor = null }
        }
        try {
            executor?.shutdownNow()
        } catch (ignored: Throwable) {
        }
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(LOCK) {
        sGeneration == generation
    }

    @JvmStatic
    fun getHeyboxDialogSpec(module: MainModule, activity: Activity, cb: SpecCallback) {
        val cl = activity.classLoader
        val cached = readCache(activity, cl)
        if (cached != null) {
            cached.fromCache = true
            module.logd(Log.INFO, TAG, "✔ HeyBoxDialog 解析命中缓存")
            cb.onReady(cached)
            return
        }
        val generation = synchronized(LOCK) { sGeneration }
        executor().execute {
            if (!isCurrent(generation)) {
                return@execute
            }
            val a = try {
                analyzeWithDexKit(module, cl, activity)
            } catch (t: Throwable) {
                module.logd(Log.WARN, TAG, "DexKit 分析异常: $t")
                null
            }
            val analysis = a
            if (!isCurrent(generation)) {
                return@execute
            }
            MAIN.post {
                if (!isCurrent(generation)) {
                    return@post
                }
                if (activity.isFinishing || activity.isDestroyed) {
                    cb.onFailed("activity 已销毁")
                    return@post
                }
                if (analysis == null) {
                    cb.onFailed("DexKit 未定位到 HeyBoxDialog")
                    return@post
                }
                try {
                    probeAndClassify(module, activity, analysis, cb)
                } catch (t: Throwable) {
                    module.logd(Log.WARN, TAG, "HeyBoxDialog 探针异常: $t")
                    cb.onFailed("探针异常: $t")
                }
            }
        }
    }

    private class Analysis {
        var dialogClass: Class<*>? = null
        var builderClass: Class<*>? = null
        var builderCtor: Constructor<*>? = null
        var charSeqCands: MutableList<Method> = ArrayList()
        var viewCands: MutableList<Method> = ArrayList()
        var buttonCands: MutableList<Method> = ArrayList()
        var buildCands: MutableList<Method> = ArrayList()
    }

    private fun analyzeWithDexKit(
        module: MainModule, cl: ClassLoader, activity: Activity
    ): Analysis? {
        val dialogClass = findDialogClassByAnchor(module, cl, activity) ?: return null
        val builderClass = findBuilderClass(dialogClass)
        if (builderClass == null) {
            module.logd(
                Log.WARN, TAG,
                "HeyBoxDialog 已定位但未找到 Builder 形态的内部类: " + dialogClass.name
            )
            return null
        }
        val a = Analysis()
        a.dialogClass = dialogClass
        a.builderClass = builderClass
        a.builderCtor = try {
            builderClass.getConstructor(Context::class.java)
        } catch (e: NoSuchMethodException) {
            module.logd(Log.WARN, TAG, "Builder 缺少 (Context) 构造器: " + builderClass.name)
            return null
        }
        for (m in builderClass.declaredMethods) {
            if (!Modifier.isPublic(m.modifiers)) {
                continue
            }
            when (classifyBuilderMethod(m, builderClass, dialogClass)) {
                1 -> a.charSeqCands.add(m)
                2 -> a.viewCands.add(m)
                3 -> a.buttonCands.add(m)
                4 -> a.buildCands.add(m)
            }
        }
        if (a.charSeqCands.isEmpty() || a.viewCands.isEmpty() ||
            a.buttonCands.isEmpty() || a.buildCands.isEmpty()
        ) {
            module.logd(
                Log.WARN, TAG,
                "Builder 方法族不完整: charSeq=" + a.charSeqCands.size +
                        " view=" + a.viewCands.size +
                        " button=" + a.buttonCands.size +
                        " build=" + a.buildCands.size
            )
            return null
        }
        return a
    }

    private fun findDialogClassByAnchor(
        module: MainModule, cl: ClassLoader, activity: Activity
    ): Class<*>? {
        val path = DexKit.apkPath(activity, cl)
        if (path == null) {
            module.logd(Log.WARN, TAG, "DexKit 跳过: 取不到小黑盒 APK 路径")
            return null
        }
        try {
            return DexKit.scan(path, cl) { scanner ->
                scanner.classesUsingStrings(listOf(DIALOG_ANCHOR_STRING), emptyArray())
                    .firstOrNull { Dialog::class.java.isAssignableFrom(it) }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, TAG, "DexKit 扫描失败: $t")
        }
        return null
    }

    private fun classifyBuilderMethod(m: Method, builderClass: Class<*>, dialogClass: Class<*>): Int {
        val ps = m.parameterTypes
        if (m.returnType == builderClass) {
            if (ps.size == 1 && ps[0] == CharSequence::class.java) {
                return 1
            }
            if (ps.size == 1 && ps[0] == View::class.java) {
                return 2
            }
            if (ps.size == 2 && ps[0] == CharSequence::class.java &&
                ps[1] == DialogInterface.OnClickListener::class.java
            ) {
                return 3
            }
        } else if (ps.size == 0 && m.returnType == dialogClass) {
            return 4
        }
        return 0
    }

    private fun findBuilderClass(dialogClass: Class<*>): Class<*>? {
        for (inner in dialogClass.declaredClasses) {
            if (!Modifier.isStatic(inner.modifiers) || Modifier.isInterface(inner.modifiers)) {
                continue
            }
            try {
                inner.getConstructor(Context::class.java)
            } catch (e: NoSuchMethodException) {
                continue
            }
            var charSeq = 0
            var view = 0
            var button = 0
            var build = 0
            for (m in inner.declaredMethods) {
                when (classifyBuilderMethod(m, inner, dialogClass)) {
                    1 -> charSeq++
                    2 -> view++
                    3 -> button++
                    4 -> build++
                }
            }
            if (charSeq >= 2 && view >= 2 && button >= 2 && build >= 1) {
                return inner
            }
        }
        return null
    }

    private class RoundResult {
        var title: Method? = null
        var center: Method? = null
        var positive: Method? = null
        var negative: Method? = null
        var buildUsed: Method? = null
        var replacer: Method? = null
    }

    private fun probeAndClassify(
        module: MainModule, activity: Activity, a: Analysis, cb: SpecCallback
    ) {
        probeRound(module, activity, a, 0, cb)
    }

    private fun probeRound(
        module: MainModule, activity: Activity, a: Analysis,
        round: Int, cb: SpecCallback
    ) {
        if (round >= 3) {
            cb.onFailed("探针超过最大轮数仍未分类完成")
            return
        }
        probeOnce(
            activity, a,
            ArrayList(a.charSeqCands), ArrayList(a.viewCands), ArrayList(a.buttonCands)
        ) { r ->
            module.logd(
                Log.INFO, TAG,
                "探针第${round}轮: title=" + name(r.title) +
                        " center=" + name(r.center) + " pos=" + name(r.positive) +
                        " neg=" + name(r.negative) + " replacer=" + name(r.replacer) +
                        " build=" + name(r.buildUsed)
            )
            if (r.replacer != null) {
                a.viewCands.remove(r.replacer)
                probeRound(module, activity, a, round + 1, cb)
                return@probeOnce
            }
            if (r.title == null || r.center == null || r.positive == null ||
                r.negative == null || r.buildUsed == null
            ) {
                cb.onFailed(
                    "探针未能分类 Builder 方法（" +
                            (if (r.title == null) "title " else "") +
                            (if (r.center == null) "center " else "") +
                            (if (r.positive == null) "pos " else "") +
                            (if (r.negative == null) "neg" else "") + "缺位）"
                )
                return@probeOnce
            }
            val spec = HeyboxDialogSpec(
                a.builderCtor!!, r.title!!, r.center!!, r.positive!!, r.negative!!, r.buildUsed!!
            )
            writeCache(activity, a, spec)
            module.logd(
                Log.INFO, TAG,
                "✔ HeyBoxDialog 自动解析完成: dialog=" + a.dialogClass!!.name +
                        " builder=" + a.builderClass!!.name +
                        " title=" + name(spec.setTitle) + " view=" + name(spec.setCenterView) +
                        " pos=" + name(spec.setPositiveButton) +
                        " neg=" + name(spec.setNegativeButton) +
                        " build=" + name(spec.buildMethod)
            )
            cb.onReady(spec)
        }
    }

    private fun name(m: Method?): String = m?.name ?: "null"

    private fun interface RoundCallback {
        fun onRound(r: RoundResult)
    }

    private fun probeOnce(
        activity: Activity, a: Analysis,
        charSeq: List<Method>, view: List<Method>, button: List<Method>,
        done: RoundCallback
    ) {
        val r = RoundResult()
        val textByMethod = HashMap<String, Method>()
        val buttonByText = HashMap<String, Method>()
        var dialog: Dialog? = null
        var decor: View? = null
        try {
            val builder = a.builderCtor!!.newInstance(activity)

            for (i in charSeq.indices) {
                val text = PROBE_TEXT_PREFIX + "C" + i
                textByMethod[text] = charSeq[i]
                charSeq[i].invoke(builder, text)
            }
            for (i in view.indices) {
                val text = PROBE_TEXT_PREFIX + "V" + i
                val marker = TextView(activity)
                marker.text = text
                view[i].invoke(builder, marker)
            }
            for (i in button.indices) {
                val text = PROBE_TEXT_PREFIX + "B" + i
                buttonByText[text] = button[i]
                button[i].invoke(builder, text, DialogInterface.OnClickListener { _, _ -> })
            }
            for (c in a.buildCands) {
                try {
                    val d = c.invoke(builder)
                    if (d is Dialog) {
                        dialog = d
                        r.buildUsed = c
                        break
                    }
                } catch (ignored: Throwable) {
                }
            }
            if (dialog != null) {
                val w: Window? = dialog.window
                if (w != null) {
                    val wp = w.attributes
                    wp.windowAnimations = 0
                    wp.alpha = 0f
                    wp.dimAmount = 0f
                    w.attributes = wp
                    decor = w.decorView
                }
                if (!dialog.isShowing) {
                    dialog.show()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "probeOnce 构建异常: $t")
        }
        if (dialog == null || decor == null) {
            if (dialog != null) {
                try {
                    dialog.dismiss()
                } catch (ignored: Throwable) {
                }
            }
            done.onRound(r)
            return
        }
        val probeDialog = dialog
        val capturedDecor = decor
        capturedDecor.postDelayed({
            try {
                classify(capturedDecor, view, textByMethod, buttonByText, r)
            } catch (t: Throwable) {
                Log.w(TAG, "classify 异常: $t")
            }
            try {
                probeDialog.dismiss()
            } catch (ignored: Throwable) {
            }
            done.onRound(r)
        }, 50L)
    }

    private fun classify(
        decor: View, viewCands: List<Method>,
        textByMethod: Map<String, Method>,
        buttonByText: Map<String, Method>, r: RoundResult
    ) {
        val all = ArrayList<TextView>()
        collectTextViews(decor, all)
        val rendered = HashMap<String, TextView>()
        for (tv in all) {
            val cs = tv.text
            if (cs != null && textByMethod.containsKey(cs.toString())) {
                rendered[cs.toString()] = tv
            }
        }
        var titleCandidate: Method? = null
        var titleView: TextView? = null
        for (e in rendered.entries) {
            if (titleView == null || screenY(e.value) < screenY(titleView!!)) {
                titleView = e.value
                titleCandidate = textByMethod[e.key]
            }
        }
        if (titleView != null) {
            r.title = titleCandidate
        }
        for (tv in all) {
            val cs = tv.text
            if (cs == null || !cs.toString().startsWith(PROBE_TEXT_PREFIX + "V")) {
                continue
            }
            val idx = try {
                cs.toString().substring((PROBE_TEXT_PREFIX + "V").length).toInt()
            } catch (ignored: NumberFormatException) {
                continue
            }
            if (idx < 0 || idx >= viewCands.size) {
                continue
            }
            val cand = viewCands[idx]
            if (titleView == null) {
                r.replacer = cand
            } else if (screenY(tv) > screenY(titleView)) {
                r.center = cand
            }
        }
        var posView: TextView? = null
        var negView: TextView? = null
        var posCandidate: Method? = null
        var negCandidate: Method? = null
        for (tv in all) {
            val cs = tv.text ?: continue
            val cand = buttonByText[cs.toString()] ?: continue
            if (posView == null || screenX(tv) < screenX(posView)) {
                if (posView != null) {
                    negView = posView
                    negCandidate = posCandidate
                }
                posView = tv
                posCandidate = cand
            } else if (negView == null || screenX(tv) < screenX(negView)) {
                negView = tv
                negCandidate = cand
            }
        }
        if (posView != null && negView != null) {
            r.positive = posCandidate
            r.negative = negCandidate
        }
    }

    private fun screenY(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[1]
    }

    private fun screenX(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[0]
    }

    private fun collectTextViews(root: View, out: MutableList<TextView>) {
        if (root is TextView) {
            out.add(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectTextViews(root.getChildAt(i), out)
            }
        }
    }

    private fun writeCache(ctx: Context, a: Analysis, s: HeyboxDialogSpec) {
        val v = a.dialogClass!!.name + ";" + a.builderClass!!.name + ";" +
                s.setTitle.name + ";" + s.setCenterView.name + ";" +
                s.setPositiveButton.name + ";" + s.setNegativeButton.name + ";" +
                s.buildMethod.name
        DexKit.write(ctx, CACHE_NS, v)
    }

    private fun readCache(ctx: Context, cl: ClassLoader): HeyboxDialogSpec? {
        return try {
            val v = DexKit.read(ctx, CACHE_NS)
            if (v.isNullOrEmpty()) {
                return null
            }
            val p = v.split(";")
            if (p.size != 7) {
                return null
            }
            val dialogClass = Class.forName(p[0], false, cl)
            val builderClass = Class.forName(p[1], false, cl)
            val ctor = builderClass.getConstructor(Context::class.java)
            val title = builderClass.getDeclaredMethod(p[2], CharSequence::class.java)
            val view = builderClass.getDeclaredMethod(p[3], View::class.java)
            val pos = builderClass.getDeclaredMethod(
                p[4], CharSequence::class.java, DialogInterface.OnClickListener::class.java
            )
            val neg = builderClass.getDeclaredMethod(
                p[5], CharSequence::class.java, DialogInterface.OnClickListener::class.java
            )
            val build = builderClass.getDeclaredMethod(p[6])
            if (title.returnType != builderClass || view.returnType != builderClass ||
                pos.returnType != builderClass || neg.returnType != builderClass ||
                !dialogClass.isAssignableFrom(build.returnType)
            ) {
                return null
            }
            HeyboxDialogSpec(ctor, title, view, pos, neg, build)
        } catch (t: Throwable) {
            null
        }
    }
}
