package com.anxiousspending.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private data class AnalyticsWindow(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val label: String
) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)
    val days: Long get() = ChronoUnit.DAYS.between(start, endInclusive) + 1
}

private data class AmountItem(
    val key: String,
    val label: String,
    val amount: Double,
    val secondary: String = ""
)

private data class TrendPoint(val label: String, val amount: Double)

private val analyticsCategoryNames = mapOf(
    "shopping" to "购物",
    "food" to "餐饮",
    "game" to "游戏",
    "ai" to "AI",
    "misc" to "杂费",
    "transport" to "交通",
    "travel" to "旅游",
    "snack" to "零食",
    "books" to "书籍",
    "investment" to "投资",
    "medical" to "医疗",
    "salary" to "工资",
    "red_packet" to "红包",
    "investment_income" to "理财",
    "aa_income" to "AA收入"
)

private val analyticsPaymentNames = mapOf(
    "alipay" to "支付宝",
    "wechat" to "微信",
    "other" to "其他"
)

private fun analyticsCategoryName(key: String): String = analyticsCategoryNames[key] ?: key
private fun analyticsPaymentName(key: String): String = analyticsPaymentNames[key] ?: key
private fun money(value: Double): String = "¥%,.2f".format(value)

private fun pctChange(current: Double, previous: Double): Double? =
    if (previous == 0.0) null else (current - previous) / previous * 100.0

private fun signedMoney(value: Double): String =
    if (value >= 0) "+${money(value)}" else "-${money(abs(value))}"

private fun percentText(value: Double?): String =
    value?.let { "%+.1f%%".format(it) } ?: "—"

private fun stableNotePairs(entries: List<Expense>): Set<Pair<String, String>> =
    entries
        .asSequence()
        .filter { it.entryType == "expense" && it.category != "books" && it.note.trim().isNotEmpty() }
        .groupBy { it.category to it.note.trim() }
        .filterValues { it.size >= 3 }
        .keys

@Composable
fun AnalyticsHub(entries: List<Expense>) {
    val today = LocalDate.now()
    var module by remember { mutableIntStateOf(0) }
    var timeMode by remember { mutableIntStateOf(0) } // 0 month, 1 year, 2 custom
    var selectedMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedYear by remember { mutableIntStateOf(today.year) }

    var startYear by remember { mutableStateOf(today.year.toString()) }
    var startMonth by remember { mutableStateOf("01") }
    var startDay by remember { mutableStateOf("01") }
    var endYear by remember { mutableStateOf(today.year.toString()) }
    var endMonth by remember { mutableStateOf(today.monthValue.toString().padStart(2, '0')) }
    var endDay by remember { mutableStateOf(today.dayOfMonth.toString().padStart(2, '0')) }

    val customStart = parseAnalyticsDate(startYear, startMonth, startDay)
    val customEnd = parseAnalyticsDate(endYear, endMonth, endDay)
    val activeWindow = remember(timeMode, selectedMonth, selectedYear, customStart, customEnd) {
        when (timeMode) {
            0 -> AnalyticsWindow(selectedMonth.atDay(1), selectedMonth.atEndOfMonth(), "${selectedMonth.year}年${selectedMonth.monthValue}月")
            1 -> AnalyticsWindow(LocalDate.of(selectedYear, 1, 1), LocalDate.of(selectedYear, 12, 31), "${selectedYear}年")
            else -> if (customStart != null && customEnd != null && !customEnd.isBefore(customStart)) {
                AnalyticsWindow(customStart, customEnd, "${customStart.format(DateTimeFormatter.ofPattern("yyyy/M/d"))}～${customEnd.format(DateTimeFormatter.ofPattern("yyyy/M/d"))}")
            } else null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AnalyticsModuleButton("数据统计", module == 0, { module = 0 }, Modifier.weight(1f))
            AnalyticsModuleButton("数据分析", module == 1, { module = 1 }, Modifier.weight(1f))
            AnalyticsModuleButton("财报", module == 2, { module = 2 }, Modifier.weight(1f))
        }

        if (module != 2) {
            AnalyticsTimeFilter(
                timeMode = timeMode,
                selectedMonth = selectedMonth,
                selectedYear = selectedYear,
                startParts = Triple(startYear, startMonth, startDay),
                endParts = Triple(endYear, endMonth, endDay),
                onTimeMode = { timeMode = it },
                onMonthPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                onMonthNext = { selectedMonth = selectedMonth.plusMonths(1) },
                onYearPrevious = { selectedYear -= 1 },
                onYearNext = { selectedYear += 1 },
                onStartChanged = { y, m, d -> startYear = y; startMonth = m; startDay = d },
                onEndChanged = { y, m, d -> endYear = y; endMonth = m; endDay = d }
            )
        }

        Box(Modifier.fillMaxSize()) {
            when (module) {
                0 -> StatisticsModule(entries, activeWindow)
                1 -> DataAnalysisModule(entries, activeWindow)
                else -> FinancialReportModule(entries)
            }
        }
    }
}

