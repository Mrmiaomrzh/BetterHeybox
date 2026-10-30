package com.better.heybox.hooks

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.better.heybox.App
import com.better.heybox.MainModule
import java.lang.ref.WeakReference

object SearchContextTracker {

    private const val SEARCH_ACTIVITY_PREFIX = "com.max.hbsearch.SearchNew"

    private const val USER_HOME_ACTIVITY_PREFIX =
        "com.max.xiaoheihe.module.account.MeHomeActivity"

    private const val FOLLOW_LIST_ACTIVITY_PREFIX =
        "com.max.xiaoheihe.module.account.FollowingAndFansActivity"

    private const val HOME_ACTIVITY_PREFIX = "com.max.xiaoheihe.MainActivity"

    private val FOLLOW_TAB_FRAGMENT_PREFIXES = arrayOf(
        "com.max.xiaoheihe.module.bbs.FollowedMomentsFragment",
    )

    private val RECOMMEND_TAB_FRAGMENT_PREFIXES = arrayOf(
        "com.max.feature.feeds.RecommendFeedsFlowFragment",
    )

    private const val RETRY_COOLDOWN_MS = 5_000L

    private val LOCK = Any()

    @Volatile
    private var sModule: MainModule? = null

    @Volatile
    private var sRegistered = false

    @Volatile
    private var sNextRetryAt = 0L

    @Volatile
    private var sResumed: WeakReference<Activity>? = null

    @Volatile
    private var sSearchPage = false

    @Volatile
    private var sFollowContext = false

    @Volatile
    private var sFollowReason: String? = null

    @JvmStatic
    fun install(module: MainModule?) {
        if (module != null) {
            sModule = module
        }
        ensureRegistered()
    }

    @JvmStatic
    fun isSearchContext(): Boolean {
        if (!sRegistered) {
            ensureRegistered()
        }
        return sSearchPage
    }

    @JvmStatic
    fun isFollowContext(): Boolean {
        if (!sRegistered) {
            ensureRegistered()
        }
        return sFollowContext
    }

    @JvmStatic
    fun followContextReason(): String? = sFollowReason

    @JvmStatic
    fun resumedActivityName(): String? {
        val ref = sResumed
        val activity = ref?.get()
        return activity?.javaClass?.name
    }

    private fun ensureRegistered() {
        if (sRegistered) {
            return
        }
        synchronized(LOCK) {
            if (sRegistered) {
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now < sNextRetryAt) {
                return
            }
            val app = resolveApplication()
            if (app == null) {
                sNextRetryAt = now + RETRY_COOLDOWN_MS
                log(Log.WARN, "搜索页跟踪未注册：拿不到宿主 Application")
                return
            }
            try {
                app.registerActivityLifecycleCallbacks(Callbacks())
                sRegistered = true
                log(Log.INFO, "搜索页上下文跟踪已注册")
            } catch (t: Throwable) {
                sNextRetryAt = now + RETRY_COOLDOWN_MS
                log(Log.WARN, "搜索页跟踪注册失败: $t")
            }
        }
    }

