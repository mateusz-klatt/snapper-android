package ch.snapper.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.R
import ch.snapper.android.BuildConfig
import ch.snapper.android.data.OrderScope
import ch.snapper.android.features.system.SystemFeed

private const val POSITIONS_READ_PERMISSION = "read:positions"

private enum class Destination(val label: Int, val permission: String? = null) {
    HOME(R.string.home), POSITIONS(R.string.positions, POSITIONS_READ_PERMISSION),
    ORDERS(R.string.orders, "read:orders"), ALERTS(R.string.alerts, "read:notifications"),
    MARKET(R.string.market, "read:market_data"), SYSTEM(R.string.system_tools),
    ACCOUNTS(R.string.venue_accounts_title, "read:account_state"), SIGNALS(R.string.signals_title, "read:signals"),
    PNL(R.string.positions_timeline_chart_title, POSITIONS_READ_PERMISSION), DESKS(R.string.desk_nav_title),
    PROCESSES(R.string.process_title), AI_REVIEWS(R.string.ai_reviews_decisions_title),
    NOTIFICATIONS(R.string.notification_defaults_title), SETTINGS(R.string.settings)
}

@Composable
fun SnapperApp(state: AppUiState, actions: AppActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.isRestoring -> Column(Modifier.padding(32.dp)) { CircularProgressIndicator(); Text(stringResource(R.string.loading)) }
            state.user == null -> LoginScreen(state, actions)
            else -> AuthenticatedApp(state, actions)
        }
    }
}

@Composable
private fun AuthenticatedApp(state: AppUiState, actions: AppActions) {
    var selected by rememberSaveable { mutableStateOf(Destination.HOME.name) }
    val permissions = state.user?.effectivePermissions.orEmpty().toSet()
    val destinations = Destination.entries.filter {
        when (it) {
            Destination.SYSTEM -> SystemFeed.entries.any { feed -> feed.isAllowed(permissions) }
            Destination.PROCESSES -> "read:system_status" in permissions || "read:processes" in permissions
            Destination.AI_REVIEWS -> "read:ai_reviews" in permissions ||
                (!state.user?.delegatePublicId.isNullOrBlank() && "read:signals" in permissions)
            else -> it.permission == null || it.permission in permissions
        }
    }
    val current = destinations.firstOrNull { it.name == selected } ?: Destination.HOME
    val sessionScope = state.user?.let { Triple(OrderScope.from(it), it.primaryOperatorPublicId, it.delegatePublicId) }
    val pnlWallet = state.snapshot?.wallets?.firstOrNull { it.publicId == state.user?.activeWalletPublicId }
    LaunchedEffect(current, state.backendUrl, sessionScope, if (current == Destination.PNL) pnlWallet else null) {
        when (current) {
            Destination.MARKET -> actions.loadCatalog()
            Destination.DESKS -> actions.desks.reload()
            Destination.NOTIFICATIONS -> actions.notificationDefaults.refresh()
            Destination.SYSTEM -> SystemFeed.entries.filter { it.isAllowed(permissions) }.forEach(actions.system::refresh)
            Destination.SIGNALS -> actions.signals.refresh()
            Destination.PNL -> actions.pnl.refresh()
            Destination.PROCESSES -> actions.loadProcesses()
            Destination.AI_REVIEWS -> actions.loadAiReviews()
            else -> Unit
        }
    }
    DisposableEffect(current, state.backendUrl, sessionScope) {
        actions.venueAccountsVisible(current == Destination.ACCOUNTS)
        actions.marketVisible(current == Destination.MARKET)
        onDispose { actions.venueAccountsVisible(false); actions.marketVisible(false) }
    }
    LaunchedEffect(current, state.backendUrl, sessionScope, state.marketCatalog.exchange,
        state.marketCatalog.instrument, state.marketCatalog.timeframe, state.marketCatalog.canLoad) {
        val catalog = state.marketCatalog
        if (current == Destination.MARKET && catalog.canLoad) actions.selectMarket(catalog.exchange, catalog.instrument, catalog.timeframe)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PrimaryScrollableTabRow(selectedTabIndex = destinations.indexOf(current), edgePadding = 0.dp) {
            destinations.forEach { destination ->
                Tab(selected = destination == current, onClick = { selected = destination.name },
                    modifier = Modifier.testTag("nav.${destination.name.lowercase()}"),
                    text = { Text(stringResource(destination.label)) })
            }
        }
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(current.label), style = MaterialTheme.typography.headlineMedium)
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorCard(it, actions.refresh) }
            } }
            when (current) {
                Destination.HOME -> item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { HomeScreen(state, actions) } }
                Destination.POSITIONS -> positionsScreen(state, actions)
                Destination.ORDERS -> ordersScreen(state, actions.orders, state.records, actions.records)
                Destination.ALERTS -> alertsScreen(state)
                Destination.MARKET -> {
                    marketScreen(state.market, actions.loadMarket, state.marketCatalog, actions.marketCatalog)
                    item { MarketContextContent(state.marketContext, actions.marketContext) }
                }
                Destination.SYSTEM -> systemScreen(state.system, actions.system)
                Destination.SIGNALS -> signalsScreen(state.signals, actions.signals)
                Destination.ACCOUNTS -> venueAccountsScreen(state.venueAccounts, actions.refreshVenueAccounts)
                Destination.PNL -> item { PnlTimelineScreen(state.pnl, actions.pnl) }
                Destination.PROCESSES -> processesScreen(state.processes, actions.processes)
                Destination.AI_REVIEWS -> aiReviewsScreen(state.aiReviews, actions.aiReviews)
                Destination.DESKS -> item { DeskScreen(state.desks, actions.desks) }
                Destination.NOTIFICATIONS -> notificationDefaultsScreen(state.notificationDefaults, actions.notificationDefaults)
                Destination.SETTINGS -> item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { SettingsScreen(state, actions) } }
            }
            if (current in setOf(Destination.HOME, Destination.POSITIONS, Destination.ORDERS, Destination.ALERTS)) {
                item { OutlinedButton(onClick = actions.refresh, enabled = !state.isLoading, modifier = Modifier.testTag("dashboard.refresh")) {
                    Text(stringResource(R.string.refresh))
                } }
            }
        }
    }
    OrderDialogs(state, actions.orders)
    PositionActionDialogs(state.positionActions, actions.positionActions, actions.refresh)
    AiReviewConfirmation(state.aiReviews, actions.aiReviews)
}

