package com.anxiousspending.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class ExpenseStore(context: Context) {
    private val prefs = context.getSharedPreferences("anxious_spending", Context.MODE_PRIVATE)

    fun load(): List<Expense> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        Expense(
                            id = obj.getString("id"),
                            amount = obj.getDouble("amount"),
                            category = obj.getString("category"),
                            note = obj.optString("note", ""),
                            date = LocalDate.parse(obj.getString("date"))
                        )
                    )
                }
            }.sortedByDescending { it.date }
        }.getOrDefault(emptyList())
    }

    fun save(expenses: List<Expense>) {
        val array = JSONArray()
        expenses.forEach { expense ->
            array.put(
                JSONObject().apply {
                    put("id", expense.id)
                    put("amount", expense.amount)
                    put("category", expense.category)
                    put("note", expense.note)
                    put("date", expense.date.toString())
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "expenses_json"
    }
}
