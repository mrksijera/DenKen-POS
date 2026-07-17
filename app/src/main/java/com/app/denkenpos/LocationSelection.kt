package com.app.denkenpos

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.ktor.client.call.body
import io.ktor.client.request.get

private object LocationSheetColumns {
    val NAME = listOf("Name")
}

suspend fun fetchLocations(url: String): List<String> {
    val csv: String = httpClient.get(url).body()
    val lines = csv.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()

    val headers = lines.first().split(",")
    val nameIndex = headers.indexOfHeader(LocationSheetColumns.NAME)

    if (nameIndex == -1) {
        log("fetchLocations: missing Name column in Locations sheet header: $headers")
        return emptyList()
    }

    return lines.drop(1)
        .mapNotNull { line -> line.split(",").getOrNull(nameIndex)?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

//Shown on first launch or appLocation == null, blocks the rest of the app until a location is picked.
@Composable
fun LocationSelectionScreen(
    onLocationSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var locations by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        isLoading = true
        hasError = false
        locations = try {
            fetchLocations(getLocationSheetUrl(context)).also { hasError = it.isEmpty() }
        } catch (e: Exception) {
            hasError = true
            emptyList()
        }
        isLoading = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.LightGray),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Text(
                text = stringResource(R.string.selectLocation),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            when {
                isLoading -> CircularProgressIndicator(
                    color = Color(0xFF6C63FF),
                    strokeWidth = 8.dp,
                    modifier = Modifier.size(60.dp)
                )

                hasError -> {
                    Text(
                        text = stringResource(R.string.loadLocationFailedRetry),
                        color = colorResource(R.color.Cancel),
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    ConfirmButton(onClick = { reloadKey++ }) {
                        Text("Retry", fontSize = 20.sp)
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                        items(locations) { location ->
                            LocationRow(location = location, onClick = { onLocationSelected(location) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Admin-only dialog (triggered from the POS menu) for reassigning this tablet
 * to a different location after initial setup.
 */
@Composable
fun LocationPickerDialog(
    currentLocation: String?,
    onDismiss: () -> Unit,
    onLocationSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var locations by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        isLoading = true
        hasError = false
        locations = try {
            fetchLocations(getLocationSheetUrl(context)).also { hasError = it.isEmpty() }
        } catch (e: Exception) {
            hasError = true
            emptyList()
        }
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .heightIn(max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.changeLocation),
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = "Close",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                when {
                    isLoading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }

                    hasError -> {
                        Text(
                            text = stringResource(R.string.loadLocationFailed),
                            color = colorResource(R.color.Cancel),
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { reloadKey++ }) { Text(stringResource(R.string.retry)) }
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(locations) { location ->
                                LocationRow(
                                    location = location,
                                    selected = location == currentLocation,
                                    onClick = { onLocationSelected(location) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationRow(
    location: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = location,
                fontSize = 22.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )

            if (selected) {
                Spacer(Modifier.width(8.dp))

                Text(
                    text = "CURRENT",
                    fontSize = 16.sp,
                    color = colorResource(R.color.Confirm),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
