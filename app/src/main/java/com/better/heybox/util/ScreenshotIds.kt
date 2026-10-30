package com.better.heybox.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import com.better.heybox.MainModule
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object ScreenshotIds {

    const val POST_BODY = "post_content_container"

    const val FEED_CARD = "epoxy_model_group_child_container"

    const val COMMENT_BLOCK = "vg_comments_detail"

    const val COMMENT_TEXT = "tv_comment"

    const val SUB_COMMENT_LIST = "rv_sub_comments"

    const val SCROLL_CONTAINER = "vg_scroll_container"

    const val USER_SECTION = "v_user_section"

    const val POST_TITLE = "tv_title"
    const val POST_TEXT = "tv_content"

    val TAGS = arrayOf(
        "vg_hashtag",
        "bbs_user_level",
        "bbs_medal",
        "vg_bottom_bar",
    )

    val TIMESTAMPS = arrayOf(
        "tv_time",
    )

    val ACTIONS = arrayOf(
        "iv_link_more",
    )

    val IRRELEVANT = arrayOf(
        "tv_relevant_search",
        "rv_relevant_search",
        "iv_relevant_search_icon",
        "story_relevant_search",
        "tv_game_comment_relevant_search",
        "vg_relevant_search",
        "line_relevant_search",
        "rv_search_query",
        "relevant_search_divider",
        "rv_search_recommend_v2",
        "vg_related_topics",
        "ll_related_topics",
        "v_related_topics_divider",
        "tv_related_name",
        "tv_related_desc",
        "tv_related_videos_title",
        "vg_related_events",
        "vg_related_moments",
        "rv_recommend_post",
        "vg_recommend_post_v2",
        "tv_recommend_title_w500",
        "tv_recommend_post_title",
        "tv_recommend_post_desc",
        "divider_recommend",
        "rv_post_guide",
        "vp_hor_mini_program",
        "rv_mini_program_v3",
        "ll_article_collection",
        "rv_event_entry",
    )

    val IRRELEVANT_TITLES = arrayOf(
        "相关搜索",
        "都在搜",
        "大家还在搜",
        "相似搜索",
        "相关推荐",
        "相关内容",
        "相关话题",
        "相关视频",
        "相关活动",
        "相关动态",
        "猜你喜欢",
        "大家都在看",
        "推荐阅读",
    )

    val IMAGE_COUNT = arrayOf(
        "tv_img_cnt",
        "tv_img_cnt_top_right",
        "tv_img_cnt_bottom_right",
        "tv_index",
        "tv_image_count",
        "tv_pic_num",
        "story_picture_indicator",
        "tv_folder_img_count",
    )

    val CLOSE_ICONS = arrayOf(
        "iv_close",
        "iv_info_close",
        "iv_window_close",
        "iv_banner_close",
        "iv_bg_close",
        "iv_close_rec",
        "iv_dialog_close",
        "iv_menu_close",
        "iv_dismiss_message",
        "iv_cancel",
        "comment_close",
        "bubble_close",
        "group_close",
        "iv_recommend_topic_close",
        "iv_recommend_friends_close",
        "iv_edit_comment_notify_close",
        "iv_game_coupon_tips_close",
        "iv_synchronized_notice_close",
        "iv_little_program_exit",
        "iv_game_web_exit",
    )

    val FOLLOW = arrayOf(
        "v_follow_btn",
        "tv_follow",
        "ll_follow",
        "iv_follow_icon",
        "iv_follow_state",
        "iv_follow_status",
        "subscribe_button",
        "ll_subscribe_button",
    )

    val LIKE_COUNT = arrayOf(
        "vg_like",
        "comment_like_count",
        "comment_like_icon",
        "tv_like",
        "tv_like_and_collect",
        "tv_thumbs_up",
        "vg_thumbs_up",
        "vg_thumb",
        "iv_thumbs_up",
        "iv_like",
        "cb_like",
        "tv_interactive_like",
        "vg_interactive_like",
    )

    val VERIFY = arrayOf(
        POST_BODY, FEED_CARD, COMMENT_BLOCK, COMMENT_TEXT, SUB_COMMENT_LIST,
        SCROLL_CONTAINER, USER_SECTION, POST_TITLE, POST_TEXT,
        "vg_hashtag", "bbs_user_level", "bbs_medal", "vg_bottom_bar", "tv_time",
        "vg_like", "iv_link_more",
        "tv_relevant_search", "rv_relevant_search", "iv_relevant_search_icon",
        "story_relevant_search", "tv_game_comment_relevant_search",
        "vg_relevant_search", "line_relevant_search", "rv_search_query",
        "relevant_search_divider", "rv_search_recommend_v2",
        "vg_related_topics", "ll_related_topics", "v_related_topics_divider",
        "tv_related_name", "tv_related_desc", "tv_related_videos_title",
        "vg_related_events", "vg_related_moments",
        "rv_recommend_post", "vg_recommend_post_v2", "tv_recommend_title_w500",
        "divider_recommend",
        "rv_post_guide", "vp_hor_mini_program",
        "rv_mini_program_v3", "ll_article_collection", "rv_event_entry",
        "iv_close", "iv_info_close", "iv_window_close", "iv_close_rec",
        "iv_cancel", "iv_dismiss_message", "comment_close", "group_close",
        "v_follow_btn", "tv_follow", "ll_follow", "iv_follow_icon", "iv_follow_state",
        "comment_like_count", "comment_like_icon", "tv_like", "tv_thumbs_up",
        "vg_thumb", "iv_thumbs_up", "iv_like", "cb_like",
        "tv_image_count", "tv_pic_num", "story_picture_indicator",
        "tv_img_cnt", "tv_img_cnt_top_right", "tv_img_cnt_bottom_right", "tv_index",
    )

    private val ID_CACHE = ConcurrentHashMap<String, Int>()

    @JvmStatic
    fun id(context: Context?, name: String?): Int {
        if (context == null || name == null) {
            return 0
        }
        val cached = ID_CACHE[name]
        if (cached != null) {
            return cached
        }
        var value = 0
        try {
            val res = context.resources
            value = res.getIdentifier(name, "id", MainModule.TARGET_PKG)
            if (value == 0) {
                value = res.getIdentifier(name, "id", context.packageName)
            }
        } catch (ignored: Throwable) {
        }
        ID_CACHE[name] = value
        return value
    }

    @JvmStatic
    fun ids(context: Context?, names: Array<String>?): IntArray {
        if (names == null) {
            return IntArray(0)
        }
        val out = IntArray(names.size)
        var count = 0
        for (name in names) {
            val value = id(context, name)
            if (value != 0) {
                out[count++] = value
            }
        }
        val trimmed = IntArray(count)
        System.arraycopy(out, 0, trimmed, 0, count)
        return trimmed
    }

    @JvmStatic
    fun entryName(view: View?): String? {
        if (view == null) {
            return null
        }
        return try {
            val viewId = view.id
            if (viewId == View.NO_ID) {
                return null
            }
            view.resources.getResourceEntryName(viewId)
        } catch (ignored: Throwable) {
            null
        }
    }

    @JvmStatic
    fun findAncestor(view: View?, viewId: Int): View? {
        if (view == null || viewId == 0) {
            return null
        }
        var current: View? = view
        var depth = 0
        while (current != null && depth < 24) {
            if (current.id == viewId) {
                return current
            }
            val parent: ViewParent? = current.parent
            current = parent as? View
            depth++
        }
        return null
    }

    @JvmStatic
    fun findAncestorByName(view: View?, name: String): View? {
        if (view == null) {
            return null
        }
        return findAncestor(view, id(view.context, name))
    }

    @JvmStatic
    fun findDescendantById(root: View?, viewId: Int): View? {
        if (root == null || viewId == 0) {
            return null
        }
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (!queue.isEmpty() && visited++ < 4000) {
            val current = queue.poll()
            if (current !== root && current.id == viewId) {
                return current
            }
            if (current is ViewGroup) {
                val group = current
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
        return null
    }

    @JvmStatic
    fun directChildUnder(touched: View?, container: View?): View? {
        if (touched == null || container == null) {
            return null
        }
        var current: View? = touched
        var depth = 0
        while (current != null && depth < 24) {
            val parent: ViewParent? = current.parent
            if (parent === container) {
                return current
            }
            current = parent as? View
            depth++
        }
        return null
    }
}
