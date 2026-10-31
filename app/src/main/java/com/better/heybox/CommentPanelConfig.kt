package com.better.heybox

import org.json.JSONArray
import org.json.JSONObject

object CommentPanelConfig {
    data class Action(val id: String, val name: String)
    private const val KEY = "comment_panel_actions"
    private const val DISCOVERED_KEY = "comment_panel_discovered"
    private data class State(val actions: MutableList<Action>, val order: MutableList<String>, val hidden: MutableSet<String>)

    private fun read(): State {
        val json = try { JSONObject(HeyboxPrefs.getString(KEY, null) ?: "{}") } catch (_: Exception) { JSONObject() }
        val actions = mutableListOf<Action>()
        val discovered = try { JSONArray(HeyboxPrefs.getString(DISCOVERED_KEY, null) ?: "[]") } catch (_: Exception) { JSONArray() }
        for (i in 0 until discovered.length()) {
            val item = discovered.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank() && actions.none { it.id == id }) actions.add(Action(id, item.optString("name", id)))
        }
        fun strings(key: String): MutableList<String> {
            val array = json.optJSONArray(key) ?: JSONArray()
            return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.distinct().toMutableList()
        }
        return State(actions, strings("order"), strings("hidden").toMutableSet())
    }
    private fun write(state: State) {
        HeyboxPrefs.setString(KEY, JSONObject().put("order", JSONArray(state.order))
            .put("hidden", JSONArray(state.hidden.toList())).toString())
    }
    private fun writeDiscovered(actions: List<Action>) {
        val array = JSONArray()
        actions.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        HeyboxPrefs.setString(DISCOVERED_KEY, array.toString())
    }
    @Synchronized fun recordDiscovered(actions: List<Action>) {
        val state = read()
        var changed = false
        for (action in actions) {
            if (action.id.isBlank()) continue
            val normalized = Action(action.id, action.name.ifBlank { action.id })
            val index = state.actions.indexOfFirst { it.id == action.id }
            if (index < 0) { state.actions.add(normalized); changed = true }
            else if (state.actions[index] != normalized) { state.actions[index] = normalized; changed = true }
        }
        if (changed) writeDiscovered(state.actions)
    }
    @Synchronized fun orderedVisibleIds(ids: List<String>): List<String> {
        val state = read()
        val rank = state.order.withIndex().associate { it.value to it.index }
        return ids.filterNot { it in state.hidden }.withIndex()
            .sortedWith(compareBy({ rank[it.value] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }
    @Synchronized fun discovered(): List<Action> {
        val state = read()
        val rank = state.order.withIndex().associate { it.value to it.index }
        return state.actions.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }
    @Synchronized fun hiddenIds(): Set<String> = read().hidden.toSet()
    @Synchronized fun save(order: List<String>, hidden: Set<String>) {
        val state = read()
        val merged = mergeSavedOrder(state.order, order)
        state.order.clear()
        state.order.addAll(merged)
        state.hidden.clear()
        state.hidden.addAll(hidden.filter { it.isNotBlank() })
        write(state)
    }
    @Synchronized fun reset() {
        val state = read()
        state.order.clear()
        state.hidden.clear()
        write(state)
    }
}
