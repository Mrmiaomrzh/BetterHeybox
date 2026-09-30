package com.better.heybox.task;

import android.util.Log;

import com.better.heybox.MainModule;
import com.better.heybox.watch.HttpBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TaskListClient {

    public static final String TASK_LIST_PATH = "task/list_v2/";

    private static final Map<String, String> BASE_HEADERS;
    static {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Referer", "https://www.xiaoheihe.cn/");
        BASE_HEADERS = java.util.Collections.unmodifiableMap(h);
    }

    private static volatile MainModule sModule;
    private static volatile String sLastSummary = "尚未读取";
    private static volatile String sLastError = "";
    private static volatile long sLastAt;
    private static volatile int sLastCount;
    private static volatile int sLastLimited;
    private static final Set<String> sSeenFingerprints = new HashSet<>();

    private TaskListClient() {
    }

    public static void init(MainModule module) {
        sModule = module;
    }

    public static List<TaskItem> fetch() {
        String body = null;
        String err = "";
        try {
            if (!HttpBridge.ready()) {
                err = "宿主 HTTP 客户端尚未捕获（打开「记录日志」或先让 App 发一次网络请求）";
            } else {
                body = HttpBridge.get(HttpBridge.BASE + TASK_LIST_PATH, BASE_HEADERS);
                if (body == null || body.trim().isEmpty()) {
                    err = "任务列表无响应";
                }
            }
        } catch (Throwable t) {
            err = "任务列表请求异常: " + t;
        }
        if (body == null || body.trim().isEmpty()) {
            record(null, err);
            return null;
        }
        try {
            List<TaskItem> out = new ArrayList<>();
            sSeenFingerprints.clear();
            walk(new JSONObject(body), "", false, out, 0);
            if (out.isEmpty()) {
                walkAny(body, out);
            }
            record(out, "");
            return out;
        } catch (Throwable t) {
            record(null, "任务列表解析失败: " + t);
            return null;
        }
    }

    private static void walk(Object node, String group, boolean fromLine,
                            List<TaskItem> out, int depth) {
        if (node == null || depth > 12 || out.size() > 400) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                walk(arr.opt(i), group, fromLine, out, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) node;
        JSONArray tasks = o.optJSONArray("tasks");
        if (tasks != null) {
            String g = o.optString("title", group);
            for (int i = 0; i < tasks.length(); i++) {
                walk(tasks.opt(i), g, fromLine, out, depth + 1);
            }
            return;
        }
        JSONArray lines = o.optJSONArray("task_line_items");
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                Object line = lines.opt(i);
                if (!(line instanceof JSONObject)) {
                    continue;
                }
                JSONObject lo = (JSONObject) line;
                String label = lo.optString("title", "");
                String cnt = lo.optString("finish_cnt", "");
                String total = lo.optString("total_cnt", "");
                String progress = cnt.isEmpty() && total.isEmpty()
                        ? "" : (cnt.isEmpty() ? "0" : cnt) + "/" + (total.isEmpty() ? "?" : total);
                JSONArray lineTasks = lo.optJSONArray("tasks");
                if (lineTasks == null) {
                    continue;
                }
                for (int j = 0; j < lineTasks.length(); j++) {
                    walk(lineTasks.opt(j), label.isEmpty() ? group : label, true, out, depth + 1);
                }
            }
            return;
        }
        if (looksLikeTask(o)) {
            add(out, o, group, fromLine, "");
            return;
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String key = it.next();
            if ("report_extra".equals(key)) {
                continue;
            }
            walk(o.opt(key), group, fromLine, out, depth + 1);
        }
    }

    private static void walkAny(Object node, List<TaskItem> out) {
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                if (looksLikeTask(o)) {
                    add(out, o, "", false, "");
                    return;
                }
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    walkAny(o.opt(it.next()), out);
                }
            } else if (node instanceof JSONArray) {
                JSONArray arr = (JSONArray) node;
                for (int i = 0; i < arr.length(); i++) {
                    walkAny(arr.opt(i), out);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean looksLikeTask(JSONObject o) {
        return o.has("type") && o.has("state") && o.has("title") && !o.has("tasks");
    }

    private static void add(List<TaskItem> out, JSONObject o, String group,
                            boolean fromLine, String progress) {
        TaskItem item = parse(o, group, fromLine, progress);
        if (item == null || item.title.isEmpty()) {
            return;
        }
        String fp = item.fingerprint() + "|" + item.state;
        if (!sSeenFingerprints.contains(fp)) {
            sSeenFingerprints.add(fp);
            out.add(item);
        }
    }

    private static TaskItem parse(JSONObject o, String group, boolean fromLine, String progress) {
        long end = 0L;
        try {
            String raw = o.optString("task_end_time", "");
            if (!raw.isEmpty()) {
                end = (long) Double.parseDouble(raw);
            }
        } catch (Throwable ignored) {
        }
        return new TaskItem(group, fromLine,
                o.optString("title", ""), o.optString("desc", ""),
                o.optString("type", ""), o.optString("state", ""),
                o.optString("state_desc", ""), o.optString("maxjia", ""),
                o.optString("url", ""), o.optString("tab_id", ""),
                end, progress);
    }

    private static synchronized void record(List<TaskItem> items, String err) {
        sLastAt = System.currentTimeMillis();
        sLastError = err == null ? "" : err;
        if (items == null) {
            sLastCount = 0;
            sLastLimited = 0;
            sLastSummary = "读取失败：" + sLastError;
            return;
        }
        int limited = 0;
        for (TaskItem t : items) {
            if (t.limited()) {
                limited++;
            }
        }
        sLastCount = items.size();
        sLastLimited = limited;
        StringBuilder sb = new StringBuilder(512);
        sb.append("任务 ").append(items.size()).append(" 条，其中限时 ").append(limited).append(" 条");
        int show = Math.min(items.size(), 12);
        for (int i = 0; i < show; i++) {
            sb.append('\n').append(items.get(i).summary());
        }
        if (items.size() > show) {
            sb.append("\n… 还有 ").append(items.size() - show).append(" 条（见日志）");
        }
        sLastSummary = sb.toString();
    }

    public static String report() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("接口：GET /task/list_v2/（零参数，路径跨版本稳定）\n");
        sb.append("宿主客户端：").append(HttpBridge.ready()
                ? "已捕获 " + HttpBridge.describe()
                : (HttpBridge.captureInstalled() ? "已挂捕获 Hook，等宿主第一次发请求" : "未挂捕获 Hook"));
        String uid = HttpBridge.hostUserId();
        sb.append("\n宿主账号：").append(uid == null || uid.isEmpty() ? "未登录" : uid);
        sb.append("\n最近读取：").append(sLastAt == 0L
                ? "从未"
                : android.text.format.DateFormat.format("MM-dd HH:mm:ss", sLastAt));
        sb.append("\n最近结果：").append(sLastSummary);
        if (!sLastError.isEmpty()) {
            sb.append("\n错误：").append(sLastError);
        }
        return sb.toString();
    }

    public static String lastError() {
        return sLastError;
    }

    public static void logAll(MainModule module, List<TaskItem> items) {
        if (module == null || items == null || items.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (TaskItem t : items) {
            if (seen.add(t.fingerprint() + t.state)) {
                module.logd(Log.INFO, module.TAG, "我的任务：" + t.summary());
            }
        }
    }
}
