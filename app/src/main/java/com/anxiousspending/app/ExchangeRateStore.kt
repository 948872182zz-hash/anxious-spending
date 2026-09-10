package com.anxiousspending.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

 data class ExchangeRateSnapshot(
    val ratesToCny: Map<String, Double>,
    val updatedAtMillis: Long
) {
    fun rateToCny(currency: String): Double? =
        if (currency == "CNY") 1.0 else ratesToCny[currency]
}

class ExchangeRateStore(context: Context) {
    private val prefs = context.getSharedPreferences("anxious_spending_exchange_rates", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(): ExchangeRateSnapshot? {
        val raw = prefs.getString(KEY_RATES, null) ?: return null
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        return runCatching {
            val obj = JSONObject(raw)
            val rates = buildMap {
                put("CNY", 1.0)
                listOf("USD", "KRW", "JPY").forEach { code ->
                    if (obj.has(code)) put(code, obj.getDouble(code))
                }
            }
            ExchangeRateSnapshot(rates, updatedAt)
        }.getOrNull()
    }

    fun refreshAsync(force: Boolean = false, callback: (ExchangeRateSnapshot?) -> Unit) {
        val cached = load()
        val freshEnough = cached != null && System.currentTimeMillis() - cached.updatedAtMillis < TWELVE_HOURS
        if (!force && freshEnough) {
            callback(cached)
            return
        }

        Thread {
            val fresh = fetchLatest() ?: cached
            mainHandler.post { callback(fresh) }
        }.start()
    }

    private fun fetchLatest(): ExchangeRateSnapshot? = runCatching {
        val connection = (URL("https://open.er-api.com/v6/latest/CNY").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json")
        }

        try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val ratesFromCny = root.getJSONObject("rates")
            val ratesToCny = buildMap {
                put("CNY", 1.0)
                listOf("USD", "KRW", "JPY").forEach { code ->
                    val foreignPerCny = ratesFromCny.getDouble(code)
                    if (foreignPerCny > 0.0) put(code, 1.0 / foreignPerCny)
                }
            }
            if (ratesToCny.size < 4) return null

            val snapshot = ExchangeRateSnapshot(ratesToCny, System.currentTimeMillis())
            save(snapshot)
            snapshot
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun save(snapshot: ExchangeRateSnapshot) {
        val obj = JSONObject()
        snapshot.ratesToCny.forEach { (code, rate) -> obj.put(code, rate) }
        prefs.edit()
            .putString(KEY_RATES, obj.toString())
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtMillis)
            .apply()
    }

    private companion object {
        const val KEY_RATES = "rates_to_cny"
        const val KEY_UPDATED_AT = "updated_at"
        const val TWELVE_HOURS = 12L * 60L * 60L * 1000L
    }
}
