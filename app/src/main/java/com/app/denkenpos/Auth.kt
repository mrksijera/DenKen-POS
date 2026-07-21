package com.app.denkenpos

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Cashier(
    val id: Int,
    val name: String,
    val location: String,
    val pin: String
)

val httpClient = HttpClient {
    followRedirects = true

    install(ContentNegotiation) { json() }

    install(HttpTimeout) {
        requestTimeoutMillis = 5000
    }
}

val json = Json{
    ignoreUnknownKeys = true
}


@Composable
fun LoginScreen(
    cashiers: List<Cashier>,
    isLoading: Boolean,
    onLoginSuccess: (Cashier) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val loginMessage = stringResource(R.string.loginSuccess)

    var selectedCashier by remember { mutableStateOf<Cashier?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.LightGray)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.login).uppercase(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )

            Spacer(modifier = Modifier.height(24.dp))
            if (isLoading) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.fetchingStaffData),
                        color = Color.Black,
                        fontSize = 28.sp
                    )

                    CircularProgressIndicator(
                        color = Color(0xFF6C63FF),
                        strokeWidth = 8.dp,
                        modifier = Modifier.size(60.dp)
                    )
                }
            } else {
                if (cashiers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.emptyCashierList),
                        fontSize = 32.sp,
                        color = colorResource(R.color.slateGray),
                        textAlign = TextAlign.Center
                    )
                }

                // cashier grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .align(Alignment.CenterHorizontally)
                ) {
                    items(cashiers) { cashier ->
                        CashierCard(
                            cashier = cashier,
                            onClick = {
                                selectedCashier = cashier
                                pinInput = ""
                                pinError = false
                            }
                        )
                    }
                }
            }
        }
    }

    // pin dialog
    selectedCashier?.let { cashier ->
        var shakeKey by remember{ mutableIntStateOf(0) }

        PinDialog(
            cashier = cashier,
            pin = pinInput,
            hasError = pinError,
            shakeKey = shakeKey,
            onPinChange = { input ->
                pinError = false
                pinInput = input
            },
            onResetError = { pinError = false },
            onDismiss = {
                log("dismissed")
                selectedCashier = null }
        )

        LaunchedEffect(pinInput) {
            val cashier = selectedCashier ?: return@LaunchedEffect

            if (pinInput.length == 6) {
                if (pinInput == cashier.pin) {
                    log("${cashier.name} login success!")
                    Toast.makeText(context, loginMessage, Toast.LENGTH_SHORT).show()
                    onLoginSuccess(cashier)
                } else {
                    log("${cashier.name} login failed!")
                    pinError = true
                    shakeKey++
                    pinInput = ""
                }
            }
        }
    }
}

