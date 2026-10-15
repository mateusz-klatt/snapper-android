package ch.snapper.android.ui

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

internal fun LazyListScope.ordersScreen(state: AppUiState, actions: OrderActions) {
    val records = state.snapshot?.orders.orEmpty()
    item { OrderControls(state, actions) }
    item { Text(stringResource(R.string.snapshot_limit), style = MaterialTheme.typography.bodySmall) }
    if (records.isEmpty() && !state.isLoading) item { Text(stringResource(R.string.empty)) }
    items(records) { order ->
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
    items(records) { alert ->
        RecordCard(alert.displayTitle) {
            if (alert.isSafetyCritical) Text(stringResource(R.string.safety_critical), color = MaterialTheme.colorScheme.error)
            Text(alert.body)
            Text("${alert.priority} · ${alert.alertType}", style = MaterialTheme.typography.labelMedium)
            Text(alert.timestamp, style = MaterialTheme.typography.bodySmall)
        }
    }
}
