package com.better.heybox.watch;

import android.util.Log;

import com.better.heybox.MainModule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 取数：用宿主 OkHttp 拉「某用户的帖子列表」。
 *
 * <p>端点在 1.3.393 的 dex 里确认为 {@code bbs/app/profile/user/link/list}
 * （另有 {@code bbs/app/profile/user/profile}）。响应结构随版本可能变化，
 * 因此这里用<b>宽松解析</b>：递归遍历 JSON，收集所有含 link_id 的对象。
 */
public final class WatchFetcher {

    private static final String BASE = "https://api.xiaoheihe.cn/";
    private static volatile MainModule sModule;

    private WatchFetcher() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    public static String userPostsUrl(String userId, int limit) {
        return BASE + "bbs/app/profile/user/link/list?userid=" + userId
                + "&offset=0&limit=" + limit;
    }

    /** 拉一个用户的最近帖子；失败返回空列表 */
    public static List<WatchItem> fetchUserPosts(String userId, int limit) {
        List<WatchItem> out = new ArrayList<>();
        if (userId == null || userId.isEmpty()) {
            return out;
        }
        String url = userPostsUrl(userId, limit);
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Referer", "https://www.xiaoheihe.cn/");
        String body = HttpBridge.get(url, headers);
        if (body == null || body.isEmpty()) {
            log(Log.WARN, "user_posts 无响应 userid=" + userId);
            return out;
        }
        try {
            JSONObject root = new JSONObject(body);
            collect(root, out, 0);
        } catch (Throwable t) {
            log(Log.WARN, "user_posts 解析失败 userid=" + userId + " : " + t);
        }
        log(Log.INFO, "user_posts userid=" + userId + " 解析出 " + out.size() + " 条");
        return out;
    }

    /** 递归收集所有"像帖子"的对象；按 linkId 去重 */
    private static void collect(Object node, List<WatchItem> out, int depth) {
        if (node == null || depth > 6 || out.size() > 200) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collect(arr.opt(i), out, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) node;
        WatchItem item = extract(o);
        if (item != null) {
            out.add(item);
        }
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject || v instanceof JSONArray) {
                collect(v, out, depth + 1);
            }
        }
    }

    private static WatchItem extract(JSONObject o) {
        String linkId = firstNonEmpty(o, "link_id", "linkId", "linkid", "id_str");
        JSONObject link = o.optJSONObject("link");
        String title = firstNonEmpty(o, "title", "link_title");
        String desc = firstNonEmpty(o, "description", "desc", "content", "text");
        String authorId = firstNonEmpty(o, "userid", "user_id", "author_id");
        String authorName = firstNonEmpty(o, "username", "user_name", "nickname", "author");
        if (link != null) {
            if (linkId == null) {
                linkId = firstNonEmpty(link, "link_id", "linkid");
            }
            if (title == null) {
                title = firstNonEmpty(link, "title");
            }
            if (desc == null) {
                desc = firstNonEmpty(link, "description", "desc");
            }
        }
        JSONObject user = o.optJSONObject("user");
        if (user == null && link != null) {
            user = link.optJSONObject("user");
        }
        if (user != null) {
            if (authorId == null) {
                authorId = firstNonEmpty(user, "userid", "user_id");
            }
            if (authorName == null) {
                authorName = firstNonEmpty(user, "username", "nickname", "user_name");
            }
        }
        if (linkId == null) {
            return null;
        }
        long ts = firstLong(o, "create_at", "create_time", "publish_time", "timestamp", "time");
        if (ts > 100000000000L) {
            ts = ts / 1000L;
        }
        return new WatchItem(linkId, safe(title), safe(desc), safe(authorId), safe(authorName), ts, "", "");
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return null;
    }

    private static long firstLong(JSONObject o, String... keys) {
        for (String k : keys) {
            long v = o.optLong(k, 0L);
            if (v > 0) {
                return v;
            }
        }
        return 0L;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 合并多来源条目并按 linkId 去重（后出现的补充缺失字段） */
    public static List<WatchItem> dedupe(List<WatchItem> in) {
        Map<String, WatchItem> map = new java.util.LinkedHashMap<>();
        for (WatchItem it : in) {
            WatchItem old = map.get(it.linkId);
            if (old == null) {
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

    /** 供设置面板显示"最近一次请求结果"用 */
    public static final Set<String> EMPTY = new HashSet<>();
}
