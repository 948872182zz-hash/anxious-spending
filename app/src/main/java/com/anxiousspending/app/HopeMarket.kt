package com.anxiousspending.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class HopeHolding(
    val code: String,
    val market: Int,
    val shares: Double,
    val fallbackName: String
)

data class HopeQuote(
    val code: String,
    val name: String,
    val price: Double,
    val previousClose: Double,
    val change: Double,
    val changePercent: Double,
    val updatedAtMillis: Long
)

private val defaultHopeHoldings = listOf(
    HopeHolding("159755", 0, 200.0, "电池ETF"),
    HopeHolding("513300", 1, 100.0, "纳斯达克ETF"),
    HopeHolding("513310", 1, 100.0, "中韩半导体ETF"),
    HopeHolding("513500", 1, 100.0, "标普500ETF"),
    HopeHolding("562500", 1, 300.0, "机器人ETF")
)

class HopeHoldingStore(context: Context) {
    private val prefs = context.getSharedPreferences("anxious_spending_hope_holdings", Context.MODE_PRIVATE)

    fun load(): List<HopeHolding> {
        val raw = prefs.getString("holdings", null) ?: return defaultHopeHoldings
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        HopeHolding(
                            code = o.getString("code"),
                            market = o.getInt("market"),
                            shares = o.getDouble("shares"),
                            fallbackName = o.optString("fallbackName", o.getString("code"))
                        )
                    )
                }
            }
        }.getOrDefault(defaultHopeHoldings)
    }

    fun save(holdings: List<HopeHolding>) {
        val array = JSONArray()
        holdings.forEach { holding ->
            array.put(JSONObject().apply {
                put("code", holding.code)
                put("market", holding.market)
                put("shares", holding.shares)
                put("fallbackName", holding.fallbackName)
            })
        }
        prefs.edit().putString("holdings", array.toString()).apply()
    }
}

private fun guessHopeMarket(code: String): Int =
    if (code.startsWith("5") || code.startsWith("6") || code.startsWith("9")) 1 else 0

class HopeQuoteStore(context: Context) {
    private val prefs = context.getSharedPreferences("anxious_spending_hope_quotes", Context.MODE_PRIVATE)

