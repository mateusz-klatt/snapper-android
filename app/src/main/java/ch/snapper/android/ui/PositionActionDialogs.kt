package ch.snapper.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.data.*
import ch.snapper.android.features.positionactions.*

@Composable
fun PositionActionDialogs(state: PositionActionState, actions: PositionActionActions, checkStatus: () -> Unit) {
    if (state.dismissed || state.draft == null) return
    AlertDialog(onDismissRequest = actions.dismiss,
        title = { Text(stringResource(R.string.position_action_review)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PositionIdentity(state)
            val review = state.review
            if (review == null) PositionFields(state.draft, actions.edit, !state.submitting) else PositionIntentDetails(review.intent)
            state.issue?.let { issue -> Text(stringResource(when (issue) {
                PositionActionIssue.UNKNOWN -> R.string.order_unknown_outcome
                PositionActionIssue.INVALID -> R.string.position_action_invalid
                else -> R.string.error_unknown
            })) }
            if (state.issue == PositionActionIssue.UNKNOWN) TextButton(checkStatus) { Text(stringResource(R.string.order_check_status)) }
        } },
        confirmButton = { PositionConfirm(state, actions) },
        dismissButton = { TextButton(actions.dismiss, enabled = !state.submitting) { Text(stringResource(R.string.positions_reduce_cancel)) } })
}

@Composable
fun PositionActionFeedback(state: PositionActionState, actions: PositionActionActions, checkStatus: () -> Unit) {
    if (state.accepted != null) Text(stringResource(R.string.position_action_accepted), Modifier.testTag("position.accepted"))
    if (state.dismissed && state.issue == PositionActionIssue.UNKNOWN) {
        Text(stringResource(R.string.order_unknown_outcome))
        TextButton(checkStatus) { Text(stringResource(R.string.order_check_status)) }
        TextButton(actions.retry, enabled = !state.submitting) { Text(stringResource(R.string.position_action_retry)) }
    }
}

@Composable
private fun PositionIdentity(state: PositionActionState) {
    state.wallet?.let { Text(it.label + " · " + stringResource(if (it.isPaper) R.string.paper else R.string.order_live)) }
    state.target?.let {
        Text(it.instrument + " · " + it.exchange)
        Text(stringResource(R.string.order_quantity) + ": " + it.quantity.display())
        Text(stringResource(R.string.position_action_cycle) + ": " + it.cyclePublicId)
    }
}

@Composable
private fun PositionConfirm(state: PositionActionState, actions: PositionActionActions) {
    val unknown = state.issue == PositionActionIssue.UNKNOWN
    val callback = if (unknown) actions.retry else if (state.review == null) actions.review else actions.confirm
    val label = if (unknown) R.string.position_action_retry else if (state.review == null) R.string.position_action_review else R.string.position_action_confirm
    TextButton(callback, enabled = !state.submitting, modifier = Modifier.testTag("position.confirm")) { Text(stringResource(label)) }
    if (state.review != null && !unknown) TextButton(actions.back, enabled = !state.submitting) { Text(stringResource(R.string.order_back)) }
}

@Composable
private fun PositionFields(draft: PositionActionDraft, edit: (PositionActionDraft) -> Unit, enabled: Boolean) {
    when (draft.kind) {
        PositionActionKind.CLOSE -> Text(stringResource(R.string.positions_reduce_footer))
        PositionActionKind.REDUCE -> {
            Text(stringResource(R.string.positions_reduce_footer))
            Row { listOf("25", "50", "75", "100").forEach { value ->
                TextButton({ edit(draft.copy(percent = value)) }, enabled = enabled) { Text("$value%") }
            } }
            PositionInput(R.string.positions_reduce_sizelabel, draft.percent, enabled) { edit(draft.copy(percent = it)) }
        }
        PositionActionKind.BRACKET -> {
            PositionInput(R.string.trading_bracket_stoplosslabel, draft.stopLoss, enabled) { edit(draft.copy(stopLoss = it)) }
            PositionInput(R.string.trading_bracket_takeprofitlabel, draft.takeProfit, enabled) { edit(draft.copy(takeProfit = it)) }
        }
        PositionActionKind.TRAILING -> {
            PositionInput(R.string.trading_trailingstop_trailamountlabel, draft.distance, enabled) { edit(draft.copy(distance = it)) }
            PositionInput(R.string.position_action_minimum_lock, draft.minimumLock, enabled) { edit(draft.copy(minimumLock = it)) }
        }
    }
}

@Composable
private fun PositionInput(label: Int, value: String, enabled: Boolean, update: (String) -> Unit) {
    OutlinedTextField(value, update, enabled = enabled, singleLine = true, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("position.field.$label"))
}

@Composable
private fun PositionIntentDetails(intent: PositionActionIntent) {
    when (intent) {
        is PositionActionIntent.Order -> {
            Text(stringResource(if (intent.payload.side == "buy") R.string.order_buy else R.string.order_sell))
            Text(stringResource(R.string.order_market) + " · " + stringResource(R.string.order_reduce_only))
            Text(stringResource(R.string.order_quantity) + ": " + intent.payload.quantity.display())
        }
        is PositionActionIntent.Bracket -> {
            intent.payload.stopLoss?.let { Text(stringResource(R.string.trading_bracket_stoplosslabel) + ": " + it.display()) }
            intent.payload.takeProfit?.let { Text(stringResource(R.string.trading_bracket_takeprofitlabel) + ": " + it.display()) }
        }
        is PositionActionIntent.Trailing -> {
            Text(stringResource(R.string.trading_trailingstop_trailamountlabel) + ": " + intent.payload.distance.display() + "%")
            Text(stringResource(R.string.position_action_minimum_lock) + ": " + intent.payload.minimumLock.display())
        }
    }
}
