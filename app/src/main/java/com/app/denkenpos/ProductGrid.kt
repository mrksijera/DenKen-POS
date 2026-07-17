package com.app.denkenpos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

@Immutable
data class Product(
    val name: String,
    val price: Double,
    val category: String,
    // val imageRes: Int,
    val id: Int = ProductIdGenerator.next()
)

@Immutable
sealed class ProductGridItem {
    data class Header(val category: String) : ProductGridItem()
    data class ProductCard(val product: Product) : ProductGridItem()
}

object ProductIdGenerator{
    private var nextId = 1

    fun next(): Int{
        return nextId++
    }
}

// grid functions
fun buildGridItems(products : List<Product>): List<ProductGridItem> {
    val grouped = products.groupBy { it.category }
    val result = mutableListOf<ProductGridItem>()

    grouped.forEach { (category, items) ->
        result.add(ProductGridItem.Header(category))

        items.forEach {
            result.add(ProductGridItem.ProductCard(it))
        }
    }

    return result
}

fun buildCategoryIndexMap(gridItems: List<ProductGridItem>): Map<String, Int> {
    val map = mutableMapOf<String, Int>()

    gridItems.forEachIndexed{ index, item ->
        if (item is ProductGridItem.Header) {
            map[item.category] = index
        }
    }

    return map
}

@Composable
fun PosScreen(
    products: List<Product>,
    currentCashier: Cashier? = null,
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val gridItems = remember { buildGridItems(products) }
    val categoryIndexMap = remember { buildCategoryIndexMap(gridItems) }
    val categoryPositions = remember {
        categoryIndexMap.entries.sortedBy { it.value }
    }

    val categories = categoryIndexMap.keys.toList()

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val currentCategory by remember {
        derivedStateOf {
            val firstVisible = gridState.firstVisibleItemIndex

            categoryPositions
                .lastOrNull { it.value <= firstVisible }
                ?.key
        }
    }

    val cart = remember{ CartState() }

    // layouts
    Row(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.weight(0.55f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                CategoryRow(
                    categories = categories,
                    selectedCategory = currentCategory,
                    onCategoryClick = { category ->
                        val index = categoryIndexMap[category]

                        if (index != null) {
                            scope.launch {
                                gridState.animateScrollToItem(index)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                PosMenuButton(
                    currentCashier = currentCashier,
                    onLogout = onLogout,
                    onRefresh = { },
                    onHistory = {}
                )
            }

            HorizontalDivider()

            ProductGrid(
                gridItems = gridItems,
                gridState = gridState,
                modifier = Modifier
                    .fillMaxHeight(),
                onProductClick = { product -> cart.add(product) }
            )
        }

        VerticalDivider()

        CartPanel(
            cartItems = cart.items,
            lastModifiedEvent = cart.lastModifiedEvent,
            total = cart.total,
            modifier = Modifier
                .weight(0.45f)
                .padding(8.dp),
            onIncrease = cart::increase,
            onDecrease = cart::decrease,
            onEdit = cart::editQuantity,
            onClear = cart::clear,
            onBuildTransaction = { cart.buildTransaction(currentCashier) },
            onConfirmTransaction = { transaction ->
                cart.clear()
                enqueueTransaction(context, transaction)
            }
        )
    }

    cart.editingProductId?.let { productId ->
        EditQuantityDialog(
            editingQuantity = cart.editingQuantity,
            onQuantityChange = { cart.editingQuantity = it },
            onConfirm = { newQty ->
                cart.setQuantity(productId, newQty)
                cart.editingProductId = null
            },
            onDismiss = { cart.editingProductId = null}
        )
    }
}

@Composable
fun CategoryRow(
    categories: List<String>,
    selectedCategory: String?,
    onCategoryClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.padding(horizontal = 8.dp)
    ) {
        items(categories) { category ->
            val selected = category == selectedCategory

            FilterChip(
                selected = selected,
                onClick = { onCategoryClick(category) },
                label = { Text(category) },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@Composable
fun ProductGrid(
    gridItems: List<ProductGridItem>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    onProductClick: (Product) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        state = gridState,
        modifier = modifier,
        contentPadding = PaddingValues(8.dp)
    ) {
        items(
            count = gridItems.size,
            key = {index ->
                when (val item = gridItems[index]) {
                    is ProductGridItem.Header -> "header_${item.category}"
                    is ProductGridItem.ProductCard -> item.product.id
                }
            },
            span = { index ->
                when (gridItems[index]) {
                    is ProductGridItem.Header -> GridItemSpan(maxLineSpan)
                    is ProductGridItem -> GridItemSpan(1)
                }
            }
        ) { index ->
            when (val item = gridItems[index]) {
                is ProductGridItem.Header -> CategoryHeader(item.category)

                is ProductGridItem.ProductCard -> ProductCard(item.product, onClick = onProductClick)
            }
        }
    }
}

@Composable
fun CategoryHeader(category: String) {
    Text(
        text = category,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 3.dp,
                bottom = 3.dp
            )
    )
}

@Composable
fun ProductCard(
    product: Product,
    onClick: (Product) -> Unit
){
    Card (
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        onClick = { onClick(product) }
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = product.name,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(String.format(
                Locale.getDefault(),
                "%s %.2f",
                stringResource(R.string.currency), product.price)
            )
        }
    }
}