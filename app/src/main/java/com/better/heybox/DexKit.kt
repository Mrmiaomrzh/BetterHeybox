package com.better.heybox

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.better.heybox.hooks.SettingsEntryHook
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher

   
                                   
  
                                                         
                                                                
                                                
                         
   
object DexKit {

    private val TAG = MainModule.TAG
    private const val KEY_PREFIX = "dexkit."

                          
    private const val LEGACY_PREFS_PREFIX = "targets_"
    private const val LEGACY_FILE = "bh_dexkit_dialog_cache.txt"

    private val LOCK = Any()

    @Volatile
    private var sNativeLoaded = false

    @Volatile
    private var sLegacyCleaned = false

                                                
    @JvmStatic
    fun fingerprint(context: Context?): String? {
        val ctx = context ?: App.resolveAppContext() ?: return null
        val fp = VersionUtils.getHeyboxFingerprint(ctx)
        if (fp != null) {
            return fp
        }
        val code = VersionUtils.getHeyboxVersionCode(ctx)
        return if (code > 0) code.toString() else null
    }

    @JvmStatic
    fun read(context: Context?, ns: String): String? {
        val fp = fingerprint(context) ?: return null
        val raw = prefs()?.getString(KEY_PREFIX + ns, null) ?: return null
        return decode(raw, fp)
    }

    @JvmStatic
    fun write(context: Context?, ns: String, payload: String) {
        val fp = fingerprint(context) ?: return
        val prefs = prefs() ?: return
        prefs.edit().putString(KEY_PREFIX + ns, encode(fp, payload)).apply()
    }

    @JvmStatic
    fun drop(context: Context?, ns: String) {
        val prefs = prefs() ?: return
        if (prefs.contains(KEY_PREFIX + ns)) {
            prefs.edit().remove(KEY_PREFIX + ns).apply()
            Log.w(TAG, "DexKit 缓存 [$ns] 已作废，下次重新解析")
        }
    }

