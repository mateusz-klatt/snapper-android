package ch.snapper.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.snapper.android.OrderActions
import ch.snapper.android.OrderDraft
import ch.snapper.android.OrderFlowState
import ch.snapper.android.OrderForm
import ch.snapper.android.OrderIssue
import ch.snapper.android.R

@Composable
internal fun OrderEntry(flow: OrderFlowState, actions: OrderActions) {
    val draft = flow.draft ?: return
    val enabled = !flow.isSubmitting && !flow.loadingCatalog
    OrderCatalogue(flow, actions)
    OrderPicker(R.string.side, stringResource(sideLabel(draft.side)), "order.side", enabled) { close ->
        listOf("buy", "sell").forEach { side ->
            DropdownMenuItem(text = { Text(stringResource(sideLabel(side))) },
                modifier = Modifier.testTag("order.side.$side"), onClick = { actions.updateDraft(draft.copy(side = side)); close() })
        }
    }
    OrderPicker(R.string.order_type, stringResource(orderTypeLabel(draft.orderType)), "order.type", enabled) { close ->
        OrderForm.orderTypes.forEach { type ->
            DropdownMenuItem(text = { Text(stringResource(orderTypeLabel(type))) }, modifier = Modifier.testTag("order.type.$type"),
                onClick = { actions.updateDraft(draft.copy(orderType = type)); close() })
        }
    }
    OrderAmounts(draft, actions.updateDraft, enabled)
    val reduceOnlyLabel = stringResource(R.string.order_reduce_only)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(reduceOnlyLabel, modifier = Modifier.weight(1f))
        Switch(checked = draft.reduceOnly, onCheckedChange = { actions.updateDraft(draft.copy(reduceOnly = it)) },
            enabled = enabled, modifier = Modifier.testTag("order.reduceOnly").semantics { contentDescription = reduceOnlyLabel })
    }
    Text(stringResource(R.string.order_invalid_form), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun OrderCatalogue(flow: OrderFlowState, actions: OrderActions) {
    val draft = flow.draft ?: return
    val enabled = !flow.isSubmitting && !flow.loadingCatalog
    OrderPicker(R.string.exchange, draft.exchange, "order.exchange", enabled) { close ->
        flow.exchanges.forEach { exchange ->
            DropdownMenuItem(text = { Text(exchange) }, modifier = Modifier.testTag("order.exchange.$exchange"),
                onClick = { actions.selectExchange(exchange); close() })
        }
    }
    val selected = flow.instruments.firstOrNull { it.instrumentPublicId == draft.instrumentPublicId }
    OrderPicker(R.string.instrument, selected?.symbol.orEmpty(), "order.instrument", enabled && flow.instruments.isNotEmpty()) { close ->
        flow.instruments.forEach { instrument ->
            val tradable = instrument.canTrade && instrument.instrumentResolved && instrument.exchange == draft.exchange
            DropdownMenuItem(text = { Text(instrument.symbol) }, enabled = tradable,
                modifier = Modifier.testTag("order.instrument.${instrument.instrumentPublicId}"),
                onClick = { actions.updateDraft(draft.copy(instrumentPublicId = instrument.instrumentPublicId)); close() })
        }
    }
    if (flow.loadingCatalog) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("order.catalog.loading"))
    if (!flow.loadingCatalog && flow.instruments.isEmpty()) Text(stringResource(R.string.order_no_instruments))
    if (flow.instruments.any { !it.canTrade || !it.instrumentResolved }) Text(stringResource(R.string.order_observation_only))
    if (flow.issue == OrderIssue.CATALOG) {
        OutlinedButton(onClick = actions.retryCatalog, enabled = enabled, modifier = Modifier.testTag("order.catalog.retry")) {
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
private fun OrderAmounts(draft: OrderDraft, update: (OrderDraft) -> Unit, enabled: Boolean) {
    OrderNumberField(R.string.order_quantity, draft.quantity, "order.quantity", enabled) { update(draft.copy(quantity = it)) }
    if (OrderForm.needsPrice(draft.orderType)) {
        OrderNumberField(R.string.price, draft.price, "order.price", enabled) { update(draft.copy(price = it)) }
    }
    if (OrderForm.needsStopPrice(draft.orderType)) {
        OrderNumberField(R.string.order_stop_price, draft.stopPrice, "order.stopPrice", enabled) { update(draft.copy(stopPrice = it)) }
    }
    OrderNumberField(R.string.order_leverage, draft.leverage, "order.leverage", enabled) { update(draft.copy(leverage = it)) }
}

@Composable
private fun OrderNumberField(label: Int, value: String, tag: String, enabled: Boolean, update: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = update, label = { Text(stringResource(label)) }, singleLine = true,
        enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun OrderPicker(label: Int, value: String, tag: String, enabled: Boolean,
    options: @Composable (close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                Text(value.ifBlank { stringResource(label) })
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                options { expanded = false }
            }
        }
    }
}
