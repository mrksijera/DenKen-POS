package com.app.denkenpos

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

data class LastModifiedEvent(val productId: Int, val timeStamp: Long = System.currentTimeMillis())

class CartState {
    val cart = mutableStateMapOf<Int, CartItem>()
    val cartOrder = mutableStateListOf<Int>()
    val items by  derivedStateOf { cartOrder.mapNotNull { cart[it] } }
    val total by derivedStateOf { items.sumOf { it.product.price * it.quantity } }

    var editingProductId by mutableStateOf<Int?>(null)
    var editingQuantity by mutableStateOf(TextFieldValue(""))
    var lastModifiedEvent by mutableStateOf<LastModifiedEvent?>(null)

    // functions
    fun add(product: Product) {
        val existing = cart[product.id]

        if (existing != null) {
            cart[product.id] = existing.copy(
                quantity = existing.quantity + 1
            )
        } else {
            cart[product.id] = CartItem(product, 1)
            cartOrder.add(product.id)
        }

        lastModifiedEvent = LastModifiedEvent(product.id)
    }

    fun increase(productId: Int) {
        val existing = cart[productId] ?: return

        cart[productId] = existing.copy(
            quantity = existing.quantity + 1
        )

        lastModifiedEvent = LastModifiedEvent(productId)
    }

    fun decrease(productId: Int) {
        val existing = cart[productId] ?: return

        if (existing.quantity > 1) {
            cart[productId] = existing.copy(
                quantity = existing.quantity - 1
            )
        } else {
            cart.remove(productId)
            cartOrder.remove(productId)
        }
    }

    fun editQuantity(productId: Int, currentQty: Int) {
        editingProductId = productId

        val text = currentQty.toString()

        // select current value when opening dialog
        editingQuantity = TextFieldValue(
            text = text,
            selection = TextRange(0, text.length)
        )

        lastModifiedEvent = LastModifiedEvent(productId)
    }

    fun setQuantity(productId: Int, quantity: Int) {
        if (quantity <= 0) {
            cart.remove(productId)
            cartOrder.remove(productId)
            return
        }

        val existing = cart[productId] ?: return
        cart[productId] = existing.copy(quantity = quantity)

        lastModifiedEvent = LastModifiedEvent(productId)
    }

    fun clear() {
        cart.clear()
        cartOrder.clear()
    }

    fun buildTransaction(cashier: Cashier?, locationOverride: String? = null): Transaction? {
        if (items.isEmpty()) return null

        return Transaction(
            cashierId = cashier?.id ?: -1,
            cashierName = cashier?.name ?: "Unknown",
            location = locationOverride?: cashier?.location ?: "Unknown",
            items = items.map { cartItem ->
                TransactionItem(
                    productId = cartItem.product.id,
                    name = cartItem.product.name,
                    price = cartItem.product.price,
                    quantity = cartItem.quantity
                )
            },
            total = total
        )
    }
}

data class CartItem(
    val product: Product,
    var quantity: Int
)

