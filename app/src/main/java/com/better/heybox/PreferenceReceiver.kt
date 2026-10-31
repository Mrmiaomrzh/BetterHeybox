package com.better.heybox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process

class PreferenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            Logs.w("BetterHeybox", "广播接收: intent=null")
            return
        }
        if (ACTION_RELOAD_MODULE == intent.action) {
            val result = goAsync()
            Thread({
                try {
                    handleReloadRequest()
                } finally {
                    result.finish()
                }
            }, "bhx-hot-reload-req").start()
        }
    }

    companion object {
        const val ACTION_RELOAD_MODULE = "com.better.heybox.RELOAD_MODULE"
        private const val WAIT_SERVICE_BIND_MS = 6000L

        private fun handleReloadRequest() {
            Checkpoint.mark("收到模块热重载请求")
            Logs.i("BetterHeybox", "收到模块热重载请求, pid=" + Process.myPid())
            var service = App.getService()
            val deadline = System.currentTimeMillis() + WAIT_SERVICE_BIND_MS
            while (service == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                service = App.getService()
            }
            if (service == null) {
                val reason = "热重载取消：等待框架服务绑定超时"
                Logs.w("BetterHeybox", reason)
                LogRecorder.recordEvent(reason)
                return
            }
            if (!ModuleReloader.isAvailable()) {
                val reason = "热重载不可用：" + ModuleReloader.describe()
                Logs.w("BetterHeybox", reason)
                LogRecorder.recordEvent(reason)
                return
            }
            ModuleReloader.request(null) { message ->
                Logs.i("BetterHeybox", "热重载结果: " + message)
            }
        }
    }
}
