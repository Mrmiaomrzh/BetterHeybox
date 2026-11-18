package com.better.heybox.hooks

class CyGroupSelector<Row, Comment : Any>(
    private val commentOf: (Row) -> Comment?,
    private val isCy: (Comment) -> Boolean,
    private val bodyOf: (Comment) -> String?,
    private val clockMillis: () -> Long,
    private val sameIdentity: (Comment, Comment) -> Boolean = { a, b -> a === b },
) {
    fun select(rows: List<Row>?, skipHead: Boolean = false): Comment? =
        scan(rows, skipHead, null)

    fun isFirstEligible(rows: List<Row>?, candidate: Comment?, skipHead: Boolean = false): Boolean =
        candidate != null && scan(rows, skipHead, candidate) != null

    private fun scan(rows: List<Row>?, skipHead: Boolean, candidate: Comment?): Comment? {
        if (rows == null) return null
        val size = rows.size
        val start = if (skipHead) 1 else 0
        if (size - start < 2) return null
        val started = clockMillis()
        var first: Comment? = null
        var body: String? = null
        var count = 0
        for (index in start until size) {
            if (clockMillis() - started > BUDGET_MS || rows.size != size) return null
            val row = try { rows[index] } catch (_: IndexOutOfBoundsException) { return null }
            val comment = commentOf(row) ?: continue
            if (!isCy(comment)) continue
            if (first == null) {
                if (candidate != null && !sameIdentity(comment, candidate)) return null
                first = comment
                body = bodyOf(comment)
            } else if (body != bodyOf(comment)) {
                return null
            }
            count++
        }
        return if (rows.size == size && count >= 2) first else null
    }

    companion object {
        const val BUDGET_MS = 120L
    }
}
