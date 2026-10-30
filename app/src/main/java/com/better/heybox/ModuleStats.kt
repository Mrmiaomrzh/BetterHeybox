package com.better.heybox

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ModuleStats {

    @JvmField val commentCopyHelperCalls = AtomicInteger()

    @JvmField val commentCopyWithRecord = AtomicInteger()

    @JvmField val commentCopyNoRecord = AtomicInteger()

    @JvmField val commentDfsRuns = AtomicInteger()

    @JvmField val commentDfsBudgetHits = AtomicInteger()

    @JvmField val commentDfsNodes = AtomicLong()

    @JvmField val commentDfsMillis = AtomicLong()

    @JvmField val commentCopyMatched = AtomicInteger()

    @JvmField val commentCopyNoMatch = AtomicInteger()

    @JvmField val dailyTaskResumeChecks = AtomicInteger()

    @JvmField val dailyTaskNoLink = AtomicInteger()

    @JvmField val bbsListItemsSeen = AtomicInteger()

    @JvmField val bbsListItemsDropped = AtomicInteger()

    @JvmField val bbsListItemsHidden = AtomicInteger()

    @JvmField val topicFeedRequests = AtomicInteger()

    @JvmField val topicFeedStallRuns = AtomicInteger()

    @JvmField val myTaskResumeChecks = AtomicInteger()

    @JvmField val myTaskRuns = AtomicInteger()

    @JvmField val myTaskReadFail = AtomicInteger()

    @JvmField val myTaskNoMatch = AtomicInteger()

    @JvmField val myTaskMatched = AtomicInteger()

    @JvmField val myTaskPublished = AtomicInteger()

    @JvmField val myTaskPublishFail = AtomicInteger()

    @JvmField val myTaskVerified = AtomicInteger()

    @JvmField val myTaskVerifyFail = AtomicInteger()

    @JvmField val myTaskClaimed = AtomicInteger()

    @JvmField val myTaskDeleted = AtomicInteger()

    @JvmField val myTaskAbort = AtomicInteger()

    @JvmField val bottomTabApplies = AtomicInteger()

    @JvmField val bottomTabReentrySkips = AtomicInteger()

    @JvmField val bottomTabLayoutListeners = AtomicInteger()

    @JvmField val bottomTabHides = AtomicInteger()

    @JvmField val bottomTabHideNoops = AtomicInteger()

    @JvmField val bottomTabLayoutFixes = AtomicInteger()

    @JvmField val videoControllersCreated = AtomicInteger()

    @JvmField val videoDecorListenersRemoved = AtomicInteger()

    @JvmField val textSelectionSwept = AtomicInteger()

    private fun liveTextSelections(): Int = try {
        CustomTextSelection.liveControllerCount()
    } catch (t: Throwable) {
        -1
    }

    private fun liveTextSelectionsReadOnly(): Int = try {
        CustomTextSelection.liveControllerCountReadOnly()
    } catch (t: Throwable) {
        -1
    }

    @JvmField val abnormalExits = AtomicInteger()

    @JvmField val processStartedAt: Long = SystemClock.elapsedRealtime()

    @JvmField val feedBinds = AtomicInteger()

    @JvmField val feedFlowListItems = AtomicInteger()

    @JvmField val feedConfigRebuilds = AtomicInteger()

    @JvmField val tipWatcherScans = AtomicInteger()

    @JvmField val slowOps = AtomicInteger()

    private const val SLOW_MS = 50L

    private val SLOW_BY_NAME = ConcurrentHashMap<String, AtomicInteger>()

    @JvmStatic
    fun slow(name: String?, ms: Long) {
        if (ms < SLOW_MS || name == null) {
            return
        }
        slowOps.incrementAndGet()
        val counter = SLOW_BY_NAME.computeIfAbsent(name) { AtomicInteger() }
        counter.incrementAndGet()
    }

    @JvmStatic
    fun snapshot(): String = snapshot(false)

    @JvmStatic
    fun crashSnapshot(): String = snapshot(true)

    private fun snapshot(crashPath: Boolean): String {
        val sb = StringBuilder(320)
        sb.append("评论自由复制：助手调用=").append(commentCopyHelperCalls.get())
            .append("（有长按记录=").append(commentCopyWithRecord.get())
            .append(" / 无长按记录放行=").append(commentCopyNoRecord.get())
            .append(" / 命中=").append(commentCopyMatched.get())
            .append(" / 未命中=").append(commentCopyNoMatch.get()).append("）")
        sb.append(" / 整树遍历=").append(commentDfsRuns.get())
            .append(" 次，访问 ").append(commentDfsNodes.get())
            .append(" 个 View，累计 ").append(commentDfsMillis.get()).append(" ms")
        sb.append('\n').append("每日任务：onResume 检查=").append(dailyTaskResumeChecks.get())
            .append(" / 未配置链接=").append(dailyTaskNoLink.get())
        sb.append('\n').append("社区/话题列表(#41)：数据层条目=").append(bbsListItemsSeen.get())
            .append(" / 数据层删除=").append(bbsListItemsDropped.get())
            .append(" / 视图隐藏=").append(bbsListItemsHidden.get())
        sb.append('\n').append("话题信息流分页(#41)：宿主请求=").append(topicFeedRequests.get())
            .append(" / 疑似空转告警=").append(topicFeedStallRuns.get())
        sb.append('\n').append("我的任务(#42)：检查=").append(myTaskResumeChecks.get())
            .append(" / 跑了=").append(myTaskRuns.get())
            .append(" / 命中=").append(myTaskMatched.get())
            .append(" / 发帖成功=").append(myTaskPublished.get())
            .append(" / 发帖失败=").append(myTaskPublishFail.get())
            .append(" / 进度确认=").append(myTaskVerified.get())
            .append(" / 进度未确认=").append(myTaskVerifyFail.get())
            .append(" / 领奖=").append(myTaskClaimed.get())
            .append(" / 删帖成功=").append(myTaskDeleted.get())
            .append(" / 读列表失败=").append(myTaskReadFail.get())
            .append(" / 无匹配=").append(myTaskNoMatch.get())
            .append(" / 中止=").append(myTaskAbort.get())
        sb.append('\n').append("慢操作(>").append(SLOW_MS).append("ms)=").append(slowOps.get())
        sb.append('\n').append("首页过滤(#44)：条目判定=").append(feedBinds.get())
            .append(" / 列表层条目=").append(feedFlowListItems.get())
            .append(" / 配置快照重建=").append(feedConfigRebuilds.get())
            .append(" / 提示条全树扫描=").append(tipWatcherScans.get())
        sb.append('\n').append("底部导航(#37)：应用=").append(bottomTabApplies.get())
            .append(" / 重入拦截=").append(bottomTabReentrySkips.get())
            .append(" / layout监听器注册=").append(bottomTabLayoutListeners.get())
            .append(" / 隐藏生效=").append(bottomTabHides.get())
            .append(" / 重复隐藏跳过=").append(bottomTabHideNoops.get())
            .append(" / 布局修正=").append(bottomTabLayoutFixes.get())
        sb.append('\n').append("资源回收：视频窗口控制器=").append(videoControllersCreated.get())
            .append(" / 视频监听器移除=").append(videoDecorListenersRemoved.get())
            .append(" / 文本选择在册=")
            .append(if (crashPath) liveTextSelectionsReadOnly() else liveTextSelections())
            .append(" / 文本选择回收=").append(textSelectionSwept.get())
        sb.append('\n').append("进程：存活=")
            .append((SystemClock.elapsedRealtime() - processStartedAt) / 1000L)
            .append("s / 上次异常退出=").append(abnormalExits.get())
        if (SLOW_BY_NAME.isNotEmpty()) {
            sb.append("：")
            var first = true
            for ((key, value) in SLOW_BY_NAME) {
                if (!first) {
                    sb.append(", ")
                }
                first = false
                sb.append(key).append('×').append(value.get())
            }
        }
        return sb.toString()
    }
}
