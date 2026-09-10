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

    fun incrementClick(category: String, text: String): Map<String, Int> {
        val normalizedCategory = category.trim()
        val normalizedText = text.trim()
        if (normalizedCategory.isEmpty() || normalizedText.isEmpty()) return loadClickCounts()

        val key = keyFor(normalizedCategory, normalizedText)
        val next = loadClickCounts().toMutableMap()
        next[key] = (next[key] ?: 0) + 1

        val obj = JSONObject()
        next.forEach { (savedKey, value) -> obj.put(savedKey, value) }
        prefs.edit().putString(KEY_CLICKS, obj.toString()).apply()
        return next.toMap()
    }

    companion object {
        fun keyFor(category: String, text: String): String = "${category.trim()}\u001F${text.trim()}"
        private const val KEY_CLICKS = "note_tag_click_counts"
    }
}
