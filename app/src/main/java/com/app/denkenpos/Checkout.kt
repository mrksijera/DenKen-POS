package com.app.denkenpos

import android.content.Context
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ---- Queue persistence (mirrors saveCashiers/loadCashiers in Auth.kt) ----

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

// ---- Sending (stub — swap once the write endpoint is decided) ----

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

        // Apps Script web apps respond to POST with a 302 to a script.googleusercontent.com URL that actually serves the JSON body.
        // The client doesn't auto-follow redirects for POST, so without this we'd treat a successful write as a failed "302 Found" response.
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

// ---- WorkManager plumbing ----

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

    // KEEP: if a sync attempt is already queued/running, don't stack another
//    WorkManager.getInstance(context).enqueueUniqueWork(
//        SYNC_WORK_NAME,
//        ExistingWorkPolicy.KEEP,
//        request
//    )
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

    // poll for status change
    LaunchedEffect(Unit) {
        while(true) {
            delay(1000.milliseconds)
            records = loadTransactionHistory(context).sortedByDescending { it.transaction.timestamp }
        }
    }

    Dialog(onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(1f)
                .heightIn(max = 700.dp)
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
                text = String.format(Locale.getDefault(), "${stringResource(R.string.currency)} %.2f", transaction.total),
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