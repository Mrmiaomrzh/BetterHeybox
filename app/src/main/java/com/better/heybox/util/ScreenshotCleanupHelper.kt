package com.better.heybox.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashSet
import java.util.Locale
import java.util.WeakHashMap

class ScreenshotCleanupHelper {

    private val hidden = WeakHashMap<View, Int>()

    fun hideInside(root: View?): Int {
        return hideInside(
            root,
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_TAGS, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_TIMESTAMPS, false),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_ACTIONS, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_IRRELEVANT, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_CLOSE, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_FOLLOW, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_LIKE_COUNT, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_IMAGE_COUNT, true),
            HeyboxPrefs.getString(App.KEY_SCREENSHOT_HIDE_CUSTOM, "")
        )
    }

    fun restore() {
        for ((view, visibility) in hidden) {
            try {
                view.visibility = visibility
            } catch (ignored: Throwable) {
            }
        }
        hidden.clear()
    }

    private fun hideMatching(
        root: View, targets: Set<Int>, actions: Boolean,
        likeCount: Boolean, irrelevant: Boolean, imageCount: Boolean,
        customNames: Set<String>,
        customTexts: List<String>, follow: Boolean
    ): Int {
        var count = 0
        val queue = ArrayDeque<View>()
        val depths = ArrayDeque<Int>()
        queue.add(root)
        depths.add(0)
        var visited = 0
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            val current = queue.poll()
            val depth = depths.poll() ?: 0

            if (current !== root && current.visibility != View.GONE) {
                val target = hideTarget(
                    current, targets, actions, likeCount, irrelevant,
                    imageCount, customNames, customTexts, follow
                )
                if (target != null && target.visibility != View.GONE) {
                    hidden[target] = target.visibility
                    target.visibility = View.GONE
                    count++
                    continue
                }
            }

            if (depth < MAX_DEPTH && current is ViewGroup) {
                val group = current
                val childCount = group.childCount
                for (i in 0 until childCount) {
                    queue.add(group.getChildAt(i))
                    depths.add(depth + 1)
                }
            }
        }
        return count
    }

    fun hideInside(
        root: View?, tags: Boolean, timestamps: Boolean,
        actions: Boolean, irrelevant: Boolean
    ): Int {
        return hideInside(
            root, tags, timestamps, actions, irrelevant,
            false, false, false, false, null
        )
    }

    fun hideInside(
        root: View?, tags: Boolean, timestamps: Boolean,
        actions: Boolean, irrelevant: Boolean, closeIcons: Boolean,
        follow: Boolean, likeCount: Boolean, imageCount: Boolean, custom: String?
    ): Int {
        if (root == null) {
            return 0
        }
        val context: Context = root.context
        val targets = HashSet<Int>()
        if (tags) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.TAGS))
        }
        if (timestamps) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.TIMESTAMPS))
        }
        if (actions) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.ACTIONS))
        }
        if (irrelevant) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.IRRELEVANT))
        }
        if (closeIcons) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.CLOSE_ICONS))
        }
        if (follow) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.FOLLOW))
        }
        if (likeCount) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.LIKE_COUNT))
        }
        if (imageCount) {
            addAll(targets, ScreenshotIds.ids(context, ScreenshotIds.IMAGE_COUNT))
        }
        val customNames = HashSet<String>()
        val customTexts = ArrayList<String>()
        parseCustom(custom, customNames, customTexts)

        if (targets.isEmpty() && customNames.isEmpty() && customTexts.isEmpty()
            && !actions && !likeCount && !follow && !irrelevant && !imageCount
        ) {
            return 0
        }
        return hideMatching(
            root, targets, actions, likeCount, irrelevant, imageCount,
            customNames, customTexts, follow
        )
    }

    private fun hideTarget(
        view: View, targets: Set<Int>, actions: Boolean,
        likeCount: Boolean, irrelevant: Boolean, imageCount: Boolean,
        customNames: Set<String>,
        customTexts: List<String>, follow: Boolean
    ): View? {
        if (targets.isNotEmpty() && targets.contains(view.id)) {
            return view
        }
        if (imageCount && view is TextView && isImageCountLabel(textOf(view))) {
            return smallContainer(view, 140)
        }
        if (irrelevant && isIrrelevantTitle(textOf(view))) {
            return irrelevantContainer(view)
        }
        if (likeCount && isLikeControl(view)) {
            return view
        }
        if (likeCount && isIconStatGroup(view)) {
            return view
        }
        if (customNames.isNotEmpty()) {
            val entry = ScreenshotIds.entryName(view)
            if (entry != null && customNames.contains(entry.lowercase(Locale.ROOT))) {
                return view
            }
        }
        if (customTexts.isNotEmpty() && view is TextView) {
            val text = textOf(view)
            if (text.isNotEmpty()) {
                val lower = text.lowercase(Locale.ROOT)
                for (keyword in customTexts) {
                    if (lower.contains(keyword)) {
                        return smallContainer(view, 140)
                    }
                }
            }
        }
        if (follow && isFollowControl(view)) {
            return followContainer(view)
        }
        return null
    }

    private fun isImageCountLabel(text: String): Boolean {
        if (text.isEmpty() || text.length > 8) {
            return false
        }
        val slash = text.indexOf('/')
        if (slash > 0 && slash < text.length - 1) {
            val left = text.substring(0, slash).trim()
            val right = text.substring(slash + 1).trim()
            return isNumber(left) && isNumber(right)
        }
        var body = if (text.startsWith("共")) text.substring(1) else text
        if (body.endsWith("张")) {
            body = body.substring(0, body.length - 1).trim()
        } else {
            return false
        }
        return isNumber(body)
    }

    private fun isIrrelevantTitle(text: String): Boolean {
        if (text.isEmpty() || text.length > 12) {
            return false
        }
        for (keyword in ScreenshotIds.IRRELEVANT_TITLES) {
            if (text.startsWith(keyword)) {
                return true
            }
        }
        return false
    }

    private fun irrelevantContainer(title: View): View {
        var node = title
        var target: View? = null
        for (i in 0 until 6) {
            val parent: ViewParent? = node.parent
            if (parent !is ViewGroup) {
                break
            }
            val group = parent
            if (group.childCount >= 2) {
                target = group
                break
            }
            node = group
        }
        if (target == null) {
            val parent: ViewParent? = title.parent
            target = if (parent is View) parent else title
        }
        val height = target.height
        val titleHeight = title.height
        if (height > 0 && titleHeight > 0 && height > titleHeight * 12) {
            return title
        }
        return target
    }

    private fun smallContainer(view: View, maxHeight: Int): View {
        var target = view
        var current = view
        for (i in 0 until 2) {
            val parent: ViewParent? = current.parent
            if (parent !is ViewGroup) {
                break
            }
            val candidate = parent
            if (candidate.childCount > 4) {
                break
            }
            val height = candidate.height
            if (height > 0 && height > maxHeight) {
                break
            }
            target = candidate
            current = candidate
        }
        return target
    }

    private fun isFollowControl(view: View): Boolean {
        if (view !is TextView) {
            return false
        }
        val text = textOf(view).replace("+", "").replace("＋", "").trim()
        val label = text == "关注" || text == "已关注" || text == "关注中"
        if (!label && !nameLooksLikeFollow(view)) {
            return false
        }
        return nameLooksLikeFollow(view) || isClickableNear(view, 3)
    }

    private fun nameLooksLikeFollow(view: View): Boolean {
        val id = ScreenshotIds.entryName(view)
        if (id != null && id.lowercase().contains("follow")) {
            return true
        }
        var parent: ViewParent? = view.parent
        var i = 0
        while (i < 3 && parent is View) {
            val name = parent.javaClass.simpleName.lowercase()
            if (name.contains("follow") || name.contains("subscribe")) {
                return true
            }
            parent = (parent as View).parent
            i++
        }
        return false
    }

    private fun isClickableNear(view: View, levels: Int): Boolean {
        var current: View? = view
        var i = 0
        while (i <= levels && current != null) {
            if (current.isClickable) {
                return true
            }
            val parent: ViewParent? = current.parent
            current = parent as? View
            i++
        }
        return false
    }

    private fun followContainer(view: View): View {
        var target = view
        var current = view
        for (i in 0 until 2) {
            val parent: ViewParent? = current.parent
            if (parent !is View) {
                break
            }
            val candidate = parent as View
            if (!candidate.isClickable) {
                break
            }
            val width = candidate.width
            val height = candidate.height
            if (width > 0 && (width > 460 || height > 160)) {
                break
            }
            target = candidate
            current = candidate
        }
        return target
    }

    private fun isLikeControl(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null && type !== View::class.java) {
            if ("BBSLinkListLikeComment" == type.simpleName) {
                return true
            }
            type = type.superclass
        }
        return false
    }

    private fun isIconStatGroup(view: View): Boolean {
        if (view !is ViewGroup) {
            return false
        }
        val group = view
        val childCount = group.childCount
        if (childCount < 2 || childCount > 3) {
            return false
        }
        var icon = false
        var number = false
        for (i in 0 until childCount) {
            val child = group.getChildAt(i)
            if (child.visibility == View.GONE) {
                continue
            }
            if (child is ViewGroup) {
                return false
            }
            if (child is TextView) {
                val text = textOf(child)
                if (text.isEmpty() || !isNumber(text)) {
                    return false
                }
                number = true
            } else {
                icon = true
            }
        }
        return icon && number
    }

    private fun textOf(view: View): String {
        if (view !is TextView) {
            return ""
        }
        val text: CharSequence? = view.text
        return text?.toString()?.trim() ?: ""
    }

    private fun isNumber(text: String): Boolean {
        val length = text.length
        if (length == 0 || length > 8) {
            return false
        }
        var digit = false
        for (element in text) {
            val c = element
            if (c >= '0' && c <= '9') {
                digit = true
            } else if (c != '.' && c != '+' && c != '万' && c != '亿' && c != 'k'
                && c != 'K' && c != 'w' && c != 'W'
            ) {
                return false
            }
        }
        return digit
    }

    companion object {

        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 5000

        @JvmStatic
        fun currentRules(): BooleanArray = booleanArrayOf(
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_TAGS, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_TIMESTAMPS, false),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_ACTIONS, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_IRRELEVANT, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_CLOSE, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_FOLLOW, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_LIKE_COUNT, true),
            HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_HIDE_IMAGE_COUNT, true),
        )

        @JvmStatic
        fun parseCustom(raw: String?, names: MutableSet<String>, texts: MutableList<String>) {
            if (raw.isNullOrEmpty()) {
                return
            }
            for (line in raw.split("[\n,，;；]".toRegex())) {
                val item = line.trim()
                if (item.isEmpty() || item.startsWith("#")) {
                    continue
                }
                val lower = item.lowercase(Locale.ROOT)
                if (lower.startsWith("text:") || lower.startsWith("文字:")) {
                    val keyword = item.substring(item.indexOf(':') + 1).trim()
                    if (keyword.isNotEmpty()) {
                        texts.add(keyword.lowercase(Locale.ROOT))
                    }
                } else {
                    names.add(lower)
                }
            }
        }

        private fun addAll(out: MutableSet<Int>, values: IntArray) {
            for (value in values) {
                if (value != 0) {
                    out.add(value)
                }
            }
        }
    }
}
