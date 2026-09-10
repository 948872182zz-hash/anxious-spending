package com.anxiousspending.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ExpenseStore(this)
        setContent {
            MaterialTheme {
                AnxiousSpendingApp(store)
            }
        }
    }
}

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val amount: Double,
    val category: String,
    val note: String,
    val date: LocalDate
)

data class CategoryOption(val key: String, val subtitle: String)

private val categories = listOf(
    CategoryOption("shopping", "BUY STH NEW"),
    CategoryOption("food", "EATING"),
    CategoryOption("game", "婷芷我说婷芷"),
    CategoryOption("ai", "别BAN我"),
    CategoryOption("misc", "生活杂费"),
    CategoryOption("transport", "🚈🚕🚌嘟嘟"),
    CategoryOption("travel", "GO GO GO出发喽"),
    CategoryOption("snack", "STOP EAT"),
    CategoryOption("books", "今天你看书了吗"),
    CategoryOption("investment", "HOPE💹"),
    CategoryOption("medical", "今天哪里又痛了我的大小姐")
)

private fun categorySubtitle(key: String): String =
    categories.firstOrNull { it.key == key }?.subtitle ?: key

private fun formatAmount(value: Double): String {
    val fixed = "%.2f".format(value)
    return fixed.trimEnd('0').trimEnd('.')
}

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
        val number = expression.substring(start, index).toDoubleOrNull() ?: return null
        numbers += number

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

    while (operators.isNotEmpty()) {
        if (!applyTop()) return null
    }
    return numbers.singleOrNull()?.takeIf { it.isFinite() }
}

