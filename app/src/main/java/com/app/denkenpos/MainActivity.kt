package com.app.denkenpos

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.denkenpos.ui.theme.DenKenPOSTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // hide system bars
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            DenKenPOSTheme {
                Scaffold(modifier = Modifier.fillMaxWidth()) { innerPadding ->
                     AppRoot(
                         modifier = Modifier.padding(innerPadding)
                     )
                }
            }
        }
    }
}

class RootViewModel(application: Application): AndroidViewModel(application) {
    val cashiers = mutableStateListOf<Cashier>()
    var isLoading by mutableStateOf(false)

    var appLocation by mutableStateOf<String?>(null)
        private set

    init {
        val context = getApplication<Application>()
        scheduleLogSync(context) // pick up anything left over from before a restart/kill

        appLocation = getAppLocation(context)

        // Only pull cashiers once we know which store this tablet belongs to.
        // Until then, AppRoot shows the location picker instead of the login screen.
        if (appLocation != null) {
            loadCashiersForLocation()
        }
    }

    // Called after the first-run picker or the admin "Change Location" dialog.
    fun assignLocation(location: String) {
        val context = getApplication<Application>()
        setAppLocation(context, location)
        appLocation = location
        loadCashiersForLocation()
    }

    private fun loadCashiersForLocation() {
        isLoading = true

        viewModelScope.launch {
            val context = getApplication<Application>()
            val location = appLocation ?: run {
                isLoading = false
                return@launch
            }

            val url = getStaffSheetUrl(context)
            log("fetching staff data from: $url for location $location")

            var success = false
            var attempt = 0
            val maxAttempts = getStaffSheetMaxTries(context)

            var fetched: List<Cashier> = emptyList()

            while (!success && attempt < maxAttempts) {
                attempt++
                log("fetching cashiers attempt $attempt of $maxAttempts")

                try {
                    log("fetching cashiers")
                    fetched = fetchCashiers(url)
                    success = true
                } catch (_: Exception) {
                    log("attempt $attempt failed")

                    if (attempt < maxAttempts) {
                        delay(2000L.milliseconds)
                    }
                }
            }

            cashiers.clear()

            if (success) {
                // Only cashiers+Admin assigned to this tablet's location.
                val filtered = fetched.filter {
                    it.location.equals(location, ignoreCase = true) || it.id == getAdminId(context)
                }

                cashiers.addAll(filtered)
                saveCashiers(context, filtered)
            } else {
                val cached = loadCashiers(context)
                cashiers.addAll(cached)
                log("all attempts failed, fallback to cached data with ${cashiers.count()} accounts")
            }

            isLoading = false
        }
    }
}

@Composable
fun AppRoot(modifier: Modifier = Modifier, viewModel: RootViewModel = viewModel()) {
    val context = LocalContext.current

    var currentCashierId by rememberSaveable { mutableStateOf<Int?>(null) }
    val currentCashier = viewModel.cashiers.find { it.id == currentCashierId }

    // timeout check
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun onUserInteraction() {
        lastInteractionTime = System.currentTimeMillis()
    }

    LaunchedEffect(lastInteractionTime) {
        delay((getTimeoutMinutes(context) * 60 * 1000).milliseconds)
        log("timed out")
        currentCashierId = null
    }

    if (viewModel.appLocation == null) {
        // first launch or location not assigned
        LocationSelectionScreen(
            onLocationSelected = { location -> viewModel.assignLocation(location) },
            modifier = modifier
        )

        return
    }

    if (currentCashier == null) {
        // login
        log("login screen for location ${viewModel.appLocation}")
        LoginScreen(
            cashiers = viewModel.cashiers,
            isLoading = viewModel.isLoading,
            onLoginSuccess = { currentCashierId = it.id },
            modifier = modifier
        )
    } else {
        // login successful
        Box(
            modifier = modifier
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while(true) {
                            awaitPointerEvent()
                            onUserInteraction()
                        }
                    }
                }
        ) {
            val loggedOutMessage = stringResource(R.string.loggedOut)
            val locationChangedMessage = stringResource(R.string.locationChanged)

            PosScreen(
                products = sampleProducts(),
                currentCashier = currentCashier,
                appLocation = viewModel.appLocation,
                onLogout = {
                    Toast.makeText(context, loggedOutMessage, Toast.LENGTH_SHORT).show()
                    currentCashierId = null
                },
                onChangeLocation = { newLocation ->
                    Toast.makeText(context, "$locationChangedMessage $newLocation.", Toast.LENGTH_SHORT).show()
                    viewModel.assignLocation(newLocation)
                }
            )
        }
    }
}

fun sampleProducts(): List<Product> {
    return listOf(
        Product("Coke", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Pepsi", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Sprite", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Water", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Mountain Dew", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Dr. Pepper", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Royal", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Magnolia", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Tang", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Nestea Lemon", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Chuckie", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Drinks1", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Drinks2", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Drinks3", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Drinks4", Random.Default.nextDouble(30.0, 60.0), "Drinks"),
        Product("Drinks5", Random.Default.nextDouble(30.0, 60.0), "Drinks"),

        Product("Chippy", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Tattoos", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Piattos", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Ding Dong", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Snickers", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Boy Bawang", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Esep Esep", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Patatas", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Snack1", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Snack2", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Snack3", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Snack4", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Snack5", Random.Default.nextDouble(15.0, 35.0), "Snacks"),
        Product("Snack6", Random.Default.nextDouble(15.0, 35.0), "Snacks"),

        Product("Corned Tuna", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Beef Loaf", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Corned Beef", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Sardines", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Meat Sauce", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Coconut Milk", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Evaporated Milk", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Condensed Milk", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Sausage", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Mushroom", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Canned1", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Canned2", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Canned3", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Canned4", Random.Default.nextDouble(25.0, 70.0), "Canned"),
        Product("Canned5", Random.Default.nextDouble(25.0, 70.0), "Canned"),

        Product("Pancit Canton", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("Cup Noodles", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("Mi Goreng", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("Cheese Ramen", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("Ramyeon", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("GenericNoodle1", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("GenericNoodle2", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("GenericNoodle3", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("GenericNoodle4", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("GenericNoodle5", Random.Default.nextDouble(25.0, 100.0), "Noodles"),
        Product("Jjampong", Random.Default.nextDouble(25.0, 100.0), "Noodles")
    )
}