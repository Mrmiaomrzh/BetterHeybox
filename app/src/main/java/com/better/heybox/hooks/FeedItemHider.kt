package com.better.heybox.hooks

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import java.util.WeakHashMap

object FeedItemHider {

    private val HIDDEN_HEIGHTS = WeakHashMap<View, Int?>()

    @JvmStatic
    fun getItemView(viewHolder: Any?): View? {
        if (viewHolder == null) return null
        return try {
            viewHolder.javaClass.getField("itemView").get(viewHolder) as? View
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
        var c: Class<*>? = parent.javaClass
        while (c != null) {
            if ("androidx.recyclerview.widget.RecyclerView" == c.name) {
                return true
            }
            c = c.superclass
        }
        return false
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
