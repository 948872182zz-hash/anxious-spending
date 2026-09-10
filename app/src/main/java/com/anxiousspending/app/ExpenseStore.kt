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
                    val amount = obj.getDouble("amount")
                    val currency = obj.optString("currency", "CNY")
                    val exchangeRateToCny = obj.optDouble("exchangeRateToCny", 1.0)
                    add(
                        Expense(
                            id = obj.getString("id"),
                            amount = amount,
                            category = obj.getString("category"),
                            note = obj.optString("note", ""),
                            date = LocalDate.parse(obj.getString("date")),
                            currency = currency,
                            exchangeRateToCny = exchangeRateToCny,
                            cnyAmount = obj.optDouble("cnyAmount", amount * exchangeRateToCny)
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
                    put("currency", expense.currency)
                    put("exchangeRateToCny", expense.exchangeRateToCny)
                    put("cnyAmount", expense.cnyAmount)
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "expenses_json"
    }
}