    private fun resolveApplication(): Application? {
        return try {
            val context: Context = App.resolveAppContext() ?: return null
            val app: Context = context.applicationContext
            if (app is Application) {
                return app
            }
            context as? Application
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun log(level: Int, msg: String) {
        val module = sModule
        if (module != null) {
            try {
                module.logd(level, MainModule.TAG, msg)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun refreshFollowContext(activityName: String?) {
        var follow = false
        var reason: String? = null
        try {
            if (activityName == null) {
                follow = false
            } else if (activityName.startsWith(USER_HOME_ACTIVITY_PREFIX)) {
                follow = true
                reason = "user_home"
            } else if (activityName.startsWith(FOLLOW_LIST_ACTIVITY_PREFIX)) {
                follow = true
                reason = "following_list"
            } else if (activityName.startsWith(HOME_ACTIVITY_PREFIX)) {
                val ref = sResumed
                val activity = ref?.get()
                if (activity != null && matchesFollowTabFragment(activity)) {
                    follow = true
                    reason = "follow_tab"
                }
            }
        } catch (t: Throwable) {
            follow = false
            reason = null
        }
        if (follow != sFollowContext || (reason != null && reason != sFollowReason)) {
            log(
                Log.INFO, "关注/用户主页上下文: 页面=$activityName"
                        + " 命中=$follow" + (if (reason != null) " 类型=$reason" else "")
            )
        }
        sFollowReason = reason
        sFollowContext = follow
    }

    private fun matchesFollowTabFragment(activity: Activity): Boolean {
        return try {
            var fmGetter: java.lang.reflect.Method? = null
            var c: Class<*>? = activity.javaClass
            while (c != null) {
                try {
                    fmGetter = c.getDeclaredMethod("getSupportFragmentManager")
                    if (fmGetter != null) {
                        break
                    }
                } catch (ignored: NoSuchMethodException) {
                }
                c = c.superclass
            }
            if (fmGetter == null) {
                return false
            }
            val fm = fmGetter.invoke(activity) ?: return false
            val all = ArrayList<Any>(8)
            collectFragments(fm, all, 0)
            var hasFollow = false
            for (f in all) {
                val name = f.javaClass.name
                if (matchesAny(name, RECOMMEND_TAB_FRAGMENT_PREFIXES)) {
                    return false
                }
                if (matchesAny(name, FOLLOW_TAB_FRAGMENT_PREFIXES)) {
                    hasFollow = true
                }
            }
            hasFollow
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun collectFragments(fm: Any?, out: MutableList<Any>, depth: Int) {
        if (fm == null || depth > 6 || out.size > 200) {
            return
        }
        try {
            val fmCls = Class.forName("androidx.fragment.app.FragmentManager")
            val getAll = fmCls.getMethod("getFragments")
            val fragments = getAll.invoke(fm)
            if (fragments !is List<*>) {
                return
            }
            for (f in fragments) {
                if (f == null || out.size > 200) {
                    continue
                }
                out.add(f)
                val fCls = Class.forName("androidx.fragment.app.Fragment")
                val childGetter = fCls.getMethod("getChildFragmentManager")
                collectFragments(childGetter.invoke(f), out, depth + 1)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun matchesAny(name: String?, prefixes: Array<String>): Boolean {
        if (name == null) {
            return false
        }
        for (prefix in prefixes) {
            if (name.startsWith(prefix)) {
                return true
            }
        }
        return false
    }

    private class Callbacks : Application.ActivityLifecycleCallbacks {

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        }

        override fun onActivityStarted(activity: Activity) {
        }

        override fun onActivityResumed(activity: Activity) {
            sResumed = WeakReference(activity)
            val name = activity.javaClass.name
            val search = name.startsWith(SEARCH_ACTIVITY_PREFIX)
            refreshFollowContext(name)
            if (search != sSearchPage) {
                sSearchPage = search
                log(
                    Log.INFO, "当前页面: $name"
                            + " 搜索页=$search 关注/用户主页=$sFollowContext"
                )
            }
        }

        override fun onActivityPaused(activity: Activity) {
            val ref = sResumed
            val resumed = ref?.get()
            val resumedActivity = resumed ?: run {
                if (sSearchPage || sFollowContext) {
                    sSearchPage = false
                    sFollowContext = false
                    sFollowReason = null
                    log(Log.INFO, "离开页面: ${activity.javaClass.name}")
                }
                return
            }
            if (activity === resumedActivity) {
                sResumed = null
                if (sSearchPage || sFollowContext) {
                    sSearchPage = false
                    sFollowContext = false
                    sFollowReason = null
                    log(Log.INFO, "离开页面: ${activity.javaClass.name}")
                }
            }
        }

        override fun onActivityStopped(activity: Activity) {
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        }

        override fun onActivityDestroyed(activity: Activity) {
            val ref = sResumed
            val resumed = ref?.get()
            if (resumed == null || activity === resumed) {
                sResumed = null
                sSearchPage = false
                sFollowContext = false
                sFollowReason = null
            }
        }
    }
}
