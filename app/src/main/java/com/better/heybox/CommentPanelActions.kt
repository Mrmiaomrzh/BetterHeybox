package com.better.heybox
internal fun uniquePanelActionIds(ids: List<String?>): List<String?> {
    val counts = ids.filterNotNull().groupingBy { it }.eachCount()
    return ids.map { id -> id?.takeIf { counts[it] == 1 } }
}
internal fun <T> arrangePanelActions(original: List<T>, uniqueIds: List<String?>, visibleIds: List<String>): List<T> {
    val byId = uniqueIds.mapIndexedNotNull { index, id -> id?.let { it to original[index] } }.toMap()
    val ordered = visibleIds.mapNotNull { id -> byId[id] }.iterator()
    val result = ArrayList<T>()
    for (index in original.indices) {
        if (uniqueIds[index] == null) result.add(original[index])
        else if (ordered.hasNext()) result.add(ordered.next())
    }
    return result
}
internal fun mergeSavedOrder(previous: List<String>, edited: List<String>): List<String> {
    val updated = edited.filter { it.isNotBlank() }.distinct()
    val editedIds = updated.toSet()
    val remaining = updated.iterator()
    val merged = mutableListOf<String>()
    for (id in previous) {
        if (id !in editedIds) merged.add(id)
        else if (remaining.hasNext()) merged.add(remaining.next())
    }
    while (remaining.hasNext()) merged.add(remaining.next())
    return merged
}
