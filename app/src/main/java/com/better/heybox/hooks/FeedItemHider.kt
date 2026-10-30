package com.better.heybox.hooks

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import com.better.heybox.ModuleStats
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object FeedItemHider {

    private val HIDDEN_HEIGHTS = WeakHashMap<View, Int?>()

    private val ITEM_VIEW_FIELDS = ConcurrentHashMap<Class<*>, Any>()

    private val NO_FIELD = Any()

    private val RECYCLER_VIEW_CLASSES = ConcurrentHashMap<Class<*>, Boolean>()

    @JvmStatic
    fun getItemView(viewHolder: Any?): View? {
        if (viewHolder == null) {
            return null
        }
        return try {
            val cls = viewHolder.javaClass
            var cached = ITEM_VIEW_FIELDS[cls]
            if (cached == null) {
                var found: Field? = null
                try {
                    found = cls.getField("itemView")
                } catch (ignored: Throwable) {
                }
                val prev = ITEM_VIEW_FIELDS.putIfAbsent(cls, found ?: NO_FIELD)
                cached = prev ?: (found ?: NO_FIELD)
            }
            if (cached === NO_FIELD) {
                return null
            }
            (cached as Field).get(viewHolder) as? View
        } catch (t: Throwable) {
            null
        }
    }

    @JvmStatic
    fun topLevel(view: View?): View? {
        var current = view
        var depth = 0
        while (depth < 16 && current != null) {
            val parent: ViewParent? = current.parent
            if (parent !is ViewGroup) {
                return current
            }
            if (isRecyclerView(parent)) {
                return current
            }
            current = parent as View
            depth++
        }
        return current
    }

    private fun isRecyclerView(parent: ViewParent): Boolean {
        val cls = parent.javaClass
        val cached = RECYCLER_VIEW_CLASSES[cls]
        if (cached != null) {
            return cached
        }
        var found = false
        var c: Class<*>? = cls
        while (c != null) {
            if ("androidx.recyclerview.widget.RecyclerView" == c.name) {
                found = true
                break
            }
            c = c.superclass
        }
        RECYCLER_VIEW_CLASSES[cls] = found
        return found
    }

    @JvmStatic
    fun hide(itemView: View?) {
        try {
            if (itemView == null) {
                return
            }
            if (itemView.visibility == View.GONE && HIDDEN_HEIGHTS.containsKey(itemView)) {
                return
            }
            if (!HIDDEN_HEIGHTS.containsKey(itemView)) {
                val lp = itemView.layoutParams
                HIDDEN_HEIGHTS[itemView] = lp?.height
            }
            itemView.visibility = View.GONE
            val lp = itemView.layoutParams
            if (lp != null) {
                lp.height = 0
                itemView.layoutParams = lp
            }
            ModuleStats.bbsListItemsHidden.incrementAndGet()
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun restore(viewHolder: Any?) {
        restore(getItemView(viewHolder))
    }

    @JvmStatic
    fun restore(itemView: View?) {
        if (itemView == null || !HIDDEN_HEIGHTS.containsKey(itemView)) {
            return
        }
        val height = HIDDEN_HEIGHTS.remove(itemView)
        itemView.visibility = View.VISIBLE
        val lp = itemView.layoutParams
        if (lp != null && height != null) {
            lp.height = height
            itemView.layoutParams = lp
        }
    }
}