@Composable
private fun AnalyticsModuleButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(38.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(38.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label) }
    }
}

@Composable
private fun AnalyticsTimeFilter(
    timeMode: Int,
    selectedMonth: YearMonth,
    selectedYear: Int,
    startParts: Triple<String, String, String>,
    endParts: Triple<String, String, String>,
    onTimeMode: (Int) -> Unit,
    onMonthPrevious: () -> Unit,
    onMonthNext: () -> Unit,
    onYearPrevious: () -> Unit,
    onYearNext: () -> Unit,
    onStartChanged: (String, String, String) -> Unit,
    onEndChanged: (String, String, String) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("月", "年", "自定义").forEachIndexed { index, label ->
                    if (timeMode == index) {
                        Button(onClick = { onTimeMode(index) }, modifier = Modifier.weight(1f).height(34.dp), contentPadding = PaddingValues(0.dp)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onTimeMode(index) }, modifier = Modifier.weight(1f).height(34.dp), contentPadding = PaddingValues(0.dp)) { Text(label) }
                    }
                }
            }

            when (timeMode) {
                0 -> AnalyticsPeriodStepper(
                    value = "${selectedMonth.year}年 ${selectedMonth.monthValue}月",
                    onPrevious = onMonthPrevious,
                    onNext = onMonthNext
                )
                1 -> AnalyticsPeriodStepper(
                    value = "${selectedYear}年",
                    onPrevious = onYearPrevious,
                    onNext = onYearNext
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("从", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
                        AnalyticsDateInput(startParts) { y, m, d -> onStartChanged(y, m, d) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("到", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
                        AnalyticsDateInput(endParts) { y, m, d -> onEndChanged(y, m, d) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsPeriodStepper(value: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious, modifier = Modifier.width(56.dp)) { Text("‹") }
        Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        TextButton(onClick = onNext, modifier = Modifier.width(56.dp)) { Text("›") }
    }
}

@Composable
private fun AnalyticsDateInput(parts: Triple<String, String, String>, onChanged: (String, String, String) -> Unit) {
    val (year, month, day) = parts
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnalyticsMiniField(year, 4, 66) { onChanged(it, month, day) }
        Text("/", modifier = Modifier.padding(horizontal = 2.dp))
        AnalyticsMiniField(month, 2, 42) { onChanged(year, it, day) }
        Text("/", modifier = Modifier.padding(horizontal = 2.dp))
        AnalyticsMiniField(day, 2, 42) { onChanged(year, month, it) }
    }
}

@Composable
private fun AnalyticsMiniField(value: String, maxDigits: Int, width: Int, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier.width(width.dp).height(32.dp).border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxDigits)) },
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp)
        )
    }
}

private fun parseAnalyticsDate(y: String, m: String, d: String): LocalDate? {
    if (y.length != 4 || m.isBlank() || d.isBlank()) return null
    return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
}

