package com.anxiousspending.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expenseStore = ExpenseStore(this)
        val noteTagStore = NoteTagStore(this)
        val exchangeRateStore = ExchangeRateStore(this)
        setContent {
            MaterialTheme {
                AnxiousSpendingApp(expenseStore, noteTagStore, exchangeRateStore)
            }
        }
    }
}

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val amount: Double,
    val category: String,
    val note: String,
    val date: LocalDate,
    val currency: String = "CNY",
    val exchangeRateToCny: Double = 1.0,
    val cnyAmount: Double = amount,
    val paymentSource: String = "alipay",
    val entryType: String = "expense"
)

data class CategoryOption(val key: String, val name: String, val subtitle: String)
private data class CurrencyOption(val code: String, val name: String, val symbol: String)
private data class PaymentSourceOption(val key: String, val name: String, val dot: String, val color: Color)
internal data class NoteSuggestion(
    val entryType: String,
    val category: String,
    val text: String,
    val count: Int,
    val clickCount: Int,
    val lastUsed: LocalDate
)

private val expenseCategories = listOf(
    CategoryOption("shopping", "购物", "BUY STH NEW"),
    CategoryOption("food", "餐饮", "EATING"),
    CategoryOption("game", "游戏", "婷芷我说婷芷"),
    CategoryOption("entertainment", "娱乐", "LET\'S PARTY"),
    CategoryOption("ai", "AI", "别BAN我"),
    CategoryOption("misc", "杂费", "生活杂费"),
    CategoryOption("transport", "交通", "🚈🚕🚌嘟嘟"),
    CategoryOption("travel", "旅游", "GO GO GO出发喽"),
    CategoryOption("snack", "零食", "STOP EAT"),
    CategoryOption("books", "书籍", "今天你看书了吗"),
    CategoryOption("investment", "投资", "HOPE💹"),
    CategoryOption("medical", "医疗", "今天哪里又痛了我的大小姐")
)

private val incomeCategories = listOf(
    CategoryOption("salary", "工资", "钱来"),
    CategoryOption("red_packet", "红包", "意外收获"),
    CategoryOption("investment_income", "理财", "HOPE一直💹"),
    CategoryOption("aa_income", "AA收入", "没有惊喜的一笔钱")
)

private val currencies = listOf(
    CurrencyOption("CNY", "人民币", "¥"),
    CurrencyOption("USD", "美元", "$"),
    CurrencyOption("KRW", "韩元", "₩"),
    CurrencyOption("JPY", "日元", "¥")
)

private val paymentSources = listOf(
    PaymentSourceOption("alipay", "支付宝", "●", Color(0xFF1677FF)),
    PaymentSourceOption("wechat", "微信", "●", Color(0xFF07C160)),
    PaymentSourceOption("other", "其他", "●", Color(0xFF9E9E9E))
)

private fun categoriesFor(entryType: String): List<CategoryOption> =
    if (entryType == "income") incomeCategories else expenseCategories

private fun categoryOption(key: String): CategoryOption? =
    (expenseCategories + incomeCategories).firstOrNull { it.key == key }

private fun categorySubtitle(key: String): String = categoryOption(key)?.subtitle ?: key
private fun categoryName(key: String): String = categoryOption(key)?.name ?: key

private fun currencyOption(code: String): CurrencyOption =
    currencies.firstOrNull { it.code == code } ?: currencies.first()

private fun paymentSourceOption(key: String): PaymentSourceOption =
    paymentSources.firstOrNull { it.key == key } ?: paymentSources.last()

private fun formatAmount(value: Double): String {
    val fixed = "%.2f".format(value)
    return fixed.trimEnd('0').trimEnd('.')
}

private fun formatOriginal(entry: Expense): String {
    val option = currencyOption(entry.currency)
    val prefix = if (entry.entryType == "income") "+" else ""
    return if (entry.currency == "CNY") {
        "$prefix¥${formatAmount(entry.amount)}"
    } else {
        "$prefix${option.code} ${option.symbol}${formatAmount(entry.amount)}"
    }
}

private fun parseDateParts(year: String, month: String, day: String): LocalDate? {
    if (year.length != 4 || month.isBlank() || day.isBlank()) return null
    return runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
}

