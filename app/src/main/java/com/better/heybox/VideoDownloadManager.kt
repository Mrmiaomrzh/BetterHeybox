package com.better.heybox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VideoDownloadManager private constructor() {

    private val executor: ExecutorService = Executors.newCachedThreadPool(object : ThreadFactory {
        private val seq = AtomicInteger()

        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "BetterHeybox-video-" + seq.incrementAndGet())
            t.setDaemon(true)
            return t
        }
    })

    private val tasks: MutableMap<String, DownloadTask> = LinkedHashMap()

    private val listeners = CopyOnWriteArrayList<TaskListener>()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    interface TaskListener {
        fun onTasksChanged()
    }

    interface ProbeCallback {
        fun onInfo(totalBytes: Long, segments: Int)
    }

    fun addListener(listener: TaskListener?) {
        if (listener != null) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: TaskListener?) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onTasksChanged()
                } catch (ignored: Throwable) {
                }
            }
        }
    }


    @Volatile
    private var receiverRegistered = false

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleAction(context, intent?.getAction(), intent)
        }
    }

    fun init(context: Context?) {
        if (context == null) {
            return
        }
        val app = context.getApplicationContext()
        if (appContext == null && app != null) {
            appContext = app
        }
        var ctx = appContext
        if (ctx == null) {
            ctx = context
        }
        if (ctx != null && !receiverRegistered) {
            try {
                val filter = IntentFilter()
                filter.addAction(ACTION_CANCEL)
                filter.addAction(ACTION_PAUSE)
                filter.addAction(ACTION_RETRY)
                filter.addAction(ACTION_DELETE)
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    ctx.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
                LogRecorder.recordEvent("视频下载广播已注册（宿主进程内）")
            } catch (t: Throwable) {
                moduleLogWarn("视频下载广播注册失败", t)
            }
        }
        restoreHistory()
    }

    fun findTask(url: String?): DownloadTask? {
        if (url == null) {
            return null
        }
        synchronized(tasks) {
            return tasks[taskKey(url)]
        }
    }

    fun startDownload(url: String?, headers: Map<String, String>?, suggestedName: String?) {
        if (url == null || !isSupportedUrl(url)) {
            return
        }
        val key = taskKey(url)
        val task = findTask(url)
        if (task != null && !task.isTerminal()) {
            task.notifyProgress()
            return
        }
        if (task != null) {
            synchronized(tasks) {
                tasks.remove(key)
            }
        }
        val created = DownloadTask(url, headers, suggestedName)
        created.start()
        notifyChanged()
    }

    fun pause(url: String?) {
        val task = findTask(url)
        if (task != null) {
            task.pause()
        }
    }

    fun resume(url: String?) {
        val task = findTask(url)
        if (task != null) {
            task.resumeDownload()
        }
    }

    fun cancel(url: String?) {
        val task = findTask(url)
        if (task != null) {
            task.cancel()
        }
    }

    fun remove(url: String?) {
        val task = findTask(url)
        if (task == null) {
            return
        }
        task.cancel()
        synchronized(tasks) {
            tasks.remove(task.key)
        }
        persistTasks()
        notifyChanged()
    }

    fun probeInfo(url: String?, headers: Map<String, String>?, callback: ProbeCallback?) {
        if (url == null || callback == null) {
            return
        }
        val h: Map<String, String> = headers ?: emptyMap<String, String>()
        executor.execute(object : Runnable {
            override fun run() {
                var total = -1L
                var segments = -1
                try {
                    if (isHlsUrl(url)) {
                        var playlistUrl = url
                        var playlist = fetchText(playlistUrl, h)
                        if (playlist != null && playlist.contains("#EXT-X-STREAM-INF")) {
                            playlistUrl = pickBestVariantStatic(playlistUrl, playlist)
                            playlist = fetchText(playlistUrl, h)
                        }
                        if (playlist != null) {
                            segments = parseSegmentUris(playlistUrl, playlist).size
                        }
                    } else {
                        var c: HttpURLConnection? = null
                        try {
                            c = openConnection(url, h)
                            c.setRequestMethod("HEAD")
                            val code = c.getResponseCode()
                            if (code >= 200 && code < 300) {
                                total = c.getContentLengthLong()
                            }
                        } finally {
                            if (c != null) {
                                c.disconnect()
                            }
                        }
                        if (total <= 0) {
                            var g: HttpURLConnection? = null
                            try {
                                g = openConnection(url, h)
                                g.setRequestProperty("Range", "bytes=0-0")
                                val code = g.getResponseCode()
                                val range = g.getHeaderField("Content-Range")
                                if (code == 206 && range != null) {
                                    val slash = range.lastIndexOf('/')
                                    if (slash >= 0 && slash < range.length - 1) {
                                        total = java.lang.Long.parseLong(
                                            range.substring(slash + 1).trim()
                                        )
                                    }
                                }
                            } finally {
                                if (g != null) {
                                    g.disconnect()
                                }
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                }
                val fTotal = total
                val fSegments = segments
                mainHandler.post {
                    try {
                        callback.onInfo(fTotal, fSegments)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        })
    }

    fun handleAction(context: Context?, action: String?, intent: Intent?) {
        if (action == null || intent == null) {
            return
        }
        val url = intent.getStringExtra(EXTRA_URL)
        if (ACTION_CANCEL == action) {
            if (url != null) {
                cancel(url)
            }
            return
        }
        if (ACTION_PAUSE == action) {
            if (url != null) {
                pause(url)
            }
            return
        }
        if (ACTION_RETRY == action) {
            if (url != null) {
                resume(url)
            }
            return
        }
        if (ACTION_DELETE == action) {
            val uri: Uri? = intent.getParcelableExtra(EXTRA_URI)
            if (uri != null) {
                try {
                    context!!.getContentResolver().delete(uri, null, null)
                } catch (t: Throwable) {
                    moduleLogWarn("删除已下载视频失败", t)
                }
            }
            if (url != null) {
                remove(url)
            }
        }
    }

    public enum class State {
        PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    inner class DownloadTask internal constructor(
        url: String,
        headers: Map<String, String>?,
        suggestedName: String?
    ) : Runnable {
        @JvmField
        val url: String = url

        @JvmField
        val key: String = taskKey(url)

        @JvmField
        internal val headers: Map<String, String> = headers ?: emptyMap<String, String>()

        private val suggestedName: String? = suggestedName

        @JvmField
        @Volatile
        var createdAt: Long = System.currentTimeMillis()

        private val cancelled = AtomicBoolean(false)
        private val pauseRequested = AtomicBoolean(false)

        private val runLock = Any()

        @Volatile
        private var runGeneration: Long = 0

        @JvmField
        @Volatile
        var state: State = State.PENDING

        @Volatile
        private var retryCount = 0

        @Volatile
        private var hlsMode = false

        @Volatile
        private var segmentsDone = 0

        @Volatile
        private var segmentsTotal = 0

        @JvmField
        @Volatile
        var downloaded: Long = 0

        @JvmField
        @Volatile
        var total: Long = 0

        @Volatile
        private var speedBps: Long = 0

        @Volatile
        private var tempFile: File? = null

        @Volatile
        private var tempDir: File? = null

        @Volatile
        private var resolvedExt: String? = null

        @JvmField
        @Volatile
        var resultUri: Uri? = null

        @JvmField
        @Volatile
        var savedPath: String? = null

        @JvmField
        @Volatile
        var errorMsg: String? = null

        fun isTerminal(): Boolean {
            return DownloadStateRules.isTerminal(state)
        }

        private fun transition(next: State) {
            if (!DownloadStateRules.allowsTransition(state, next)) {
                return
            }
            state = next
            notifyChanged()
            persistTasks()
        }

        fun displayTitle(): String {
            val n = bestBaseName()
            return n ?: "视频下载"
        }

        private fun bestBaseName(): String? {
            var n = cleanName(suggestedName, null)
            if (n == null) {
                n = nameFromUrl(url)
            }
            return if (isGenericSegmentName(n)) null else n
        }

        fun percent(): Int {
            if (state == State.COMPLETED) {
                return 100
            }
            if (hlsMode && segmentsTotal > 0) {
                return ((segmentsDone * 100L) / segmentsTotal).toInt()
            }
            if (total > 0) {
                return ((downloaded * 100L) / total).toInt()
            }
            return -1
        }

        fun statusLine(): String {
            val s = state
            return when (s) {
                State.DOWNLOADING -> {
                    val line = if (hlsMode) {
                        String.format(Locale.US, "分段 %d / %d", segmentsDone, segmentsTotal)
                    } else if (total > 0) {
                        String.format(
                            Locale.US, "%s / %s",
                            formatSize(downloaded), formatSize(total)
                        )
                    } else {
                        formatSize(downloaded)
                    }
                    val speed = formatSpeed(speedBps)
                    if (speed.isEmpty()) line else line + " · " + speed
                }

                State.PAUSED -> "已暂停 · " + formatSize(downloaded)
                State.COMPLETED -> "已完成" + (if (total > 0) " · " + formatSize(total) else "")
                State.FAILED -> "下载失败" + (if (errorMsg != null) " · " + errorMsg else "")
                State.CANCELLED -> "已取消"
                else -> "等待中"
            }
        }

        fun open(context: Context) {
            val uri = resultUri
            if (uri == null) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                context.startActivity(buildViewIntent(uri))
            } catch (t: Throwable) {
                Toast.makeText(context, "没有可播放的应用", Toast.LENGTH_SHORT).show()
            }
        }

        fun share(context: Context) {
            val uri = resultUri
            if (uri == null) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val chooser = Intent.createChooser(buildShareIntent(uri), "分享视频")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (t: Throwable) {
                Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
            }
        }

        private fun buildViewIntent(uri: Uri): Intent {
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeForExtension(resultExtension()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        private fun buildShareIntent(uri: Uri): Intent {
            return Intent(Intent.ACTION_SEND)
                .setType(mimeForExtension(resultExtension()))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        @JvmName("start")
        internal fun start() {
            runGeneration++
            activeTasksPut(this)
            executor.execute(this)
            notifyProgress()
        }

        @JvmName("pause")
        internal fun pause() {
            if (!DownloadStateRules.canPause(state)) {
                return
            }
            pauseRequested.set(true)
            runGeneration++
            transition(State.PAUSED)
        }

        @JvmName("resumeDownload")
        internal fun resumeDownload() {
            if (!DownloadStateRules.canResume(state)) {
                return
            }
            cancelled.set(false)
            pauseRequested.set(false)
            errorMsg = null
            retryCount = 0
            activeTasksPut(this)
            transition(State.DOWNLOADING)
            runGeneration++
            executor.execute(this)
            notifyProgress()
        }

        @JvmName("cancel")
        internal fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                pauseRequested.set(false)
                runGeneration++
                deleteTemp()
                transition(State.CANCELLED)
                mainHandler.post {
                    if (notifAllowed()) {
                        showNotification(
                            id(),
                            buildBase("下载已取消")
                                .setContentText(displayTitle())
                                .setAutoCancel(true)
                                .build()
                        )
                    }
                }
            }
        }

        private fun activeTasksPut(t: DownloadTask) {
            synchronized(tasks) {
                tasks[t.key] = t
            }
        }

        override fun run() {
            synchronized(runLock) {
                val myGen = runGeneration
                if (cancelled.get() || pauseRequested.get()) {
                    return
                }
                try {
                    transition(State.DOWNLOADING)
                    val fetched = if (isHlsUrl(url)) fetchHls() else fetchOnce()
                    if (myGen != runGeneration || cancelled.get()) {
                        return
                    }
                    if (pauseRequested.get()) {
                        transition(State.PAUSED)
                        return
                    }
                    finish(fetched)
                } catch (t: Throwable) {
                    if (myGen != runGeneration || cancelled.get()) {
                        return
                    }
                    if (pauseRequested.get()) {
                        transition(State.PAUSED)
                        return
                    }
                    handleFailure(t)
                }
            }
        }

        private fun handleFailure(t: Throwable) {
            if (cancelled.get() || pauseRequested.get() ||
                state == State.CANCELLED || state == State.PAUSED
            ) {
                return
            }
            retryCount++
            if (retryCount <= MAX_AUTO_RETRY) {
                executor.execute(this)
                notifyProgress()
                return
            }
            errorMsg = if (t is java.net.ConnectException) {
                "网络连接失败"
            } else if (t is java.net.SocketTimeoutException) {
                "下载超时"
            } else {
                t.message ?: "下载失败"
            }
            transition(State.FAILED)
            val msg = errorMsg
            val detail = t.message ?: msg
            mainHandler.post {
                if (!notifAllowed()) {
                    return@post
                }
                val builder = buildBase("下载失败")
                    .setContentText(msg + " · " + displayTitle())
                    .setAutoCancel(true)
                    .setStyle(
                        Notification.BigTextStyle()
                            .bigText(msg + " · " + displayTitle() + "\n" + detail)
                    )
                    .addAction(0, "重试", retryPendingIntent())
                    .addAction(0, "取消", cancelPendingIntent())
                showNotification(id(), builder.build())
            }
        }

        @Throws(Exception::class)
        private fun fetchOnce(): Long {
            val part = stableTempFile()
            var offset = 0L
            var appending = false
            if (part.exists() && part.length() > 0) {
                offset = part.length()
                appending = true
            }
            tempFile = part
            downloaded = offset

            val connection = openConnection(url, headers)
            if (appending) {
                connection.addRequestProperty("Range", "bytes=" + offset + "-")
            }
            try {
                val code = connection.getResponseCode()
                if (appending && code == 416) {
                    downloaded = offset
                    total = offset
                    speedBps = 0
                    return offset
                }
                if (appending && code != 206) {
                    offset = 0
                    appending = false
                    downloaded = 0
                    part.delete()
                }
                if (code != 200 && code != 206) {
                    throw IllegalStateException("HTTP $code")
                }
                val contentType = connection.getContentType()
                if (contentType != null && !appending) {
                    val lower = contentType.lowercase(Locale.US)
                    val ok = lower.startsWith("video/") ||
                        lower.contains("octet-stream") ||
                        lower.contains("application/vnd.")
                    if (!ok) {
                        throw IllegalStateException("非视频内容: $contentType")
                    }
                    if (extensionFromUrl(url) == null) {
                        resolvedExt = extensionFromMimeType(lower)
                    }
                }
                val remaining = connection.getContentLengthLong()
                total = if (remaining > 0) offset + remaining else -1L

                var count = 0L
                var tickAt = System.currentTimeMillis()
                var tickBytes = downloaded
                connection.getInputStream().use { input ->
                    FileOutputStream(part, appending).use { file ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (!cancelled.get() && !pauseRequested.get()) {
                            val read = input.read(buffer)
                            if (read == -1) {
                                break
                            }
                            file.write(buffer, 0, read)
                            count += read
                            downloaded = offset + count
                            if (downloaded > MAX_FILE_SIZE) {
                                throw IllegalStateException("文件过大")
                            }
                            val now = System.currentTimeMillis()
                            if (now - tickAt >= PROGRESS_INTERVAL_MS) {
                                updateSpeed(now, tickAt, tickBytes)
                                tickAt = now
                                tickBytes = downloaded
                                notifyProgress()
                            }
                        }
                    }
                }
                if (pauseRequested.get()) {
                    return -1L
                }
                if (cancelled.get()) {
                    throw IllegalStateException("cancelled")
                }
                downloaded = offset + count
                total = downloaded
                speedBps = 0
                return downloaded
            } finally {
                connection.disconnect()
            }
        }

        @Throws(Exception::class)
        private fun fetchHls(): Long {
            val dir = stableHlsDir()
            val resuming = dir.exists()
            if (!resuming && !dir.mkdirs()) {
                throw IllegalStateException("临时目录创建失败")
            }
            tempDir = dir
            hlsMode = true
            total = -1
            try {
                var playlistUrl = url
                var playlist = fetchText(playlistUrl, headers)
                if (playlist == null || playlist.trim().isEmpty()) {
                    throw IllegalStateException("HLS 播放列表为空")
                }
                if (playlist.contains("#EXT-X-STREAM-INF")) {
                    playlistUrl = pickBestVariantStatic(playlistUrl, playlist)
                    LogRecorder.record(Log.INFO, "BetterHeybox", "HLS 媒体播放列表: $playlistUrl")
                    playlist = fetchText(playlistUrl, headers)
                    if (playlist == null || playlist.trim().isEmpty()) {
                        throw IllegalStateException("HLS 媒体播放列表为空")
                    }
                }
                requireUnencrypted(playlist)
                val segments = parseSegmentUris(playlistUrl, playlist)
                if (segments.isEmpty()) {
                    throw IllegalStateException("HLS 无可用分片")
                }
                segmentsTotal = segments.size
                segmentsDone = 0
                downloaded = 0
                var bytes = 0L
                var tickAt = System.currentTimeMillis()
                var tickBytes = 0L
                for (i in 0 until segments.size) {
                    if (pauseRequested.get()) {
                        return -1L
                    }
                    if (cancelled.get()) {
                        throw IllegalStateException("cancelled")
                    }
                    val seg = File(dir, String.format(Locale.US, "seg_%05d", i))
                    if (resuming && seg.exists() && seg.length() > 0) {
                        bytes += seg.length()
                    } else {
                        bytes += downloadToFile(segments[i], seg)
                        if (bytes > MAX_FILE_SIZE) {
                            throw IllegalStateException("文件过大")
                        }
                    }
                    segmentsDone = i + 1
                    downloaded = bytes
                    val now = System.currentTimeMillis()
                    if (now - tickAt >= PROGRESS_INTERVAL_MS) {
                        updateSpeed(now, tickAt, tickBytes)
                        tickAt = now
                        tickBytes = bytes
                        notifyProgress()
                    }
                }
                val merged = File(
                    newTempDir(),
                    "m_" + Integer.toHexString(url.hashCode()) + ".tmp"
                )
                tempFile = merged
                FileOutputStream(merged).use { out ->
                    for (i in 0 until segments.size) {
                        if (pauseRequested.get()) {
                            return -1L
                        }
                        if (cancelled.get()) {
                            throw IllegalStateException("cancelled")
                        }
                        val seg = File(dir, String.format(Locale.US, "seg_%05d", i))
                        FileInputStream(seg).use { input ->
                            copyStream(input, out)
                        }
                    }
                }
                resolvedExt = "ts"
                deleteDir(dir)
                tempDir = null
                total = bytes
                speedBps = 0
                if (bytes <= 0) {
                    throw IllegalStateException("空文件")
                }
                return bytes
            } catch (t: Throwable) {
                if (pauseRequested.get() || cancelled.get()) {
                    return -1L
                }
                throw t
            }
        }

        private fun updateSpeed(now: Long, tickAt: Long, tickBytes: Long) {
            val dt = now - tickAt
            if (dt <= 0) {
                return
            }
            val inst = (downloaded - tickBytes) * 1000L / dt
            speedBps = if (speedBps == 0L) inst else (inst + speedBps * 3) / 4
        }

        private fun stableTempFile(): File {
            return File(newTempDir(), "v_" + Integer.toHexString(url.hashCode()) + ".tmp")
        }

        private fun stableHlsDir(): File {
            return File(newTempDir(), "hls_" + Integer.toHexString(url.hashCode()))
        }

        private fun finish(fetchedIn: Long) {
            var fetched = fetchedIn
            if (cancelled.get() || pauseRequested.get()) {
                return
            }
            if (fetched <= 0) {
                handleFailure(IllegalStateException("空文件"))
                return
            }
            val initialTmp = tempFile
            if (initialTmp == null || !initialTmp.exists()) {
                handleFailure(IllegalStateException("临时文件缺失"))
                return
            }
            var tmp: File = initialTmp
            val context = appContext
            if (context == null) {
                handleFailure(IllegalStateException("Context 未初始化"))
                return
            }
            var ext = resultExtension()
            if ("ts" == ext && HeyboxPrefs.getBoolean(App.KEY_VIDEO_TO_MP4, true)) {
                notifyProgress()
                val mp4 = remuxTsToMp4(tmp)
                if (cancelled.get() || pauseRequested.get()) {
                    return
                }
                if (mp4 != null && mp4.length() > 0) {
                    val oldTs = tmp
                    tmp = mp4
                    tempFile = tmp
                    ext = "mp4"
                    oldTs.delete()
                    fetched = mp4.length()
                }
            }
            var base = bestBaseName()
            if (base == null) {
                base = "heybox_video_" + System.currentTimeMillis()
            }
            val fileName = uniqueFileName(context, base, ext)
            val mime = mimeForExtension(ext)
            val saveTarget = HeyboxPrefs.getString(App.KEY_VIDEO_DIR, null)
            val uri: Uri?
            val path: String
            if (saveTarget != null && saveTarget.startsWith("content:")) {
                val sb = StringBuilder()
                uri = saveToTree(context, tmp, fileName, mime, saveTarget, sb)
                path = if (sb.length > 0) sb.toString() else fileName
            } else if (Build.VERSION.SDK_INT >= 29) {
                uri = saveToMediaStore(context, tmp, fileName, ext)
                path = "Movies/" + storageSubDir() + "/" + fileName
            } else {
                uri = saveToPublicMovies(context, tmp, fileName, ext)
                path = "Movies/" + storageSubDir() + "/" + fileName
            }
            if (cancelled.get() || pauseRequested.get()) {
                return
            }
            resultUri = uri
            savedPath = path
            total = fetched
            downloaded = fetched
            val finalName = fileName
            deleteTemp()
            transition(State.COMPLETED)
            mainHandler.post {
                notifyCompleted(finalName, uri, path)
            }
        }

        private fun resultExtension(): String {
            val ext = extensionFromUrl(url)
            return ext ?: (resolvedExt ?: "mp4")
        }

        private fun saveToMediaStore(
            context: Context,
            file: File,
            fileName: String,
            ext: String
        ): Uri? {
            try {
                val values = ContentValues()
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                values.put(MediaStore.Video.Media.MIME_TYPE, mimeForExtension(ext))
                values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + storageSubDir()
                )
                values.put(MediaStore.Video.Media.IS_PENDING, 1)
                val uri = context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri == null) {
                    return null
                }
                context.getContentResolver().openOutputStream(uri).use { os ->
                    FileInputStream(file).use { input ->
                        if (os == null) {
                            return null
                        }
                        copyStream(input, os)
                    }
                }
                val done = ContentValues()
                done.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.getContentResolver().update(uri, done, null, null)
                return uri
            } catch (t: Throwable) {
                moduleLogWarn("写入视频相册失败", t)
                return null
            }
        }

        private fun saveToTree(
            context: Context,
            file: File,
            fileName: String,
            mime: String,
            treeUriString: String,
            outPath: StringBuilder
        ): Uri? {
            try {
                val treeUri = Uri.parse(treeUriString)
                val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                val resolver = context.getContentResolver()
                var doc = createTreeDocument(resolver, parent, mime, fileName)
                if (doc == null) {
                    val dot = fileName.lastIndexOf('.')
                    val base = if (dot > 0) fileName.substring(0, dot) else fileName
                    val ext = if (dot > 0) fileName.substring(dot) else ""
                    doc = createTreeDocument(resolver, parent, mime, base + "(1)" + ext)
                }
                if (doc == null) {
                    return null
                }
                resolver.openOutputStream(doc).use { os ->
                    FileInputStream(file).use { input ->
                        if (os == null) {
                            return null
                        }
                        copyStream(input, os)
                    }
                }
                val docId = android.provider.DocumentsContract.getDocumentId(doc)
                val colon = docId.indexOf(':')
                val rel = if (colon >= 0) docId.substring(colon + 1) else docId
                outPath.append("/sdcard/").append(rel)
                MediaScannerConnection.scanFile(
                    context, arrayOf(outPath.toString()), arrayOf(mime), null
                )
                return doc
            } catch (t: Throwable) {
                moduleLogWarn("写入所选文件夹失败", t)
                return null
            }
        }

        private fun createTreeDocument(
            resolver: ContentResolver,
            parent: Uri,
            mime: String,
            name: String
        ): Uri? {
            try {
                return android.provider.DocumentsContract.createDocument(
                    resolver, parent, mime, name
                )
            } catch (t: Throwable) {
                return null
            }
        }

        private fun saveToPublicMovies(
            context: Context,
            file: File,
            fileName: String,
            ext: String
        ): Uri? {
            try {
                if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return null
                }
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    storageSubDir()
                )
                if (!dir.exists() && !dir.mkdirs()) {
                    return null
                }
                val target = File(dir, fileName)
                FileInputStream(file).use { input ->
                    FileOutputStream(target).use { os ->
                        copyStream(input, os)
                    }
                }
                MediaScannerConnection.scanFile(
                    context, arrayOf(target.getAbsolutePath()), null, null
                )
                return Uri.fromFile(target)
            } catch (t: Throwable) {
                moduleLogWarn("写入公共 Movies 目录失败", t)
                return null
            }
        }

        private fun uniqueFileName(context: Context, base: String, ext: String): String {
            val full = base + "." + ext
            if (!existsInMediaStore(context, full) &&
                !existsInPublicMovies(context, full)
            ) {
                return full
            }
            for (i in 1 until 1000) {
                val candidate = base + "(" + i + ")." + ext
                if (!existsInMediaStore(context, candidate) &&
                    !existsInPublicMovies(context, candidate)
                ) {
                    return candidate
                }
            }
            return base + "_" + System.currentTimeMillis() + "." + ext
        }

        private fun existsInMediaStore(context: Context, displayName: String): Boolean {
            try {
                val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
                val selection = MediaStore.Video.Media.DISPLAY_NAME + " = ?"
                context.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, arrayOf(displayName), null
                ).use { cursor ->
                    return cursor != null && cursor.moveToFirst()
                }
            } catch (t: Throwable) {
                return false
            }
        }

        private fun existsInPublicMovies(context: Context, displayName: String): Boolean {
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    storageSubDir()
                )
                return File(dir, displayName).exists()
            } catch (t: Throwable) {
                return false
            }
        }

        private fun remuxTsToMp4(tsFile: File): File? {
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            val out = File(
                tsFile.getParentFile(),
                "convert-" + System.currentTimeMillis() + ".mp4"
            )
            try {
                extractor.setDataSource(tsFile.getAbsolutePath())
                val trackCount = extractor.getTrackCount()
                var videoTracks = 0
                var maxSample = 1 shl 20
                for (i in 0 until trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME)
                    if (mime == null) {
                        return null
                    }
                    if (mime.startsWith("video/")) {
                        videoTracks++
                    }
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxSample = Math.max(maxSample, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
                if (videoTracks == 0) {
                    return null
                }
                muxer = MediaMuxer(
                    out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                val muxTracks = IntArray(trackCount)
                for (i in 0 until trackCount) {
                    muxTracks[i] = muxer.addTrack(extractor.getTrackFormat(i))
                }
                muxer.start()
                val buffer = java.nio.ByteBuffer.allocate(maxSample)
                val info = MediaCodec.BufferInfo()
                for (i in 0 until trackCount) {
                    extractor.selectTrack(i)
                }
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        break
                    }
                    val trackIndex = extractor.getSampleTrackIndex()
                    if (trackIndex < 0 || trackIndex >= trackCount) {
                        break
                    }
                    info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags())
                    muxer.writeSampleData(muxTracks[trackIndex], buffer, info)
                    extractor.advance()
                }
                muxer.stop()
                LogRecorder.record(
                    Log.INFO, "BetterHeybox",
                    "ts→mp4 转封装完成: " + out.getName()
                )
                return out
            } catch (t: Throwable) {
                moduleLogWarn("ts→mp4 转封装失败（保留 ts）", t)
                if (out.exists() && !out.delete()) {
                    out.deleteOnExit()
                }
                return null
            } finally {
                try {
                    extractor.release()
                } catch (ignored: Throwable) {
                }
                if (muxer != null) {
                    try {
                        muxer.release()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }

        private fun deleteTemp() {
            val tmp = tempFile
            if (tmp != null && tmp.exists() && !tmp.delete()) {
                tmp.deleteOnExit()
            }
            tempFile = null
            val dir = tempDir
            if (dir != null) {
                deleteDir(dir)
                tempDir = null
            }
        }

        private fun id(): Int {
            return NOTIF_BASE + (url.hashCode() and 0xFFFF)
        }

        private fun notifAllowed(): Boolean {
            val context = appContext
            if (context == null) {
                return false
            }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            try {
                val nm = context.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager?
                return nm != null && nm.areNotificationsEnabled()
            } catch (t: Throwable) {
                return false
            }
        }

        @JvmName("notifyProgress")
        internal fun notifyProgress() {
            notifyChanged()
            if (!notifAllowed()) {
                return
            }
            mainHandler.post {
                if (state != State.DOWNLOADING) {
                    return@post
                }
                val builder = buildBase("正在下载视频")
                    .setContentText(displayTitle() + " · " + statusLine())
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                val pct = percent()
                if (pct >= 0) {
                    builder.setProgress(100, pct, false)
                } else {
                    builder.setProgress(0, 0, true)
                }
                builder.addAction(0, "暂停", pausePendingIntent())
                builder.addAction(0, "取消", cancelPendingIntent())
                showNotification(id(), builder.build())
            }
        }

        private fun notifyCompleted(fileName: String, uri: Uri?, savedPath: String) {
            if (!notifAllowed()) {
                return
            }
            val builder = buildBase("下载完成")
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            builder.setContentText("已保存到 " + savedPath)
                .setStyle(Notification.BigTextStyle().bigText("已保存到 " + savedPath))
                .setAutoCancel(true)
            if (uri != null) {
                builder.setContentIntent(openPendingIntent(uri))
                    .addAction(0, "分享", sharePendingIntent(uri))
            }
            builder.addAction(0, "删除", deletePendingIntent(uri))
            showNotification(id(), builder.build())
        }

        private fun pausePendingIntent(): PendingIntent {
            val i = Intent(ACTION_PAUSE)
                .setPackage(context().getPackageName())
                .putExtra(EXTRA_URL, url)
            return PendingIntent.getBroadcast(
                context(), id() + 3, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun cancelPendingIntent(): PendingIntent {
            val i = Intent(ACTION_CANCEL)
                .setPackage(context().getPackageName())
                .putExtra(EXTRA_URL, url)
            return PendingIntent.getBroadcast(
                context(), id(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun retryPendingIntent(): PendingIntent {
            val i = Intent(ACTION_RETRY)
                .setPackage(context().getPackageName())
                .putExtra(EXTRA_URL, url)
            return PendingIntent.getBroadcast(
                context(), id(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openPendingIntent(uri: Uri): PendingIntent {
            return PendingIntent.getActivity(
                context(), id(), buildViewIntent(uri),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun sharePendingIntent(uri: Uri): PendingIntent {
            val chooser = Intent.createChooser(buildShareIntent(uri), "分享视频")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context(), id() + 1, chooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun deletePendingIntent(uri: Uri?): PendingIntent {
            val i = Intent(ACTION_DELETE)
                .setPackage(context().getPackageName())
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_URL, url)
            return PendingIntent.getBroadcast(
                context(), id() + 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun context(): Context {
            val c = appContext
            if (c == null) {
                throw IllegalStateException("VideoDownloadManager 未初始化")
            }
            return c
        }

        private fun requireUnencrypted(playlist: String) {
            for (line in playlist.split("\n")) {
                val l = line.trim()
                if ((l.startsWith("#EXT-X-KEY") || l.startsWith("#EXT-X-SESSION-KEY")) &&
                    !l.contains("METHOD=NONE")
                ) {
                    throw IllegalStateException("HLS 加密流，不支持下载")
                }
            }
        }

        @Throws(Exception::class)
        private fun downloadToFile(fileUrl: String, target: File): Long {
            val connection = openConnection(fileUrl, headers)
            try {
                val code = connection.getResponseCode()
                if (code < 200 || code >= 300) {
                    throw IllegalStateException("HTTP $code (segment)")
                }
                return connection.getInputStream().use { input ->
                    FileOutputStream(target).use { file ->
                        copyStream(input, file)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun ensureChannel(context: Context?) {
        try {
            val nm = context!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID_DOWNLOADS) != null) {
                return
            }
            val channel = NotificationChannel(
                CHANNEL_ID_DOWNLOADS, "视频下载",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setDescription("小黑盒视频下载进度与结果")
            channel.enableVibration(false)
            channel.enableLights(false)
            nm.createNotificationChannel(channel)
        } catch (ignored: Throwable) {
        }
    }

    private fun buildBase(title: String): Notification.Builder {
        val context = appContext
        ensureChannel(context)
        val builder = Notification.Builder(context, CHANNEL_ID_DOWNLOADS)
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
        builder.setContentTitle(title)
        return builder
    }

    private fun showNotification(id: Int, notification: Notification) {
        val context = appContext
        if (context == null) {
            return
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            if (nm != null) {
                nm.notify(id, notification)
            }
        } catch (t: Throwable) {
            moduleLogWarn("发出通知失败", t)
        }
    }

    private fun persistTasks() {
        try {
            val arr = JSONArray()
            synchronized(tasks) {
                for (t in tasks.values) {
                    val s = t.state
                    if (s == State.DOWNLOADING || s == State.PENDING) {
                        continue
                    }
                    val o = JSONObject()
                    o.put("url", t.url)
                    o.put("title", t.displayTitle())
                    o.put("state", s.name)
                    o.put("path", t.savedPath ?: "")
                    val resultUri = t.resultUri
                    o.put("uri", if (resultUri != null) resultUri.toString() else "")
                    o.put("total", t.total)
                    o.put("at", t.createdAt)
                    o.put("headers", JSONObject(t.headers))
                    arr.put(o)
                }
            }
            HeyboxPrefs.setString(KEY_TASK_HISTORY, arr.toString())
        } catch (t: Throwable) {
            moduleLogWarn("任务历史写入失败", t)
        }
    }

    private fun restoreHistory() {
        try {
            val raw = HeyboxPrefs.getString(KEY_TASK_HISTORY, null)
            if (raw == null || raw.isEmpty()) {
                return
            }
            val arr = JSONArray(raw)
            synchronized(tasks) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url: String? = o.optString("url", null)
                    if (url == null || tasks.containsKey(taskKey(url))) {
                        continue
                    }
                    val st = DownloadStateRules.coerceRestoredState(
                        o.optString("state", "PAUSED")
                    )
                    val headers = HashMap<String, String>()
                    val hs = o.optJSONObject("headers")
                    if (hs != null) {
                        val keys = hs.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            headers[k] = hs.optString(k)
                        }
                    } else {
                        val legacy = o.optJSONArray("headers")
                        if (legacy != null) {
                            for (j in 0 until legacy.length()) {
                                val pair = legacy.optString(j, "")
                                val sep = pair.indexOf('\u0001')
                                if (sep > 0) {
                                    headers[pair.substring(0, sep)] = pair.substring(sep + 1)
                                }
                            }
                        }
                    }
                    val t = DownloadTask(url, headers, null)
                    t.state = st
                    val savedPath = o.optString("path", null)
                    t.savedPath = savedPath
                    if (savedPath != null && savedPath.isEmpty()) {
                        t.savedPath = null
                    }
                    val uriStr: String? = o.optString("uri", null)
                    if (uriStr != null && !uriStr.isEmpty()) {
                        try {
                            t.resultUri = Uri.parse(uriStr)
                        } catch (ignored: Throwable) {
                        }
                    }
                    t.total = o.optLong("total", -1)
                    t.createdAt = o.optLong("at", System.currentTimeMillis())
                    tasks[t.key] = t
                }
            }
        } catch (t: Throwable) {
            moduleLogWarn("任务历史恢复失败", t)
        }
    }

    private fun newTempDir(): File {
        val context = appContext
        var dir: File? = if (context != null) context.getExternalCacheDir() else null
        if (dir == null) {
            dir = if (context != null) context.getCacheDir() else null
        }
        if (dir == null) {
            dir = File(System.getProperty("java.io.tmpdir"))
        }
        val sub = File(dir, "betterheybox-video")
        if (!sub.exists() && !sub.mkdirs()) {
            return dir
        }
        return sub
    }

    companion object {

        const val CHANNEL_ID_DOWNLOADS = "betterheybox_video_download"

        const val ACTION_CANCEL = "com.better.heybox.ACTION_CANCEL_DOWNLOAD"
        const val ACTION_PAUSE = "com.better.heybox.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_RETRY = "com.better.heybox.ACTION_RETRY_DOWNLOAD"
        const val ACTION_DELETE = "com.better.heybox.ACTION_DELETE_VIDEO"
        const val EXTRA_URL = "url"
        const val EXTRA_URI = "uri"

        private const val KEY_TASK_HISTORY = "video_task_history"

        private val INSTANCE = VideoDownloadManager()

        private const val NOTIF_BASE = 0x5644
        private const val BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 20000
        private const val MAX_AUTO_RETRY = 1
        private const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 500L

        @JvmStatic
        fun get(): VideoDownloadManager {
            return INSTANCE
        }

        @JvmStatic
        fun isSupportedUrl(url: String?): Boolean {
            if (url == null) {
                return false
            }
            val lower = url.lowercase(Locale.US)
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return false
            }
            if (isThirdPartyTrailer(lower)) {
                return false
            }
            if (hasWebExtension(lower)) {
                return false
            }
            return true
        }

        @JvmStatic
        private fun isThirdPartyTrailer(lower: String): Boolean {
            return lower.contains("steamstatic.com") ||
                lower.contains("steamusercontent.com") ||
                lower.contains("steampowered.com") ||
                lower.contains("store_trailers")
        }

        @JvmStatic
        private fun hasWebExtension(lower: String): Boolean {
            var path = lower
            try {
                val uri = java.net.URI.create(lower)
                if (uri.getPath() != null) {
                    path = uri.getPath()
                }
            } catch (ignored: Throwable) {
            }
            val lastDot = path.lastIndexOf('.')
            if (lastDot < 0 || lastDot >= path.length - 1) {
                return false
            }
            val ext = path.substring(lastDot + 1)
            return ext == "html" || ext == "htm" || ext == "php" ||
                ext == "jsp" || ext == "asp" || ext == "aspx" ||
                ext == "json" || ext == "xml" || ext == "txt" ||
                ext == "js" || ext == "css"
        }

        @JvmStatic
        private fun taskKey(url: String): String {
            try {
                val query = URL(url).getQuery()
                if (query != null) {
                    for (pair in query.split("&")) {
                        if (pair.startsWith("link_id=") && pair.length > 8) {
                            return "link:" + pair.substring(8)
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
            return url
        }

        @JvmStatic
        private fun isHlsUrl(url: String?): Boolean {
            return DownloadStateRules.isHlsUrl(url)
        }

        @JvmStatic
        private fun isVideoExtension(ext: String?): Boolean {
            return DownloadStateRules.isVideoExtension(ext)
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun openConnection(target: String, headers: Map<String, String>): HttpURLConnection {
            val connection = URL(target).openConnection() as HttpURLConnection
            connection.setConnectTimeout(CONNECT_TIMEOUT)
            connection.setReadTimeout(READ_TIMEOUT)
            connection.setInstanceFollowRedirects(true)
            val merged = HashMap(headers)
            if (!merged.containsKey("User-Agent") && !merged.containsKey("user-agent")) {
                merged["User-Agent"] = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 heybox"
            }
            if (!merged.containsKey("Referer") && !merged.containsKey("referer")) {
                merged["Referer"] = "https://api.xiaoheihe.cn/"
            }
            merged["Accept"] = "video/*,*/*;q=0.8"
            for (e in merged.entries) {
                if (e.key != null && e.value != null) {
                    connection.setRequestProperty(e.key, e.value)
                }
            }
            return connection
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun copyStream(input: InputStream, out: OutputStream): Long {
            val buffer = ByteArray(BUFFER_SIZE)
            var count = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                out.write(buffer, 0, read)
                count += read
            }
            return count
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun fetchText(textUrl: String, headers: Map<String, String>): String? {
            val connection = openConnection(textUrl, headers)
            try {
                val code = connection.getResponseCode()
                if (code < 200 || code >= 300) {
                    throw IllegalStateException("HTTP $code (playlist)")
                }
                return connection.getInputStream().use { input ->
                    val bos = java.io.ByteArrayOutputStream()
                    copyStream(input, bos)
                    String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                }
            } finally {
                connection.disconnect()
            }
        }

        @JvmStatic
        private fun pickBestVariantStatic(playlistUrl: String, playlist: String): String {
            var bestBw = -1L
            var bestUri: String? = null
            var pendingBw = -1L
            for (line in playlist.split("\n")) {
                val l = line.trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    pendingBw = parseBandwidth(l)
                } else if (!l.isEmpty() && !l.startsWith("#")) {
                    if (pendingBw > bestBw) {
                        bestBw = pendingBw
                        bestUri = l
                    }
                    pendingBw = -1L
                }
            }
            if (bestUri == null) {
                throw IllegalStateException("HLS master 无变体")
            }
            return absolutize(playlistUrl, bestUri)
        }

        @JvmStatic
        private fun parseBandwidth(line: String): Long {
            val idx = line.uppercase(Locale.US).indexOf("BANDWIDTH=")
            if (idx < 0) {
                return -1L
            }
            val sb = StringBuilder()
            for (i in (idx + "BANDWIDTH=".length) until line.length) {
                val c = line[i]
                if (c >= '0' && c <= '9') {
                    sb.append(c)
                } else if (sb.length > 0) {
                    break
                }
            }
            try {
                return java.lang.Long.parseLong(sb.toString())
            } catch (e: NumberFormatException) {
                return -1L
            }
        }

        @JvmStatic
        private fun absolutize(baseUrl: String, ref: String): String {
            try {
                return URL(URL(baseUrl), ref).toString()
            } catch (t: Throwable) {
                return ref
            }
        }

        @JvmStatic
        private fun parseSegmentUris(playlistUrl: String, playlist: String): List<String> {
            val segments = ArrayList<String>()
            for (line in playlist.split("\n")) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) {
                    continue
                }
                segments.add(absolutize(playlistUrl, l))
            }
            return segments
        }

        @JvmStatic
        private fun storageSubDir(): String {
            val raw = HeyboxPrefs.getString(App.KEY_VIDEO_DIR, null)
            val clean = cleanName(raw, null)
            if (clean == null || clean == "." || clean == "..") {
                return "BetterHeybox"
            }
            return clean
        }

        @JvmStatic
        private fun isGenericSegmentName(name: String?): Boolean {
            if (name == null) {
                return false
            }
            val lower = name.lowercase(Locale.US)
            return lower == "segs" || lower == "seg" || lower == "index" ||
                lower == "playlist" || lower == "master" ||
                lower == "chunklist" || lower == "video" ||
                lower == "hls" || lower == "main"
        }

        @JvmStatic
        private fun deleteDir(dir: File) {
            try {
                val children = dir.listFiles()
                if (children != null) {
                    for (c in children) {
                        if (c.isDirectory()) {
                            deleteDir(c)
                        } else {
                            if (!c.delete()) {
                                c.deleteOnExit()
                            }
                        }
                    }
                }
                if (!dir.delete()) {
                    dir.deleteOnExit()
                }
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        @JvmName("cleanName")
        internal fun cleanName(raw: String?, fallback: String?): String? {
            if (raw == null) {
                return fallback
            }
            var clean = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val sb = StringBuilder()
            for (i in 0 until clean.length) {
                val c = clean[i]
                sb.append(if (c.code < 0x20 || c.code == 0x7F) '_' else c)
            }
            clean = sb.toString().trim().replace(Regex("\\s+"), " ")
            if (clean.length > 80) {
                clean = clean.substring(0, 80).trim()
            }
            if (clean.isEmpty()) {
                return fallback
            }
            return clean
        }

        @JvmStatic
        @JvmName("nameFromUrl")
        internal fun nameFromUrl(url: String?): String? {
            try {
                val u = URL(url)
                val path: String? = u.getPath()
                if (path == null) {
                    return null
                }
                val idx = path.lastIndexOf('/')
                var name = if (idx >= 0) path.substring(idx + 1) else path
                if (name.isEmpty()) {
                    return null
                }
                val dot = name.lastIndexOf('.')
                if (dot > 0) {
                    name = name.substring(0, dot)
                }
                while (name.endsWith("_") || name.endsWith(".")) {
                    name = name.substring(0, name.length - 1)
                }
                return if (name.isEmpty()) null else name
            } catch (t: Throwable) {
                return null
            }
        }

        @JvmStatic
        private fun extensionFromUrl(url: String): String? {
            try {
                val path = URL(url).getPath()
                val dot = path.lastIndexOf('.')
                if (dot >= 0 && dot < path.length - 1) {
                    val ext = path.substring(dot + 1).lowercase(Locale.US)
                    if (isVideoExtension(ext)) {
                        return ext
                    }
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        @JvmStatic
        private fun extensionFromMimeType(mime: String?): String {
            if (mime == null) {
                return "mp4"
            }
            val lower = mime.lowercase(Locale.US)
            val slash = lower.indexOf('/')
            val semi = lower.indexOf(';')
            val subtype = if (semi >= 0) lower.substring(0, semi) else lower
            if (slash >= 0 && slash < subtype.length - 1) {
                val ext = subtype.substring(slash + 1).trim()
                if (ext == "mp2t") {
                    return "ts"
                }
                if (!ext.isEmpty() && ext.length <= 8 &&
                    Regex("[a-z0-9]+").matches(ext)
                ) {
                    return ext
                }
            }
            return "mp4"
        }

        @JvmStatic
        private fun mimeForExtension(ext: String?): String {
            if (ext == null) {
                return "video/mp4"
            }
            return when (ext) {
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "flv" -> "video/x-flv"
                "mov" -> "video/quicktime"
                "3gp" -> "video/3gpp"
                "ts" -> "video/mp2t"
                else -> "video/mp4"
            }
        }

        @JvmStatic
        fun formatSize(bytes: Long): String {
            if (bytes < 0) {
                return ""
            }
            if (bytes < 1024) {
                return "$bytes B"
            }
            if (bytes < 1024 * 1024) {
                return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            }
            if (bytes < 1024 * 1024 * 1024) {
                return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            }
            return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }

        @JvmStatic
        private fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) {
                return ""
            }
            if (bytesPerSec < 1024) {
                return "$bytesPerSec B/s"
            }
            if (bytesPerSec < 1024 * 1024) {
                return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            }
            return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }

        @JvmStatic
        private fun moduleLogWarn(msg: String, t: Throwable) {
            try {
                LogRecorder.record(Log.WARN, "BetterHeybox", "$msg: $t")
            } catch (ignored: Throwable) {
            }
        }
    }
}
