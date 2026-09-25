package com.better.heybox.watch;

import android.util.Log;

import com.better.heybox.MainModule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WatchFetcher {

    private static final String BASE = "https://api.xiaoheihe.cn/";

    public static final int FETCH_LIMIT = 10;
    public static final int FOLLOW_IMPORT_LIMIT = 100;

    private static final String[] USER_POSTS_PATHS = {
            "bbs/app/profile/user/link/list",
            "bbs/app/profile/award/link",
    };
    private static final String[] FOLLOWING_PATHS = {
            "bbs/app/profile/following/list",
            "bbs/app/profile/following/simple_list",
            "bbs/app/profile/inter_follow/list",
            "bbs/app/profile/follower/list",
    };
    private static final String[] TOPIC_LIST_PATHS = {
            "bbs/app/profile/preference_v5/topic_list",
            "bbs/app/profile/topic/settings",
    };
    private static final String TOPIC_INFO_PATH = "bbs/app/topic/list_infos";
    private static final String[] TOPIC_SEARCH_PATHS = {
            "bbs/app/api/search/topic",
            "bbs/app/topic/search",
            "bbs/app/hashtag/search",
    };
    private static final String[] TOPIC_FEED_PATHS = {
            "bbs/app/topic/feeds",
            "bbs/app/topic/max/feeds",
            "bbs/app/hashtag/concept/feeds",
    };
    private static final String[] SEARCH_PATHS = {
            "bbs/app/api/general/search/v1",
            "bbs/app/hashtag/search",
            "bbs/app/topic/search",
    };
    private static final String[] HOT_WORD_PATHS = {
            "bbs/app/api/search/hot_words",
    };
    private static final String[] SUGGEST_PATHS = {
            "bbs/app/api/search/suggestion/v2",
    };

    private static volatile MainModule sModule;

    private WatchFetcher() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }


    public static String userPostsUrl(String userId, int limit) {
        return BASE + USER_POSTS_PATHS[0] + "?userid=" + userId + "&offset=0&limit=" + limit;
    }

    public static List<WatchItem> fetchUserPosts(String userId, int limit) {
        List<WatchItem> out = new ArrayList<>();
        if (userId == null || userId.isEmpty()) {
            return out;
        }
        Map<String, String> headers = baseHeaders();
        String body = null;
        for (String path : USER_POSTS_PATHS) {
            body = HttpBridge.get(BASE + path + "?userid=" + userId + "&offset=0&limit=" + limit, headers);
            if (body != null && !body.isEmpty() && body.contains("link")) {
                break;
            }
        }
        if (body == null || body.isEmpty()) {
            log(Log.WARN, "user_posts 无响应 userid=" + userId);
            return out;
        }
        try {
            collect(new JSONObject(body), out, 0);
        } catch (Throwable t) {
            log(Log.WARN, "user_posts 解析失败 userid=" + userId + " : " + t);
        }
        log(Log.INFO, "user_posts userid=" + userId + " 解析出 " + out.size() + " 条");
        return out;
    }


    public static List<String[]> fetchFollowing(int limit) {
        List<String[]> out = new ArrayList<>();
        Map<String, String> headers = baseHeaders();
        String body = null;
        String usedPath = null;
        for (String path : FOLLOWING_PATHS) {
            body = HttpBridge.get(BASE + path + "?offset=0&limit=50", headers);
            if (body != null && body.length() > 20) {
                usedPath = path;
                break;
            }
        }
        if (body == null || body.isEmpty()) {
            log(Log.WARN, "关注列表无响应（可能未登录或端点变化）");
            return out;
        }
        try {
            List<JSONObject> users = new ArrayList<>();
            collectUserObjects(new JSONObject(body), users, 0);
            Map<String, String> seen = new LinkedHashMap<>();
            for (JSONObject u : users) {
                String id = str(u, "userid", "user_id", "heybox_id");
                if (id == null || id.isEmpty() || "0".equals(id)) {
                    continue;
                }
                String name = str(u, "username", "nickname", "user_name", "name");
                if (!seen.containsKey(id)) {
                    seen.put(id, name == null ? "" : name);
                }
                if (seen.size() >= limit) {
                    break;
                }
            }
            for (Map.Entry<String, String> e : seen.entrySet()) {
                out.add(new String[]{e.getKey(), e.getValue()});
            }
            log(Log.INFO, "关注列表导入：" + usedPath + " 解析出 " + out.size() + " 个用户");
        } catch (Throwable t) {
            log(Log.WARN, "关注列表解析失败: " + t);
        }
        return out;
    }


    public static List<String[]> fetchFollowedTopics(int limit) {
        Map<String, String> headers = baseHeaders();
        String body = HttpBridge.get(BASE + TOPIC_LIST_PATHS[0] + "?offset=0&limit=50", headers);
        List<String[]> out = new ArrayList<>();
        if (body != null && body.length() > 20) {
            out.addAll(parseTopicPairs(body, limit));
            if (out.isEmpty()) {
                logBody("关注话题 " + TOPIC_LIST_PATHS[0], body);
            }
        }
        if (out.isEmpty()) {
            out.addAll(fetchRecentTopics(limit));
        }
        if (out.isEmpty()) {
            log(Log.WARN, "没有可导入的话题：关注话题接口不可用，最近也没浏览过话题");
        } else {
            log(Log.INFO, "可导入话题共 " + out.size() + " 个");
        }
        return out;
    }

    public static List<String[]> fetchRecentTopics(int limit) {
        List<String> ids = HttpBridge.recentTopicIds();
        if (ids.isEmpty()) {
            log(Log.INFO, "最近浏览话题为空（在小黑盒里点开任意话题页即可记录）");
            return new ArrayList<>();
        }
        return fetchTopicInfos(ids, limit);
    }

    public static List<String[]> fetchTopicInfos(List<String> ids, int limit) {
        List<String[]> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        int end = Math.min(ids.size(), Math.max(1, limit));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        String body = HttpBridge.get(BASE + TOPIC_INFO_PATH + "?topic_ids=" + sb, baseHeaders());
        if (body == null || body.isEmpty()) {
            log(Log.WARN, "话题名解析无响应");
            return out;
        }
        try {
            List<JSONObject> objs = new ArrayList<>();
            collectTopicObjects(new JSONObject(body), objs, 0);
            Map<String, String> seen = new LinkedHashMap<>();
            for (JSONObject o : objs) {
                String name = str(o, "name", "topic_name", "topicName", "title");
                String id = str(o, "topic_id", "topicId", "id");
                if (name == null || name.trim().isEmpty() || id == null) {
                    continue;
                }
                if (!seen.containsKey(id)) {
                    seen.put(id, name.trim());
                }
                if (seen.size() >= limit) {
                    break;
                }
            }
            for (Map.Entry<String, String> e : seen.entrySet()) {
                out.add(new String[]{e.getKey(), e.getValue()});
            }
            log(Log.INFO, "话题名解析出 " + out.size() + " 个");
        } catch (Throwable t) {
            log(Log.WARN, "话题名解析异常: " + t);
        }
        return out;
    }

    public static List<String[]> fetchTopicSearch(String keyword, int limit) {
        List<String[]> out = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return out;
        }
        String q = java.net.URLEncoder.encode(keyword.trim());
        for (String path : TOPIC_SEARCH_PATHS) {
            String body = HttpBridge.get(BASE + path + "?q=" + q + "&keyword=" + q
                    + "&offset=0&limit=" + limit, baseHeaders());
            if (body != null && body.length() > 20) {
                out.addAll(parseTopicPairs(body, limit));
            }
            if (!out.isEmpty()) {
                log(Log.INFO, "话题搜索 " + path + " [" + keyword + "] → " + out.size() + " 个");
                return out;
            }
            sleepQuiet(300);
        }
        log(Log.INFO, "话题搜索无结果 [" + keyword + "]");
        return out;
    }

    private static List<String[]> parseTopicPairs(String body, int limit) {
        List<String[]> out = new ArrayList<>();
        try {
            List<JSONObject> objs = new ArrayList<>();
            collectTopicObjects(new JSONObject(body), objs, 0);
            Map<String, String> seen = new LinkedHashMap<>();
            for (JSONObject o : objs) {
                String name = str(o, "name", "topic_name", "topicName", "title",
                        "tag_name", "display_name");
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                String id = str(o, "topic_id", "topicId", "tag_id", "hashtag_id", "cid", "id");
                if (id != null && !id.matches("\\d{1,20}")) {
                    id = null;
                }
                if (!seen.containsKey(name.trim())) {
                    seen.put(name.trim(), id == null ? "" : id);
                }
                if (seen.size() >= limit) {
                    break;
                }
            }
            for (Map.Entry<String, String> e : seen.entrySet()) {
                out.add(new String[]{e.getValue().isEmpty() ? null : e.getValue(), e.getKey()});
            }
        } catch (Throwable t) {
            log(Log.WARN, "话题解析失败: " + t);
        }
        return out;
    }

    public static List<WatchItem> fetchTopicPosts(String topicId, String topicName, int limit) {
        List<WatchItem> out = new ArrayList<>();
        Map<String, String> headers = baseHeaders();
        String body = null;
        if (topicId != null && !topicId.isEmpty()) {
            String[] queries = {
                    "?topic_id=" + topicId + "&offset=0&limit=" + limit,
                    "?topic_ids=" + topicId + "&offset=0&limit=" + limit,
                    "?id=" + topicId + "&offset=0&limit=" + limit,
                    "?concept_id=" + topicId + "&offset=0&limit=" + limit,
                    "?hashtag_id=" + topicId + "&offset=0&limit=" + limit,
            };
            for (String path : TOPIC_FEED_PATHS) {
                for (String q : queries) {
                    body = HttpBridge.get(BASE + path + q, headers);
                    if (body != null && body.length() > 50) {
                        try {
                            collect(new JSONObject(body), out, 0);
                        } catch (Throwable t) {
                            log(Log.WARN, "话题帖解析失败 " + path + " : " + t);
                        }
                        if (!out.isEmpty()) {
                            log(Log.INFO, "话题帖 " + path + q.split("&")[0] + " → " + out.size() + " 条");
                            return out;
                        }
                    }
                    sleepQuiet(400);
                }
            }
        }
        if (topicName != null && !topicName.isEmpty()) {
            log(Log.INFO, "话题无 id，退化为关键词搜索：" + topicName);
            return fetchKeywordPosts(topicName, limit);
        }
        log(Log.WARN, "话题取数失败 topicId=" + topicId + " name=" + topicName);
        return out;
    }

    public static List<WatchItem> fetchKeywordPosts(String keyword, int limit) {
        List<WatchItem> out = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return out;
        }
        String q = java.net.URLEncoder.encode(keyword.trim());
        Map<String, String> headers = baseHeaders();
        for (String path : SEARCH_PATHS) {
            String body = HttpBridge.get(BASE + path + "?q=" + q + "&query=" + q
                    + "&offset=0&limit=" + limit + "&search_type=link&type=link", headers);
            if (body == null || body.length() < 50) {
                sleepQuiet(400);
                continue;
            }
            try {
                collect(new JSONObject(body), out, 0);
            } catch (Throwable t) {
                log(Log.WARN, "关键词搜索解析失败 " + path + " : " + t);
            }
            if (!out.isEmpty()) {
                log(Log.INFO, "关键词搜索 " + path + " [" + keyword + "] → " + out.size() + " 条");
                return out;
            }
            sleepQuiet(400);
        }
        log(Log.INFO, "关键词搜索无结果 [" + keyword + "]");
        return out;
    }

    public static List<String> fetchHotWords(String seed, int limit) {
        List<String> out = new ArrayList<>();
        Map<String, String> headers = baseHeaders();
        for (String path : HOT_WORD_PATHS) {
            String body = HttpBridge.get(BASE + path, headers);
            if (body != null && body.length() > 10) {
                collectWords(body, out, limit);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        if (seed != null && !seed.trim().isEmpty() && out.size() < limit) {
            String q = java.net.URLEncoder.encode(seed.trim());
            for (String path : SUGGEST_PATHS) {
                String body = HttpBridge.get(BASE + path + "?q=" + q + "&query=" + q, headers);
                if (body != null && body.length() > 10) {
                    collectWords(body, out, limit);
                }
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        log(Log.INFO, "推荐关键词候选 " + out.size() + " 个");
        return out;
    }

    private static void collectTopicObjects(Object node, List<JSONObject> out, int depth) {
        if (node == null || depth > 6 || out.size() > 200) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectTopicObjects(arr.opt(i), out, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) node;
        boolean looksLikeUser = str(o, "userid", "user_id", "heybox_id") != null;
        boolean hasName = str(o, "name", "topic_name", "topicName", "title",
                "tag_name", "display_name") != null;
        if (!looksLikeUser && hasName) {
            out.add(o);
        }
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject || v instanceof JSONArray) {
                collectTopicObjects(v, out, depth + 1);
            }
        }
    }

    private static void collectWords(String body, List<String> out, int limit) {
        try {
            Object root = body.trim().startsWith("[") ? new JSONArray(body) : new JSONObject(body);
            collectWords(root, out, limit, 0);
        } catch (Throwable t) {
            log(Log.WARN, "推荐关键词解析失败: " + t);
        }
    }

    private static void collectWords(Object node, List<String> out, int limit, int depth) {
        if (node == null || depth > 5 || out.size() >= limit) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.opt(i);
                if (v instanceof String) {
                    addWord(out, (String) v, limit);
                } else {
                    collectWords(v, out, limit, depth + 1);
                }
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) node;
        String word = str(o, "word", "keyword", "query", "key", "name", "text", "title", "content");
        if (word != null) {
            addWord(out, word, limit);
        }
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject || v instanceof JSONArray) {
                collectWords(v, out, limit, depth + 1);
            }
        }
    }

    private static void addWord(List<String> out, String raw, int limit) {
        String w = raw == null ? "" : raw.trim();
        if (w.isEmpty() || w.length() > 20 || out.size() >= limit) {
            return;
        }
        if (!out.contains(w)) {
            out.add(w);
        }
    }

    private static void logBody(String tag, String body) {
        if (body == null) {
            log(Log.WARN, tag + " 响应为空");
            return;
        }
        String s = body.replace('\n', ' ').replace('\r', ' ').trim();
        log(Log.WARN, tag + " body[" + body.length() + "]="
                + (s.length() > 240 ? s.substring(0, 240) + "…" : s));
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }


    private static void collect(Object node, List<WatchItem> out, int depth) {
        collect(node, out, depth, null);
    }

    private static void collect(Object node, List<WatchItem> out, int depth, JSONObject inheritedUser) {
        if (node == null || depth > 6 || out.size() > 300) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collect(arr.opt(i), out, depth + 1, inheritedUser);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) node;
        JSONObject here = authorObjectOf(o);
        JSONObject ctxUser = here != null ? here : inheritedUser;
        WatchItem item = extract(o, ctxUser);
        if (item != null) {
            out.add(item);
        }
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject || v instanceof JSONArray) {
                collect(v, out, depth + 1, ctxUser);
            }
        }
    }

    private static JSONObject authorObjectOf(JSONObject o) {
        JSONObject u = o.optJSONObject("user");
        if (u == null) {
            u = o.optJSONObject("user_info");
        }
        if (u == null) {
            u = o.optJSONObject("userinfo");
        }
        if (u == null) {
            u = o.optJSONObject("author");
        }
        if (u == null) {
            u = o.optJSONObject("hb_user");
        }
        if (u == null) {
            JSONObject link = o.optJSONObject("link");
            if (link != null) {
                u = link.optJSONObject("user");
                if (u == null) {
                    u = link.optJSONObject("user_info");
                }
            }
        }
        return u;
    }

    private static void collectUserObjects(Object node, List<JSONObject> out, int depth) {
        if (node == null || depth > 6 || out.size() > 500) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectUserObjects(arr.opt(i), out, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) node;
        if (str(o, "userid", "user_id", "heybox_id") != null
                && str(o, "username", "nickname", "user_name", "name") != null) {
            out.add(o);
        }
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject || v instanceof JSONArray) {
                collectUserObjects(v, out, depth + 1);
            }
        }
    }

    private static WatchItem extract(JSONObject o, JSONObject ctxUser) {
        String linkId = str(o, "link_id", "linkId", "linkid");
        JSONObject link = o.optJSONObject("link");
        if ((linkId == null || linkId.isEmpty()) && link != null) {
            linkId = str(link, "link_id", "linkid");
        }
        if (linkId == null || linkId.isEmpty()) {
            return null;
        }
        String title = str(o, "title", "link_title");
        String desc = str(o, "description", "desc", "content", "text");
        String authorId = str(o, "userid", "user_id", "author_id");
        String authorName = str(o, "username", "user_name", "nickname", "author");
        if (link != null) {
            if (title == null) {
                title = str(link, "title");
            }
            if (desc == null) {
                desc = str(link, "description", "desc");
            }
        }
        JSONObject user = authorObjectOf(o);
        if (user == null) {
            user = ctxUser;
        }
        if (user != null) {
            if (authorId == null) {
                authorId = str(user, "userid", "user_id", "heybox_id");
            }
            if (authorName == null) {
                authorName = str(user, "username", "nickname", "user_name", "name");
            }
        }
        long ts = firstTime(o, link);
        return new WatchItem(linkId, nz(title), nz(desc), nz(authorId), nz(authorName), ts, "", "");
    }

    private static final String[] TIME_KEYS = {
            "create_at", "create_time", "createAt", "publish_time", "publish_at",
            "post_time", "timestamp", "time", "ctime", "created_at",
    };

    private static long firstTime(JSONObject o, JSONObject link) {
        long v = firstTimeIn(o);
        if (v <= 0 && link != null) {
            v = firstTimeIn(link);
        }
        if (v > 100000000000L) {
            v = v / 1000L;
        }
        return v;
    }

    private static long firstTimeIn(JSONObject o) {
        for (String k : TIME_KEYS) {
            Object raw = o.opt(k);
            long v = toEpochSeconds(raw);
            if (v > 0) {
                return v;
            }
        }
        return 0L;
    }

    private static final Pattern DIGITS = Pattern.compile("(\\d{9,14})");
    private static final SimpleDateFormat[] DATE_FORMATS = {
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA),
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA),
            new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA),
    };

    private static long toEpochSeconds(Object raw) {
        if (raw == null || JSONObject.NULL.equals(raw)) {
            return 0L;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (Throwable ignored) {
        }
        Matcher m = DIGITS.matcher(s);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (Throwable ignored) {
            }
        }
        for (SimpleDateFormat f : DATE_FORMATS) {
            try {
                java.util.Date d = f.parse(s);
                if (d != null) {
                    return d.getTime() / 1000L;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return null;
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Referer", "https://www.xiaoheihe.cn/");
        return h;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static List<WatchItem> dedupe(List<WatchItem> in) {
        Map<String, WatchItem> map = new LinkedHashMap<>();
        for (WatchItem it : in) {
            if (!map.containsKey(it.linkId)) {
                map.put(it.linkId, it);
            }
        }
        return new ArrayList<>(map.values());
    }

    private static void log(int level, String msg) {
        MainModule m = sModule;
        if (m != null) {
            m.logd(level, m.TAG, "[动态推送] " + msg);
        }
    }
}