private fun formatRateTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))

private fun buildNoteSuggestions(
    entries: List<Expense>,
    clickCounts: Map<String, Int>
): List<NoteSuggestion> =
    entries
        .filter { it.note.trim().isNotEmpty() }
        .groupBy { Triple(it.entryType, it.category, it.note.trim()) }
        .map { (key, matches) ->
            NoteSuggestion(
                entryType = key.first,
                category = key.second,
                text = key.third,
                count = matches.size,
                clickCount = clickCounts[NoteTagStore.keyFor(key.second, key.third)] ?: 0,
                lastUsed = matches.maxOf { it.date }
            )
        }
        .filter { it.count >= 3 }
        .sortedWith(
            compareByDescending<NoteSuggestion> { it.clickCount }
                .thenByDescending { it.count }
                .thenByDescending { it.lastUsed }
                .thenBy { it.text }
        )

private fun evaluateExpression(raw: String): Double? {
    val expression = raw.replace("×", "*").replace("÷", "/").replace(" ", "")
    if (expression.isBlank()) return null
    val numbers = mutableListOf<Double>()
    val operators = mutableListOf<Char>()
    var index = 0

    fun precedence(op: Char): Int = if (op == '*' || op == '/') 2 else 1
    fun applyTop(): Boolean {
        if (numbers.size < 2 || operators.isEmpty()) return false
        val b = numbers.removeAt(numbers.lastIndex)
        val a = numbers.removeAt(numbers.lastIndex)
        val op = operators.removeAt(operators.lastIndex)
        val result = when (op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            '/' -> if (b == 0.0) return false else a / b
            else -> return false
        }
        numbers += result
        return true
    }

    while (index < expression.length) {
        val start = index
        var dots = 0
        while (index < expression.length && (expression[index].isDigit() || expression[index] == '.')) {
            if (expression[index] == '.') dots++
            if (dots > 1) return null
            index++
        }
        if (start == index) return null
        numbers += expression.substring(start, index).toDoubleOrNull() ?: return null
        if (index < expression.length) {
            val op = expression[index]
            if (op !in charArrayOf('+', '-', '*', '/')) return null
            while (operators.isNotEmpty() && precedence(operators.last()) >= precedence(op)) {
                if (!applyTop()) return null
            }
            operators += op
            index++
            if (index >= expression.length) return null
        }
    }
    while (operators.isNotEmpty()) if (!applyTop()) return null
    return numbers.singleOrNull()?.takeIf { it.isFinite() }
}

