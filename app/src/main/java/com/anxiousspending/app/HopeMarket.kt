package com.anxiousspending.app

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    coroutineScope {
        holdings.map { holding ->
            async {
                runCatching {
                    val secId = "${holding.market}.${holding.code}"
                    val endpoint = "https://push2.eastmoney.com/api/qt/stock/get?invt=2&fltt=2&fields=f43,f57,f58,f60,f169,f170&secid=$secId"
                    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000
                        readTimeout = 6000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                        setRequestProperty("Referer", "https://quote.eastmoney.com/")
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache")
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
            }
        }.awaitAll().filterNotNull().associateBy { it.code }
    }
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
    var showEditHoldings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshQuotes() {
        if (loading) return
        val holdingsSnapshot = holdings
        loading = true
        error = null
        scope.launch {
            try {
                val fresh = runCatching { fetchHopeQuotes(holdingsSnapshot) }.getOrElse { emptyMap() }
                if (fresh.isNotEmpty()) {
                    val merged = quotes + fresh
                    quotes = merged
                    quoteStore.save(merged.values)
                    if (fresh.size < holdingsSnapshot.size) {
                        error = "部分行情没拉到，已保留上次缓存"
                    }
                } else {
                    error = if (quotes.isEmpty()) "行情暂时没拉到，晚点再试" else "刷新失败，仍显示上次缓存"
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshQuotes()
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
                            onClick = { refreshQuotes() },
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
                refreshQuotes()
            }
        )
    }
}

@Composable
private fun HopeHoldingsEditor(
    initial: List<HopeHolding>,
    onDismiss: () -> Unit,
    onSave: (List<HopeHolding>) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var addingNew by remember { mutableStateOf(false) }

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
                        "滚动区只显示轻量卡片；点某一只再单独编辑。这样不会让十几个输入框一起参与滚动。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                itemsIndexed(draft, key = { _, item -> item.code }) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingIndex = index }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.fallbackName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${item.code} · ${formatHopeShares(item.shares)}份",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("编辑", color = MaterialTheme.colorScheme.primary)
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { addingNew = true }) {
                            Text("+ 新增持仓")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    editingIndex?.let { index ->
        val item = draft.getOrNull(index)
        if (item != null) {
            HopeHoldingItemEditor(
                initial = item,
                onDismiss = { editingIndex = null },
                onDelete = {
                    draft = draft.filterIndexed { i, _ -> i != index }
                    editingIndex = null
                },
                onSave = { updated ->
                    if (draft.any { it.code == updated.code && it.code != item.code }) {
                        return@HopeHoldingItemEditor
                    }
                    draft = draft.mapIndexed { i, old -> if (i == index) updated else old }
                    editingIndex = null
                }
            )
        }
    }

    if (addingNew) {
        HopeHoldingItemEditor(
            initial = null,
            onDismiss = { addingNew = false },
            onDelete = null,
            onSave = { added ->
                if (draft.none { it.code == added.code }) {
                    draft = draft + added
                    addingNew = false
                }
            }
        )
    }
}

@Composable
private fun HopeHoldingItemEditor(
    initial: HopeHolding?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (HopeHolding) -> Unit
) {
    var code by remember(initial?.code) { mutableStateOf(initial?.code.orEmpty()) }
    var shares by remember(initial?.code) { mutableStateOf(initial?.let { formatHopeShares(it.shares) }.orEmpty()) }
    var name by remember(initial?.code) { mutableStateOf(initial?.fallbackName.orEmpty()) }
    var error by remember(initial?.code) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增持仓" else "修改持仓") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.filter(Char::isDigit).take(6)
                        error = false
                    },
                    label = { Text("证券代码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = shares,
                    onValueChange = {
                        shares = it.filter { ch -> ch.isDigit() || ch == '.' }
                        error = false
                    },
                    label = { Text("持有份额") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Text(
                        "证券代码要填 6 位数字，份额要大于 0。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = shares.toDoubleOrNull()
                    if (code.length != 6 || amount == null || amount <= 0.0) {
                        error = true
                    } else {
                        onSave(
                            HopeHolding(
                                code = code,
                                market = guessHopeMarket(code),
                                shares = amount,
                                fallbackName = name.ifBlank { code }
                            )
                        )
                    }
                }
            ) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

private fun formatHopeShares(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
