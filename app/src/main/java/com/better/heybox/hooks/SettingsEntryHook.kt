package com.better.heybox.hooks

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentResolver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.better.heybox.App
import com.better.heybox.BuildFlags
import com.better.heybox.Checkpoint
import com.better.heybox.ConfigBackup
import com.better.heybox.DexKitResolver
import com.better.heybox.GlassProvider
import com.better.heybox.HeyboxPrefs
import com.better.heybox.HeyboxTargets
import com.better.heybox.LogExport
import com.better.heybox.LogRecorder
import com.better.heybox.MainModule
import com.better.heybox.PreferenceReceiver
import com.better.heybox.ThemeUtils
import com.better.heybox.VersionUtils
import com.better.heybox.liquidglass.GlassSettingsSheet
import com.better.heybox.liquidglass.LiquidGlassInstaller

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

class SettingsEntryHook(private val module: MainModule) {

    private var mSettingsPanel: WeakReference<View>? = null

    fun install(cl: ClassLoader) {
        hookSettingsEntry(cl)
        hookLaunchPrompt(cl)
    }

    private fun hookLaunchPrompt(cl: ClassLoader) {
        try {
            val main: Class<*> = Class.forName("com.max.xiaoheihe.MainActivity", false, cl)
            val onCreate: Method = main.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
            module.hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                try {
                    val self = chain.getThisObject()
                    if (self is Activity) {
                        val activity = self
                        activity.window.decorView.postDelayed(
                            Runnable { maybeShowDisclaimer(activity) }, 1000L
                        )
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "液态玻璃实现启动提示调度失败: " + t)
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 液态玻璃实现启动提示 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "液态玻璃实现启动提示 Hook 失败: " + t)
        }
    }

    private fun hookSettingsEntry(cl: ClassLoader) {
        try {
            val clazz: Class<*> = Class.forName(
                "com.max.xiaoheihe.module.account.GeneralSettingsActivity", false, cl
            )
            var setupMethod: Method? = findSetupMethod(clazz)
            if (setupMethod == null) {
                setupMethod = findLifecycleFallback(clazz)
            }
            if (setupMethod == null) {
                module.logd(Log.ERROR, MainModule.TAG, "✘ 未找到设置页入口方法")
                return
            }
            val target: Method = setupMethod
            val entryClass: Class<*> = clazz
            module.hook(target).intercept { chain ->
                val result = chain.proceed()
                try {
                    val thisObj = chain.getThisObject()
                    if (thisObj is Activity && entryClass.isInstance(thisObj)) {
                        val activity = thisObj
                        activity.window.decorView.post(Runnable {
                            insertSettingsEntryWithRetry(activity, 0)
                        })
                    }
                } catch (t: Throwable) {
                    module.logd(Log.ERROR, MainModule.TAG, "设置入口插入调度异常", t)
                }
                result
            }
            hookActivityResult(clazz)
            module.logd(
                Log.INFO, MainModule.TAG,
                "✔ 设置页入口 Hook 已安装 (" + target.name + "+retry)"
            )
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 设置页入口 Hook 失败", t)
        }
    }

    private fun findSetupMethod(clazz: Class<*>): Method? {
        for (name in SETUP_METHOD_CANDIDATES) {
            try {
                return clazz.getDeclaredMethod(name)
            } catch (ignored: NoSuchMethodException) {
            }
        }
        return null
    }

