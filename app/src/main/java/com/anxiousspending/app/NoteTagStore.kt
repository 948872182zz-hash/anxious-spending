package com.anxiousspending.app

import android.content.Context
import org.json.JSONObject

class NoteTagStore(context: Context) {
    private val prefs = context.getSharedPreferences("anxious_spending_note_tags", Context.MODE_PRIVATE)

    fun loadClickCounts(): Map<String, Int> {
        val raw = prefs.getString(KEY_CLICKS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, obj.optInt(key, 0))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun incrementClick(text: String): Map<String, Int> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return loadClickCounts()

        val next = loadClickCounts().toMutableMap()
        next[normalized] = (next[normalized] ?: 0) + 1

        val obj = JSONObject()
        next.forEach { (key, value) -> obj.put(key, value) }
        prefs.edit().putString(KEY_CLICKS, obj.toString()).apply()
        return next.toMap()
    }

    private companion object {
        const val KEY_CLICKS = "note_tag_click_counts"
    }
}
