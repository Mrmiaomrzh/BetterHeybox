package com.better.heybox.hooks

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.View
import android.widget.TextView
import com.better.heybox.App
import com.better.heybox.Checkpoint
import com.better.heybox.HeyboxPrefs
import com.better.heybox.MainModule
import java.lang.reflect.Field
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class MessageRedDotHook(private val module: MainModule) {

    init {
        sInstance = this
    }

    fun install(cl: ClassLoader) {
        refresh()
        val dot = hookUnreadFlag(cl)
        val rows = hookRowBind(cl)
        sDotHooked = dot
        sRowHooked = rows
        Checkpoint.mark(
            "消息红点安装: 红点=%b 入口=%b 数字=%d 整行=%d",
            dot, rows, sNumberNames.size, sFullNames.size
        )
        module.logd(
            Log.INFO, MainModule.TAG,
            "✔ 消息红点 Hook 已安装（未读红点=" + (if (dot) "已挂" else "未挂") +
                    " / 消息入口=" + (if (rows) "已挂" else "未挂") +
                    " | 红点=" + onOff(sHideDot) +
                    " 入口精简=" + onOff(sEntryClean) +
                    "（红数字 " + sNumberNames.size + " 项 / 整行 " + sFullNames.size + " 项））"
        )
    }

    private fun hookUnreadFlag(cl: ClassLoader): Boolean {
        return try {
            val cls: Class<*> = Class.forName(HOST_CACHE_CLASS, false, cl)
            val getter = cls.getDeclaredMethod(UNREAD_METHOD)
            getter.setAccessible(true)
            module.hook(getter).intercept { chain ->
                if (sHideDot) false else chain.proceed()
            }
            module.logd(
                Log.INFO, MainModule.TAG,
                "✔ 消息红点：未读标记挂点 " + HOST_CACHE_CLASS + "#" + UNREAD_METHOD + "()Z"
            )
            true
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "✘ 消息红点：未读标记挂点失败（本版本无该目标），红点开关将不生效: " + t
            )
            false
        }
    }

    private fun hookRowBind(cl: ClassLoader): Boolean {
        return try {
            val adapter: Class<*> = Class.forName(BASE_ADAPTER_CLASS, false, cl)
            val holder: Class<*> = Class.forName(HOLDER_CLASS, false, cl)
            val bind = adapter.getDeclaredMethod("onBindViewHolder", holder, Integer.TYPE)
            module.hook(bind).intercept { chain ->
                val viewHolder = chain.getArg(0)
                if (sEntryClean) {
                    try {
                        restoreForBind(viewHolder)
                    } catch (t: Throwable) {
                        warnOnce("restore", "消息红点：入口行还原异常，已放行: $t")
                    }
                }
                val result = chain.proceed()
                if (sEntryClean) {
                    try {
                        applyBind(viewHolder)
                    } catch (t: Throwable) {
                        warnOnce("apply", "消息红点：消息入口处理异常，已放行: $t")
                    }
                }
                result
            }
            module.logd(
                Log.INFO, MainModule.TAG,
                "✔ 消息红点：消息入口挂点 " +
                        BASE_ADAPTER_CLASS + "#onBindViewHolder(" + HOLDER_CLASS + ", int)"
            )
            true
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "✘ 消息红点：消息入口挂点失败（本版本无该目标），入口开关将不生效: " + t
            )
            false
        }
    }

    private class Hidden(val title: String, val visibility: Int)

    companion object {

        private const val HOST_CACHE_CLASS = "com.max.hbcache.c"
        private const val UNREAD_METHOD = "v"
        private const val BASE_ADAPTER_CLASS = "com.max.hbcommon.base.adapter.s"
        private const val HOLDER_CLASS = "com.max.hbcommon.base.adapter.s\$e"
        private const val ID_BADGE = "badge"
        private val ID_TITLES = arrayOf("tv_title", "tv_name")
        private const val KEY_OBSERVED = "msg_badge_seen_entries"
        private const val MAX_OBSERVED = 60

        const val PICK_NUMBER = 1

        const val PICK_FULL = 2

        private val DEFAULT_NUMBER_NAMES = arrayOf("活动消息", "官方消息")
        private val EMPTY_NAMES = arrayOf<String>()

        @Volatile
        private var sInstance: MessageRedDotHook? = null

        @Volatile
        private var sHideDot = false

        @Volatile
        private var sEntryClean = false

        @Volatile
        private var sNumberNames: Set<String> = Collections.emptySet()

        @Volatile
        private var sFullNames: Set<String> = Collections.emptySet()

        @Volatile
        private var sObserved: Set<String> = LinkedHashSet<String>()

        @Volatile
        private var sDotHooked = false

        @Volatile
        private var sRowHooked = false

        private val sHiddenBadges: MutableMap<View, Hidden?> =
            Collections.synchronizedMap(WeakHashMap<View, Hidden?>())

        private val sHiddenRows: MutableMap<View, String?> =
            Collections.synchronizedMap(WeakHashMap<View, String?>())

        private val sIds = ConcurrentHashMap<String, Int>()
        private val sItemViewFields = ConcurrentHashMap<Class<*>, Field>()
        private val sWarned = ConcurrentHashMap.newKeySet<String>()
        private val sLoggedTitles = ConcurrentHashMap.newKeySet<String>()

        @JvmStatic
        fun refresh() {
            val instance = sInstance ?: return
            val m = instance.module
            val clean = m.isEnabled(App.KEY_HIDE_MSG_BADGE, false)
            val numbers = resolveNames(
                m.getString(App.KEY_MSG_BADGE_ENTRIES, null), DEFAULT_NUMBER_NAMES
            )
            val full = resolveNames(m.getString(App.KEY_MSG_FULL_HIDE_ENTRIES, null), EMPTY_NAMES)
            sHideDot = m.isEnabled(App.KEY_HIDE_MSG_DOT, false)
            sEntryClean = clean
            sNumberNames = numbers
            sFullNames = full
            loadObserved()
            if (!clean) {
                restoreAll()
                return
            }
            for (entry in ArrayList(sHiddenRows.entries)) {
                val title = entry.value
                if (title == null || !full.contains(title)) {
                    restoreRow(entry.key)
                }
            }
            for (entry in ArrayList(sHiddenBadges.entries)) {
                val hidden = entry.value
                if (hidden == null || !numbers.contains(hidden.title) || full.contains(hidden.title)) {
                    restoreBadge(entry.key)
                }
            }
        }

        private fun restoreForBind(holder: Any?) {
            val itemView = itemViewOf(holder) ?: return
            if (sHiddenRows.containsKey(itemView)) {
                restoreRow(itemView)
            }
            val badgeId = idOf(itemView, ID_BADGE)
            if (badgeId == 0) {
                return
            }
            val badge = itemView.findViewById<View>(badgeId)
            if (badge != null && sHiddenBadges.containsKey(badge)) {
                restoreBadge(badge)
            }
        }

        private fun applyBind(holder: Any?) {
            val itemView = itemViewOf(holder) ?: return
            val badgeId = idOf(itemView, ID_BADGE)
            if (badgeId == 0) {
                return
            }
            val badge = itemView.findViewById<View>(badgeId) ?: return
            val title = rowTitle(itemView)
            if (title == null || title.isEmpty()) {
                warnOnce(
                    "notitle", "消息红点：入口行没有标题文本，已跳过（入口以实际可见文案为准）"
                )
                return
            }
            rememberName(title)
            if (sFullNames.contains(title)) {
                sHiddenRows[itemView] = title
                FeedItemHider.hide(itemView)
                logOnce("row:$title", "完整隐藏消息入口: $title")
                return
            }
            if (sNumberNames.contains(title)) {
                sHiddenBadges[badge] = Hidden(title, badge.visibility)
                badge.visibility = View.GONE
                logOnce("badge:$title", "隐藏消息入口红数字: $title")
            }
        }

        private fun rowTitle(itemView: View): String? {
            for (name in ID_TITLES) {
                val id = idOf(itemView, name)
                if (id == 0) {
                    continue
                }
                val view = itemView.findViewById<View>(id)
                if (view !is TextView) {
                    continue
                }
                val text: CharSequence? = view.text
                if (text == null) {
                    continue
                }
                val value = text.toString().trim()
                if (!value.isEmpty()) {
                    return value
                }
            }
            return null
        }

        private fun restoreRow(itemView: View?) {
            if (itemView == null) {
                return
            }
            val title = sHiddenRows.remove(itemView) ?: return
            FeedItemHider.restore(itemView)
            logv("恢复消息入口整行: $title")
        }

        private fun restoreBadge(badge: View?) {
            if (badge == null) {
                return
            }
            val hidden = sHiddenBadges.remove(badge) ?: return
            try {
                badge.visibility = hidden.visibility
            } catch (ignored: Throwable) {
            }
            logv("恢复消息入口红数字: ${hidden.title}")
        }

        private fun restoreAll() {
            for (row in ArrayList(sHiddenRows.keys)) {
                restoreRow(row)
            }
            for (badge in ArrayList(sHiddenBadges.keys)) {
                restoreBadge(badge)
            }
        }

        @JvmStatic
        fun pickerEntries(kind: Int): List<@JvmSuppressWildcards Array<String>> {
            val out: MutableList<Array<String>> = ArrayList<Array<String>>()
            val seen: MutableSet<String> = LinkedHashSet<String>()
            for (name in selectedNames(kind)) {
                if (seen.add(name)) {
                    out.add(arrayOf(name, ""))
                }
            }
            loadObserved()
            for (name in ArrayList(sObserved)) {
                if (seen.add(name)) {
                    out.add(arrayOf(name, ""))
                }
            }
            return out
        }

        @JvmStatic
        fun selectedNames(kind: Int): Set<@JvmSuppressWildcards String> {
            if (kind == PICK_FULL) {
                return resolveNames(readString(App.KEY_MSG_FULL_HIDE_ENTRIES, null), EMPTY_NAMES)
            }
            return resolveNames(readString(App.KEY_MSG_BADGE_ENTRIES, null), DEFAULT_NUMBER_NAMES)
        }

        @JvmStatic
        fun setSelectedNames(kind: Int, names: Collection<String?>?) {
            val sb = StringBuilder()
            if (names != null) {
                for (name in names) {
                    if (name == null || name.trim().isEmpty()) {
                        continue
                    }
                    if (sb.length > 0) {
                        sb.append('\n')
                    }
                    sb.append(name.trim())
                }
            }
            try {
                HeyboxPrefs.setString(
                    if (kind == PICK_FULL) App.KEY_MSG_FULL_HIDE_ENTRIES
                    else App.KEY_MSG_BADGE_ENTRIES,
                    sb.toString()
                )
            } catch (ignored: Throwable) {
            }
            refresh()
        }

        @JvmStatic
        fun diagnostics(): String {
            val instance = sInstance
            val dot = if (instance != null) {
                instance.module.isEnabled(App.KEY_HIDE_MSG_DOT, false)
            } else {
                sHideDot
            }
            val clean = if (instance != null) {
                instance.module.isEnabled(App.KEY_HIDE_MSG_BADGE, false)
            } else {
                sEntryClean
            }
            val numbers = selectedNames(PICK_NUMBER)
            val full = selectedNames(PICK_FULL)
            loadObserved()
            val sb = StringBuilder()
            sb.append("未读红点=").append(onOff(dot))
                .append("（挂点").append(if (sDotHooked) "已装" else "未装").append("）").append('\n')
            sb.append("消息入口精简=").append(onOff(clean))
                .append("（挂点").append(if (sRowHooked) "已装" else "未装").append("）").append('\n')
            sb.append("隐藏红数字（").append(numbers.size).append("）：")
            sb.append(join(numbers)).append('\n')
            sb.append("完整隐藏（").append(full.size).append("）：")
            sb.append(join(full)).append('\n')
            sb.append("已观察到（").append(sObserved.size).append("）：")
            sb.append(join(sObserved)).append('\n')
            sb.append("提示：候选只在「消息入口精简」开关打开并浏览过消息列表后才会累积")
            return sb.toString()
        }

        private fun join(values: Set<String>): String {
            val sb = StringBuilder()
            for (value in values) {
                if (sb.length > 0) {
                    sb.append('、')
                }
                sb.append(value)
            }
            return if (sb.length == 0) "—" else sb.toString()
        }

        private fun onOff(enabled: Boolean): String = if (enabled) "隐藏" else "保留"

        private fun readString(key: String, def: String?): String? {
            val instance = sInstance
            if (instance != null) {
                return instance.module.getString(key, def)
            }
            return try {
                HeyboxPrefs.getString(key, def)
            } catch (ignored: Throwable) {
                def
            }
        }

        private fun resolveNames(raw: String?, defaults: Array<String>): Set<String> {
            if (raw == null) {
                val out: MutableSet<String> = LinkedHashSet<String>()
                Collections.addAll(out, *defaults)
                return out
            }
            return parseNames(raw)
        }

        private fun parseNames(raw: String?): Set<String> {
            if (raw == null || raw.trim().isEmpty()) {
                return Collections.emptySet()
            }
            val out: MutableSet<String> = LinkedHashSet<String>()
            for (line in raw.split("\n")) {
                val name = line.trim()
                if (!name.isEmpty() && !name.startsWith("#")) {
                    out.add(name)
                }
            }
            return out
        }

        private fun loadObserved() {
            val loaded = parseNames(HeyboxPrefs.getString(KEY_OBSERVED, ""))
            if (!loaded.isEmpty()) {
                sObserved = LinkedHashSet<String>(loaded)
            }
        }

        private fun rememberName(name: String?) {
            if (name == null || name.trim().isEmpty()) {
                return
            }
            val value = name.trim()
            val observed = sObserved
            if (observed.contains(value)) {
                return
            }
            val next: MutableSet<String> = LinkedHashSet(observed)
            next.add(value)
            val iterator = next.iterator()
            while (next.size > MAX_OBSERVED && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
            sObserved = next
            try {
                val sb = StringBuilder()
                for (item in next) {
                    if (sb.length > 0) {
                        sb.append('\n')
                    }
                    sb.append(item)
                }
                HeyboxPrefs.setString(KEY_OBSERVED, sb.toString())
            } catch (ignored: Throwable) {
            }
        }

        private fun itemViewOf(holder: Any?): View? {
            if (holder == null) {
                return null
            }
            val cls = holder.javaClass
            val cached = sItemViewFields[cls]
            if (cached != null) {
                try {
                    val value = cached.get(holder)
                    return if (value is View) value else null
                } catch (ignored: Throwable) {
                }
            }
            return try {
                val field = cls.getField("itemView")
                sItemViewFields[cls] = field
                val value = field.get(holder)
                if (value is View) value else null
            } catch (t: Throwable) {
                FeedItemHider.getItemView(holder)
            }
        }

        private fun idOf(root: View, name: String): Int {
            val cached = sIds[name]
            if (cached != null) {
                return cached
            }
            var id = 0
            try {
                val context = root.context
                val resources: Resources? = context?.resources
                if (resources != null && context != null) {
                    id = resources.getIdentifier(name, "id", context.packageName)
                    if (id == 0) {
                        id = resources.getIdentifier(name, "id", MainModule.TARGET_PKG)
                    }
                }
            } catch (ignored: Throwable) {
            }
            if (id == 0) {
                sIds[name] = 0
                warnOnce(
                    "id:$name",
                    "消息红点：当前版本没有资源 $name，入口精简对该行不生效"
                )
                return 0
            }
            sIds[name] = id
            return id
        }

        private fun warnOnce(key: String, message: String) {
            val instance = sInstance
            if (instance == null || !sWarned.add(key)) {
                return
            }
            instance.module.logd(Log.WARN, MainModule.TAG, message)
        }

        private fun logOnce(key: String, message: String) {
            if (!sLoggedTitles.add(key)) {
                return
            }
            log(Log.INFO, message)
        }

        private fun log(level: Int, message: String) {
            val instance = sInstance
            if (instance != null) {
                instance.module.logd(level, MainModule.TAG, message)
            }
        }

        private fun logv(message: String) {
            val instance = sInstance
            if (instance != null) {
                instance.module.logv(MainModule.TAG, message)
            }
        }
    }
}
