package com.better.heybox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.better.heybox.VideoDownloadManager.State;

import org.junit.Test;

public class DownloadStateRulesTest {


    @Test
    public void terminalStatesAreCompletedFailedCancelled() {
        assertTrue(DownloadStateRules.isTerminal(State.COMPLETED));
        assertTrue(DownloadStateRules.isTerminal(State.FAILED));
        assertTrue(DownloadStateRules.isTerminal(State.CANCELLED));
    }

    @Test
    public void transientStatesAreNotTerminal() {
        assertFalse(DownloadStateRules.isTerminal(State.PENDING));
        assertFalse(DownloadStateRules.isTerminal(State.DOWNLOADING));
        assertFalse(DownloadStateRules.isTerminal(State.PAUSED));
    }


    @Test
    public void cancelledTaskRejectsEveryRewrite() {
        for (State next : State.values()) {
            if (next == State.CANCELLED) {
                continue;
            }
            assertFalse("已取消的任务不得被改写为 " + next,
                    DownloadStateRules.allowsTransition(State.CANCELLED, next));
        }
    }

    @Test
    public void cancelledToCancelledIsIdempotent() {
        assertTrue(DownloadStateRules.allowsTransition(State.CANCELLED, State.CANCELLED));
    }

    @Test
    public void otherStatesAcceptEveryTransition() {
        for (State from : State.values()) {
            if (from == State.CANCELLED) {
                continue;
            }
            for (State to : State.values()) {
                assertTrue(from + " -> " + to + " 应被允许",
                        DownloadStateRules.allowsTransition(from, to));
            }
        }
    }


    @Test
    public void pauseOnlyAllowedWhileRunningOrNotStarted() {
        assertTrue(DownloadStateRules.canPause(State.DOWNLOADING));
        assertTrue(DownloadStateRules.canPause(State.PENDING));
        assertFalse(DownloadStateRules.canPause(State.PAUSED));
        assertFalse(DownloadStateRules.canPause(State.COMPLETED));
        assertFalse(DownloadStateRules.canPause(State.FAILED));
        assertFalse(DownloadStateRules.canPause(State.CANCELLED));
    }


    @Test
    public void resumeAllowedForInterruptedStates() {
        assertTrue(DownloadStateRules.canResume(State.PAUSED));
        assertTrue(DownloadStateRules.canResume(State.FAILED));
        assertTrue(DownloadStateRules.canResume(State.CANCELLED));
        assertTrue(DownloadStateRules.canResume(State.PENDING));
    }

    @Test
    public void resumeRejectedWhileRunningOrFinished() {
        assertFalse(DownloadStateRules.canResume(State.DOWNLOADING));
        assertFalse(DownloadStateRules.canResume(State.COMPLETED));
    }

    @Test
    public void cancelledCanBeResumedByUserAction() {
        assertTrue(DownloadStateRules.canResume(State.CANCELLED));
        assertFalse(DownloadStateRules.allowsTransition(State.CANCELLED, State.COMPLETED));
    }


    @Test
    public void restoredTransientStatesDegradeToPaused() {
        assertEquals(State.PAUSED, DownloadStateRules.coerceRestoredState("DOWNLOADING"));
        assertEquals(State.PAUSED, DownloadStateRules.coerceRestoredState("PENDING"));
    }

    @Test
    public void restoredSettledStatesArePreserved() {
        assertEquals(State.COMPLETED, DownloadStateRules.coerceRestoredState("COMPLETED"));
        assertEquals(State.FAILED, DownloadStateRules.coerceRestoredState("FAILED"));
        assertEquals(State.CANCELLED, DownloadStateRules.coerceRestoredState("CANCELLED"));
        assertEquals(State.PAUSED, DownloadStateRules.coerceRestoredState("PAUSED"));
    }

    @Test
    public void restoredUnknownNameDegradesToPaused() {
        assertEquals(State.PAUSED, DownloadStateRules.coerceRestoredState("NOPE"));
        assertEquals(State.PAUSED, DownloadStateRules.coerceRestoredState(""));
        assertEquals(State.PAUSED, DownloadStateRules.coerceRestoredState(null));
    }

    @Test
    public void restoredGarbageNeverBecomesTerminal() {
        String[] garbage = {"completed", "DONE", "下载中", "0", "1"};
        for (String g : garbage) {
            assertFalse("归档名 " + g + " 竟被还原成终态",
                    DownloadStateRules.isTerminal(DownloadStateRules.coerceRestoredState(g)));
        }
    }


    @Test
    public void hlsUrlDetectedCaseInsensitively() {
        assertTrue(DownloadStateRules.isHlsUrl("https://a.com/x.m3u8"));
        assertTrue(DownloadStateRules.isHlsUrl("https://A.COM/X.M3U8"));
        assertFalse(DownloadStateRules.isHlsUrl("https://a.com/x.mp4"));
        assertFalse(DownloadStateRules.isHlsUrl(null));
    }

    @Test
    public void videoExtensionsAccepted() {
        String[] ok = {"mp4", "mov", "m4v", "mkv", "webm", "avi", "flv", "3gp", "rmvb", "wmv", "ts"};
        for (String e : ok) {
            assertTrue(e, DownloadStateRules.isVideoExtension(e));
        }
    }

    @Test
    public void nonVideoExtensionsRejected() {
        String[] bad = {"m3u8", "html", "json", "", "MP4"};
        for (String e : bad) {
            assertFalse(e, DownloadStateRules.isVideoExtension(e));
        }
    }
}
