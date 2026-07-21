package com.app.denkenpos

import android.content.Context
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

@Serializable
private data class SheetTableResponse(
    val success: Boolean = false,
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val error: String? = null
)

/**
 * Reads a sheet through the same Apps Script Web App already used to log sales
 * [type] must match one of the keys the doGet handler dispatches on (SHEET_NAMES map in AS): "products", "categories", "staff", or "locations".
 * Returns headers as the first row followed by data rows
 */
suspend fun fetchSheetTable(context: Context, type: String): List<List<String>> {
    val url = getSalesLogUrl(context)
    val secret = getSalesLogSecret(context)

    val response: SheetTableResponse = httpClient.get(url) {
        parameter("type", type)
        parameter("secret", secret)
        timeout { requestTimeoutMillis = 20000 }
    }.body()

    if (!response.success) {
        throw IllegalStateException("fetchSheetTable($type): ${response.error ?: "unknown error"}")
    }

    return listOf(response.headers) + response.rows
}