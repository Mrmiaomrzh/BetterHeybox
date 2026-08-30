package com.better.heybox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 小黑盒视频下载：下载、保存、任务注册表与通知反馈（宿主进程内单例）。
 * 要点：mp4 直链 Range 续传 / HLS 分片下载合并转 MP4（加密拒绝）；终态任务持久化、重启恢复；保存走 MediaStore/SAF/公共 Movies。
 */
public final class VideoDownloadManager {

    /** 通知渠道 ID（宿主进程内唯一） */
    public static final String CHANNEL_ID_DOWNLOADS = "betterheybox_video_download";

    /** 通知 action（模块内广播，宿主应用本地自处理，外部不可见） */
    public static final String ACTION_CANCEL = "com.better.heybox.ACTION_CANCEL_DOWNLOAD";
    public static final String ACTION_PAUSE = "com.better.heybox.ACTION_PAUSE_DOWNLOAD";
    public static final String ACTION_RETRY = "com.better.heybox.ACTION_RETRY_DOWNLOAD";
    public static final String ACTION_DELETE = "com.better.heybox.ACTION_DELETE_VIDEO";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_URI = "uri";

    /** 任务历史持久化 key（HeyboxPrefs 内 JSON 数组） */
    private static final String KEY_TASK_HISTORY = "video_task_history";

    /** 进程内单例 */
    private static final VideoDownloadManager INSTANCE = new VideoDownloadManager();

    private static final int NOTIF_BASE = 0x5644; // 'VD'
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;
    private static final int MAX_AUTO_RETRY = 1;
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024; // 2GB 上限
    private static final long PROGRESS_INTERVAL_MS = 500;

    private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "BetterHeybox-video-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** 任务注册表：url → 任务，含终态（插入序，管理页展示用）。synchronized 保护。 */
    private final Map<String, DownloadTask> tasks = new LinkedHashMap<>();

    /** 任务变化监听（主线程回调） */
    private final CopyOnWriteArrayList<TaskListener> listeners = new CopyOnWriteArrayList<>();

    /** UI 操作 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile Context appContext;

    private VideoDownloadManager() {
    }

    public static VideoDownloadManager get() {
        return INSTANCE;
    }


    /** 任务集变化回调（主线程）。任何状态/进度变化都会触发，UI 侧自行按需读取。 */
    public interface TaskListener {
        void onTasksChanged();
    }

    /** 大小探测结果回调（主线程）：totalBytes 为 -1 表示未知，segments 为 HLS 分段数（非 HLS 为 -1）。 */
    public interface ProbeCallback {
        void onInfo(long totalBytes, int segments);
    }

