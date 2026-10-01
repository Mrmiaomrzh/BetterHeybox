package com.better.heybox.util;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.better.heybox.MainModule;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScreenshotIds {

    public static final String POST_BODY = "post_content_container";

    public static final String FEED_CARD = "epoxy_model_group_child_container";

    public static final String COMMENT_BLOCK = "vg_comments_detail";

    public static final String COMMENT_TEXT = "tv_comment";

    public static final String SUB_COMMENT_LIST = "rv_sub_comments";

    public static final String SCROLL_CONTAINER = "vg_scroll_container";

    public static final String USER_SECTION = "v_user_section";

    public static final String POST_TITLE = "tv_title";
    public static final String POST_TEXT = "tv_content";

    public static final String[] TAGS = {
            "vg_hashtag",
            "bbs_user_level",
            "bbs_medal",
            "vg_bottom_bar",
    };

    public static final String[] TIMESTAMPS = {
            "tv_time",
    };

    public static final String[] ACTIONS = {
            "iv_link_more",
    };

    public static final String[] IRRELEVANT = {
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
    };

    public static final String[] IRRELEVANT_TITLES = {
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
    };

    public static final String[] IMAGE_COUNT = {
            "tv_img_cnt",
            "tv_img_cnt_top_right",
            "tv_img_cnt_bottom_right",
            "tv_index",
            "tv_image_count",
            "tv_pic_num",
            "story_picture_indicator",
            "tv_folder_img_count",
    };

    public static final String[] CLOSE_ICONS = {
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
    };

    public static final String[] FOLLOW = {
            "v_follow_btn",
            "tv_follow",
            "ll_follow",
            "iv_follow_icon",
            "iv_follow_state",
            "iv_follow_status",
            "subscribe_button",
            "ll_subscribe_button",
    };

    public static final String[] LIKE_COUNT = {
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
    };

    public static final String[] VERIFY = {
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
    };

    private static final Map<String, Integer> ID_CACHE = new ConcurrentHashMap<>();

    private ScreenshotIds() {
    }

    public static int id(Context context, String name) {
        if (context == null || name == null) {
            return 0;
        }
        Integer cached = ID_CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        int value = 0;
        try {
            Resources res = context.getResources();
            value = res.getIdentifier(name, "id", MainModule.TARGET_PKG);
            if (value == 0) {
                value = res.getIdentifier(name, "id", context.getPackageName());
            }
        } catch (Throwable ignored) {

        }
        ID_CACHE.put(name, value);
        return value;
    }

    public static int[] ids(Context context, String[] names) {
        if (names == null) {
            return new int[0];
        }
        int[] out = new int[names.length];
        int count = 0;
        for (String name : names) {
            int value = id(context, name);
            if (value != 0) {
                out[count++] = value;
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    public static String entryName(View view) {
        if (view == null) {
            return null;
        }
        try {
            int viewId = view.getId();
            if (viewId == View.NO_ID) {
                return null;
            }
            return view.getResources().getResourceEntryName(viewId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static View findAncestor(View view, int viewId) {
        if (view == null || viewId == 0) {
            return null;
        }
        View current = view;
        for (int depth = 0; current != null && depth < 24; depth++) {
            if (current.getId() == viewId) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    public static View findAncestorByName(View view, String name) {
        if (view == null) {
            return null;
        }
        return findAncestor(view, id(view.getContext(), name));
    }

    public static View findDescendantById(View root, int viewId) {
        if (root == null || viewId == 0) {
            return null;
        }
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 4000) {
            View current = queue.poll();
            if (current != root && current.getId() == viewId) {
                return current;
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return null;
    }

    public static View directChildUnder(View touched, View container) {
        if (touched == null || container == null) {
            return null;
        }
        View current = touched;
        for (int depth = 0; current != null && depth < 24; depth++) {
            ViewParent parent = current.getParent();
            if (parent == container) {
                return current;
            }
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }
}
