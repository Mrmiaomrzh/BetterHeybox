package com.better.heybox.hooks

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.better.heybox.App
import com.better.heybox.MainModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class SingleColumnFeedHook(private val module: MainModule) {

    private var coverClass: Class<*>? = null

    fun install(cl: ClassLoader) {
        installLegacyWaterfallHook(cl)
        installEpoxyPairHook(cl)
    }

    private fun enabled(): Boolean = module.isEnabled(App.KEY_SINGLE_COLUMN_FEED, false)

    private fun installLegacyWaterfallHook(cl: ClassLoader) {
        try {
            val clazz = Class.forName("com.max.xiaoheihe.module.bbs.utils.b", false, cl)
            val rvClass = Class.forName("androidx.recyclerview.widget.RecyclerView", false, cl)
            val lmClass = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$LayoutManager", false, cl)
            val sglmClass = Class.forName(
                "androidx.recyclerview.widget.StaggeredGridLayoutManager", false, cl)
            val llmClass = Class.forName(
                "androidx.recyclerview.widget.LinearLayoutManager", false, cl)
            val ctxClass = Class.forName("android.content.Context", false, cl)

            val setLayoutManager = rvClass.getMethod("setLayoutManager", lmClass)
            val getLayoutManager = rvClass.getMethod("getLayoutManager")
            val llmCtor: Constructor<*> = llmClass.getConstructor(ctxClass)

            var target: Method? = null
            for (m in clazz.declaredMethods) {
                if (m.name != "X") {
                    continue
                }
                val p = m.parameterTypes
                if (p.size == 5 && p[0] == ctxClass && p[1] == rvClass &&
                    p[2] == Integer.TYPE && p[3] == Integer.TYPE && p[4] == Integer.TYPE
                ) {
                    target = m
                    break
                }
            }
            if (target == null) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "✘ 未找到双列瀑布流布局方法 b.X(Context,RecyclerView,III)"
                )
                return
            }

            module.hook(target).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (enabled()) {
                        val rv = chain.arg(1)
                        val cur = getLayoutManager.invoke(rv)
                        if (cur != null && sglmClass.isInstance(cur)) {
                            setLayoutManager.invoke(rv, llmCtor.newInstance(chain.arg(0)))
                            module.logd(Log.INFO, MainModule.TAG, "已屏蔽双列瀑布流，切换为单列布局")
                        }
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "切换单列布局失败: $t")
                }
                result
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ 双列瀑布流屏蔽 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 双列瀑布流屏蔽 Hook 失败", t)
        }
    }

    private fun installEpoxyPairHook(cl: ClassLoader) {
        try {
            val container = Class.forName(CONTAINER_CLASS, false, cl)
            val card = Class.forName(CARD_CLASS, false, cl)
            try {
                coverClass = Class.forName("$CARD_CLASS\$RoundedCoverContainer", false, cl)
            } catch (t: Throwable) {
                module.logd(
                    Log.WARN, MainModule.TAG,
                    "未找到 RoundedCoverContainer，封面比例修正不可用"
                )
            }
            var installed = 0

            val onViewAdded = container.getDeclaredMethod("onViewAdded", View::class.java)
            module.hook(onViewAdded).intercept { chain ->
                try {
                    if (enabled()) {
                        (chain.instanceOrNull as LinearLayout).orientation =
                            LinearLayout.VERTICAL
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "配对容器纵向化失败: $t")
                }
                val result = chain.proceed()
                try {
                    if (enabled()) {
                        val child = chain.arg(0) as View
                        child.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "配对子项全宽化失败: $t")
                }
                result
            }
            installed++

            val setTargetHeight =
                container.getDeclaredMethod("setChildrenTargetHeight", Int::class.javaPrimitiveType)
            module.hook(setTargetHeight).intercept { chain ->
                if (enabled()) null else chain.proceed()
            }
            installed++

            val onAttached = card.getDeclaredMethod("onAttachedToWindow")
            module.hook(onAttached).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (enabled()) {
                        val self = chain.instanceOrNull as View
                        val lp = self.layoutParams
                        if (lp is LinearLayout.LayoutParams && lp.width == 0) {
                            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                            lp.weight = 0f
                            self.layoutParams = lp
                        }
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "卡片全宽化失败: $t")
                }
                result
            }
            installed++

            val setCardWidth = card.getDeclaredMethod("setCardWidthPx", Int::class.javaPrimitiveType)
            module.hook(setCardWidth).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (enabled()) {
                        val width = chain.arg(0) as Int
                        if (width > 0) {
                            resizeCover(chain.instanceOrNull as ViewGroup, width)
                        }
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "封面比例修正失败: $t")
                }
                result
            }
            installed++

            module.logd(Log.INFO, MainModule.TAG, "✔ 首页推荐流单列 Hook 已安装（$installed 处）")
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG,
                "✘ 首页推荐流单列 Hook 安装失败（当前版本可能无此样式）: $t"
            )
        }
    }

    private fun resizeCover(card: ViewGroup, widthPx: Int) {
        val cover = findCover(card) ?: return
        val lp = cover.layoutParams
        val target = (widthPx * COVER_RATIO).toInt()
        if (lp != null && lp.height != target) {
            lp.height = target
            cover.layoutParams = lp
        }
    }

    private fun findCover(root: ViewGroup): View? {
        val cover = coverClass ?: return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (cover.isInstance(child)) {
                return child
            }
            if (child is ViewGroup) {
                findCover(child)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val CONTAINER_CLASS =
            "com.max.feature.feeds.view.itemview.WaterfallPairGroupContainer"
        private const val CARD_CLASS =
            "com.max.feature.feeds.view.itemview.WaterfallFeedsFlowItemViewV2"

        private const val COVER_RATIO = 210f / 375f
    }
}
