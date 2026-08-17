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

// reads a sheet in associated google sheet db
// type - "products", "categories", "staff", "locations", "inventory"
// location - serverside filter for inventory fetch
suspend fun fetchSheetTable(context: Context, type: String, location: String? = null): List<List<String>> {
    val url = getSalesLogUrl(context)
    val secret = getSalesLogSecret(context)

    val response: SheetTableResponse = httpClient.get(url) {
        parameter("type", type)
        parameter("secret", secret)
        if (location != null) parameter("location", location)
        if (location != null) log("fetching sheet with location $location")
        timeout { requestTimeoutMillis = 20000 }
    }.body()

    if (!response.success) {
        throw IllegalStateException("fetchSheetTable($type): ${response.error ?: "unknown error"}")
    }

    return listOf(response.headers) + response.rows
}