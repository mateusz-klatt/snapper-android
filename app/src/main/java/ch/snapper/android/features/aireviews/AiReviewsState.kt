package ch.snapper.android.features.aireviews

import ch.snapper.android.data.*
import java.time.Instant
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class AiReviewIdentity(val scope: OrderScope, val delegatePublicId: String?) {
    val canAudit: Boolean get() = "read:ai_reviews" in scope.effectivePermissions
    val canPending: Boolean get() = !delegatePublicId.isNullOrBlank() && "read:signals" in scope.effectivePermissions
    val canDecide: Boolean get() = canPending && "submit:ai_review_decision" in scope.effectivePermissions
}

enum class AiDecisionOutcome { ACCEPTED, PEER_RESOLVED, EXPIRED, MISSING, DENIED, REJECTED, PREPARATION, UNKNOWN }
data class AiDecisionResult(val outcome: AiDecisionOutcome, val message: String? = null, val decision: String? = null)
data class AiDecisionIntent(val reviewId: String, val payload: AiDecisionPayload)
data class AiReviewsState(
    val identity: AiReviewIdentity? = null,
    val audit: List<AiAuditReviewDto> = emptyList(), val pending: List<AiPendingReviewDto> = emptyList(),
    val auditLoading: Boolean = false, val pendingLoading: Boolean = false,
    val auditFailed: Boolean = false, val pendingFailed: Boolean = false,
    val results: Map<String, AiDecisionResult> = emptyMap(), val inFlight: Set<String> = emptySet(),
    val retryable: Set<String> = emptySet(),
    val confirmation: AiDecisionIntent? = null, val now: Instant = Instant.EPOCH,
) {
    fun canDecide(row: AiPendingReviewDto): Boolean = identity?.canDecide == true &&
        row.status in setOf("pending", "fanout_dispatched") && !aiReviewExpired(row.deadline, now) &&
        row.reviewPublicId !in inFlight && row.reviewPublicId !in results

    fun canConfirm(intent: AiDecisionIntent): Boolean {
        if (identity?.canDecide != true || intent.reviewId in inFlight) return false
        if (results[intent.reviewId]?.outcome == AiDecisionOutcome.UNKNOWN) return intent.reviewId in retryable
        return pending.find { it.reviewPublicId == intent.reviewId }?.let(::canDecide) == true
    }

}

fun aiReviewExpired(deadline: String, now: Instant): Boolean =
    runCatching { Instant.parse(deadline) <= now }.getOrDefault(true)

fun aiReviewVerdict(row: AiAuditReviewDto): String = when (row.status.lowercase()) {
    "resolved_approved" -> "approve"
    "resolved_rejected" -> "reject"
    "timeout" -> "expired"
    "superseded" -> "superseded"
    "pending", "fanout_dispatched" -> "pending"
    else -> row.decision?.takeIf { it == "approve" || it == "reject" } ?: "unknown"
}

fun aiDecisionOutcome(response: AiDecisionResponse, intent: AiDecisionPayload): AiDecisionOutcome {
    if (response.errorCode == "decision_already_recorded") {
        val recorded = runCatching { response.details["decision"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return if (response.success && recorded == intent.decision) AiDecisionOutcome.ACCEPTED else AiDecisionOutcome.UNKNOWN
    }
    if (response.success && response.errorCode == null) return AiDecisionOutcome.ACCEPTED
    return when (response.errorCode) {
        "review_already_resolved_by_peer" -> AiDecisionOutcome.PEER_RESOLVED
        "review_id_expired" -> AiDecisionOutcome.EXPIRED
        "review_not_found" -> AiDecisionOutcome.MISSING
        "not_authorized", "not_a_delegate", "not_selected_before_fanout" -> AiDecisionOutcome.DENIED
        "invalid_decision", "invalid_rationale" -> AiDecisionOutcome.REJECTED
        else -> AiDecisionOutcome.UNKNOWN
    }
}

data class AiReviewsActions(
    val refresh: () -> Unit = {},
    val prepare: (String, String, String) -> Unit = { _, _, _ -> },
    val confirm: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val retry: (String) -> Unit = {},
)