@Composable
private fun HomeScreen(state: AppUiState, actions: AppActions) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.snapshot?.wallets?.firstOrNull { it.publicId == state.user?.activeWalletPublicId }
    Text(stringResource(R.string.wallet))
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("home.wallet")) {
            Text(selected?.label ?: stringResource(if (state.user?.activeWalletPublicId == null) R.string.all_wallets else R.string.wallet))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(modifier = Modifier.testTag("wallet.all"), text = { Text(stringResource(R.string.all_wallets)) }, onClick = { expanded = false; actions.selectWallet(null) })
            state.snapshot?.wallets?.forEach { wallet ->
                DropdownMenuItem(modifier = Modifier.testTag("wallet.${wallet.publicId}"), text = { Text(wallet.label + if (wallet.isPaper) " · ${stringResource(R.string.paper)}" else "") },
                    onClick = { expanded = false; actions.selectWallet(wallet.publicId) })
            }
        }
    }
    Text(stringResource(R.string.snapshot_note), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.snapshot_limit), style = MaterialTheme.typography.bodySmall)
    state.snapshot?.let { snapshot ->
        RecordCard(state.user?.username.orEmpty()) {
            if (snapshot.user.hasPermission(POSITIONS_READ_PERMISSION)) Metric(R.string.positions, snapshot.positions.size.toString())
            if (snapshot.user.hasPermission("read:orders")) Metric(R.string.orders, snapshot.orders.size.toString())
            if (snapshot.user.hasPermission("read:notifications")) Metric(R.string.alerts, snapshot.alerts.size.toString())
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, actions: AppActions) {
    val sessionScope = state.user?.let { Triple(OrderScope.from(it), it.primaryOperatorPublicId, it.delegatePublicId) }
    var confirmation by remember(state.backendUrl, sessionScope) { mutableStateOf<AccountConfirmation?>(null) }
    var pendingBackend by remember(state.backendUrl, state.user?.publicId) { mutableStateOf("") }
    state.user?.let { AccountSettingsContent(it, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()) }
    Text(state.backendUrl)
    LanguagePicker(state.language, actions.selectLanguage)
    DisplayPreferencesContent(state.displayPreferences, actions.selectCountry, actions.selectFinancialColor)
    Text(stringResource(R.string.backend_warning), style = MaterialTheme.typography.bodySmall)
    BackendEditor(state.backendUrl) { value -> pendingBackend = value; confirmation = AccountConfirmation.CHANGE_BACKEND }
    PrivacyPolicyLink()
    Button(onClick = { confirmation = AccountConfirmation.LOGOUT }, modifier = Modifier.testTag("settings.logout")) { Text(stringResource(R.string.logout)) }
    confirmation?.let { kind ->
        AccountSettingsConfirmation(kind, state.isLoading, { confirmation = null }) {
            confirmation = null
            if (kind == AccountConfirmation.LOGOUT) actions.logout() else actions.saveBackend(pendingBackend)
        }
    }
}
