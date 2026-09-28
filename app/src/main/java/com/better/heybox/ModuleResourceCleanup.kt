package com.better.heybox

import com.better.heybox.hooks.AIClickbaitChecker
import com.better.heybox.watch.WatchEngine
import com.better.heybox.watch.WatchSeen

/**
 * Releases module-owned threads, executors and callbacks before a hot reload.
 *
 * The framework replaces the module instance in place, so the outgoing instance
 * must stop everything it owns. Each step is best-effort: one failing release
 * must not prevent the remaining ones from running.
 */
object ModuleResourceCleanup {

    private const val TAG = "BetterHeybox"

    @Volatile
    private var sReleased = false

    @JvmStatic
    fun releaseAll() {
        if (sReleased) {
            return
        }
        sReleased = true
        Logs.w(TAG, "热重载准备：开始释放模块资源")
        release("AI 标题党检查") { AIClickbaitChecker.shutdown() }
        release("关注检查") { WatchEngine.shutdown() }
        release("关注去重") { WatchSeen.shutdown() }
        release("目标解析") { HeyboxTargets.shutdown() }
        release("DexKit 解析") { DexKitResolver.shutdown() }
        release("视频下载") { VideoDownloadManager.shutdown() }
        release("前台跟踪") { ForegroundTracker.shutdown() }
        release("模块实例") { MainModule.releaseCurrent() }
        // 日志写入最后释放，前面的失败才能写进日志文件。
        release("日志写入") { LogRecorder.shutdown() }
        Logs.w(TAG, "热重载准备：模块资源释放完成")
    }

    private inline fun release(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Logs.e(TAG, "释放失败（$label）: $t", t)
        }
    }
}
