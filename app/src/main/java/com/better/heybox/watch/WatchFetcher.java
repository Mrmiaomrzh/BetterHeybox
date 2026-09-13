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

/**
 * 取数：用宿主 OkHttp 拉「某用户的帖子列表」与「我的关注列表」。
 *
 * <p>端点在 1.3.393 dex 中确认：
 * <ul>
 *   <li>{@code bbs/app/profile/user/link/list} —— 某用户发布的帖子</li>
 *   <li>{@code bbs/app/profile/following/list} / {@code .../following/simple_list} /
 *       {@code .../inter_follow/list} —— 我的关注（用于一键导入）</li>
 * </ul>
 * 响应结构随版本可能变化，因此统一用<b>宽松解析</b>：递归遍历 JSON，按字段名候选收集。
 */
public final class WatchFetcher {

    private static final String BASE = "https://api.xiaoheihe.cn/";

    /** 每个关注对象每轮最多取多少条（新版在最前） */
    public static final int FETCH_LIMIT = 10;
    /** 关注列表导入上限 */
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
    /** 我关注的话题（1.3.393/1.3.395 dex 均确认存在） */
    private static final String[] TOPIC_LIST_PATHS = {
            "bbs/app/profile/preference_v5/topic_list",
            "bbs/app/profile/topic/settings",
    };
    /** 话题信息（按 id 批量取名字） */
    private static final String TOPIC_INFO_PATH = "bbs/app/topic/list_infos";
    /** 话题 / 标签搜索 */
    private static final String[] TOPIC_SEARCH_PATHS = {
            "bbs/app/api/search/topic",
            "bbs/app/topic/search",
            "bbs/app/hashtag/search",
    };
    /** 话题下的帖子流 */
    private static final String[] TOPIC_FEED_PATHS = {
            "bbs/app/topic/feeds",
            "bbs/app/topic/max/feeds",
            "bbs/app/hashtag/concept/feeds",
    };
    /** 关键词搜索 */
    private static final String[] SEARCH_PATHS = {
            "bbs/app/api/general/search/v1",
            "bbs/app/hashtag/search",
            "bbs/app/topic/search",
    };
    /** 热搜词（推荐关键词用） */
    private static final String[] HOT_WORD_PATHS = {
            "bbs/app/api/search/hot_words",
    };
    /** 搜索联想词（推荐关键词用） */
    private static final String[] SUGGEST_PATHS = {
            "bbs/app/api/search/suggestion/v2",
    };

    private static volatile MainModule sModule;

    private WatchFetcher() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    // ------------------------------------------------------------ 帖子

    public static String userPostsUrl(String userId, int limit) {
        return BASE + USER_POSTS_PATHS[0] + "?userid=" + userId + "&offset=0&limit=" + limit;
    }

    /** 拉一个用户的最近帖子（失败返回空列表） */
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

    // ------------------------------------------------------------ 关注列表

    /**
     * 拉「我关注的用户」列表。
     *
     * @param limit 最多返回多少个
     * @return 每个元素为 {userid, 昵称}
     */
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

    // ------------------------------------------------------------ 话题

    /**
     * 拉「我关注的话题」，用于一键导入成监控关键词。
     *
     * @return 每个元素 {话题id, 话题名}，id 可能为 null
     */
    public static List<String[]> fetchFollowedTopics(int limit) {
        Map<String, String> headers = baseHeaders();
        String body = HttpBridge.get(BASE + TOPIC_LIST_PATHS[0] + "?offset=0&limit=50", headers);
        List<String[]> out = new ArrayList<>();
        if (body != null && body.length() > 20) {
            out.addAll(parseTopicPairs(body, limit));
            if (out.isEmpty()) {
                // 实测该接口要求一个「平台」参数（返回 msg=请至少选择一个平台），
                // 到底叫什么名字没有公开信息，所以拿不到就回落到「最近浏览过的话题」
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

    /**
     * 回落到「最近浏览过的话题」：用户在小黑盒里打开过的话题页都会被记下 id，
     * 再用 {@code bbs/app/topic/list_infos} 批量换回名字。
     */
    public static List<String[]> fetchRecentTopics(int limit) {
        List<String> ids = HttpBridge.recentTopicIds();
        if (ids.isEmpty()) {
            log(Log.INFO, "最近浏览话题为空（在小黑盒里点开任意话题页即可记录）");
            return new ArrayList<>();
        }
        return fetchTopicInfos(ids, limit);
    }

    /** 按 id 批量取话题名：bbs/app/topic/list_infos?topic_ids=1,2,3 */
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

    /** 话题 / 标签搜索，返回 {id, 名字} */
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

    /** 从任意 JSON 里收集 {话题id, 话题名} 对 */
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

    /** 按话题取最新帖（id 优先，退化为话题名搜索） */
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

    /** 按关键词搜索最新帖 */
    public static List<WatchItem> fetchKeywordPosts(String keyword, int limit) {
        List<WatchItem> out = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return out;
        }
        String q = java.net.URLEncoder.encode(keyword.trim());
        Map<String, String> headers = baseHeaders();
        for (String path : SEARCH_PATHS) {
            // 实测：general/search/v1 必须带 search_type/type 才会返回帖子（不带就是空 items）
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

    /** 推荐关键词候选：热搜词 + 联想词（seed 可为空） */
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

    /** 从任意 JSON 里收集"像话题"的对象（排除用户对象） */
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

    /** 从任意 JSON 里收集候选词（字符串数组 / word 字段） */
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

    /** 取数失败时把响应片段记进日志，便于定位参数问题 */
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

    // ------------------------------------------------------------ 宽松解析

    private static void collect(Object node, List<WatchItem> out, int depth) {
        collect(node, out, depth, null);
    }

    /**
     * 宽松收集帖子对象。{@code inheritedUser} 是上层节点里带的作者对象：
     * 搜索结果常见 {@code {"info":{…帖子…},"user":{…作者…}}} 这种兄弟结构，
     * 帖子对象自己身上没有作者字段，不往下继承就会显示成「未知作者」。
     */
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

    /** 找当前节点里的作者对象：不同接口叫法不一，逐个候选试 */
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

    /** 收集所有"像用户"的对象（用于关注列表） */
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
            user = ctxUser;   // 作者是上一层节点的兄弟对象（搜索结果就是这种结构）
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

    /** 依次在条目与嵌套 link 上找时间字段，支持数字/数字串/日期串 */
    private static long firstTime(JSONObject o, JSONObject link) {
        long v = firstTimeIn(o);
        if (v <= 0 && link != null) {
            v = firstTimeIn(link);
        }
        if (v > 100000000000L) {
            v = v / 1000L;   // 毫秒 → 秒
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

    /** 合并多来源条目并按 linkId 去重 */
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
