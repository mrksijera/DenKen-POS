package com.app.denkenpos

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private object InventoryColumns {
    val ID = listOf("ID")
    val PRODUCT = listOf("Product Name", "Product", "Name", "Item")
    val LOCATION = listOf("Location")
    val STOCK = listOf("Current Stock", "Stock", "Quantity", "On Hand", "Stock Level")
    val THRESHOLD = listOf("Reorder Threshold", "Low Stock Threshold", "Reorder Level", "Min Stock", "Reorder Point")
}

data class InventoryItem(
    val productId: Int, // fallback value: -1 -> uncategorized
    val product: String,
    val stock: Int,
    val threshold: Int? // null if sheet doesn't define a per-item threshold
)

// Fetches the Inventory sheet through App Script call
suspend fun fetchInventory(context: Context, location: String): List<InventoryItem> {
    val table = fetchSheetTable(context, "inventory", location = location)
    if (table.isEmpty()) return emptyList()

    val headers = table.first()
    val rows = table.drop(1)

    val idIndex = headers.indexOfHeader((InventoryColumns.ID))
    val productIndex = headers.indexOfHeader(InventoryColumns.PRODUCT)
    val locationIndex = headers.indexOfHeader(InventoryColumns.LOCATION)
    val stockIndex = headers.indexOfHeader(InventoryColumns.STOCK)
    val thresholdIndex = headers.indexOfHeader(InventoryColumns.THRESHOLD) // optional column

    if (productIndex == -1 || locationIndex == -1 || stockIndex == -1) {
        log("fetchInventory: missing required column(s) in Inventory sheet header: $headers " +
                "(product=$productIndex, location=$locationIndex, stock=$stockIndex)")
        return emptyList()
    }

    val requiredCount = maxOf(idIndex, productIndex, locationIndex, stockIndex, thresholdIndex) + 1

    return rows.mapNotNull { row ->
        if (row.size >= requiredCount) {
            val rowLocation = row[locationIndex].trim()
            if (!rowLocation.equals(location, ignoreCase = true)) return@mapNotNull null // safety net for edge cases

            val productId = if (idIndex == -1) -1
            else row[idIndex].trim().toDoubleOrNull()?.toInt() ?: -1
            val product = row[productIndex].trim()
            val stock = row[stockIndex].trim().toDoubleOrNull()?.toInt() ?: return@mapNotNull null
            val threshold = if (thresholdIndex == -1) null
            else row[thresholdIndex].trim().toDoubleOrNull()?.toInt()

            if (product.isEmpty()) return@mapNotNull null

            InventoryItem(productId = productId, product = product, stock = stock, threshold = threshold)
        } else null
    }.sortedBy { it.product.lowercase() }
}

// grid
@Immutable
sealed class InventoryGridItem {
    data class Header(val category: String) : InventoryGridItem()
    data class ItemCard(val item: InventoryItem) : InventoryGridItem()
}

fun buildInventoryGridItems(
    inventory: List<InventoryItem>,
    categoryOf: (Int) -> String,
    categoryOrder: List<String>
): List<InventoryGridItem> {
    val grouped = inventory.groupBy { categoryOf(it.productId) }
    val result = mutableListOf<InventoryGridItem>()

    // Categories sheet order first, then any leftover categories (e.g. "Uncategorized") alphabetically at the end.
    val orderedCategories = categoryOrder.filter { grouped.containsKey(it) } +
            grouped.keys.filterNot { categoryOrder.contains(it) }.sorted()

    orderedCategories.forEach { category ->
        result.add(InventoryGridItem.Header(category))

        grouped[category]?.sortedBy { it.product.lowercase() }?.forEach {
            result.add(InventoryGridItem.ItemCard(it))
        }
    }

    return result
}

@Composable
fun InventoryScreen(
    appLocation: String?,
    products: List<Product>,
    categoryOrder: List<String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var inventory by remember { mutableStateOf<List<InventoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val fallbackThreshold = remember { getLowStockThreshold(context) }
    val categoryMap = remember(products) { products.associate { it.id to it.category }}
    val uncategorizedLabel = stringResource(R.string.uncategorized)

    LaunchedEffect(reloadKey) {
        isLoading = true
        hasError = false

        if (appLocation == null) {
            hasError = true
            isLoading = false
            return@LaunchedEffect
        }

        inventory = try {
            fetchInventory(context, appLocation)
        } catch (e: Exception) {
            log("fetchInventory failed: ${e.message}")
            hasError = true
            emptyList()
        }

        isLoading = false
    }

    val gridItems = remember(inventory, categoryMap, categoryOrder) {
        buildInventoryGridItems(
            inventory = inventory,
            categoryOf = { id -> categoryMap[id] ?: uncategorizedLabel },
            categoryOrder = categoryOrder
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = "Back"
                )
            }

            Text(
                text = stringResource(R.string.inventory),
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { reloadKey++ }) {
                Icon(
                    painter = painterResource(R.drawable.sync),
                    contentDescription = stringResource(R.string.refreshProducts)
                )
            }
        }

        HorizontalDivider()

        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF6C63FF))
            }

            hasError -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.loadInventoryFailed),
                        color = colorResource(R.color.Cancel),
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    ConfirmButton(onClick = { reloadKey++ }) {
                        Text(stringResource(R.string.retry), fontSize = 20.sp)
                    }
                }
            }

            inventory.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.emptyInventory),
                    fontSize = 18.sp,
                    color = colorResource(R.color.slateGray),
                    textAlign = TextAlign.Center
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(
                    count = gridItems.size,
                    key = { index ->
                        when (val item = gridItems[index]) {
                            is InventoryGridItem.Header -> "header_${item.category}"
                            is InventoryGridItem.ItemCard -> "item_${item.item.productId}_${item.item.product}"
                        }
                    },
                    span = { index ->
                        when (gridItems[index]) {
                            is InventoryGridItem.Header -> GridItemSpan(maxLineSpan)
                            is InventoryGridItem.ItemCard -> GridItemSpan(1)
                        }
                    }
                ) { index ->
                    when (val item = gridItems[index]) {
                        is InventoryGridItem.Header -> InventoryCategoryHeader(item.category)
                        is InventoryGridItem.ItemCard -> InventoryItemCard(item.item, fallbackThreshold)
                    }
                }
            }
        }
    }
}

@Composable
fun InventoryCategoryHeader(category: String) {
    Text(
        text = category,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 3.dp)
    )
}

@Composable
fun InventoryItemCard(item: InventoryItem, fallbackThreshold: Int) {
    val threshold = item.threshold ?: fallbackThreshold
    val isLowStock = item.stock <= threshold

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = if (isLowStock)
            CardDefaults.cardColors(containerColor = colorResource(R.color.Cancel).copy(alpha = 0.08f))
        else
            CardDefaults.cardColors()

    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.product,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                color = if (isLowStock) colorResource(R.color.Cancel) else Color.Black
            )

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.stock.toString(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = if (isLowStock) colorResource(R.color.Cancel) else Color.Black,
                    modifier = Modifier.weight(1f)
                )

                if (isLowStock) {
                    Text(
                        text = stringResource(R.string.lowStock),
                        color = colorResource(R.color.Cancel),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}