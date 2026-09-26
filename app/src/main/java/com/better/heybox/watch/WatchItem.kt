package com.better.heybox.watch

class WatchItem(
    @JvmField val linkId: String?,
    @JvmField val title: String?,
    @JvmField val desc: String?,
    @JvmField val authorId: String?,
    @JvmField val authorName: String?,
    @JvmField val createAt: Long,
    @JvmField val hit: String?,
    @JvmField val hitUser: String?
) {

    fun webUrl(): String =
        "https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=" + linkId

    fun displayTitle(): String {
        val t = title?.trim()
        if (!t.isNullOrEmpty()) {
            return t
        }
        val d = desc?.trim()
        if (!d.isNullOrEmpty()) {
            return if (d.length > 40) d.substring(0, 40) + "…" else d
        }
        return "新动态 " + linkId
    }

    fun displayText(): String {
        val sb = StringBuilder()
        sb.append(if (authorName.isNullOrEmpty()) "未知作者" else authorName)
        val hu = hitUser
        if (!hu.isNullOrEmpty()) {
            when (hit) {
                "user" -> sb.append(" · 关注的人发布了新动态")
                "topic" -> sb.append(" · 来自话题「").append(hu).append("」")
                "keyword" -> sb.append(" · 命中关键词「").append(hu).append("」")
            }
        } else if (hit == "keyword") {
            sb.append(" · 命中关键词")
        }
        val d = desc?.trim()
        if (!d.isNullOrEmpty()) {
            val flat = d.replace('\n', ' ')
            sb.append('\n').append(if (flat.length > 80) flat.substring(0, 80) + "…" else flat)
        }
        return sb.toString()
    }

    override fun toString(): String =
        "WatchItem{" + linkId + ", " + displayTitle() + ", by " + authorName + ", hit=" + hit + "}"
}
