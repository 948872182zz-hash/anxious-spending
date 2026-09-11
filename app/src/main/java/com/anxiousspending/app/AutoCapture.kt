package com.anxiousspending.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class CapturedPayment(
    val id: String = UUID.randomUUID().toString(),
    val notificationKey: String,
    val sourceKey: String,
    val sourceName: String,
    val amount: Double,
    val occurredAtMillis: Long,
    val title: String,
    val text: String
)

object CaptureSignal {
    var revision by mutableLongStateOf(0L)
        private set

    fun ping() {
        revision += 1L
    }
}

class CaptureStore(context: Context) {
    private val prefs = context.getSharedPreferences("anxious_spending_auto_capture", Context.MODE_PRIVATE)

    fun load(): List<CapturedPayment> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        CapturedPayment(
                            id = obj.getString("id"),
                            notificationKey = obj.optString("notificationKey", ""),
                            sourceKey = obj.getString("sourceKey"),
                            sourceName = obj.getString("sourceName"),
                            amount = obj.getDouble("amount"),
                            occurredAtMillis = obj.getLong("occurredAtMillis"),
                            title = obj.optString("title", ""),
                            text = obj.optString("text", "")
                        )
                    )
                }
            }.sortedByDescending { it.occurredAtMillis }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addIfNew(item: CapturedPayment) {
        val current = load().toMutableList()
        if (current.any { it.notificationKey.isNotBlank() && it.notificationKey == item.notificationKey }) return
        current.add(item)
        save(current.sortedByDescending { it.occurredAtMillis }.take(MAX_ITEMS))
        CaptureSignal.ping()
    }

    @Synchronized
    fun remove(id: String) {
        save(load().filterNot { it.id == id })
        CaptureSignal.ping()
    }

    @Synchronized
    fun clear() {
        save(emptyList())
        CaptureSignal.ping()
    }

    private fun save(items: List<CapturedPayment>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("notificationKey", item.notificationKey)
                    put("sourceKey", item.sourceKey)
                    put("sourceName", item.sourceName)
                    put("amount", item.amount)
                    put("occurredAtMillis", item.occurredAtMillis)
                    put("title", item.title)
                    put("text", item.text)
                }
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private companion object {
        const val KEY_ITEMS = "pending_capture_items"
        const val MAX_ITEMS = 300
    }
}

private data class CaptureSource(val key: String, val name: String)

private fun captureSource(packageName: String): CaptureSource? = when (packageName) {
    "com.tencent.mm" -> CaptureSource("wechat", "微信")
    "com.eg.android.AlipayGphone" -> CaptureSource("alipay", "支付宝")
    else -> null
}

private val outgoingKeywords = listOf(
    "支付成功",
    "付款成功",
    "已支付",
    "成功支付",
    "消费",
    "支出",
    "扣款",
    "微信支付",
    "付款"
)

private val incomingKeywords = listOf(
    "收款",
    "到账",
    "退款",
    "退回",
    "收入",
    "转入"
)

private val amountPatterns = listOf(
    Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)"""),
    Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元""")
)

private fun extractPaymentAmount(text: String): Double? {
    if (incomingKeywords.any { text.contains(it) }) return null
    if (outgoingKeywords.none { text.contains(it) }) return null

    for (pattern in amountPatterns) {
        val match = pattern.find(text) ?: continue
        val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: continue
        if (value > 0.0 && value < 10_000_000.0) return value
    }
    return null
}

class PaymentNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val source = captureSource(notification.packageName) ?: return
        if (notification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        val text = body.trim()
        val combined = listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ")
        val amount = extractPaymentAmount(combined) ?: return

        CaptureStore(applicationContext).addIfNew(
            CapturedPayment(
                notificationKey = notification.key,
                sourceKey = source.key,
                sourceName = source.name,
                amount = amount,
                occurredAtMillis = notification.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                title = title,
                text = text
            )
        )
    }
}

fun hasNotificationCaptureAccess(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    val component = ComponentName(context, PaymentNotificationListener::class.java)
    return manager.isNotificationListenerAccessGranted(component)
}

fun openNotificationCaptureSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

private fun captureTimeText(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))

@androidx.compose.runtime.Composable
fun AutoCaptureDialog(
    store: CaptureStore,
    items: List<CapturedPayment>,
    onDismiss: () -> Unit,
    onUse: (CapturedPayment) -> Unit
) {
    val context = LocalContext.current
    val accessGranted = hasNotificationCaptureAccess(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动捕获箱") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!accessGranted) {
                    Text(
                        "先给「坚持焦虑地花钱中」开启通知读取权限。只处理微信和支付宝，并且只有识别到付款关键词 + 人民币金额时才会进入这里。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = { openNotificationCaptureSettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开启通知读取") }
                } else {
                    Text(
                        "监听已开启 · 只抓微信 / 支付宝付款金额，不读短信，也不会自动写进正式账本。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (items.isEmpty()) {
                    Text("还没有捕获到付款通知。开好权限后，下一笔微信或支付宝付款拿来当小白鼠。")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 430.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "${item.sourceName} · ¥${"%.2f".format(item.amount)}",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                captureTimeText(item.occurredAtMillis),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    if (item.title.isNotBlank() || item.text.isNotBlank()) {
                                        Text(
                                            listOf(item.title, item.text)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" · ")
                                                .take(120),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { store.remove(item.id) }) { Text("忽略") }
                                        Spacer(Modifier.width(4.dp))
                                        OutlinedButton(onClick = { onUse(item) }) { Text("去分类") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关掉") }
        },
        dismissButton = {
            if (items.isNotEmpty()) {
                TextButton(onClick = { store.clear() }) { Text("清空") }
            }
        }
    )
}
