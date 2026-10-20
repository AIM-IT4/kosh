package com.amitjha.kosh.ui

import com.amitjha.kosh.designsystem.components.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.sp
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import kotlinx.coroutines.launch
import com.amitjha.kosh.ui.nav.navigateToTab

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

    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    DisposableEffect(activity, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) {
                androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            },
            navigationBarStyle = if (dark) {
                androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            },
        )
        onDispose {}
    }

    val language by remember(container) {
        container.featurePrefs.settings.map { com.amitjha.kosh.ui.common.AppLanguage.of(it.language) }
    }.collectAsState(initial = com.amitjha.kosh.ui.common.AppLanguage.ENGLISH)
    KoshTheme(darkTheme = dark, dynamicColor = settings?.dynamicColor == true) {
        CompositionLocalProvider(
            com.amitjha.kosh.designsystem.components.LocalTranslator provides remember(language) { language.translator() },
            LocalContainer provides container,
            LocalSettings provides (settings ?: Settings()),
            LocalHideAmounts provides (settings?.hideAmounts ?: false),
        ) {
            val lockEnabled = settings?.biometricLockEnabled == true
            val unlocked by AppLock.unlocked.collectAsStateWithLifecycle()

            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner, lockEnabled) {
                val obs = LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_STOP -> AppLock.onBackground()
                        Lifecycle.Event.ON_START -> AppLock.onForeground(lockEnabled)
                        else -> Unit
                    }
                }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }

            val stage = when {
                // Only while the settings load (a few frames): no artificial wait before the app.
                settings == null -> Stage.Splash
                !settings.onboardingDone -> Stage.Onboarding
                else -> Stage.Main
            }
            val locked = lockEnabled && !unlocked && stage != Stage.Splash

            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                // The lock is drawn over the app instead of replacing it, so re-locking after a minute away keeps
                // the back stack and any half-filled form. While locked the app underneath is invisible to the eye
                // and to accessibility services.
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (locked) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { } else Modifier),
                ) {
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "stage",
                    ) { s ->
                        when (s) {
                            Stage.Splash -> Splash()
                            Stage.Onboarding -> OnboardingScreen()
                            Stage.Main -> MainShell(initialRoute, onRouteConsumed)
                        }
                    }
                }
                if (locked) LockScreen(onUnlocked = { AppLock.markUnlocked() })
                // A dialog is its own window, so it would show over the lock screen: only offer updates when unlocked.
                if (stage == Stage.Main && !locked) com.amitjha.kosh.update.UpdatePrompt()
            }
        }
    }
}

private enum class Stage { Splash, Onboarding, Main }

@Composable
private fun Splash() {
    var start by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { start = true }
    val alpha by animateFloatAsState(if (start) 1f else 0f, tween(300), label = "splash")
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { this.alpha = alpha },
        ) {
            KoshLogo(size = 88.dp, shape = LogoShape.RoundedSquare)
            Spacer(Modifier.height(12.dp))
            Text("Kosh", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = KoshTheme.colors.ink)
        }
    }
}

@Composable
private fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf(BiometricGate.isAvailable(context)) }
    // App PIN / decoy PIN (see ui.tools.PinLockPad); biometrics always open the real profile.
    val toolsPrefs by remember { com.amitjha.kosh.tools.ToolsSettings.get(context).prefs }.collectAsStateWithLifecycle(initialValue = null)
    val realTheme = com.amitjha.kosh.ui.common.LocalSettings.current.themeMode
    val unlock: () -> Unit = unlock@{
        val activity = context as? FragmentActivity ?: return@unlock
        available = BiometricGate.isAvailable(context)
        if (!available) {
            // Fail closed: with no screen lock on the phone there is nothing to verify the user with.
            message = "Set a screen lock (PIN, pattern or fingerprint) on your phone to unlock Kosh."
            return@unlock
        }
        scope.launch {
            when (val r = BiometricGate.authenticate(activity)) {
                UnlockResult.Success -> {
                    com.amitjha.kosh.security.DecoySession.deactivate()
                    onUnlocked()
                }
                is UnlockResult.Error -> message = r.message
            }
        }
    }
    // With an app PIN set the PIN pad shows first, so a decoy PIN can be entered without a prompt in the way.
    LaunchedEffect(toolsPrefs?.pinSet) { if (toolsPrefs?.pinSet == false) unlock() }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            KoshLogo(size = 72.dp, shape = LogoShape.RoundedSquare)
            Text("Kosh is locked", style = MaterialTheme.typography.titleLarge, color = KoshTheme.colors.ink)
            message?.let { Text(it, color = KoshTheme.colors.subtleText, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
            val pinPrefs = toolsPrefs
            if (pinPrefs?.pinSet == true) {
                com.amitjha.kosh.ui.tools.PinLockPad(
                    prefs = pinPrefs,
                    onMatch = { match ->
                        scope.launch {
                            if (match == com.amitjha.kosh.security.PinMatch.DECOY) {
                                com.amitjha.kosh.security.DecoySession.activate(context, realTheme)
                            } else {
                                com.amitjha.kosh.security.DecoySession.deactivate()
                            }
                            onUnlocked()
                        }
                    },
                    onBiometric = if (available) unlock else null,
                )
            } else {
                PrimaryButton(text = "Unlock", onClick = unlock, icon = Icons.Outlined.Fingerprint, modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth())
            }
            if (!available && pinPrefs?.pinSet != true) {
                androidx.compose.material3.TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Open security settings") }
            }
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
            Notifications.ROUTE_WARRANTIES -> Routes.WARRANTIES
            Notifications.ROUTE_FAMILY -> Routes.FAMILY
            Notifications.ROUTE_RECURRING -> Routes.RECURRING
            com.amitjha.kosh.tools.QuickLaunch.ROUTE_ADD_EXPENSE -> Routes.addTxn(TxnType.EXPENSE)
            com.amitjha.kosh.tools.QuickLaunch.ROUTE_ADD_INCOME -> Routes.addTxn(TxnType.INCOME)
            else -> null
        }
        if (target != null) {
            if (target in TabRoutes) nav.navigateToTab(target) else nav.navigate(target) { launchSingleTop = true }
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
            AnimatedVisibility(
                current == Routes.HOME || current == Routes.TRANSACTIONS,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120)),
            ) {
                AddFab { nav.navigate(Routes.addTxn()) }
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.End,
    ) { padding ->
        Box(Modifier.padding(bottom = if (showBar) padding.calculateBottomPadding() else 0.dp)) {
            KoshNavHost(nav)
        }
    }
}