    fun load(): Map<String, HopeQuote> {
        val raw = prefs.getString("quotes", null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val quote = HopeQuote(
                        code = o.getString("code"),
                        name = o.getString("name"),
                        price = o.getDouble("price"),
                        previousClose = o.getDouble("previousClose"),
                        change = o.getDouble("change"),
                        changePercent = o.getDouble("changePercent"),
                        updatedAtMillis = o.getLong("updatedAtMillis")
                    )
                    put(quote.code, quote)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(quotes: Collection<HopeQuote>) {
        val array = JSONArray()
        quotes.forEach { q ->
            array.put(JSONObject().apply {
                put("code", q.code)
                put("name", q.name)
                put("price", q.price)
                put("previousClose", q.previousClose)
                put("change", q.change)
                put("changePercent", q.changePercent)
                put("updatedAtMillis", q.updatedAtMillis)
            })
        }
        prefs.edit().putString("quotes", array.toString()).apply()
    }
}

private suspend fun fetchHopeQuotes(holdings: List<HopeHolding>): Map<String, HopeQuote> = withContext(Dispatchers.IO) {
    holdings.mapNotNull { holding ->
        runCatching {
            val secId = "${holding.market}.${holding.code}"
            val endpoint = "https://push2.eastmoney.com/api/qt/stock/get?invt=2&fltt=2&fields=f43,f57,f58,f60,f169,f170&secid=$secId"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://quote.eastmoney.com/")
            }
            try {
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val data = JSONObject(text).optJSONObject("data") ?: error("No quote data")
                val price = data.optDouble("f43", Double.NaN)
                val previousClose = data.optDouble("f60", Double.NaN)
                val change = data.optDouble("f169", Double.NaN)
                val pct = data.optDouble("f170", Double.NaN)
                if (!price.isFinite() || price <= 0.0) error("Invalid quote")
                HopeQuote(
                    code = holding.code,
                    name = data.optString("f58").ifBlank { holding.fallbackName },
                    price = price,
                    previousClose = previousClose.takeIf { it.isFinite() } ?: price,
                    change = change.takeIf { it.isFinite() } ?: (price - previousClose),
                    changePercent = pct.takeIf { it.isFinite() } ?: 0.0,
                    updatedAtMillis = System.currentTimeMillis()
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }.associateBy { it.code }
}

private fun hopeMoney(value: Double): String = "¥%,.2f".format(value)

private fun hopeSignedMoney(value: Double): String =
    if (value >= 0.0) "+${hopeMoney(value)}" else "-${hopeMoney(abs(value))}"

private fun hopeSignedPercent(value: Double): String = "%+.2f%%".format(value)

private fun hopeUpdatedText(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))

@Composable
fun HopeMarketModule() {
    val context = LocalContext.current
    val quoteStore = remember { HopeQuoteStore(context) }
    val holdingStore = remember { HopeHoldingStore(context) }
    var holdings by remember { mutableStateOf(holdingStore.load()) }
    var quotes by remember { mutableStateOf(quoteStore.load()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableStateOf(0) }
    var showEditHoldings by remember { mutableStateOf(false) }

    LaunchedEffect(refreshNonce, holdings) {
        loading = true
        error = null
        val fresh = runCatching { fetchHopeQuotes(holdings) }.getOrElse { emptyMap() }
        if (fresh.isNotEmpty()) {
            quotes = quotes + fresh
            quoteStore.save(quotes.values)
        } else if (quotes.isEmpty()) {
            error = "行情暂时没拉到，晚点再试"
        }
        loading = false
    }

    val rows = holdings.mapNotNull { holding ->
        quotes[holding.code]?.let { holding to it }
    }
    val totalMarketValue = rows.sumOf { (holding, quote) -> holding.shares * quote.price }
    val totalDailyChange = rows.sumOf { (holding, quote) -> holding.shares * quote.change }
    val latestUpdate = rows.maxOfOrNull { it.second.updatedAtMillis }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("HOPE💹", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            latestUpdate?.let { "行情更新 ${hopeUpdatedText(it)}" } ?: "还没有行情缓存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showEditHoldings = true }) { Text("编辑持仓") }
                        TextButton(
                            onClick = { refreshNonce += 1 },
                            enabled = !loading
                        ) { Text(if (loading) "刷新中…" else "刷新") }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("当前市值", style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (rows.isEmpty()) "—" else hopeMoney(totalMarketValue),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("今日浮动", style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (rows.isEmpty()) "—" else hopeSignedMoney(totalDailyChange),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }

                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        holdings.forEach { holding ->
            val quote = quotes[holding.code]
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1.4f)) {
                        Text(quote?.name ?: holding.fallbackName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${holding.code} · ${holding.shares.toInt()}份",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(quote?.let { "%.3f".format(it.price) } ?: "—")
                        Text(
                            quote?.let { hopeMoney(it.price * holding.shares) } ?: "—",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            quote?.let { hopeSignedPercent(it.changePercent) } ?: "—",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            quote?.let { hopeSignedMoney(it.change * holding.shares) } ?: "—",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        Text(
            "行情来自东方财富公开行情接口。持仓数量只保存在本机，可随时修改；这里只做查看，不连接券商、不下单，也不写入记账流水。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showEditHoldings) {
        HopeHoldingsEditor(
            initial = holdings,
            onDismiss = { showEditHoldings = false },
            onSave = { updated ->
                holdings = updated
                holdingStore.save(updated)
                showEditHoldings = false
                refreshNonce += 1
            }
        )
    }
}

private class EditableHolding(
    code: String,
    shares: String,
    name: String
) {
    val id: String = java.util.UUID.randomUUID().toString()
    var code by mutableStateOf(code)
    var shares by mutableStateOf(shares)
    var name by mutableStateOf(name)
}

@Composable
private fun HopeHoldingsEditor(
    initial: List<HopeHolding>,
    onDismiss: () -> Unit,
    onSave: (List<HopeHolding>) -> Unit
) {
    val rows = remember {
        mutableStateListOf<EditableHolding>().apply {
            addAll(initial.map { EditableHolding(it.code, formatHopeShares(it.shares), it.fallbackName) })
        }
    }
    var codeError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 HOPE💹 持仓") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "这里只改“现在持有多少份”，不记录买卖流水。卖光可以删掉，买新 ETF 可以直接新增代码。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = row.code,
                                onValueChange = { next ->
                                    row.code = next.filter(Char::isDigit).take(6)
                                    codeError = false
                                },
                                label = { Text("证券代码") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = row.shares,
                                onValueChange = { next ->
                                    row.shares = next.filter { it.isDigit() || it == '.' }
                                },
                                label = { Text("持有份额") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = row.name,
                                onValueChange = { next -> row.name = next },
                                label = { Text("显示名称（可选）") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { rows.removeAt(index) }) { Text("删除") }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { rows.add(EditableHolding("", "", "")) }) {
                            Text("+ 新增持仓")
                        }
                    }
                }

                if (codeError) {
                    item {
                        Text(
                            "证券代码要填 6 位数字，份额要大于 0。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = rows.mapNotNull { row ->
                        val shares = row.shares.toDoubleOrNull()
                        if (row.code.length == 6 && shares != null && shares > 0.0) {
                            HopeHolding(
                                code = row.code,
                                market = guessHopeMarket(row.code),
                                shares = shares,
                                fallbackName = row.name.ifBlank { row.code }
                            )
                        } else null
                    }
                    if (parsed.size != rows.size || parsed.map { it.code }.distinct().size != parsed.size) {
                        codeError = true
                    } else {
                        onSave(parsed)
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun formatHopeShares(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
