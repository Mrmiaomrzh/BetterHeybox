package com.better.heybox;

import com.better.heybox.VideoDownloadManager.State;

final class DownloadStateRules {

    private DownloadStateRules() {
    }

    static boolean isTerminal(State s) {
        return s == State.COMPLETED || s == State.FAILED || s == State.CANCELLED;
    }

    static boolean allowsTransition(State current, State next) {
        return !(current == State.CANCELLED && next != State.CANCELLED);
    }

    static boolean canPause(State s) {
        return s == State.DOWNLOADING || s == State.PENDING;
    }

    static boolean canResume(State s) {
        return s == State.PAUSED || s == State.FAILED || s == State.CANCELLED || s == State.PENDING;
    }

    static State coerceRestoredState(String name) {
        State s;
        try {
            s = State.valueOf(name == null ? "" : name);
        } catch (Throwable bad) {
            return State.PAUSED;
        }
        if (s == State.DOWNLOADING || s == State.PENDING) {
            return State.PAUSED;
        }
        return s;
    }

    static boolean isHlsUrl(String url) {
        return url != null && url.toLowerCase(java.util.Locale.US).contains(".m3u8");
    }

    static boolean isVideoExtension(String ext) {
        if (ext == null) {
            return false;
        }
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
}
