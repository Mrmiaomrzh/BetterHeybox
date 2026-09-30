package com.better.heybox.task;

import com.better.heybox.watch.HttpBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OwnPostClient {

    private static final String[] USER_POST_PATHS = {
            "bbs/app/profile/user/link/list",
            "bbs/app/profile/award/link",
    };

    private static final Map<String, String> BASE_HEADERS;
    static {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Referer", "https://www.xiaoheihe.cn/");
        BASE_HEADERS = java.util.Collections.unmodifiableMap(h);
    }

    private OwnPostClient() {
    }

    public static boolean hasPost(String linkId) {
        if (linkId == null || linkId.trim().isEmpty()) {
            return false;
        }
        String uid = HttpBridge.hostUserId();
        if (uid == null || uid.isEmpty()) {
            return false;
        }
        String id = linkId.trim();
        for (String path : USER_POST_PATHS) {
            String url = HttpBridge.BASE + path + "?userid=" + uid + "&offset=0&limit=30";
            String body;
            try {
                body = HttpBridge.get(url, BASE_HEADERS);
            } catch (Throwable t) {
                android.util.Log.w("BetterHeybox", "[OwnPostClient] hasPost 请求异常 " + path + " : " + t);
                body = null;
            }
            if (body == null || body.isEmpty()) {
                continue;
            }
            try {
                if (containsId(new JSONObject(body), id)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean containsId(Object node, String id) {
        return findItem(node, id) != null;
    }

    public static String findRecent(String keyword, long sinceMillis) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "";
        }
        String uid = HttpBridge.hostUserId();
        if (uid == null || uid.isEmpty()) {
            return "";
        }
        String want = keyword.trim();
        for (String path : USER_POST_PATHS) {
            String url = HttpBridge.BASE + path + "?userid=" + uid + "&offset=0&limit=30";
            String body;
            try {
                body = HttpBridge.get(url, BASE_HEADERS);
            } catch (Throwable t) {
                android.util.Log.w("BetterHeybox", "[OwnPostClient] findRecent 请求异常 " + path + " : " + t);
                body = null;
            }
            if (body == null || body.isEmpty()) {
                continue;
            }
            try {
                String hit = pickNewest(new JSONObject(body), want, sinceMillis);
                if (!hit.isEmpty()) {
                    return hit;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static final class Item {
        String linkId = "";
        String text = "";
        long createdAt;
    }

    private static String pickNewest(Object node, String keyword, long sinceMillis) {
        String best = "";
        long bestAt = 0L;
        for (Item it : collect(node)) {
            if (it.linkId.isEmpty() || !it.text.contains(keyword)) {
                continue;
            }
            if (it.createdAt > 0L && it.createdAt < sinceMillis) {
                continue;
            }
            if (best.isEmpty() || it.createdAt >= bestAt) {
                best = it.linkId;
                bestAt = it.createdAt;
            }
        }
        return best;
    }

    private static List<Item> collect(Object node) {
        List<Item> out = new ArrayList<>();
        gather(node, out, 0);
        return out;
    }

    private static void gather(Object node, List<Item> out, int depth) {
        if (node == null || depth > 10) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String id = str(o, "link_id", "linkid", "linkId");
            if (id.isEmpty()) {
                Object link = o.opt("link");
                if (link instanceof JSONObject) {
                    id = str((JSONObject) link, "link_id", "linkid");
                }
            }
            if (!id.isEmpty()) {
                Item it = new Item();
                it.linkId = id;
                it.text = o.optString("description", "") + " " + o.optString("title", "")
                        + " " + o.optString("text", "") + " " + o.optString("content", "");
                it.createdAt = parseTime(o.optString("create_at", ""));
                out.add(it);
                return;
            }
            java.util.Iterator<String> it2 = o.keys();
            while (it2.hasNext()) {
                gather(o.opt(it2.next()), out, depth + 1);
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                gather(arr.opt(i), out, depth + 1);
            }
        }
    }

    private static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    private static long parseTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 0L;
        }
        String s = raw.trim();
        try {
            long n = Long.parseLong(s);
            return n > 1000000000000L ? n : n * 1000L;
        } catch (Throwable ignored) {
        }
        for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"}) {
            try {
                return new java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(s).getTime();
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static Item findItem(Object node, String id) {
        for (Item it : collect(node)) {
            if (it.linkId.equals(id)) {
                return it;
            }
        }
        return null;
    }
}
