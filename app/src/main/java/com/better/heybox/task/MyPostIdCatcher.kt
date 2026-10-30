package com.better.heybox.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.better.heybox.MainModule

object MyPostIdCatcher {

    const val ACTION_POST_SUCCESS =
        "com.max.xiaoheihe.BROADCAST_POST_SUCCESS_JS_CALLBACK"
    const val EXTRA_LINK_ID = "link_id"

    fun interface Listener {
        fun onPostCreated(linkId: String)
    }

    @Volatile
    private var sContext: Context? = null

    @Volatile
    private var sListener: Listener? = null

    @Volatile
    private var sReceiver: BroadcastReceiver? = null

    @Volatile
    private var sLastLinkId = ""

    @Volatile
    private var sLastSource = ""

    @JvmStatic
    fun register(context: Context?, module: MainModule?, listener: Listener?) {
        if (context == null || listener == null) {
            return
        }
        sListener = listener
        synchronized(MyPostIdCatcher::class.java) {
            if (sReceiver != null) {
                return
            }
            try {
                val app = context.applicationContext
                sContext = app
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent == null) {
                            return
                        }
                        val id = intent.getStringExtra(EXTRA_LINK_ID)
                        publish(id, "广播 $ACTION_POST_SUCCESS")
                    }
                }
                val filter = IntentFilter(ACTION_POST_SUCCESS)
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(receiver, filter)
                }
                sReceiver = receiver
            } catch (t: Throwable) {
                module?.logd(
                    Log.WARN, MainModule.TAG,
                    "我的任务：发帖成功广播注册失败（将只靠帖子页兜底）: $t"
                )
            }
        }
    }

    @JvmStatic
    fun unregister() {
        val state = synchronized(MyPostIdCatcher::class.java) {
            val context = sContext
            val receiver = sReceiver
            sContext = null
            sReceiver = null
            sListener = null
            sLastLinkId = ""
            sLastSource = ""
            Pair(context, receiver)
        }
        try {
            if (state.first != null && state.second != null) {
                state.first!!.unregisterReceiver(state.second)
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun report(linkId: String?, source: String?) {
        publish(linkId, source)
    }

    private fun publish(linkId: String?, source: String?) {
        if (linkId == null || linkId.trim().isEmpty()) {
            return
        }
        val id = linkId.trim()
        sLastLinkId = id
        sLastSource = source ?: ""
        val listener = sListener
        if (listener != null) {
            try {
                listener.onPostCreated(id)
            } catch (ignored: Throwable) {
            }
        }
    }

    @JvmStatic
    fun lastLinkId(): String = sLastLinkId

    @JvmStatic
    fun lastSource(): String = sLastSource
}