@Composable
fun CartPanel(
    cartItems: List<CartItem>,
    lastModifiedEvent: LastModifiedEvent?,
    total: Double,
    modifier: Modifier = Modifier,
    onIncrease: (Int) -> Unit,
    onDecrease: (Int) -> Unit,
    onEdit: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onBuildTransaction: () -> Transaction?,
    onConfirmTransaction: (Transaction) -> Unit
) {
    val listState = rememberLazyListState()
    var showClearConfirm by remember { mutableStateOf(false) }
    var showEmptyCartNotice by remember { mutableStateOf(false) }
    var pendingTransaction by remember { mutableStateOf<Transaction?>(null) }

    LaunchedEffect(lastModifiedEvent?.productId) {
        val productId = lastModifiedEvent?.productId ?: return@LaunchedEffect

        val index = cartItems.indexOfFirst { it.product.id == productId }

        if (index != -1 && index != cartItems.lastIndex) {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val isVisible = visibleItems.any { it.index == index }

            if (!isVisible) {
                listState.animateScrollToItem(index)
            }
        } else {
            listState.animateScrollToItem(cartItems.lastIndex)
        }
    }

    Column(
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(Color.LightGray)
                .fillMaxWidth()
                .padding(
                    top = 3.dp,
                    bottom = 3.dp,
                    start = 6.dp,
                    end = 6.dp
                )
        ) {
            Text(
                text = "Cart",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        }

        HorizontalDivider(color = Color.Black)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(
                items = cartItems,
                key = { _, item -> item.product.id }
            ) { index, item ->
                CartItemRow(
                    item = item,
                    index = index,
                    isLastModified = lastModifiedEvent,
                    onIncrease = onIncrease,
                    onDecrease = onDecrease,
                    onEdit = onEdit
                )
            }
        }

        CartPanelBottom(
            total = total,
            hasItems = cartItems.isNotEmpty(),
            onClear = { showClearConfirm = true },
            onCheckout = {
                if (cartItems.isEmpty()) {
                    showEmptyCartNotice = true
                } else {
                    pendingTransaction = onBuildTransaction()
                }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = {
                Text(stringResource(R.string.clearCartTitle)) },
            text = {
                Text(
                    text = stringResource(R.string.clearCartPrompt),
                    fontSize = 20.sp
                ) },
            confirmButton = {
                ConfirmButton(
                    onClick = {
                        onClear()
                        showClearConfirm = false
                    },
                    modifier = Modifier.fillMaxWidth(0.3f)
                ) { Text(
                    text = stringResource(R.string.yes),
                    fontSize = 26.sp
                ) }
            },
            dismissButton = {
                CancelButton(
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.fillMaxWidth(0.3f)
                ) { Text(
                    text = stringResource(R.string.no),
                    fontSize = 26.sp
                ) }
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .padding(vertical = 10.dp)
                .wrapContentWidth()
                .wrapContentHeight()
        )
    }

    if (showEmptyCartNotice) {
        AlertDialog(
            onDismissRequest = { showEmptyCartNotice = false },
            title = { Text(stringResource(R.string.cartIsEmpty)) },
            text = { Text(stringResource(R.string.addItemsPrompt), fontSize = 20.sp) },
            confirmButton = {
                ConfirmButton(
                    onClick = { showEmptyCartNotice = false },
                    modifier = Modifier.fillMaxWidth(0.3f)
                ) { Text(stringResource(R.string.confirm), fontSize = 22.sp) }
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .padding(vertical = 10.dp)
                .wrapContentWidth()
                .wrapContentHeight()
        )
    }

    pendingTransaction?.let { transaction ->
        AlertDialog(
            onDismissRequest = { pendingTransaction = null }, // acts as "Back"
            title = { Text(
                        stringResource(R.string.confirmTransaction),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ) },
            text = {
                Column {
                    Text("Cashier: ${transaction.cashierName}", fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp) // 3
                    ) {
                        items(transaction.items, key = { it.productId }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${item.quantity}x ${item.name}",
                                    fontSize = 18.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = String.format(Locale.getDefault(), "%.2f", item.lineTotal),
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Total",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text( // 4
                            text = "${stringResource(R.string.currency)} " +
                                    String.format(Locale.getDefault(), "%.2f", transaction.total),
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            color = colorResource(R.color.Confirm)
                        )
                    }
                }
            },
            confirmButton = {
                ConfirmButton(
                    onClick = {
                        onConfirmTransaction(transaction)
                        pendingTransaction = null
                    },
                    modifier = Modifier.fillMaxWidth(0.3f)
                ) { Text(stringResource(R.string.confirm), fontSize = 26.sp) }
            },
            dismissButton = {
                CancelButton(
                    onClick = { pendingTransaction = null },
                    modifier = Modifier.fillMaxWidth(0.3f)
                ) { Text(stringResource(R.string.cancel), fontSize = 26.sp) }
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .padding(vertical = 10.dp)
                .wrapContentWidth()
                .wrapContentHeight()
        )
    }
}

@Composable
fun CartItemRow(
    item: CartItem,
    index: Int,
    isLastModified: LastModifiedEvent?,
    onIncrease: (Int) -> Unit,
    onDecrease: (Int) -> Unit,
    onEdit: (Int, Int) -> Unit
) {
    val baseColor =
        if (index % 2 == 0) Color.White
        else colorResource(R.color.cartRowGray)

    val highlightColor = colorResource(R.color.Confirm).copy(alpha = 0.3f)
    var triggered by remember{ mutableStateOf(false) }

    LaunchedEffect(isLastModified) {
        if(isLastModified?.productId == item.product.id) {
            triggered = true
            delay(300.milliseconds)
            triggered = false
        } else if (triggered) {
            triggered = false
        }
    }

    val rowColor by animateColorAsState(
        targetValue = if (triggered) highlightColor else baseColor,
        animationSpec = tween(durationMillis = 300),
        label = "rowHighlight"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(rowColor)
    ) {
        Text(
            text = item.product.name,
            fontSize = 22.sp,
            modifier = Modifier.weight(0.45f)
        )

        IconButton(
            onClick = { onDecrease(item.product.id) },
            modifier = Modifier.size(42.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.remove_24px),
                contentDescription = "Remove 1 ${item.product.name}"
            )
        }

        Text(
            text = item.quantity.toString(),
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable {
                    onEdit(item.product.id, item.quantity)
                }
                .width(60.dp)
        )

        IconButton(
            onClick = { onIncrease(item.product.id) },
            modifier = Modifier.size(42.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.add_24px),
                contentDescription = "Add 1 ${item.product.name}"
            )
        }

        Text(
            text = String.format(
                Locale.getDefault(),
                "%s %.2f",
                stringResource(R.string.currency), item.product.price * item.quantity
            ),
            textAlign = TextAlign.End,
            fontSize = 22.sp,
            modifier = Modifier.weight(0.3f)
        )
    }
}