    public void addListener(TaskListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(TaskListener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (TaskListener l : listeners) {
                    try {
                        l.onTasksChanged();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /* 对外 API（全部线程安全） */

    private volatile boolean receiverRegistered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleAction(context, intent == null ? null : intent.getAction(), intent);
        }
    };

    /** 幂等 */
    public void init(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (appContext == null && app != null) {
            appContext = app;
        }
        Context ctx = appContext;
        if (ctx == null) {
            ctx = context;
        }
        if (ctx != null && !receiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_CANCEL);
                filter.addAction(ACTION_PAUSE);
                filter.addAction(ACTION_RETRY);
                filter.addAction(ACTION_DELETE);
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    ctx.registerReceiver(receiver, filter);
                }
                receiverRegistered = true;
                LogRecorder.recordEvent("视频下载广播已注册（宿主进程内）");
            } catch (Throwable t) {
                moduleLogWarn("视频下载广播注册失败", t);
            }
        }
        restoreHistory();
    }

    /** 按 URL 查找任务（含终态），无则 null。同视频不同清晰度地址会归并到同一任务。 */
    public DownloadTask findTask(String url) {
        if (url == null) {
            return null;
        }
        synchronized (tasks) {
            return tasks.get(taskKey(url));
        }
    }

    /**
 * 创建下载任务（幂等）：未完成任务重复请求只刷新通知；终态任务重新请求则重置重下
 */
    public boolean startDownload(String url, Map<String, String> headers, String suggestedName) {
        if (url == null || !isSupportedUrl(url)) {
            return false;
        }
        String key = taskKey(url);
        DownloadTask task = findTask(url);
        if (task != null && !task.isTerminal()) {
            task.notifyProgress(-1);
            return false;
        }
        if (task != null) {
            // 终态任务重新下载：清掉旧记录（断点一并清除，从头开始）
            synchronized (tasks) {
                tasks.remove(key);
            }
        }
        DownloadTask created = new DownloadTask(url, headers, suggestedName);
        created.start();
        notifyChanged();
        return true;
    }

    /** 暂停（保留断点，可续传） */
    public void pause(String url) {
        DownloadTask task = findTask(url);
        if (task != null) {
            task.pause();
        }
    }

    /** 继续/重试（从断点续传） */
    public void resume(String url) {
        DownloadTask task = findTask(url);
        if (task != null) {
            task.resumeDownload();
        }
    }

    /** 取消任务：删除断点与半成品，任务进入 CANCELLED 态（保留在列表，可重新下载） */
    public void cancel(String url) {
        DownloadTask task = findTask(url);
        if (task != null) {
            task.cancel();
        }
    }

    /** 移除任务记录；deleteFile 为 true 时同时删除已保存的文件/断点数据 */
    public void remove(String url, boolean deleteFile) {
        DownloadTask task = findTask(url);
        if (task == null) {
            return;
        }
        task.cancel();
        if (deleteFile) {
            task.deleteSavedFile();
        }
        synchronized (tasks) {
            tasks.remove(task.key);
        }
        persistTasks();
        notifyChanged();
    }

    /** 异步探测大小：mp4 走 HEAD/Range，HLS 返回分段数。结果主线程回调。 */
    public void probeInfo(String url, Map<String, String> headers, ProbeCallback callback) {
        if (url == null || callback == null) {
            return;
        }
        final Map<String, String> h = headers != null
                ? headers : Collections.<String, String>emptyMap();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                long total = -1;
                int segments = -1;
                try {
                    if (isHlsUrl(url)) {
                        String playlistUrl = url;
                        String playlist = fetchText(playlistUrl, h);
                        if (playlist != null && playlist.contains("#EXT-X-STREAM-INF")) {
                            playlistUrl = pickBestVariantStatic(playlistUrl, playlist);
                            playlist = fetchText(playlistUrl, h);
                        }
                        if (playlist != null) {
                            segments = parseSegmentUris(playlistUrl, playlist).size();
                        }
                    } else {
                        HttpURLConnection c = null;
                        try {
                            c = openConnection(url, h);
                            c.setRequestMethod("HEAD");
                            int code = c.getResponseCode();
                            if (code >= 200 && code < 300) {
                                total = c.getContentLengthLong();
                            }
                        } finally {
                            if (c != null) {
                                c.disconnect();
                            }
                        }
                        if (total <= 0) {
                            // HEAD 不被支持：Range 0-0 探测，从 Content-Range 取总长
                            HttpURLConnection g = null;
                            try {
                                g = openConnection(url, h);
                                g.setRequestProperty("Range", "bytes=0-0");
                                int code = g.getResponseCode();
                                String range = g.getHeaderField("Content-Range");
                                if (code == 206 && range != null) {
                                    int slash = range.lastIndexOf('/');
                                    if (slash >= 0 && slash < range.length() - 1) {
                                        total = Long.parseLong(range.substring(slash + 1).trim());
                                    }
                                }
                            } finally {
                                if (g != null) {
                                    g.disconnect();
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                final long fTotal = total;
                final int fSegments = segments;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callback.onInfo(fTotal, fSegments);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        });
    }

    public boolean handleAction(Context context, String action, Intent intent) {
        if (action == null || intent == null) {
            return false;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        if (ACTION_CANCEL.equals(action)) {
            if (url != null) {
                cancel(url);
            }
            return true;
        }
        if (ACTION_PAUSE.equals(action)) {
            if (url != null) {
                pause(url);
            }
            return true;
        }
        if (ACTION_RETRY.equals(action)) {
            if (url != null) {
                resume(url);
            }
            return true;
        }
        if (ACTION_DELETE.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable t) {
                    moduleLogWarn("删除已下载视频失败", t);
                }
            }
            if (url != null) {
                remove(url, false);
            }
            return true;
        }
        return false;
    }


    /**
 * 入口判断：http(s) 直链（含 m3u8），排除网页/接口地址与第三方站点 CDN
 */
    public static boolean isSupportedUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        if (isThirdPartyTrailer(lower)) {
            return false;
        }
        if (hasWebExtension(lower)) {
            return false;
        }
        return true;
    }

    /** 第三方游戏卡片预告片（误报来源）：Steam 商店素材不显示下载入口。 */
    private static boolean isThirdPartyTrailer(String lower) {
        return lower.contains("steamstatic.com")
                || lower.contains("steamusercontent.com")
                || lower.contains("steampowered.com")
                || lower.contains("store_trailers");
    }

    private static boolean hasWebExtension(String lower) {
        String path = lower;
        try {
            java.net.URI uri = java.net.URI.create(lower);
            if (uri.getPath() != null) {
                path = uri.getPath();
            }
        } catch (Throwable ignored) {
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= path.length() - 1) {
            return false;
        }
        String ext = path.substring(lastDot + 1);
        return ext.equals("html") || ext.equals("htm") || ext.equals("php")
                || ext.equals("jsp") || ext.equals("asp") || ext.equals("aspx")
                || ext.equals("json") || ext.equals("xml") || ext.equals("txt")
                || ext.equals("js") || ext.equals("css");
    }

    /**
 * 任务去重键：取 query 的 link_id（同视频不同清晰度归并），无则用 URL
 */
    private static String taskKey(String url) {
        try {
            String query = new URL(url).getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    if (pair.startsWith("link_id=") && pair.length() > 8) {
                        return "link:" + pair.substring(8);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return url;
    }

    private static boolean isHlsUrl(String url) {
        return url != null && url.toLowerCase(Locale.US).contains(".m3u8");
    }

    /** ts 为 HLS 合并产物 */
    private static boolean isVideoExtension(String ext) {
        switch (ext) {
            case "mp4":
            case "mov":
            case "m4v":
            case "mkv":
            case "webm":
            case "avi":
            case "flv":
            case "3gp":
            case "rmvb":
            case "wmv":
            case "ts":
                return true;
            default:
                return false;
        }
    }


    public enum State {
        PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    /** 状态迁移统一走 {@link #transition} */
    public final class DownloadTask implements Runnable {
        public final String url;
        public final String key;
        final Map<String, String> headers;
        final String suggestedName;
        public volatile long createdAt = System.currentTimeMillis();

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean pauseRequested = new AtomicBoolean(false);
        /** 任务级运行锁：同一时刻只有一个工作线程在下载（防止暂停/继续竞态双写断点） */
        private final Object runLock = new Object();
        /** 运行代际：暂停/继续都会递增，旧代线程在任何收尾点发现代际过期即静默退出 */
        private volatile long runGeneration;
        public volatile State state = State.PENDING;
        volatile int retryCount;
        volatile boolean hlsMode; // m3u8：进度按分段计
        volatile int segmentsDone;
        volatile int segmentsTotal;
        public volatile long downloaded;
        public volatile long total; // 总字节（-1 未知）；完成后 = downloaded
        volatile long speedBps; // 平滑后速度（字节/秒）
        volatile File tempFile;
        volatile File tempDir; // HLS 分片目录（取消时递归清理，暂停时保留）
        volatile String resolvedExt;
        public volatile Uri resultUri;
        public volatile String savedPath; // 人类可读保存路径（完成后填充）
        public volatile String errorMsg;

        DownloadTask(String url, Map<String, String> headers, String suggestedName) {
            this.url = url;
            this.key = taskKey(url);
            this.headers = headers != null ? headers : Collections.<String, String>emptyMap();
            this.suggestedName = suggestedName;
        }


        public boolean isTerminal() {
            State s = state;
            return s == State.COMPLETED || s == State.FAILED || s == State.CANCELLED;
        }

        private void transition(State next) {
            State current = state;
            // 用户已取消的任务不允许被并发收尾改写成完成/失败
            if (current == State.CANCELLED && next != State.CANCELLED) {
                return;
            }
            state = next;
            notifyChanged();
            persistTasks();
        }

        /** 列表/面板展示标题：帖子标题 > URL 文件名 > 通用名 */
        public String displayTitle() {
            String n = cleanName(suggestedName, null);
            if (n == null) {
                n = nameFromUrl(url);
            }
            if (isGenericSegmentName(n)) {
                n = null;
            }
            return n != null ? n : "视频下载";
        }

        /** 进度百分比（-1 未知）；HLS 按分段、直链按字节 */
        public int percent() {
            if (state == State.COMPLETED) {
                return 100;
            }
            if (hlsMode && segmentsTotal > 0) {
                return (int) ((segmentsDone * 100L) / segmentsTotal);
            }
            if (total > 0) {
                return (int) ((downloaded * 100L) / total);
            }
            return -1;
        }

        public String statusLine() {
            State s = state;
            switch (s) {
                case DOWNLOADING: {
                    String line = hlsMode
                            ? String.format(Locale.US, "分段 %d / %d", segmentsDone, segmentsTotal)
                            : (total > 0
                            ? String.format(Locale.US, "%s / %s",
                            formatSize(downloaded), formatSize(total))
                            : formatSize(downloaded));
                    String speed = formatSpeed(speedBps);
                    return speed.isEmpty() ? line : line + " · " + speed;
                }
                case PAUSED:
                    return "已暂停 · " + formatSize(downloaded);
                case COMPLETED:
                    return "已完成" + (total > 0 ? " · " + formatSize(total) : "");
                case FAILED:
                    return "下载失败" + (errorMsg != null ? " · " + errorMsg : "");
                case CANCELLED:
                    return "已取消";
                default:
                    return "等待中";
            }
        }

        public void open(Context context) {
            Uri uri = resultUri;
            if (uri == null) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mimeForExtension(resultExtension()))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(context, "没有可播放的应用", Toast.LENGTH_SHORT).show();
            }
        }

        public void share(Context context) {
            Uri uri = resultUri;
            if (uri == null) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Intent i = new Intent(Intent.ACTION_SEND)
                        .setType(mimeForExtension(resultExtension()))
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(i, "分享视频");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(chooser);
            } catch (Throwable t) {
                Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show();
            }
        }


        void start() {
            runGeneration++;
            activeTasksPut(this);
            executor.execute(this);
            notifyProgress(0);
        }

        /** 暂停：保留断点（.part / 已下载分片），可续传。立即转 PAUSED，当前代线程在检查点静默退出 */
        void pause() {
            if (state != State.DOWNLOADING && state != State.PENDING) {
                return;
            }
            pauseRequested.set(true);
            runGeneration++;
            transition(State.PAUSED);
        }

        /** 继续/重试：从断点续传（进程重启后恢复的 PAUSED 任务也可续传） */
        void resumeDownload() {
            State s = state;
            if (s != State.PAUSED && s != State.FAILED && s != State.CANCELLED
                    && s != State.PENDING) {
                return;
            }
            cancelled.set(false);
            pauseRequested.set(false);
            errorMsg = null;
            retryCount = 0;
            activeTasksPut(this);
            transition(State.DOWNLOADING);
            runGeneration++;
            executor.execute(this);
            notifyProgress(-1);
        }

        /** 取消：删除断点与半成品；终态 CANCELLED 保留在列表，可重新下载 */
        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                pauseRequested.set(false);
                runGeneration++; // 使进行中的收尾（finish/网络读取）失效
                deleteTemp();
                transition(State.CANCELLED);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (notifAllowed()) {
                            showNotification(id(), buildBase("下载已取消")
                                    .setContentText(displayTitle())
                                    .setAutoCancel(true)
                                    .build());
                        }
                    }
                });
            }
        }

        /** 删除已保存的文件（完成态）与断点数据（任意态） */
        void deleteSavedFile() {
            deleteTemp();
            Uri uri = resultUri;
            if (uri != null) {
                try {
                    if ("content".equals(uri.getScheme())) {
                        appContext.getContentResolver().delete(uri, null, null);
                    } else {
                        new File(uri.getPath()).delete();
                    }
                } catch (Throwable t) {
                    moduleLogWarn("删除已保存视频失败", t);
                }
                resultUri = null;
            }
            if (savedPath != null && savedPath.startsWith("/sdcard/")) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    new File(savedPath).delete();
                } catch (Throwable ignored) {
                }
            }
        }

        private void activeTasksPut(DownloadTask t) {
            synchronized (tasks) {
                tasks.put(t.key, t);
            }
        }


        @Override
        public void run() {
            // 任务级串行化：等旧代线程完全退出后才开工，避免双写断点文件
            synchronized (runLock) {
                final long myGen = runGeneration;
                if (cancelled.get() || pauseRequested.get()) {
                    return; // 提交后未开跑就被暂停/取消（状态已由调用方迁移）
                }
                try {
                    transition(State.DOWNLOADING);
                    long fetched = isHlsUrl(url) ? fetchHls() : fetchOnce();
                    if (myGen != runGeneration || cancelled.get()) {
                        return; // 旧代线程静默退出（新代已接管，状态由新代负责）
                    }
                    if (pauseRequested.get()) {
                        transition(State.PAUSED);
                        return;
                    }
                    finish(fetched);
                } catch (Throwable t) {
                    if (myGen != runGeneration || cancelled.get()) {
                        return; // 同上：旧代退出不产生失败
                    }
                    if (pauseRequested.get()) {
                        transition(State.PAUSED);
                        return;
                    }
                    handleFailure(t);
                }
            }
        }

        private void handleFailure(Throwable t) {
            if (cancelled.get() || pauseRequested.get()
                    || state == State.CANCELLED || state == State.PAUSED) {
                return; // 用户已取消/暂停：不失败化、不自动重试（这正是「下载重复」的来源）
            }
            retryCount++;
            if (retryCount <= MAX_AUTO_RETRY) {
                // 自动重试：保留断点续传
                executor.execute(this);
                notifyProgress(-1);
                return;
            }
            errorMsg = t instanceof java.net.ConnectException
                    ? "网络连接失败"
                    : (t instanceof java.net.SocketTimeoutException
                    ? "下载超时"
                    : (t.getMessage() != null ? t.getMessage() : "下载失败"));
            transition(State.FAILED);
            final String msg = errorMsg;
            final String detail = t.getMessage() != null ? t.getMessage() : msg;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!notifAllowed()) {
                        return;
                    }
                    Notification.Builder builder = buildBase("下载失败")
                            .setContentText(msg + " · " + displayTitle())
                            .setAutoCancel(true)
                            .setStyle(new Notification.BigTextStyle()
                                    .bigText(msg + " · " + displayTitle() + "\n" + detail))
                            .addAction(0, "重试", retryPendingIntent())
                            .addAction(0, "取消", cancelPendingIntent());
                    showNotification(id(), builder.build());
                }
            });
        }


        private long fetchOnce() throws Exception {
            File part = stableTempFile();
            long offset = 0;
            boolean appending = false;
            if (part.exists() && part.length() > 0) {
                offset = part.length();
                appending = true;
            }
            tempFile = part;
            downloaded = offset;

            HttpURLConnection connection = openConnection(url, headers);
            if (appending) {
                connection.addRequestProperty("Range", "bytes=" + offset + "-");
            }
            try {
                int code = connection.getResponseCode();
                if (appending && code == 416) {
                    // 断点已等于全文件大小（暂停竞态落在完成前一瞬）：按完成处理
                    downloaded = offset;
                    total = offset;
                    speedBps = 0;
                    return offset;
                }
                if (appending && code != 206) {
                    // 服务端不支持续传：清零重下
                    offset = 0;
                    appending = false;
                    downloaded = 0;
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                if (code != 200 && code != 206) {
                    throw new IllegalStateException("HTTP " + code);
                }
                String contentType = connection.getContentType();
                if (contentType != null && !appending) {
                    String lower = contentType.toLowerCase(Locale.US);
                    boolean ok = lower.startsWith("video/")
                            || lower.contains("octet-stream")
                            || lower.contains("application/vnd.");
                    if (!ok) {
                        throw new IllegalStateException("非视频内容: " + contentType);
                    }
                    if (extensionFromUrl(url) == null) {
                        resolvedExt = extensionFromMimeType(lower);
                    }
                }
                long remaining = connection.getContentLengthLong();
                total = remaining > 0 ? offset + remaining : -1;

                long count = 0;
                long tickAt = System.currentTimeMillis();
                long tickBytes = downloaded;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream file = new FileOutputStream(part, appending)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while (!cancelled.get() && !pauseRequested.get()
                            && (read = input.read(buffer)) != -1) {
                        file.write(buffer, 0, read);
                        count += read;
                        downloaded = offset + count;
                        if (downloaded > MAX_FILE_SIZE) {
                            throw new IllegalStateException("文件过大");
                        }
                        long now = System.currentTimeMillis();
                        if (now - tickAt >= PROGRESS_INTERVAL_MS) {
                            updateSpeed(now, tickAt, tickBytes);
                            tickAt = now;
                            tickBytes = downloaded;
                            notifyProgress(-1);
                        }
                    }
                }
                if (pauseRequested.get()) {
                    return -1; // run() 统一转 PAUSED
                }
                if (cancelled.get()) {
                    throw new IllegalStateException("cancelled");
                }
                downloaded = offset + count;
                total = downloaded;
                speedBps = 0;
                return downloaded;
            } finally {
                connection.disconnect();
            }
        }


        private long fetchHls() throws Exception {
            File dir = stableHlsDir();
            boolean resuming = dir.exists();
            if (!resuming && !dir.mkdirs()) {
                throw new IllegalStateException("临时目录创建失败");
            }
            tempDir = dir;
            hlsMode = true;
            total = -1;
            try {
                String playlistUrl = url;
                String playlist = fetchText(playlistUrl, headers);
                if (playlist == null || playlist.trim().isEmpty()) {
                    throw new IllegalStateException("HLS 播放列表为空");
                }
                if (playlist.contains("#EXT-X-STREAM-INF")) {
                    playlistUrl = pickBestVariantStatic(playlistUrl, playlist);
                    LogRecorder.record(Log.INFO, "BetterHeybox", "HLS 媒体播放列表: " + playlistUrl);
                    playlist = fetchText(playlistUrl, headers);
                    if (playlist == null || playlist.trim().isEmpty()) {
                        throw new IllegalStateException("HLS 媒体播放列表为空");
                    }
                }
                requireUnencrypted(playlist);
                List<String> segments = parseSegmentUris(playlistUrl, playlist);
                if (segments.isEmpty()) {
                    throw new IllegalStateException("HLS 无可用分片");
                }
                segmentsTotal = segments.size();
                segmentsDone = 0;
                downloaded = 0;
                long bytes = 0;
                long tickAt = System.currentTimeMillis();
                long tickBytes = 0;
                for (int i = 0; i < segments.size(); i++) {
                    if (pauseRequested.get()) {
                        return -1;
                    }
                    if (cancelled.get()) {
                        throw new IllegalStateException("cancelled");
                    }
                    File seg = new File(dir, String.format(Locale.US, "seg_%05d", i));
                    if (resuming && seg.exists() && seg.length() > 0) {
                        bytes += seg.length();
                    } else {
                        bytes += downloadToFile(segments.get(i), seg);
                        if (bytes > MAX_FILE_SIZE) {
                            throw new IllegalStateException("文件过大");
                        }
                    }
                    segmentsDone = i + 1;
                    downloaded = bytes;
                    long now = System.currentTimeMillis();
                    if (now - tickAt >= PROGRESS_INTERVAL_MS) {
                        updateSpeed(now, tickAt, tickBytes);
                        tickAt = now;
                        tickBytes = bytes;
                        notifyProgress(-1);
                    }
                }
                // 按序合并为单个 MPEG-TS（ts 顺序拼接即可播放，无需 ffmpeg）。
                // 合并文件必须写在分片目录之外：deleteDir(dir) 会清分片目录，写在内会连自己一起删
                File merged = new File(newTempDir(),
                        "m_" + Integer.toHexString(url.hashCode()) + ".tmp");
                tempFile = merged;
                try (OutputStream out = new FileOutputStream(merged)) {
                    for (int i = 0; i < segments.size(); i++) {
                        if (pauseRequested.get()) {
                            return -1;
                        }
                        if (cancelled.get()) {
                            throw new IllegalStateException("cancelled");
                        }
                        File seg = new File(dir, String.format(Locale.US, "seg_%05d", i));
                        try (InputStream in = new FileInputStream(seg)) {
                            copyStream(in, out);
                        }
                    }
                }
                resolvedExt = "ts";
                deleteDir(dir);
                tempDir = null;
                total = bytes;
                speedBps = 0;
                if (bytes <= 0) {
                    throw new IllegalStateException("空文件");
                }
                return bytes;
            } catch (Throwable t) {
                if (pauseRequested.get() || cancelled.get()) {
                    return -1; // 暂停/取消：保留分片（取消由 cancel() 清理）
                }
                throw t;
            }
        }

        /** 平滑速度估算（指数滑动平均） */
        private void updateSpeed(long now, long tickAt, long tickBytes) {
            long dt = now - tickAt;
            if (dt <= 0) {
                return;
            }
            long inst = (downloaded - tickBytes) * 1000L / dt;
            speedBps = speedBps == 0 ? inst : (inst + speedBps * 3) / 4;
        }

        /** 稳定命名的断点文件（跨重试/重启保持同一文件名） */
        private File stableTempFile() {
            return new File(newTempDir(), "v_" + Integer.toHexString(url.hashCode()) + ".tmp");
        }

        private File stableHlsDir() {
            return new File(newTempDir(), "hls_" + Integer.toHexString(url.hashCode()));
        }


        private void finish(long fetched) {
            if (cancelled.get() || pauseRequested.get()) {
                return; // 收尾前任务已被取消/暂停：半成品交给 cancel/deleteTemp 处理
            }
            if (fetched <= 0) {
                handleFailure(new IllegalStateException("空文件"));
                return;
            }
            File tmp = tempFile;
            if (tmp == null || !tmp.exists()) {
                handleFailure(new IllegalStateException("临时文件缺失"));
                return;
            }
            Context context = appContext;
            if (context == null) {
                handleFailure(new IllegalStateException("Context 未初始化"));
                return;
            }
            String ext = extensionFromUrl(url);
            if (ext == null && resolvedExt != null) {
                ext = resolvedExt;
            }
            if (ext == null) {
                ext = "mp4";
            }
            // HLS 合并产物是 ts：按设置自动转封装为 MP4（无转码；失败保留 ts）
            if ("ts".equals(ext) && HeyboxPrefs.getBoolean(App.KEY_VIDEO_TO_MP4, true)) {
                notifyProgress(-1);
                File mp4 = remuxTsToMp4(tmp);
                if (cancelled.get() || pauseRequested.get()) {
                    return;
                }
                if (mp4 != null && mp4.length() > 0) {
                    File oldTs = tmp;
                    tempFile = tmp = mp4;
                    ext = "mp4";
                    //noinspection ResultOfMethodCallIgnored
                    oldTs.delete();
                    fetched = mp4.length();
                }
            }
            // 文件名优先级：视频帖子标题（捕获侧传入）> URL 文件名 > 时间戳
            String base = cleanName(suggestedName, null);
            if (base == null) {
                base = nameFromUrl(url);
            }
            if (isGenericSegmentName(base)) {
                base = null;
            }
            if (base == null) {
                base = "heybox_video_" + System.currentTimeMillis();
            }
            String fileName = uniqueFileName(context, base, ext);
            String mime = mimeForExtension(ext);
            String saveTarget = HeyboxPrefs.getString(App.KEY_VIDEO_DIR, null);
            Uri uri;
            String path;
            if (saveTarget != null && saveTarget.startsWith("content:")) {
                // 用户通过系统选择器指定的保存文件夹（SAF tree）
                StringBuilder sb = new StringBuilder();
                uri = saveToTree(context, tmp, fileName, mime, saveTarget, sb);
                path = sb.length() > 0 ? sb.toString() : fileName;
            } else if (Build.VERSION.SDK_INT >= 29) {
                uri = saveToMediaStore(context, tmp, fileName, ext);
                path = "Movies/" + storageSubDir() + "/" + fileName;
            } else {
                uri = saveToPublicMovies(context, tmp, fileName, ext);
                path = "Movies/" + storageSubDir() + "/" + fileName;
            }
            if (cancelled.get() || pauseRequested.get()) {
                return; // 转存期间被取消：文件已由 cancel 的 deleteTemp 清理
            }
            resultUri = uri;
            savedPath = path;
            total = fetched;
            downloaded = fetched;
            String finalName = fileName;
            deleteTemp();
            transition(State.COMPLETED);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyCompleted(finalName, uri, path);
                }
            });
        }

        /** 产出文件的真实扩展名（URL 推断优先，其次响应推断） */
        private String resultExtension() {
            String ext = extensionFromUrl(url);
            return ext != null ? ext : (resolvedExt != null ? resolvedExt : "mp4");
        }

        private Uri saveToMediaStore(Context context, File file, String fileName, String ext) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, mimeForExtension(ext));
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/" + storageSubDir());
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                Uri uri = context.getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return null;
                }
                try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                     InputStream in = new FileInputStream(file)) {
                    if (os == null) {
                        return null;
                    }
                    copyStream(in, os);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, done, null, null);
                return uri;
            } catch (Throwable t) {
                moduleLogWarn("写入视频相册失败", t);
                return null;
            }
        }

        /**
 * 写入用户选择的 SAF 文件夹；重名自动 (n)，outPath 填充可读保存路径
 */
        private Uri saveToTree(Context context, File file, String fileName, String mime,
                               String treeUriString, StringBuilder outPath) {
            try {
                Uri treeUri = Uri.parse(treeUriString);
                Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri));
                ContentResolver resolver = context.getContentResolver();
                Uri doc = null;
                String candidate = fileName;
                int dot = fileName.lastIndexOf('.');
                String base = dot > 0 ? fileName.substring(0, dot) : fileName;
                String ext = dot > 0 ? fileName.substring(dot) : "";
                for (int i = 0; i < 100 && doc == null; i++) {
                    try {
                        doc = android.provider.DocumentsContract.createDocument(
                                resolver, parent, mime, candidate);
                    } catch (Throwable t) {
                        doc = null;
                    }
                    if (doc == null && i >= 1) {
                        break;
                    }
                    candidate = base + "(" + (i + 1) + ")" + ext;
                }
                if (doc == null) {
                    return null;
                }
                try (OutputStream os = resolver.openOutputStream(doc);
                     InputStream in = new FileInputStream(file)) {
                    if (os == null) {
                        return null;
                    }
                    copyStream(in, os);
                }
                // 人类可读路径："primary:Movies/xx/名.mp4" → "/sdcard/Movies/xx/名.mp4"
                String docId = android.provider.DocumentsContract.getDocumentId(doc);
                int colon = docId.indexOf(':');
                String rel = colon >= 0 ? docId.substring(colon + 1) : docId;
                outPath.append("/sdcard/").append(rel);
                MediaScannerConnection.scanFile(context,
                        new String[]{outPath.toString()}, new String[]{mime}, null);
                return doc;
            } catch (Throwable t) {
                moduleLogWarn("写入所选文件夹失败", t);
                return null;
            }
        }

        private Uri saveToPublicMovies(Context context, File file, String fileName, String ext) {
            try {
                if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), storageSubDir());
                if (!dir.exists() && !dir.mkdirs()) {
                    return null;
                }
                File target = new File(dir, fileName);
                try (InputStream in = new FileInputStream(file);
                     OutputStream os = new FileOutputStream(target)) {
                    copyStream(in, os);
                }
                MediaScannerConnection.scanFile(context,
                        new String[]{target.getAbsolutePath()}, null, null);
                return Uri.fromFile(target);
            } catch (Throwable t) {
                moduleLogWarn("写入公共 Movies 目录失败", t);
                return null;
            }
        }

        /** 重名自动加 (n) 后缀，绝不覆盖：检查 MediaStore 与公共目录两个视图 */
        private String uniqueFileName(Context context, String base, String ext) {
            String full = base + "." + ext;
            if (!existsInMediaStore(context, full)
                    && !existsInPublicMovies(context, full)) {
                return full;
            }
            for (int i = 1; i < 1000; i++) {
                String candidate = base + "(" + i + ")." + ext;
                if (!existsInMediaStore(context, candidate)
                        && !existsInPublicMovies(context, candidate)) {
                    return candidate;
                }
            }
            return base + "_" + System.currentTimeMillis() + "." + ext;
        }

        private boolean existsInMediaStore(Context context, String displayName) {
            try {
                String[] projection = {MediaStore.Video.Media.DISPLAY_NAME};
                String selection = MediaStore.Video.Media.DISPLAY_NAME + " = ?";
                try (android.database.Cursor cursor = context.getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection, selection, new String[]{displayName}, null)) {
                    return cursor != null && cursor.moveToFirst();
                }
            } catch (Throwable t) {
                return false;
            }
        }

        private boolean existsInPublicMovies(Context context, String displayName) {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), storageSubDir());
                return new File(dir, displayName).exists();
            } catch (Throwable t) {
                return false;
            }
        }

        /**
 * ts→mp4 无转码转封装；全轨道交错读取（按轨抽干会致音轨无样本），失败返回 null 保留 ts
 */
        private File remuxTsToMp4(File tsFile) {
            MediaExtractor extractor = new MediaExtractor();
            MediaMuxer muxer = null;
            File out = new File(tsFile.getParentFile(),
                    "convert-" + System.currentTimeMillis() + ".mp4");
            try {
                extractor.setDataSource(tsFile.getAbsolutePath());
                int trackCount = extractor.getTrackCount();
                int videoTracks = 0;
                int maxSample = 1 << 20;
                for (int i = 0; i < trackCount; i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime == null) {
                        return null;
                    }
                    if (mime.startsWith("video/")) {
                        videoTracks++;
                    }
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxSample = Math.max(maxSample, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                }
                if (videoTracks == 0) {
                    return null; // 无视频轨（纯音频等），保留 ts
                }
                muxer = new MediaMuxer(out.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int[] muxTracks = new int[trackCount];
                for (int i = 0; i < trackCount; i++) {
                    muxTracks[i] = muxer.addTrack(extractor.getTrackFormat(i));
                }
                muxer.start();
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(maxSample);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                for (int i = 0; i < trackCount; i++) {
                    extractor.selectTrack(i);
                }
                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        break;
                    }
                    int trackIndex = extractor.getSampleTrackIndex();
                    if (trackIndex < 0 || trackIndex >= trackCount) {
                        break;
                    }
                    info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
                    muxer.writeSampleData(muxTracks[trackIndex], buffer, info);
                    extractor.advance();
                }
                muxer.stop();
                LogRecorder.record(Log.INFO, "BetterHeybox",
                        "ts→mp4 转封装完成: " + out.getName());
                return out;
            } catch (Throwable t) {
                moduleLogWarn("ts→mp4 转封装失败（保留 ts）", t);
                if (out.exists() && !out.delete()) {
                    out.deleteOnExit();
                }
                return null;
            } finally {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                }
                if (muxer != null) {
                    try {
                        muxer.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        /** 删除本次任务的半成品临时文件与分片目录（取消/完成时调用；暂停保留断点） */
        private void deleteTemp() {
            File tmp = tempFile;
            if (tmp != null && tmp.exists() && !tmp.delete()) {
                tmp.deleteOnExit();
            }
            tempFile = null;
            File dir = tempDir;
            if (dir != null) {
                deleteDir(dir);
                tempDir = null;
            }
        }


        private int id() {
            return NOTIF_BASE + (url.hashCode() & 0xFFFF);
        }

        private boolean notifAllowed() {
            Context context = appContext;
            if (context == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            } catch (Throwable t) {
                return false;
            }
        }

        private void notifyProgress(long downloadedNow) {
            if (downloadedNow >= 0) {
                downloaded = downloadedNow;
            }
            notifyChanged(); // 实时刷新
            if (!notifAllowed()) {
                return;
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (state != State.DOWNLOADING) {
                        return;
                    }
                    Notification.Builder builder = buildBase("正在下载视频")
                            .setContentText(displayTitle() + " · " + statusLine())
                            .setOnlyAlertOnce(true)
                            .setOngoing(true);
                    int pct = percent();
                    if (pct >= 0) {
                        builder.setProgress(100, pct, false);
                    } else {
                        builder.setProgress(0, 0, true);
                    }
                    builder.addAction(0, "暂停", pausePendingIntent());
                    builder.addAction(0, "取消", cancelPendingIntent());
                    showNotification(id(), builder.build());
                }
            });
        }

        private void notifyCompleted(String fileName, Uri uri, String savedPath) {
            if (!notifAllowed()) {
                return;
            }
            Notification.Builder builder = buildBase("下载完成");
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done);
            builder.setContentText("已保存到 " + savedPath)
                    .setStyle(new Notification.BigTextStyle()
                            .bigText("已保存到 " + savedPath))
                    .setAutoCancel(true);
            if (uri != null) {
                builder.setContentIntent(openPendingIntent(uri))
                        .addAction(0, "分享", sharePendingIntent(uri));
            }
            builder.addAction(0, "删除", deletePendingIntent(uri));
            showNotification(id(), builder.build());
        }

        private PendingIntent pausePendingIntent() {
            Intent i = new Intent(ACTION_PAUSE)
                    .setPackage(context().getPackageName())
                    .putExtra(EXTRA_URL, url);
            return PendingIntent.getBroadcast(context(), id() + 3, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        private PendingIntent cancelPendingIntent() {
            Intent i = new Intent(ACTION_CANCEL)
                    .setPackage(context().getPackageName())
                    .putExtra(EXTRA_URL, url);
            return PendingIntent.getBroadcast(context(), id(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        private PendingIntent retryPendingIntent() {
            Intent i = new Intent(ACTION_RETRY)
                    .setPackage(context().getPackageName())
                    .putExtra(EXTRA_URL, url);
            return PendingIntent.getBroadcast(context(), id(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        private PendingIntent openPendingIntent(Uri uri) {
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mimeForExtension(resultExtension()))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(context(), id(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        private PendingIntent sharePendingIntent(Uri uri) {
            Intent i = new Intent(Intent.ACTION_SEND)
                    .setType(mimeForExtension(resultExtension()))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "分享视频");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(context(), id() + 1, chooser,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        private PendingIntent deletePendingIntent(Uri uri) {
            Intent i = new Intent(ACTION_DELETE)
                    .setPackage(context().getPackageName())
                    .putExtra(EXTRA_URI, uri)
                    .putExtra(EXTRA_URL, url);
            return PendingIntent.getBroadcast(context(), id() + 2, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        private Context context() {
            Context c = appContext;
            if (c == null) {
                throw new IllegalStateException("VideoDownloadManager 未初始化");
            }
            return c;
        }


        /** 加密 HLS（DRM 等价物）不支持下载 */
        private void requireUnencrypted(String playlist) {
            for (String line : playlist.split("\n")) {
                String l = line.trim();
                if ((l.startsWith("#EXT-X-KEY") || l.startsWith("#EXT-X-SESSION-KEY"))
                        && !l.contains("METHOD=NONE")) {
                    throw new IllegalStateException("HLS 加密流，不支持下载");
                }
            }
        }

        /** 单文件下载（分片）：返回字节数 */
        private long downloadToFile(String fileUrl, File target) throws Exception {
            HttpURLConnection connection = openConnection(fileUrl, headers);
            try {
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code + " (segment)");
                }
                try (InputStream input = connection.getInputStream();
                     FileOutputStream file = new FileOutputStream(target)) {
                    return copyStream(input, file);
                }
            } finally {
                connection.disconnect();
            }
        }

    }


    /** 统一建连：防盗链请求头（捕获的 + 默认 UA/Referer 兜底）+ 超时 + 重定向 */
    private static HttpURLConnection openConnection(String target, Map<String, String> headers)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        Map<String, String> merged = new HashMap<>(headers);
        if (!merged.containsKey("User-Agent") && !merged.containsKey("user-agent")) {
            merged.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 heybox");
        }
        if (!merged.containsKey("Referer") && !merged.containsKey("referer")) {
            merged.put("Referer", "https://api.xiaoheihe.cn/");
        }
        merged.put("Accept", "video/*,*/*;q=0.8");
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                connection.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return connection;
    }

    private static long copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        long count = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            count += read;
        }
        return count;
    }

    private static String fetchText(String textUrl, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = openConnection(textUrl, headers);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " (playlist)");
            }
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                copyStream(in, bos);
                return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** master playlist：选 BANDWIDTH 最高的变体，返回绝对 URL（静态版，探测/下载共用） */
    private static String pickBestVariantStatic(String playlistUrl, String playlist) {
        long bestBw = -1;
        String bestUri = null;
        long pendingBw = -1;
        for (String line : playlist.split("\n")) {
            String l = line.trim();
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                pendingBw = parseBandwidth(l);
            } else if (!l.isEmpty() && !l.startsWith("#")) {
                if (pendingBw > bestBw) {
                    bestBw = pendingBw;
                    bestUri = l;
                }
                pendingBw = -1;
            }
        }
        if (bestUri == null) {
            throw new IllegalStateException("HLS master 无变体");
        }
        return absolutize(playlistUrl, bestUri);
    }

    private static long parseBandwidth(String line) {
        int idx = line.toUpperCase(Locale.US).indexOf("BANDWIDTH=");
        if (idx < 0) {
            return -1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = idx + "BANDWIDTH=".length(); i < line.length(); i++) {
            char c = line.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String absolutize(String baseUrl, String ref) {
        try {
            return new URL(new URL(baseUrl), ref).toString();
        } catch (Throwable t) {
            return ref;
        }
    }

    /** 提取媒体播放列表的分片 URI（非注释行），并绝对化 */
    private static List<String> parseSegmentUris(String playlistUrl, String playlist) {
        List<String> segments = new ArrayList<>();
        for (String line : playlist.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            segments.add(absolutize(playlistUrl, l));
        }
        return segments;
    }


    private void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID_DOWNLOADS) != null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID_DOWNLOADS, "视频下载",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("小黑盒视频下载进度与结果");
            channel.enableVibration(false);
            channel.enableLights(false);
            nm.createNotificationChannel(channel);
        } catch (Throwable ignored) {
        }
    }

    private Notification.Builder buildBase(String title) {
        Context context = appContext;
        ensureChannel(context);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(context, CHANNEL_ID_DOWNLOADS);
        } else {
            builder = new Notification.Builder(context);
        }
        // 必须设置 smallIcon，否则 NotificationManager 直接拒收（no valid small icon）
        builder.setSmallIcon(android.R.drawable.stat_sys_download);
        builder.setContentTitle(title);
        return builder;
    }

    /**
 * 发通知（方法名避开 notify，防在内部类里与 Object.notify() 冲突）
 */
    private void showNotification(int id, Notification notification) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(id, notification);
            }
        } catch (Throwable t) {
            moduleLogWarn("发出通知失败", t);
        }
    }


    private void persistTasks() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (tasks) {
                for (DownloadTask t : tasks.values()) {
                    State s = t.state;
                    if (s == State.DOWNLOADING || s == State.PENDING) {
                        continue; // 瞬态不入盘
                    }
                    JSONObject o = new JSONObject();
                    o.put("url", t.url);
                    o.put("title", t.displayTitle());
                    o.put("state", s.name());
                    o.put("path", t.savedPath != null ? t.savedPath : "");
                    o.put("uri", t.resultUri != null ? t.resultUri.toString() : "");
                    o.put("total", t.total);
                    o.put("at", t.createdAt);
                    JSONArray hs = new JSONArray();
                    for (Map.Entry<String, String> e : t.headers.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) {
                            hs.put(e.getKey() + "\u0001" + e.getValue());
                        }
                    }
                    o.put("headers", hs);
                    arr.put(o);
                }
            }
            HeyboxPrefs.setString(KEY_TASK_HISTORY, arr.toString());
        } catch (Throwable t) {
            moduleLogWarn("任务历史写入失败", t);
        }
    }

    private void restoreHistory() {
        try {
            String raw = HeyboxPrefs.getString(KEY_TASK_HISTORY, null);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            JSONArray arr = new JSONArray(raw);
            synchronized (tasks) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String url = o.optString("url", null);
                    if (url == null || tasks.containsKey(taskKey(url))) {
                        continue;
                    }
                    State st;
                    try {
                        st = State.valueOf(o.optString("state", "PAUSED"));
                    } catch (Throwable bad) {
                        st = State.PAUSED;
                    }
                    if (st == State.DOWNLOADING || st == State.PENDING) {
                        st = State.PAUSED; // 进程被杀时的瞬态归档为暂停
                    }
                    Map<String, String> headers = new HashMap<>();
                    JSONArray hs = o.optJSONArray("headers");
                    if (hs != null) {
                        for (int j = 0; j < hs.length(); j++) {
                            String pair = hs.optString(j, "");
                            int sep = pair.indexOf('\u0001');
                            if (sep > 0) {
                                headers.put(pair.substring(0, sep), pair.substring(sep + 1));
                            }
                        }
                    }
                    DownloadTask t = new DownloadTask(url, headers, null);
                    t.state = st;
                    t.savedPath = o.optString("path", null);
                    if (t.savedPath != null && t.savedPath.isEmpty()) {
                        t.savedPath = null;
                    }
                    String uriStr = o.optString("uri", null);
                    if (uriStr != null && !uriStr.isEmpty()) {
                        try {
                            t.resultUri = Uri.parse(uriStr);
                        } catch (Throwable ignored) {
                        }
                    }
                    t.total = o.optLong("total", -1);
                    t.createdAt = o.optLong("at", System.currentTimeMillis());
                    tasks.put(t.key, t);
                }
            }
        } catch (Throwable t) {
            moduleLogWarn("任务历史恢复失败", t);
        }
    }


    private File newTempDir() {
        Context context = appContext;
        File dir = context != null ? context.getExternalCacheDir() : null;
        if (dir == null) {
            dir = context != null ? context.getCacheDir() : null;
        }
        if (dir == null) {
            dir = new File(System.getProperty("java.io.tmpdir"));
        }
        File sub = new File(dir, "betterheybox-video");
        if (!sub.exists() && !sub.mkdirs()) {
            return dir;
        }
        return sub;
    }

    /** 设置中的保存目录（Movies 下的子目录名），非法输入回退默认值 */
    private static String storageSubDir() {
        String raw = HeyboxPrefs.getString(App.KEY_VIDEO_DIR, null);
        String clean = cleanName(raw, null);
        if (clean == null || clean.equals(".") || clean.equals("..")) {
            return "BetterHeybox";
        }
        return clean;
    }

    /** URL 最后一段是否为 HLS 通用名（segs/index 等），不能当文件名用 */
    private static boolean isGenericSegmentName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.equals("segs") || lower.equals("seg") || lower.equals("index")
                || lower.equals("playlist") || lower.equals("master")
                || lower.equals("chunklist") || lower.equals("video")
                || lower.equals("hls") || lower.equals("main");
    }

    private static void deleteDir(File dir) {
        try {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.isDirectory()) {
                        deleteDir(c);
                    } else {
                        if (!c.delete()) {
                            c.deleteOnExit();
                        }
                    }
                }
            }
            if (!dir.delete()) {
                dir.deleteOnExit();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 去非法字符、压缩空白、截断过长 */
    static String cleanName(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String clean = raw.replaceAll("[\\\\/:*?\"<>|]", "_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '_' : c);
        }
        clean = sb.toString().trim().replaceAll("\\s+", " ");
        if (clean.length() > 80) {
            clean = clean.substring(0, 80).trim();
        }
        if (clean.isEmpty()) {
            return fallback;
        }
        return clean;
    }

    /** URL 最后一段（去 query 与扩展名）作为默认文件名；无则返回 null */
    static String nameFromUrl(String url) {
        try {
            URL u = new URL(url);
            String path = u.getPath();
            if (path == null) {
                return null;
            }
            int idx = path.lastIndexOf('/');
            String name = idx >= 0 ? path.substring(idx + 1) : path;
            if (name.isEmpty()) {
                return null;
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            while (name.endsWith("_") || name.endsWith(".")) {
                name = name.substring(0, name.length() - 1);
            }
            return name.isEmpty() ? null : name;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extensionFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.US);
                if (isVideoExtension(ext)) {
                    return ext;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从响应 Content-Type 回退出扩展名（如 "video/mp4" → "mp4"，"video/mp2t" → "ts"） */
    private static String extensionFromMimeType(String mime) {
        if (mime == null) {
            return "mp4";
        }
        String lower = mime.toLowerCase(Locale.US);
        int slash = lower.indexOf('/');
        int semi = lower.indexOf(';');
        String subtype = (semi >= 0 ? lower.substring(0, semi) : lower);
        if (slash >= 0 && slash < subtype.length() - 1) {
            String ext = subtype.substring(slash + 1).trim();
            if (ext.equals("mp2t")) {
                return "ts";
            }
            if (!ext.isEmpty() && ext.length() <= 8
                    && ext.matches("[a-z0-9]+")) {
                return ext;
            }
        }
        return "mp4";
    }

    private static String mimeForExtension(String ext) {
        if (ext == null) {
            return "video/mp4";
        }
        switch (ext) {
            case "mkv":
                return "video/x-matroska";
            case "webm":
                return "video/webm";
            case "avi":
                return "video/x-msvideo";
            case "flv":
                return "video/x-flv";
            case "mov":
                return "video/quicktime";
            case "3gp":
                return "video/3gpp";
            case "ts":
                return "video/mp2t";
            case "mp4":
            default:
                return "video/mp4";
        }
    }

    /** UI 跨包使用 */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "";
        }
        if (bytesPerSec < 1024) {
            return bytesPerSec + " B/s";
        }
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    private static void moduleLogWarn(String msg, Throwable t) {
        try {
            LogRecorder.record(Log.WARN, "BetterHeybox", msg + ": " + t);
        } catch (Throwable ignored) {
        }
    }
}
