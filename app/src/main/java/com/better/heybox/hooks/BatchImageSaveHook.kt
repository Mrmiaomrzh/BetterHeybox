package com.better.heybox.hooks

import android.content.Context
import android.widget.Toast

object BatchImageSaveHook {
    @Volatile
    internal var handler: ((Context, List<String>, ClassLoader) -> Unit)? = null

    fun start(context: Context, urls: List<String>, classLoader: ClassLoader) {
        val save = handler
        if (save == null) {
            Toast.makeText(context, "图片保存功能尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        save(context, urls.toList(), classLoader)
    }
}
