package com.anxiousspending.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expenseStore = ExpenseStore(this)
        val noteTagStore = NoteTagStore(this)
        setContent {
            MaterialTheme {
                AnxiousSpendingApp(expenseStore, noteTagStore)
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

private data class NoteSuggestion(
    val text: String,
    val count: Int,
    val clickCount: Int,
    val lastUsed: LocalDate
)

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

private val dateDisplayFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
private val dateParseFormatter = DateTimeFormatter
    .ofPattern("uuuu/MM/dd")
    .withResolverStyle(ResolverStyle.STRICT)

private fun categorySubtitle(key: String): String =
    categories.firstOrNull { it.key == key }?.subtitle ?: key

private fun formatAmount(value: Double): String {
    val fixed = "%.2f".format(value)
    return fixed.trimEnd('0').trimEnd('.')
}

private fun formatDateInput(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(8)
    return buildString {
        append(digits.take(4))
        if (digits.length > 4) {
            append('/')
            append(digits.substring(4, minOf(6, digits.length)))
        }
        if (digits.length > 6) {
            append('/')
            append(digits.substring(6))
        }
    }
}

private fun parseDateInput(text: String): LocalDate? =
    runCatching { LocalDate.parse(text, dateParseFormatter) }.getOrNull()

private fun buildNoteSuggestions(
    expenses: List<Expense>,
    clickCounts: Map<String, Int>
): List<NoteSuggestion> =
    expenses
        .filter { it.note.trim().isNotEmpty() }
        .groupBy { it.note.trim() }
        .map { (text, matches) ->
            NoteSuggestion(
                text = text,
                count = matches.size,
                clickCount = clickCounts[text] ?: 0,
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

    while (operators.isNotEmpty()) {
        if (!applyTop()) return null
    }

    return numbers.singleOrNull()?.takeIf { it.isFinite() }
}

@Composable
fun AnxiousSpendingApp(
    store: ExpenseStore,
    noteTagStore: NoteTagStore
) {
    var expenses by remember { mutableStateOf(store.load()) }
    var noteTagClicks by remember { mutableStateOf(noteTagStore.loadClickCounts()) }
    var page by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Expense?>(null) }
    var pendingEdit by remember { mutableStateOf<Expense?>(null) }

    val noteSuggestions = remember(expenses, noteTagClicks) {
        buildNoteSuggestions(expenses, noteTagClicks)
    }

    fun persist(next: List<Expense>) {
        expenses = next.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
        store.save(expenses)
    }

    fun registerNoteTagClick(text: String) {
        noteTagClicks = noteTagStore.incrementClick(text)
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
            noteSuggestions = noteSuggestions,
            onNoteTagClick = ::registerNoteTagClick,
            onDismiss = { showAdd = false },
            onSave = {
                persist(expenses + it)
                showAdd = false
            }
        )
    }

    pendingEdit?.let { editing ->
        ExpenseDialog(
            initialExpense = editing,
            noteSuggestions = noteSuggestions,
            onNoteTagClick = ::registerNoteTagClick,
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
    val today = LocalDate.now()
    var selectedYear by remember { mutableIntStateOf(today.year) }
    var selectedMonth by remember { mutableIntStateOf(today.monthValue) }
    var analysisMode by remember { mutableIntStateOf(0) }

    val monthExpenses = expenses.filter {
        it.date.year == selectedYear && it.date.monthValue == selectedMonth
    }
    val yearExpenses = expenses.filter { it.date.year == selectedYear }
    val activeExpenses = if (analysisMode == 0) monthExpenses else yearExpenses
    val nonEmptyMonths = (1..12).mapNotNull { month ->
        val list = yearExpenses.filter { it.date.monthValue == month }
        if (list.isEmpty()) null else month to list.sumOf { it.amount }
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
                    CompactSelector(
                        label = "年份",
                        value = "$selectedYear",
                        onPrevious = { selectedYear -= 1 },
                        onNext = { selectedYear += 1 },
                        modifier = Modifier.weight(1f)
                    )
                    VerticalDivider(Modifier.height(48.dp))
                    CompactSelector(
                        label = "月份",
                        value = "${selectedMonth}月",
                        onPrevious = { selectedMonth = if (selectedMonth == 1) 12 else selectedMonth - 1 },
                        onNext = { selectedMonth = if (selectedMonth == 12) 1 else selectedMonth + 1 },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
            val title = if (analysisMode == 0) {
                "$selectedYear 年 $selectedMonth 月支出"
            } else {
                "$selectedYear 年支出"
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("¥%.2f".format(activeExpenses.sumOf { it.amount }), style = MaterialTheme.typography.headlineMedium)
                Text("${activeExpenses.size} 笔", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (analysisMode == 1) {
            item {
                Text("每月", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (nonEmptyMonths.isEmpty()) {
                item { Text("这一年还没有账。") }
            } else {
                items(nonEmptyMonths, key = { it.first }) { (month, total) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${month}月")
                        Text("¥%.2f".format(total))
                    }
                }
            }
        }

        item {
            Text("分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (activeExpenses.isEmpty()) {
            item {
                Text(if (analysisMode == 0) "这个月还没有账。" else "这一年还没有账。")
            }
        } else {
            items(
                activeExpenses.groupBy { it.category }
                    .toList()
                    .sortedByDescending { (_, list) -> list.sumOf { it.amount } },
                key = { "category-${analysisMode}-${it.first}" }
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
private fun CompactSelector(
    label: String,
    value: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onPrevious,
                modifier = Modifier.width(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("‹") }

            Text(
                value,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = onNext,
                modifier = Modifier.width(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("›") }
        }
    }
}

@Composable
private fun ExpenseDialog(
    initialExpense: Expense?,
    noteSuggestions: List<NoteSuggestion>,
    onNoteTagClick: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit
) {
    var expression by remember(initialExpense?.id) {
        mutableStateOf(initialExpense?.let { formatAmount(it.amount) }.orEmpty())
    }
    var note by remember(initialExpense?.id) {
        mutableStateOf(initialExpense?.note.orEmpty())
    }
    var categoryIndex by remember(initialExpense?.id) {
        mutableIntStateOf(
            categories.indexOfFirst { it.key == initialExpense?.category }
                .takeIf { it >= 0 } ?: 0
        )
    }
    var categoryMenu by remember { mutableStateOf(false) }
    var dateText by remember(initialExpense?.id) {
        mutableStateOf((initialExpense?.date ?: LocalDate.now()).format(dateDisplayFormatter))
    }
    var dateError by remember(initialExpense?.id) { mutableStateOf(false) }
    var calculatorError by remember { mutableStateOf(false) }

    fun appendToken(token: String) {
        calculatorError = false
        val operators = setOf("+", "-", "×", "÷")
        if (token in operators) {
            if (expression.isBlank()) return
            val last = expression.last().toString()
            expression = if (last in operators) {
                expression.dropLast(1) + token
            } else {
                expression + token
            }
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

    val calculatedAmount = evaluateExpression(expression) ?: expression.toDoubleOrNull()
    val parsedDate = parseDateInput(dateText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialExpense == null) "记一笔" else "修改这笔") },
        text = {
            val dialogFocusManager = LocalFocusManager.current
            val dialogKeyboardController = LocalSoftwareKeyboardController.current
            val dialogView = LocalView.current
            val inputMethodManager = remember(dialogView) {
                dialogView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            }

            fun finishDialogEditing() {
                dialogFocusManager.clearFocus(force = true)
                dialogKeyboardController?.hide()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dialogView.windowInsetsController?.hide(WindowInsets.Type.ime())
                }
                inputMethodManager.hideSoftInputFromWindow(dialogView.windowToken, 0)
                dialogView.post {
                    inputMethodManager.hideSoftInputFromWindow(dialogView.windowToken, 0)
                }
            }

            fun setDate(date: LocalDate) {
                dateText = date.format(dateDisplayFormatter)
                dateError = false
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = expression,
                        onValueChange = { next ->
                            val allowed = next.filter {
                                it.isDigit() || it in listOf('.', '+', '-', '×', '÷', '*', '/')
                            }
                            expression = allowed.replace('*', '×').replace('/', '÷')
                            calculatorError = false
                        },
                        label = { Text("金额 / 算式") },
                        supportingText = {
                            when {
                                calculatorError -> Text("这个算式算不出来")
                                calculatedAmount != null && expression.any { it in "+-×÷" } -> {
                                    Text("= ¥${formatAmount(calculatedAmount)}")
                                }
                                else -> Text("可以直接输入，也可以用下面的计算器")
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { finishDialogEditing() }
                        ),
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
                    Box {
                        OutlinedButton(
                            onClick = { categoryMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(categories[categoryIndex].subtitle, fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(
                            expanded = categoryMenu,
                            onDismissRequest = { categoryMenu = false }
                        ) {
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
                        trailingIcon = {
                            IconButton(onClick = { finishDialogEditing() }) {
                                Text("✓", fontWeight = FontWeight.Bold)
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { finishDialogEditing() }
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                    finishDialogEditing()
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("常用备注", style = MaterialTheme.typography.labelMedium)
                        if (noteSuggestions.isEmpty()) {
                            Text(
                                "同一备注保存满 3 次后，会自动固定成标签",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(noteSuggestions, key = { it.text }) { suggestion ->
                                    AssistChip(
                                        onClick = {
                                            note = suggestion.text
                                            onNoteTagClick(suggestion.text)
                                            finishDialogEditing()
                                        },
                                        label = { Text("${suggestion.text} · ${suggestion.count}次") }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("日期：")
                            OutlinedTextField(
                                value = dateText,
                                onValueChange = { next ->
                                    val formatted = formatDateInput(next)
                                    dateText = formatted
                                    dateError = formatted.length == 10 && parseDateInput(formatted) == null
                                },
                                placeholder = { Text("yyyy/mm/dd") },
                                singleLine = true,
                                isError = dateError,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        dateError = parseDateInput(dateText) == null
                                        if (!dateError) finishDialogEditing()
                                    }
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (dateError) {
                            Text(
                                "日期无效，请按 yyyy/mm/dd 输入",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                enabled = parsedDate != null,
                                onClick = { parsedDate?.let { setDate(it.minusDays(1)) } }
                            ) { Text("−1天") }

                            TextButton(onClick = { setDate(LocalDate.now()) }) {
                                Text("今天")
                            }

                            TextButton(
                                enabled = parsedDate != null,
                                onClick = { parsedDate?.let { setDate(it.plusDays(1)) } }
                            ) { Text("+1天") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = calculatedAmount?.let { it > 0.0 } == true && parsedDate != null,
                onClick = {
                    val amount = calculatedAmount?.takeIf { it > 0.0 } ?: return@TextButton
                    val date = parsedDate ?: return@TextButton
                    onSave(
                        Expense(
                            id = initialExpense?.id ?: UUID.randomUUID().toString(),
                            amount = amount,
                            category = categories[categoryIndex].key,
                            note = note.trim(),
                            date = date
                        )
                    )
                }
            ) {
                Text(if (initialExpense == null) "保存" else "保存修改")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
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
