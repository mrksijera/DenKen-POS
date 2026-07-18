package com.app.denkenpos

import android.content.Context
import androidx.core.content.edit
import io.ktor.client.call.body
import io.ktor.client.request.get

private object ProductColumns {
    val ID = listOf("ID")
    val NAME = listOf("Name")
    val CATEGORY = listOf("Category")
    val PRICE = listOf("Default Price", "Price")
    val ACTIVE = listOf("Active")
}

private object CategoryColumns {
    val NAME = listOf("Name")
}

// ---- Fetching ----

suspend fun fetchProducts(url: String): List<Product> {
    val csv: String = httpClient.get(url).body()

    val lines = csv.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()

    val headers = lines.first().split(",")

    val idIndex = headers.indexOfHeader(ProductColumns.ID)
    val nameIndex = headers.indexOfHeader(ProductColumns.NAME)
    val categoryIndex = headers.indexOfHeader(ProductColumns.CATEGORY)
    val priceIndex = headers.indexOfHeader(ProductColumns.PRICE)
    val activeIndex = headers.indexOfHeader(ProductColumns.ACTIVE) // optional column

    if (idIndex == -1 || nameIndex == -1 || categoryIndex == -1 || priceIndex == -1) {
        log("fetchProducts: missing required column(s) in Products Masterlist header: $headers " +
                "(id=$idIndex, name=$nameIndex, category=$categoryIndex, price=$priceIndex)")
        return emptyList()
    }

    val requiredCount = maxOf(idIndex, nameIndex, categoryIndex, priceIndex, activeIndex) + 1

    return lines.drop(1).mapNotNull { line ->
        val parts = line.split(",")

        if (parts.size >= requiredCount) {
            // Skip rows explicitly marked inactive; treat missing/unparseable
            // Active column as active so the sheet works without it too.
            val active = if (activeIndex == -1) true
            else parts[activeIndex].trim().equals("Active", ignoreCase = true)

            if (!active) return@mapNotNull null

            val id = parts[idIndex].trim().toDoubleOrNull()?.toInt() ?: return@mapNotNull null
            val price = parts[priceIndex].trim().toDoubleOrNull() ?: return@mapNotNull null
            val name = parts[nameIndex].trim()
            val category = parts[categoryIndex].trim()

            if (name.isEmpty()) return@mapNotNull null

            Product(
                id = id,
                name = name,
                price = price,
                category = category
            )
        } else null
    }
}

suspend fun fetchCategories(url: String): List<String> {
    val csv: String = httpClient.get(url).body()
    val lines = csv.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()

    val headers = lines.first().split(",")
    val nameIndex = headers.indexOfHeader(CategoryColumns.NAME)

    if (nameIndex == -1) {
        log("fetchCategories: missing Name column in Categories sheet header: $headers")
        return emptyList()
    }

    return lines.drop(1)
        .mapNotNull { line -> line.split(",").getOrNull(nameIndex)?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

// caching

fun saveProducts(context: Context, products: List<Product>) {
    val jsonString = json.encodeToString(products)

    context.getSharedPreferences("pos", Context.MODE_PRIVATE)
        .edit { putString("products", jsonString) }
}

fun loadProducts(context: Context): List<Product> {
    val jsonString = context.getSharedPreferences("pos", Context.MODE_PRIVATE)
        .getString("products", null) ?: return emptyList()

    return try {
        json.decodeFromString<List<Product>>(jsonString)
    } catch (e: Exception) {
        emptyList()
    }
}

fun saveCategoryOrder(context: Context, categories: List<String>) {
    val jsonString = json.encodeToString(categories)

    context.getSharedPreferences("pos", Context.MODE_PRIVATE)
        .edit { putString("category_order", jsonString) }
}

fun loadCategoryOrder(context: Context): List<String> {
    val jsonString = context.getSharedPreferences("pos", Context.MODE_PRIVATE)
        .getString("category_order", null) ?: return emptyList()

    return try {
        json.decodeFromString<List<String>>(jsonString)
    } catch (e: Exception) {
        emptyList()
    }
}