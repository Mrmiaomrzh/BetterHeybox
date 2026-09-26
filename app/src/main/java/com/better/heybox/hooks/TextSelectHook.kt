package com.better.heybox.hooks

import android.util.Log
import android.view.View
import android.widget.TextView
import com.better.heybox.App
import com.better.heybox.CustomTextSelection
import com.better.heybox.LogRecorder
import com.better.heybox.MainModule
import com.better.heybox.SelectionSafeLinkMovementMethod
import java.lang.ref.WeakReference
import java.lang.reflect.Method

class TextSelectHook(private val module: MainModule) {

    init {
        sInstance = this
    }

    fun install(cl: ClassLoader) {
        hookTextSelectHandler(cl)
        hookPostTextSelect(cl)
    }

    private fun hookTextSelectHandler(cl: ClassLoader) {
        try {
            val handler = Class.forName(
                "com.max.common.common.selecthandler.TextSelectHandler",
                false,
                cl
            )

            var onTouch: Method? = null
            for (m in handler.declaredMethods) {
                if ("onTouch" == m.name && m.parameterCount == 2) {
                    onTouch = m
                    break
                }
            }

            if (onTouch == null) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 未找到 TextSelectHandler.onTouch")
                return
            }
            module.hook(onTouch).intercept { false }

            module.logd(Log.INFO, MainModule.TAG, "✔ TextSelectHandler 防复制拦截已解除")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ TextSelectHandler 解除失败", t)
        }
    }

    private fun hookPostTextSelect(cl: ClassLoader) {
        try {
            val clazz = Class.forName(
                "com.max.xiaoheihe.module.bbs.post.ui.fragments.v2.PostPictureFragmentV2",
                false,
                cl
            )

            var target: Method? = null
            var renamed: Method? = null
            for (m in clazz.declaredMethods) {
                if (m.parameterCount == 1 &&
                    m.parameterTypes[0] == View::class.java &&
                    m.name.startsWith("installViews")
                ) {
                    if ("installViews" == m.name) {
                        target = m
                        break
                    }
                    if (renamed == null) {
                        renamed = m
                    }
                }
            }
            if (target == null) {
                target = renamed
            }

            if (target == null) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 未找到 PostPictureFragmentV2.installViews")
                return
            }

            module.hook(target).intercept { chain ->
                val result = chain.proceed()

                try {
                    val arg = chain.getArg(0)
                    if (arg is View) {
                        scheduleEnableTextSelect(arg, 0)
                        registerRoot(arg)
                    }
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "正文选择调度异常: $t")
                }

                result
            }

            module.logd(Log.INFO, MainModule.TAG, "✔ 帖子正文原生文本选择 Hook 已安装")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ 帖子正文复制 Hook 失败", t)
        }
    }

    private fun registerRoot(root: View?) {
        if (root == null) {
            return
        }
        synchronized(sRegisteredRoots) {
            for (ref in sRegisteredRoots) {
                if (ref.get() == root) {
                    return
                }
            }
            sRegisteredRoots.add(WeakReference(root))
        }
    }

    private fun refreshAll() {
        synchronized(sRegisteredRoots) {
            for (ref in sRegisteredRoots) {
                val root = ref.get() ?: continue
                try {
                    enablePostTextSelect(root)
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "刷新文本选择设置异常: $t")
                }
            }
        }
    }

    private fun scheduleEnableTextSelect(content: View, attempt: Int) {
        if (content == null) {
            return
        }
        if (attempt > 15) {
            module.logd(Log.WARN, MainModule.TAG, "正文 View 长时间未就绪，放弃开启文本选择")
            return
        }

        val delay = if (attempt == 0) 200L else 150L

        content.postDelayed({
            try {
                if (content.isShown && content.width > 0 && content.height > 0) {
                    enablePostTextSelect(content)
                } else {
                    scheduleEnableTextSelect(content, attempt + 1)
                }
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "正文选择调度异常: $t")
            }
        }, delay)
    }

    private fun enablePostTextSelect(root: View?) {
        if (root == null) {
            return
        }
        LogRecorder.setContext(root.context)
        if (!module.isEnabled(App.KEY_COPY_POST, true)) {
            return
        }
        val customSelect = module.isEnabled(App.KEY_CUSTOM_TEXT_SELECT, false)

        applyTextSelectByIds(
            root, customSelect,
            arrayOf("tv_title", "tv_desc"), true, "设置文本选择失败"
        )

        applyTextSelectByIds(
            root, customSelect,
            arrayOf(
                "bbs_name", "bbs_username", "bbs_user_name", "tv_post_author",
                "tv_author", "tv_username", "tv_nickname", "tv_user_name",
                "tv_userinfo", "tv_user_info", "author_name", "username",
                "tv_name", "tv_user", "tv_author_name"
            ), false, "设置用户名长按选择失败"
        )
    }

    private fun applyTextSelectByIds(
        root: View, customSelect: Boolean,
        idNames: Array<String>, body: Boolean, logLabel: String
    ) {
        for (idName in idNames) {
            try {
                val id = root.resources.getIdentifier(idName, "id", MainModule.TARGET_PKG)
                if (id == 0) {
                    continue
                }
                val v = root.findViewById<View>(id)
                if (v !is TextView) {
                    continue
                }
                applyTextSelect(v, idName, body, customSelect)
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "$logLabel ($idName): $t")
            }
        }
    }

    private fun applyTextSelect(tv: TextView, idName: String, body: Boolean, customSelect: Boolean) {
        CustomTextSelection.detach(tv)

        if (customSelect) {
            if (tv.isTextSelectable) {
                tv.setTextIsSelectable(false)
            }
            tv.movementMethod = null
            CustomTextSelection.attach(tv)
            module.logd(Log.INFO, MainModule.TAG, "✔ 已启用自绘制文本选择: $idName")
            return
        }

        if (!tv.isTextSelectable) {
            tv.setTextIsSelectable(true)
            module.logd(Log.INFO, MainModule.TAG, "✔ 已开启标准文本选择: $idName")
        }
        if (body) {
            tv.linksClickable = true
            tv.movementMethod = SelectionSafeLinkMovementMethod.getInstance()
        }
    }

    companion object {
        @Volatile private var sInstance: TextSelectHook? = null

        private val sRegisteredRoots = ArrayList<WeakReference<View>>()

        @JvmStatic
        fun refresh() {
            sInstance?.refreshAll()
        }
    }
}