@Composable
fun AnxiousSpendingApp(store: ExpenseStore) {
    var expenses by remember { mutableStateOf(store.load()) }
    var page by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Expense?>(null) }
    var pendingEdit by remember { mutableStateOf<Expense?>(null) }

    fun persist(next: List<Expense>) {
        expenses = next.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
        store.save(expenses)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("坚持焦虑地花钱中", fontWeight = FontWeight.SemiBold)
                        Text("Spending Anxiously, Consistently.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == 0,
                    onClick = { page = 0 },
                    icon = { Text("账") },
                    label = { Text("记录") }
                )
                NavigationBarItem(
                    selected = page == 1,
                    onClick = { page = 1 },
                    icon = { Text("析") },
                    label = { Text("分析") }
                )
            }
        },
        floatingActionButton = {
            if (page == 0) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新增支出")
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (page == 0) {
                LedgerPage(
                    expenses = expenses,
                    onEdit = { pendingEdit = it },
                    onDelete = { pendingDelete = it }
                )
            } else {
                AnalysisPage(expenses)
            }
        }
    }

    if (showAdd) {
        ExpenseDialog(
            initialExpense = null,
            onDismiss = { showAdd = false },
            onSave = { expense ->
                persist(expenses + expense)
                showAdd = false
            }
        )
    }

    pendingEdit?.let { editing ->
        ExpenseDialog(
            initialExpense = editing,
            onDismiss = { pendingEdit = null },
            onSave = { updated ->
                persist(expenses.map { if (it.id == updated.id) updated else it })
                pendingEdit = null
            }
        )
    }

    pendingDelete?.let { expense ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这笔？") },
            text = { Text("${categorySubtitle(expense.category)} · ¥%.2f".format(expense.amount)) },
            confirmButton = {
                TextButton(onClick = {
                    persist(expenses.filterNot { it.id == expense.id })
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LedgerPage(
    expenses: List<Expense>,
    onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit
) {
    if (expenses.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有账。先花一笔再说。")
        }
        return
    }

    val grouped = expenses.groupBy { it.date }.toSortedMap(compareByDescending { it })
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        grouped.forEach { (date, dayExpenses) ->
            item(key = "day-$date") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(date.format(DateTimeFormatter.ofPattern("M月d日 EEE")), fontWeight = FontWeight.Bold)
                    Text("¥%.2f".format(dayExpenses.sumOf { it.amount }))
                }
            }
            items(dayExpenses, key = { it.id }) { expense ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(expense) }
                            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(categorySubtitle(expense.category), fontWeight = FontWeight.SemiBold)
                            if (expense.note.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(expense.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("¥%.2f".format(expense.amount), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { onDelete(expense) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除这笔")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisPage(expenses: List<Expense>) {
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    val monthExpenses = expenses.filter { YearMonth.from(it.date) == selectedMonth }
    val yearExpenses = expenses.filter { it.date.year == selectedMonth.year }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { selectedMonth = selectedMonth.minusMonths(1) }) { Text("‹ 上月") }
                Text(
                    selectedMonth.format(DateTimeFormatter.ofPattern("yyyy年 M月")),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { selectedMonth = selectedMonth.plusMonths(1) }) { Text("下月 ›") }
            }
        }
        item {
            Text("这个月", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("¥%.2f".format(monthExpenses.sumOf { it.amount }), style = MaterialTheme.typography.headlineMedium)
            Text("${monthExpenses.size} 笔", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("${selectedMonth.year} 年", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("¥%.2f".format(yearExpenses.sumOf { it.amount }), style = MaterialTheme.typography.headlineMedium)
        }
        item { Text("分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (monthExpenses.isEmpty()) {
            item { Text("这个月还没有账。") }
        } else {
            items(
                monthExpenses.groupBy { it.category }
                    .toList()
                    .sortedByDescending { (_, list) -> list.sumOf { it.amount } },
                key = { it.first }
            ) { (category, list) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(categorySubtitle(category), fontWeight = FontWeight.Medium)
                    Text("¥%.2f".format(list.sumOf { it.amount }))
                }
            }
        }
    }
}

@Composable
private fun ExpenseDialog(
    initialExpense: Expense?,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit
) {
    var expression by remember(initialExpense?.id) {
        mutableStateOf(initialExpense?.let { formatAmount(it.amount) }.orEmpty())
    }
    var note by remember(initialExpense?.id) { mutableStateOf(initialExpense?.note.orEmpty()) }
    var categoryIndex by remember(initialExpense?.id) {
        mutableIntStateOf(
            categories.indexOfFirst { it.key == initialExpense?.category }.takeIf { it >= 0 } ?: 0
        )
    }
    var categoryMenu by remember { mutableStateOf(false) }
    var selectedDate by remember(initialExpense?.id) {
        mutableStateOf(initialExpense?.date ?: LocalDate.now())
    }
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
            if (!currentNumber.contains('.')) {
                expression += if (currentNumber.isEmpty()) "0." else "."
            }
        } else {
            expression += token
        }
    }

    fun calculate() {
        val result = evaluateExpression(expression)
        if (result == null || result <= 0.0) {
            calculatorError = true
        } else {
            expression = formatAmount(result)
            calculatorError = false
        }
    }

    val calculatedAmount = evaluateExpression(expression)
        ?: expression.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialExpense == null) "记一笔" else "修改这笔") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = expression,
                        onValueChange = { next ->
                            val allowed = next.filter { it.isDigit() || it in listOf('.', '+', '-', '×', '÷', '*', '/') }
                            expression = allowed.replace('*', '×').replace('/', '÷')
                            calculatorError = false
                        },
                        label = { Text("金额 / 算式") },
                        supportingText = {
                            when {
                                calculatorError -> Text("这个算式算不出来")
                                calculatedAmount != null && expression.any { it in "+-×÷" } -> Text("= ¥${formatAmount(calculatedAmount)}")
                                else -> Text("可以直接输入，也可以用下面的计算器")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item { CalculatorPad(onToken = ::appendToken, onClear = { expression = "" }, onBackspace = {
                    if (expression.isNotEmpty()) expression = expression.dropLast(1)
                    calculatorError = false
                }, onEquals = ::calculate) }

                item {
                    Box {
                        OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(categories[categoryIndex].subtitle, fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            categories.forEachIndexed { index, item ->
                                DropdownMenuItem(
                                    text = { Text(item.subtitle, fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        categoryIndex = index
                                        categoryMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Text("日期：${selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selectedDate = selectedDate.minusDays(1) }) { Text("−1天") }
                        TextButton(onClick = { selectedDate = LocalDate.now() }) { Text("今天") }
                        TextButton(onClick = { selectedDate = selectedDate.plusDays(1) }) { Text("+1天") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = calculatedAmount?.let { it > 0.0 } == true,
                onClick = {
                    val amount = calculatedAmount?.takeIf { it > 0.0 } ?: return@TextButton
                    onSave(
                        Expense(
                            id = initialExpense?.id ?: UUID.randomUUID().toString(),
                            amount = amount,
                            category = categories[categoryIndex].key,
                            note = note.trim(),
                            date = selectedDate
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { label ->
                    OutlinedButton(
                        onClick = {
                            when (label) {
                                "C" -> onClear()
                                "⌫" -> onBackspace()
                                "=" -> onEquals()
                                else -> onToken(label)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(label)
                    }
                }
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
