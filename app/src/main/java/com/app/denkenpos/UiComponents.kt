package com.app.denkenpos

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun PosMenuButton(
    currentCashier: Cashier?,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onHistory: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember{ mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.menu),
                tint = Color.Black,
                contentDescription = null
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transactionHistory)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.history),
                        contentDescription = null
                    )
                },
                onClick = {
                    expanded = false
                    showHistory = true
                }
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.refreshProducts)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.sync),
                        contentDescription = null
                    )
                },
                onClick = {
                    expanded = false
                    onRefresh()
                }
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.logout)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.logout),
                        contentDescription = null
                    )
                },
                onClick = {
                    expanded = false
                    showLogoutConfirm = true
                }
            )
        }
    }

    if (showHistory) {
        TransactionHistoryDialog(
            context = context,
            onDismiss = { showHistory = false }
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Log Out") },
            text = {
                Text(
                    text = stringResource(R.string.confirmLogout),
                    fontSize = 18.sp
                )
            },
            confirmButton = {
                ConfirmButton(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    },
                    modifier = Modifier.fillMaxWidth(0.3f)
                ) { Text("Yes", fontSize = 22.sp) }
            },
            dismissButton = {
                CancelButton(
                    onClick = { showLogoutConfirm = false },
                    modifier = Modifier.fillMaxWidth(0.3f)
                ) { Text("No", fontSize = 22.sp) }
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
fun ConfirmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonColors(
            containerColor = colorResource(R.color.Confirm),
            contentColor = Color.White,
            disabledContainerColor = Color.LightGray,
            disabledContentColor = Color.DarkGray
        ),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        content()
    }
}

@Composable
fun CancelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonColors(
            containerColor = colorResource(R.color.Cancel),
            contentColor = Color.White,
            disabledContainerColor = Color.LightGray,
            disabledContentColor = Color.DarkGray
        ),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        content()
    }
}