    private fun findLifecycleFallback(clazz: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            val cur: Class<*> = c
            if (cur == Any::class.java) {
                return null
            }
            try {
                val m = cur.getDeclaredMethod("onResume")
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "设置页入口混淆名失效，回退生命周期 Hook: " + cur.simpleName + ".onResume"
                )
                return m
            } catch (ignored: NoSuchMethodException) {
                c = cur.superclass
            }
        }
        return null
    }

    private fun hookActivityResult(clazz: Class<*>) {
        try {
            val m = findOnActivityResult(clazz)
            if (m == null) {
                module.logd(Log.WARN, MainModule.TAG, "未找到 onActivityResult，内嵌面板导入/导出不可用")
                return
            }
            module.hook(m).intercept { chain ->
                val result = chain.proceed()
                try {
                    val a0 = chain.getArg(0)
                    val a1 = chain.getArg(1)
                    val a2 = chain.getArg(2)
                    val requestCode = if (a0 is Int) a0 else 0
                    val resultCode = if (a1 is Int) a1 else 0
                    val data = if (a2 is Intent) a2 else null
                    handleEmbeddedPickResult(requestCode, resultCode, data)
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "处理文件选择结果异常: " + t)
                }
                result
            }
            module.logd(
                Log.INFO, MainModule.TAG,
                "✔ onActivityResult Hook 已安装 (" +
                        m.declaringClass.simpleName + "." + m.name + ")"
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "onActivityResult Hook 失败，内嵌面板导入/导出不可用: " + t)
        }
    }

    private fun findOnActivityResult(clazz: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            val cur: Class<*> = c
            if (cur == Any::class.java) {
                return null
            }
            try {
                return cur.getDeclaredMethod(
                    "onActivityResult",
                    java.lang.Integer.TYPE, java.lang.Integer.TYPE, Intent::class.java
                )
            } catch (ignored: NoSuchMethodException) {
                c = cur.superclass
            }
        }
        return null
    }

    private fun handleEmbeddedPickResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK_SAVE_DIR) {
            handleSaveDirResult(resultCode, data)
            return
        }
        if (requestCode != REQUEST_EMBEDDED_EXPORT && requestCode != REQUEST_EMBEDDED_IMPORT &&
            requestCode != REQUEST_EMBEDDED_LOG_EXPORT
        ) {
            return
        }
        val cb = sPendingPick
        sPendingPick = null
        if (cb == null || resultCode != Activity.RESULT_OK || data == null || data.data == null) {
            return
        }
        try {
            cb.onResult(data.data!!)
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "执行文件选择回调失败: " + t)
        }
    }

    private fun handleSaveDirResult(resultCode: Int, data: Intent?) {
        val panelRef = mSettingsPanel
        val panel = panelRef?.get()
        val context: Context? = if (panel != null) panel.context else null
        if (resultCode != Activity.RESULT_OK || data == null || data.data == null) {
            return
        }
        val treeUri: Uri = data.data!!
        try {
            if (context != null) {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "持久化保存位置授权失败: " + t)
        }
        HeyboxPrefs.setString(App.KEY_VIDEO_DIR, treeUri.toString())
        val name = if (context != null) queryDirDisplayName(context, treeUri) else null
        Toast.makeText(
            context, "保存位置已设置：" + (if (name != null) name else treeUri),
            Toast.LENGTH_LONG
        ).show()
        LogRecorder.recordEvent("视频保存位置已设置: " + treeUri)
    }

    private fun queryDirDisplayName(context: Context, treeUri: Uri): String? {
        try {
            val c: android.database.Cursor? = context.contentResolver.query(
                treeUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        return c.getString(0)
                    }
                } finally {
                    c.close()
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun buildDialogMessage(activity: Activity, text: String): TextView {
        val message = TextView(activity)
        val pad = module.dp(activity, 10f)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, pad, 0, pad * 2)
        message.setLayoutParams(lp)
        message.setPadding(pad, pad, pad, pad)
        message.setText(text)
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val textColor = hostColor(activity, "color_text_primary_day_night", 0)
        if (textColor != 0) {
            message.setTextColor(textColor)
        }
        return message
    }

    private fun withHeyboxDialog(activity: Activity, nativeCall: NativeDialogCall, fallback: Runnable) {
        DexKitResolver.getHeyboxDialogSpec(module, activity, object : DexKitResolver.SpecCallback {
            override fun onReady(spec: DexKitResolver.HeyboxDialogSpec) {
                try {
                    nativeCall.call(spec)
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "小黑盒原生弹窗不可用，回退系统弹窗: " + t)
                    fallback.run()
                }
            }

            override fun onFailed(reason: String) {
                module.logd(Log.WARN, MainModule.TAG, "小黑盒原生弹窗解析失败(" + reason + ")，回退系统弹窗")
                fallback.run()
            }
        })
    }

    private fun showSaveDirDialog(activity: Activity) {
        val current = HeyboxPrefs.getString(App.KEY_VIDEO_DIR, null)
        if (current == null || !current.startsWith("content:")) {
            startDirPicker(activity)
            return
        }
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showSaveDirDialogNative(activity, current, spec) },
            Runnable { showSaveDirDialogFallback(activity, current) }
        )
    }

    @Throws(Exception::class)
    private fun showSaveDirDialogNative(
        activity: Activity, current: String,
        spec: DexKitResolver.HeyboxDialogSpec
    ) {
        val message = buildDialogMessage(
            activity, "当前：" + describeSaveDir(activity, current) +
                    "\n\n默认位置为相册 Movies/BetterHeybox"
        )
        val pick = DialogInterface.OnClickListener { d, _ ->
            d.dismiss()
            startDirPicker(activity)
        }
        val reset = DialogInterface.OnClickListener { d, _ ->
            HeyboxPrefs.setString(App.KEY_VIDEO_DIR, "")
            Toast.makeText(activity, "已恢复默认：Movies/BetterHeybox", Toast.LENGTH_SHORT).show()
            LogRecorder.recordEvent("视频保存位置已恢复默认")
            d.dismiss()
        }
        spec.buildAndShow(activity, "保存位置", message, "选择其他文件夹", pick, "恢复默认", reset)
        module.logd(Log.INFO, MainModule.TAG, "✔ 使用小黑盒原生弹窗管理保存位置")
    }

    private fun describeSaveDir(activity: Activity, current: String): String {
        var name = queryDirDisplayName(activity, Uri.parse(current))
        if (name == null || name.isEmpty()) {
            try {
                val decoded = Uri.decode(current)
                val idx = decoded.lastIndexOf('/')
                if (idx >= 0 && idx < decoded.length - 1) {
                    name = decoded.substring(idx + 1)
                }
            } catch (ignored: Throwable) {
            }
        }
        return if (name == null || name.isEmpty()) "已选择的文件夹" else name
    }

    private fun showSaveDirDialogFallback(activity: Activity, current: String) {
        try {
            val name = queryDirDisplayName(activity, Uri.parse(current))
            AlertDialog.Builder(activity)
                .setTitle("保存位置")
                .setMessage(
                    "当前：" + (if (name != null) name else current) +
                            "\n\n默认位置为相册 Movies/BetterHeybox"
                )
                .setPositiveButton("选择其他文件夹", DialogInterface.OnClickListener { _, _ ->
                    startDirPicker(activity)
                })
                .setNeutralButton("恢复默认", DialogInterface.OnClickListener { _, _ ->
                    HeyboxPrefs.setString(App.KEY_VIDEO_DIR, "")
                    Toast.makeText(activity, "已恢复默认：Movies/BetterHeybox", Toast.LENGTH_SHORT).show()
                })
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            startDirPicker(activity)
        }
    }

    private fun startDirPicker(activity: Activity) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            activity.startActivityForResult(intent, REQUEST_PICK_SAVE_DIR)
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "打开文件夹选择器失败: " + t)
            Toast.makeText(activity, "打开文件夹选择器失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun insertSettingsEntryWithRetry(activity: Activity, attempt: Int) {
        if (attempt > 20) {
            module.logd(Log.WARN, MainModule.TAG, "设置页未就绪，放弃插入")
            return
        }
        try {
            val ok = tryInsertSettingsEntry(activity)
            if (!ok && !activity.isFinishing) {
                activity.window.decorView.postDelayed(
                    Runnable { insertSettingsEntryWithRetry(activity, attempt + 1) }, 50L
                )
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "插入设置入口重试异常: " + t)
        }
    }

    private fun tryInsertSettingsEntry(activity: Activity): Boolean {
        LogRecorder.setContext(activity)
        HeyboxPrefs.init(activity)
        try {
            val binding = getGeneralSettingsBinding(activity)
            if (binding == null) {
                return false
            }
            val list = resolveSettingsList(activity, binding)
            if (list == null) {
                return false
            }
            for (i in list.childCount - 1 downTo 0) {
                if (ENTRY_TAG == list.getChildAt(i).tag) {
                    list.removeViewAt(i)
                }
            }

            val entry = buildEntryCard(activity)
            if (entry == null) {
                return false
            }
            entry.tag = ENTRY_TAG
            entry.isClickable = true
            entry.isFocusable = true
            entry.setOnClickListener { _ ->
                try {
                    showEmbeddedSettings(activity)
                } catch (t: Throwable) {
                    module.logd(Log.ERROR, MainModule.TAG, "渲染内嵌设置界面失败", t)
                    Toast.makeText(
                        activity, "BetterHeybox 内嵌设置加载失败", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            list.addView(entry, 0)
            module.logd(Log.INFO, MainModule.TAG, "✔ 原生 BetterHeybox 入口已作为列表项插入通用设置页顶部")
            return true
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "插入设置入口异常: " + t)
            return false
        }
    }

    private fun getGeneralSettingsBinding(activity: Activity): Any? {
        try {
            for (f in activity.javaClass.declaredFields) {
                if (!isViewBindingShape(f.type)) {
                    continue
                }
                f.isAccessible = true
                val binding = f.get(activity)
                if (binding != null) {
                    module.logd(
                        Log.INFO, MainModule.TAG,
                        "GeneralSettings binding 已按 ViewBinding 形态解析: " + f.type.name
                    )
                    return binding
                }
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "查找 GeneralSettings binding 失败: " + t)
        }
        return null
    }

    private fun resolveSettingsList(activity: Activity, binding: Any): LinearLayout? {
        for (m in binding.javaClass.methods) {
            if (m.parameterCount != 0 || m.returnType != LinearLayout::class.java) {
                continue
            }
            try {
                val result = m.invoke(binding)
                if (result is LinearLayout && isViewAttachedUnder(result, activity)) {
                    return result
                }
            } catch (ignored: Throwable) {
            }
        }
        for (m in binding.javaClass.methods) {
            if (m.parameterCount != 0 || m.returnType != LinearLayout::class.java) {
                continue
            }
            try {
                val result = m.invoke(binding)
                if (result is LinearLayout) {
                    return result
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    private var mCurrentPage: String? = null

    private fun showEmbeddedSettings(activity: Activity) {
        mCurrentPage = null
        resetSearchQuery()
        openEmbeddedPanel(
            activity, "BetterHeybox 设置", buildSettingsGroups(activity),
            Runnable { dismissEmbeddedSettings() }
        )
    }

    private fun showModulePage(activity: Activity, pageId: String) {
        mCurrentPage = pageId
        resetSearchQuery()
        openEmbeddedPanel(
            activity, pageTitle(pageId), buildPageGroups(activity, pageId),
            Runnable { showEmbeddedSettings(activity) }
        )
    }

    private fun showWatchV2Settings(activity: Activity) {
        showModulePage(activity, PAGE_WATCH)
    }

    private var mSearchQuery: String? = ""
    private var mPreserveSearch: Boolean = false
    private var mSearchIndex: MutableList<SettingsGroup>? = null

    private fun resetSearchQuery() {
        if (mPreserveSearch) {
            mPreserveSearch = false
            return
        }
        mSearchQuery = ""
    }

    private fun buildPanelSearchBox(activity: Activity): EditText {
        val input = EditText(activity)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 40f)
        )
        val margin = module.dp(activity, 12f)
        lp.setMargins(margin, module.dp(activity, 8f), margin, 0)
        input.setLayoutParams(lp)
        input.setSingleLine(true)
        input.setHint("搜索设置项")
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        input.setInputType(InputType.TYPE_CLASS_TEXT)
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        val bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0)
        if (bgId != 0) {
            input.setBackgroundResource(bgId)
        }
        val textColor = hostColor(activity, "color_text_primary_day_night", 0)
        if (textColor != 0) {
            input.setTextColor(textColor)
        }
        input.setHintTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A.toInt()))
        val pad = module.dp(activity, 10f)
        input.setPadding(pad, 0, pad, 0)
        return input
    }

    private fun searchIndex(activity: Activity): List<SettingsGroup> {
        val cached = mSearchIndex
        if (cached != null) {
            return cached
        }
        val all = ArrayList<SettingsGroup>()
        val roots = buildSettingsGroups(activity)
        if (!roots.isEmpty()) {
            all.add(SettingsGroup("分类入口", roots[0].items))
        }
        for (pageId in PAGE_IDS) {
            for (group in buildPageGroups(activity, pageId)) {
                all.add(
                    SettingsGroup(
                        pageTitle(pageId) + " · " + group.title,
                        group.items
                    )
                )
            }
        }
        mSearchIndex = all
        return all
    }

    private fun searchGroups(activity: Activity, query: String): List<SettingsGroup> {
        val q = query.lowercase(Locale.ROOT)
        val out = ArrayList<SettingsGroup>()
        for (group in searchIndex(activity)) {
            val groupHit = group.title.lowercase(Locale.ROOT).contains(q)
            val hits = ArrayList<SwitchDef>()
            for (def in group.items) {
                if (groupHit || matchSearch(def, q)) {
                    hits.add(def)
                }
            }
            if (!hits.isEmpty()) {
                out.add(SettingsGroup(group.title, hits.toTypedArray()))
            }
        }
        return out
    }

    private fun countGroupsItems(groups: List<SettingsGroup>): Int {
        var count = 0
        for (group in groups) {
            count += group.items.size
        }
        return count
    }

    private fun renderPanelGroups(
        activity: Activity, cl: ClassLoader, box: LinearLayout,
        groups: List<SettingsGroup>
    ) {
        box.removeAllViews()
        val raw = mSearchQuery
        val query = if (raw == null) "" else raw.trim()
        val shown = if (query.isEmpty()) groups else searchGroups(activity, query)
        if (!query.isEmpty()) {
            val tip = TextView(activity)
            tip.setText(
                if (shown.isEmpty()) "没有匹配的设置项"
                else "找到 " + countGroupsItems(shown) + " 项"
            )
            tip.setTextSize(TypedValue.COMPLEX_UNIT_PX, module.dp(activity, 13f).toFloat())
            tip.setTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A.toInt()))
            val tipLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val tm = module.dp(activity, 12f)
            tipLp.setMargins(tm, module.dp(activity, 12f), tm, 0)
            tip.setLayoutParams(tipLp)
            box.addView(tip)
        }
        for (group in shown) {
            val card = buildSectionCard(activity, cl, group)
            if (card != null) {
                box.addView(card)
            }
        }
        appendEmbeddedFooter(activity, box)
    }

    private fun openEmbeddedPanel(
        activity: Activity, title: String,
        groups: List<SettingsGroup>, onBack: Runnable?
    ) {
        try {
            dismissEmbeddedSettings()
            HeyboxPrefs.init(activity)
            val appbarBg = hostColor(activity, "appbar_bg_color", 0xFFFFFFFF.toInt())
            val pageBg = hostColor(activity, "color_bg_subtle_day_night", 0xFFFFFFFF.toInt())

            var statusBarH = 0
            try {
                val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id > 0) {
                    statusBarH = activity.resources.getDimensionPixelSize(id)
                }
            } catch (ignored: Throwable) {
            }
            if (statusBarH <= 0) {
                statusBarH = module.dp(activity, 24f)
            }

            val swallowUntil = android.os.SystemClock.uptimeMillis() + 300L
            val overlay = object : FrameLayout(activity) {
                override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                    if (android.os.SystemClock.uptimeMillis() < swallowUntil) {
                        return true
                    }
                    return super.dispatchTouchEvent(ev)
                }
            }
            overlay.setBackgroundColor(pageBg)
            overlay.setClickable(true)
            overlay.setFocusable(true)
            overlay.setFocusableInTouchMode(true)

            val page = LinearLayout(activity)
            page.setOrientation(LinearLayout.VERTICAL)
            page.setLayoutParams(
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            overlay.addView(page)
            val statusSpacer = View(activity)
            statusSpacer.setBackgroundColor(appbarBg)
            statusSpacer.setLayoutParams(
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, statusBarH
                )
            )
            page.addView(statusSpacer)
            val cl = activity.classLoader
            page.addView(buildEmbeddedTitleBar(activity, cl, appbarBg, title, onBack))
            mSearchIndex = null
            val searchBox = buildPanelSearchBox(activity)
            page.addView(searchBox)
            val scroller = ScrollView(activity)
            scroller.setLayoutParams(
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            val box = LinearLayout(activity)
            box.setOrientation(LinearLayout.VERTICAL)
            box.setLayoutParams(
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            box.setPadding(0, module.dp(activity, 2f), 0, 0)
            scroller.addView(box)
            page.addView(scroller)

            searchBox.setText(mSearchQuery)
            searchBox.setSelection(searchBox.length())
            searchBox.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable?) {
                    val next = if (s == null) "" else s.toString()
                    if (next == mSearchQuery) {
                        return
                    }
                    mSearchQuery = next
                    renderPanelGroups(activity, cl, box, groups)
                }
            })
            renderPanelGroups(activity, cl, box, groups)
            if (HeyboxPrefs.getBoolean(App.KEY_TARGET_HINT_VISIBLE, true)) {
                val wm = TargetHintHook.createVisibleHint(activity)
                wm.setLayoutParams(
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                overlay.addView(wm)
            }
            attachEmbeddedPanel(activity, overlay, onBack)
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "渲染原生设置面板失败", t)
        }
    }

    @Throws(Throwable::class)
    private fun buildEmbeddedTitleBar(
        activity: Activity, cl: ClassLoader, appbarBg: Int,
        title: String, onBack: Runnable?
    ): View {
        val titleBarCls: Class<*> = Class.forName("com.max.hbcommon.component.TitleBar", false, cl)
        val titleBar: Any = titleBarCls.getConstructor(Context::class.java).newInstance(activity)
        (titleBar as View).setBackgroundColor(appbarBg)
        titleBarCls.getMethod("setTitle", CharSequence::class.java).invoke(titleBar, title)
        titleBarCls.getMethod("setNavigationIcon", java.lang.Integer.TYPE)
            .invoke(titleBar, hostResId(activity, "appbar_back", "drawable", 0))
        val ocl: Class<*> = Class.forName("android.view.View\$OnClickListener", false, cl)
        val backListener: Any = View.OnClickListener { _ ->
            if (onBack != null) {
                onBack.run()
            } else {
                dismissEmbeddedSettings()
            }
        }
        titleBarCls.getMethod("setNavigationOnClickListener", ocl).invoke(titleBar, backListener)
        (titleBar as View).setLayoutParams(
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 44f)
            )
        )
        return titleBar
    }

    private fun appendEmbeddedFooter(activity: Activity, box: LinearLayout) {
        try {
            val footer = TextView(activity)
            val displayVersion = moduleVersionName(activity)
            footer.setText("BetterHeybox v" + displayVersion)
            footer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            footer.setGravity(android.view.Gravity.CENTER)
            footer.setTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A.toInt()))
            val footerLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val fm = module.dp(activity, 16f)
            footerLp.setMargins(fm, module.dp(activity, 12f), fm, module.dp(activity, 24f))
            footer.setLayoutParams(footerLp)
            footer.setOnClickListener { _ -> handleFooterClick(activity) }
            box.addView(footer)
            module.logd(
                Log.INFO, MainModule.TAG, "✔ 内嵌面板底部版本号已添加: " +
                        (if (displayVersion == null) "unknown" else displayVersion)
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "内嵌面板版本号页脚渲染失败: " + t)
        }
    }

    private var mHintToggleClicks: Int = 0
    private var mHintToggleLastAt: Long = 0L

    private fun handleFooterClick(activity: Activity) {
        val now = System.currentTimeMillis()
        if (now - mHintToggleLastAt > 3000L) {
            mHintToggleClicks = 0
        }
        mHintToggleLastAt = now
        if (++mHintToggleClicks < 7) {
            return
        }
        mHintToggleClicks = 0
        val next = !HeyboxPrefs.getBoolean(App.KEY_TARGET_HINT_VISIBLE, true)
        writeEmbeddedBoolean(activity, App.KEY_TARGET_HINT_VISIBLE, next)
        Toast.makeText(
            activity, if (next) "Open!" else "Closed!",
            Toast.LENGTH_SHORT
        ).show()
        showEmbeddedSettings(activity)
    }

    private fun attachEmbeddedPanel(activity: Activity, overlay: FrameLayout, onBack: Runnable?) {
        overlay.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                if (onBack != null) {
                    onBack.run()
                } else {
                    dismissEmbeddedSettings()
                }
                return@setOnKeyListener true
            }
            false
        }
        val decor = activity.window.decorView as ViewGroup
        decor.addView(overlay)
        overlay.requestFocus()
        mSettingsPanel = WeakReference<View>(overlay)
        module.logd(Log.INFO, MainModule.TAG, "✔ 原生子页面设置面板已叠加到小黑盒窗口")
    }

    private fun dismissEmbeddedSettings() {
        try {
            val panelRef = mSettingsPanel
            val panel = panelRef?.get()
            if (panel != null && panel.parent != null) {
                (panel.parent as ViewGroup).removeView(panel)
            }
        } catch (ignored: Throwable) {
        }
        mSettingsPanel = null
    }

    private fun buildSectionCard(activity: Activity, cl: ClassLoader, group: SettingsGroup): View? {
        try {
            val groupRoot = LinearLayout(activity)
            groupRoot.setOrientation(LinearLayout.VERTICAL)
            groupRoot.setLayoutParams(
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val groupTitle = TextView(activity)
            groupTitle.setText(group.title)
            val titleSize = module.dp(activity, 13f)
            groupTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize.toFloat())
            groupTitle.setTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A.toInt()))
            groupTitle.setGravity(android.view.Gravity.CENTER_VERTICAL)
            val titleLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val tm = module.dp(activity, 12f)
            titleLp.setMargins(tm, module.dp(activity, 16f), tm, 0)
            groupTitle.setLayoutParams(titleLp)
            groupRoot.addView(groupTitle)
            val cardPair = buildHostCard(activity, cl)
            val card = cardPair[0]
            val content = cardPair[1] as LinearLayout
            for (i in group.items.indices) {
                val item = createSettingSwitch(activity, cl, group.items[i])
                if (item == null) {
                    continue
                }
                if (i == group.items.size - 1) {
                    try {
                        val itemCls: Class<*> = Class.forName(
                            "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl
                        )
                        itemCls.getMethod("setShowBottomDivider", java.lang.Boolean.TYPE)
                            .invoke(item, false)
                    } catch (ignored: Throwable) {
                    }
                }
                content.addView(item)
            }
            groupRoot.addView(card as View)
            return groupRoot
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "构建设置卡片分区失败: " + t)
            return null
        }
    }

    private fun createSettingSwitch(activity: Activity, cl: ClassLoader, def: SwitchDef): View? {
        try {
            val itemCls: Class<*> = Class.forName(
                "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl
            )
            val item: Any = itemCls.getConstructor(Context::class.java).newInstance(activity)

            itemCls.getMethod("setTitle", String::class.java).invoke(item, def.title)
            if (def.desc != null) {
                itemCls.getMethod("setTitleDesc", String::class.java).invoke(item, def.desc)
                val descToggle = resolveDescToggle(itemCls, activity)
                if (descToggle != null) {
                    descToggle.invoke(item, true)
                }
            }
            val typeEnum: Class<*> = Class.forName(
                "com.max.xiaoheihe.module.account.component.SettingItemView\$Type", false, cl
            )
            if (def.clickRow) {
                @Suppress("UNCHECKED_CAST")
                val arrowType: Any = java.lang.Enum.valueOf(typeEnum as Class<out Enum<*>>, "Arrow")
                itemCls.getMethod("setRightType", typeEnum).invoke(item, arrowType)
                try {
                    itemCls.getMethod("setShowBottomDivider", java.lang.Boolean.TYPE)
                        .invoke(item, true)
                } catch (ignored: Throwable) {
                }
                val editKey = def.editKey
                when (def.action) {
                    Action.CLEAR_DAILY -> setRowClick(itemCls, item, View.OnClickListener {
                        try {
                            module.clearDailyTaskAndRetry(activity)
                            Toast.makeText(
                                activity, "已清除今日打卡状态，重新尝试中…",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (t: Throwable) {
                            module.logd(Log.ERROR, MainModule.TAG, "清除今日打卡失败", t)
                        }
                    })

                    Action.CHANNEL -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showChannelDialog(activity)
                    })

                    Action.EXPORT -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        startEmbeddedExport(activity)
                    })

                    Action.IMPORT -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        startEmbeddedImport(activity)
                    })

                    Action.EXPORT_LOG -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        startEmbeddedLogExport(activity)
                    })

                    Action.PICK_DIR -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showSaveDirDialog(activity)
                    })

                    Action.RUNTIME_STATUS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showEmbeddedRuntimeStatus(activity)
                    })

                    Action.TARGET_STATUS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showTargetStatus(activity)
                    })

                    Action.CLEAR_LOG -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        confirmClearLogs(activity)
                    })

                    Action.VIEW_LOG -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showLogPreview(activity)
                    })

                    Action.OPEN_WEB -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showOpenWebDialog(activity)
                    })

                    Action.RESET_GLASS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        resetLiquidGlassSettings(activity)
                    })

                    Action.CHOOSE_GLASS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showGlassProviderDialog(activity)
                    })

                    Action.GLASS_SHEET -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        GlassSettingsSheet.show(activity)
                    })

                    Action.POST_LEVEL -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showPostLevelDialog(activity)
                    })

                    Action.POST_MIN_LIKE -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showPostCountDialog(activity, "屏蔽低赞帖子", App.KEY_POST_MIN_LIKE)
                    })

                    Action.POST_MIN_COMMENT -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showPostCountDialog(activity, "屏蔽低评论帖子", App.KEY_POST_MIN_COMMENT)
                    })

                    Action.POST_MIN_FAVOUR -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showPostCountDialog(activity, "屏蔽低收藏帖子", App.KEY_POST_MIN_FAVOUR)
                    })

                    Action.POST_KEYWORDS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showPostKeywordsDialog(activity)
                    })

                    Action.COMMENT_KEYWORDS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showCommentKeywordsDialog(activity)
                    })

                    Action.COMMENT_FILTER_DIAG -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMultilineInfo(activity, "评论过滤状态", CommentFilterHook.diagnostics())
                    })

                    Action.GAME_LIB_TYPES -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showGameLibPicker(activity, GameLibraryCleanHook.PICK_TYPE, "自定义隐藏类型")
                    })

                    Action.GAME_LIB_ENTRIES -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showGameLibPicker(activity, GameLibraryCleanHook.PICK_ENTRY, "隐藏指定入口卡片")
                    })

                    Action.GAME_LIB_SECTIONS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showGameLibPicker(activity, GameLibraryCleanHook.PICK_SECTION, "隐藏指定推荐分区")
                    })

                    Action.GAME_LIB_DIAG -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showGameLibDiagnostics(activity)
                    })

                    Action.MESSAGE_BADGE_ENTRIES -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMessageEntryPicker(activity, MessageRedDotHook.PICK_NUMBER, "隐藏红数字入口")
                    })

                    Action.MESSAGE_FULL_HIDE_ENTRIES -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMessageEntryPicker(activity, MessageRedDotHook.PICK_FULL, "隐藏入口")
                    })

                    Action.MESSAGE_BADGE_DIAG -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMultilineInfo(activity, "消息红点状态", MessageRedDotHook.diagnostics())
                    })

                    Action.AI_PROVIDER -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showAiProviderDialog(activity)
                    })

                    Action.AI_PROMPT -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showAiPromptDialog(activity)
                    })

                    Action.AI_MAX_TOKENS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showAiMaxTokensDialog(activity)
                    })

                    Action.AI_TEST -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        testAiConnection(activity)
                    })

                    Action.REDIRECT_FORCE -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMultilineEditDialog(
                            activity, "强制重定向域名", App.KEY_BROWSER_REDIRECT_FORCE,
                            "一行一个域名或完整链接，总是用外部浏览器打开", false
                        )
                    })

                    Action.REDIRECT_BLOCK -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMultilineEditDialog(
                            activity, "强制内置域名", App.KEY_BROWSER_REDIRECT_BLOCK,
                            "一行一个域名或完整链接，总是留在内置浏览器", false
                        )
                    })

                    Action.REDIRECT_TARGET -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showBrowserTargetDialog(activity)
                    })

                    Action.WEB_LOG -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showWebLogDialog(activity)
                    })

                    Action.ABOUT -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showAboutDialog(activity)
                    })

                    Action.WATCH_USERS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMultilineEditDialog(
                            activity, "关注对象", App.KEY_WATCH_USERS,
                            "一行一个 userid 或主页链接（最多 30 个）", false
                        )
                    })

                    Action.WATCH_KEYWORDS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMultilineEditDialog(
                            activity, "监控关键词", App.KEY_WATCH_KEYWORDS,
                            "一行一个，命中即提醒；regex: 为正则", false
                        )
                    })

                    Action.WATCH_IMPORT_FOLLOW -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        importWatchFollowing(activity)
                    })

                    Action.OPEN_PAGE -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showModulePage(activity, editKey ?: "")
                    })

                    Action.WATCH_V2 -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showWatchV2Settings(activity)
                    })

                    Action.WATCH_TOPICS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showMultilineEditDialog(
                            activity, "关注的话题", App.KEY_WATCH_TOPICS,
                            "一行一个：话题名或话题id|话题名（「导入关注话题」会自动带上 id）",
                            false
                        )
                    })

                    Action.WATCH_IMPORT_TOPICS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        importWatchTopics(activity)
                    })

                    Action.WATCH_TOPIC_SEARCH -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showTopicSearchDialog(activity)
                    })

                    Action.WATCH_WINDOW -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showWatchWindowDialog(activity)
                    })

                    Action.WATCH_INTERVAL -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showWatchIntervalDialog(activity)
                    })

                    Action.WATCH_SUGGEST_KEYWORDS -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        suggestWatchKeywords(activity)
                    })

                    Action.WATCH_TEST_PUSH -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        module.forceInstallHook(App.KEY_WATCH_ENABLED)
                        testWatchPush(activity)
                    })

                    Action.WATCH_DEBUG_PUSH3 -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        Toast.makeText(activity, "正在拉取最近 3 条并推送…", Toast.LENGTH_SHORT).show()
                        module.forceInstallHook(App.KEY_WATCH_ENABLED)
                        com.better.heybox.watch.WatchEngine.debugPushLatest(activity, 3)
                    })

                    Action.WATCH_CHECK -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        module.forceInstallHook(App.KEY_WATCH_ENABLED)
                        com.better.heybox.watch.WatchEngine.checkNow(activity, true)
                        Toast.makeText(activity, "已触发检查，结果见日志", Toast.LENGTH_SHORT).show()
                    })

                    else -> setRowClick(itemCls, item, View.OnClickListener { _ ->
                        showEditLinkDialog(activity, def.title, editKey)
                    })
                }
                val itemH = module.dp(activity, 48f)
                (item as View).setLayoutParams(
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH)
                )
                return item
            }
            @Suppress("UNCHECKED_CAST")
            val switchType: Any = java.lang.Enum.valueOf(typeEnum as Class<out Enum<*>>, "SwitchButton")
            itemCls.getMethod("setRightType", typeEnum).invoke(item, switchType)
            try {
                itemCls.getMethod("setShowBottomDivider", java.lang.Boolean.TYPE).invoke(item, true)
            } catch (ignored: Throwable) {
            }
            val cur = readEmbeddedBoolean(def.key!!, def.def)
            itemCls.getMethod(
                "setChecked",
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE
            ).invoke(item, cur, false)
            registerSwitchItem(def.key!!, item)
            val listenerCls: Class<*> = Class.forName(
                "android.widget.CompoundButton\$OnCheckedChangeListener", false, cl
            )
            val listener: Any = CompoundButton.OnCheckedChangeListener { _, isChecked ->
                try {
                    if (writeEmbeddedBoolean(activity, def.key!!, isChecked) && def.restart) {
                        showRestartAppDialog(activity, cl)
                    }
                    if (App.KEY_CUSTOM_TEXT_SELECT == def.key ||
                        App.KEY_COPY_POST == def.key
                    ) {
                        TextSelectHook.refresh()
                    }
                    if (App.KEY_COMMENT_FREE_COPY == def.key) {
                        CommentCopyHook.refresh()
                    }
                    if (App.KEY_SEARCH_HIDE_BANNER == def.key ||
                        App.KEY_SEARCH_HIDE_DISCOVER == def.key ||
                        App.KEY_SEARCH_HIDE_HOT_RANK == def.key
                    ) {
                        SearchPageCleanHook.refresh()
                    }
                    if (App.KEY_GAME_LIB_HIDE_BANNER == def.key ||
                        App.KEY_GAME_LIB_HIDE_MENU == def.key ||
                        App.KEY_GAME_LIB_HIDE_SECTIONS == def.key
                    ) {
                        GameLibraryCleanHook.refresh()
                    }
                    if (App.KEY_HIDE_MSG_DOT == def.key ||
                        App.KEY_HIDE_MSG_BADGE == def.key
                    ) {
                        MessageRedDotHook.refresh()
                    }
                    if (App.KEY_LIQUID_GLASS == def.key) {
                        LiquidGlassInstaller.applyGlassEnabled(activity)
                    }
                    if (App.KEY_GLASS_IMMERSIVE == def.key ||
                        App.KEY_GLASS_ADAPTIVE == def.key ||
                        App.KEY_GLASS_FIT_TABS == def.key
                    ) {
                        LiquidGlassInstaller.refreshGlassWith(activity)
                    }
                    if (App.KEY_DEBUG_NO_DOWNGRADE == def.key) {
                        if (isChecked) {
                            HeyboxPrefs.setString(App.KEY_MODULE_VERSION_FLOOR, "0")
                            LogRecorder.recordEvent("已清除模块版本降级限制")
                            Toast.makeText(
                                activity, "已清除模块版本降级限制", Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            LogRecorder.recordEvent("版本降级限制将在下次启动重新生效")
                            Toast.makeText(
                                activity, "版本降级限制将在下次启动重新生效",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    if (!mutexApplying) {
                        applySwitchMutex(activity, def.key!!, isChecked)
                    }
                } catch (t: Throwable) {
                    module.logd(Log.ERROR, MainModule.TAG, "开关监听回调异常: " + def.title, t)
                }
            }
            itemCls.getMethod("setOnCheckedChangeListener", listenerCls).invoke(item, listener)
            val itemH = module.dp(activity, 48f)
            (item as View).setLayoutParams(
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH)
            )
            return item
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "创建 SettingItemView 开关失败 (" + def.title + "): " + t)
            return null
        }
    }

    private val sSwitchItems = java.util.HashMap<String, WeakReference<Any>>()

    private fun registerSwitchItem(key: String, item: Any) {
        synchronized(sSwitchItems) {
            sSwitchItems.put(key, WeakReference(item))
        }
    }

    private var mutexApplying: Boolean = false

    private fun applySwitchMutex(activity: Activity, key: String, checked: Boolean) {
        mutexApplying = true
        try {
            applySwitchMutexInner(activity, key, checked)
        } finally {
            mutexApplying = false
        }
    }

    private fun applySwitchMutexInner(activity: Activity, key: String, checked: Boolean) {
        if (App.KEY_BROWSER_REDIRECT == key) {
            if (checked) {
                setSwitchPref(activity, App.KEY_WEBVIEW_DEVTOOLS, false)
                setSwitchPref(activity, App.KEY_BROWSER_REDIRECT_KNOWN, false)
            } else {
                setSwitchPref(activity, App.KEY_BROWSER_REDIRECT_KNOWN, false)
            }
        } else if (App.KEY_WEBVIEW_DEVTOOLS == key) {
            if (checked) {
                setSwitchPref(activity, App.KEY_BROWSER_REDIRECT, false)
                setSwitchPref(activity, App.KEY_BROWSER_REDIRECT_KNOWN, false)
            }
        } else if (App.KEY_BROWSER_REDIRECT_KNOWN == key && checked) {
            setSwitchPref(activity, App.KEY_BROWSER_REDIRECT, true)
        }
    }

    private fun setSwitchPref(activity: Activity, key: String, value: Boolean): Boolean {
        val current = readEmbeddedBoolean(
            key,
            java.lang.Boolean.TRUE.equals(App.BOOLEAN_DEFAULTS[key])
        )
        if (current == value) {
            return false
        }
        writeEmbeddedBoolean(activity, key, value)
        val ref: WeakReference<Any>?
        synchronized(sSwitchItems) {
            ref = sSwitchItems[key]
        }
        val item = ref?.get()
        if (item != null) {
            try {
                item.javaClass.getMethod(
                    "setChecked",
                    java.lang.Boolean.TYPE, java.lang.Boolean.TYPE
                ).invoke(item, value, false)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "联动开关原地更新失败: " + key, t)
            }
        }
        return true
    }

    private fun resolveDescToggle(itemCls: Class<*>, activity: Activity): Method? {
        if (sDescToggle != null) {
            return sDescToggle
        }
        try {
            val probe: Any = itemCls.getConstructor(Context::class.java).newInstance(activity)
            itemCls.getMethod("setTitleDesc", String::class.java).invoke(probe, DESC_PROBE_TEXT)
            for (m in itemCls.declaredMethods) {
                if (Modifier.isStatic(m.modifiers) ||
                    m.parameterCount != 1 ||
                    m.parameterTypes[0] != java.lang.Boolean.TYPE ||
                    m.returnType != java.lang.Void.TYPE
                ) {
                    continue
                }
                try {
                    m.invoke(probe, true)
                    val lit = isProbeDescVisible(probe)
                    m.invoke(probe, false)
                    if (lit) {
                        sDescToggle = m
                        module.logd(
                            Log.INFO, MainModule.TAG,
                            "desc 可见性开关已解析: " + m.name + "(boolean)"
                        )
                        return m
                    }
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun isProbeDescVisible(root: Any?): Boolean {
        if (root !is View) {
            return false
        }
        if (root is TextView && DESC_PROBE_TEXT == root.text.toString()) {
            return root.visibility == View.VISIBLE
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (isProbeDescVisible(root.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }

    private fun showChannelDialog(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showChannelDialogNative(activity, spec) },
            Runnable { showChannelDialogFallback(activity) }
        )
    }

    private fun buildOptionRowList(activity: Activity, labels: Array<String>, checked: Int): LinearLayout {
        val list = LinearLayout(activity)
        list.setOrientation(LinearLayout.VERTICAL)
        val pad = module.dp(activity, 8f)
        list.setPadding(pad, pad, pad, pad)
        for (i in labels.indices) {
            val row = TextView(activity)
            row.setText(labels[i])
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            row.setGravity(Gravity.CENTER_VERTICAL)
            row.setPadding(pad, module.dp(activity, 14f), pad, module.dp(activity, 14f))
            row.setTextColor(
                hostColor(
                    activity,
                    if (i == checked) "color_text_link_day_night" else "color_text_primary_day_night",
                    if (i == checked) 0xFF1677FF.toInt() else 0xFF333333.toInt()
                )
            )
            row.setClickable(true)
            row.setFocusable(true)
            list.addView(
                row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return list
    }

    private fun bindOptionRows(dialog: Dialog, list: LinearLayout, onPick: OptionPick) {
        for (i in 0 until list.childCount) {
            val index = i
            list.getChildAt(i).setOnClickListener { _ ->
                onPick.pick(index)
                try {
                    dialog.dismiss()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun showSingleChoiceFallback(
        activity: Activity, title: String,
        labels: Array<String>, checked: Int, onPick: OptionPick
    ) {
        try {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, DialogInterface.OnClickListener { dialog, which ->
                    onPick.pick(which)
                    dialog.dismiss()
                })
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "单选弹框失败(" + title + "): " + t)
        }
    }

    private fun applyShareChannel(activity: Activity, index: Int) {
        try {
            HeyboxPrefs.init(activity)
            HeyboxPrefs.setString(App.KEY_SHARE_CHANNEL, SHARE_CHANNELS[index])
            LogRecorder.recordEvent("分享渠道已选择: " + SHARE_CHANNELS[index])
            Toast.makeText(
                activity, "分享渠道已设为 " + SHARE_CHANNEL_LABELS[index],
                Toast.LENGTH_SHORT
            ).show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "保存分享渠道失败: " + t)
        }
    }

    @Throws(Exception::class)
    private fun showChannelDialogNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val cur = module.getString(App.KEY_SHARE_CHANNEL, "QQ")
        val checked = if ("WECHAT" == cur) 1 else if ("WEIBO" == cur) 2 else 0
        val list = buildOptionRowList(activity, SHARE_CHANNEL_LABELS, checked)
        val dialog = spec.buildAndShow(
            activity, "分享渠道", list, null, null,
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
        bindOptionRows(dialog, list, OptionPick { index -> applyShareChannel(activity, index) })
        module.logd(Log.INFO, MainModule.TAG, "✔ 使用小黑盒原生弹窗选择分享渠道")
    }

    private fun showChannelDialogFallback(activity: Activity) {
        val cur = module.getString(App.KEY_SHARE_CHANNEL, "QQ")
        val checked = if ("WECHAT" == cur) 1 else if ("WEIBO" == cur) 2 else 0
        showSingleChoiceFallback(
            activity, "分享渠道", SHARE_CHANNEL_LABELS, checked,
            OptionPick { index -> applyShareChannel(activity, index) }
        )
    }

    private fun showGlassProviderDialog(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showGlassProviderDialogNative(activity, spec) },
            Runnable { showGlassProviderDialogFallback(activity) }
        )
    }

    @Throws(Exception::class)
    private fun showGlassProviderDialogNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val current = module.getString(App.KEY_GLASS_PROVIDER, "")
        val checked = if (GlassProvider.PROVIDER_HBMOD == current) 1 else 0
        val list = buildOptionRowList(activity, GLASS_PROVIDER_LABELS, checked)
        val dialog = spec.buildAndShow(
            activity, "选择液态玻璃实现", list, null, null,
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
        bindOptionRows(dialog, list, OptionPick { index ->
            chooseGlassProvider(activity, GLASS_PROVIDER_VALUES[index])
        })
        module.logd(Log.INFO, MainModule.TAG, "✔ 使用小黑盒原生弹窗选择液态玻璃实现")
    }

    private fun showGlassProviderDialogFallback(activity: Activity) {
        val current = module.getString(App.KEY_GLASS_PROVIDER, "")
        val checked = if (GlassProvider.PROVIDER_HBMOD == current) 1 else 0
        showSingleChoiceFallback(
            activity, "选择液态玻璃实现", GLASS_PROVIDER_LABELS, checked,
            OptionPick { index -> chooseGlassProvider(activity, GLASS_PROVIDER_VALUES[index]) }
        )
    }

    private fun chooseGlassProvider(activity: Activity, value: String) {
        try {
            HeyboxPrefs.setString(App.KEY_GLASS_PROVIDER, value)
            LogRecorder.recordEvent("液态玻璃实现已选择: " + value)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "保存液态玻璃实现选择失败: " + t)
            return
        }
        try {
            val panelRef = mSettingsPanel
            val panel = panelRef?.get()
            if (panel != null && panel.parent != null) {
                showEmbeddedSettings(activity)
            }
        } catch (ignored: Throwable) {
        }
        showRestartAppDialog(activity, activity.classLoader)
    }

    private fun refreshEmbeddedPanel(activity: Activity) {
        val panelRef = mSettingsPanel
        val panel = panelRef?.get()
        if (panel == null || panel.parent == null) {
            return
        }
        val old = findScroller(panel)
        val scrollY = if (old == null) 0 else old.scrollY
        mPreserveSearch = true
        if (mCurrentPage != null) {
            showModulePage(activity, mCurrentPage!!)
        } else {
            showEmbeddedSettings(activity)
        }
        val freshRef = mSettingsPanel
        val fresh = freshRef?.get()
        if (fresh != null) {
            val scroller = findScroller(fresh)
            if (scroller != null) {
                scroller.post { scroller.scrollTo(0, scrollY) }
            }
        }
    }

    private fun showPostLevelDialog(activity: Activity) {
        val checked = Math.min(
            Math.max(currentPostMinLevel(), 0),
            POST_LEVEL_VALUES.size - 1
        )
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                val list = buildOptionRowList(activity, POST_LEVEL_LABELS, checked)
                val dialog = spec.buildAndShow(
                    activity, "屏蔽低等级发帖", list, null, null,
                    "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                )
                bindOptionRows(dialog, list, OptionPick { index ->
                    HeyboxPrefs.setString(App.KEY_POST_MIN_LEVEL, POST_LEVEL_VALUES[index])
                    LogRecorder.recordEvent("发帖等级阈值已设置: " + POST_LEVEL_VALUES[index])
                    refreshEmbeddedPanel(activity)
                })
            },
            Runnable {
                showSingleChoiceFallback(
                    activity, "屏蔽低等级发帖", POST_LEVEL_LABELS,
                    checked, OptionPick { index ->
                        HeyboxPrefs.setString(App.KEY_POST_MIN_LEVEL, POST_LEVEL_VALUES[index])
                        refreshEmbeddedPanel(activity)
                    }
                )
            }
        )
    }

    private fun currentPostMinLevel(): Int {
        return try {
            Integer.parseInt(module.getString(App.KEY_POST_MIN_LEVEL, "0")!!.trim())
        } catch (t: Throwable) {
            0
        }
    }

    private fun currentThreshold(key: String): Int {
        return try {
            Integer.parseInt(module.getString(key, "0")!!.trim())
        } catch (t: Throwable) {
            0
        }
    }

    private fun showPostCountDialog(activity: Activity, title: String, key: String) {
        val checked = thresholdIndex(module.getString(key, "0"))
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                val list = buildOptionRowList(activity, POST_COUNT_LABELS, checked)
                val dialog = spec.buildAndShow(
                    activity, title, list, null, null,
                    "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                )
                bindOptionRows(dialog, list, OptionPick { index ->
                    HeyboxPrefs.setString(key, POST_COUNT_VALUES[index])
                    LogRecorder.recordEvent(title + "已设置: " + POST_COUNT_VALUES[index])
                    refreshEmbeddedPanel(activity)
                })
            },
            Runnable {
                showSingleChoiceFallback(
                    activity, title, POST_COUNT_LABELS,
                    checked, OptionPick { index ->
                        HeyboxPrefs.setString(key, POST_COUNT_VALUES[index])
                        refreshEmbeddedPanel(activity)
                    }
                )
            }
        )
    }

    private fun showPostKeywordsDialog(activity: Activity) {
        showMultilineEditDialog(
            activity, "屏蔽关键词", App.KEY_POST_KEYWORDS,
            "一行一个，命中标题或正文即屏蔽；regex: 前缀为正则", false
        )
    }

    private fun showCommentKeywordsDialog(activity: Activity) {
        showMultilineEditDialog(
            activity, "评论关键词", App.KEY_COMMENT_KEYWORDS,
            "一行一个，命中评论正文即屏蔽；regex: 前缀为正则", false
        )
    }

    private fun showAiPromptDialog(activity: Activity) {
        showMultilineEditDialog(
            activity, "判定提示词", App.KEY_AI_PROMPT,
            "留空使用内置默认提示词", true
        )
    }

    private fun showGameLibDiagnostics(activity: Activity) {
        showMultilineInfo(activity, "游戏库精简状态", GameLibraryCleanHook.diagnostics())
    }

    private fun showMultilineInfo(activity: Activity, title: String, text: String) {
        try {
            val content = buildDialogMessage(activity, text)
            content.setTextIsSelectable(true)
            val scroller = ScrollView(activity)
            scroller.addView(content)
            scroller.setLayoutParams(
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 420f)
                )
            )
            withHeyboxDialog(
                activity,
                NativeDialogCall { spec ->
                    spec.buildAndShow(
                        activity, title, scroller, null, null,
                        "关闭", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                    )
                },
                Runnable {
                    AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setView(scroller)
                        .setPositiveButton("关闭", null)
                        .show()
                }
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "诊断弹窗失败: " + t)
        }
    }

    private fun showGameLibPicker(activity: Activity, kind: Int, title: String) {
        showEntryPicker(
            activity, title, GameLibraryCleanHook.pickerEntries(kind),
            if (kind == GameLibraryCleanHook.PICK_TYPE) GameLibraryCleanHook.selectedTypes()
            else GameLibraryCleanHook.selectedNames(kind),
            null,
            PickSaver { _, _, picked ->
                if (kind == GameLibraryCleanHook.PICK_TYPE) {
                    GameLibraryCleanHook.setSelectedTypes(picked)
                } else {
                    GameLibraryCleanHook.setSelectedNames(kind, picked)
                }
            }
        )
    }

    private fun showMessageEntryPicker(activity: Activity, kind: Int, title: String) {
        val hint = if (kind == MessageRedDotHook.PICK_FULL)
            "勾选的入口整行从消息列表移除；候选来自实际见过的入口行，打开一次消息列表后会列出更多"
        else
            "勾选的入口只隐藏右侧红色数字；候选来自实际见过的入口行，打开一次消息列表后会列出更多"
        showEntryPicker(
            activity, title, MessageRedDotHook.pickerEntries(kind),
            MessageRedDotHook.selectedNames(kind), hint,
            PickSaver { _, _, picked -> MessageRedDotHook.setSelectedNames(kind, picked) }
        )
    }

    private fun showEntryPicker(
        activity: Activity, title: String,
        entries: List<Array<String>>, selected: Set<String>,
        hint: String?, saver: PickSaver
    ) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                showEntryPickerNative(activity, title, entries, selected, hint, saver, spec)
            },
            Runnable {
                showEntryPickerFallback(activity, title, entries, selected, hint, saver)
            }
        )
    }

    @Throws(Exception::class)
    private fun showEntryPickerNative(
        activity: Activity, title: String,
        entries: List<Array<String>>, selected: Set<String>, hint: String?,
        saver: PickSaver, spec: DexKitResolver.HeyboxDialogSpec
    ) {
        val boxes = ArrayList<CheckBox>()
        val content = buildCheckListPicker(activity, entries, selected, hint, boxes)
        spec.buildAndShow(
            activity, title, content, "保存",
            DialogInterface.OnClickListener { d, _ ->
                savePickedEntries(activity, title, boxes, saver)
                d.dismiss()
            },
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
    }

    private fun showEntryPickerFallback(
        activity: Activity, title: String,
        entries: List<Array<String>>, selected: Set<String>, hint: String?,
        saver: PickSaver
    ) {
        try {
            val boxes = ArrayList<CheckBox>()
            val content = buildCheckListPicker(activity, entries, selected, hint, boxes)
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("保存", DialogInterface.OnClickListener { _, _ ->
                    savePickedEntries(activity, title, boxes, saver)
                })
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, title + "弹窗失败: " + t)
        }
    }

    private fun buildCheckListPicker(
        activity: Activity, entries: List<Array<String>>,
        selected: Set<String>?, hint: String?,
        boxesOut: MutableList<CheckBox>
    ): ScrollView {
        val column = LinearLayout(activity)
        column.setOrientation(LinearLayout.VERTICAL)
        val pad = module.dp(activity, 8f)
        column.setPadding(pad, pad, pad, pad)
        val textColor = hostColor(activity, "color_text_primary_day_night", 0)
        if (hint != null && !hint.isEmpty()) {
            val tip = TextView(activity)
            tip.setText(hint)
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tip.setPadding(0, 0, 0, module.dp(activity, 6f))
            val tipColor = hostColor(activity, "color_text_tertiary_day_night", 0)
            if (tipColor != 0) {
                tip.setTextColor(tipColor)
            }
            column.addView(
                tip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        for (entry in entries) {
            val value = entry[0]
            val label = if (entry.size > 1) entry[1] else null
            val box = CheckBox(activity)
            box.setText(if (label == null || label.isEmpty()) value else value + "  " + label)
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            box.setChecked(selected != null && selected.contains(value))
            if (textColor != 0) {
                box.setTextColor(textColor)
            }
            box.tag = value
            column.addView(
                box, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            boxesOut.add(box)
        }
        val scroller = ScrollView(activity)
        scroller.addView(column)
        scroller.setLayoutParams(
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 400f)
            )
        )
        return scroller
    }

    private fun savePickedEntries(
        activity: Activity, title: String, boxes: List<CheckBox>,
        saver: PickSaver
    ) {
        val picked = LinkedHashSet<String>()
        for (box in boxes) {
            val tag = box.tag
            if (box.isChecked && tag != null) {
                picked.add(stringify(tag))
            }
        }
        saver.save(activity, title, picked)
        LogRecorder.recordEvent(title + "已保存: " + picked.size + " 项")
        Toast.makeText(
            activity,
            if (picked.isEmpty()) "已清空「" + title + "」"
            else "已保存 " + picked.size + " 项，立即生效",
            Toast.LENGTH_SHORT
        ).show()
        refreshEmbeddedPanel(activity)
    }

    private fun showMultilineEditDialog(
        activity: Activity, title: String, key: String,
        hint: String, resetToDefault: Boolean
    ) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                showMultilineEditNative(activity, title, key, hint, resetToDefault, spec)
            },
            Runnable { showMultilineEditFallback(activity, title, key, hint, resetToDefault) }
        )
    }

    private fun buildMultilineInput(activity: Activity, key: String, hint: String): EditText {
        val input = EditText(activity)
        val pad = module.dp(activity, 10f)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, pad, 0, pad * 2)
        input.setLayoutParams(lp)
        input.setPadding(pad, pad, pad, pad)
        input.setGravity(Gravity.TOP or Gravity.START)
        input.setMinLines(5)
        input.setInputType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        input.setHint(hint)
        input.setText(HeyboxPrefs.getString(key, ""))
        input.setSelection(input.length())
        val bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0)
        if (bgId != 0) {
            input.setBackgroundResource(bgId)
        }
        val textColor = hostColor(activity, "color_text_primary_day_night", 0)
        if (textColor != 0) {
            input.setTextColor(textColor)
        }
        return input
    }

    private fun wrapScrollableInput(activity: Activity, input: EditText): ScrollView {
        val scroller = ScrollView(activity)
        scroller.addView(input)
        scroller.setLayoutParams(
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 400f)
            )
        )
        return scroller
    }

    @Throws(Exception::class)
    private fun showMultilineEditNative(
        activity: Activity, title: String,
        key: String, hint: String,
        resetToDefault: Boolean,
        spec: DexKitResolver.HeyboxDialogSpec
    ) {
        val input = buildMultilineInput(activity, key, hint)
        val negative: DialogInterface.OnClickListener = if (resetToDefault) {
            DialogInterface.OnClickListener { d, _ ->
                saveMultiline(activity, key, "", title)
                Toast.makeText(activity, "已恢复默认", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
        } else {
            DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        }
        spec.buildAndShow(
            activity, title, wrapScrollableInput(activity, input), "保存",
            DialogInterface.OnClickListener { d, _ ->
                saveMultiline(activity, key, input.text.toString(), title)
                d.dismiss()
            },
            if (resetToDefault) "恢复默认" else "取消", negative
        )
    }

    private fun showMultilineEditFallback(
        activity: Activity, title: String,
        key: String, hint: String,
        resetToDefault: Boolean
    ) {
        try {
            val input = buildMultilineInput(activity, key, hint)
            val builder = AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(wrapScrollableInput(activity, input))
                .setPositiveButton("保存", DialogInterface.OnClickListener { _, _ ->
                    saveMultiline(activity, key, input.text.toString(), title)
                })
            if (resetToDefault) {
                builder.setNegativeButton("恢复默认", DialogInterface.OnClickListener { _, _ ->
                    saveMultiline(activity, key, "", title)
                    Toast.makeText(activity, "已恢复默认", Toast.LENGTH_SHORT).show()
                })
            } else {
                builder.setNegativeButton("取消", null)
            }
            builder.show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "多行编辑框失败(" + title + "): " + t)
        }
    }

    private fun saveMultiline(activity: Activity, key: String, raw: String, title: String) {
        val normalized: String
        if (App.KEY_POST_KEYWORDS == key) {
            val sb = StringBuilder()
            for (line in raw.split(Regex("\n"))) {
                val k = line.trim()
                if (!k.isEmpty()) {
                    if (sb.length > 0) {
                        sb.append('\n')
                    }
                    sb.append(k)
                }
            }
            normalized = sb.toString()
        } else if (App.KEY_BROWSER_REDIRECT_FORCE == key ||
            App.KEY_BROWSER_REDIRECT_BLOCK == key
        ) {
            val sb = StringBuilder()
            val seen = java.util.HashSet<String>()
            for (line in raw.split(Regex("\n"))) {
                var d = line.trim().lowercase(Locale.getDefault())
                if (d.isEmpty()) {
                    continue
                }
                if (d.startsWith("http://")) {
                    d = d.substring(7)
                } else if (d.startsWith("https://")) {
                    d = d.substring(8)
                }
                val slash = d.indexOf('/')
                if (slash >= 0) {
                    d = d.substring(0, slash)
                }
                if (!d.isEmpty() && seen.add(d)) {
                    if (sb.length > 0) {
                        sb.append('\n')
                    }
                    sb.append(d)
                }
            }
            normalized = sb.toString()
        } else {
            normalized = raw.trim()
        }
        HeyboxPrefs.setString(key, normalized)
        if (App.KEY_COMMENT_KEYWORDS == key) {
            CommentFilterHook.refresh()
        }
        LogRecorder.recordEvent(title + " 已保存")
        Toast.makeText(activity, "已保存，立即生效", Toast.LENGTH_SHORT).show()
        refreshEmbeddedPanel(activity)
    }

    private fun showAiProviderDialog(activity: Activity) {
        val current = module.getString(App.KEY_AI_PROVIDER, "")
        val checked = Math.max(AIClickbaitChecker.providerIndex(current), 0)
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                val list = buildOptionRowList(activity, AIClickbaitChecker.PROVIDER_LABELS, checked)
                val dialog = spec.buildAndShow(
                    activity, "选择 AI 提供商", list, null, null,
                    "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                )
                bindOptionRows(dialog, list, OptionPick { index -> applyAiProvider(activity, index) })
            },
            Runnable {
                showSingleChoiceFallback(
                    activity, "选择 AI 提供商",
                    AIClickbaitChecker.PROVIDER_LABELS, checked,
                    OptionPick { index -> applyAiProvider(activity, index) }
                )
            }
        )
    }

    private fun applyAiProvider(activity: Activity, index: Int) {
        try {
            HeyboxPrefs.setString(App.KEY_AI_PROVIDER, AIClickbaitChecker.PROVIDER_IDS[index])
            val base = AIClickbaitChecker.PROVIDER_BASE_URLS[index]
            val model = AIClickbaitChecker.PROVIDER_MODELS[index]
            if (!base.isEmpty()) {
                HeyboxPrefs.setString(App.KEY_AI_BASE_URL, base)
            }
            if (!model.isEmpty()) {
                HeyboxPrefs.setString(App.KEY_AI_MODEL, model)
            }
            LogRecorder.recordEvent("AI 提供商已选择: " + AIClickbaitChecker.PROVIDER_IDS[index])
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "保存 AI 提供商失败: " + t)
            return
        }
        refreshEmbeddedPanel(activity)
    }

    private fun showAiMaxTokensDialog(activity: Activity) {
        val current = AIClickbaitChecker.maxTokens(module)
        val labelArr = arrayOfNulls<String>(AIClickbaitChecker.MAX_TOKEN_OPTIONS.size)
        var checkedIdx = 0
        for (i in labelArr.indices) {
            val v = AIClickbaitChecker.MAX_TOKEN_OPTIONS[i]
            labelArr[i] = v.toString() + (if (v == 700) "（推荐）" else "")
            if (v == current) {
                checkedIdx = i
            }
        }
        val labels = labelArr.requireNoNulls()
        val checked = checkedIdx
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                val list = buildOptionRowList(activity, labels, checked)
                val dialog = spec.buildAndShow(
                    activity, "输出 Token 上限", list, null, null,
                    "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                )
                bindOptionRows(dialog, list, OptionPick { index -> applyAiMaxTokens(activity, index) })
            },
            Runnable {
                showSingleChoiceFallback(
                    activity, "输出 Token 上限", labels, checked,
                    OptionPick { index -> applyAiMaxTokens(activity, index) }
                )
            }
        )
    }

    private fun applyAiMaxTokens(activity: Activity, index: Int) {
        try {
            HeyboxPrefs.setString(
                App.KEY_AI_MAX_TOKENS,
                AIClickbaitChecker.MAX_TOKEN_OPTIONS[index].toString()
            )
            LogRecorder.recordEvent(
                "AI 输出上限已选择: " +
                        AIClickbaitChecker.MAX_TOKEN_OPTIONS[index]
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "保存 AI 输出上限失败: " + t)
            return
        }
        Toast.makeText(activity, "已保存，立即生效", Toast.LENGTH_SHORT).show()
        refreshEmbeddedPanel(activity)
    }

    private fun testAiConnection(activity: Activity) {
        Toast.makeText(activity, "正在测试 AI 连接…", Toast.LENGTH_SHORT).show()
        AIClickbaitChecker.testConnection(module) { ok, message ->
            Toast.makeText(
                activity,
                if (ok) "AI 连接成功：" + message else "AI 连接失败：" + message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun maybeShowDisclaimer(activity: Activity) {
        try {
            if (HeyboxPrefs.getBoolean(App.KEY_DISCLAIMER_ACCEPTED, false)) {
                maybePromptGlassProvider(activity)
                return
            }
            withHeyboxDialog(
                activity,
                NativeDialogCall { spec -> showDisclaimerNative(activity, spec) },
                Runnable { showDisclaimerFallback(activity) }
            )
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "免责声明弹窗调度失败: " + t)
        }
    }

    @Throws(Exception::class)
    private fun showDisclaimerNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val message = buildDialogMessage(activity, DISCLAIMER_TEXT)
        val dialog = spec.buildAndShow(
            activity, "免责声明", message, "同意并继续",
            DialogInterface.OnClickListener { d, _ ->
                d.dismiss()
                acceptDisclaimer(activity)
            },
            "不同意并退出", DialogInterface.OnClickListener { d, _ ->
                d.dismiss()
                exitWithoutConsent(activity)
            }
        )
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
    }

    private fun showDisclaimerFallback(activity: Activity) {
        try {
            AlertDialog.Builder(activity)
                .setTitle("免责声明")
                .setMessage(DISCLAIMER_TEXT)
                .setCancelable(false)
                .setPositiveButton("同意并继续", DialogInterface.OnClickListener { _, _ ->
                    acceptDisclaimer(activity)
                })
                .setNegativeButton("不同意并退出", DialogInterface.OnClickListener { _, _ ->
                    exitWithoutConsent(activity)
                })
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "免责声明系统弹窗失败: " + t)
        }
    }

    private fun acceptDisclaimer(activity: Activity) {
        writeEmbeddedBoolean(activity, App.KEY_DISCLAIMER_ACCEPTED, true)
        activity.window.decorView.postDelayed(
            Runnable { maybePromptGlassProvider(activity) }, 600L
        )
    }

    private fun exitWithoutConsent(activity: Activity) {
        try {
            activity.finishAffinity()
        } catch (t: Throwable) {
            activity.finish()
        }
    }

    private fun maybePromptGlassProvider(activity: Activity) {
        try {
            if (sLaunchPromptShown) {
                return
            }
            if (!HeyboxPrefs.getBoolean(App.KEY_DISCLAIMER_ACCEPTED, false)) {
                return
            }
            if (!GlassProvider.isHbmodInstalled(activity)) {
                return
            }
            if (!module.getString(App.KEY_GLASS_PROVIDER, "")!!.isEmpty()) {
                return
            }
            sLaunchPromptShown = true
            activity.window.decorView.post { showGlassProviderDialog(activity) }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "液态玻璃实现选择提示失败: " + t)
        }
    }

    private fun showOpenWebDialog(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showOpenWebDialogNative(activity, spec) },
            Runnable { showOpenWebDialogFallback(activity) }
        )
    }

    private fun createWebUrlInput(activity: Activity): EditText {
        val input = EditText(activity)
        val pad = module.dp(activity, 10f)
        input.setPadding(pad, pad, pad, pad)
        input.setSingleLine(true)
        input.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        input.setHint("例如：https://example.com")
        val current = HeyboxPrefs.getString(App.KEY_WEBVIEW_ENTRY_URL, DEFAULT_WEBVIEW_ENTRY_URL)
        input.setText(if (current == null || current.trim().isEmpty()) DEFAULT_WEBVIEW_ENTRY_URL else current)
        input.setSelection(input.length())
        val bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0)
        if (bgId != 0) input.setBackgroundResource(bgId)
        return input
    }

    private fun saveAndOpenWeb(activity: Activity, raw: String?) {
        val url = if (raw == null) "" else raw.trim()
        val uri = Uri.parse(url)
        val scheme = uri.scheme
        if (url.isEmpty() || uri.host == null || scheme == null ||
            !("http".equals(scheme, ignoreCase = true) || "https".equals(scheme, ignoreCase = true))
        ) {
            Toast.makeText(activity, "请输入有效的 http/https 网页地址", Toast.LENGTH_SHORT).show()
            return
        }
        HeyboxPrefs.setString(App.KEY_WEBVIEW_ENTRY_URL, url)
        openNativeWeb(activity, url)
    }

    @Throws(Exception::class)
    private fun showOpenWebDialogNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val input = createWebUrlInput(activity)
        spec.buildAndShow(
            activity, "打开网页", input, "打开",
            DialogInterface.OnClickListener { _, _ -> saveAndOpenWeb(activity, input.text.toString()) },
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
    }

    private fun showOpenWebDialogFallback(activity: Activity) {
        try {
            val input = createWebUrlInput(activity)
            AlertDialog.Builder(activity).setTitle("打开网页")
                .setMessage("仅支持 http/https，将使用小黑盒内置浏览器打开")
                .setView(input)
                .setPositiveButton("打开", DialogInterface.OnClickListener { _, _ ->
                    saveAndOpenWeb(activity, input.text.toString())
                })
                .setNegativeButton("取消", null).show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "打开网页编辑框失败", t)
        }
    }

    private fun showAboutDialog(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showAboutDialogNative(activity, spec) },
            Runnable { showAboutDialogFallback(activity) }
        )
    }

    @Throws(Exception::class)
    private fun showAboutDialogNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        spec.buildAndShow(
            activity, "关于 BetterHeybox", buildAboutContent(activity), "打开 GitHub 仓库",
            DialogInterface.OnClickListener { d, _ ->
                d.dismiss()
                openNativeWeb(activity, DEFAULT_WEBVIEW_ENTRY_URL)
            },
            "关闭", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
    }

    private fun showAboutDialogFallback(activity: Activity) {
        try {
            AlertDialog.Builder(activity)
                .setTitle("关于 BetterHeybox")
                .setMessage(
                    "版本 " + moduleVersionName(activity) +
                            "\nGitHub：" + DEFAULT_WEBVIEW_ENTRY_URL
                )
                .setPositiveButton("打开 GitHub 仓库", DialogInterface.OnClickListener { _, _ ->
                    openNativeWeb(activity, DEFAULT_WEBVIEW_ENTRY_URL)
                })
                .setNegativeButton("关闭", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "关于弹窗失败: " + t)
        }
    }

    private fun buildAboutContent(activity: Activity): View {
        val box = LinearLayout(activity)
        box.setOrientation(LinearLayout.VERTICAL)
        val pad = module.dp(activity, 10f)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, pad, 0, pad * 2)
        box.setLayoutParams(lp)
        box.setPadding(pad, pad, pad, pad)

        val version = TextView(activity)
        version.setText("版本 " + moduleVersionName(activity))
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val textColor = hostColor(activity, "color_text_primary_day_night", 0)
        if (textColor != 0) {
            version.setTextColor(textColor)
        }
        box.addView(version)

        val link = TextView(activity)
        link.setText(DEFAULT_WEBVIEW_ENTRY_URL)
        link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val linkColor = hostColor(activity, "color_text_link_day_night", 0xFF3B78E7.toInt())
        link.setTextColor(linkColor)
        box.addView(link)
        link.setOnClickListener { _ -> openNativeWeb(activity, DEFAULT_WEBVIEW_ENTRY_URL) }
        return box
    }

    private fun moduleVersionName(activity: Activity): String? {
        try {
            val info = module.getModuleApplicationInfo()
            val pkg = activity.packageManager.getPackageArchiveInfo(info.sourceDir, 0)
            if (pkg != null && pkg.versionName != null) {
                val v: String = pkg.versionName!!
                return if (v.startsWith("v")) v.substring(1) else v
            }
        } catch (ignored: Throwable) {
        }
        return "unknown"
    }

    private fun openNativeWeb(activity: Activity, url: String) {
        try {
            val webActivity: Class<*> = Class.forName(
                "com.max.xiaoheihe.module.webview.NativeWebActionActivity", false,
                activity.classLoader
            )
            val intent = Intent(activity, webActivity)
                .putExtra("pageurl", url)
                .putExtra("title", "BetterHeybox")
            activity.startActivity(intent)
            LogRecorder.recordEvent("打开小黑盒内置网页: " + url)
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "启动小黑盒内置浏览器失败", t)
            Toast.makeText(activity, "小黑盒内置浏览器不可用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetLiquidGlassSettings(activity: Activity) {
        HeyboxPrefs.setBoolean(App.KEY_LIQUID_GLASS, true)
        HeyboxPrefs.setBoolean(App.KEY_GLASS_IMMERSIVE, true)
        module.onSettingChanged(App.KEY_LIQUID_GLASS)
        module.onSettingChanged(App.KEY_GLASS_IMMERSIVE)
        HeyboxPrefs.setBoolean(App.KEY_GLASS_ADAPTIVE, true)
        HeyboxPrefs.setString(App.KEY_GLASS_DARK_COLOR, "#000000")
        HeyboxPrefs.setString(App.KEY_GLASS_DARK_ALPHA, "56")
        HeyboxPrefs.setString(App.KEY_GLASS_LIGHT_COLOR, "#FFFFFF")
        HeyboxPrefs.setString(App.KEY_GLASS_LIGHT_ALPHA, "64")
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_HEIGHT, "0")
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_OFFSET, "16")
        HeyboxPrefs.setBoolean(App.KEY_GLASS_FIT_TABS, false)
        HeyboxPrefs.setString(App.KEY_GLASS_SIDE_MARGIN, "16")
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_WIDTH_MODE, "0")
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_WIDTH_PCT, "100")
        HeyboxPrefs.setString(App.KEY_GLASS_TAB_WIDTH_PCT, "100")
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_LAYOUT, "0")
        maybeRefreshGlassRuntime(activity, App.KEY_LIQUID_GLASS)
        Toast.makeText(activity, "液态玻璃设置已恢复默认", Toast.LENGTH_SHORT).show()
        val panelRef = mSettingsPanel
        val panel = panelRef?.get()
        if (panel != null && panel.parent != null) showEmbeddedSettings(activity)
    }

    private fun showEditLinkDialog(activity: Activity, title: String?, key: String?) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showEditLinkDialogNative(activity, title, key, spec) },
            Runnable { showEditLinkDialogFallback(activity, title, key) }
        )
    }

    @Throws(Exception::class)
    private fun showEditLinkDialogNative(
        activity: Activity, title: String?, key: String?,
        spec: DexKitResolver.HeyboxDialogSpec
    ) {
        val k: String = key!!
        val input = EditText(activity)
        val pad = module.dp(activity, 10f)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, pad, 0, pad * 2)
        input.setLayoutParams(lp)
        input.setPadding(pad, pad, pad, pad)
        input.setGravity(Gravity.CENTER_VERTICAL)
        val bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0)
        if (bgId != 0) {
            input.setBackgroundResource(bgId)
        }
        val textColor = hostColor(activity, "color_text_primary_day_night", 0)
        if (textColor != 0) {
            input.setTextColor(textColor)
        }
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        input.setSingleLine(true)
        input.setHint("例如：https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456")
        val cur = HeyboxPrefs.getString(k, "")
        input.setText(if (cur == null) "" else cur)
        input.setSelection(input.length())
        val saveListener = DialogInterface.OnClickListener { d, _ ->
            try {
                HeyboxPrefs.setString(k, input.text.toString().trim())
                maybeRefreshGlassRuntime(activity, k)
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                module.logd(Log.INFO, MainModule.TAG, "分享链接已保存: " + k)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "保存分享链接失败: " + t)
            }
            d.dismiss()
        }
        spec.buildAndShow(
            activity, title, input, "保存", saveListener,
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
        module.logd(Log.INFO, MainModule.TAG, "✔ 使用小黑盒原生弹窗编辑链接: " + k)
    }

    private fun showEditLinkDialogFallback(activity: Activity, title: String?, key: String?) {
        try {
            val k: String = key!!
            val input = EditText(activity)
            input.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            input.setSingleLine(true)
            input.setHint("例如：https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456")
            val cur = HeyboxPrefs.getString(k, "")
            input.setText(if (cur == null) "" else cur)
            input.setSelection(input.length())
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("保存", DialogInterface.OnClickListener { _, _ ->
                    try {
                        HeyboxPrefs.setString(k, input.text.toString().trim())
                        maybeRefreshGlassRuntime(activity, k)
                        Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                        module.logd(Log.INFO, MainModule.TAG, "分享链接已保存: " + k)
                    } catch (t: Throwable) {
                        module.logd(Log.WARN, MainModule.TAG, "保存分享链接失败: " + t)
                    }
                })
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "打开链接编辑框失败: " + t)
        }
    }

    private fun showRestartAppDialog(activity: Activity, cl: ClassLoader) {
        try {
            val ktCls: Class<*> = Class.forName(
                "com.max.xiaoheihe.accelworld.AccelWorldWebkitKt", false, cl
            )
            val x = ktCls.getDeclaredMethod("x", Context::class.java, String::class.java)
            x.invoke(null, activity, "底栏改动需重启小黑盒后生效")
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "复用小黑盒重启 Dialog 失败，回退系统 AlertDialog: " + t)
            try {
                AlertDialog.Builder(activity)
                    .setTitle("重新启动APP生效")
                    .setMessage("底栏改动需重启小黑盒后生效")
                    .setPositiveButton("我知道了", null)
                    .show()
            } catch (t2: Throwable) {
                module.logd(Log.ERROR, MainModule.TAG, "回退弹窗也失败", t2)
            }
        }
    }

    private fun startEmbeddedExport(activity: Activity) {
        try {
            val json = ConfigBackup.buildJson(
                object : ConfigBackup.Reader<Boolean> {
                    override fun get(key: String, def: Boolean): Boolean = module.isEnabled(key, def)
                },
                object : ConfigBackup.Reader<String> {
                    override fun get(key: String, def: String): String =
                        module.getString(key, def) ?: def
                }
            )
            if (json == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show()
                return
            }
            val content = json
            sPendingPick = PickCallback { uri -> writeEmbeddedExport(activity, uri, content) }
            val fileName = "BetterHeybox配置_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date()) + ".json"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType("application/json")
            intent.putExtra(Intent.EXTRA_TITLE, fileName)
            activity.startActivityForResult(intent, REQUEST_EMBEDDED_EXPORT)
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "打开导出选择器失败: " + t)
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeEmbeddedExport(activity: Activity, uri: Uri, json: String) {
        try {
            val resolver = activity.contentResolver
            val os = resolver.openOutputStream(uri)
            if (os == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show()
                return
            }
            os.use { out ->
                out.write(json.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            LogRecorder.recordEvent("内嵌面板配置已导出: " + uri)
            Toast.makeText(activity, "配置已导出", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "写入导出文件失败: " + t)
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startEmbeddedLogExport(activity: Activity) {
        val logPath = LogRecorder.getLogFilePath()
        val logFile = if (logPath != null) File(logPath) else null
        if (logFile == null || !logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(
                activity, "暂无日志文件：请先开启「记录日志」，再打开一次小黑盒，然后回来导出",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            sPendingPick = PickCallback { uri -> writeEmbeddedLogExport(activity, uri) }
            val fileName = "BetterHeybox日志_" + SimpleDateFormat("yyMMdd_HHmmss", Locale.US)
                .format(Date()) + ".txt"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType("text/plain")
            intent.putExtra(Intent.EXTRA_TITLE, fileName)
            activity.startActivityForResult(intent, REQUEST_EMBEDDED_LOG_EXPORT)
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "打开日志导出选择器失败: " + t)
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeEmbeddedLogExport(activity: Activity, uri: Uri) {
        try {
            val content = LogExport.buildExportText(activity)
            val resolver = activity.contentResolver
            val os = resolver.openOutputStream(uri)
            if (os == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show()
                return
            }
            os.use { out ->
                out.write(content.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            LogRecorder.recordEvent("内嵌面板日志已导出: " + uri)
            Toast.makeText(activity, "日志已导出", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "写入日志导出文件失败: " + t)
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWebLogDialog(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showWebLogDialogNative(activity, spec) },
            Runnable { showWebLogDialogFallback(activity) }
        )
    }

    private fun buildWebLogText(activity: Activity): TextView {
        val data = HeyboxPrefs.getString(App.KEY_WEB_LOG_DATA, "")
        val text = buildDialogMessage(
            activity,
            if (data!!.isEmpty()) "暂无记录，开启「网页日志」后访问内置网页即开始记录" else data
        )
        text.setTextIsSelectable(true)
        return text
    }

    @Throws(Exception::class)
    private fun showWebLogDialogNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val pad = module.dp(activity, 10f)
        val scroller = ScrollView(activity)
        val box = LinearLayout(activity)
        box.setOrientation(LinearLayout.VERTICAL)
        val copyRow = TextView(activity)
        copyRow.setText("全部复制")
        copyRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        copyRow.setPadding(pad, pad, pad, 0)
        copyRow.setTextColor(hostColor(activity, "color_text_link_day_night", 0xFF1677FF.toInt()))
        copyRow.setClickable(true)
        copyRow.setOnClickListener { _ -> copyWebLog(activity) }
        box.addView(
            copyRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        box.addView(buildWebLogText(activity))
        scroller.addView(
            box, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scroller.setLayoutParams(
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 400f)
            )
        )
        spec.buildAndShow(
            activity, "网页日志", scroller,
            "清空", DialogInterface.OnClickListener { d, _ ->
                BrowserRedirectHook.clearLog()
                Toast.makeText(activity, "网页日志已清空", Toast.LENGTH_SHORT).show()
                d.dismiss()
            },
            "关闭", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
    }

    private fun showWebLogDialogFallback(activity: Activity) {
        try {
            val scroller = ScrollView(activity)
            scroller.addView(buildWebLogText(activity))
            AlertDialog.Builder(activity)
                .setTitle("网页日志")
                .setView(scroller)
                .setPositiveButton("清空", DialogInterface.OnClickListener { _, _ ->
                    BrowserRedirectHook.clearLog()
                    Toast.makeText(activity, "网页日志已清空", Toast.LENGTH_SHORT).show()
                })
                .setNeutralButton("全部复制", DialogInterface.OnClickListener { _, _ ->
                    copyWebLog(activity)
                })
                .setNegativeButton("关闭", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "网页日志弹窗失败: " + t)
        }
    }

    private fun copyWebLog(activity: Activity) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager?
            if (cm != null) {
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "BetterHeybox 网页日志", HeyboxPrefs.getString(App.KEY_WEB_LOG_DATA, "")
                    )
                )
                Toast.makeText(activity, "已复制全部日志", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "网页日志复制失败", t)
        }
    }

    private fun browserTargetLabel(activity: Activity): String {
        val pkg = module.getString(App.KEY_BROWSER_TARGET, "")!!
        if (pkg.isEmpty()) {
            return "跟随系统"
        }
        try {
            val pm = activity.packageManager
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() + "（" + pkg + "）"
        } catch (t: Throwable) {
            return pkg
        }
    }

    private fun installedBrowsers(activity: Activity): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        try {
            val pm = activity.packageManager
            val infos: List<android.content.pm.ResolveInfo> = pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com/")), 0
            )
            val byPkg = java.util.LinkedHashMap<String, android.content.pm.ResolveInfo>()
            for (info in infos) {
                if (info.activityInfo == null) {
                    continue
                }
                val pkg = info.activityInfo.packageName
                if (pkg == null || MainModule.TARGET_PKG == pkg) {
                    continue
                }
                byPkg.putIfAbsent(pkg, info)
            }
            for (info in byPkg.values) {
                val label: String = try {
                    info.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    info.activityInfo.packageName
                }
                out.add(arrayOf(label, info.activityInfo.packageName))
            }
            out.sortWith(java.util.Comparator { a, b ->
                java.lang.String.CASE_INSENSITIVE_ORDER.compare(a[0], b[0])
            })
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "枚举浏览器失败: " + t)
        }
        return out
    }

    private fun showBrowserTargetDialog(activity: Activity) {
        val browsers = installedBrowsers(activity)
        if (browsers.isEmpty()) {
            showEditLinkDialog(activity, "重定向浏览器包名", App.KEY_BROWSER_TARGET)
            return
        }
        val values = arrayOfNulls<String>(browsers.size + 1)
        val labels = arrayOfNulls<String>(browsers.size + 1)
        values[0] = ""
        labels[0] = "跟随系统"
        for (i in browsers.indices) {
            values[i + 1] = browsers[i][1]
            labels[i + 1] = browsers[i][0] + "（" + browsers[i][1] + "）"
        }
        val valuesArr = values.requireNoNulls()
        val labelsArr = labels.requireNoNulls()
        val cur = module.getString(App.KEY_BROWSER_TARGET, "")
        var checked = 0
        for (i in valuesArr.indices) {
            if (valuesArr[i] == cur) {
                checked = i
                break
            }
        }
        val checkedIndex = checked
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                val list = buildOptionRowList(activity, labelsArr, checkedIndex)
                val dialog = spec.buildAndShow(
                    activity, "选择重定向浏览器", list, null, null,
                    "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                )
                bindOptionRows(dialog, list, OptionPick { index ->
                    applyBrowserTarget(activity, valuesArr[index], labelsArr[index])
                })
            },
            Runnable {
                showSingleChoiceFallback(
                    activity, "选择重定向浏览器", labelsArr, checkedIndex,
                    OptionPick { index ->
                        applyBrowserTarget(activity, valuesArr[index], labelsArr[index])
                    }
                )
            }
        )
    }

    private fun applyBrowserTarget(activity: Activity, pkg: String, label: String) {
        try {
            HeyboxPrefs.init(activity)
            HeyboxPrefs.setString(App.KEY_BROWSER_TARGET, pkg)
            LogRecorder.recordEvent("重定向浏览器已选择: " + pkg)
            Toast.makeText(activity, "重定向浏览器已设为 " + label, Toast.LENGTH_SHORT).show()
            refreshEmbeddedPanel(activity)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "保存重定向浏览器失败: " + t)
        }
    }

    private fun showEmbeddedRuntimeStatus(activity: Activity) {
        try {
            val sb = StringBuilder()
            sb.append("构建类型: ").append(if (BuildFlags.DEBUG) "debug" else "release").append('\n')
            sb.append('\n').append("—— 本进程（小黑盒）运行检查点 ——\n")
                .append(Checkpoint.dump(150))
            AlertDialog.Builder(activity)
                .setTitle("运行状态")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "运行状态弹窗失败: " + t)
        }
    }

    private fun showLogPreview(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showLogPreviewNative(activity, spec) },
            Runnable { showLogPreviewFallback(activity) }
        )
    }

    private fun buildLogPreviewText(activity: Activity): TextView {
        val tail = LogRecorder.readTail(200)
        val text = buildDialogMessage(
            activity,
            if (tail == null) "暂无日志：请先开启「记录日志」；关闭「详细日志」时只会记录错误日志"
            else "当前日志：" + LogRecorder.sizeInfo() + "（以下为最近 200 行）\n\n" + tail
        )
        text.setTextIsSelectable(true)
        return text
    }

    private fun copyLogPreview(activity: Activity) {
        try {
            val tail = LogRecorder.readTail(2000)
            if (tail == null) {
                Toast.makeText(activity, "暂无可复制的日志", Toast.LENGTH_SHORT).show()
                return
            }
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager?
            if (cm != null) {
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("BetterHeybox \u65e5\u5fd7", tail)
                )
                Toast.makeText(activity, "已复制最近日志", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "日志复制失败", t)
        }
    }

    @Throws(Exception::class)
    private fun showLogPreviewNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val pad = module.dp(activity, 10f)
        val scroller = ScrollView(activity)
        val box = LinearLayout(activity)
        box.setOrientation(LinearLayout.VERTICAL)
        val copyRow = TextView(activity)
        copyRow.setText("复制最近日志")
        copyRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        copyRow.setPadding(pad, pad, pad, 0)
        copyRow.setTextColor(hostColor(activity, "color_text_link_day_night", 0xFF1677FF.toInt()))
        copyRow.setClickable(true)
        copyRow.setOnClickListener { _ -> copyLogPreview(activity) }
        box.addView(
            copyRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        box.addView(buildLogPreviewText(activity))
        scroller.addView(
            box, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scroller.setLayoutParams(
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 400f)
            )
        )
        spec.buildAndShow(
            activity, "模块日志", scroller,
            "清空", DialogInterface.OnClickListener { d, _ ->
                doClearLogs(activity)
                d.dismiss()
            },
            "关闭", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
    }

    private fun showLogPreviewFallback(activity: Activity) {
        try {
            val scroller = ScrollView(activity)
            scroller.addView(buildLogPreviewText(activity))
            AlertDialog.Builder(activity)
                .setTitle("模块日志")
                .setView(scroller)
                .setPositiveButton("清空", DialogInterface.OnClickListener { _, _ ->
                    doClearLogs(activity)
                })
                .setNeutralButton("复制最近日志", DialogInterface.OnClickListener { _, _ ->
                    copyLogPreview(activity)
                })
                .setNegativeButton("关闭", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "日志预览弹窗失败: " + t)
        }
    }

    private fun confirmClearLogs(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> clearLogsNative(activity, spec) },
            Runnable { clearLogsFallback(activity) }
        )
    }

    @Throws(Exception::class)
    private fun clearLogsNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val message = buildDialogMessage(
            activity,
            "将删除模块日志文件（log.txt / log.1.txt）与运行检查点，确定继续？"
        )
        spec.buildAndShow(
            activity, "清除日志", message, "清除",
            DialogInterface.OnClickListener { d, _ ->
                doClearLogs(activity)
                d.dismiss()
            },
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
        module.logd(Log.INFO, MainModule.TAG, "✔ 使用小黑盒原生弹窗确认清除日志")
    }

    private fun clearLogsFallback(activity: Activity) {
        try {
            AlertDialog.Builder(activity)
                .setTitle("清除日志")
                .setMessage("将删除模块日志文件（log.txt / log.1.txt）与运行检查点，确定继续？")
                .setPositiveButton("清除", DialogInterface.OnClickListener { _, _ ->
                    doClearLogs(activity)
                })
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "清除日志弹窗失败: " + t)
        }
    }

    private fun doClearLogs(activity: Activity) {
        try {
            LogRecorder.setContext(activity)
            val freed = LogRecorder.clear()
            Checkpoint.clear()
            LogRecorder.recordEvent("模块日志已清除: " + (if (freed == null) "无日志文件" else freed))
            val text = if (freed == null)
                "已清除运行检查点（暂无日志文件）"
            else
                "已清除日志 " + freed + " 与运行检查点"
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
            module.logd(Log.INFO, MainModule.TAG, text)
            refreshEmbeddedPanel(activity)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "清除日志失败: " + t)
        }
    }

    private fun showTargetStatus(activity: Activity) {
        try {
            val sb = StringBuilder()
            sb.append("广告 content_type: ").append(PromoteDetector.contentTypesInfo()).append('\n')
            sb.append(HeyboxTargets.report())
            AlertDialog.Builder(activity)
                .setTitle("目标解析状态")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "目标解析状态弹窗失败: " + t)
        }
    }

    private fun startEmbeddedImport(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> startEmbeddedImportNative(activity, spec) },
            Runnable { showEmbeddedImportConfirmFallback(activity) }
        )
    }

    @Throws(Exception::class)
    private fun startEmbeddedImportNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val message = buildDialogMessage(
            activity,
            "导入将覆盖当前所有设置（开关、分享链接、分享渠道等），确定继续？"
        )
        val importListener = DialogInterface.OnClickListener { d, _ ->
            launchImportPicker(activity)
            d.dismiss()
        }
        spec.buildAndShow(
            activity, "导入配置", message, "导入", importListener,
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
        module.logd(Log.INFO, MainModule.TAG, "✔ 使用小黑盒原生弹窗确认导入配置")
    }

    private fun showEmbeddedImportConfirmFallback(activity: Activity) {
        try {
            AlertDialog.Builder(activity)
                .setTitle("导入配置")
                .setMessage("导入将覆盖当前所有设置（开关、分享链接、分享渠道等），确定继续？")
                .setPositiveButton("导入", DialogInterface.OnClickListener { _, _ ->
                    launchImportPicker(activity)
                })
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "导入确认弹框失败: " + t)
        }
    }

    private fun launchImportPicker(activity: Activity) {
        try {
            sPendingPick = PickCallback { uri -> readEmbeddedImport(activity, uri) }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType("application/json")
            activity.startActivityForResult(intent, REQUEST_EMBEDDED_IMPORT)
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "打开导入选择器失败: " + t)
            Toast.makeText(activity, "导入失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readEmbeddedImport(activity: Activity, uri: Uri) {
        try {
            val resolver: ContentResolver = activity.contentResolver
            val stream = resolver.openInputStream(uri)
            if (stream == null) {
                Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show()
                return
            }
            val buffer = ByteArrayOutputStream()
            stream.use { input: InputStream ->
                val chunk = ByteArray(8192)
                var len = input.read(chunk)
                while (len != -1) {
                    buffer.write(chunk, 0, len)
                    len = input.read(chunk)
                }
            }
            val json = String(buffer.toByteArray(), StandardCharsets.UTF_8)
            HeyboxPrefs.init(activity)
            val result = ConfigBackup.applyJson(
                json,
                object : ConfigBackup.Writer<Boolean> {
                    override fun write(key: String, value: Boolean) {
                        writeEmbeddedBoolean(activity, key, value)
                    }
                },
                object : ConfigBackup.Writer<String> {
                    override fun write(key: String, value: String) {
                        HeyboxPrefs.setString(key, value)
                    }
                }
            )
            if (result == null) {
                Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show()
                return
            }
            LogRecorder.recordEvent("内嵌面板配置已导入: " + result.applied + " 项, uri=" + uri)
            Toast.makeText(activity, "配置已导入（" + result.applied + " 项）", Toast.LENGTH_SHORT).show()
            val panelRef = mSettingsPanel
            val panel = panelRef?.get()
            if (panel != null && panel.parent != null) {
                showEmbeddedSettings(activity)
            }
            if (result.restartRequired) {
                showRestartAppDialog(activity, activity.classLoader)
            }
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "读取导入文件失败: " + t)
            Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readEmbeddedBoolean(key: String, defaultValue: Boolean): Boolean {
        return module.isEnabled(key, defaultValue)
    }

    private fun modulePackageName(): String {
        try {
            val info = module.getModuleApplicationInfo()
            if (info != null && info.packageName != null && !info.packageName.isEmpty()) {
                return info.packageName
            }
        } catch (ignored: Throwable) {
        }
        return "com.better.heybox"
    }

    private fun maybeRefreshGlassRuntime(activity: Activity?, key: String?) {
        if (key == null || activity == null) {
            return
        }
        if (App.KEY_LIQUID_GLASS != key && !key.startsWith("glass_")) {
            return
        }
        try {
            LiquidGlassInstaller.refreshGlassWith(activity)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "玻璃设置运行时刷新失败: " + key, t)
        }
    }

    private fun writeEmbeddedBoolean(activity: Activity, key: String, value: Boolean): Boolean {
        LogRecorder.setContext(activity)
        HeyboxPrefs.init(activity)
        val localOk = HeyboxPrefs.setBoolean(key, value)
        module.invalidateLogSwitches()
        module.onSettingChanged(key)
        LogRecorder.recordEvent(
            "内嵌面板开关已写入小黑盒本地配置: key=" + key +
                    ", value=" + value + ", ok=" + localOk
        )
        try {
            val request = Intent(PreferenceReceiver.ACTION_SET_BOOLEAN)
                .setComponent(
                    android.content.ComponentName(
                        modulePackageName(), PreferenceReceiver::class.java.name
                    )
                )
                .putExtra(PreferenceReceiver.EXTRA_KEY, key)
                .putExtra(PreferenceReceiver.EXTRA_VALUE, value)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            activity.sendBroadcast(request)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "远程镜像广播失败（本地配置已生效，不影响使用）: " + key, t)
        }
        if (App.KEY_CUSTOM_TEXT_SELECT == key || App.KEY_COPY_POST == key) {
            TextSelectHook.refresh()
        }
        if (App.KEY_BLOCK_CY_COMMENT == key || App.KEY_HOST_HIDE_CY == key ||
            App.KEY_BLOCK_GAME_RELAY == key
        ) {
            CommentFilterHook.refresh()
        }
        return localOk
    }

    private fun buildSettingsGroups(activity: Activity): List<SettingsGroup> {
        val groups = ArrayList<SettingsGroup>()
        val cfg: com.better.heybox.watch.WatchConfig =
            com.better.heybox.watch.WatchConfig.load(module)
        groups.add(
            SettingsGroup(
                "功能分类", arrayOf(
                    entry(
                        PAGE_ADS, "广告与内容过滤",
                        "广告、推广贴、发帖过滤、评论过滤、搜索 / 游戏库精简、分享净化"
                    ),
                    entry(
                        PAGE_UI, "界面与外观",
                        "液态玻璃、底栏隐藏、单列信息流"
                    ),
                    entry(
                        PAGE_BROWSE, "浏览与下载",
                        "解除复制、链接重定向、视频下载"
                    ),
                    entry(
                        PAGE_WATCH, "动态推送",
                        "关注 " + cfg.users.size + " · 话题 " + cfg.topics.size +
                                " · 关键词 " + cfg.keywords.size + " · 时间窗 " +
                                cfg.windowText()
                    ),
                    entry(PAGE_TASK, "每日任务", "一键完成三个分享任务"),
                    entry(PAGE_COMMON, "通用与备份", "通知权限、更新、日志、备份、关于"),
                )
            )
        )
        return groups
    }

    private fun buildPageGroups(activity: Activity, pageId: String): List<SettingsGroup> {
        val groups = ArrayList<SettingsGroup>()
        if (PAGE_ADS == pageId) {
            addBase(groups, "广告过滤")
            insertPostFilterGroup(groups)
            insertCommentFilterGroup(groups)
            addBase(groups, "搜索页精简")
            groups.add(buildGameLibGroup())
            addBase(groups, TITLE_SHARE_PURIFY)
            return groups
        }
        if (PAGE_UI == pageId) {
            val glass = buildGlassGroup(activity)
            if (glass != null) {
                groups.add(glass)
            }
            groups.add(buildBottomTabGroup(activity))
            groups.add(buildMessageRedDotGroup())
            if (VersionUtils.isHeyboxBuildAtLeast(
                    activity, EXPERIMENTAL_HEYBOX_VERSION,
                    EXPERIMENTAL_HEYBOX_MIN_CODE
                )
            ) {
                groups.add(
                    SettingsGroup(
                        "实验性功能", arrayOf(
                            SwitchDef(
                                "屏蔽双列信息流",
                                "信息流恢复为单列",
                                App.KEY_SINGLE_COLUMN_FEED, false, false
                            ),
                        )
                    )
                )
            }
            return groups
        }
        if (PAGE_BROWSE == pageId) {
            addBase(groups, "解除复制")
            insertBrowserRedirectGroup(activity, groups)
            addBase(groups, "视频下载")
            return groups
        }
        if (PAGE_WATCH == pageId) {
            return buildWatchV2Groups(activity)
        }
        if (PAGE_TASK == pageId) {
            addBase(groups, "每日任务")
            return groups
        }
        addBase(groups, TITLE_GENERAL)
        appendExtraRows(groups, TITLE_GENERAL, DebugSettings.generalRows())
        if (BuildFlags.DEBUG) {
            addRuntimeStatusRow(groups)
        }
        addBase(groups, "配置备份")
        addBase(groups, "关于")
        return groups
    }

    private fun buildWatchV2Groups(activity: Activity): List<SettingsGroup> {
        val cfg: com.better.heybox.watch.WatchConfig =
            com.better.heybox.watch.WatchConfig.load(module)
        val groups = ArrayList<SettingsGroup>()

        groups.add(
            SettingsGroup(
                "总开关", arrayOf(
                    SwitchDef(
                        "关注动态提醒",
                        "打开小黑盒时检查新动态",
                        App.KEY_WATCH_ENABLED, false, false
                    ),
                )
            )
        )

        groups.add(
            SettingsGroup(
                "监控目标", arrayOf(
                    SwitchDef(
                        "关注对象",
                        if (cfg.users.isEmpty()) "一行一个 userid 或主页链接"
                        else "已配置 " + cfg.users.size + " 个",
                        null, false, false, true, null, Action.WATCH_USERS
                    ),
                    SwitchDef(
                        "导入关注列表", "读取「我关注的」并追加",
                        null, false, false, true, null, Action.WATCH_IMPORT_FOLLOW
                    ),
                    SwitchDef(
                        "关注的话题",
                        if (cfg.topics.isEmpty()) "一行一个：话题名或话题id|话题名"
                        else "已配置 " + cfg.topics.size + " 个",
                        null, false, false, true, null, Action.WATCH_TOPICS
                    ),
                    SwitchDef(
                        "导入关注话题", "读取「我关注的话题」",
                        null, false, false, true, null, Action.WATCH_IMPORT_TOPICS
                    ),
                    SwitchDef(
                        "搜索话题", "搜索话题并一键关注",
                        null, false, false, true, null, Action.WATCH_TOPIC_SEARCH
                    ),
                    SwitchDef(
                        "监控关键词",
                        if (cfg.keywords.isEmpty()) "命中即提醒；regex: 为正则"
                        else "已配置 " + cfg.keywords.size + " 个",
                        null, false, false, true, null, Action.WATCH_KEYWORDS
                    ),
                    SwitchDef(
                        "推荐关键词", "从热搜词里挑关键词",
                        null, false, false, true, null, Action.WATCH_SUGGEST_KEYWORDS
                    ),
                )
            )
        )

        groups.add(
            SettingsGroup(
                "抓取范围", arrayOf(
                    SwitchDef(
                        "关键词只匹配标题", "不匹配正文",
                        App.KEY_WATCH_TITLE_ONLY, false, false
                    ),
                    SwitchDef(
                        "话题/关键词拉流",
                        "主动拉取话题/关键词最新帖",
                        App.KEY_WATCH_STREAM_FETCH, false, false
                    ),
                    SwitchDef(
                        "获取时间窗", "只提醒 " + cfg.windowText() + " 内的帖子",
                        null, false, false, true, null, Action.WATCH_WINDOW
                    ),
                    SwitchDef(
                        "检查间隔", "自动检查间隔 " + cfg.intervalMin + " 分钟",
                        null, false, false, true, null, Action.WATCH_INTERVAL
                    ),
                )
            )
        )

        groups.add(
            SettingsGroup(
                "提醒方式", arrayOf(
                    SwitchDef(
                        "应用内横幅", "界面顶部弹出提醒",
                        App.KEY_WATCH_BANNER, true, false
                    ),
                    SwitchDef(
                        "系统通知", "通知栏提醒，可点击跳转",
                        App.KEY_WATCH_NOTIFY, true, false
                    ),
                )
            )
        )

        groups.add(
            SettingsGroup(
                "第三方推送", arrayOf(
                    SwitchDef(
                        "第三方推送", "转发新动态到外部渠道",
                        App.KEY_WATCH_PUSH_ENABLED, false, false
                    ),
                    SwitchDef(
                        "钉钉机器人", "webhook 地址或 access_token",
                        null, false, false, true, App.KEY_WATCH_PUSH_DINGTALK
                    ),
                    SwitchDef(
                        "WxPusher", "appToken|topicId 或 appToken|uid:UID",
                        null, false, false, true, App.KEY_WATCH_PUSH_WXPUSHER
                    ),
                    SwitchDef(
                        "AstrBot 机器人",
                        "http://主机:6199|群号|令牌",
                        null, false, false, true, App.KEY_WATCH_PUSH_ONEBOT
                    ),
                    SwitchDef(
                        "自定义 webhook", "支持 {title}{link} 等占位符",
                        null, false, false, true, App.KEY_WATCH_PUSH_CUSTOM
                    ),
                )
            )
        )

        val testRows = ArrayList<SwitchDef>()
        testRows.add(
            SwitchDef(
                "测试提醒", "发送一条测试消息",
                null, false, false, true, null, Action.WATCH_TEST_PUSH
            )
        )
        testRows.add(
            SwitchDef(
                "立即检查", "手动检查一次",
                null, false, false, true, null, Action.WATCH_CHECK
            )
        )
        testRows.add(
            SwitchDef(
                "调试：推送最近 3 条",
                "立即推送最近 3 条",
                null, false, false, true, null, Action.WATCH_DEBUG_PUSH3
            )
        )
        groups.add(SettingsGroup("测试与调试", testRows.toTypedArray()))

        return groups
    }

    private fun currentWatchWindowMin(): Int {
        val cfg = com.better.heybox.watch.WatchConfig.load(module)
        return cfg.windowMin
    }

    private fun currentWatchIntervalMin(): Int {
        val cfg = com.better.heybox.watch.WatchConfig.load(module)
        return cfg.intervalMin
    }

    private fun showWatchWindowDialog(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showWatchWindowDialogNative(activity, spec) },
            Runnable { showWatchWindowDialogFallback(activity) }
        )
    }

    @Throws(Exception::class)
    private fun showWatchWindowDialogNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val list = buildOptionRowList(
            activity, WATCH_WINDOW_LABELS,
            nearestIndex(WATCH_WINDOW_MINUTES, currentWatchWindowMin())
        )
        val dialog = spec.buildAndShow(
            activity, "获取时间窗", list, null, null,
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
        bindOptionRows(dialog, list, OptionPick { index -> applyWatchWindow(activity, index) })
    }

    private fun showWatchWindowDialogFallback(activity: Activity) {
        showSingleChoiceFallback(
            activity, "获取时间窗", WATCH_WINDOW_LABELS,
            nearestIndex(WATCH_WINDOW_MINUTES, currentWatchWindowMin()),
            OptionPick { index -> applyWatchWindow(activity, index) }
        )
    }

    private fun applyWatchWindow(activity: Activity, index: Int) {
        try {
            HeyboxPrefs.init(activity)
            HeyboxPrefs.setString(App.KEY_WATCH_WINDOW_MIN, WATCH_WINDOW_MINUTES[index].toString())
            LogRecorder.recordEvent("动态推送时间窗: " + WATCH_WINDOW_LABELS[index])
            Toast.makeText(
                activity, "获取时间窗已设为 " + WATCH_WINDOW_LABELS[index],
                Toast.LENGTH_SHORT
            ).show()
            refreshEmbeddedPanel(activity)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "设置获取时间窗失败: " + t)
        }
    }

    private fun showWatchIntervalDialog(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec -> showWatchIntervalDialogNative(activity, spec) },
            Runnable { showWatchIntervalDialogFallback(activity) }
        )
    }

    @Throws(Exception::class)
    private fun showWatchIntervalDialogNative(activity: Activity, spec: DexKitResolver.HeyboxDialogSpec) {
        val list = buildOptionRowList(
            activity, WATCH_INTERVAL_LABELS,
            nearestIndex(WATCH_INTERVAL_MINUTES, currentWatchIntervalMin())
        )
        val dialog = spec.buildAndShow(
            activity, "检查间隔", list, null, null,
            "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
        )
        bindOptionRows(dialog, list, OptionPick { index -> applyWatchInterval(activity, index) })
    }

    private fun showWatchIntervalDialogFallback(activity: Activity) {
        showSingleChoiceFallback(
            activity, "检查间隔", WATCH_INTERVAL_LABELS,
            nearestIndex(WATCH_INTERVAL_MINUTES, currentWatchIntervalMin()),
            OptionPick { index -> applyWatchInterval(activity, index) }
        )
    }

    private fun applyWatchInterval(activity: Activity, index: Int) {
        try {
            HeyboxPrefs.init(activity)
            HeyboxPrefs.setString(
                App.KEY_WATCH_INTERVAL_MIN,
                WATCH_INTERVAL_MINUTES[index].toString()
            )
            LogRecorder.recordEvent("动态推送检查间隔: " + WATCH_INTERVAL_LABELS[index])
            Toast.makeText(
                activity, "检查间隔已设为 " + WATCH_INTERVAL_LABELS[index],
                Toast.LENGTH_SHORT
            ).show()
            refreshEmbeddedPanel(activity)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "设置检查间隔失败: " + t)
        }
    }

    private fun importWatchTopics(activity: Activity) {
        Toast.makeText(activity, "正在读取关注话题…", Toast.LENGTH_SHORT).show()
        Thread({
            val msg = arrayOfNulls<String>(1)
            try {
                val list = com.better.heybox.watch.WatchFetcher.fetchFollowedTopics(
                    com.better.heybox.watch.WatchConfig.MAX_TOPICS
                )
                if (list.isEmpty()) {
                    msg[0] = "未取到关注话题（详情见模块日志）"
                } else {
                    val exist = com.better.heybox.watch.WatchConfig
                        .splitLines(module.getString(App.KEY_WATCH_TOPICS, ""), 999)
                    val set = java.util.LinkedHashSet(exist)
                    var added = 0
                    for (t in list) {
                        val line = com.better.heybox.watch.WatchConfig.formatTopic(t[0], t[1])
                        if (line.isEmpty() || set.contains(line) ||
                            set.size >= com.better.heybox.watch.WatchConfig.MAX_TOPICS
                        ) {
                            continue
                        }
                        set.add(line)
                        added++
                    }
                    if (added > 0) {
                        val sb = StringBuilder()
                        for (line in set) {
                            sb.append(line).append('\n')
                        }
                        val ok = HeyboxPrefs.setString(App.KEY_WATCH_TOPICS, sb.toString())
                        LogRecorder.recordEvent(
                            "导入关注话题已写入: added=" + added +
                                    ", total=" + set.size + ", ok=" + ok
                        )
                    }
                    msg[0] = if (added > 0) ("已导入 " + added + " 个话题，共 " + set.size +
                            " 个（重进面板可见）") else "没有新的话题可导入"
                }
            } catch (t: Throwable) {
                msg[0] = "导入失败：" + t
            }
            val out = msg[0]
            activity.runOnUiThread {
                try {
                    Toast.makeText(activity, out, Toast.LENGTH_LONG).show()
                    refreshEmbeddedPanel(activity)
                } catch (ignored: Throwable) {
                }
            }
        }, "betterheybox-watch-topic-import").start()
    }

    private fun showTopicSearchDialog(activity: Activity) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                val input = buildTopicSearchInput(activity)
                spec.buildAndShow(
                    activity, "搜索话题", input, "搜索",
                    DialogInterface.OnClickListener { _, _ ->
                        runTopicSearch(activity, input.text.toString())
                    },
                    "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                )
            },
            Runnable {
                try {
                    val input = buildTopicSearchInput(activity)
                    AlertDialog.Builder(activity)
                        .setTitle("搜索话题")
                        .setView(input)
                        .setPositiveButton("搜索", DialogInterface.OnClickListener { _, _ ->
                            runTopicSearch(activity, input.text.toString())
                        })
                        .setNegativeButton("取消", null)
                        .show()
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "搜索话题弹窗失败: " + t)
                }
            }
        )
    }

    private fun buildTopicSearchInput(activity: Activity): EditText {
        val input = EditText(activity)
        val pad = module.dp(activity, 10f)
        input.setPadding(pad, pad, pad, pad)
        input.setSingleLine(true)
        input.setInputType(InputType.TYPE_CLASS_TEXT)
        input.setHint("例如：原神 / 数码硬件")
        val bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0)
        if (bgId != 0) {
            input.setBackgroundResource(bgId)
        }
        return input
    }

    private fun runTopicSearch(activity: Activity, keyword: String?) {
        val kw = if (keyword == null) "" else keyword.trim()
        if (kw.isEmpty()) {
            Toast.makeText(activity, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "正在搜索「" + kw + "」…", Toast.LENGTH_SHORT).show()
        Thread({
            val found = ArrayList<Array<String>>()
            try {
                found.addAll(com.better.heybox.watch.WatchFetcher.fetchTopicSearch(kw, 10))
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "话题搜索失败: " + t)
            }
            activity.runOnUiThread {
                try {
                    if (found.isEmpty()) {
                        Toast.makeText(
                            activity, "没搜到话题（详情见模块日志）",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    val labels = arrayOfNulls<String>(found.size)
                    for (i in found.indices) {
                        val id = found[i][0]
                        labels[i] = found[i][1] + (if (id == null) "" else ("  #" + id))
                    }
                    val labelArr = labels.requireNoNulls()
                    withHeyboxDialog(
                        activity,
                        NativeDialogCall { spec ->
                            val list = buildOptionRowList(activity, labelArr, -1)
                            val dialog = spec.buildAndShow(
                                activity,
                                "搜索结果（点击加入）", list, null, null,
                                "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                            )
                            bindOptionRows(dialog, list, OptionPick { index ->
                                addWatchTopic(
                                    activity,
                                    found[index][0], found[index][1]
                                )
                            })
                        },
                        Runnable {
                            showListPickFallback(
                                activity, "搜索结果（点击加入）", labelArr,
                                OptionPick { index ->
                                    addWatchTopic(
                                        activity, found[index][0],
                                        found[index][1]
                                    )
                                }
                            )
                        }
                    )
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "话题搜索结果弹窗失败: " + t)
                }
            }
        }, "betterheybox-topic-search").start()
    }

    private fun addWatchTopic(activity: Activity, id: String?, name: String) {
        try {
            HeyboxPrefs.init(activity)
            val line = com.better.heybox.watch.WatchConfig.formatTopic(id, name)
            if (line.isEmpty()) {
                return
            }
            val exist = ArrayList(
                com.better.heybox.watch.WatchConfig
                    .splitLines(module.getString(App.KEY_WATCH_TOPICS, ""), 999)
            )
            for (e in exist) {
                if (e == line || com.better.heybox.watch.WatchConfig.topicName(e) == name) {
                    Toast.makeText(
                        activity, "「" + name + "」已在关注的话题里",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
            }
            if (exist.size >= com.better.heybox.watch.WatchConfig.MAX_TOPICS) {
                Toast.makeText(
                    activity, "关注的话题最多 " +
                            com.better.heybox.watch.WatchConfig.MAX_TOPICS + " 个",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            exist.add(line)
            val sb = StringBuilder()
            for (l in exist) {
                sb.append(l).append('\n')
            }
            HeyboxPrefs.setString(App.KEY_WATCH_TOPICS, sb.toString())
            LogRecorder.recordEvent("已加入关注话题: " + line)
            Toast.makeText(activity, "已加入话题：" + name, Toast.LENGTH_SHORT).show()
            refreshEmbeddedPanel(activity)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "加入话题失败: " + t)
        }
    }

    private fun suggestWatchKeywords(activity: Activity) {
        Toast.makeText(activity, "正在获取推荐关键词…", Toast.LENGTH_SHORT).show()
        Thread({
            val words = ArrayList<String>()
            try {
                words.addAll(com.better.heybox.watch.WatchFetcher.fetchHotWords(null, 12))
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "推荐关键词获取失败: " + t)
            }
            activity.runOnUiThread {
                try {
                    if (words.isEmpty()) {
                        Toast.makeText(
                            activity, "没取到推荐关键词（详情见模块日志）",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    showWatchSuggestDialog(activity, words.toTypedArray())
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "推荐关键词弹窗失败: " + t)
                }
            }
        }, "betterheybox-watch-suggest").start()
    }

    private fun showWatchSuggestDialog(activity: Activity, labels: Array<String>) {
        withHeyboxDialog(
            activity,
            NativeDialogCall { spec ->
                val list = buildOptionRowList(activity, labels, -1)
                val dialog = spec.buildAndShow(
                    activity, "推荐关键词（点击添加）", list, null, null,
                    "取消", DialogInterface.OnClickListener { d, _ -> d.dismiss() }
                )
                bindOptionRows(dialog, list, OptionPick { index ->
                    addWatchKeyword(activity, labels[index])
                })
            },
            Runnable {
                showListPickFallback(
                    activity, "推荐关键词（点击添加）", labels,
                    OptionPick { index -> addWatchKeyword(activity, labels[index]) }
                )
            }
        )
    }

    private fun showListPickFallback(
        activity: Activity, title: String,
        labels: Array<String>, onPick: OptionPick
    ) {
        try {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(labels, DialogInterface.OnClickListener { dialog, which ->
                    onPick.pick(which)
                    dialog.dismiss()
                })
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "列表弹框失败(" + title + "): " + t)
        }
    }

    private fun addWatchKeyword(activity: Activity, word: String) {
        try {
            HeyboxPrefs.init(activity)
            val exist = ArrayList(
                com.better.heybox.watch.WatchConfig
                    .splitLines(module.getString(App.KEY_WATCH_KEYWORDS, ""), 999)
            )
            for (e in exist) {
                if (e == word) {
                    Toast.makeText(activity, "「" + word + "」已在关键词里", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            if (exist.size >= com.better.heybox.watch.WatchConfig.MAX_KEYWORDS) {
                Toast.makeText(
                    activity, "关键词最多 " +
                            com.better.heybox.watch.WatchConfig.MAX_KEYWORDS + " 个",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            exist.add(word)
            val sb = StringBuilder()
            for (line in exist) {
                sb.append(line).append('\n')
            }
            HeyboxPrefs.setString(App.KEY_WATCH_KEYWORDS, sb.toString())
            LogRecorder.recordEvent("推荐关键词已加入: " + word)
            Toast.makeText(activity, "已添加关键词：" + word, Toast.LENGTH_SHORT).show()
            refreshEmbeddedPanel(activity)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "添加关键词失败: " + t)
        }
    }

    private fun importWatchFollowing(activity: Activity) {
        Toast.makeText(activity, "正在读取关注列表…", Toast.LENGTH_SHORT).show()
        Thread({
            val msg = arrayOfNulls<String>(1)
            try {
                val list = com.better.heybox.watch.WatchFetcher
                    .fetchFollowing(com.better.heybox.watch.WatchFetcher.FOLLOW_IMPORT_LIMIT)
                if (list.isEmpty()) {
                    msg[0] = "未取到关注列表：请确认小黑盒已登录（详情见模块日志）"
                } else {
                    val exist = com.better.heybox.watch.WatchConfig
                        .splitLines(module.getString(App.KEY_WATCH_USERS, ""), 999)
                    val set = java.util.LinkedHashSet(exist)
                    var added = 0
                    for (u in list) {
                        val uid = u[0]
                        var dup = false
                        for (e in set) {
                            val parsed = com.better.heybox.watch.WatchConfig.parseUserId(e)
                            if (uid == parsed) {
                                dup = true
                                break
                            }
                        }
                        if (dup || set.size >= com.better.heybox.watch.WatchConfig.MAX_USERS) {
                            continue
                        }
                        set.add(if (u[1] == null || u[1].isEmpty()) uid else (uid + "  # " + u[1]))
                        added++
                    }
                    if (added > 0) {
                        val sb = StringBuilder()
                        for (line in set) {
                            sb.append(line).append('\n')
                        }
                        val ok = HeyboxPrefs.setString(App.KEY_WATCH_USERS, sb.toString())
                        LogRecorder.recordEvent(
                            "导入关注列表已写入: added=" + added +
                                    ", total=" + set.size + ", ok=" + ok
                        )
                    }
                    msg[0] = if (added > 0) ("已导入 " + added + " 个关注，共 " + set.size +
                            " 个（重进面板可见）")
                    else "没有新的关注对象可导入"
                }
            } catch (t: Throwable) {
                msg[0] = "导入失败：" + t
            }
            activity.runOnUiThread {
                try {
                    Toast.makeText(activity, msg[0], Toast.LENGTH_LONG).show()
                } catch (ignored: Throwable) {
                }
            }
        }, "betterheybox-watch-import").start()
    }

    private fun testWatchPush(activity: Activity) {
        Toast.makeText(activity, "正在发送测试提醒…", Toast.LENGTH_SHORT).show()
        try {
            val cfg = com.better.heybox.watch.WatchConfig.load(module)
            val item = com.better.heybox.watch.WatchItem(
                "betterheybox-test", "测试消息 · BetterHeybox",
                "这是一条测试提醒：关注对象发布新动态 / 关键词命中时会这样提示",
                "0", "BetterHeybox", System.currentTimeMillis() / 1000L, "keyword", ""
            )
            val banner = com.better.heybox.watch.WatchOutput.testBanner(activity, item)
            val notify = com.better.heybox.watch.WatchOutput.notifyPost(activity, item)
            Thread({
                val msg = arrayOfNulls<String>(1)
                try {
                    val n = com.better.heybox.watch.WatchOutput.pushAll(cfg, item)
                    val push = if (!cfg.pushEnabled) "推送未开启"
                    else (if (n > 0) ("推送 " + n + " 个渠道") else "推送失败：检查地址")
                    msg[0] = (if (banner) "横幅 ✓" else "横幅 ✗") +
                            " · " + (if (notify) "通知 ✓" else "通知 ✗") + " · " + push
                } catch (t: Throwable) {
                    msg[0] = "推送测试异常：" + t
                }
                activity.runOnUiThread {
                    try {
                        Toast.makeText(activity, msg[0], Toast.LENGTH_LONG).show()
                    } catch (ignored: Throwable) {
                    }
                }
            }, "betterheybox-watch-test").start()
        } catch (t: Throwable) {
            Toast.makeText(activity, "测试失败：" + t, Toast.LENGTH_LONG).show()
        }
    }

    private fun insertPostFilterGroup(groups: MutableList<SettingsGroup>) {
        var minLevel = 0
        try {
            minLevel = Integer.parseInt(module.getString(App.KEY_POST_MIN_LEVEL, "0")!!.trim())
        } catch (ignored: Throwable) {
        }
        var kwCount = 0
        for (s in module.getString(App.KEY_POST_KEYWORDS, "")!!.split(Regex("\n"))) {
            if (!s.trim().isEmpty()) {
                kwCount++
            }
        }
        val providerId = module.getString(App.KEY_AI_PROVIDER, "")
        val minLike = currentThreshold(App.KEY_POST_MIN_LIKE)
        val minComment = currentThreshold(App.KEY_POST_MIN_COMMENT)
        val minFavour = currentThreshold(App.KEY_POST_MIN_FAVOUR)
        val group = SettingsGroup(
            "发帖过滤", arrayOf(
                SwitchDef(
                    "屏蔽视频帖",
                    "信息流中隐藏视频帖；",
                    App.KEY_BLOCK_VIDEO_POST, false, false
                ),
                SwitchDef(
                    "屏蔽低赞帖子",
                    if (minLike > 0) "当前：点赞 < " + minLike else "选择点赞数阈值",
                    null, false, false, true, null, Action.POST_MIN_LIKE
                ),
                SwitchDef(
                    "屏蔽低评论帖子",
                    if (minComment > 0) "当前：评论 < " + minComment else "选择评论数阈值",
                    null, false, false, true, null, Action.POST_MIN_COMMENT
                ),
                SwitchDef(
                    "屏蔽低收藏帖子",
                    if (minFavour > 0) "当前：收藏 < " + minFavour + "；无收藏数据的列表自动放行"
                    else "选择收藏数阈值；无收藏数据的列表自动放行",
                    null, false, false, true, null, Action.POST_MIN_FAVOUR
                ),
                SwitchDef(
                    "屏蔽低等级发帖",
                    if (minLevel > 0) "当前：屏蔽 Lv" + minLevel + " 以下"
                    else "选择等级阈值",
                    null, false, false, true, null, Action.POST_LEVEL
                ),
                SwitchDef(
                    "屏蔽无等级用户",
                    "无等级账号一并屏蔽",
                    App.KEY_POST_NO_LEVEL, false, false
                ),
                SwitchDef(
                    "关键词屏蔽",
                    if (kwCount > 0) "已配置 " + kwCount + " 个"
                    else "命中标题或正文即屏蔽",
                    null, false, false, true, null, Action.POST_KEYWORDS
                ),
                SwitchDef(
                    "AI 标题党识别",
                    "标题会发送给 AI 服务商",
                    App.KEY_POST_AI_ENABLED, false, false
                ),
                SwitchDef(
                    "AI 提供商",
                    "当前：" + AIClickbaitChecker.providerLabel(providerId),
                    null, false, false, true, null, Action.AI_PROVIDER
                ),
                SwitchDef(
                    "API 地址", "OpenAI 兼容接口地址",
                    null, false, false, true, App.KEY_AI_BASE_URL, Action.EDIT_LINK
                ),
                SwitchDef(
                    "模型", "OpenAI 兼容模型名", null, false, false,
                    true, App.KEY_AI_MODEL, Action.EDIT_LINK
                ),
                SwitchDef(
                    "API Token", "本地模型可留空",
                    null, false, false, true, App.KEY_AI_TOKEN, Action.EDIT_LINK
                ),
                SwitchDef(
                    "判定提示词", "留空用内置提示词",
                    null, false, false, true, null, Action.AI_PROMPT
                ),
                SwitchDef(
                    "输出 Token 上限",
                    "当前：" + AIClickbaitChecker.maxTokens(module) + "；" +
                            "过小会截断结果",
                    null, false, false, true, null, Action.AI_MAX_TOKENS
                ),
                SwitchDef(
                    "测试 AI 连接", null, null, false, false,
                    true, null, Action.AI_TEST
                ),
            )
        )
        var insertAt = groups.size
        for (i in groups.indices) {
            if (TITLE_AD_FILTER == groups[i].title) {
                insertAt = i + 1
                break
            }
        }
        groups.add(insertAt, group)
    }

    private fun insertCommentFilterGroup(groups: MutableList<SettingsGroup>) {
        val kwCount = countConfiguredLines(module.getString(App.KEY_COMMENT_KEYWORDS, "")!!)
        val group = SettingsGroup(
            "评论过滤", arrayOf(
                SwitchDef(
                    "屏蔽插眼评论",
                    "在数据/列表层直接摘掉带 Cy 标的评论",
                    App.KEY_HOST_HIDE_CY, true, false
                ),
                SwitchDef(
                    "评论关键词屏蔽/ 无意义评论",
                    "关键词屏蔽评论",
                    App.KEY_BLOCK_CY_COMMENT, false, false
                ),
                SwitchDef(
                    "评论关键词屏蔽",
                    if (kwCount > 0) "已配置 " + kwCount + " 个"
                    else "命中评论正文即屏蔽",
                    null, false, false, true, null, Action.COMMENT_KEYWORDS
                ),
                SwitchDef(
                    "屏蔽游戏名接龙",
                    "屏蔽正文仅为游戏链接的评论",
                    App.KEY_BLOCK_GAME_RELAY, false, false
                ),
                SwitchDef(
                    "接龙判定忽略表情",
                    "[cube_xxx] 不计入残留字数",
                    App.KEY_RELAY_IGNORE_EMOJI, false, false
                ),
                SwitchDef(
                    "评论过滤状态",
                    "查看安装结果与各层命中计数",
                    null, false, false, true, null, Action.COMMENT_FILTER_DIAG
                ),
            )
        )
        var insertAt = groups.size
        for (i in groups.indices) {
            if ("发帖过滤" == groups[i].title) {
                insertAt = i + 1
                break
            }
        }
        groups.add(insertAt, group)
    }

    private fun insertBrowserRedirectGroup(activity: Activity, groups: MutableList<SettingsGroup>) {
        val forceCount = countConfiguredLines(module.getString(App.KEY_BROWSER_REDIRECT_FORCE, "")!!)
        val blockCount = countConfiguredLines(module.getString(App.KEY_BROWSER_REDIRECT_BLOCK, "")!!)
        val group = SettingsGroup(
            "网页", arrayOf(
                SwitchDef(
                    "重定向外部链接",
                    "外部链接用系统浏览器打开", App.KEY_BROWSER_REDIRECT, false, false
                ),
                SwitchDef(
                    "包含小黑盒域名",
                    "小黑盒域名也重定向",
                    App.KEY_BROWSER_REDIRECT_KNOWN, false, false
                ),
                SwitchDef(
                    "重定向浏览器",
                    "当前：" + browserTargetLabel(activity),
                    null, false, false, true, null, Action.REDIRECT_TARGET
                ),
                SwitchDef(
                    "强制重定向域名",
                    if (forceCount > 0) "已配置 " + forceCount + " 个"
                    else "一行一个域名",
                    null, false, false, true, null, Action.REDIRECT_FORCE
                ),
                SwitchDef(
                    "强制内置域名",
                    if (blockCount > 0) "已配置 " + blockCount + " 个"
                    else "一行一个域名",
                    null, false, false, true, null, Action.REDIRECT_BLOCK
                ),
                SwitchDef(
                    "网页 DevTools", "开启 Chrome 远程调试",
                    App.KEY_WEBVIEW_DEVTOOLS, false, false
                ),
                SwitchDef(
                    "打开网页", "用内置浏览器打开网页", null, false, false,
                    true, App.KEY_WEBVIEW_ENTRY_URL, Action.OPEN_WEB
                ),
                SwitchDef(
                    "网页日志",
                    "记录打开过的页面与标题", App.KEY_WEB_LOG, false, false
                ),
                SwitchDef("查看网页日志", null, null, false, false, true, null, Action.WEB_LOG),
            )
        )
        var insertAt = groups.size
        for (i in groups.indices) {
            if (TITLE_SHARE_PURIFY == groups[i].title) {
                insertAt = i + 1
                break
            }
        }
        groups.add(insertAt, group)
    }

    private fun buildGlassGroup(activity: Activity): SettingsGroup? {
        val switchable = GlassProvider.isHbmodInstalled(activity) ||
                GlassProvider.prefersHbmod(module)
        val ownGlass = !GlassProvider.prefersHbmod(module)
        val rows = ArrayList<SwitchDef>()
        if (switchable) {
            val label = GlassProvider.providerLabel(
                module.getString(App.KEY_GLASS_PROVIDER, "")
            )
            rows.add(
                SwitchDef(
                    "液态玻璃提供方",
                    "当前：" + label, null, false, false, true, null, Action.CHOOSE_GLASS
                )
            )
        }
        if (ownGlass) {
            rows.add(SwitchDef("液态玻璃底栏", "底栏显示液态玻璃", App.KEY_LIQUID_GLASS, true, false))
            rows.add(SwitchDef("沉浸式小白条", "底栏延伸至手势区域", App.KEY_GLASS_IMMERSIVE, true, false))
            rows.add(SwitchDef("自适应反色", "文字图标随背景反色", App.KEY_GLASS_ADAPTIVE, true, false))
            rows.add(
                SwitchDef(
                    "加长选中 Tab",
                    "隐藏标签后选中项加长，底栏随可见数量收缩",
                    App.KEY_GLASS_FIT_TABS, false, false
                )
            )
            rows.add(
                SwitchDef(
                    "玻璃条宽度",
                    "宽度模式 / 左右边距 / Tab 宽度，打开调节面板",
                    null, false, false, true, null, Action.GLASS_SHEET
                )
            )
            rows.add(SwitchDef("暗色模式底色", "例如 #000000", null, false, false, true, App.KEY_GLASS_DARK_COLOR))
            rows.add(SwitchDef("暗色模式不透明度", "5-98 的百分比", null, false, false, true, App.KEY_GLASS_DARK_ALPHA))
            rows.add(SwitchDef("亮色模式底色", "例如 #FFFFFF", null, false, false, true, App.KEY_GLASS_LIGHT_COLOR))
            rows.add(SwitchDef("亮色模式不透明度", "5-98 的百分比", null, false, false, true, App.KEY_GLASS_LIGHT_ALPHA))
            rows.add(SwitchDef("玻璃条高度", "0 自动，或 51-99 dp", null, false, false, true, App.KEY_GLASS_BAR_HEIGHT))
            rows.add(SwitchDef("距屏幕底部", "0-40 dp", null, false, false, true, App.KEY_GLASS_BAR_OFFSET))
            rows.add(SwitchDef("恢复液态玻璃默认设置", "恢复默认外观与布局", null, false, false, true, null, Action.RESET_GLASS))
        }
        if (rows.isEmpty()) {
            return null
        }
        return SettingsGroup("液态玻璃", rows.toTypedArray())
    }

    private fun buildEntryCard(activity: Activity): View? {
        try {
            val cl = activity.classLoader
            val cardPair = buildHostCard(activity, cl)
            val card = cardPair[0]
            val content = cardPair[1] as LinearLayout
            val itemCls: Class<*> = Class.forName(
                "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl
            )
            val item: Any = itemCls.getConstructor(Context::class.java).newInstance(activity)
            itemCls.getMethod("setTitle", String::class.java).invoke(item, "BetterHeybox 设置")
            try {
                itemCls.getMethod("setTitleDesc", String::class.java).invoke(item, "广告过滤与界面增强")
            } catch (ignored: Throwable) {
            }
            val typeEnum: Class<*> = Class.forName(
                "com.max.xiaoheihe.module.account.component.SettingItemView\$Type", false, cl
            )
            val arrow = enumConstant(typeEnum, "Arrow")
            itemCls.getMethod("setRightType", typeEnum).invoke(item, arrow)
            try {
                itemCls.getMethod("setShowBottomDivider", java.lang.Boolean.TYPE).invoke(item, true)
            } catch (ignored: Throwable) {
            }
            val itemH = module.dp(activity, 48f)
            (item as View).setLayoutParams(
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH)
            )
            content.addView(item)
            return card as View
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "构建原生入口卡片失败: " + t)
            return null
        }
    }
    internal class SwitchDef internal constructor(
        @JvmField internal val title: String?,
        @JvmField internal val desc: String?,
        @JvmField internal val key: String?,
        @JvmField internal val def: Boolean,
        @JvmField internal val restart: Boolean,
        @JvmField internal val clickRow: Boolean,
        @JvmField internal val editKey: String?,
        @JvmField internal val action: Action
    ) {
        internal constructor(title: String?, desc: String?, key: String?, def: Boolean, restart: Boolean) :
                this(title, desc, key, def, restart, false, null, Action.NONE)

        internal constructor(
            title: String?, desc: String?, key: String?, def: Boolean, restart: Boolean,
            clickRow: Boolean, editKey: String?
        ) : this(title, desc, key, def, restart, clickRow, editKey, Action.EDIT_LINK)
    }

    internal class SettingsGroup(
        @JvmField internal val title: String,
        @JvmField internal val items: Array<SwitchDef>
    )

    internal enum class Action {
        NONE, EDIT_LINK, CLEAR_DAILY, CHANNEL, EXPORT, IMPORT,
        EXPORT_LOG, CLEAR_LOG, VIEW_LOG, RUNTIME_STATUS, TARGET_STATUS, OPEN_WEB, PICK_DIR, RESET_GLASS,
        CHOOSE_GLASS, GLASS_SHEET,
        POST_LEVEL, POST_KEYWORDS, COMMENT_KEYWORDS, COMMENT_FILTER_DIAG, AI_PROVIDER, AI_PROMPT, AI_TEST,
        AI_MAX_TOKENS,
        POST_MIN_LIKE, POST_MIN_COMMENT, POST_MIN_FAVOUR,
        REDIRECT_FORCE, REDIRECT_BLOCK, REDIRECT_TARGET, WEB_LOG, ABOUT,
        WATCH_USERS, WATCH_KEYWORDS, WATCH_IMPORT_FOLLOW, WATCH_TEST_PUSH, WATCH_CHECK,
        WATCH_DEBUG_PUSH3, WATCH_V2, OPEN_PAGE,
        WATCH_TOPICS, WATCH_IMPORT_TOPICS, WATCH_WINDOW, WATCH_INTERVAL, WATCH_SUGGEST_KEYWORDS,
        WATCH_TOPIC_SEARCH, GAME_LIB_TYPES, GAME_LIB_ENTRIES, GAME_LIB_SECTIONS, GAME_LIB_DIAG,
        MESSAGE_BADGE_ENTRIES, MESSAGE_FULL_HIDE_ENTRIES, MESSAGE_BADGE_DIAG
    }

    internal fun interface PickCallback {
        fun onResult(uri: Uri)
    }

    internal fun interface OptionPick {
        fun pick(index: Int)
    }

    internal fun interface PickSaver {
        fun save(activity: Activity, title: String, picked: Set<String>)
    }

    internal fun interface NativeDialogCall {
        @Throws(Throwable::class)
        fun call(spec: DexKitResolver.HeyboxDialogSpec)
    }

    companion object {

        private fun stringify(v: Any?): String = if (v == null) "null" else v.toString()

        private fun enumConstant(enumClass: Class<*>, name: String): Any {
            @Suppress("UNCHECKED_CAST")
            return java.lang.Enum.valueOf(enumClass as Class<out Enum<*>>, name)
        }

        private const val ENTRY_TAG = "betterheybox_entry"

        private const val REQUEST_EMBEDDED_EXPORT = 0x4248
        private const val REQUEST_EMBEDDED_IMPORT = 0x4249
        private const val REQUEST_EMBEDDED_LOG_EXPORT = 0x424A
        private const val REQUEST_PICK_SAVE_DIR = 0x424B

        @Volatile
        private var sPendingPick: PickCallback? = null

        @Volatile
        private var sLaunchPromptShown: Boolean = false

        private val BASE_GROUPS: Array<SettingsGroup> = arrayOf(
            SettingsGroup(
                "广告过滤", arrayOf(
                    SwitchDef("屏蔽开屏广告", null, App.KEY_OPEN_SCREEN, true, false),
                    SwitchDef("屏蔽信息流广告", null, App.KEY_FEED_AD, true, false),
                    SwitchDef("屏蔽气泡广告", null, App.KEY_BUBBLE_AD, true, false),
                    SwitchDef("屏蔽角标广告", null, App.KEY_CORNER_AD, true, false),
                    SwitchDef(
                        "屏蔽推广贴", "首页推广卡、广告横幅、社区推广贴与广告位",
                        App.KEY_PROMOTE_AD, true, false
                    ),
                    SwitchDef(
                        "诊断：记录首页流条目", "把每条首页流的判定信息写进日志",
                        App.KEY_FLOW_DIAGNOSE, false, false
                    ),
                )
            ),
            SettingsGroup(
                "视频下载", arrayOf(
                    SwitchDef("下载视频", "视频上显示下载入口", App.KEY_VIDEO_DOWNLOAD, true, false),
                    SwitchDef("保存位置", "选择保存文件夹", null, false, false, true, null, Action.PICK_DIR),
                    SwitchDef("转存 MP4", "合并后转为 MP4", App.KEY_VIDEO_TO_MP4, true, false),
                )
            ),
            SettingsGroup(
                "解除复制", arrayOf(
                    SwitchDef("解除复制", "恢复系统文本选择", App.KEY_COPY_POST, true, false),
                    SwitchDef("自绘制文本选择", "修复选区异常", App.KEY_CUSTOM_TEXT_SELECT, false, false),
                    SwitchDef("评论区自由复制", "长按菜单的复制可自由选择", App.KEY_COMMENT_FREE_COPY, true, false),
                    SwitchDef("系统分享图片", "图片长按加入系统分享", App.KEY_SYSTEM_SHARE, true, false),
                )
            ),
            SettingsGroup(
                "搜索页精简", arrayOf(
                    SwitchDef(
                        "隐藏搜索页横幅", "搜索栏下方横幅推荐",
                        App.KEY_SEARCH_HIDE_BANNER, false, false
                    ),
                    SwitchDef(
                        "隐藏「搜索发现」", "搜索页的搜索发现标题与推荐列表",
                        App.KEY_SEARCH_HIDE_DISCOVER, false, false
                    ),
                    SwitchDef(
                        "隐藏「黑盒热榜」", "搜索页的热榜标签页与热词卡片",
                        App.KEY_SEARCH_HIDE_HOT_RANK, false, false
                    ),
                )
            ),
            SettingsGroup(
                "分享净化", arrayOf(
                    SwitchDef("净化分享链接", null, App.KEY_PURIFY_SHARE_LINK, true, false),
                )
            ),
            SettingsGroup(
                "收藏管理", arrayOf(
                    SwitchDef(
                        "自动清理失效收藏",
                        "打开收藏列表发现失效内容时自动清理",
                        App.KEY_FAVOUR_AUTO_CLEAN, false, false
                    ),
                )
            ),
            SettingsGroup(
                "每日任务", arrayOf(
                    SwitchDef("自动完成每日分享任务", null, App.KEY_DAILY_TASK_ENABLED, false, false),
                    SwitchDef("完成后返回首页", "完成后自动退回首页", App.KEY_DAILY_TASK_BACK_HOME, true, false),
                    SwitchDef("帖子链接", "任务一：分享帖子", null, false, false, true, App.KEY_DAILY_TASK_PICTURE),
                    SwitchDef("游戏详情链接", "任务二：分享游戏详情", null, false, false, true, App.KEY_DAILY_TASK_NORMAL),
                    SwitchDef("游戏评价链接", "任务三：分享游戏评价", null, false, false, true, App.KEY_DAILY_TASK_CHANNEL),
                    SwitchDef("分享渠道", null, App.KEY_SHARE_CHANNEL, false, false, true, null, Action.CHANNEL),
                    SwitchDef("清除今日打卡", null, null, false, false, true, null, Action.CLEAR_DAILY),
                )
            ),
            SettingsGroup(
                "通用", arrayOf(
                    SwitchDef("伪装通知权限", "伪装通知已开启，获得签到加成", App.KEY_FAKE_NOTIFICATION, false, false),
                    SwitchDef("屏蔽更新", "屏蔽小黑盒更新入口", App.KEY_BLOCK_UPDATE, false, false),
                    SwitchDef("记录日志", null, App.KEY_LOG, false, false),
                    SwitchDef(
                        "详细日志", "关闭时只记错误日志；开启后记录全部并附带帖子信息",
                        App.KEY_VERBOSE_LOG, false, false
                    ),
                    SwitchDef(
                        "查看日志", "预览最近 200 行模块日志", null, false, false,
                        true, null, Action.VIEW_LOG
                    ),
                    SwitchDef(
                        "清除日志", "删除模块日志文件与运行检查点", null, false, false,
                        true, null, Action.CLEAR_LOG
                    ),
                    SwitchDef("导出日志", null, null, false, false, true, null, Action.EXPORT_LOG),
                )
            ),
            SettingsGroup(
                "配置备份", arrayOf(
                    SwitchDef("导出配置", null, null, false, false, true, null, Action.EXPORT),
                    SwitchDef("导入配置", null, null, false, false, true, null, Action.IMPORT),
                )
            ),
            SettingsGroup(
                "关于", arrayOf(
                    SwitchDef(
                        "关于 BetterHeybox", "版本与 GitHub 仓库",
                        null, false, false, true, null, Action.ABOUT
                    ),
                )
            ),
        )

        private fun buildGameLibGroup(): SettingsGroup {
            return SettingsGroup(
                "游戏库精简", arrayOf(
                    SwitchDef(
                        "隐藏游戏库横幅", "游戏库顶端横幅推荐",
                        App.KEY_GAME_LIB_HIDE_BANNER, false, false
                    ),
                    SwitchDef(
                        "隐藏游戏库小分区", "黑盒商城、小程序等入口卡片",
                        App.KEY_GAME_LIB_HIDE_MENU, false, false
                    ),
                    SwitchDef(
                        "隐藏游戏库推荐分区", "「为你推荐」等分区标题与内容卡",
                        App.KEY_GAME_LIB_HIDE_SECTIONS, false, false
                    ),
                    SwitchDef(
                        "自定义隐藏类型", picksDesc(
                            GameLibraryCleanHook.selectedTypes(),
                            "勾选要隐藏的 type"
                        ),
                        null, false, false, true, null, Action.GAME_LIB_TYPES
                    ),
                    SwitchDef(
                        "隐藏指定入口卡片", picksDesc(
                            GameLibraryCleanHook.selectedNames(GameLibraryCleanHook.PICK_ENTRY),
                            "勾选要隐藏的入口卡片"
                        ),
                        null, false, false, true, null, Action.GAME_LIB_ENTRIES
                    ),
                    SwitchDef(
                        "隐藏指定推荐分区", picksDesc(
                            GameLibraryCleanHook.selectedNames(GameLibraryCleanHook.PICK_SECTION),
                            "点这里勾选要隐藏的分区"
                        ),
                        null, false, false, true, null, Action.GAME_LIB_SECTIONS
                    ),
                    SwitchDef(
                        "诊断：游戏库精简状态", "目标解析",
                        null, false, false, true, null, Action.GAME_LIB_DIAG
                    ),
                )
            )
        }

        private fun buildMessageRedDotGroup(): SettingsGroup {
            return SettingsGroup(
                "消息红点", arrayOf(
                    SwitchDef(
                        "隐藏消息未读红点", "各页面右上角 ✉️ 的小红点（含底栏消息红点）",
                        App.KEY_HIDE_MSG_DOT, false, false
                    ),
                    SwitchDef(
                        "精简消息入口", "隐藏勾选入口的红色数字",
                        App.KEY_HIDE_MSG_BADGE, false, false
                    ),
                    SwitchDef(
                        "隐藏红数字的入口", picksDesc(
                            MessageRedDotHook.selectedNames(MessageRedDotHook.PICK_NUMBER),
                            "默认「活动消息」「官方消息」"
                        ),
                        null, false, false, true, null, Action.MESSAGE_BADGE_ENTRIES
                    ),
                    SwitchDef(
                        "隐藏相关入口", picksDesc(
                            MessageRedDotHook.selectedNames(MessageRedDotHook.PICK_FULL),
                            "整行移除；默认不隐藏任何入口"
                        ),
                        null, false, false, true, null, Action.MESSAGE_FULL_HIDE_ENTRIES
                    ),
                    SwitchDef(
                        "诊断：消息红点状态", "开关 / 勾选 / 已观察到的入口",
                        null, false, false, true, null, Action.MESSAGE_BADGE_DIAG
                    ),
                )
            )
        }

        private fun picksDesc(picked: Set<String>?, emptyHint: String): String {
            if (picked == null || picked.isEmpty()) {
                return emptyHint
            }
            val sb = StringBuilder("已选 " + picked.size + " 项：")
            var shown = 0
            for (value in picked) {
                if (shown == 3) {
                    sb.append(" 等")
                    break
                }
                if (shown > 0) {
                    sb.append('、')
                }
                sb.append(value)
                shown++
            }
            return sb.toString()
        }

        private fun buildBottomTabGroup(activity: Activity): SettingsGroup {
            val home = labelOr(
                BottomTabHook.runtimeTabLabel(0),
                MainModule.getHeyboxTabLabel(activity, "discover", "发现")
            )
            val slot2 = labelOr(
                BottomTabHook.runtimeTabLabel(1),
                MainModule.getHeyboxTabLabel(activity, "game_store", "游戏库")
            )
            val slot4 = labelOr(
                BottomTabHook.runtimeTabLabel(2),
                MainModule.getHeyboxTabLabel(activity, "bbs", "社区")
            )
            return SettingsGroup(
                "底部导航栏隐藏", arrayOf(
                    SwitchDef("隐藏「" + home + "」", null, App.KEY_HIDE_TAB_HOME, false, true),
                    SwitchDef("隐藏「" + slot2 + "」", null, App.KEY_HIDE_TAB_HOT, false, true),
                    SwitchDef("隐藏「" + slot4 + "」", null, App.KEY_HIDE_TAB_GAME, false, true),
                    SwitchDef("隐藏「加号」", null, App.KEY_HIDE_ADD, false, true),
                )
            )
        }

        private fun labelOr(runtime: String?, fallback: String?): String? {
            return if (runtime != null && !runtime.trim().isEmpty()) runtime else fallback
        }

        private const val TITLE_GENERAL = "通用"
        private const val EXPERIMENTAL_HEYBOX_VERSION = "1.3.396"
        private const val EXPERIMENTAL_HEYBOX_MIN_CODE = 1134L

        private const val PAGE_ADS = "ads"
        private const val PAGE_UI = "ui"
        private const val PAGE_BROWSE = "browse"
        private const val PAGE_WATCH = "watch"
        private const val PAGE_TASK = "task"
        private const val PAGE_COMMON = "common"

        private fun entry(pageId: String, title: String, desc: String): SwitchDef {
            return SwitchDef(title, desc, null, false, false, true, pageId, Action.OPEN_PAGE)
        }

        private fun pageTitle(pageId: String): String {
            if (PAGE_ADS == pageId) {
                return "广告与内容过滤"
            }
            if (PAGE_UI == pageId) {
                return "界面与外观"
            }
            if (PAGE_BROWSE == pageId) {
                return "浏览与下载"
            }
            if (PAGE_WATCH == pageId) {
                return "动态推送"
            }
            if (PAGE_TASK == pageId) {
                return "每日任务"
            }
            if (PAGE_COMMON == pageId) {
                return "通用与备份"
            }
            return "BetterHeybox 设置"
        }

        private fun addBase(out: MutableList<SettingsGroup>, title: String) {
            for (g in BASE_GROUPS) {
                if (g.title == title) {
                    out.add(g)
                    return
                }
            }
        }

        private fun appendExtraRows(
            out: MutableList<SettingsGroup>, title: String,
            extra: Array<out SwitchDef>?
        ) {
            if (extra == null || extra.isEmpty()) {
                return
            }
            for (i in out.indices) {
                val g = out[i]
                if (g.title == title) {
                    out[i] = withExtraRows(g, extra)
                    return
                }
            }
        }

        private fun withExtraRows(g: SettingsGroup, extra: Array<out SwitchDef>?): SettingsGroup {
            if (extra == null || extra.isEmpty()) {
                return g
            }
            val rows: Array<SwitchDef> = java.util.Arrays.copyOf(g.items, g.items.size + extra.size)
            System.arraycopy(extra, 0, rows, g.items.size, extra.size)
            return SettingsGroup(g.title, rows)
        }

        private const val TITLE_AD_FILTER = "广告过滤"

        private val WATCH_WINDOW_LABELS = arrayOf(
            "30 分钟", "1 小时", "3 小时", "6 小时", "12 小时", "1 天", "3 天", "7 天", "30 天"
        )
        private val WATCH_WINDOW_MINUTES = intArrayOf(
            30, 60, 180, 360, 720, 1440, 4320, 10080, 43200
        )
        private val WATCH_INTERVAL_LABELS = arrayOf(
            "5 分钟", "10 分钟", "15 分钟", "30 分钟", "1 小时", "3 小时", "6 小时", "12 小时"
        )
        private val WATCH_INTERVAL_MINUTES = intArrayOf(5, 10, 15, 30, 60, 180, 360, 720)

        private fun nearestIndex(values: IntArray, cur: Int): Int {
            var best = 0
            for (i in 1 until values.size) {
                if (Math.abs(values[i] - cur) < Math.abs(values[best] - cur)) {
                    best = i
                }
            }
            return best
        }

        private const val TITLE_SHARE_PURIFY = "分享净化"

        private fun countConfiguredLines(raw: String): Int {
            var count = 0
            for (line in raw.split(Regex("\n"))) {
                if (!line.trim().isEmpty()) {
                    count++
                }
            }
            return count
        }

        private val SETUP_METHOD_CANDIDATES = arrayOf("N1", "L1", "G1", "S1")

        private fun isViewBindingShape(type: Class<*>): Boolean {
            if (type.isInterface || type.isPrimitive) {
                return false
            }
            for (itf in type.interfaces) {
                val ms = itf.declaredMethods
                if (ms.size == 1 && ms[0].parameterCount == 0 &&
                    ms[0].returnType == View::class.java
                ) {
                    return true
                }
            }
            return false
        }

        private fun isViewAttachedUnder(view: View, activity: Activity): Boolean {
            try {
                val decor = activity.window.decorView
                var p: ViewParent? = view.parent
                while (p is View) {
                    if (p === decor) {
                        return true
                    }
                    p = p.parent
                }
            } catch (ignored: Throwable) {
            }
            return false
        }

        private val PAGE_IDS = arrayOf(
            PAGE_ADS, PAGE_UI, PAGE_BROWSE, PAGE_WATCH, PAGE_TASK, PAGE_COMMON
        )

        private fun matchSearch(def: SwitchDef, lowerQuery: String): Boolean {
            if (def.title != null && def.title.lowercase(Locale.ROOT).contains(lowerQuery)) {
                return true
            }
            return def.desc != null && def.desc.lowercase(Locale.ROOT).contains(lowerQuery)
        }

        private fun addRuntimeStatusRow(groups: MutableList<SettingsGroup>) {
            for (i in groups.indices) {
                val g = groups[i]
                if (TITLE_GENERAL == g.title) {
                    val items = arrayOfNulls<SwitchDef>(g.items.size + 2)
                    System.arraycopy(g.items, 0, items, 0, g.items.size)
                    items[g.items.size] = SwitchDef(
                        "运行状态", "查看模块运行检查点", null, false, false,
                        true, null, Action.RUNTIME_STATUS
                    )
                    items[g.items.size + 1] = SwitchDef(
                        "目标解析状态", "查看混淆名定位结果与判定依据", null, false, false,
                        true, null, Action.TARGET_STATUS
                    )
                    @Suppress("UNCHECKED_CAST")
                    groups[i] = SettingsGroup(g.title, items as Array<SwitchDef>)
                    return
                }
            }
        }

        @Volatile
        private var sDescToggle: Method? = null
        private const val DESC_PROBE_TEXT = "BH_DESC_PROBE"

        private fun setRowClick(itemCls: Class<*>, item: Any, l: View.OnClickListener) {
            itemCls.getMethod("setOnClickListener", View.OnClickListener::class.java).invoke(item, l)
        }

        private fun findScroller(root: View): ScrollView? {
            if (root is ScrollView) {
                return root
            }
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val found = findScroller(root.getChildAt(i))
                    if (found != null) {
                        return found
                    }
                }
            }
            return null
        }

        private val DEFAULT_WEBVIEW_ENTRY_URL = "https://github.com/Mrmiaomrzh/BetterHeybox"

        private val SHARE_CHANNELS = arrayOf("QQ", "WECHAT", "WEIBO")
        private val SHARE_CHANNEL_LABELS = arrayOf("QQ / QQ空间", "微信 / 朋友圈", "微博")

        private val GLASS_PROVIDER_VALUES = arrayOf(
            GlassProvider.PROVIDER_OWN, GlassProvider.PROVIDER_HBMOD
        )
        private val GLASS_PROVIDER_LABELS = arrayOf(
            "BetterHeybox（模块自带）", "小黑盒液态玻璃模块"
        )

        private val POST_LEVEL_VALUES = arrayOf(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"
        )
        private val POST_LEVEL_LABELS = arrayOf(
            "关闭", "Lv1", "Lv2", "Lv3", "Lv4", "Lv5", "Lv6", "Lv7", "Lv8", "Lv9", "Lv10"
        )

        private val POST_COUNT_VALUES = arrayOf(
            "0", "1", "5", "10", "20", "50", "100", "200", "500", "1000"
        )
        private val POST_COUNT_LABELS = arrayOf(
            "关闭", "1", "5", "10", "20", "50", "100", "200", "500", "1000"
        )

        private fun thresholdIndex(raw: String?): Int {
            val value = if (raw == null) "" else raw.trim()
            for (i in POST_COUNT_VALUES.indices) {
                if (POST_COUNT_VALUES[i] == value) {
                    return i
                }
            }
            return 0
        }

        private val DISCLAIMER_TEXT =
            "本应用与清枫(北京)科技有限公司无任何关联，亦未经其授权或认可\n\n" +
                    "本项目仅用于学习与研究小黑盒 APP 的部分技术原理，严禁用于任何商业或非法用途\n\n" +
                    "请在下载后 24 小时内删除本应用及相关文件\n\n" +
                    "禁止在 小黑盒 / HeyBox 平台内发布、讨论或传播本模块的内容，违者后果自负"

        @JvmStatic
        fun hostResId(context: Context, name: String, type: String, fallback: Int): Int {
            return try {
                val id = context.resources.getIdentifier(name, type, MainModule.TARGET_PKG)
                if (id != 0) id else fallback
            } catch (t: Throwable) {
                fallback
            }
        }

        @JvmStatic
        fun hostColor(context: Context, name: String, fallback: Int): Int {
            val id = hostResId(context, name, "color", 0)
            if (id != 0) {
                try {
                    return context.getColor(id)
                } catch (ignored: Throwable) {
                }
            }
            return fallback
        }

        @Throws(Throwable::class)
        private fun buildHostCard(activity: Activity, cl: ClassLoader): Array<Any> {
            val cardCls: Class<*> = Class.forName("androidx.cardview.widget.CardView", false, cl)
            val card: Any = cardCls.getConstructor(Context::class.java).newInstance(activity)
            val density = activity.resources.displayMetrics.density
            cardCls.getMethod("setRadius", java.lang.Float.TYPE).invoke(card, 8f * density)
            cardCls.getMethod("setCardElevation", java.lang.Float.TYPE).invoke(card, 0f)
            try {
                cardCls.getMethod("setMaxCardElevation", java.lang.Float.TYPE).invoke(card, 0f)
            } catch (ignored: Throwable) {
            }
            val cardLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val m = ThemeUtils.dp(activity, 12f)
            cardLp.setMargins(m, ThemeUtils.dp(activity, 8f), m, 0)
            (card as View).setLayoutParams(cardLp)
            val content = LinearLayout(activity)
            content.setOrientation(LinearLayout.VERTICAL)
            content.setLayoutParams(
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            (card as ViewGroup).addView(content)
            return arrayOf(card, content)
        }
    }
}
