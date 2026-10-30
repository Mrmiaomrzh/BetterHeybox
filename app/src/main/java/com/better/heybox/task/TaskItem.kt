package com.better.heybox.task

class TaskItem(
    group: String?,
    val fromLine: Boolean,
    title: String?,
    desc: String?,
    type: String?,
    state: String?,
    stateDesc: String?,
    maxjia: String?,
    url: String?,
    tabId: String?,
    val endTimeSec: Long,
    progress: String?
) {

    val group: String = nz(group)
    val title: String = nz(title)
    val desc: String = nz(desc)
    val type: String = nz(type)
    val state: String = nz(state)
    val stateDesc: String = nz(stateDesc)
    val maxjia: String = nz(maxjia)
    val url: String = nz(url)
    val tabId: String = nz(tabId)
    val progress: String = nz(progress)

    fun limited(): Boolean = endTimeSec > 0L

    fun done(): Boolean = STATE_FINISH == state || STATE_BLOCKING == state

    fun canClaim(): Boolean = STATE_CAN_REWARD == state

    fun hasEntry(): Boolean = maxjia.isNotEmpty()

    fun fingerprint(): String {
        val raw = "$group|$title|$desc"
        return Integer.toHexString(raw.hashCode())
    }

    fun searchText(): String = "$title $desc"

    fun summary(): String {
        val sb = StringBuilder(160)
        sb.append('[').append(if (group.isEmpty()) "未分组" else group).append(']')
        if (fromLine) {
            sb.append(" 任务线 ").append(progress)
        }
        sb.append(' ').append(title)
        if (desc.isNotEmpty()) {
            sb.append("（").append(desc).append('）')
        }
        sb.append(" type=").append(if (type.isEmpty()) "-" else type)
        sb.append(" state=").append(if (state.isEmpty()) "-" else state)
        if (stateDesc.isNotEmpty()) {
            sb.append(" 按钮=").append(stateDesc)
        }
        if (limited()) {
            val left = endTimeSec * 1000L - System.currentTimeMillis()
            sb.append(" 剩").append(maxOf(0L, left) / 3600_000L).append("h")
        }
        return sb.toString()
    }

    override fun toString(): String = "TaskItem{${summary()}}"

    companion object {
        const val STATE_FINISH = "finish"
        const val STATE_CAN_REWARD = "can_reward"
        const val STATE_BLOCKING = "blocking"

        private fun nz(s: String?): String = s?.trim() ?: ""
    }
}
