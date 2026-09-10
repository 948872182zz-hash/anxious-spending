package com.anxiousspending.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    categories.firstOrNull { it.key == key }?.subtitle.orEmpty()

@Composable
fun AnxiousSpendingApp(store: ExpenseStore) {
    var expenses by remember { mutableStateOf(store.load()) }
    var page by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Expense?>(null) }

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
                LedgerPage(expenses = expenses, onDelete = { pendingDelete = it })
            } else {
                AnalysisPage(expenses)
            }
        }
    }

    if (showAdd) {
        AddExpenseDialog(
            onDismiss = { showAdd = false },
            onSave = { expense ->
                persist(expenses + expense)
                showAdd = false
            }
        )
    }

    pendingDelete?.let { expense ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这笔？") },
            text = { Text("${expense.category} · ¥%.2f".format(expense.amount)) },
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
private fun LedgerPage(expenses: List<Expense>, onDelete: (Expense) -> Unit) {
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
                        Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(expense.category, fontWeight = FontWeight.SemiBold)
                            val subtitle = categorySubtitle(expense.category)
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, style = MaterialTheme.typography.labelSmall)
                            }
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
                    Column {
                        Text(category, fontWeight = FontWeight.Medium)
                        val subtitle = categorySubtitle(category)
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("¥%.2f".format(list.sumOf { it.amount }))
                }
            }
        }
    }
}

@Composable
private fun AddExpenseDialog(onDismiss: () -> Unit, onSave: (Expense) -> Unit) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var categoryIndex by remember { mutableIntStateOf(0) }
    var categoryMenu by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { next ->
                        val filtered = next.filter { it.isDigit() || it == '.' }
                        if (filtered.count { it == '.' } <= 1) amountText = filtered
                    },
                    label = { Text("金额") },
                    singleLine = true
                )
                Box {
                    OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(categories[categoryIndex].key, fontWeight = FontWeight.SemiBold)
                            Text(categories[categoryIndex].subtitle, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        categories.forEachIndexed { index, item ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(item.key, fontWeight = FontWeight.Medium)
                                        Text(item.subtitle, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = {
                                    categoryIndex = index
                                    categoryMenu = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") }
                )
                Text("日期：${selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))}")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { selectedDate = selectedDate.minusDays(1) }) { Text("−1天") }
                    TextButton(onClick = { selectedDate = LocalDate.now() }) { Text("今天") }
                    TextButton(onClick = { selectedDate = selectedDate.plusDays(1) }) { Text("+1天") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = amountText.toDoubleOrNull()?.let { it > 0 } == true,
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@TextButton
                    onSave(
                        Expense(
                            amount = amount,
                            category = categories[categoryIndex].key,
                            note = note.trim(),
                            date = selectedDate
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
