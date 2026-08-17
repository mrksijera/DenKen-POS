package com.app.denkenpos

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale

@Immutable
@Serializable
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
fun buildGridItems(products : List<Product>, categoryOrder: List<String> = emptyList()): List<ProductGridItem> {
    val grouped = products.groupBy { it.category }
    val result = mutableListOf<ProductGridItem>()

    // Categories sheet order first, then any category present in the products but missing from the sheet tacked on at the end.
    val orderedCategories = categoryOrder.filter { grouped.containsKey(it) } +
            grouped.keys.filterNot { categoryOrder.contains(it) }.sorted()

    orderedCategories.forEach { category ->
        result.add(ProductGridItem.Header(category))

        grouped[category]?.sortedBy { it.name.lowercase() }?.forEach {
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
    categoryOrder: List<String> = emptyList(),
    isLoadingProducts: Boolean = false,
    currentCashier: Cashier? = null,
    appLocation: String? = null,
    onLogout: () -> Unit = {},
    onChangeLocation: (String) -> Unit = {},
    onRefreshProducts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // products/categoryOrder now come from a live sheet fetch (with a refresh action),
    // so the grid has to be rebuilt whenever they change instead of only once on first composition.
    val gridItems by remember { derivedStateOf { buildGridItems(products, categoryOrder) } }
    val categoryIndexMap = remember(gridItems) { buildCategoryIndexMap(gridItems) }
    val categoryPositions = remember(categoryIndexMap) {
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

    val cart: CartState = viewModel()

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    var showInventory by remember { mutableStateOf(false) }
    if (showInventory) {
        InventoryScreen(
            appLocation = appLocation,
            products = products,
            categoryOrder = categoryOrder,
            onBack = { showInventory = false },
            modifier = modifier.fillMaxSize()
        )
        return
    }

    var showCartNotEmptyNotice by remember { mutableStateOf(false) }

    val productSection: @Composable (Modifier) -> Unit = { sectionModifier ->
        Column(
            modifier = sectionModifier
        ) {
            val headerRow: @Composable () -> Unit = {
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
                        onLogout = {
                            // clear cart on logout
                            cart.clear()
                            onLogout()
                        },
                        onRefresh = {
                            if (cart.items.isEmpty()) {
                                cart.clear()
                                onRefreshProducts()
                            } else {
                                // block refresh while midsale
                                showCartNotEmptyNotice = true
                            }
                        },
                        onHistory = { },
                        onInventory = { showInventory = true },
                        currentLocation = appLocation,
                        onChangeLocation = { newLocation ->
                            cart.clear()
                            onChangeLocation(newLocation)
                        }
                    )
                }
            }

            val gridBox: @Composable () -> Unit = {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    ProductGrid(
                        gridItems = gridItems,
                        gridState = gridState,
                        modifier = Modifier.fillMaxSize(),
                        onProductClick = { product -> cart.add(product) }
                    )

                    if (isLoadingProducts && products.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFF6C63FF))
                        }
                    } else if (!isLoadingProducts && products.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.emptyProductList),
                                fontSize = 20.sp,
                                color = colorResource(R.color.slateGray),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            if (isPortrait) {
                gridBox()
                HorizontalDivider()
                headerRow()
            } else {
                headerRow()
                HorizontalDivider()
                gridBox()
            }
        }
    }

    // cart panel
    val cartSection: @Composable (Modifier) -> Unit = { sectionModifier ->
        CartPanel(
            cartItems = cart.items,
            lastModifiedEvent = cart.lastModifiedEvent,
            total = cart.total,
            modifier = sectionModifier.padding(8.dp),
            onIncrease = cart::increase,
            onDecrease = cart::decrease,
            onEdit = cart::editQuantity,
            onClear = cart::clear,
            onBuildTransaction = {
                val isAdminCashier = currentCashier?.id == getAdminId(context)
                cart.buildTransaction(
                    currentCashier,
                    locationOverride = if (isAdminCashier) appLocation else null
                )
            },
            onConfirmTransaction = { transaction ->
                cart.clear()
                enqueueTransaction(context, transaction)
            }
        )
    }

    // layouts
    Box(modifier = Modifier.fillMaxSize()) {
        if (isPortrait) {
            // product on top, cart on bottom
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                productSection(Modifier.weight(0.5f).fillMaxWidth())
                HorizontalDivider()
                cartSection(Modifier.weight(0.5f).fillMaxWidth())
            }
        } else {
            // products on left, cart of right
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                productSection(Modifier.weight(0.55f))
                VerticalDivider()
                cartSection(Modifier.weight(0.45f))
            }
        }

        // block all touch while product refresh in progress
        if (isLoadingProducts) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* swallow input while refreshing */ },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
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

    if (showCartNotEmptyNotice) {
        AlertDialog(
            onDismissRequest = { showCartNotEmptyNotice = false },
            title = { Text(stringResource(R.string.cartNotEmpty)) },
            text = {
                Text(
                    text = stringResource(R.string.cartNotEmptyMessage),
                    fontSize = 18.sp
                )
            },
            confirmButton = {
                ConfirmButton(
                    onClick = { showCartNotEmptyNotice = false },
                    modifier = Modifier.fillMaxWidth(0.3f)
                ) { Text(stringResource(R.string.confirm), fontSize = 22.sp) }
            }
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
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(String.format(stringResource(R.string.currency), product.price)
            )
        }
    }
}