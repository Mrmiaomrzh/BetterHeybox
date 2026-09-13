package com.better.heybox.watch;

/** 一条被监控到的帖子（归一化后的最小字段集）。 */
public final class WatchItem {

    public final String linkId;
    public final String title;
    public final String desc;
    public final String authorId;
    public final String authorName;
    public final long createAt;
    /** 命中来源：user=关注作者，keyword=关键词 */
    public final String hit;
    /** 命中的关注对象（用于文案） */
    public final String hitUser;

    public WatchItem(String linkId, String title, String desc, String authorId,
                     String authorName, long createAt, String hit, String hitUser) {
        this.linkId = linkId;
        this.title = title;
        this.desc = desc;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createAt = createAt;
        this.hit = hit;
        this.hitUser = hitUser;
    }

    /** 分享/打开链接。小黑盒对 BROWSABLE 的 web/share 链接有路由。 */
    public String webUrl() {
        return "https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=" + linkId;
    }

    public String displayTitle() {
        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }
        if (desc != null && !desc.trim().isEmpty()) {
            String d = desc.trim();
            return d.length() > 40 ? d.substring(0, 40) + "…" : d;
        }
        return "新动态 " + linkId;
    }

    public String displayText() {
        StringBuilder sb = new StringBuilder();
        sb.append(authorName == null || authorName.isEmpty() ? "未知作者" : authorName);
        if ("user".equals(hit) && hitUser != null && !hitUser.isEmpty()) {
            sb.append(" · 关注的人发布了新动态");
        } else if ("topic".equals(hit) && hitUser != null && !hitUser.isEmpty()) {
            sb.append(" · 来自话题「").append(hitUser).append("」");
        } else if ("keyword".equals(hit) && hitUser != null && !hitUser.isEmpty()) {
            sb.append(" · 命中关键词「").append(hitUser).append("」");
        } else if ("keyword".equals(hit)) {
            sb.append(" · 命中关键词");
        }
        if (desc != null && !desc.trim().isEmpty()) {
            String d = desc.trim().replace('\n', ' ');
            sb.append('\n').append(d.length() > 80 ? d.substring(0, 80) + "…" : d);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "WatchItem{" + linkId + ", " + displayTitle() + ", by " + authorName + ", hit=" + hit + "}";
    }
}
