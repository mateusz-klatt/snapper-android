package ch.snapper.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.snapper.android.AppUiState
import ch.snapper.android.OrderActions
import ch.snapper.android.OrderFlowState
import ch.snapper.android.OrderForm
import ch.snapper.android.OrderIssue
import ch.snapper.android.OrderOutcome
import ch.snapper.android.OrderReview
import ch.snapper.android.R
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.OrderScope
import ch.snapper.android.data.WalletDto

@Composable
internal fun OrderControls(state: AppUiState, actions: OrderActions) {
    val wallet = state.snapshot?.wallets?.firstOrNull { it.publicId == state.user?.activeWalletPublicId }
    val canReadCatalogue = state.user?.hasPermission("read:market_data") == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.user?.hasPermission("create:orders") == true) {
            Button(onClick = actions.open, enabled = wallet != null && canReadCatalogue && !state.orders.isSubmitting && !state.isLoading,
                modifier = Modifier.testTag("order.new")) { Text(stringResource(R.string.order_new)) }
            if (wallet == null) Text(stringResource(R.string.order_select_wallet))
            if (!canReadCatalogue) Text(stringResource(R.string.order_forbidden))
        }
        if (state.orders.isDismissed || (state.orders.draft == null && state.orders.pendingCancel == null)) {
            OrderFeedback(state.orders, actions, state.isLoading)
        }
    }
}

@Composable
internal fun OrderDialogs(state: AppUiState, actions: OrderActions) {
    val user = state.user ?: return
    val wallet = state.snapshot?.wallets?.firstOrNull { it.publicId == user.activeWalletPublicId }
    val flow = state.orders
    if (flow.isDismissed) return
    val scope = OrderScope.from(user)
    key(state.backendUrl, scope) {
        if (flow.draft != null && wallet != null && user.hasPermission("create:orders") && user.hasPermission("read:market_data")) {
            if (flow.review == null || flow.review.scope == scope) {
                NewOrderDialog(flow, wallet, actions, state.isLoading)
            }
        }
        flow.pendingCancel?.let { order ->
            if (OrderForm.canCancel(order, user) && flow.cancelScope == scope) {
                CancelOrderDialog(order, state, actions)
            }
        }
    }
}

@Composable
private fun OrderDialog(title: Int, dismiss: () -> Unit, busy: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 720.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
                content()
            }
        }
    }
}

@Composable
private fun NewOrderDialog(flow: OrderFlowState, wallet: WalletDto, actions: OrderActions, checking: Boolean) {
    OrderDialog(if (flow.review == null) R.string.order_entry else R.string.order_confirm, actions.dismiss, flow.isSubmitting) {
        if (flow.review == null) {
            WalletSummary(wallet)
            OrderEntry(flow, actions)
        } else {
            ReviewSummary(flow.review)
        }
        OrderFeedback(flow, actions, checking)
        if (flow.outcome != OrderOutcome.UNKNOWN) {
            NewOrderButtons(flow, actions)
        }
        TextButton(onClick = actions.dismiss, enabled = !flow.isSubmitting, modifier = Modifier.testTag("order.dismiss")) {
            Text(stringResource(R.string.dismiss))
        }
    }
}

@Composable
private fun NewOrderButtons(flow: OrderFlowState, actions: OrderActions) {
    if (flow.review == null) {
        Button(onClick = actions.review, enabled = flow.canReview && !flow.loadingCatalog && !flow.isSubmitting,
            modifier = Modifier.testTag("order.review")) { Text(stringResource(R.string.order_review)) }
    } else {
        Button(onClick = actions.confirm, enabled = !flow.isSubmitting, modifier = Modifier.testTag("order.confirm")) {
            Text(stringResource(if (flow.isSubmitting) R.string.order_submitting else R.string.order_submit))
        }
        TextButton(onClick = actions.back, enabled = !flow.isSubmitting, modifier = Modifier.testTag("order.back")) {
            Text(stringResource(R.string.order_back))
        }
    }
}

@Composable
internal fun WalletSummary(wallet: WalletDto) {
    Metric(R.string.wallet, wallet.label)
    Metric(R.string.order_mode, stringResource(if (wallet.isPaper) R.string.paper else R.string.order_live))
}

