package com.better.heybox.util

import android.view.View
import com.better.heybox.hooks.FeedItemHider

class ScreenshotUnit private constructor(
    val view: View,
    val comment: Boolean,
    val idName: String
) {

    override fun toString(): String =
        (if (comment) "comment:" else "post:") + idName

    companion object {

        @JvmStatic
        fun postBody(body: View?): ScreenshotUnit? =
            if (body == null) null else ScreenshotUnit(body, false, ScreenshotIds.POST_BODY)

        @JvmStatic
        fun resolve(touched: View?): ScreenshotUnit? {
            val unit = resolveAncestors(touched)
            if (unit != null) {
                return unit
            }
            if (touched == null) {
                return null
            }

            val feedCardId = ScreenshotIds.id(touched.context, ScreenshotIds.FEED_CARD)
            if (feedCardId != 0) {
                val inner = ScreenshotIds.findDescendantById(touched, feedCardId)
                if (inner != null) {
                    return ScreenshotUnit(inner, false, ScreenshotIds.FEED_CARD)
                }
            }
            return null
        }

        @JvmStatic
        fun resolveAncestors(touched: View?): ScreenshotUnit? {
            if (touched == null) {
                return null
            }

            val card = ScreenshotIds.findAncestorByName(touched, ScreenshotIds.FEED_CARD)
            if (card != null) {
                return ScreenshotUnit(card, false, ScreenshotIds.FEED_CARD)
            }

            val body = ScreenshotIds.findAncestorByName(touched, ScreenshotIds.POST_BODY)
            if (body != null) {
                return ScreenshotUnit(body, false, ScreenshotIds.POST_BODY)
            }

            val block = ScreenshotIds.findAncestorByName(touched, ScreenshotIds.COMMENT_BLOCK)
            if (block != null) {
                val subList = ScreenshotIds.findAncestorByName(touched, ScreenshotIds.SUB_COMMENT_LIST)
                if (subList != null) {
                    val sub = ScreenshotIds.directChildUnder(touched, subList)
                    if (sub != null) {
                        return ScreenshotUnit(sub, true, ScreenshotIds.SUB_COMMENT_LIST)
                    }
                }
                val top = FeedItemHider.topLevel(block)
                if (top != null) {
                    return ScreenshotUnit(top, true, ScreenshotIds.COMMENT_BLOCK)
                }
                return ScreenshotUnit(block, true, ScreenshotIds.COMMENT_BLOCK)
            }

            return null
        }
    }
}
