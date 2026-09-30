package com.better.heybox.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class TaskMatcher {

    public static final String[] DEFAULT_KEYWORDS = {"发布", "发帖", "帖子", "内容"};

    private TaskMatcher() {
    }

    public static final class Match {
        public final TaskItem task;
        public final String reason;

        Match(TaskItem task, String reason) {
            this.task = task;
            this.reason = reason;
        }
    }

    public static String[] keywords(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_KEYWORDS;
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,，、;；\\n]")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? DEFAULT_KEYWORDS : out.toArray(new String[0]);
    }

    public static boolean keywordHit(TaskItem task, String[] keywords) {
        if (keywords == null || keywords.length == 0) {
            return true;
        }
        String text = task.searchText();
        for (String k : keywords) {
            if (k != null && !k.isEmpty() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    public static Match pick(List<TaskItem> items, boolean urgentOnly, String keywords,
                             Set<String> doneFingerprints, int doneToday, int maxPerDay) {
        if (items == null || items.isEmpty()) {
            return new Match(null, "任务列表为空");
        }
        int cap = maxPerDay <= 0 ? 1 : maxPerDay;
        if (doneToday >= cap) {
            return new Match(null, "今日已自动完成 " + doneToday + "/" + cap + " 条，达到每日上限");
        }
        String[] keys = keywords(keywords);
        int pending = 0;
        int limited = 0;
        int hit = 0;
        List<TaskItem> candidates = new ArrayList<>();
        for (TaskItem t : items) {
            if (t.done()) {
                continue;
            }
            pending++;
            if (t.limited()) {
                limited++;
            }
            if (doneFingerprints != null && doneFingerprints.contains(t.fingerprint())) {
                continue;
            }
            if (!keywordHit(t, keys)) {
                continue;
            }
            hit++;
            if (urgentOnly && !t.limited()) {
                continue;
            }
            if (!t.hasEntry()) {
                continue;
            }
            candidates.add(t);
        }
        if (candidates.isEmpty()) {
            return new Match(null, "待办 " + pending + " 条（限时 " + limited + " 条），关键词命中 "
                    + hit + " 条，但没有可自动打开的限时发帖任务");
        }
        Collections.sort(candidates, new Comparator<TaskItem>() {
            @Override
            public int compare(TaskItem a, TaskItem b) {
                if (a.limited() != b.limited()) {
                    return a.limited() ? -1 : 1;
                }
                if (a.limited() && a.endTimeSec != b.endTimeSec) {
                    return a.endTimeSec < b.endTimeSec ? -1 : 1;
                }
                return a.title.compareTo(b.title);
            }
        });
        TaskItem best = candidates.get(0);
        return new Match(best, "待办 " + pending + " 条（限时 " + limited + " 条），关键词命中 "
                + hit + " 条，选中最紧急的一条：" + best.title);
    }
}
