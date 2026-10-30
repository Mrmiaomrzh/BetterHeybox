package com.better.heybox

import com.better.heybox.VideoDownloadManager.State
import java.util.Locale

internal object DownloadStateRules {

    @JvmStatic
    fun isTerminal(s: State?): Boolean =
        s == State.COMPLETED || s == State.FAILED || s == State.CANCELLED

    @JvmStatic
    fun allowsTransition(current: State?, next: State?): Boolean =
        !(current == State.CANCELLED && next != State.CANCELLED)

    @JvmStatic
    fun canPause(s: State?): Boolean = s == State.DOWNLOADING || s == State.PENDING

    @JvmStatic
    fun canResume(s: State?): Boolean =
        s == State.PAUSED || s == State.FAILED || s == State.CANCELLED || s == State.PENDING

    @JvmStatic
    fun coerceRestoredState(name: String?): State {
        val s = try {
            State.valueOf(name ?: "")
        } catch (bad: Throwable) {
            return State.PAUSED
        }
        if (s == State.DOWNLOADING || s == State.PENDING) {
            return State.PAUSED
        }
        return s
    }

    @JvmStatic
    fun isHlsUrl(url: String?): Boolean =
        url != null && url.lowercase(Locale.US).contains(".m3u8")

    @JvmStatic
    fun isVideoExtension(ext: String?): Boolean = when (ext) {
        "mp4", "mov", "m4v", "mkv", "webm", "avi", "flv", "3gp", "rmvb", "wmv", "ts" -> true
        else -> false
    }
}
