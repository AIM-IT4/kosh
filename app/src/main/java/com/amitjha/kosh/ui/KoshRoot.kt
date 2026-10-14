package com.amitjha.kosh.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.amitjha.kosh.data.prefs.Settings
import com.amitjha.kosh.data.prefs.ThemeMode
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.designsystem.components.KoshLogo
import com.amitjha.kosh.designsystem.components.LogoShape
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.theme.BrandPalette
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.notify.Notifications
import com.amitjha.kosh.security.BiometricGate
import com.amitjha.kosh.security.UnlockResult
import com.amitjha.kosh.ui.accounts.AccountDetailScreen
import com.amitjha.kosh.ui.accounts.AccountEditScreen
import com.amitjha.kosh.ui.accounts.AccountsScreen
import com.amitjha.kosh.ui.bills.BillsScreen
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.LocalSettings
import com.amitjha.kosh.ui.home.HomeScreen
import com.amitjha.kosh.ui.insights.InsightsScreen
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.nav.TabRoutes
import com.amitjha.kosh.ui.nav.Tabs
import com.amitjha.kosh.ui.onboarding.OnboardingScreen
import com.amitjha.kosh.ui.planning.PlanningScreen
import com.amitjha.kosh.ui.settings.CategoriesScreen
import com.amitjha.kosh.ui.settings.MoreScreen
import com.amitjha.kosh.ui.transactions.AddTransactionScreen
import com.amitjha.kosh.ui.transactions.ReviewScreen
import com.amitjha.kosh.ui.transactions.TransactionDetailScreen
import com.amitjha.kosh.ui.transactions.TransactionsScreen
import com.amitjha.kosh.ui.wealth.InvestmentDetailScreen
import com.amitjha.kosh.ui.wealth.InvestmentEditScreen
import com.amitjha.kosh.ui.wealth.WealthScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RELOCK_AFTER_MILLIS = 60_000L

/** Root of the UI: theme, splash, app lock, onboarding and navigation. */
@Composable
fun KoshRoot(container: AppContainer, initialRoute: String?, onRouteConsumed: () -> Unit) {
    val settingsState by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = settingsState
    val dark = when (settings?.themeMode ?: ThemeMode.SYSTEM) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    KoshTheme(darkTheme = dark) {
        CompositionLocalProvider(
            LocalContainer provides container,
            LocalSettings provides (settings ?: Settings()),
            LocalHideAmounts provides (settings?.hideAmounts ?: false),
        ) {
            var splashDone by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(700)
                splashDone = true
            }
            val lockEnabled = settings?.biometricLockEnabled == true
            var unlocked by remember { mutableStateOf(false) }
            var backgroundedAt by remember { mutableLongStateOf(0L) }

            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner, lockEnabled) {
                val obs = LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
                        Lifecycle.Event.ON_START -> if (lockEnabled && backgroundedAt > 0 &&
                            System.currentTimeMillis() - backgroundedAt > RELOCK_AFTER_MILLIS
                        ) {
                            unlocked = false
                        }
                        else -> Unit
                    }
                }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }

            val stage = when {
                settings == null || !splashDone -> Stage.Splash
                lockEnabled && !unlocked -> Stage.Locked
                !settings.onboardingDone -> Stage.Onboarding
                else -> Stage.Main
            }

            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                AnimatedContent(
                    targetState = stage,
                    transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) },
                    label = "stage",
                ) { s ->
                    when (s) {
                        Stage.Splash -> Splash()
                        Stage.Locked -> LockScreen(onUnlocked = { unlocked = true })
                        Stage.Onboarding -> OnboardingScreen()
                        Stage.Main -> MainShell(initialRoute, onRouteConsumed)
                    }
                }
            }
        }
    }
}

private enum class Stage { Splash, Locked, Onboarding, Main }