@Composable
private fun AddFab(onClick: () -> Unit) {
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        containerColor = KoshTheme.colors.ink,
        contentColor = KoshTheme.colors.onInk,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = "Add transaction")
    }
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    KoshBottomBar(current) { route -> nav.navigateToTab(route) }
}

/** The Material 3 navigation bar: five tabs, the selected one in a tinted pill, over a top hairline. */
@Composable
fun KoshBottomBar(current: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(KoshTheme.colors.hairline))
        androidx.compose.material3.NavigationBar(
            containerColor = KoshTheme.colors.cardSurface,
            tonalElevation = 0.dp,
        ) {
            Tabs.forEach { tab ->
                val selected = current == tab.route
                androidx.compose.material3.NavigationBarItem(
                    selected = selected,
                    onClick = { if (!selected) onSelect(tab.route) },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(tr(tab.label), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = KoshTheme.colors.ink,
                        selectedTextColor = KoshTheme.colors.ink,
                        indicatorColor = KoshTheme.colors.accentTint,
                        unselectedIconColor = KoshTheme.colors.tertiaryText,
                        unselectedTextColor = KoshTheme.colors.tertiaryText,
                    ),
                )
            }
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
        composable(Routes.PENDING_ACCOUNTS) { com.amitjha.kosh.ui.accounts.PendingAccountsScreen(nav) }
        composable(Routes.NET_WORTH) { com.amitjha.kosh.ui.wealth.NetWorthBreakdownScreen(nav) }
        composable(Routes.UNCATEGORISED) { com.amitjha.kosh.ui.transactions.UncategorisedScreen(nav) }
        composable(Routes.TAX) { com.amitjha.kosh.ui.tax.TaxScreen(nav) }
        composable(Routes.HOUSEHOLD) { com.amitjha.kosh.ui.household.HouseholdScreen(nav) }
        composable(Routes.RECURRING) { com.amitjha.kosh.ui.features.RecurringScreen(nav) }
        composable(
            Routes.RECURRING_EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                navArgument("series") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            com.amitjha.kosh.ui.features.RecurringEditScreen(
                nav,
                entry.arguments!!.getLong("id").takeIf { it > 0 },
                entry.arguments!!.getString("series")?.takeIf { it.isNotBlank() },
            )
        }
        composable(Routes.LOANS) { com.amitjha.kosh.ui.features.LoansScreen(nav) }
        composable(Routes.LOAN_DETAIL, arguments = idArg) { entry -> com.amitjha.kosh.ui.features.LoanDetailScreen(nav, entry.arguments!!.getLong("id")) }
        composable(Routes.LOAN_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L })) { entry ->
            com.amitjha.kosh.ui.features.LoanEditScreen(nav, entry.arguments!!.getLong("id").takeIf { it > 0 })
        }
        composable(Routes.DEBTS) { com.amitjha.kosh.ui.features.DebtsScreen(nav) }
        composable(Routes.AUTO_BACKUP) { com.amitjha.kosh.ui.features.AutoBackupScreen(nav) }
        composable(Routes.STATEMENT_IMPORT) { com.amitjha.kosh.ui.features.StatementImportScreen(nav) }
        composable(Routes.REPORTS) { com.amitjha.kosh.ui.features.ReportsScreen(nav) }
        composable(Routes.YEAR_REVIEW, arguments = listOf(navArgument("year") { type = NavType.IntType })) { entry ->
            com.amitjha.kosh.ui.features.YearReviewScreen(nav, entry.arguments!!.getInt("year"))
        }
        composable(Routes.CALENDAR) { com.amitjha.kosh.ui.features.CalendarScreen(nav) }
        composable(Routes.WARRANTIES) { com.amitjha.kosh.ui.features.WarrantiesScreen(nav) }
        composable(Routes.SMS_CHECKUP) { com.amitjha.kosh.ui.settings.SmsCheckupScreen(nav) }
        composable(Routes.FAMILY) { com.amitjha.kosh.ui.family.FamilyScreen(nav) }
    }
}