@Composable
fun CartPanelBottom(
    total: Double,
    hasItems: Boolean,
    onClear: () -> Unit,
    onCheckout: () -> Unit
) {
    // total row
    Row(
        modifier = Modifier
            .background(Color.LightGray)
            .fillMaxWidth()
            .padding(
                top = 3.dp,
                bottom = 3.dp,
                start = 6.dp,
                end = 6.dp
            )
    ) {
        Text(
            text = "Total:",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.6f)
        )

        Text(
            text = stringResource(R.string.currency),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.1f)
        )

        Text(
            text = String.format(Locale.getDefault(), "%.2f", total),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.3f)
        )
    }

    // action buttons row
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .background(Color.LightGray)
            .fillMaxWidth()
    ) {
        CancelButton(
            onClick = onClear,
            enabled = hasItems,
            modifier = Modifier
                .padding(5.dp)
                .weight(0.3f)
                .height(80.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.delete_40px),
                contentDescription = stringResource(R.string.clearCart),
                modifier = Modifier.fillMaxSize()
            )
        }

        ConfirmButton(
            onClick = onCheckout,
            enabled = hasItems,
            modifier = Modifier
                .padding(5.dp)
                .weight(0.7f)
                .height(80.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.checkout_40px),
                contentDescription = stringResource(R.string.checkoutCart),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun EditQuantityDialog(
    editingQuantity: TextFieldValue,
    onQuantityChange: (TextFieldValue) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setQuantity)) },
        text = {
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current

            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            TextField(
                value = editingQuantity,
                onValueChange = onQuantityChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 40.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        editingQuantity.text.toIntOrNull()?.let { onConfirm(it) }
                    }
                ),
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            ConfirmButton(
                onClick = { editingQuantity.text.toIntOrNull()?.let { onConfirm(it) } },
                modifier = Modifier.width(130.dp)
            ) {
                Text(
                    text = stringResource(R.string.confirm),
                    fontSize = 26.sp
                )
            }
        },
        dismissButton = {
            CancelButton(
                onClick = onDismiss,
                modifier = Modifier.width(130.dp)
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    fontSize = 26.sp
                )
            }
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(vertical = 10.dp)
            .fillMaxWidth(0.6f)
            .wrapContentHeight()
    )
}