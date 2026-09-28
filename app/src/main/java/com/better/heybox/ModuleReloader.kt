package com.better.heybox

import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ModuleReloader {

    private const val TAG = "BetterHeybox"

    private const val TIMEOUT_MS = 30_000L

    private val sInFlight = AtomicBoolean(false)

    @JvmStatic
    fun isAvailable(): Boolean {
        val service = App.getService() ?: return false
        return try {
            service.apiVersion >= XposedService.API_102
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun describe(): String {
        val service = App.getService() ?: return "框架服务未连接"
        return try {
            val api = service.apiVersion
            if (api < XposedService.API_102) {
                "框架 API " + api + " 不支持热重载（需要 102）"
            } else {
                "框架 API " + api + "，可热重载目标 " + service.runningTargets.size + " 个"
            }
        } catch (t: Throwable) {
            "查询热重载能力失败：" + t
        }
    }

    @JvmStatic
    fun request(processName: String?, onResult: (String) -> Unit) {
        if (!sInFlight.compareAndSet(false, true)) {
            onResult("热重载请求已在进行中")
            return
        }
        val service = App.getService()
        if (service == null) {
            sInFlight.set(false)
            onResult("热重载失败：框架服务未连接")
            return
        }
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        val finish: (String) -> Unit = { message ->
            if (done.compareAndSet(false, true)) {
                sInFlight.set(false)
                LogRecorder.recordEvent(message)
                main.post { onResult(message) }
            }
        }
        main.postDelayed(
            { finish("热重载超时：未在 " + TIMEOUT_MS / 1000 + " 秒内收到结果") },
            TIMEOUT_MS
        )
        Thread({ submit(service, processName, finish) }, "bhx-hot-reload").start()
    }

    private fun submit(
        service: XposedService,
        processName: String?,
        finish: (String) -> Unit
    ) {
        try {
            val api = service.apiVersion
            if (api < XposedService.API_102) {
                finish("热重载不可用：需要框架 API 102，当前 " + api)
                return
            }
            val selected = service.runningTargets.filter {
                processName == null || it.processName == processName
            }
            if (selected.isEmpty()) {
                val scope = if (processName == null) "" else "（" + processName + "）"
                finish("热重载跳过：未找到运行中的目标进程" + scope)
                return
            }
            val messages = Collections.synchronizedList(ArrayList<String>())
            val pending = AtomicInteger(selected.size)
            val onOne: (String) -> Unit = { line ->
                messages.add(line)
                if (pending.decrementAndGet() == 0) {
                    finish(messages.joinToString("; "))
                }
            }
            for (target in selected) {
                try {
                    service.hotReloadModule(
                        target,
                        null,
                        object : XposedService.HotReloadCallback {
                            override fun onHotReloadResult(t: HookedTarget, r: HotReloadResult) {
                                val extra = r.message
                                val suffix = if (extra == null) "" else "：" + extra
                                onOne(label(t) + " -> " + r.status + suffix)
                            }
                        }
                    )
                } catch (t: Throwable) {
                    onOne(label(target) + " -> 提交失败：" + t)
                }
            }
        } catch (t: Throwable) {
            finish("热重载异常：" + t)
        }
    }

    private fun label(target: HookedTarget): String =
        target.processName + "(pid=" + target.pid + ", " + target.state + ")"
}
