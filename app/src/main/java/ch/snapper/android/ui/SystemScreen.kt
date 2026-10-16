package ch.snapper.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.features.system.SystemActions
import ch.snapper.android.features.system.SystemFeed
import ch.snapper.android.features.system.SystemFeedState
import ch.snapper.android.features.system.SystemState

/** Stateless, permission-gated feeds. Session scope and request generations belong to the caller. */
internal fun LazyListScope.systemScreen(state: SystemState, actions: SystemActions) {
    statusSection(state, actions)
    healthSection(state, actions)
    strategiesSection(state, actions)
    backtestsSection(state, actions)
    usersSection(state, actions)
    delegatesSection(state, actions)
}

private fun LazyListScope.statusSection(state: SystemState, actions: SystemActions) {
    if (SystemFeed.STATUS.isAllowed(state.permissions)) item {
        SystemSection(R.string.system_trader, SystemFeed.STATUS, state.status, actions) { data ->
            Metric(R.string.status, data.trader.status)
            data.trader.error?.let { Text(it) }
            data.backtests.toSortedMap().forEach { (name, process) -> RecordCard(name) { Text(process.status) } }
            data.strategies.orEmpty().forEach { strategy -> RecordCard(strategy.strategyName) { Text(strategy.status) } }
        }
    }
}

private fun LazyListScope.healthSection(state: SystemState, actions: SystemActions) {
    if (SystemFeed.HEALTH.isAllowed(state.permissions)) item {
        SystemSection(R.string.system_health, SystemFeed.HEALTH, state.health, actions) { data ->
            Metric(R.string.status, data.status)
            Metric(R.string.system_version, data.version)
            Metric(R.string.system_connections, data.connections.activeConnections.counter())
            Metric(R.string.system_subscribers, data.connections.zmqSubscribers.counter())
            Metric(R.string.system_tasks, data.connections.subscriberTasks.counter())
            Metric(R.string.system_topics, data.topics.active.toString())
            Metric(R.string.system_clients, data.connections.activeClients.counter())
            Metric(R.string.system_gaps, data.gapDetection.bridge.gapsDetected.counter())
            Metric(R.string.system_resets, data.gapDetection.bridge.sessionResets.counter())
            Metric(R.string.system_duplicates, data.gapDetection.bridge.duplicates.counter())
            Metric(R.string.system_joins, data.gapDetection.bridge.midStreamJoins.counter())
            Metric(R.string.system_unstamped, data.gapDetection.bridge.rejectedUnstamped.counter())
            Metric(R.string.system_rest_clients, data.gapDetection.restClients?.size?.toString() ?: "—")
        }
    }
}

private fun LazyListScope.strategiesSection(state: SystemState, actions: SystemActions) {
    if (SystemFeed.STRATEGIES.isAllowed(state.permissions)) item {
        SystemSection(R.string.system_strategies, SystemFeed.STRATEGIES, state.strategies, actions) { rows ->
            EmptySystemRows(rows.isEmpty())
            rows.sortedBy { it.name }.forEach { row -> RecordCard(row.name) {
                Metric(R.string.status, stringResource(if (row.running) R.string.system_running else R.string.system_stopped))
                Metric(R.string.system_mode, row.mode)
                if (row.enabled) Text(stringResource(R.string.system_autostart))
                row.strategyClass?.let { Text(it) }
                (row.coordinatorLabel ?: row.coordinator)?.let { Text(it) }
            } }
        }
    }
}

private fun LazyListScope.backtestsSection(state: SystemState, actions: SystemActions) {
    if (SystemFeed.BACKTESTS.isAllowed(state.permissions)) item {
        SystemSection(R.string.system_backtests, SystemFeed.BACKTESTS, state.backtests, actions) { rows ->
            EmptySystemRows(rows.isEmpty())
            rows.forEach { row -> RecordCard(row.strategyName) {
                Text(row.instrument ?: row.instrumentPublicId)
                Metric(R.string.status, row.status)
                Metric(R.string.exchange, row.exchange)
                row.targetExecutionExchange?.let { Text(it) }
                Metric(R.string.timeframe, row.timeframe)
                Metric(R.string.system_period, "${row.startDate} – ${row.endDate}")
                Metric(R.string.system_initial_cash, row.initialCash.display())
                Text(row.publicId, style = MaterialTheme.typography.bodySmall)
                row.error?.let { Text(it) }
            } }
        }
    }
}

private fun LazyListScope.usersSection(state: SystemState, actions: SystemActions) {
    if (SystemFeed.USERS.isAllowed(state.permissions)) item {
        SystemSection(R.string.system_users, SystemFeed.USERS, state.users, actions) { rows ->
            EmptySystemRows(rows.isEmpty())
            rows.sortedBy { it.username }.forEach { row -> RecordCard(row.username) {
                Text(row.role)
                row.email?.let { Text(it) }
                Metric(R.string.status, row.isActive?.let { stringResource(if (it) R.string.system_active else R.string.system_inactive) } ?: "—")
                Metric(R.string.system_created, row.createdAt)
            } }
        }
    }
}

private fun LazyListScope.delegatesSection(state: SystemState, actions: SystemActions) {
    if (SystemFeed.DELEGATES.isAllowed(state.permissions)) item {
        SystemSection(R.string.system_delegates, SystemFeed.DELEGATES, state.delegates, actions) { rows ->
            EmptySystemRows(rows.isEmpty())
            rows.sortedBy { it.label }.forEach { row -> RecordCard(row.label) {
                Text(row.username)
                Metric(R.string.status, stringResource(if (row.isActive) R.string.system_active else R.string.system_revoked))
                Metric(R.string.system_created, row.createdAt)
                Metric(R.string.system_max_orders, row.caps.maxOpenOrders?.toString() ?: stringResource(R.string.system_default))
                Metric(R.string.system_max_daily, row.caps.maxDailyNotionalUsd?.display() ?: stringResource(R.string.system_default))
            } }
        }
    }
}

@Composable
private fun <T> SystemSection(title: Int, feed: SystemFeed, state: SystemFeedState<T>, actions: SystemActions, content: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("system.${feed.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { actions.refresh(feed) }, enabled = !state.isLoading) { Text(stringResource(R.string.refresh)) }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ErrorCard(it, { actions.refresh(feed) }) }
        state.value?.let { content(it) }
    }
}

@Composable
private fun EmptySystemRows(empty: Boolean) {
    if (empty) Text(stringResource(R.string.empty))
}

private fun Long?.counter(): String = this?.toString() ?: "—"
