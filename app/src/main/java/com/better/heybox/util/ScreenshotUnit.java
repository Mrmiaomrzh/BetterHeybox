package com.better.heybox.util;

import android.view.View;

import com.better.heybox.hooks.FeedItemHider;

public final class ScreenshotUnit {

    public final View view;

    public final boolean comment;

    public final String idName;

    private ScreenshotUnit(View view, boolean comment, String idName) {
        this.view = view;
        this.comment = comment;
        this.idName = idName;
    }

    @Override
    public String toString() {
        return (comment ? "comment:" : "post:") + idName;
    }

    public static ScreenshotUnit postBody(View body) {
        return body == null ? null : new ScreenshotUnit(body, false, ScreenshotIds.POST_BODY);
    }

    public static ScreenshotUnit resolve(View touched) {
        ScreenshotUnit unit = resolveAncestors(touched);
        if (unit != null) {
            return unit;
        }
        if (touched == null) {
            return null;
        }

        int feedCardId = ScreenshotIds.id(touched.getContext(), ScreenshotIds.FEED_CARD);
        if (feedCardId != 0) {
            View inner = ScreenshotIds.findDescendantById(touched, feedCardId);
            if (inner != null) {
                return new ScreenshotUnit(inner, false, ScreenshotIds.FEED_CARD);
            }
        }
        return null;
    }

    public static ScreenshotUnit resolveAncestors(View touched) {
        if (touched == null) {
            return null;
        }

        View card = ScreenshotIds.findAncestorByName(touched, ScreenshotIds.FEED_CARD);
        if (card != null) {
            return new ScreenshotUnit(card, false, ScreenshotIds.FEED_CARD);
        }

        View body = ScreenshotIds.findAncestorByName(touched, ScreenshotIds.POST_BODY);
        if (body != null) {
            return new ScreenshotUnit(body, false, ScreenshotIds.POST_BODY);
        }

        View block = ScreenshotIds.findAncestorByName(touched, ScreenshotIds.COMMENT_BLOCK);
        if (block != null) {
            View subList = ScreenshotIds.findAncestorByName(touched, ScreenshotIds.SUB_COMMENT_LIST);
            if (subList != null) {
                View sub = ScreenshotIds.directChildUnder(touched, subList);
                if (sub != null) {
                    return new ScreenshotUnit(sub, true, ScreenshotIds.SUB_COMMENT_LIST);
                }
            }
            View top = FeedItemHider.topLevel(block);
            if (top != null) {
                return new ScreenshotUnit(top, true, ScreenshotIds.COMMENT_BLOCK);
            }
            return new ScreenshotUnit(block, true, ScreenshotIds.COMMENT_BLOCK);
        }

        return null;
    }
}
