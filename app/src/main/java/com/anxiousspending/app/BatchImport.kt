package com.anxiousspending.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class ImportPreview(
    val fileName: String,
    val rowsRead: Int,
    val accepted: List<Expense>,
    val duplicateCount: Int,
    val errors: List<String>,
    val minDate: LocalDate?,
    val maxDate: LocalDate?
)

private val importCategoryMap = mapOf(
    "购物" to "shopping",
    "餐饮" to "food",
    "游戏" to "game",
    "娱乐" to "entertainment",
    "AI" to "ai",
    "ai" to "ai",
    "杂费" to "misc",
    "交通" to "transport",
    "旅游" to "travel",
    "零食" to "snack",
    "书籍" to "books",
    "投资" to "investment",
    "医疗" to "medical",
    "工资" to "salary",
    "红包" to "red_packet",
    "理财" to "investment_income",
    "AA收入" to "aa_income"
)

private val importPaymentMap = mapOf(
    "支付宝" to "alipay",
    "微信" to "wechat",
    "其他" to "other",
    "alipay" to "alipay",
    "wechat" to "wechat",
    "other" to "other"
)

private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                current.append('"')
                i++
            }
            ch == '"' -> quoted = !quoted
            ch == ',' && !quoted -> {
                result += current.toString()
                current.clear()
            }
            else -> current.append(ch)
        }
        i++
    }
    result += current.toString()
    return result
}

private fun readCsvRows(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val logical = StringBuilder()
    var quoted = false

    for (physicalLine in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        if (logical.isNotEmpty()) logical.append('\n')
        logical.append(physicalLine)

        var i = 0
        while (i < physicalLine.length) {
            if (physicalLine[i] == '"') {
                if (quoted && i + 1 < physicalLine.length && physicalLine[i + 1] == '"') {
                    i++
                } else {
                    quoted = !quoted
                }
            }
            i++
        }

        if (!quoted) {
            if (logical.isNotBlank()) rows += parseCsvLine(logical.toString())
            logical.clear()
        }
    }
    if (logical.isNotBlank()) rows += parseCsvLine(logical.toString())
    return rows
}

private fun decodeCsv(bytes: ByteArray): String {
    val utf8 = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    return utf8 ?: String(bytes, charset("GB18030"))
}

private fun importSignature(e: Expense): String = listOf(
    e.date.toString(),
    e.entryType,
    "%.4f".format(e.amount),
    e.currency,
    "%.4f".format(e.cnyAmount),
    e.category,
    e.note.trim(),
    e.paymentSource
).joinToString("|")

