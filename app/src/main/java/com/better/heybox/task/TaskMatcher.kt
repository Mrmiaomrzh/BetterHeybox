package com.better.heybox.task

object TaskMatcher {

    val DEFAULT_KEYWORDS = arrayOf("发布", "发帖", "帖子", "内容")

    class Match(val task: TaskItem?, val reason: String)

    @JvmStatic
    fun keywords(raw: String?): Array<String> {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_KEYWORDS
        }
        val out = ArrayList<String>()
        for (part in raw.split("[,，、;；\\n]".toRegex())) {
            val s = part.trim()
            if (s.isNotEmpty()) {
                out.add(s)
            }
        }
        return if (out.isEmpty()) DEFAULT_KEYWORDS else out.toTypedArray()
    }

    @JvmStatic
    fun keywordHit(task: TaskItem, keywords: Array<String>?): Boolean {
        if (keywords == null || keywords.isEmpty()) {
            return true
        }
        val text = task.searchText()
        for (k in keywords) {
            if (k.isNotEmpty() && text.contains(k)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun pick(
        items: List<TaskItem>?, urgentOnly: Boolean, keywords: String?,
        doneFingerprints: Set<String>?, doneToday: Int, maxPerDay: Int
    ): Match {
        if (items.isNullOrEmpty()) {
            return Match(null, "任务列表为空")
        }
        val cap = if (maxPerDay <= 0) 1 else maxPerDay
        if (doneToday >= cap) {
            return Match(null, "今日已自动完成 $doneToday/$cap 条，达到每日上限")
        }
        val keys = keywords(keywords)
        var pending = 0
        var limited = 0
        var hit = 0
        val candidates = ArrayList<TaskItem>()
        for (t in items) {
            if (t.done()) {
                continue
            }
            pending++
            if (t.limited()) {
                limited++
            }
            if (doneFingerprints != null && doneFingerprints.contains(t.fingerprint())) {
                continue
            }
            if (!keywordHit(t, keys)) {
                continue
            }
            hit++
            if (urgentOnly && !t.limited()) {
                continue
            }
            if (!t.hasEntry()) {
                continue
            }
            candidates.add(t)
        }
        if (candidates.isEmpty()) {
            return Match(
                null, "待办 $pending 条（限时 $limited 条），关键词命中 "
                        + hit + " 条，但没有可自动打开的限时发帖任务"
            )
        }
        candidates.sortWith { a, b ->
            if (a.limited() != b.limited()) {
                return@sortWith if (a.limited()) -1 else 1
            }
            if (a.limited() && a.endTimeSec != b.endTimeSec) {
                return@sortWith if (a.endTimeSec < b.endTimeSec) -1 else 1
            }
            a.title.compareTo(b.title)
        }
        val best = candidates[0]
        return Match(
            best, "待办 $pending 条（限时 $limited 条），关键词命中 "
                    + hit + " 条，选中最紧急的一条：" + best.title
        )
    }
}