@Composable
private fun StatisticsModule(entries: List<Expense>, window: AnalyticsWindow?) {
    if (window == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("自定义日期还没填对。") }
        return
    }

    val allExpenses = remember(entries) { entries.filter { it.entryType == "expense" } }
    val stablePairs = remember(allExpenses) { stableNotePairs(allExpenses) }
    val activeExpenses = remember(allExpenses, window) { allExpenses.filter { window.contains(it.date) } }
    val activeIncome = remember(entries, window) { entries.filter { it.entryType == "income" && window.contains(it.date) } }

    var selectedCategory by remember(window) { mutableStateOf<String?>(null) }
    var detailNote by remember(window) { mutableStateOf<String?>(null) }
    var globalDetailNote by remember(window) { mutableStateOf<String?>(null) }
    var categoryView by remember(window) { mutableIntStateOf(0) }
    var trendNote by remember(window) { mutableStateOf<String?>(null) }

    when {
        globalDetailNote != null -> {
            RawLedgerDrilldown(
                title = globalDetailNote ?: "明细",
                entries = activeExpenses.filter { it.note.trim() == globalDetailNote },
                onBack = { globalDetailNote = null }
            )
        }
        selectedCategory != null && detailNote != null -> {
            RawLedgerDrilldown(
                title = "${analyticsCategoryName(selectedCategory!!)} · $detailNote",
                entries = activeExpenses.filter { it.category == selectedCategory && it.note.trim() == detailNote },
                onBack = { detailNote = null }
            )
        }
        selectedCategory != null -> {
            CategorySecondLayer(
                category = selectedCategory!!,
                activeExpenses = activeExpenses.filter { it.category == selectedCategory },
                stablePairs = stablePairs,
                window = window,
                view = categoryView,
                trendNote = trendNote,
                onView = { categoryView = it },
                onTrendNote = { trendNote = it },
                onNoteDetail = { detailNote = it },
                onBack = { selectedCategory = null; detailNote = null; trendNote = null }
            )
        }
        else -> {
            val categoryTotals = activeExpenses
                .groupBy { it.category }
                .map { (key, list) -> AmountItem(key, analyticsCategoryName(key), list.sumOf { it.cnyAmount }) }
                .sortedByDescending { it.amount }

            val globalNoteTotals = activeExpenses
                .filter { (it.category to it.note.trim()) in stablePairs }
                .groupBy { it.note.trim() }
                .map { (note, list) -> AmountItem(note, note, list.sumOf { it.cnyAmount }, "${list.size}笔") }
                .sortedByDescending { it.amount }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    AnalyticsOverviewCard(
                        label = window.label,
                        expense = activeExpenses.sumOf { it.cnyAmount },
                        income = activeIncome.sumOf { it.cnyAmount },
                        count = activeExpenses.size
                    )
                }
                item {
                    AnalyticsSectionCard("分类结构", "点分类进入第二层") {
                        if (categoryTotals.isEmpty()) Text("这个时间段还没有支出。")
                        else CategoryPieChart(categoryTotals) { selectedCategory = it }
                    }
                }
                item {
                    AnalyticsSectionCard("全局常用备注", "真实出现过 3 次及以上 · 拉通所有分类") {
                        if (globalNoteTotals.isEmpty()) Text("这个时间段还没有可统计的常用备注。")
                        else AmountBarList(globalNoteTotals) { clicked ->
                            val categories = activeExpenses
                                .filter { it.note.trim() == clicked.key && (it.category to it.note.trim()) in stablePairs }
                                .map { it.category }
                                .distinct()
                            if (categories.size == 1 && categories.first() != "books") {
                                selectedCategory = categories.first()
                                categoryView = 1
                                trendNote = clicked.key
                            } else {
                                globalDetailNote = clicked.key
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsOverviewCard(label: String, expense: Double, income: Double, count: Int) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(money(expense), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("支出 $count 笔", style = MaterialTheme.typography.bodySmall)
                Text("收入 ${money(income)} · 结余 ${money(income - expense)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AnalyticsSectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun CategoryPieChart(items: List<AmountItem>, onClick: (String) -> Unit) {
    val total = items.sumOf { it.amount }.takeIf { it > 0 } ?: 1.0
    val palette = listOf(
        Color(0xFF4E657A), Color(0xFF7B6F90), Color(0xFF9A6C68), Color(0xFF67836D),
        Color(0xFF9A855F), Color(0xFF6C7A95), Color(0xFF8A6E7C), Color(0xFF6F8C8C),
        Color(0xFF8B7C68), Color(0xFF5F6F62), Color(0xFF87736B)
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(142.dp)) {
            var start = -90f
            items.forEachIndexed { index, item ->
                val sweep = (item.amount / total * 360.0).toFloat()
                drawArc(
                    color = palette[index % palette.size],
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = true
                )
                start += sweep
            }
            drawCircle(color = Color.White.copy(alpha = 0.92f), radius = size.minDimension * 0.19f)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onClick(item.key) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).background(palette[index % palette.size], RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(7.dp))
                    Text(item.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("%.1f%%".format(item.amount / total * 100.0), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AmountBarList(items: List<AmountItem>, onClick: ((AmountItem) -> Unit)? = null) {
    val maxAmount = items.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        items.forEach { item ->
            Column(
                Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick(item) } else Modifier),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (item.secondary.isNotBlank()) {
                        Text(item.secondary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(money(item.amount), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(99.dp))) {
                    Box(
                        Modifier.fillMaxWidth((item.amount / maxAmount).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(99.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoricalNoteLineChart(items: List<AmountItem>, onNoteClick: (String) -> Unit) {
    if (items.isEmpty()) return
    val maxValue = items.maxOf { it.amount }.takeIf { it > 0.0 } ?: 1.0
    val axis = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(money(maxValue), style = MaterialTheme.typography.labelSmall)
            Text("金额", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(176.dp)) {
            val left = 8f
            val right = size.width - 8f
            val top = 8f
            val bottom = size.height - 8f
            drawLine(axis, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.2f)
            drawLine(axis, Offset(left, top), Offset(left, bottom), strokeWidth = 1.2f)
            val points = items.mapIndexed { index, item ->
                val x = if (items.size == 1) (left + right) / 2f else left + (right - left) * index / (items.size - 1).toFloat()
                val y = bottom - (bottom - top) * (item.amount / maxValue).toFloat()
                Offset(x, y)
            }
            points.zipWithNext().forEach { (a, b) -> drawLine(line, a, b, strokeWidth = 4f, cap = StrokeCap.Round) }
            points.forEach { point -> drawCircle(line, radius = 5f, center = point) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                Column(
                    modifier = Modifier.weight(1f).clickable { onNoteClick(item.key) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(item.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
                    Text(money(item.amount), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun CategorySecondLayer(
    category: String,
    activeExpenses: List<Expense>,
    stablePairs: Set<Pair<String, String>>,
    window: AnalyticsWindow,
    view: Int,
    trendNote: String?,
    onView: (Int) -> Unit,
    onTrendNote: (String?) -> Unit,
    onNoteDetail: (String) -> Unit,
    onBack: () -> Unit
) {
    if (category == "books") {
        RawLedgerDrilldown("书籍 · ${window.label}", activeExpenses, onBack)
        return
    }

    val stableNotes = activeExpenses
        .filter { (category to it.note.trim()) in stablePairs }
        .map { it.note.trim() }
        .distinct()
        .sorted()

    val noteTotals = activeExpenses
        .filter { (category to it.note.trim()) in stablePairs }
        .groupBy { it.note.trim() }
        .map { (note, list) -> AmountItem(note, note, list.sumOf { it.cnyAmount }, "${list.size}笔") }
        .sortedByDescending { it.amount }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹ 返回") }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(analyticsCategoryName(category), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(window.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsModuleButton("A · 备注金额", view == 0, { onView(0) }, Modifier.weight(1f))
                AnalyticsModuleButton("B · 时间趋势", view == 1, { onView(1) }, Modifier.weight(1f))
            }
        }
        if (view == 0) {
            item {
                AnalyticsSectionCard("常用备注金额对比", "x 轴是备注 · y 轴是金额") {
                    if (noteTotals.isEmpty()) Text("这个时间段没有可统计的常用备注。")
                    else CategoricalNoteLineChart(noteTotals, onNoteDetail)
                }
            }
        } else {
            item {
                AnalyticsSectionCard("常用备注时间趋势", "x 轴是时间 · y 轴是金额") {
                    TrendNoteSelector(stableNotes, trendNote, onTrendNote)
                    val trendEntries = if (trendNote == null) activeExpenses else activeExpenses.filter { it.note.trim() == trendNote }
                    val points = buildTrendPoints(trendEntries, window)
                    Spacer(Modifier.height(4.dp))
                    AnalyticsLineChart(points)
                }
            }
        }
    }
}

@Composable
private fun TrendNoteSelector(notes: List<String>, selected: String?, onSelected: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected == null) "常用备注：全部" else "常用备注：$selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部") }, onClick = { onSelected(null); expanded = false })
            notes.forEach { note ->
                DropdownMenuItem(text = { Text(note) }, onClick = { onSelected(note); expanded = false })
            }
        }
    }
}

private fun buildTrendPoints(entries: List<Expense>, window: AnalyticsWindow): List<TrendPoint> {
    val byDay = window.days <= 62
    return if (byDay) {
        generateSequence(window.start) { current -> current.plusDays(1).takeIf { !it.isAfter(window.endInclusive) } }
            .map { day -> TrendPoint(day.format(DateTimeFormatter.ofPattern("M/d")), entries.filter { it.date == day }.sumOf { it.cnyAmount }) }
            .toList()
    } else {
        val startMonth = YearMonth.from(window.start)
        val endMonth = YearMonth.from(window.endInclusive)
        generateSequence(startMonth) { current -> current.plusMonths(1).takeIf { !it.isAfter(endMonth) } }
            .map { month ->
                TrendPoint(
                    month.format(DateTimeFormatter.ofPattern("yy/M")),
                    entries.filter { YearMonth.from(it.date) == month }.sumOf { it.cnyAmount }
                )
            }
            .toList()
    }
}

@Composable
private fun AnalyticsLineChart(points: List<TrendPoint>) {
    if (points.isEmpty()) {
        Text("暂无趋势数据。")
        return
    }
    val maxValue = points.maxOf { it.amount }.takeIf { it > 0.0 } ?: 1.0
    val axis = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(money(maxValue), style = MaterialTheme.typography.labelSmall)
            Text("峰值", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(190.dp)) {
            val left = 6f
            val right = size.width - 6f
            val top = 8f
            val bottom = size.height - 8f
            drawLine(axis, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.2f)
            drawLine(axis, Offset(left, top), Offset(left, bottom), strokeWidth = 1.2f)

            val coordinates = points.mapIndexed { index, point ->
                val x = if (points.size == 1) (left + right) / 2f else left + (right - left) * index / (points.size - 1).toFloat()
                val y = bottom - (bottom - top) * (point.amount / maxValue).toFloat()
                Offset(x, y)
            }
            coordinates.zipWithNext().forEach { (a, b) -> drawLine(line, a, b, strokeWidth = 4f, cap = StrokeCap.Round) }
            coordinates.forEach { point -> drawCircle(line, radius = 4.5f, center = point) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().label, style = MaterialTheme.typography.labelSmall)
            if (points.size > 2) Text(points[points.size / 2].label, style = MaterialTheme.typography.labelSmall)
            Text(points.last().label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RawLedgerDrilldown(title: String, entries: List<Expense>, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹ 返回") }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${entries.size} 笔 · ${money(entries.sumOf { it.cnyAmount })}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (entries.isEmpty()) {
            item { Text("没有对应账单。") }
        } else {
            items(entries.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.cnyAmount }), key = { it.id }) { entry ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.note.ifBlank { analyticsCategoryName(entry.category) }, fontWeight = FontWeight.SemiBold)
                            Text("${entry.date} · ${analyticsCategoryName(entry.category)} · ${analyticsPaymentName(entry.paymentSource)}", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(money(entry.cnyAmount), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DataAnalysisModule(entries: List<Expense>, window: AnalyticsWindow?) {
    if (window == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("自定义日期还没填对。") }
        return
    }

    val expenses = remember(entries) { entries.filter { it.entryType == "expense" } }
    val stablePairs = remember(expenses) { stableNotePairs(expenses) }
    val current = expenses.filter { window.contains(it.date) }
    val previousWindow = previousPeriod(window)
    val yearAgoWindow = AnalyticsWindow(window.start.minusYears(1), window.endInclusive.minusYears(1), "去年同期")
    val previous = expenses.filter { previousWindow.contains(it.date) }
    val yearAgo = expenses.filter { yearAgoWindow.contains(it.date) }

    val currentTotal = current.sumOf { it.cnyAmount }
    val previousTotal = previous.sumOf { it.cnyAmount }
    val yearAgoTotal = yearAgo.sumOf { it.cnyAmount }

    val attribution = categoryDeltaItems(current, previous)
    val freqUnit = frequencyUnitItems(current, previous, stablePairs)
    val anomalies = anomalyItems(expenses, current, window)
    val stability = stabilityItems(expenses, stablePairs, window.endInclusive)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AnalyticsSectionCard("${window.label} · 分析", "分析负责找变化，不重复统计看板") {
                Text(money(currentTotal), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComparisonMiniCard("环比", currentTotal - previousTotal, pctChange(currentTotal, previousTotal), Modifier.weight(1f))
                    ComparisonMiniCard("同比", currentTotal - yearAgoTotal, pctChange(currentTotal, yearAgoTotal), Modifier.weight(1f))
                }
            }
        }
        item {
            AnalyticsSectionCard("变化归因", "和上一期相比，哪些分类把总额推高或拉低") {
                if (attribution.isEmpty()) Text("暂无足够数据。")
                else DeltaList(attribution)
            }
        }
        item {
            AnalyticsSectionCard("频率 × 单价", "常用备注的变化，到底是买勤了还是买贵了") {
                if (freqUnit.isEmpty()) Text("暂无可比较的常用备注。")
                else freqUnit.take(8).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    if (line != freqUnit.take(8).last()) Divider()
                }
            }
        }
        item {
            AnalyticsSectionCard("异常偏离", "只和你自己的历史基线比，不给你发消费道德奖状") {
                if (anomalies.isEmpty()) Text("暂时没找到明显偏离，或者历史数据不足。")
                else anomalies.take(6).forEach { item ->
                    Text(item, style = MaterialTheme.typography.bodySmall)
                    if (item != anomalies.take(6).last()) Divider()
                }
            }
        }
        item {
            AnalyticsSectionCard("稳定性分析", "过去 12 个月里哪些常用备注最稳定、哪些金额最爱蹦") {
                if (stability.isEmpty()) Text("常用备注数据还不够。")
                else stability.take(8).forEach { item ->
                    Text(item, style = MaterialTheme.typography.bodySmall)
                    if (item != stability.take(8).last()) Divider()
                }
            }
        }
    }
}

@Composable
private fun ComparisonMiniCard(title: String, delta: Double, percent: Double?, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            Text(percentText(percent), fontWeight = FontWeight.Bold)
            Text(signedMoney(delta), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun previousPeriod(window: AnalyticsWindow): AnalyticsWindow {
    val end = window.start.minusDays(1)
    val start = end.minusDays(window.days - 1)
    return AnalyticsWindow(start, end, "上一期")
}

private fun categoryDeltaItems(current: List<Expense>, previous: List<Expense>): List<AmountItem> {
    val currentMap = current.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.cnyAmount } }
    val previousMap = previous.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.cnyAmount } }
    return (currentMap.keys + previousMap.keys).distinct().map { key ->
        val c = currentMap[key] ?: 0.0
        val p = previousMap[key] ?: 0.0
        AmountItem(key, analyticsCategoryName(key), c - p, "${money(p)} → ${money(c)}")
    }.filter { abs(it.amount) > 0.005 }.sortedByDescending { abs(it.amount) }.take(8)
}

@Composable
private fun DeltaList(items: List<AmountItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(item.secondary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(signedMoney(item.amount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun frequencyUnitItems(
    current: List<Expense>,
    previous: List<Expense>,
    stablePairs: Set<Pair<String, String>>
): List<String> {
    val notes = (current + previous)
        .filter { (it.category to it.note.trim()) in stablePairs }
        .map { it.category to it.note.trim() }
        .distinct()

    return notes.mapNotNull { pair ->
        val c = current.filter { it.category == pair.first && it.note.trim() == pair.second }
        val p = previous.filter { it.category == pair.first && it.note.trim() == pair.second }
        if (c.isEmpty() && p.isEmpty()) return@mapNotNull null
        val cAvg = if (c.isEmpty()) 0.0 else c.sumOf { it.cnyAmount } / c.size
        val pAvg = if (p.isEmpty()) 0.0 else p.sumOf { it.cnyAmount } / p.size
        val countDelta = c.size - p.size
        val avgDelta = cAvg - pAvg
        val driver = when {
            abs(countDelta.toDouble()) >= 1 && abs(avgDelta) < max(1.0, pAvg * 0.12) -> "主要是频率变化"
            abs(avgDelta) >= max(1.0, pAvg * 0.12) && countDelta == 0 -> "主要是单次金额变化"
            else -> "频率和单次金额都在动"
        }
        Triple(abs(c.sumOf { it.cnyAmount } - p.sumOf { it.cnyAmount }), pair, "${pair.second} · ${analyticsCategoryName(pair.first)}\n${p.size} → ${c.size} 次，单次 ${money(pAvg)} → ${money(cAvg)}，$driver")
    }.sortedByDescending { it.first }.map { it.third }
}

private fun anomalyItems(expenses: List<Expense>, current: List<Expense>, window: AnalyticsWindow): List<String> {
    val baselines = buildBaselineWindows(window)
    if (baselines.isEmpty()) return emptyList()
    val categories = current.map { it.category }.distinct()
    return categories.mapNotNull { category ->
        val currentAmount = current.filter { it.category == category }.sumOf { it.cnyAmount }
        val values = baselines.map { base -> expenses.filter { it.category == category && base.contains(it.date) }.sumOf { it.cnyAmount } }
        val nonZeroHistory = values.count { it > 0.0 }
        if (nonZeroHistory < 2) return@mapNotNull null
        val average = values.average()
        if (average <= 0.0) return@mapNotNull null
        val deviation = (currentAmount - average) / average * 100.0
        if (abs(deviation) < 35.0 && abs(currentAmount - average) < 100.0) return@mapNotNull null
        Triple(abs(deviation), category, "${analyticsCategoryName(category)} ${money(currentAmount)}，历史同尺度均值 ${money(average)}，偏离 ${"%+.0f%%".format(deviation)}")
    }.sortedByDescending { it.first }.map { it.third }
}

private fun buildBaselineWindows(window: AnalyticsWindow): List<AnalyticsWindow> {
    return if (window.start.dayOfMonth == 1 && window.endInclusive == YearMonth.from(window.endInclusive).atEndOfMonth() && YearMonth.from(window.start) == YearMonth.from(window.endInclusive)) {
        val ym = YearMonth.from(window.start)
        (1..6).map { offset ->
            val month = ym.minusMonths(offset.toLong())
            AnalyticsWindow(month.atDay(1), month.atEndOfMonth(), month.toString())
        }
    } else if (window.start.monthValue == 1 && window.start.dayOfMonth == 1 && window.endInclusive.monthValue == 12 && window.endInclusive.dayOfMonth == 31) {
        (1..3).map { offset ->
            val year = window.start.year - offset
            AnalyticsWindow(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), year.toString())
        }
    } else {
        (1..4).map { offset ->
            val end = window.start.minusDays(1 + (window.days * (offset - 1)))
            val start = end.minusDays(window.days - 1)
            AnalyticsWindow(start, end, "")
        }
    }
}

private fun stabilityItems(expenses: List<Expense>, stablePairs: Set<Pair<String, String>>, endDate: LocalDate): List<String> {
    val endMonth = YearMonth.from(endDate)
    val months = (0..11).map { endMonth.minusMonths(it.toLong()) }.reversed()
    return stablePairs.mapNotNull { pair ->
        val values = months.map { month ->
            expenses.filter { it.category == pair.first && it.note.trim() == pair.second && YearMonth.from(it.date) == month }.sumOf { it.cnyAmount }
        }
        val present = values.count { it > 0.0 }
        if (present == 0) return@mapNotNull null
        val activeValues = values.filter { it > 0.0 }
        val mean = activeValues.average()
        val variance = if (activeValues.size <= 1) 0.0 else activeValues.sumOf { (it - mean) * (it - mean) } / activeValues.size
        val cv = if (mean == 0.0) 0.0 else sqrt(variance) / mean
        val amountLabel = when {
            cv <= 0.25 -> "金额很稳"
            cv <= 0.60 -> "金额有波动"
            else -> "金额波动大"
        }
        Triple(present, -cv, "${pair.second} · ${analyticsCategoryName(pair.first)}：过去12个月出现 $present 个月，$amountLabel")
    }.sortedWith(compareByDescending<Triple<Int, Double, String>> { it.first }.thenByDescending { it.second }).map { it.third }
}

@Composable
private fun FinancialReportModule(entries: List<Expense>) {
    val today = LocalDate.now()
    var year by remember { mutableIntStateOf(today.year) }
    var halfDialog by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val yearEntries = entries.filter { it.date.year == year }
    val expenses = yearEntries.filter { it.entryType == "expense" }
    val incomes = yearEntries.filter { it.entryType == "income" }
    val summary = buildReportSummary(entries, year, 1, 12)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AnalyticsSectionCard("ABC王国责任不怎么有但一定爽公司", "定期财务披露专区") {
                AnalyticsPeriodStepper("${year}年度", { year -= 1 }, { year += 1 })
            }
        }
        item {
            AnalyticsSectionCard("半年度财务报告", "应用内弹窗 · 看完就走，不额外生成图片") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { halfDialog = 1 }, modifier = Modifier.weight(1f)) { Text("上半年") }
                    OutlinedButton(onClick = { halfDialog = 2 }, modifier = Modifier.weight(1f)) { Text("下半年") }
                }
            }
        }
        item {
            AnalyticsSectionCard("${year}年度财务报告", "年度正式版 · 一图流 PNG") {
                Text("全年收入 ${money(incomes.sumOf { it.cnyAmount })}", style = MaterialTheme.typography.bodySmall)
                Text("全年支出 ${money(expenses.sumOf { it.cnyAmount })}", style = MaterialTheme.typography.bodySmall)
                Text("净结余 ${money(incomes.sumOf { it.cnyAmount } - expenses.sumOf { it.cnyAmount })}", style = MaterialTheme.typography.bodySmall)
                if (summary.topCategories.isNotEmpty()) {
                    Divider()
                    Text("支出分类 TOP", fontWeight = FontWeight.SemiBold)
                    summary.topCategories.take(3).forEachIndexed { index, item -> Text("${index + 1}. ${item.label}  ${money(item.amount)}") }
                }
                Button(
                    enabled = yearEntries.isNotEmpty(),
                    onClick = {
                        val result = saveAnnualReportImage(context, entries, year)
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "年度财报已保存到相册 📈" else "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存年度一图流 PNG") }
            }
        }
    }

    halfDialog?.let { half ->
        val startMonth = if (half == 1) 1 else 7
        val endMonth = if (half == 1) 6 else 12
        val halfSummary = buildReportSummary(entries, year, startMonth, endMonth)
        HalfYearReportDialog(year, half, halfSummary) { halfDialog = null }
    }
}

private data class ReportSummary(
    val income: Double,
    val expense: Double,
    val topCategories: List<AmountItem>,
    val topNotes: List<AmountItem>,
    val channels: List<AmountItem>,
    val peakMonth: Pair<Int, Double>?,
    val maxExpense: Expense?,
    val recordCount: Int
)

private fun buildReportSummary(entries: List<Expense>, year: Int, startMonth: Int, endMonth: Int): ReportSummary {
    val periodEntries = entries.filter { it.date.year == year && it.date.monthValue in startMonth..endMonth }
    val expenses = periodEntries.filter { it.entryType == "expense" }
    val income = periodEntries.filter { it.entryType == "income" }.sumOf { it.cnyAmount }
    val stablePairs = stableNotePairs(entries.filter { it.entryType == "expense" })
    val topCategories = expenses.groupBy { it.category }.map { (key, list) ->
        AmountItem(key, analyticsCategoryName(key), list.sumOf { it.cnyAmount })
    }.sortedByDescending { it.amount }
    val topNotes = expenses.filter { (it.category to it.note.trim()) in stablePairs }
        .groupBy { it.note.trim() }
        .map { (note, list) -> AmountItem(note, note, list.sumOf { it.cnyAmount }, "${list.size}笔") }
        .sortedByDescending { it.amount }
    val channels = expenses.groupBy { it.paymentSource }.map { (key, list) ->
        AmountItem(key, analyticsPaymentName(key), list.sumOf { it.cnyAmount })
    }.sortedByDescending { it.amount }
    val peakMonth = (startMonth..endMonth)
        .map { month -> month to expenses.filter { it.date.monthValue == month }.sumOf { it.cnyAmount } }
        .maxByOrNull { it.second }
        ?.takeIf { it.second > 0.0 }
    return ReportSummary(
        income = income,
        expense = expenses.sumOf { it.cnyAmount },
        topCategories = topCategories,
        topNotes = topNotes,
        channels = channels,
        peakMonth = peakMonth,
        maxExpense = expenses.maxByOrNull { it.cnyAmount },
        recordCount = periodEntries.size
    )
}

@Composable
private fun HalfYearReportDialog(year: Int, half: Int, summary: ReportSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${year}年${if (half == 1) "上" else "下"}半年财务报告") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("ABC王国责任不怎么有但一定爽公司", fontWeight = FontWeight.Bold)
                    Text("收入 ${money(summary.income)} · 支出 ${money(summary.expense)} · 结余 ${money(summary.income - summary.expense)}")
                }
                if (summary.topCategories.isNotEmpty()) {
                    item { Text("成本结构 TOP3", fontWeight = FontWeight.Bold) }
                    items(summary.topCategories.take(3)) { item -> Text("${item.label} · ${money(item.amount)}") }
                }
                if (summary.topNotes.isNotEmpty()) {
                    item { Text("重点项目 TOP3", fontWeight = FontWeight.Bold) }
                    items(summary.topNotes.take(3)) { item -> Text("${item.label} · ${money(item.amount)}") }
                }
                if (summary.channels.isNotEmpty()) {
                    item { Text("资金渠道", fontWeight = FontWeight.Bold) }
                    items(summary.channels) { item -> Text("${item.label} · ${money(item.amount)}") }
                }
                summary.peakMonth?.let { peak -> item { Text("阶段性支出高峰：${peak.first}月 · ${money(peak.second)}") } }
                summary.maxExpense?.let { maxEntry -> item { Text("最大单笔：${money(maxEntry.cnyAmount)} · ${analyticsCategoryName(maxEntry.category)} · ${maxEntry.date}") } }
                item { Text("本期共记录 ${summary.recordCount} 笔。责任承担有限，爽感投入继续接受股东本人监督。") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("本股东已阅") } }
    )
}

private fun saveAnnualReportImage(context: Context, entries: List<Expense>, year: Int): Result<Unit> = runCatching {
    val summary = buildReportSummary(entries, year, 1, 12)
    val previous = buildReportSummary(entries, year - 1, 1, 12)
    val width = 1080
    val height = 3300
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(AndroidColor.rgb(248, 247, 243))

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(28, 28, 30) }
    val secondary = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(95, 95, 100) }
    var y = 130f

    fun text(value: String, size: Float, bold: Boolean = false, gap: Float = 18f) {
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(value.take(42), 86f, y, paint)
        y += size + gap
    }
    fun small(value: String, gap: Float = 14f) {
        secondary.textSize = 30f
        secondary.typeface = Typeface.DEFAULT
        canvas.drawText(value.take(58), 86f, y, secondary)
        y += 44f + gap
    }
    fun divider() {
        y += 18f
        paint.strokeWidth = 2f
        paint.color = AndroidColor.rgb(215, 213, 207)
        canvas.drawLine(86f, y, 994f, y, paint)
        paint.color = AndroidColor.rgb(28, 28, 30)
        y += 54f
    }
    fun rankedSection(title: String, items: List<AmountItem>, total: Double) {
        text(title, 42f, true, 24f)
        val maxAmount = items.take(5).maxOfOrNull { it.amount }?.takeIf { it > 0.0 } ?: 1.0
        items.take(5).forEachIndexed { index, item ->
            text("${index + 1}. ${item.label}", 32f, true, 8f)
            small("${money(item.amount)}   ${if (total > 0) "%.1f%%".format(item.amount / total * 100.0) else "—"}", 6f)
            val barWidth = (760f * (item.amount / maxAmount)).toFloat().coerceAtLeast(8f)
            paint.color = AndroidColor.rgb(70, 82, 92)
            canvas.drawRoundRect(86f, y, 86f + barWidth, y + 18f, 9f, 9f, paint)
            paint.color = AndroidColor.rgb(28, 28, 30)
            y += 42f
        }
    }

    text("ABC王国责任不怎么有但一定爽公司", 52f, true, 14f)
    small("${year}年度财务报告 · Annual Financial Report", 42f)
    text("年度经营总览", 42f, true, 22f)
    text("全年支出  ${money(summary.expense)}", 58f, true, 14f)
    small("全年收入 ${money(summary.income)}   净结余 ${money(summary.income - summary.expense)}")
    val yoy = pctChange(summary.expense, previous.expense)
    small("较${year - 1}年：${signedMoney(summary.expense - previous.expense)}（${percentText(yoy)}）")
    divider()

    rankedSection("成本结构 · 分类 TOP", summary.topCategories, summary.expense)
    divider()
    rankedSection("重点项目 · 常用备注 TOP", summary.topNotes, summary.expense)
    divider()
    rankedSection("资金渠道结构", summary.channels, summary.expense)
    divider()

    text("年度波动与重大事项", 42f, true, 22f)
    summary.peakMonth?.let { small("支出峰值月份：${it.first}月   ${money(it.second)}") }
    summary.maxExpense?.let { small("最大单笔：${money(it.cnyAmount)}   ${analyticsCategoryName(it.category)}   ${it.date}") }
    small("全年共记录 ${summary.recordCount} 笔")
    divider()

    text("董事会结语", 42f, true, 22f)
    small("本年度公司继续坚持“该花就花，花完再看”的基本经营方针。")
    small("在责任承担有限的前提下，持续提升爽感投入效率。")
    small("以上数据全部来自本公司真实账本。")

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "ABC_${year}_年度财务报告.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ABC王国财报")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("系统没有返回保存位置")
    resolver.openOutputStream(uri)?.use { stream ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) error("PNG 写入失败")
    } ?: error("无法打开保存位置")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    bitmap.recycle()
}
