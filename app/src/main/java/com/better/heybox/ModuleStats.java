package com.better.heybox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ModuleStats {

    /** copy-helper calls seen (includes non-comment copies: row bindings also hit helpers) */
    public static final AtomicInteger commentCopyHelperCalls = new AtomicInteger();
    /** helper calls where the long-press record was available (one-view fast path) */
    public static final AtomicInteger commentCopyWithRecord = new AtomicInteger();
    /** helper calls with no record: window search is required (some builds never record) */
    public static final AtomicInteger commentCopyNoRecord = new AtomicInteger();
    /** budgeted window DFS runs (one per record-less helper call, only on real copies) */
    public static final AtomicInteger commentDfsRuns = new AtomicInteger();
    /** DFS runs stopped by the node budget (the target may sit further down) */
    public static final AtomicInteger commentDfsBudgetHits = new AtomicInteger();
    /** total View nodes visited by the DFS */
    public static final AtomicLong commentDfsNodes = new AtomicLong();
    /** total DFS time (ms) */
    public static final AtomicLong commentDfsMillis = new AtomicLong();
    /** text matched a comment view -> sheet shown */
    public static final AtomicInteger commentCopyMatched = new AtomicInteger();
    /** DFS ran but nothing matched */
    public static final AtomicInteger commentCopyNoMatch = new AtomicInteger();

    /** MainActivity.onResume daily-task checks */
    public static final AtomicInteger dailyTaskResumeChecks = new AtomicInteger();
    /** "no share link configured" hits (the log line itself is throttled) */
    public static final AtomicInteger dailyTaskNoLink = new AtomicInteger();
    public static final AtomicInteger bbsListItemsSeen = new AtomicInteger();
    public static final AtomicInteger bbsListItemsDropped = new AtomicInteger();
    public static final AtomicInteger bbsListItemsHidden = new AtomicInteger();
    public static final AtomicInteger topicFeedRequests = new AtomicInteger();
    public static final AtomicInteger topicFeedStallRuns = new AtomicInteger();

    public static final AtomicInteger myTaskResumeChecks = new AtomicInteger();
    public static final AtomicInteger myTaskRuns = new AtomicInteger();
    public static final AtomicInteger myTaskReadFail = new AtomicInteger();
    public static final AtomicInteger myTaskNoMatch = new AtomicInteger();
    public static final AtomicInteger myTaskMatched = new AtomicInteger();
    public static final AtomicInteger myTaskPublished = new AtomicInteger();
    public static final AtomicInteger myTaskPublishFail = new AtomicInteger();
    public static final AtomicInteger myTaskVerified = new AtomicInteger();
    public static final AtomicInteger myTaskVerifyFail = new AtomicInteger();
    public static final AtomicInteger myTaskClaimed = new AtomicInteger();
    public static final AtomicInteger myTaskDeleted = new AtomicInteger();
    public static final AtomicInteger myTaskAbort = new AtomicInteger();

    public static final AtomicInteger bottomTabApplies = new AtomicInteger();
    public static final AtomicInteger bottomTabReentrySkips = new AtomicInteger();
    public static final AtomicInteger bottomTabLayoutListeners = new AtomicInteger();
    public static final AtomicInteger bottomTabHides = new AtomicInteger();
    public static final AtomicInteger bottomTabHideNoops = new AtomicInteger();
    public static final AtomicInteger bottomTabLayoutFixes = new AtomicInteger();

    public static final AtomicInteger videoControllersCreated = new AtomicInteger();
    public static final AtomicInteger videoDecorListenersRemoved = new AtomicInteger();

    public static final AtomicInteger textSelectionSwept = new AtomicInteger();

    private static int liveTextSelections() {
        try {
            return CustomTextSelection.liveControllerCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int liveTextSelectionsReadOnly() {
        try {
            return CustomTextSelection.liveControllerCountReadOnly();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static final AtomicInteger abnormalExits = new AtomicInteger();
    public static final long processStartedAt = android.os.SystemClock.elapsedRealtime();


    public static final AtomicInteger feedBinds = new AtomicInteger();
    public static final AtomicInteger feedFlowListItems = new AtomicInteger();
    public static final AtomicInteger feedConfigRebuilds = new AtomicInteger();
    public static final AtomicInteger tipWatcherScans = new AtomicInteger();

    /** module operations slower than {@link #SLOW_MS} */
    public static final AtomicInteger slowOps = new AtomicInteger();

    /** slow-op threshold; below it we count nothing to avoid noise */
    private static final long SLOW_MS = 50L;
    private static final Map<String, AtomicInteger> SLOW_BY_NAME = new ConcurrentHashMap<>();

    private ModuleStats() {
    }

    /** Record one slow module op (only when &gt;{@value #SLOW_MS}ms). */
    public static void slow(String name, long ms) {
        if (ms < SLOW_MS || name == null) {
            return;
        }
        slowOps.incrementAndGet();
        AtomicInteger counter = SLOW_BY_NAME.get(name);
        if (counter == null) {
            counter = SLOW_BY_NAME.computeIfAbsent(name, k -> new AtomicInteger());
        }
        counter.incrementAndGet();
    }

    /** One-line summary for the log-export header and crash snapshots. */
    public static String snapshot() {
        return snapshot(false);
    }

    public static String crashSnapshot() {
        return snapshot(true);
    }

    private static String snapshot(boolean crashPath) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("评论自由复制：助手调用=").append(commentCopyHelperCalls.get())
                .append("（有长按记录=").append(commentCopyWithRecord.get())
                .append(" / 无长按记录放行=").append(commentCopyNoRecord.get())
                .append(" / 命中=").append(commentCopyMatched.get())
                .append(" / 未命中=").append(commentCopyNoMatch.get()).append("）");
        sb.append(" / 整树遍历=").append(commentDfsRuns.get())
                .append(" 次，访问 ").append(commentDfsNodes.get())
                .append(" 个 View，累计 ").append(commentDfsMillis.get()).append(" ms");
        sb.append('\n').append("每日任务：onResume 检查=").append(dailyTaskResumeChecks.get())
                .append(" / 未配置链接=").append(dailyTaskNoLink.get());
        sb.append('\n').append("社区/话题列表(#41)：数据层条目=").append(bbsListItemsSeen.get())
                .append(" / 数据层删除=").append(bbsListItemsDropped.get())
                .append(" / 视图隐藏=").append(bbsListItemsHidden.get());
        sb.append('\n').append("话题信息流分页(#41)：宿主请求=").append(topicFeedRequests.get())
                .append(" / 疑似空转告警=").append(topicFeedStallRuns.get());
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
                .append(" / 中止=").append(myTaskAbort.get());
        sb.append('\n').append("慢操作(>").append(SLOW_MS).append("ms)=").append(slowOps.get());
        sb.append('\n').append("首页过滤(#44)：条目判定=").append(feedBinds.get())
                .append(" / 列表层条目=").append(feedFlowListItems.get())
                .append(" / 配置快照重建=").append(feedConfigRebuilds.get())
                .append(" / 提示条全树扫描=").append(tipWatcherScans.get());
        sb.append('\n').append("底部导航(#37)：应用=").append(bottomTabApplies.get())
                .append(" / 重入拦截=").append(bottomTabReentrySkips.get())
                .append(" / layout监听器注册=").append(bottomTabLayoutListeners.get())
                .append(" / 隐藏生效=").append(bottomTabHides.get())
                .append(" / 重复隐藏跳过=").append(bottomTabHideNoops.get())
                .append(" / 布局修正=").append(bottomTabLayoutFixes.get());
        sb.append('\n').append("资源回收：视频窗口控制器=").append(videoControllersCreated.get())
                .append(" / 视频监听器移除=").append(videoDecorListenersRemoved.get())
                .append(" / 文本选择在册=")
                .append(crashPath ? liveTextSelectionsReadOnly() : liveTextSelections())
                .append(" / 文本选择回收=").append(textSelectionSwept.get());
        sb.append('\n').append("进程：存活=")
                .append((android.os.SystemClock.elapsedRealtime() - processStartedAt) / 1000L)
                .append("s / 上次异常退出=").append(abnormalExits.get());
        if (!SLOW_BY_NAME.isEmpty()) {
            sb.append("：");
            boolean first = true;
            for (Map.Entry<String, AtomicInteger> entry : SLOW_BY_NAME.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(entry.getKey()).append('×').append(entry.getValue().get());
            }
        }
        return sb.toString();
    }
}