@Composable
private fun Splash() {
    var start by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { start = true }
    val scale by animateFloatAsState(
        if (start) 1f else 0.6f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "logo",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.splash()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KoshLogo(Modifier.scale(scale), size = 112.dp, shape = LogoShape.RoundedSquare)
            Spacer(Modifier.height(20.dp))
            AnimatedVisibility(start, enter = fadeIn(tween(500, 200)) + slideInVertically { it / 2 }) {
                Text("Kosh", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private object Brush {
    fun splash() = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(BrandPalette.Indigo900, BrandPalette.Ink))
}

@Composable
private fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val unlock: () -> Unit = {
        val activity = context as? FragmentActivity
        if (activity == null || !BiometricGate.isAvailable(context)) {
            onUnlocked()
        } else {
            scope.launch {
                when (val r = BiometricGate.authenticate(activity)) {
                    UnlockResult.Success -> onUnlocked()
                    is UnlockResult.Error -> message = r.message
                }
            }
        }
    }
    LaunchedEffect(Unit) { unlock() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.splash()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            KoshLogo(size = 88.dp)
            Text("Kosh is locked", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            message?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(text = "Unlock", onClick = unlock, icon = Icons.Filled.Fingerprint)
        }
    }
}

@Composable
private fun MainShell(initialRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = current in TabRoutes

    LaunchedEffect(initialRoute) {
        val target = when (initialRoute) {
            Notifications.ROUTE_REVIEW -> Routes.REVIEW
            Notifications.ROUTE_BILLS -> Routes.BILLS
            Notifications.ROUTE_INSIGHTS -> Routes.INSIGHTS
            Notifications.ROUTE_INVESTMENTS -> Routes.WEALTH
            else -> null
        }
        if (target != null) {
            nav.navigate(target)
            onRouteConsumed()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(showBar, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                BottomBar(nav, current)
            }
        },
        floatingActionButton = {
            AnimatedVisibility(showBar, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
                AddFab { nav.navigate(Routes.addTxn()) }
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
    ) { padding ->
        Box(Modifier.padding(bottom = if (showBar) padding.calculateBottomPadding() else 0.dp)) {
            KoshNavHost(nav)
        }
    }
}

@Composable
private fun AddFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = Color.Transparent,
        elevation = FloatingActionButtonDefaults.elevation(6.dp),
        modifier = Modifier
            .padding(bottom = 36.dp)
            .size(60.dp)
            .background(BrandPalette.brandGradient, CircleShape),
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Add transaction", tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    NavigationBar(
        containerColor = KoshTheme.colors.cardSurface,
        tonalElevation = 0.dp,
        modifier = Modifier,
    ) {
        Tabs.forEachIndexed { index, tab ->
            if (index == 2) {
                // Room for the centre add button.
                Box(Modifier.weight(0.6f))
            }
            val selected = current == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        nav.navigate(tab.route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    unselectedIconColor = KoshTheme.colors.subtleText,
                    unselectedTextColor = KoshTheme.colors.subtleText,
                ),
            )
        }
    }
}

@Composable
private fun KoshNavHost(nav: NavHostController) {
    val idArg = listOf(navArgument("id") { type = NavType.LongType })
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = {
            if (targetState.destination.route in TabRoutes) fadeIn(tween(220))
            else slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))
        },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = {
            if (initialState.destination.route in TabRoutes) fadeOut(tween(180))
            else slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(260))
        },
    ) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.TRANSACTIONS) { TransactionsScreen(nav) }
        composable(Routes.PLANNING) { PlanningScreen(nav) }
        composable(Routes.WEALTH) { WealthScreen(nav) }
        composable(Routes.MORE) { MoreScreen(nav) }

        composable(Routes.REVIEW) { ReviewScreen(nav) }
        composable(
            Routes.ADD_TXN,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType; defaultValue = TxnType.EXPENSE.name },
                navArgument("account") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val type = runCatching { TxnType.valueOf(entry.arguments?.getString("type") ?: "") }.getOrDefault(TxnType.EXPENSE)
            val account = entry.arguments?.getLong("account")?.takeIf { it > 0 }
            AddTransactionScreen(nav, editId = null, initialType = type, initialAccountId = account)
        }
        composable(Routes.EDIT_TXN, arguments = idArg) { entry ->
            AddTransactionScreen(nav, editId = entry.arguments?.getLong("id"), initialType = TxnType.EXPENSE, initialAccountId = null)
        }
        composable(Routes.TXN_DETAIL, arguments = idArg) { entry -> TransactionDetailScreen(nav, entry.arguments!!.getLong("id")) }
        composable(Routes.ACCOUNTS) { AccountsScreen(nav) }
        composable(Routes.ACCOUNT_DETAIL, arguments = idArg) { entry -> AccountDetailScreen(nav, entry.arguments!!.getLong("id")) }
        composable(
            Routes.ACCOUNT_EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                navArgument("card") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            AccountEditScreen(
                nav,
                editId = entry.arguments?.getLong("id")?.takeIf { it > 0 },
                defaultCard = entry.arguments?.getBoolean("card") ?: false,
            )
        }
        composable(Routes.BILLS) { BillsScreen(nav) }
        composable(Routes.INVESTMENT_ADD, arguments = listOf(navArgument("type") { type = NavType.StringType })) { entry ->
            val type = InvestmentType.valueOf(entry.arguments?.getString("type") ?: InvestmentType.FD.name)
            InvestmentEditScreen(nav, editId = null, type = type)
        }
        composable(Routes.INVESTMENT_EDIT, arguments = idArg) { entry ->
            InvestmentEditScreen(nav, editId = entry.arguments!!.getLong("id"), type = null)
        }
        composable(Routes.INVESTMENT_DETAIL, arguments = idArg) { entry -> InvestmentDetailScreen(nav, entry.arguments!!.getLong("id")) }
        composable(Routes.INSIGHTS) { InsightsScreen(nav) }
        composable(Routes.CATEGORIES) { CategoriesScreen(nav) }
    }
}

