package com.better.heybox.task;

public final class TaskItem {

    public static final String STATE_FINISH = "finish";
    public static final String STATE_CAN_REWARD = "can_reward";
    public static final String STATE_BLOCKING = "blocking";

    public final String group;
    public final boolean fromLine;
    public final String title;
    public final String desc;
    public final String type;
    public final String state;
    public final String stateDesc;
    public final String maxjia;
    public final String url;
    public final String tabId;
    public final long endTimeSec;
    public final String progress;

    public TaskItem(String group, boolean fromLine, String title, String desc, String type,
                    String state, String stateDesc, String maxjia, String url, String tabId,
                    long endTimeSec, String progress) {
        this.group = nz(group);
        this.fromLine = fromLine;
        this.title = nz(title);
        this.desc = nz(desc);
        this.type = nz(type);
        this.state = nz(state);
        this.stateDesc = nz(stateDesc);
        this.maxjia = nz(maxjia);
        this.url = nz(url);
        this.tabId = nz(tabId);
        this.endTimeSec = endTimeSec;
        this.progress = nz(progress);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    public boolean limited() {
        return endTimeSec > 0L;
    }

    public boolean done() {
        return STATE_FINISH.equals(state) || STATE_BLOCKING.equals(state);
    }

    public boolean canClaim() {
        return STATE_CAN_REWARD.equals(state);
    }

    public boolean hasEntry() {
        return !maxjia.isEmpty();
    }

    public String fingerprint() {
        String raw = group + '|' + title + '|' + desc;
        return Integer.toHexString(raw.hashCode());
    }

    public String searchText() {
        return title + " " + desc;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder(160);
        sb.append('[').append(group.isEmpty() ? "未分组" : group).append(']');
        if (fromLine) {
            sb.append(" 任务线 ").append(progress);
        }
        sb.append(' ').append(title);
        if (!desc.isEmpty()) {
            sb.append("（").append(desc).append('）');
        }
        sb.append(" type=").append(type.isEmpty() ? "-" : type);
        sb.append(" state=").append(state.isEmpty() ? "-" : state);
        if (!stateDesc.isEmpty()) {
            sb.append(" 按钮=").append(stateDesc);
        }
        if (limited()) {
            long left = endTimeSec * 1000L - System.currentTimeMillis();
            sb.append(" 剩").append(Math.max(0L, left) / 3600_000L).append("h");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "TaskItem{" + summary() + "}";
    }
}
