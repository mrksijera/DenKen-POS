package com.app.denkenpos

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class TransactionItem(
    val productId: Int,
    val name: String,
    val price: Double,
    val quantity: Int
) {
    val lineTotal: Double get() = price * quantity
}

@Serializable
data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val cashierId: Int,
    val cashierName: String,
    val location: String,
    val items: List<TransactionItem>,
    val total: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
private data class SalesLogItem(val productId: Int, val name: String, val price: Double, val quantity: Int)

@Serializable
private data class SalesLogPayload(
    val secret: String,
    val id: String,
    val cashierId: Int,
    val cashierName: String,
    val location: String,
    val timestamp: Long,
    val items: List<SalesLogItem>
)

@Serializable
private data class SalesLogResponse(val success: Boolean, val status: String? = null, val error: String? = null)

// Queue persistence (mirrors saveCashiers/loadCashiers in Auth.kt)

enum class TransactionStatus { PENDING, SENT, FAILED }

@Serializable
data class TransactionRecord(
    val transaction: Transaction,
    val status: TransactionStatus = TransactionStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null
)

private const val PREFS_NAME = "pos"
private const val KEY_TRANSACTION_HISTORY = "transaction_history"
private const val MAX_ATTEMPTS = 5

fun loadTransactionHistory(context: Context): List<TransactionRecord> {
    val jsonString = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TRANSACTION_HISTORY, null) ?: return emptyList()

    return try {
        json.decodeFromString<List<TransactionRecord>>(jsonString)
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveTransactionHistory(context: Context, records: List<TransactionRecord>) {
    val pruned = pruneHistory(records)
    val jsonString = json.encodeToString(pruned)
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit { putString(KEY_TRANSACTION_HISTORY, jsonString) }
}

private const val HISTORY_RETENTION_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

private fun pruneHistory(records: List<TransactionRecord>): List<TransactionRecord> {
    val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS

    return records.filter { record ->
        record.status != TransactionStatus.SENT || record.transaction.timestamp >= cutoff
    }
}

fun enqueueTransaction(context: Context, transaction: Transaction) {
    val updated = loadTransactionHistory(context) + TransactionRecord(transaction)
    saveTransactionHistory(context, updated)
    scheduleLogSync(context)
}

/** Reset a FAILED record back to PENDING so the worker will pick it up again. */
fun retryTransaction(context: Context, transactionId: String) {
    val updated = loadTransactionHistory(context).map {
        if (it.transaction.id == transactionId) it.copy(status = TransactionStatus.PENDING, attempts = 0, lastError = null)
        else it
    }
    saveTransactionHistory(context, updated)
    scheduleLogSync(context)
}

// Sending
suspend fun sendTransactionToSheet(context: Context, transaction: Transaction): Boolean {
    val url = getSalesLogUrl(context)
    if (url.isBlank()) {
        log("sales log URL not configured, skipping")
        return false
    }

    val payload = SalesLogPayload(
        secret = getSalesLogSecret(context),
        id = transaction.id,
        cashierId = transaction.cashierId,
        cashierName = transaction.cashierName,
        location = transaction.location,
        timestamp = transaction.timestamp,
        items = transaction.items.map { SalesLogItem(it.productId, it.name, it.price, it.quantity) }
    )

    return try {
        var response = httpClient.post(url) {
            log("sending payload for transaction ${payload.id}")
            contentType(ContentType.Application.Json)
            setBody(payload)
            timeout {
                // give Apps Script more leeway to respond so slow-but-successful writes aren't considered a failure.
                requestTimeoutMillis = 20000
            }

        }

        // if POST response is 302, follow redirect to get the response
        if (response.status.value in 300..399) {
            val location = response.headers[HttpHeaders.Location]

            response = if (location != null) {
                log("sendTransactionToSheet: following redirect to fetch actual response")
                httpClient.get(location) {
                    timeout { requestTimeoutMillis = 20000 }
                }
            } else {
                log("sendTransactionToSheet: got ${response.status} with no Location header")
                return false
            }
        }

        val bodyText = response.bodyAsText()

        if (!response.status.isSuccess()) {
            log("sendTransactionToSheet HTTP ${response.status}: $bodyText")
            return false
        }

        val parsed = try {
            log("response transaction parsed")
            json.decodeFromString<SalesLogResponse>(bodyText)
        } catch (e: Exception) {
            log("sendTransactionToSheet: non-JSON response: $bodyText")
            return false
        }

        log("sendTransactionToSheet: response for ${transaction.id}: success=${parsed.success}, status=${parsed.status}")
        parsed.success
    } catch (e: Exception) {
        log("sendTransactionToSheet failed: ${e.message}")
        false
    }
}

// WorkManager
private const val SYNC_WORK_NAME = "transaction_log_sync"

fun scheduleLogSync(context: Context) {
    val request = OneTimeWorkRequestBuilder<TransactionLogWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        SYNC_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        request
    )
}

class TransactionLogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var records = loadTransactionHistory(applicationContext)
        val pending = records.filter { it.status == TransactionStatus.PENDING }
        if (pending.isEmpty()) return Result.success()

        var stillPending = false

        for (record in pending) {
            val sent = try {
                sendTransactionToSheet(applicationContext, record.transaction)
            } catch (e: Exception) {
                false
            }

            records = if (sent) {
                records.map {
                    if (it.transaction.id == record.transaction.id)
                        it.copy(status = TransactionStatus.SENT)
                    else it
                }
            } else {
                val attempts = record.attempts + 1
                val giveUp = attempts >= MAX_ATTEMPTS

                records.map {
                    if (it.transaction.id == record.transaction.id)
                        it.copy(
                            status = if (giveUp) TransactionStatus.FAILED else TransactionStatus.PENDING,
                            attempts = attempts,
                            lastError = "send failed"
                        )
                    else it
                }

                saveTransactionHistory(applicationContext, records)

                if (!giveUp) {
                    stillPending = true
                    break // preserve order: stop at first still-retryable failure
                }
                records // give up on this one, keep going to the next pending record
            }
        }

        saveTransactionHistory(applicationContext, records)
        return if (stillPending) Result.retry() else Result.success()
    }
}

@Composable
fun TransactionHistoryDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    var records by remember { mutableStateOf(loadTransactionHistory(context).sortedByDescending { it.transaction.timestamp }) }
    var expandedIds by remember { mutableStateOf(setOf<String>()) }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    // poll for status change
    LaunchedEffect(Unit) {
        while(true) {
            delay(1000.milliseconds)
            records = loadTransactionHistory(context).sortedByDescending { it.transaction.timestamp }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // manual control of width instead of the platform default cap
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(if (isPortrait) 0.95f else 0.7f)
                .heightIn(max = if (isPortrait) (configuration.screenHeightDp * 0.85f).dp else 700.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.transactionHistory),
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = onDismiss
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = "Close",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                if (records.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.emptyTransactionHistory),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            color = colorResource(R.color.slateGray),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(records, key = { it.transaction.id }) { record ->
                        val id = record.transaction.id

                        TransactionHistoryRow(
                            record = record,
                            expanded = expandedIds.contains(id),
                            isPortrait = isPortrait,
                            onToggleExpand = {
                                expandedIds = if (expandedIds.contains(id))
                                    expandedIds - id
                                else
                                    expandedIds + id
                            },
                            onRetry = {
                                retryTransaction(context, id)
                                records = loadTransactionHistory(context).sortedByDescending { it.transaction.timestamp }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private val historyTimestampFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

@Composable
fun TransactionHistoryRow(
    record: TransactionRecord,
    expanded: Boolean,
    isPortrait: Boolean,
    onToggleExpand: () -> Unit,
    onRetry: () -> Unit
) {
    val transaction = record.transaction
    val timestampText = remember(transaction.timestamp) {
        historyTimestampFormat.format(Date(transaction.timestamp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
            .padding(vertical = 8.dp)
    ) {
        if (isPortrait) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (expanded) ImageVector.vectorResource(R.drawable.keyboard_arrow_up) else ImageVector.vectorResource(R.drawable.keyboard_arrow_down),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(4.dp))

                Text(
                    text = timestampText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = String.format(stringResource(R.string.currency), transaction.total),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp)
            ) {
                Text(
                    text = "by ${transaction.cashierName}",
                    fontSize = 14.sp,
                    color = Color.Black,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                if (record.status == TransactionStatus.FAILED) {
                    StatusBadge(onClick = onRetry, record.status)
                } else {
                    StatusBadge(onClick = { }, record.status)
                }
            }

            Text(
                text = transaction.id,
                fontSize = 11.sp,
                color = Color.DarkGray,
                modifier = Modifier.padding(start = 28.dp, top = 2.dp)
            )
        } else {
            // top line: expand icon | timestamp | cashier | total | status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (expanded) ImageVector.vectorResource(R.drawable.keyboard_arrow_up) else ImageVector.vectorResource(R.drawable.keyboard_arrow_down),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(4.dp))

                Text(
                    text = timestampText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.width(4.dp))

                Text(
                    text = "by ${transaction.cashierName}",
                    fontSize = 16.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier.weight(0.9f)
                )

                Text(
                    text = String.format(stringResource(R.string.currency), transaction.total),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }

            // transaction ID + status badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = transaction.id,
                    fontSize = 12.sp,
                    color = Color.DarkGray,
                    modifier = Modifier
                        .padding(start = 24.dp, top = 2.dp)
                        .weight(1f)
                )

                // status badge
                if (record.status == TransactionStatus.FAILED) {
                    StatusBadge(onClick = onRetry, record.status)
                } else {
                    StatusBadge(onClick = { }, record.status)
                }
            }
        }

        // expanded item breakdown
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 8.dp, bottom = 4.dp)
            ) {
                HorizontalDivider(color = Color.LightGray)
                Spacer(Modifier.height(6.dp))

                transaction.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "${item.quantity}x ${item.name}",
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "%.2f", item.lineTotal),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(
    onClick: () -> Unit,
    status: TransactionStatus
) {
    val (label, color) = when (status) {
        TransactionStatus.SENT -> "Synced" to colorResource(R.color.Confirm)
        TransactionStatus.FAILED -> "Failed" to colorResource(R.color.Cancel)
        TransactionStatus.PENDING -> "Syncing" to Color.DarkGray
    }

    Text(
        text = label,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        textAlign = TextAlign.End
    )
}

// Shift summary

// bucket timestamps into a "business day" key (yyyy-MM-dd) rather than the literal calendar date,
// so a shift that crosses midnight (e.g. 6 PM-2 AM) is treated as one day
fun businessDateKey(timestamp: Long, cutoffHour: Int): String {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestamp

    if (calendar.get(Calendar.HOUR_OF_DAY) < cutoffHour) {
        calendar.add(Calendar.DAY_OF_YEAR, -1)
    }

    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
}

data class ShiftSummary(
    val businessDate: String,
    val transactionCount: Int,
    val totalSales: Double
)

// aggregates current cashier's summary for today from local transaction history regardless of sync status
fun computeShiftSummary(context: Context, cashierId: Int): ShiftSummary {
    val cutoffHour = getShiftCutoffHour(context)
    val today = businessDateKey(System.currentTimeMillis(), cutoffHour)

    val matching = loadTransactionHistory(context).filter { record ->
        record.transaction.cashierId == cashierId &&
                businessDateKey(record.transaction.timestamp, cutoffHour) == today
    }

    return ShiftSummary(
        businessDate = today,
        transactionCount = matching.size,
        totalSales = matching.sumOf { it.transaction.total }
    )
}

private val shiftSummaryDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private val shiftSummaryTimeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

@Composable
fun ShiftSummaryDialog(
    context: Context,
    cashierId: Int?,
    cashierName: String?,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.confirm),
    logoutTime: Long? = null // set only when shown as part of the deliberate-logout flow
) {
    val summary = remember(cashierId) {
        cashierId?.let { computeShiftSummary(context, it) }
    }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(if (isPortrait) 0.92f else 0.7f)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                if (summary != null) {
                    val dateLabel = remember(summary.businessDate) {
                        try {
                            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                .parse(summary.businessDate)
                            if (parsed != null) shiftSummaryDateFormat.format(parsed) else summary.businessDate
                        } catch (e: Exception) {
                            summary.businessDate
                        }
                    }

                    Text(
                        text = stringResource(R.string.shiftSummary) + " (" + dateLabel + ")",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = stringResource(R.string.shiftSummary),
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(4.dp))

                if (!cashierName.isNullOrBlank()) {
                    Text(
                        text = String.format(stringResource(R.string.shiftSummaryCashier), cashierName),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (logoutTime != null) {
                    Text(
                        text = String.format(
                            stringResource(R.string.shiftSummaryLogoutTime),
                            shiftSummaryTimeFormat.format(Date(logoutTime))
                        ),
                        fontSize = 16.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (summary == null) {
                    Text(
                        text = stringResource(R.string.shiftSummaryUnavailable),
                        fontSize = 16.sp
                    )
                } else {
                    ShiftSummaryRow(
                        label = stringResource(R.string.shiftSummaryTransactions),
                        value = summary.transactionCount.toString()
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    ShiftSummaryRow(
                        label = stringResource(R.string.shiftSummaryTotalSales),
                        value = String.format(stringResource(R.string.currency), summary.totalSales),
                        emphasize = true
                    )
                }

                Spacer(Modifier.height(24.dp))

                ConfirmButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = dismissLabel, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun ShiftSummaryRow(
    label: String,
    value: String,
    emphasize: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            fontSize = if (emphasize) 20.sp else 18.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = if (emphasize) 22.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}
