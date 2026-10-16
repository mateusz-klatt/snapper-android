package ch.snapper.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ch.snapper.android.R
import ch.snapper.android.data.AiAuditReviewDto
import ch.snapper.android.data.AiPendingReviewDto
import ch.snapper.android.features.aireviews.*

internal fun LazyListScope.aiReviewsScreen(state: AiReviewsState, actions: AiReviewsActions) {
    item { TextButton(onClick = actions.refresh) { Text(stringResource(R.string.refresh)) } }
    pendingReviews(state, actions)
    items(state.results.entries.toList(), key = { "result.${it.key}" }) { (id, result) ->
        DecisionResult(id, result, state, actions)
    }
    auditReviews(state)
}

private fun LazyListScope.pendingReviews(state: AiReviewsState, actions: AiReviewsActions) {
    if (state.identity?.canPending != true) return
    item {
        Text(stringResource(R.string.ai_reviews_pending_title), style = MaterialTheme.typography.titleLarge)
        FeedStatus(state.pendingLoading, state.pendingFailed, state.pending.isEmpty())
    }
    items(state.pending, key = { "pending.${it.reviewPublicId}" }) { row -> PendingReview(row, state, actions) }
}

private fun LazyListScope.auditReviews(state: AiReviewsState) {
    if (state.identity?.canAudit != true) return
    item {
        Text(stringResource(R.string.ai_reviews_decisions_title), style = MaterialTheme.typography.titleLarge)
        FeedStatus(state.auditLoading, state.auditFailed, state.audit.isEmpty())
    }
    items(state.audit, key = { "audit.${it.reviewPublicId}" }) { row -> AuditReview(row) }
}

@Composable
private fun FeedStatus(loading: Boolean, failed: Boolean, empty: Boolean) {
    if (loading) LinearProgressIndicator()
    if (failed) Text(stringResource(R.string.ai_reviews_load_failed))
    if (empty && !loading && !failed) Text(stringResource(R.string.empty))
}

@Composable
private fun PendingReview(row: AiPendingReviewDto, state: AiReviewsState, actions: AiReviewsActions) {
    RecordCard(row.instrument ?: row.reviewPublicId) {
        Text(row.reviewPublicId)
        Metric(R.string.status, row.status)
        Metric(R.string.ai_reviews_deadline, row.deadline)
        if (aiReviewExpired(row.deadline, state.now)) Text(stringResource(R.string.ai_reviews_expired))
        if (state.identity?.canDecide == true) DecisionControls(row, state, actions)
    }
}

@Composable
private fun DecisionControls(row: AiPendingReviewDto, state: AiReviewsState, actions: AiReviewsActions) {
    var rationale by remember(row.reviewPublicId) { mutableStateOf("") }
    val enabled = state.canDecide(row)
    OutlinedTextField(value = rationale, onValueChange = { rationale = it }, enabled = enabled,
        label = { Text(stringResource(R.string.ai_reviews_rationale)) }, modifier = Modifier.testTag("ai.rationale.${row.reviewPublicId}"))
    val validLength = rationale.codePointCount(0, rationale.length) <= 4096
    if (!validLength) Text(stringResource(R.string.ai_reviews_rationale_long, 4096))
    Row {
        listOf("approve", "reject").forEach { decision ->
            OutlinedButton(onClick = { actions.prepare(row.reviewPublicId, decision, rationale) }, enabled = enabled && validLength,
                modifier = Modifier.testTag("ai.$decision.${row.reviewPublicId}")) {
                Text(stringResource(decisionLabel(decision)))
            }
        }
    }
}

@Composable
private fun AuditReview(row: AiAuditReviewDto) {
    RecordCard(row.instrumentPublicId) {
        Text(row.reviewPublicId)
        Metric(R.string.status, stringResource(decisionLabel(aiReviewVerdict(row))))
        row.rationale?.let { Metric(R.string.ai_reviews_rationale, it) }
        row.resolvedAt?.let { Metric(R.string.ai_reviews_decided, it) }
        Metric(R.string.ai_reviews_deadline, row.deadline)
        Text(row.strategyPublicId)
        Text(row.respondingDelegatePublicId ?: row.selectedDelegatePublicId)
    }
}

@Composable
private fun DecisionResult(id: String, result: AiDecisionResult, state: AiReviewsState, actions: AiReviewsActions) {
    RecordCard(id) {
        Text(stringResource(outcomeLabel(result.outcome)), modifier = Modifier.testTag("ai.result.$id"))
        if (result.outcome == AiDecisionOutcome.ACCEPTED) result.decision?.let { Text(stringResource(decisionLabel(it))) }
        result.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
        if (result.outcome == AiDecisionOutcome.UNKNOWN) {
            TextButton(onClick = actions.refresh) { Text(stringResource(R.string.ai_reviews_check_status)) }
            OutlinedButton(onClick = { actions.retry(id) }, enabled = id in state.retryable && id !in state.inFlight,
                modifier = Modifier.testTag("ai.retry.$id")) { Text(stringResource(R.string.ai_reviews_retry)) }
        }
    }
}

@Composable
internal fun AiReviewConfirmation(state: AiReviewsState, actions: AiReviewsActions) {
    val intent = state.confirmation ?: return
    val enabled = state.canConfirm(intent)
    AlertDialog(onDismissRequest = actions.dismiss,
        title = { Text(stringResource(R.string.ai_reviews_confirm)) },
        text = { Column {
            Text(intent.reviewId)
            Text(stringResource(decisionLabel(intent.payload.decision)))
            Text(intent.payload.rationale ?: "—")
            if (intent.payload.decision == "reject") Text(stringResource(R.string.ai_reviews_reject_message))
        } },
        confirmButton = { TextButton(onClick = actions.confirm, enabled = enabled, modifier = Modifier.testTag("ai.confirm")) {
            Text(stringResource(decisionLabel(intent.payload.decision)))
        } },
        dismissButton = { TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.ai_reviews_cancel)) } })
}

private fun decisionLabel(decision: String): Int = when (decision) {
    "approve" -> R.string.ai_reviews_approve
    "reject" -> R.string.ai_reviews_reject
    "pending" -> R.string.ai_reviews_pending
    "expired" -> R.string.ai_reviews_expired
    "superseded" -> R.string.ai_reviews_superseded
    else -> R.string.ai_reviews_unknown
}

private fun outcomeLabel(outcome: AiDecisionOutcome): Int = when (outcome) {
    AiDecisionOutcome.ACCEPTED -> R.string.ai_reviews_accepted
    AiDecisionOutcome.PEER_RESOLVED -> R.string.ai_reviews_peer_resolved
    AiDecisionOutcome.EXPIRED -> R.string.ai_reviews_expired_message
    AiDecisionOutcome.MISSING -> R.string.ai_reviews_missing
    AiDecisionOutcome.DENIED -> R.string.ai_reviews_denied
    AiDecisionOutcome.REJECTED -> R.string.ai_reviews_rejected
    AiDecisionOutcome.PREPARATION -> R.string.error_service
    AiDecisionOutcome.UNKNOWN -> R.string.ai_reviews_unknown
}
