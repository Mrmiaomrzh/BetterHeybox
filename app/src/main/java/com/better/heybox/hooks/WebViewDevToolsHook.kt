package com.better.heybox.hooks

import android.util.Log
import android.webkit.WebView
import com.better.heybox.App
import com.better.heybox.MainModule

class WebViewDevToolsHook(private val module: MainModule) {

    fun install(cl: ClassLoader) {
        try {
            var count = 0
            for (constructor in WebView::class.java.declaredConstructors) {
                try {
                    module.hook(constructor).intercept { chain ->
                        val result = chain.proceed()
                        enableIfNeeded()
                        result
                    }
                    count++
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "WebView 构造器 Hook 失败: $constructor", t)
                }
            }
            module.logd(Log.INFO, MainModule.TAG, "✔ WebView 原生 DevTools Hook 已安装（构造器 $count 个）")
        } catch (t: Throwable) {
            module.logd(Log.ERROR, MainModule.TAG, "✘ WebView DevTools Hook 失败", t)
        }
    }

    private fun enableIfNeeded() {
        if (!module.isEnabled(App.KEY_WEBVIEW_DEVTOOLS, false)) return
        try {
            WebView.setWebContentsDebuggingEnabled(true)
        } catch (t: Throwable) {
            module.logd(Log.WARN, MainModule.TAG, "开启 WebView 原生 DevTools 失败", t)
        }
    }
}
