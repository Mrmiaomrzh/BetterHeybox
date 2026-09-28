package com.better.heybox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import io.github.libxposed.service.XposedService

class PreferenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            Logs.w("BetterHeybox", "广播接收: intent=null")
            return
        }
        val action = intent.action
        if (ACTION_RELOAD_MODULE == action) {
            val result = goAsync()
            Thread({
                try {
                    handleReloadRequest()
                } finally {
                    result.finish()
                }
            }, "bhx-hot-reload-req").start()
            return
        }
        val key = intent.getStringExtra(EXTRA_KEY)
        val value = intent.getBooleanExtra(EXTRA_VALUE, false)
        Checkpoint.mark("广播接收: action=%s key=%s value=%s", action, key, value)
        Logs.i(
            "BetterHeybox",
            "广播接收: action=$action, key=$key, value=$value, pid=" + Process.myPid()
        )
        if (ACTION_SET_BOOLEAN != action) {
            Logs.w("BetterHeybox", "广播忽略: action 不匹配, action=$action")
            return
        }
        if (!isAllowedKey(key)) {
            Logs.w("BetterHeybox", "广播拒绝: key 不允许, key=$key")
            return
        }

        val result = goAsync()
        Thread({
            try {
                val pending = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                pending.edit().putBoolean(key, value).commit()
                Logs.i(
                    "BetterHeybox",
                    "广播已写入待提交缓存: key=$key, value=$value, pendingCount=" + pending.all.size
                )
                LogRecorder.recordEvent("开关变更已写入待提交缓存: key=$key, value=$value")

                var service: XposedService? = App.getService()
                val deadline = System.currentTimeMillis() + WAIT_SERVICE_BIND_MS
                while (service == null && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(100)
                    } catch (ignored: InterruptedException) {
                        break
                    }
                    service = App.getService()
                }
                if (service == null) {
                    Logs.w(
                        "BetterHeybox",
                        "等待框架服务绑定超时，保留待提交缓存: key=$key（服务绑定后会自动补交）"
                    )
                }
                tryFlush(context, pending)
            } finally {
                result.finish()
            }
        }, "bhx-pref-flush").start()
    }

    companion object {
        const val ACTION_SET_BOOLEAN = "com.better.heybox.SET_BOOLEAN"
        const val ACTION_RELOAD_MODULE = "com.better.heybox.RELOAD_MODULE"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"

        private val PENDING_PREFS = App.PENDING_PREFS
        private const val WAIT_SERVICE_BIND_MS = 6000L

        private val ALLOWED_KEYS: Set<String> = HashSet(App.BOOLEAN_DEFAULTS.keys)

        /**
         * Requests a hot reload of the hooked host processes.
         *
         * The request must originate in the module process because the framework
         * service connection lives there.
         */
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

        @JvmStatic
        fun tryFlush(context: Context, pending: SharedPreferences?) {
            if (pending == null) {
                Logs.e("BetterHeybox", "远程提交跳过: pending=null")
                return
            }
            val values = pending.all
            Logs.i(
                "BetterHeybox",
                "远程提交开始: pendingCount=" + values.size + ", pid=" + Process.myPid()
            )
            try {
                val remote = App.getPrefs()
                if (remote == null) {
                    Logs.w("BetterHeybox", "远程提交等待: RemotePreferences 不可用，保留待提交缓存")
                    return
                }
                Logs.i("BetterHeybox", "远程偏好已获取，开始构造 Editor: group=" + App.PREFS_GROUP)
                val remoteEditor = remote.edit() ?: run {
                    Logs.e("BetterHeybox", "远程提交失败: RemotePreferences.edit 返回 null")
                    return
                }
                var acceptedCount = 0
                for (key in values.keys) {
                    val value = values[key]
                    if (value is Boolean && isAllowedKey(key)) {
                        remoteEditor.putBoolean(key, value)
                        acceptedCount++
                        Logs.i("BetterHeybox", "远程提交加入变更: key=$key, value=$value")
                    } else {
                        Logs.w(
                            "BetterHeybox",
                            "远程提交跳过无效缓存: key=$key, valueType=" +
                                    (value?.javaClass?.name ?: "null")
                        )
                    }
                }
                val committed = remoteEditor.commit()
                Logs.i(
                    "BetterHeybox",
                    "远程提交 commit 已返回: success=$committed, acceptedCount=$acceptedCount"
                )
                LogRecorder.recordEvent("远程提交完成: success=$committed, count=$acceptedCount")
                if (committed) {
                    pending.edit().clear().commit()
                    Logs.i("BetterHeybox", "待提交缓存已清理: pendingCount=" + pending.all.size)
                } else {
                    Logs.w("BetterHeybox", "远程提交未成功，保留待提交缓存")
                }
            } catch (t: Throwable) {
                Logs.e("BetterHeybox", "远程提交异常，保留待提交缓存", t)
            }
        }

        private fun isAllowedKey(key: String?): Boolean = key != null && ALLOWED_KEYS.contains(key)
    }
}