@Composable
fun CashierCard(
    cashier: Cashier,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cardScale"
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(160.dp)
            .scale(scale)
            .wrapContentHeight(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(R.color.cartRowGray)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        interactionSource = remember { MutableInteractionSource() }.also { source ->
            LaunchedEffect(source) {
                source.interactions.collect { interaction ->
                    pressed = interaction is PressInteraction.Press
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CardAvatar(cashier)
        }
    }
}

@Composable
fun CardAvatar(cashier: Cashier) {
    Spacer(Modifier.height(8.dp))

    val (colorStart, colorEnd) = remember(cashier.name) {
        nameToGradientColors(cashier.name)
    }

    // avatar
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(120.dp)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(colorStart, colorEnd)
                ),
                shape = CircleShape
            )
    ) {
        Text(
            text = cashier.name.first().uppercaseChar().toString(),
            color = Color.White,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(Modifier.height(16.dp))

    Text(
        text = cashier.name,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun PinDialog(
    cashier: Cashier,
    pin: String,
    hasError: Boolean,
    shakeKey: Int,
    onPinChange: (String) -> Unit,
    onResetError: () -> Unit,
    onDismiss: () -> Unit
) {
    val shakeOffset = remember{ Animatable(0f) }

    LaunchedEffect(shakeKey) {
        if (shakeKey == 0) return@LaunchedEffect
        shakeOffset.snapTo(0f)
        val shakeSteps = listOf(-12f, 12f, -8f, 8f, -4f, 4f, 0f)
        for (target in shakeSteps) {
            shakeOffset.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 60, easing = LinearEasing)
            )
        }
    }

    Dialog(onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colorResource(R.color.offWhite)),
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .wrapContentHeight()
                .offset(x = shakeOffset.value.dp)
        ) {
            Column(
                modifier = Modifier.padding(
                    top = 32.dp,
                    start = 32.dp,
                    end = 32.dp,
                    bottom = 16.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CardAvatar(cashier)

                Spacer(Modifier.height(8.dp))

                Text(
                    text = if (hasError) "Incorrect PIN. Try again." else "Enter your 6-digit PIN",
                    color = if (hasError) colorResource(R.color.Cancel) else Color.Gray,
                    fontSize = 16.sp
                )

                Spacer(Modifier.height(20.dp))

                // pin dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    repeat(6) { index ->
                        val filled = index < pin.length
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = when {
                                        hasError -> colorResource(R.color.Cancel)
                                        filled -> colorResource(R.color.strongBlue)
                                        else -> Color.LightGray
                                    },
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                // numpad
                val keys = listOf("1","2","3","4","5","6","7","8","9","","0","⌫")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(keys) { key ->
                        if (key.isEmpty()) {
                            Box(modifier = Modifier.aspectRatio(1.8f))
                        } else {
                            val isBackspace = key == "⌫"
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .aspectRatio(1.8f)
                                    .background(
                                        color = if (isBackspace) colorResource(R.color.slateGray)
                                        else colorResource(R.color.lightBlueGray),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        if (isBackspace) {
                                            if (pin.isNotEmpty())
                                                onPinChange(pin.dropLast(1))
                                        } else if (pin.length < 6) {
                                            onResetError()
                                            onPinChange(pin + key)
                                        }
                                    }
                                    .border(
                                        border = BorderStroke(1.dp, Color.LightGray),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                            ) {
                                Text(
                                    text = key,
                                    color = if (isBackspace) Color.White else colorResource(R.color.darkNavy),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        color = Color.Gray,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

private object StaffColumns {
    val ID = listOf("ID")
    val NAME = listOf("Staff Name", "Name")
    val LOCATION = listOf("Location")
    val PIN = listOf("PIN")
}

// Find index of the first header matching any of [names], else return -1.
fun List<String>.indexOfHeader(names: List<String>): Int {
    return indexOfFirst { header -> names.any { it.equals(header.trim(), ignoreCase = true) } }
}

suspend fun fetchCashiers(context: Context): List<Cashier> {
    val table = fetchSheetTable(context, "staff")
    if (table.isEmpty()) return emptyList()

    val headers = table.first()
    val rows = table.drop(1) // drop 1st row (headers)

    val idIndex = headers.indexOfHeader(StaffColumns.ID)
    val nameIndex = headers.indexOfHeader(StaffColumns.NAME)
    val locationIndex = headers.indexOfHeader(StaffColumns.LOCATION)
    val pinIndex = headers.indexOfHeader(StaffColumns.PIN)

    if (idIndex == -1 || nameIndex == -1 || locationIndex == -1 || pinIndex == -1) {
        log("fetchCashiers: missing required column(s) in Staff sheet header: $headers " +
                "(id=$idIndex, name=$nameIndex, location=$locationIndex, pin=$pinIndex)")
        return emptyList()
    }

    val requiredCount = maxOf(idIndex, nameIndex, locationIndex, pinIndex) + 1

    return rows.mapNotNull { row ->
        if (row.size >= requiredCount) {
            val id = row[idIndex].trim().toDoubleOrNull()?.toInt() ?: return@mapNotNull null

            Cashier(
                id = id,
                name = row[nameIndex].trim(),
                location = row[locationIndex].trim(),
                pin = row[pinIndex].trim()
            )
        } else null
    }
}

// caching

fun saveCashiers(context: Context, cashiers: List<Cashier>) {
    val jsonString = json.encodeToString(cashiers)

    context.getSharedPreferences("pos", Context.MODE_PRIVATE)
        .edit {
            putString("cashiers", jsonString)
        }
}

fun loadCashiers(context: Context): List<Cashier> {
    val jsonString = context
        .getSharedPreferences("pos", Context.MODE_PRIVATE)
        .getString("cashiers", null) ?: return emptyList()

    return try {
        json.decodeFromString<List<Cashier>>(jsonString)
    } catch (e: Exception) {
        emptyList()
    }
}

// helper functions

fun nameToGradientColors(name: String): Pair<Color, Color> {
    var hash = 0
    for (c in name) {
        hash = (hash * 31 + c.code)
    }

    val mixed = mixBits(hash)

    val baseHue = ((mixed % 360f) + 360f) % 360f
    val secondHue = (baseHue + 40f) % 360f

    val color1 = Color.hsv(hue = baseHue, saturation = 0.55f, value = 0.85f)
    val color2 = Color.hsv(hue = secondHue, saturation = 0.65f, value = 0.75f)

    return color1 to color2
}

fun mixBits(input: Int): Int {
    var h = input
    h = h xor (h ushr 16)
    h *= 0x7ee3623b // 0x85ebca6b
    h = h xor (h ushr 13)
    h *= -0x3b314601 // 0xc2b2ae35
    h = h xor (h ushr 16)
    return h
}

fun log(string: String) {
    Log.d("TAG", string)
}