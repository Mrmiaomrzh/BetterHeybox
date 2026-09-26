package com.better.heybox.hooks

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.better.heybox.App
import com.better.heybox.MainModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class SearchPageCleanHook(private val module: MainModule) {

    init {
        sInstance = this
    }

    fun install(cl: ClassLoader) {
        refresh()
        var hooked = 0
        try {
            val fragment = Class.forName(FRAGMENT_CLASS, false, cl)
            try {
                val onCreateView = fragment.getDeclaredMethod(
                    "onCreateView", LayoutInflater::class.java, ViewGroup::class.java,
                    android.os.Bundle::class.java
                )
                module.hook(onCreateView).intercept { chain ->
                    val result = chain.proceed()
                    if (result is View) {
                        apply(result)
                    }
                    result
                }
                hooked++
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "搜索页精简：onCreateView Hook 失败: $t")
            }
            try {
                val getView = fragment.getMethod("getView")
                val onResume = fragment.getDeclaredMethod("onResume")
                module.hook(onResume).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val view = getView.invoke(chain.getThisObject())
                        if (view is View) {
                            apply(view)
                        }
                    } catch (ignored: Throwable) {
                    }
                    result
                }
                hooked++
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "搜索页精简：onResume Hook 失败: $t")
            }
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "搜索页精简：未找到 $FRAGMENT_CLASS", t)
        }
        module.logd(
            Log.INFO, MainModule.TAG,
            "✔ 搜索页精简 Hook 已安装（$hooked 处）" +
                    " | 横幅=" + onOff(sHideBanner) +
                    " 搜索发现=" + onOff(sHideDiscover) +
                    " 黑盒热榜=" + onOff(sHideHotRank)
        )
    }

    companion object {
        private const val FRAGMENT_CLASS = "com.max.hbsearch.SearchNewFragment"

        private const val ID_PLACEHOLDER = "sv_placeholder"
        private const val ID_BANNER = "banner"

        private val IDS_BANNER = arrayOf(ID_BANNER)

        private val IDS_DISCOVER = arrayOf(
            "rl_list_header_v2",
            "rv_search_recommend_v2"
        )

        private val IDS_HOT_RANK = arrayOf(
            "v_top_space_hot_search_v3",
            "ll_hot_search_v3",
            "nsv_hot_search_v3",
            "space_hot_search_v3",
            "v_default_gap",
            "ll_hot",
            "sv_hot_v2"
        )

        @Volatile private var sInstance: SearchPageCleanHook? = null
        @Volatile private var sHideBanner = false
        @Volatile private var sHideDiscover = false
        @Volatile private var sHideHotRank = false

        private val sSeen: MutableMap<View, Boolean> = Collections.synchronizedMap(WeakHashMap())
        private val sWatching: MutableMap<View, Boolean> = Collections.synchronizedMap(WeakHashMap())
        private val sOriginal: MutableMap<View, Int> = Collections.synchronizedMap(WeakHashMap())
        private val sIds = ConcurrentHashMap<String, Int>()
        private val sMissing = ConcurrentHashMap.newKeySet<String>()
        private val sNotFound = ConcurrentHashMap.newKeySet<String>()

        @JvmStatic
        fun refresh() {
            val instance = sInstance ?: return
            val m = instance.module
            sHideBanner = m.isEnabled(App.KEY_SEARCH_HIDE_BANNER, false)
            sHideDiscover = m.isEnabled(App.KEY_SEARCH_HIDE_DISCOVER, false)
            sHideHotRank = m.isEnabled(App.KEY_SEARCH_HIDE_HOT_RANK, false)
            for (root in ArrayList(sSeen.keys)) {
                try {
                    apply(root)
                } catch (ignored: Throwable) {
                }
            }
        }

        private fun onOff(enabled: Boolean): String = if (enabled) "隐藏" else "保留"

        private fun anyEnabled(): Boolean = sHideBanner || sHideDiscover || sHideHotRank

        private fun apply(root: View?): Boolean {
            if (root == null) {
                return false
            }
            val firstSeen = sSeen.put(root, true) == null
            var changed = false
            if (firstSeen) {
                logRootReady(root)
            }
            changed = changed or applyGroup(root, sHideBanner, "隐藏搜索页横幅", IDS_BANNER)
            val scope = scopeOf(root)
            changed = changed or applyGroup(scope, sHideDiscover, "隐藏「搜索发现」", IDS_DISCOVER)
            changed = changed or applyGroup(scope, sHideHotRank, "隐藏「黑盒热榜」", IDS_HOT_RANK)
            if (anyEnabled()) {
                watch(root)
            }
            return changed
        }

        private fun logRootReady(root: View) {
            val instance = sInstance ?: return
            var bannerFound = false
            val bannerId = idOf(root, ID_BANNER)
            if (bannerId != 0) {
                bannerFound = root.findViewById<View>(bannerId) != null
            }
            instance.module.logd(
                Log.INFO, MainModule.TAG,
                "搜索页精简：页根就绪 | 横幅视图=" + (if (bannerFound) "已找到" else "未找到") +
                        " | 横幅=" + onOff(sHideBanner) +
                        " 搜索发现=" + onOff(sHideDiscover) +
                        " 黑盒热榜=" + onOff(sHideHotRank)
            )
        }

        private fun applyGroup(root: View, hide: Boolean, label: String, names: Array<String>): Boolean {
            var changed = false
            var found = false
            for (name in names) {
                val id = idOf(root, name)
                if (id == 0) {
                    continue
                }
                val view = root.findViewById<View>(id) ?: continue
                found = true
                changed = changed or if (hide) hideView(view, label, name) else restore(view)
            }
            if (hide && !found && sNotFound.add(label)) {
                sInstance?.module?.logd(
                    Log.WARN, MainModule.TAG,
                    "搜索页精简：本页未找到 $label 的目标视图，跳过"
                )
            }
            return changed
        }

        private fun hideView(view: View, label: String, name: String): Boolean {
            if (view.visibility == View.GONE) {
                return false
            }
            if (!sOriginal.containsKey(view)) {
                sOriginal[view] = view.visibility
            }
            view.visibility = View.GONE
            sInstance?.module?.logd(Log.INFO, MainModule.TAG, "搜索页精简：$label ($name)")
            return true
        }

        private fun restore(view: View): Boolean {
            val original = sOriginal.remove(view) ?: return false
            if (view.visibility == original) {
                return false
            }
            view.visibility = original
            return true
        }

        private fun watch(root: View) {
            if (sWatching.put(root, true) != null) {
                return
            }
            try {
                root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        try {
                            if (!anyEnabled() || !root.isAttachedToWindow) {
                                sWatching.remove(root)
                                val observer = root.viewTreeObserver
                                if (observer != null && observer.isAlive) {
                                    observer.removeOnPreDrawListener(this)
                                }
                                return true
                            }
                            return !apply(root)
                        } catch (ignored: Throwable) {
                            return true
                        }
                    }
                })
            } catch (t: Throwable) {
                sWatching.remove(root)
                sInstance?.module?.logd(Log.WARN, MainModule.TAG, "搜索页精简：挂绘制监听失败: $t")
            }
        }

        private fun scopeOf(root: View): View {
            val id = idOf(root, ID_PLACEHOLDER)
            if (id != 0) {
                val scope = root.findViewById<View>(id)
                if (scope != null) {
                    return scope
                }
            }
            return root
        }

        private fun idOf(root: View, name: String): Int {
            sIds[name]?.let { return it }
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
                if (sMissing.add(name)) {
                    sInstance?.module?.logd(
                        Log.WARN, MainModule.TAG,
                        "搜索页精简：当前版本没有资源 $name，跳过"
                    )
                }
                return 0
            }
            sIds[name] = id
            return id
        }
    }
}
