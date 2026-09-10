package com.anxiousspending.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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

private val categories = listOf(
    "shopping" to "BUY STH NEW",
    "food" to "EATING",
    "game" to "婷芷我说婷芷",
    "ai" to "别BAN我",
    "misc" to "生活杂费",
    "transport" to "🚈🚕🚌嘟嘟",
    "travel" to "GO GO GO出发喽",
    "snack" to "STOP EAT",
    "books" to "今天你看书了吗",
    "investment" to "HOPE💹",
    "medical" to "今天哪里又痛了我的大小姐"
)

@Composable
fun AnxiousSpendingApp(store: ExpenseStore) {
    var expenses by remember { mutableStateOf(store.load()) }
    var page by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }

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
            if (page == 0) LedgerPage(expenses) else AnalysisPage(expenses)
        }
    }

    if (showAdd) {
        AddExpenseDialog(
            onDismiss = { showAdd = false },
            onSave = { expense ->
                expenses = (expenses + expense).sortedByDescending { it.date }
                store.save(expenses)
                showAdd = false
            }
        )
    }
}

@Composable
private fun LedgerPage(expenses: List<Expense>) {
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
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(date.format(DateTimeFormatter.ofPattern("M月d日 EEE")), fontWeight = FontWeight.Bold)
                    Text("¥%.2f".format(dayExpenses.sumOf { it.amount }))
                }
            }
            items(dayExpenses, key = { it.id }) { expense ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(expense.category, fontWeight = FontWeight.Medium)
                            if (expense.note.isNotBlank()) Text(expense.note, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("¥%.2f".format(expense.amount), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisPage(expenses: List<Expense>) {
    val currentMonth = YearMonth.now()
    val monthExpenses = expenses.filter { YearMonth.from(it.date) == currentMonth }
    val yearExpenses = expenses.filter { it.date.year == LocalDate.now().year }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("本月", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("¥%.2f".format(monthExpenses.sumOf { it.amount }), style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Text("今年", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("¥%.2f".format(yearExpenses.sumOf { it.amount }), style = MaterialTheme.typography.headlineMedium)
        }
        item { Text("本月分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(monthExpenses.groupBy { it.category }.toList().sortedByDescending { (_, list) -> list.sumOf { it.amount } }) { (category, list) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(category)
                Text("¥%.2f".format(list.sumOf { it.amount }))
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("金额") },
                    singleLine = true
                )
                Box {
                    OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${categories[categoryIndex].first} · ${categories[categoryIndex].second}")
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        categories.forEachIndexed { index, pair ->
                            DropdownMenuItem(
                                text = { Text("${pair.first} · ${pair.second}") },
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
                Text("日期：今天 ${LocalDate.now()}", style = MaterialTheme.typography.bodySmall)
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
                            category = categories[categoryIndex].first,
                            note = note.trim(),
                            date = LocalDate.now()
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