private fun parseDateForImport(raw: String): LocalDate? {
    val value = raw.trim()
    val patterns = listOf("yyyy/MM/dd", "yyyy-M-d", "yyyy-MM-dd", "yyyy/M/d")
    for (pattern in patterns) {
        val parsed = runCatching { LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

private fun buildImportPreview(
    fileName: String,
    bytes: ByteArray,
    existing: List<Expense>
): ImportPreview {
    val text = decodeCsv(bytes).removePrefix("\uFEFF")
    val rows = readCsvRows(text)
    if (rows.isEmpty()) {
        return ImportPreview(fileName, 0, emptyList(), 0, listOf("文件里没有数据"), null, null)
    }

    val headers = rows.first().map { it.trim().removePrefix("\uFEFF") }
    val index = headers.withIndex().associate { it.value to it.index }
    val required = listOf("日期", "收支类型", "金额", "分类")
    val missing = required.filterNot { it in index }
    if (missing.isNotEmpty()) {
        return ImportPreview(
            fileName,
            rows.size - 1,
            emptyList(),
            0,
            listOf("缺少必填列：${missing.joinToString("、")}"),
            null,
            null
        )
    }

    fun cell(row: List<String>, name: String): String =
        index[name]?.let { row.getOrNull(it) }?.trim().orEmpty()

    val existingSignatures = existing.map(::importSignature).toMutableSet()
    val accepted = mutableListOf<Expense>()
    val errors = mutableListOf<String>()
    var duplicateCount = 0

    rows.drop(1).forEachIndexed { zeroIndex, row ->
        val rowNumber = zeroIndex + 2
        if (row.all { it.isBlank() }) return@forEachIndexed

        val date = parseDateForImport(cell(row, "日期"))
        val entryType = when (cell(row, "收支类型")) {
            "支出", "expense" -> "expense"
            "收入", "income" -> "income"
            else -> null
        }
        val amount = cell(row, "金额").replace(",", "").toDoubleOrNull()
        val categoryRaw = cell(row, "分类")
        val category = importCategoryMap[categoryRaw]
        val currency = cell(row, "币种").ifBlank { "CNY" }.uppercase()
        val cnyAmount = cell(row, "人民币金额").replace(",", "").toDoubleOrNull()
            ?: if (currency == "CNY") amount else null
        val payment = importPaymentMap[cell(row, "支付来源").ifBlank { "其他" }]
        val note = cell(row, "备注")

        val problems = mutableListOf<String>()
        if (date == null) problems += "日期"
        if (entryType == null) problems += "收支类型"
        if (amount == null || amount <= 0.0) problems += "金额"
        if (category == null) problems += "分类"
        if (currency !in setOf("CNY", "USD", "KRW", "JPY")) problems += "币种"
        if (cnyAmount == null || cnyAmount <= 0.0) problems += "人民币金额"
        if (payment == null) problems += "支付来源"

        if (problems.isNotEmpty()) {
            errors += "第$rowNumber行：${problems.joinToString("、")}有问题"
            return@forEachIndexed
        }

        val safeAmount = amount!!
        val safeCny = cnyAmount!!
        val expense = Expense(
            amount = safeAmount,
            category = category!!,
            note = note,
            date = date!!,
            currency = currency,
            exchangeRateToCny = if (currency == "CNY") 1.0 else safeCny / safeAmount,
            cnyAmount = safeCny,
            paymentSource = payment!!,
            entryType = entryType!!
        )

        val signature = importSignature(expense)
        if (signature in existingSignatures) {
            duplicateCount++
        } else {
            existingSignatures += signature
            accepted += expense
        }
    }

    return ImportPreview(
        fileName = fileName,
        rowsRead = rows.drop(1).count { row -> row.any { it.isNotBlank() } },
        accepted = accepted,
        duplicateCount = duplicateCount,
        errors = errors,
        minDate = accepted.minOfOrNull { it.date },
        maxDate = accepted.maxOfOrNull { it.date }
    )
}

@Composable
fun BatchImportDialog(
    existingEntries: List<Expense>,
    onDismiss: () -> Unit,
    onImport: (List<Expense>) -> Unit
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadError = null
        preview = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取文件")
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "CSV"
            buildImportPreview(name, bytes, existingEntries)
        }.onFailure {
            loadError = it.message ?: "读取失败"
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量导入旧账") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "按月慢慢来。当前先支持 CSV；额外列会自动忽略，只读取模板里的字段。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) }
                ) {
                    Text(if (preview == null) "选择一个月的 CSV" else "重新选择文件")
                }

                loadError?.let {
                    Text("读取失败：$it", color = MaterialTheme.colorScheme.error)
                }

                preview?.let { p ->
                    HorizontalDivider()
                    Text(p.fileName, fontWeight = FontWeight.SemiBold)
                    if (p.minDate != null && p.maxDate != null) {
                        Text("日期：${p.minDate} ～ ${p.maxDate}")
                    }
                    Text("读取 ${p.rowsRead} 行 · 可导入 ${p.accepted.size} 笔")
                    Text("跳过重复 ${p.duplicateCount} 笔 · 错误 ${p.errors.size} 行")

                    val expenseCount = p.accepted.count { it.entryType == "expense" }
                    val incomeCount = p.accepted.count { it.entryType == "income" }
                    val expenseAmount = p.accepted.filter { it.entryType == "expense" }.sumOf { it.cnyAmount }
                    val incomeAmount = p.accepted.filter { it.entryType == "income" }.sumOf { it.cnyAmount }
                    Text("支出 $expenseCount 笔 ¥${"%.2f".format(expenseAmount)}")
                    Text("收入 $incomeCount 笔 ¥${"%.2f".format(incomeAmount)}")

                    if (p.errors.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text("先修这些行：", fontWeight = FontWeight.SemiBold)
                        p.errors.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (p.errors.size > 8) {
                            Text("……还有 ${p.errors.size - 8} 行", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (p.accepted.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onImport(p.accepted) }
                        ) {
                            Text("确认导入 ${p.accepted.size} 笔")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("先不导") }
            }
        }
    )
}
