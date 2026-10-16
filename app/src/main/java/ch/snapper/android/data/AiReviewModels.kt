package ch.snapper.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Reads return the items member, not the usual payload envelope. Writes use ai_review_decision_command. */
interface AiReviewsClient {
    suspend fun auditReviews(): List<AiAuditReviewDto>
    suspend fun pendingReviews(): List<AiPendingReviewDto>
    suspend fun submitDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse
    /** Explicit retry of the controller's frozen uncertain intent; never an automatic transport replay. */
    suspend fun retryDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse =
        submitDecision(scope, reviewId, payload)
}

@Serializable
data class AiReviewListResponse<T>(val items: List<T>, val count: Int)

@Serializable
data class AiDecisionPayload(val decision: String, val rationale: String? = null)

/** For non-2xx replies transport decodes this same shape from detail, without retrying POST. */
@Serializable
data class AiDecisionResponse(
    val success: Boolean,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String,
    val details: JsonObject,
)

@Serializable
data class AiPendingReviewDto(
    @SerialName("review_public_id") val reviewPublicId: String,
    @SerialName("selected_delegate_public_id") val selectedDelegatePublicId: String,
    @SerialName("wallet_public_id") val walletPublicId: String,
    @SerialName("dispatch_version") val dispatchVersion: Int,
    val status: String, val deadline: String,
    @SerialName("fanout_after") val fanoutAfter: String,
    val instrument: String? = null,
    @SerialName("signal_envelope") val signalEnvelope: JsonObject? = null,
)

@Serializable
data class AiAuditReviewDto(
    @SerialName("review_public_id") val reviewPublicId: String,
    @SerialName("strategy_public_id") val strategyPublicId: String,
    @SerialName("user_public_id") val userPublicId: String,
    @SerialName("operator_public_id") val operatorPublicId: String,
    @SerialName("wallet_public_id") val walletPublicId: String,
    @SerialName("instrument_public_id") val instrumentPublicId: String,
    @SerialName("selected_delegate_public_id") val selectedDelegatePublicId: String,
    @SerialName("responding_delegate_public_id") val respondingDelegatePublicId: String?,
    val status: String, val decision: String?, val rationale: String?,
    @SerialName("resolution_mode") val resolutionMode: String?,
    @SerialName("dispatch_version") val dispatchVersion: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("resolved_at") val resolvedAt: String?,
    val deadline: String,
    @SerialName("signal_envelope") val signalEnvelope: JsonObject? = null,
)

class AiDecisionPreparationException(cause: Throwable) : java.io.IOException("AI review decision preparation failed.", cause)
