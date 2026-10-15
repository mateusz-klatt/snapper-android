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

private enum class Destination(val label: Int, val permission: String? = null) {
    HOME(R.string.home), POSITIONS(R.string.positions, "read:positions"),
    ORDERS(R.string.orders, "read:orders"), ALERTS(R.string.alerts, "read:notifications"),
    MARKET(R.string.market, "read:market_data"), SETTINGS(R.string.settings)
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
    val destinations = Destination.entries.filter { it.permission == null || state.user?.effectivePermissions?.contains(it.permission) == true }
    val current = destinations.firstOrNull { it.name == selected } ?: Destination.HOME
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
                Destination.POSITIONS -> positionsScreen(state)
                Destination.ORDERS -> ordersScreen(state)
                Destination.ALERTS -> alertsScreen(state)
                Destination.MARKET -> marketScreen(state.market, actions.loadMarket)
                Destination.SETTINGS -> item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { SettingsScreen(state, actions) } }
            }
            if (current != Destination.SETTINGS && current != Destination.MARKET) {
                item { OutlinedButton(onClick = actions.refresh, enabled = !state.isLoading, modifier = Modifier.testTag("dashboard.refresh")) {
                    Text(stringResource(R.string.refresh))
                } }
            }
        }
    }
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
            if (snapshot.user.hasPermission("read:positions")) Metric(R.string.positions, snapshot.positions.size.toString())
            if (snapshot.user.hasPermission("read:orders")) Metric(R.string.orders, snapshot.orders.size.toString())
            if (snapshot.user.hasPermission("read:notifications")) Metric(R.string.alerts, snapshot.alerts.size.toString())
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, actions: AppActions) {
    Text(state.user?.username.orEmpty(), style = MaterialTheme.typography.titleMedium)
    Text(state.backendUrl)
    LanguagePicker(state.language, actions.selectLanguage)
    Text(stringResource(R.string.backend_warning), style = MaterialTheme.typography.bodySmall)
    BackendEditor(state.backendUrl, actions.saveBackend)
    Button(onClick = actions.logout, modifier = Modifier.testTag("settings.logout")) { Text(stringResource(R.string.logout)) }
}
