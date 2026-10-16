package ch.snapper.android.ui

import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import ch.snapper.android.features.records.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.stringResource
import ch.snapper.android.AppUiState
import ch.snapper.android.R
import ch.snapper.android.OrderActions
import ch.snapper.android.OrderForm
import ch.snapper.android.OrderOutcome

internal fun LazyListScope.positionsScreen(state: AppUiState) {
    val records = state.snapshot?.positions.orEmpty()
    item { Text(stringResource(R.string.snapshot_limit), style = MaterialTheme.typography.bodySmall) }
    if (records.isEmpty() && !state.isLoading) item { Text(stringResource(R.string.empty)) }
    items(records) { position ->
        RecordCard(position.instrument) {
            Text(position.exchange, style = MaterialTheme.typography.labelMedium)
            Metric(R.string.size, position.quantity.display())
            Metric(R.string.average_price, position.averagePrice.display())
            Metric(R.string.mark_price, position.markPrice.display())
            Metric(R.string.pnl, position.unrealizedPnl.display())
        }
    }
}

internal fun LazyListScope.ordersScreen(
    state: AppUiState,
    actions: OrderActions,
    records: RecordsState = RecordsState(),
    recordActions: RecordsActions = RecordsActions(),
) {
    val orders = RecordFilters.orders(state.snapshot?.orders.orEmpty(), records.segment, state.user?.activeWalletPublicId)
    val fills = RecordFilters.fills(records.executions, state.user?.activeWalletPublicId)
    item { OrderControls(state, actions) }
    item {
        Row {
            OrderSegment.entries.forEach { segment ->
                val label = when (segment) {
                    OrderSegment.OPEN -> R.string.records_open
                    OrderSegment.RECENT -> R.string.records_recent
                    OrderSegment.FILLS -> R.string.records_fills
                }
                FilterChip(selected = records.segment == segment, onClick = { recordActions.selectSegment(segment) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("orders.segment.$segment"))
            }
        }
    }
    if (records.segment == OrderSegment.FILLS) {
        item {
            if (records.isLoading) LinearProgressIndicator()
            records.error?.let { ErrorCard(it, recordActions.refreshExecutions) }
            OutlinedButton(onClick = recordActions.refreshExecutions, enabled = !records.isLoading) { Text(stringResource(R.string.records_refresh)) }
            if (fills.isEmpty() && !records.isLoading) Text(stringResource(R.string.empty))
        }
        items(fills) { fill ->
            RecordCard(fill.instrument) {
                Text(fill.exchange, style = MaterialTheme.typography.labelMedium)
                Metric(R.string.side, fill.side)
                Metric(R.string.size, fill.size.display())
                Metric(R.string.price, fill.price.display())
                Metric(R.string.records_fee, "${fill.fee.display()} ${fill.feeAsset}")
                Metric(R.string.status, fill.status)
                Text(fill.executedAt, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    item { Text(stringResource(R.string.snapshot_limit), style = MaterialTheme.typography.bodySmall) }
    if (orders.isEmpty() && !state.isLoading) item { Text(stringResource(R.string.empty)) }
    items(orders) { order ->
        RecordCard(order.instrument) {
            Text(order.exchange, style = MaterialTheme.typography.labelMedium)
            Metric(R.string.side, order.side)
            Metric(R.string.status, order.status)
            Metric(R.string.size, order.size.display())
            Metric(R.string.filled, order.filledSize.display())
            Metric(R.string.price, order.price.display())
            Text(order.createdAt, style = MaterialTheme.typography.bodySmall)
            if (OrderForm.canCancel(order, state.user)) {
                OutlinedButton(onClick = { actions.requestCancel(order) },
                    enabled = !state.isLoading && !state.orders.isSubmitting && state.orders.draft == null &&
                        state.orders.outcome != OrderOutcome.UNKNOWN,
                    modifier = Modifier.testTag("order.cancel.${order.publicId}")) {
                    Text(stringResource(R.string.order_cancel_confirm))
                }
            }
        }
    }
}

internal fun LazyListScope.alertsScreen(state: AppUiState) {
    val records = state.snapshot?.alerts.orEmpty()
    item { Text(stringResource(R.string.snapshot_limit), style = MaterialTheme.typography.bodySmall) }
    if (records.isEmpty() && !state.isLoading) item { Text(stringResource(R.string.empty)) }
    items(records, key = { it.publicId }) { alert ->
        var expanded by remember(alert.publicId) { mutableStateOf(false) }
        RecordCard(alert.displayTitle) {
            if (alert.isSafetyCritical) Text(stringResource(R.string.safety_critical), color = MaterialTheme.colorScheme.error)
            Text(alert.body)
            Text("${alert.priority} · ${alert.alertType}", style = MaterialTheme.typography.labelMedium)
            Text(alert.timestamp, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("alert.details.${alert.publicId}")) {
                Text(stringResource(if (expanded) R.string.records_hide_details else R.string.records_details))
            }
            if (expanded) {
                alert.threadKey?.let { Metric(R.string.records_thread, it) }
                alert.sourceTopic?.let { Metric(R.string.records_source_topic, it) }
            }
        }
    }
}
