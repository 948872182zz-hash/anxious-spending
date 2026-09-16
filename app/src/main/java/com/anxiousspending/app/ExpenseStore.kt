package com.anxiousspending.app

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate

class ExpenseStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("anxious_spending", Context.MODE_PRIVATE)
    private val dao = AnxiousDatabase.get(appContext).expenseDao()

    suspend fun load(): List<Expense> {
        migrateLegacyIfNeeded()
        return dao.loadAll().map { it.toExpense() }
    }

    suspend fun save(expenses: List<Expense>) {
        dao.replaceAll(expenses.map { it.toEntity() })
    }

    private suspend fun migrateLegacyIfNeeded() {
        if (prefs.getBoolean(KEY_ROOM_MIGRATED, false)) return

        val existingRoomCount = dao.count()
        if (existingRoomCount > 0) {
            prefs.edit().putBoolean(KEY_ROOM_MIGRATED, true).apply()
            return
        }

        val legacy = loadLegacy()
        if (legacy.isNotEmpty()) {
            dao.insertAll(legacy.map { it.toEntity() })
        }

        // Keep the old JSON untouched as a temporary fallback. The flag only says
        // Room has taken over as the active data source.
        prefs.edit().putBoolean(KEY_ROOM_MIGRATED, true).apply()
    }

    private fun loadLegacy(): List<Expense> {
        val raw = prefs.getString(KEY_LEGACY_JSON, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val amount = obj.getDouble("amount")
                    val currency = obj.optString("currency", "CNY")
                    val exchangeRateToCny = obj.optDouble("exchangeRateToCny", 1.0)
                    val paymentSource = if (obj.has("paymentSource")) {
                        obj.optString("paymentSource", "other")
                    } else {
                        if (currency == "USD") "other" else "alipay"
                    }
                    add(
                        Expense(
                            id = obj.getString("id"),
                            amount = amount,
                            category = obj.getString("category"),
                            note = obj.optString("note", ""),
                            date = LocalDate.parse(obj.getString("date")),
                            currency = currency,
                            exchangeRateToCny = exchangeRateToCny,
                            cnyAmount = obj.optDouble("cnyAmount", amount * exchangeRateToCny),
                            paymentSource = paymentSource,
                            entryType = obj.optString("entryType", "expense")
                        )
                    )
                }
            }.sortedByDescending { it.date }
        }.getOrDefault(emptyList())
    }

    private fun Expense.toEntity(): ExpenseEntity = ExpenseEntity(
        id = id,
        amount = amount,
        category = category,
        note = note,
        date = date.toString(),
        currency = currency,
        exchangeRateToCny = exchangeRateToCny,
        cnyAmount = cnyAmount,
        paymentSource = paymentSource,
        entryType = entryType
    )

    private fun ExpenseEntity.toExpense(): Expense = Expense(
        id = id,
        amount = amount,
        category = category,
        note = note,
        date = LocalDate.parse(date),
        currency = currency,
        exchangeRateToCny = exchangeRateToCny,
        cnyAmount = cnyAmount,
        paymentSource = paymentSource,
        entryType = entryType
    )

    private companion object {
        const val KEY_LEGACY_JSON = "expenses_json"
        const val KEY_ROOM_MIGRATED = "room_migration_v1_done"
    }
}