@Composable
fun AnxiousSpendingApp(
    store: ExpenseStore,
    noteTagStore: NoteTagStore,
    exchangeRateStore: ExchangeRateStore
) {
    var entries by remember { mutableStateOf(store.load()) }
    var noteTagClicks by remember { mutableStateOf(noteTagStore.loadClickCounts()) }
    var rateSnapshot by remember { mutableStateOf(exchangeRateStore.load()) }
    var page by remember { mutableIntStateOf(0) }
    var ledgerSearch by rememberSaveable { mutableStateOf("") }
    var ledgerYear by rememberSaveable { mutableIntStateOf(0) }
    var ledgerMonth by rememberSaveable { mutableIntStateOf(0) }
    val ledgerListState = rememberLazyListState()
    val appScope = rememberCoroutineScope()
    val showBackToTop by remember {
        derivedStateOf {
            ledgerListState.firstVisibleItemIndex > 0 || ledgerListState.firstVisibleItemScrollOffset > 240
        }
    }
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Expense?>(null) }
    var pendingEdit by remember { mutableStateOf<Expense?>(null) }

    LaunchedEffect(Unit) {
        exchangeRateStore.refreshAsync { fresh -> if (fresh != null) rateSnapshot = fresh }
    }

    val noteSuggestions = remember(entries, noteTagClicks) {
        buildNoteSuggestions(entries, noteTagClicks)
    }

    fun persist(next: List<Expense>) {
        entries = next.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
        store.save(entries)
    }

    fun registerNoteTagClick(category: String, text: String) {
        noteTagClicks = noteTagStore.incrementClick(category, text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("坚持焦虑地花钱中", fontWeight = FontWeight.SemiBold)
                        Text("Spending Anxiously, Consistently.", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    if (page == 0) {
                        TextButton(onClick = { showImport = true }) { Text("导入") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == 0,
                    onClick = { page = 0 },
                    icon = { Text("●", style = MaterialTheme.typography.labelSmall) },
                    label = { Text("记录") }
                )
                NavigationBarItem(
                    selected = page == 1,
                    onClick = { page = 1 },
                    icon = { Text("●", style = MaterialTheme.typography.labelSmall) },
                    label = { Text("分析") }
                )
            }
        },
        floatingActionButton = {
            if (page == 0) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showBackToTop) {
                        SmallFloatingActionButton(
                            onClick = { appScope.launch { ledgerListState.animateScrollToItem(0) } }
                        ) {
                            Text("↑", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    FloatingActionButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新增记录")
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (page == 0) {
                LedgerPage(
                    entries = entries,
                    query = ledgerSearch,
                    onQueryChange = { ledgerSearch = it },
                    selectedYear = ledgerYear,
                    onYearChange = { ledgerYear = it },
                    selectedMonth = ledgerMonth,
                    onMonthChange = { ledgerMonth = it },
                    listState = ledgerListState,
                    onEdit = { pendingEdit = it },
                    onDelete = { pendingDelete = it }
                )
            } else AnalyticsHub(entries, onEditEntry = { pendingEdit = it })
        }
    }

    if (showImport) {
        BatchImportDialog(
            existingEntries = entries,
            onDismiss = { showImport = false },
            onImport = { imported ->
                persist(entries + imported)
                showImport = false
            }
        )
    }

    if (showAdd) {
        ExpenseDialog(
            initialExpense = null,
            noteSuggestions = noteSuggestions,
            rateSnapshot = rateSnapshot,
            onNoteTagClick = ::registerNoteTagClick,
            onDismiss = { showAdd = false },
            onSave = { persist(entries + it); showAdd = false }
        )
    }

    pendingEdit?.let { editing ->
        ExpenseDialog(
            initialExpense = editing,
            noteSuggestions = noteSuggestions,
            rateSnapshot = rateSnapshot,
            onNoteTagClick = ::registerNoteTagClick,
            onDismiss = { pendingEdit = null },
            onSave = { updated ->
                persist(entries.map { if (it.id == updated.id) updated else it })
                pendingEdit = null
            }
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这笔？") },
            text = { Text("${categorySubtitle(entry.category)} · ${formatOriginal(entry)}") },
            confirmButton = {
                TextButton(onClick = {
                    persist(entries.filterNot { it.id == entry.id })
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun LedgerPage(
    entries: List<Expense>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedYear: Int,
    onYearChange: (Int) -> Unit,
    selectedMonth: Int,
    onMonthChange: (Int) -> Unit,
    listState: LazyListState,
    onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit
) {
    val scope = rememberCoroutineScope()
    var yearMenuOpen by remember { mutableStateOf(false) }
    var monthMenuOpen by remember { mutableStateOf(false) }

    val availableYears = remember(entries) {
        entries.map { it.date.year }.distinct().sortedDescending()
    }
    val searchTokens = remember(query) {
        query.trim()
            .split(Regex("\\s+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }
    val compactDateFormatter = remember { DateTimeFormatter.ofPattern("yyyyMMdd") }

    val filteredEntries = remember(entries, searchTokens, selectedYear, selectedMonth) {
        entries.filter { entry ->
            val yearMatches = selectedYear == 0 || entry.date.year == selectedYear
            val monthMatches = selectedMonth == 0 || entry.date.monthValue == selectedMonth
            val searchable = buildString {
                append(categoryName(entry.category))
                append(' ')
                append(categorySubtitle(entry.category))
                append(' ')
                append(entry.note)
                append(' ')
                append(entry.date.format(compactDateFormatter))
            }.lowercase()
            val searchMatches = searchTokens.all { token -> searchable.contains(token) }
            yearMatches && monthMatches && searchMatches
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .height(34.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        onQueryChange(it)
                        scope.launch { listState.scrollToItem(0) }
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = if (query.isNotEmpty()) 34.dp else 10.dp)
                )
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(32.dp)
                            .clickable {
                                onQueryChange("")
                                scope.launch { listState.scrollToItem(0) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.width(120.dp)) {
                    OutlinedButton(
                        onClick = { yearMenuOpen = true },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(if (selectedYear == 0) "全部年份" else "${selectedYear}年")
                    }
                    DropdownMenu(
                        expanded = yearMenuOpen,
                        onDismissRequest = { yearMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部年份") },
                            onClick = {
                                onYearChange(0)
                                yearMenuOpen = false
                                scope.launch { listState.scrollToItem(0) }
                            }
                        )
                        availableYears.forEach { year ->
                            DropdownMenuItem(
                                text = { Text("${year}年") },
                                onClick = {
                                    onYearChange(year)
                                    yearMenuOpen = false
                                    scope.launch { listState.scrollToItem(0) }
                                }
                            )
                        }
                    }
                }

                Box(Modifier.width(120.dp)) {
                    OutlinedButton(
                        onClick = { monthMenuOpen = true },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(if (selectedMonth == 0) "全部月份" else "${selectedMonth}月")
                    }
                    DropdownMenu(
                        expanded = monthMenuOpen,
                        onDismissRequest = { monthMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部月份") },
                            onClick = {
                                onMonthChange(0)
                                monthMenuOpen = false
                                scope.launch { listState.scrollToItem(0) }
                            }
                        )
                        (1..12).forEach { month ->
                            DropdownMenuItem(
                                text = { Text("${month}月") },
                                onClick = {
                                    onMonthChange(month)
                                    monthMenuOpen = false
                                    scope.launch { listState.scrollToItem(0) }
                                }
                            )
                        }
                    }
                }
            }

            val filtersActive = query.isNotBlank() || selectedYear != 0 || selectedMonth != 0
            if (filtersActive) {
                Text(
                    "找到 ${filteredEntries.size} 笔",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有账。先记一笔再说。")
            }
            return@Column
        }

        if (filteredEntries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有匹配的记录。")
            }
            return@Column
        }

        val grouped = filteredEntries.groupBy { it.date }.toSortedMap(compareByDescending { it })
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            grouped.forEach { (date, dayEntries) ->
                item(key = "day-${date}") {
                    val expenseTotal = dayEntries.filter { it.entryType == "expense" }.sumOf { it.cnyAmount }
                    val incomeTotal = dayEntries.filter { it.entryType == "income" }.sumOf { it.cnyAmount }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(date.format(DateTimeFormatter.ofPattern("M月d日 EEE")), fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                incomeTotal > 0.0 && expenseTotal > 0.0 -> "支 ¥%.2f · 收 ¥%.2f".format(expenseTotal, incomeTotal)
                                incomeTotal > 0.0 -> "收 ¥%.2f".format(incomeTotal)
                                else -> "¥%.2f".format(expenseTotal)
                            }
                        )
                    }
                }

                items(dayEntries, key = { it.id }) { entry ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onEdit(entry) }
                                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(categorySubtitle(entry.category), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(3.dp))
                                val source = paymentSourceOption(entry.paymentSource)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (entry.note.isNotBlank()) {
                                        Text(entry.note, style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(source.dot, style = MaterialTheme.typography.bodySmall, color = source.color)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    formatOriginal(entry),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (entry.entryType == "income") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (entry.currency != "CNY") {
                                    Text(
                                        "≈ ${if (entry.entryType == "income") "+" else ""}¥${formatAmount(entry.cnyAmount)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { onDelete(entry) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除这笔")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisPage(entries: List<Expense>) {
    val expenses = entries.filter { it.entryType == "expense" }
    val today = LocalDate.now()
    var selectedYear by remember { mutableIntStateOf(today.year) }
    var selectedMonth by remember { mutableIntStateOf(today.monthValue) }
    var analysisMode by remember { mutableIntStateOf(0) }
    val monthExpenses = expenses.filter { it.date.year == selectedYear && it.date.monthValue == selectedMonth }
    val yearExpenses = expenses.filter { it.date.year == selectedYear }
    val activeExpenses = if (analysisMode == 0) monthExpenses else yearExpenses
    val nonEmptyMonths = (1..12).mapNotNull { month ->
        val list = yearExpenses.filter { it.date.monthValue == month }
        if (list.isEmpty()) null else month to list.sumOf { it.cnyAmount }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactSelector("年份", "$selectedYear", { selectedYear -= 1 }, { selectedYear += 1 }, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(48.dp))
                    CompactSelector(
                        "月份", "${selectedMonth}月",
                        { selectedMonth = if (selectedMonth == 1) 12 else selectedMonth - 1 },
                        { selectedMonth = if (selectedMonth == 12) 1 else selectedMonth + 1 },
                        Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (analysisMode == 0) {
                    Button(onClick = { analysisMode = 0 }, modifier = Modifier.weight(1f)) { Text("月分析") }
                    OutlinedButton(onClick = { analysisMode = 1 }, modifier = Modifier.weight(1f)) { Text("年分析") }
                } else {
                    OutlinedButton(onClick = { analysisMode = 0 }, modifier = Modifier.weight(1f)) { Text("月分析") }
                    Button(onClick = { analysisMode = 1 }, modifier = Modifier.weight(1f)) { Text("年分析") }
                }
            }
        }

        item {
            val title = if (analysisMode == 0) "$selectedYear 年 $selectedMonth 月支出" else "$selectedYear 年支出"
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("¥%.2f".format(activeExpenses.sumOf { it.cnyAmount }), style = MaterialTheme.typography.headlineMedium)
                Text("${activeExpenses.size} 笔 · 统一按记账时人民币金额统计", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (analysisMode == 1) {
            item { Text("每月", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (nonEmptyMonths.isEmpty()) item { Text("这一年还没有支出。") }
            else items(nonEmptyMonths, key = { it.first }) { (month, total) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${month}月")
                    Text("¥%.2f".format(total))
                }
            }
        }

        item { Text("分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (activeExpenses.isEmpty()) {
            item { Text(if (analysisMode == 0) "这个月还没有支出。" else "这一年还没有支出。") }
        } else {
            items(
                activeExpenses.groupBy { it.category }.toList()
                    .sortedByDescending { (_, list) -> list.sumOf { it.cnyAmount } },
                key = { "category-${analysisMode}-${it.first}" }
            ) { (category, list) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(categorySubtitle(category), fontWeight = FontWeight.Medium)
                    Text("¥%.2f".format(list.sumOf { it.cnyAmount }))
                }
            }
        }
    }
}

@Composable
private fun CompactSelector(
    label: String,
    value: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevious, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(0.dp)) { Text("‹") }
            Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            TextButton(onClick = onNext, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(0.dp)) { Text("›") }
        }
    }
}

@Composable
private fun CompactDateField(
    value: String,
    maxDigits: Int,
    width: Int,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
    onImeAction: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier.width(width.dp).height(30.dp).border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxDigits)) },
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp)
        )
    }
}

@Composable
internal fun ExpenseDialog(
    initialExpense: Expense?,
    noteSuggestions: List<NoteSuggestion>,
    rateSnapshot: ExchangeRateSnapshot?,
    onNoteTagClick: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit
) {
    var entryType by remember(initialExpense?.id) { mutableStateOf(initialExpense?.entryType ?: "expense") }
    var expression by remember(initialExpense?.id) {
        mutableStateOf(initialExpense?.let { formatAmount(it.amount) }.orEmpty())
    }
    var note by remember(initialExpense?.id) { mutableStateOf(initialExpense?.note.orEmpty()) }
    var categoryIndex by remember(initialExpense?.id, entryType) {
        mutableIntStateOf(
            if (initialExpense == null || initialExpense.entryType != entryType) -1
            else categoriesFor(entryType).indexOfFirst { it.key == initialExpense.category }.takeIf { it >= 0 } ?: -1
        )
    }
    var categoryMenu by remember { mutableStateOf(false) }
    var categoryError by remember(initialExpense?.id) { mutableStateOf(false) }
    var currencyIndex by remember(initialExpense?.id) {
        mutableIntStateOf(currencies.indexOfFirst { it.code == initialExpense?.currency }.takeIf { it >= 0 } ?: 0)
    }
    var currencyMenu by remember { mutableStateOf(false) }
    var currencyChanged by remember(initialExpense?.id) { mutableStateOf(false) }
    var paymentSourceIndex by remember(initialExpense?.id) {
        mutableIntStateOf(paymentSources.indexOfFirst { it.key == initialExpense?.paymentSource }.takeIf { it >= 0 } ?: 0)
    }
    var paymentSourceMenu by remember { mutableStateOf(false) }

    val activeCategories = categoriesFor(entryType)
    val startingDate = initialExpense?.date ?: LocalDate.now()
    var dateYear by remember(initialExpense?.id) { mutableStateOf(startingDate.year.toString()) }
    var dateMonth by remember(initialExpense?.id) { mutableStateOf(startingDate.monthValue.toString().padStart(2, '0')) }
    var dateDay by remember(initialExpense?.id) { mutableStateOf(startingDate.dayOfMonth.toString().padStart(2, '0')) }
    var dateError by remember(initialExpense?.id) { mutableStateOf(false) }
    var calculatorError by remember { mutableStateOf(false) }

    fun appendToken(token: String) {
        calculatorError = false
        val operators = setOf("+", "-", "×", "÷")
        if (token in operators) {
            if (expression.isBlank()) return
            val last = expression.last().toString()
            expression = if (last in operators) expression.dropLast(1) + token else expression + token
        } else if (token == ".") {
            val currentNumber = expression.takeLastWhile { it.isDigit() || it == '.' }
            if (!currentNumber.contains('.')) expression += if (currentNumber.isEmpty()) "0." else "."
        } else expression += token
    }

    fun calculate() {
        val result = evaluateExpression(expression)
        if (result == null || result <= 0.0) calculatorError = true
        else {
            expression = formatAmount(result)
            calculatorError = false
        }
    }

    fun setDateParts(date: LocalDate) {
        dateYear = date.year.toString()
        dateMonth = date.monthValue.toString().padStart(2, '0')
        dateDay = date.dayOfMonth.toString().padStart(2, '0')
        dateError = false
    }

    fun selectEntryType(next: String) {
        if (entryType != next) {
            entryType = next
            categoryIndex = -1
            categoryError = false
            categoryMenu = false
        }
    }

    val calculatedAmount = evaluateExpression(expression) ?: expression.toDoubleOrNull()
    val parsedDate = parseDateParts(dateYear, dateMonth, dateDay)
    val hasCategory = categoryIndex in activeCategories.indices
    val selectedCategoryKey = activeCategories.getOrNull(categoryIndex)?.key
    val selectedCurrency = currencies[currencyIndex]
    val selectedPaymentSource = paymentSources[paymentSourceIndex]
    val currentRate = when {
        selectedCurrency.code == "CNY" -> 1.0
        !currencyChanged && initialExpense?.currency == selectedCurrency.code -> initialExpense.exchangeRateToCny
        else -> rateSnapshot?.rateToCny(selectedCurrency.code)
    }
    val convertedCny = if (calculatedAmount != null && currentRate != null) calculatedAmount * currentRate else null
    val visibleSuggestions = remember(noteSuggestions, selectedCategoryKey, entryType) {
        if (selectedCategoryKey == null) emptyList()
        else noteSuggestions.filter { it.entryType == entryType && it.category == selectedCategoryKey }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialExpense == null) "记一笔" else "修改这笔") },
        text = {
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            val dialogView = LocalView.current
            val inputMethodManager = remember(dialogView) {
                dialogView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            }

            fun finishEditing() {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dialogView.windowInsetsController?.hide(WindowInsets.Type.ime())
                }
                inputMethodManager.hideSoftInputFromWindow(dialogView.windowToken, 0)
                dialogView.post { inputMethodManager.hideSoftInputFromWindow(dialogView.windowToken, 0) }
            }

            LazyColumn(
                modifier = Modifier.height(560.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entryType == "expense") {
                            Button(onClick = { selectEntryType("expense") }, modifier = Modifier.weight(1f).height(32.dp)) { Text("支出") }
                            OutlinedButton(onClick = { selectEntryType("income") }, modifier = Modifier.weight(1f).height(32.dp)) { Text("收入") }
                        } else {
                            OutlinedButton(onClick = { selectEntryType("expense") }, modifier = Modifier.weight(1f).height(32.dp)) { Text("支出") }
                            Button(onClick = { selectEntryType("income") }, modifier = Modifier.weight(1f).height(32.dp)) { Text("收入") }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = expression,
                        onValueChange = { next ->
                            val allowed = next.filter { it.isDigit() || it in listOf('.', '+', '-', '×', '÷', '*', '/') }
                            expression = allowed.replace('*', '×').replace('/', '÷')
                            calculatorError = false
                        },
                        label = { Text("金额 / 算式") },
                        trailingIcon = {
                            Box {
                                TextButton(onClick = { currencyMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                    Text("${selectedCurrency.symbol} ${selectedCurrency.code}", fontWeight = FontWeight.Bold)
                                }
                                DropdownMenu(expanded = currencyMenu, onDismissRequest = { currencyMenu = false }) {
                                    currencies.forEachIndexed { index, option ->
                                        DropdownMenuItem(
                                            text = { Text("${option.symbol}  ${option.name}  ${option.code}") },
                                            onClick = {
                                                currencyIndex = index
                                                currencyChanged = true
                                                currencyMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        supportingText = {
                            when {
                                calculatorError -> Text("这个算式算不出来")
                                selectedCurrency.code != "CNY" && currentRate == null -> Text("暂无可用汇率，联网后自动刷新")
                                selectedCurrency.code != "CNY" && convertedCny != null -> {
                                    val sourceText = if (!currencyChanged && initialExpense?.currency == selectedCurrency.code) {
                                        "使用这笔账原汇率"
                                    } else {
                                        rateSnapshot?.let { "汇率更新 ${formatRateTime(it.updatedAtMillis)}" } ?: "缓存汇率"
                                    }
                                    Text("≈ ¥${formatAmount(convertedCny)} · $sourceText")
                                }
                                calculatedAmount != null && expression.any { it in "+-×÷" } -> Text("= ${selectedCurrency.symbol}${formatAmount(calculatedAmount)}")
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    CalculatorPad(
                        onToken = ::appendToken,
                        onClear = { expression = "" },
                        onBackspace = {
                            if (expression.isNotEmpty()) expression = expression.dropLast(1)
                            calculatorError = false
                        },
                        onEquals = ::calculate
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Box {
                            OutlinedButton(
                                onClick = { categoryMenu = true },
                                modifier = Modifier.fillMaxWidth().height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    if (hasCategory) activeCategories[categoryIndex].subtitle else "请选择${if (entryType == "income") "收入" else "支出"}分类 *",
                                    fontWeight = if (hasCategory) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                                activeCategories.forEachIndexed { index, item ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(item.subtitle, fontWeight = FontWeight.Medium)
                                                Text(item.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        onClick = {
                                            categoryIndex = index
                                            categoryError = false
                                            categoryMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        if (categoryError) {
                            Text("请选择分类", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                item {
                    Box {
                        OutlinedButton(
                            onClick = { paymentSourceMenu = true },
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(selectedPaymentSource.dot, color = selectedPaymentSource.color)
                                Spacer(Modifier.width(5.dp))
                                Text(selectedPaymentSource.name, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        DropdownMenu(expanded = paymentSourceMenu, onDismissRequest = { paymentSourceMenu = false }) {
                            paymentSources.forEachIndexed { index, source ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(source.dot, color = source.color)
                                            Spacer(Modifier.width(7.dp))
                                            Text(source.name)
                                        }
                                    },
                                    onClick = {
                                        paymentSourceIndex = index
                                        paymentSourceMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = note,
                            onValueChange = { note = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 38.dp)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        finishEditing()
                                        true
                                    } else false
                                }
                        )
                        Text(
                            "备注",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 8.dp)
                                .offset(y = (-7).dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 2.dp)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(36.dp)
                                .clickable { finishEditing() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("常用备注", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(6.dp))
                        when {
                            selectedCategoryKey == null -> Text("先选分类", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            visibleSuggestions.isEmpty() -> Text("暂无", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> LazyRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(end = 8.dp)
                            ) {
                                items(visibleSuggestions, key = { "${it.entryType}-${it.category}-${it.text}" }) { suggestion ->
                                    AssistChip(
                                        onClick = {
                                            note = suggestion.text
                                            onNoteTagClick(suggestion.category, suggestion.text)
                                            finishEditing()
                                        },
                                        label = { Text("${suggestion.text} · ${suggestion.count}次") },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("日期：", style = MaterialTheme.typography.bodyMedium)
                            CompactDateField(
                                value = dateYear,
                                maxDigits = 4,
                                width = 60,
                                imeAction = ImeAction.Next,
                                onValueChange = { dateYear = it; dateError = false },
                                onImeAction = { focusManager.moveFocus(FocusDirection.Next) }
                            )
                            Text("/", modifier = Modifier.padding(horizontal = 1.dp))
                            CompactDateField(
                                value = dateMonth,
                                maxDigits = 2,
                                width = 36,
                                imeAction = ImeAction.Next,
                                onValueChange = { dateMonth = it; dateError = false },
                                onImeAction = { focusManager.moveFocus(FocusDirection.Next) }
                            )
                            Text("/", modifier = Modifier.padding(horizontal = 1.dp))
                            CompactDateField(
                                value = dateDay,
                                maxDigits = 2,
                                width = 36,
                                imeAction = ImeAction.Done,
                                onValueChange = { dateDay = it; dateError = false },
                                onImeAction = {
                                    dateError = parseDateParts(dateYear, dateMonth, dateDay) == null
                                    if (!dateError) finishEditing()
                                }
                            )
                            Spacer(Modifier.width(2.dp))
                            Box(
                                modifier = Modifier.size(26.dp).clickable {
                                    dateYear = ""
                                    dateMonth = ""
                                    dateDay = ""
                                    dateError = false
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("×", color = Color.Black, fontWeight = FontWeight.Black)
                            }
                        }

                        if (dateError || ((dateYear.isNotBlank() || dateMonth.isNotBlank() || dateDay.isNotBlank()) && parsedDate == null)) {
                            Text("日期无效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(
                                enabled = parsedDate != null,
                                onClick = { parsedDate?.let { setDateParts(it.minusDays(1)) } },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("−1天") }
                            TextButton(
                                onClick = { setDateParts(LocalDate.now()) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("今天") }
                            TextButton(
                                enabled = parsedDate != null,
                                onClick = { parsedDate?.let { setDateParts(it.plusDays(1)) } },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("+1天") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = calculatedAmount?.let { it > 0.0 } == true && parsedDate != null && currentRate != null,
                onClick = {
                    if (!hasCategory) {
                        categoryError = true
                        return@TextButton
                    }
                    val amount = calculatedAmount?.takeIf { it > 0.0 } ?: return@TextButton
                    val date = parsedDate ?: return@TextButton
                    val rate = currentRate ?: return@TextButton
                    onSave(
                        Expense(
                            id = initialExpense?.id ?: UUID.randomUUID().toString(),
                            amount = amount,
                            category = activeCategories[categoryIndex].key,
                            note = note.trim(),
                            date = date,
                            currency = selectedCurrency.code,
                            exchangeRateToCny = rate,
                            cnyAmount = amount * rate,
                            paymentSource = selectedPaymentSource.key,
                            entryType = entryType
                        )
                    )
                }
            ) { Text(if (initialExpense == null) "保存" else "保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CalculatorPad(
    onToken: (String) -> Unit,
    onClear: () -> Unit,
    onBackspace: () -> Unit,
    onEquals: () -> Unit
) {
    val rows = listOf(
        listOf("C", "⌫", "÷", "×"),
        listOf("7", "8", "9", "-"),
        listOf("4", "5", "6", "+"),
        listOf("1", "2", "3", "="),
        listOf("00", "0", ".")
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { label ->
                    Surface(
                        modifier = Modifier.weight(1f).height(40.dp).clickable {
                            when (label) {
                                "C" -> onClear()
                                "⌫" -> onBackspace()
                                "=" -> onEquals()
                                else -> onToken(label)
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(label, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
