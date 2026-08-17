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

    val products = mutableStateListOf<Product>()
    val categoryOrder = mutableStateListOf<String>()
    var isLoadingProducts by mutableStateOf(false)

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

        // load product catalog
        loadProductCatalog()
    }

    fun loadProductCatalog() {
        isLoadingProducts = true

        viewModelScope.launch {
            val context = getApplication<Application>()


            var fetchedProducts: List<Product>? = null
            val maxAttempts = getProductsSheetMaxTries(context)
            var attempt = 0

            while (fetchedProducts == null && attempt < maxAttempts) {
                attempt++
                log("fetching products attempt $attempt of $maxAttempts")

                fetchedProducts = try {
                    fetchProducts(context, appLocation)
                } catch (_: Exception) {
                    log("fetch products attempt $attempt failed")
                    if (attempt < maxAttempts) delay(2000L.milliseconds)
                    null
                }
            }

            val fetchedCategories = try {
                fetchCategories(context)
            } catch (_: Exception) {
                log("fetch categories failed")
                null
            }

            if (!fetchedProducts.isNullOrEmpty()) {
                products.clear()
                products.addAll(fetchedProducts)
                saveProducts(context, fetchedProducts, appLocation)
                log("fetched ${fetchedProducts.size} products from masterlist")
            } else {
                val cached = loadProducts(context, appLocation)
                products.clear()
                products.addAll(cached)
                log("product fetch failed, fallback to cached data with ${products.count()} products")
            }

            if (!fetchedCategories.isNullOrEmpty()) {
                categoryOrder.clear()
                categoryOrder.addAll(fetchedCategories)
                saveCategoryOrder(context, fetchedCategories)
                log("fetched ${fetchedCategories.size} categories from masterlist")
            } else {
                val cachedCategories = loadCategoryOrder(context)
                categoryOrder.clear()
                categoryOrder.addAll(cachedCategories)
            }

            isLoadingProducts = false
        }
    }

    // Called after the first-run picker or the admin "Change Location" dialog.
    fun assignLocation(location: String) {
        val context = getApplication<Application>()
        setAppLocation(context, location)
        appLocation = location
        loadCashiersForLocation()
        loadProductCatalog() // refetch product catalog
    }

    private fun loadCashiersForLocation() {
        isLoading = true

        viewModelScope.launch {
            val context = getApplication<Application>()
            val location = appLocation ?: run {
                isLoading = false
                return@launch
            }

            var success = false
            var attempt = 0
            val maxAttempts = getStaffSheetMaxTries(context)

            var fetched: List<Cashier> = emptyList()

            while (!success && attempt < maxAttempts) {
                attempt++
                log("fetching cashiers attempt $attempt of $maxAttempts")

                try {
                    log("fetching cashiers")
                    fetched = fetchCashiers(context)
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
                log("loaded ${cashiers.count()}  from database")
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
                products = viewModel.products,
                categoryOrder = viewModel.categoryOrder,
                isLoadingProducts = viewModel.isLoadingProducts,
                currentCashier = currentCashier,
                appLocation = viewModel.appLocation,
                onLogout = {
                    Toast.makeText(context, loggedOutMessage, Toast.LENGTH_SHORT).show()
                    currentCashierId = null
                },
                onChangeLocation = { newLocation ->
                    Toast.makeText(context, "$locationChangedMessage $newLocation.", Toast.LENGTH_SHORT).show()
                    viewModel.assignLocation(newLocation)
                },
                onRefreshProducts = { viewModel.loadProductCatalog() }
            )
        }
    }
}