@Composable
private fun ReviewSummary(review: OrderReview) {
    val body = review.body
    Metric(R.string.wallet, review.walletLabel)
    Metric(R.string.order_mode, stringResource(if (body.mode == "paper") R.string.paper else R.string.order_live))
    Metric(R.string.exchange, body.exchange)
    Metric(R.string.instrument, body.instrument)
    Metric(R.string.side, stringResource(sideLabel(body.side)))
    Metric(R.string.order_type, stringResource(orderTypeLabel(body.orderType)))
    Metric(R.string.order_quantity, body.quantity.display())
    body.price?.let { Metric(R.string.price, it.display()) }
    body.stopPrice?.let { Metric(R.string.order_stop_price, it.display()) }
    body.leverage?.let { Metric(R.string.order_leverage, it.toString()) }
    Metric(R.string.order_reduce_only, stringResource(if (body.reduceOnly) R.string.order_enabled else R.string.order_disabled))
}

@Composable
private fun CancelOrderDialog(order: OrderDto, state: AppUiState, actions: OrderActions) {
    val flow = state.orders
    OrderDialog(R.string.order_cancel_title, actions.dismissCancel, flow.isSubmitting) {
        Metric(R.string.instrument, order.instrument)
        Metric(R.string.exchange, order.exchange)
        Metric(R.string.side, stringResource(sideLabel(order.side)))
        Metric(R.string.size, order.size.display())
        Metric(R.string.price, order.price.display())
        Metric(R.string.wallet, state.snapshot?.wallets?.firstOrNull { it.publicId == order.walletPublicId }?.label
            ?: order.walletPublicId.orEmpty())
        OrderFeedback(flow, actions, state.isLoading)
        if (flow.outcome != OrderOutcome.UNKNOWN) {
            Button(onClick = actions.confirmCancel, enabled = !flow.isSubmitting, modifier = Modifier.testTag("order.cancel.confirm")) {
                Text(stringResource(R.string.order_cancel_confirm))
            }
        }
        TextButton(onClick = actions.dismissCancel, enabled = !flow.isSubmitting, modifier = Modifier.testTag("order.cancel.dismiss")) {
            Text(stringResource(if (flow.outcome == OrderOutcome.UNKNOWN) R.string.dismiss else R.string.order_keep_open))
        }
    }
}

@Composable
private fun OrderFeedback(flow: OrderFlowState, actions: OrderActions, checking: Boolean) {
    flow.issue?.let { Text(stringResource(issueLabel(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("order.issue")) }
    flow.outcome?.let { outcome ->
        val label = when (outcome) {
            OrderOutcome.CREATED -> R.string.order_created
            OrderOutcome.CANCEL_REQUESTED -> R.string.order_cancel_requested
            OrderOutcome.UNKNOWN -> R.string.order_unknown_outcome
        }
        Text(stringResource(label), modifier = Modifier.testTag("order.outcome"))
        if (flow.snapshotRefreshFailed) Text(stringResource(R.string.order_refresh_failed))
        if (outcome == OrderOutcome.UNKNOWN) {
            OutlinedButton(onClick = actions.checkStatus, enabled = !checking && !flow.isSubmitting,
                modifier = Modifier.testTag("order.checkStatus")) { Text(stringResource(R.string.order_check_status)) }
            if (flow.review != null) {
                OutlinedButton(onClick = actions.retry, enabled = !flow.isSubmitting && !checking,
                    modifier = Modifier.testTag("order.retry")) { Text(stringResource(R.string.order_retry_intent)) }
            }
        } else {
            TextButton(onClick = actions.clearResult, modifier = Modifier.testTag("order.result.dismiss")) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

private fun issueLabel(issue: OrderIssue): Int = when (issue) {
    OrderIssue.INVALID_FORM -> R.string.order_invalid_form
    OrderIssue.CATALOG -> R.string.order_catalog_error
    OrderIssue.NETWORK -> R.string.error_network
    OrderIssue.REJECTED -> R.string.order_rejected
    OrderIssue.FORBIDDEN -> R.string.order_forbidden
    OrderIssue.SESSION_CHANGED -> R.string.order_session_changed
    OrderIssue.UNKNOWN_INSTRUMENT -> R.string.order_unknown_instrument
    OrderIssue.OBSERVATION_ONLY -> R.string.order_observation_only
    OrderIssue.CAPS_VIOLATION -> R.string.order_caps_violation
}

internal fun sideLabel(side: String): Int = if (side == "sell") R.string.order_sell else R.string.order_buy

internal fun orderTypeLabel(type: String): Int = when (type) {
    "market" -> R.string.order_market
    "stop" -> R.string.order_stop
    "stop_limit" -> R.string.order_stop_limit
    else -> R.string.order_limit
}