    @JvmStatic
    @JvmName("encode")
    internal fun encode(fingerprint: String, payload: String): String = fingerprint + "\n" + payload

                      
    @JvmStatic
    @JvmName("decode")
    internal fun decode(raw: String?, fingerprint: String): String? {
        if (raw.isNullOrEmpty()) {
            return null
        }
        val nl = raw.indexOf('\n')
        val head = if (nl < 0) raw else raw.substring(0, nl)
        if (head != fingerprint) {
            return null
        }
        return if (nl < 0) "" else raw.substring(nl + 1)
    }

                                                                          
    @JvmStatic
    fun apkPath(context: Context?, cl: ClassLoader?): String? {
        try {
            val ctx = context ?: App.resolveAppContext() ?: App.getAppContext()
            val dir = ctx?.packageManager?.getApplicationInfo(MainModule.TARGET_PKG, 0)?.sourceDir
            if (dir != null && File(dir).exists()) {
                return dir
            }
        } catch (ignored: Throwable) {
        }
        if (cl != null) {
            for (probe in HOST_PROBE_CLASSES) {
                try {
                    val p = Class.forName(probe, false, cl).protectionDomain?.codeSource?.location?.path
                        ?: continue
                    if (p.endsWith(".apk") && File(p).exists()) {
                        return p
                    }
                    val i = p.lastIndexOf('/')
                    if (p.endsWith(".dex") && i > 0) {
                        val apk = p.substring(0, i) + "/base.apk"
                        if (File(apk).exists()) {
                            return apk
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
        }
        val guess = "/data/app/" + MainModule.TARGET_PKG + "/base.apk"
        return if (File(guess).exists()) guess else null
    }

                                                          
    @JvmStatic
    @Throws(Throwable::class)
    fun <T> scan(apkPath: String, cl: ClassLoader, block: (Scanner) -> T): T {
        loadNative()
        val bridge = DexKitBridge.create(apkPath)
        try {
            return block(Scanner(bridge, cl))
        } finally {
            try {
                bridge.close()
            } catch (ignored: Throwable) {
            }
        }
    }

                                                        
    class Scanner internal constructor(
        private val bridge: DexKitBridge,
        private val cl: ClassLoader
    ) {
        fun setThreads(n: Int) {
            try {
                bridge.setThreadNum(n)
            } catch (ignored: Throwable) {
            }
        }

                                                       
        fun methodsUsingStrings(
            strings: List<String>, minParams: Int, maxParams: Int, packages: Array<String>
        ): List<Method> {
            val matcher = MethodMatcher.create()
                .usingStrings(strings)
                .paramCount(minParams, maxParams)
            val out = ArrayList<Method>()
            for (data in bridge.findMethod(FindMethod.create().matcher(matcher).searchPackages(*packages))) {
                try {
                    val m = data.getMethodInstance(cl)
                    if (Modifier.isAbstract(m.modifiers)) {
                        continue
                    }
                    m.isAccessible = true
                    out.add(m)
                } catch (ignored: Throwable) {
                }
            }
            return out
        }

                                                 
        fun classesUsingStrings(strings: List<String>, packages: Array<String>): List<Class<*>> {
            var query = FindClass.create().matcher(ClassMatcher.create().usingStrings(strings))
            if (packages.isNotEmpty()) {
                query = query.searchPackages(*packages)
            }
            val out = ArrayList<Class<*>>()
            for (data in bridge.findClass(query)) {
                try {
                    out.add(data.getInstance(cl))
                } catch (ignored: Throwable) {
                }
            }
            return out
        }

        fun subclassNames(superName: String): List<String> =
            bridge.findClass(FindClass.create().matcher(ClassMatcher.create().superClass(superName)))
                .mapNotNull { it.name }

        fun classNamesIn(pkg: String): List<String> =
            bridge.findClass(FindClass.create().searchPackages(pkg)).mapNotNull { it.name }
    }

    private fun loadNative() {
        if (sNativeLoaded) {
            return
        }
        synchronized(LOCK) {
            if (!sNativeLoaded) {
                System.loadLibrary("dexkit")
                sNativeLoaded = true
            }
        }
    }

    private fun prefs(): android.content.SharedPreferences? {
        val prefs = HeyboxPrefs.get() ?: return null
        if (!sLegacyCleaned) {
            synchronized(LOCK) {
                if (!sLegacyCleaned) {
                    sLegacyCleaned = true
                    cleanLegacy(prefs)
                }
            }
        }
        return prefs
    }

    private fun cleanLegacy(prefs: android.content.SharedPreferences) {
        try {
            val stale = prefs.all.keys.filter { it.startsWith(LEGACY_PREFS_PREFIX) }
            if (stale.isNotEmpty()) {
                val editor = prefs.edit()
                for (k in stale) {
                    editor.remove(k)
                }
                editor.apply()
            }
            val ctx = App.resolveAppContext() ?: App.getAppContext()
            if (ctx != null) {
                File(ctx.filesDir, LEGACY_FILE).delete()
            }
        } catch (ignored: Throwable) {
        }
    }

    private val HOST_PROBE_CLASSES = arrayOf(
        "com.max.xiaoheihe.app.HeyBoxApplication",
        "com.max.hbcommon.base.BaseActivity",
        "com.max.xiaoheihe.MainActivity"
    )

       
                                                            
      
                                                             
                                                       
                                       
       
    object Progress {

        private val TAG = MainModule.TAG
        private const val TITLE = "BetterHeybox 正在适配小黑盒"
        private const val SHOW_DELAY_MS = 400L
        private const val DONE_LINGER_MS = 600L

        class Task internal constructor(
            internal val label: String,
            @Volatile internal var total: Int,
            private val enabled: Boolean
        ) {
            @Volatile
            internal var done = 0

            @Volatile
            internal var detail: String? = null

                                                    
            fun step(done: Int, detail: String?) {
                if (!enabled) {
                    return
                }
                this.done = done
                this.detail = detail
                MAIN.post { bind() }
            }

            fun finish() {
                if (!enabled) {
                    return
                }
                TASKS.remove(this)
                MAIN.post { onTaskFinished() }
            }
        }

        private val TASKS = CopyOnWriteArrayList<Task>()
        private val MAIN = Handler(Looper.getMainLooper())

                      
        private var sActivity: WeakReference<Activity>? = null
        private var sDialog: Dialog? = null
        private var sDialogOwner: WeakReference<Activity>? = null
        private var sDetail: TextView? = null
        private var sBar: ProgressBar? = null
        private var sBuilding = false
        private var sHiddenByUser = false
        private var sClosingSelf = false
        private var sApp: Application? = null
        private var sCallbacks: Application.ActivityLifecycleCallbacks? = null

        @JvmStatic
        fun begin(label: String, total: Int): Task {
            val enabled = try {
                SessionGuard.isMainProcess()
            } catch (ignored: Throwable) {
                false
            }
            val task = Task(label, total, enabled)
            if (!enabled) {
                return task
            }
            TASKS.add(task)
            MAIN.post {
                ensureCallbacks()
                if (sActivity?.get() == null) {
                    val resumed = findResumedActivity()
                    if (resumed != null) {
                        sActivity = WeakReference(resumed)
                    }
                }
            }
            MAIN.postDelayed({
                if (TASKS.contains(task)) {
                    showOrBind()
                }
            }, SHOW_DELAY_MS)
            return task
        }

        @JvmStatic
        fun shutdown() {
            TASKS.clear()
            MAIN.post {
                closeDialog()
                val app = sApp
                val callbacks = sCallbacks
                if (app != null && callbacks != null) {
                    try {
                        app.unregisterActivityLifecycleCallbacks(callbacks)
                    } catch (ignored: Throwable) {
                    }
                }
                sApp = null
                sCallbacks = null
                sActivity = null
                sHiddenByUser = false
                sBuilding = false
            }
        }

        private fun usable(a: Activity?): Boolean = a != null && !a.isFinishing && !a.isDestroyed

        private fun showOrBind() {
            if (TASKS.isEmpty() || sHiddenByUser || sBuilding) {
                return
            }
            if (sDialog?.isShowing == true) {
                bind()
                return
            }
            val activity = sActivity?.get()
            if (!usable(activity)) {
                                            
                return
            }
            val act = activity!!
            val module = MainModule.get()
            if (module == null) {
                showFallback(act)
                return
            }
            sBuilding = true
            try {
                DexKitResolver.getHeyboxDialogSpec(module, act, object : DexKitResolver.SpecCallback {
                    override fun onReady(spec: DexKitResolver.HeyboxDialogSpec) {
                        sBuilding = false
                        if (!stillWanted(act)) {
                            return
                        }
                        try {
                            val content = buildContent(act)
                            val d = spec.buildAndShow(
                                act, TITLE, content,
                                "后台运行", DialogInterface.OnClickListener { d, _ ->
                                    sHiddenByUser = true
                                    d.dismiss()
                                },
                                null, null
                            )
                            attach(act, d)
                        } catch (t: Throwable) {
                            Log.w(TAG, "DexKit 进度框：原生弹窗不可用，回退系统弹窗: $t")
                            showFallback(act)
                        }
                    }

                    override fun onFailed(reason: String) {
                        sBuilding = false
                        if (stillWanted(act)) {
                            showFallback(act)
                        }
                    }
                })
            } catch (t: Throwable) {
                sBuilding = false
                Log.w(TAG, "DexKit 进度框：原生弹窗通道异常: $t")
                showFallback(act)
            }
        }

        private fun stillWanted(act: Activity): Boolean =
            TASKS.isNotEmpty() && !sHiddenByUser && usable(act) && sActivity?.get() === act &&
                sDialog?.isShowing != true

        private fun showFallback(act: Activity) {
            try {
                val d = AlertDialog.Builder(act)
                    .setTitle(TITLE)
                    .setView(buildContent(act))
                    .setPositiveButton("后台运行") { d, _ ->
                        sHiddenByUser = true
                        d.dismiss()
                    }
                    .create()
                d.show()
                attach(act, d)
            } catch (t: Throwable) {
                Log.w(TAG, "DexKit 进度框显示失败: $t")
                clearViews()
            }
        }

        private fun attach(act: Activity, d: Dialog) {
            sDialog = d
            sDialogOwner = WeakReference(act)
            d.setCanceledOnTouchOutside(false)
            d.setOnDismissListener {
                if (!sClosingSelf) {
                                          
                    sHiddenByUser = true
                }
                if (sDialog === d) {
                    clearViews()
                }
            }
            bind()
        }

        private fun buildContent(act: Activity): View {
            val pad = ThemeUtils.dp(act, 12f)
            val box = LinearLayout(act)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(pad, pad, pad, pad)
            box.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val detail = TextView(act)
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val textColor = SettingsEntryHook.hostColor(act, "color_text_primary_day_night", 0)
            if (textColor != 0) {
                detail.setTextColor(textColor)
            }
                       
            detail.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            box.addView(detail)

            val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = pad
            bar.layoutParams = lp
            val accent = SettingsEntryHook.hostColor(act, "color_text_link_day_night", 0)
            if (accent != 0) {
                bar.progressTintList = ColorStateList.valueOf(accent)
                bar.indeterminateTintList = ColorStateList.valueOf(accent)
            }
            bar.contentDescription = "适配进度"
            box.addView(bar)

            sDetail = detail
            sBar = bar
            return box
        }

        private fun bind() {
            val detail = sDetail ?: return
            val bar = sBar ?: return
            val tasks = ArrayList(TASKS)
            if (tasks.isEmpty()) {
                return
            }
            var total = 0
            var done = 0
            for (t in tasks) {
                total += Math.max(0, t.total)
                done += Math.min(Math.max(0, t.done), Math.max(0, t.total))
            }
            val current = tasks[tasks.size - 1]
            val sb = StringBuilder()
            sb.append("正在").append(current.label)
            if (total > 0) {
                sb.append("（").append(done).append("/").append(total).append("）")
            }
            val d = current.detail
            if (!d.isNullOrEmpty()) {
                sb.append("\n").append(d)
            }
            detail.text = sb.toString()
            if (total <= 0 || done <= 0) {
                bar.isIndeterminate = true
            } else {
                bar.isIndeterminate = false
                bar.max = total
                bar.progress = done
            }
        }

        private fun onTaskFinished() {
            if (TASKS.isNotEmpty()) {
                bind()
                return
            }
                              
            sHiddenByUser = false
            val d = sDialog ?: return
            sDetail?.text = "适配完成"
            sBar?.let {
                it.isIndeterminate = false
                it.max = 1
                it.progress = 1
            }
            MAIN.postDelayed({
                if (TASKS.isEmpty() && sDialog === d) {
                    closeDialog()
                }
            }, DONE_LINGER_MS)
        }

        private fun closeDialog() {
            val d = sDialog ?: return
            sClosingSelf = true
            try {
                d.dismiss()
            } catch (ignored: Throwable) {
            } finally {
                sClosingSelf = false
            }
            clearViews()
        }

        private fun clearViews() {
            sDialog = null
            sDialogOwner = null
            sDetail = null
            sBar = null
        }

        private fun ensureCallbacks() {
            if (sCallbacks != null) {
                return
            }
            val app = (App.resolveAppContext() ?: App.getAppContext())?.applicationContext as? Application
                ?: return
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    sActivity = WeakReference(activity)
                    if (TASKS.isNotEmpty()) {
                        showOrBind()
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                                              
                    if (sDialogOwner?.get() === activity) {
                        closeDialog()
                    }
                }

                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {
                    if (sActivity?.get() === activity) {
                        sActivity = null
                    }
                }
            }
            try {
                app.registerActivityLifecycleCallbacks(callbacks)
                sApp = app
                sCallbacks = callbacks
            } catch (t: Throwable) {
                Log.w(TAG, "DexKit 进度框：注册页面回调失败: $t")
            }
        }

                                                                
        private fun findResumedActivity(): Activity? {
            return try {
                val at = Class.forName("android.app.ActivityThread")
                val thread = at.getMethod("currentActivityThread").invoke(null) ?: return null
                val field = at.getDeclaredField("mActivities")
                field.isAccessible = true
                val map = field.get(thread) as? Map<*, *> ?: return null
                for (record in map.values) {
                    if (record == null) {
                        continue
                    }
                    val rc = record.javaClass
                    val paused = rc.getDeclaredField("paused")
                    paused.isAccessible = true
                    if (paused.getBoolean(record)) {
                        continue
                    }
                    val af = rc.getDeclaredField("activity")
                    af.isAccessible = true
                    val a = af.get(record) as? Activity
                    if (usable(a)) {
                        return a
                    }
                }
                null
            } catch (ignored: Throwable) {
                null
            }
        }
    }
}
