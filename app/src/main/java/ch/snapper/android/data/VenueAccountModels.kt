@file:UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

fun interface VenueAccountsClient {
    suspend fun venueAccounts(walletPublicId: String?): List<VenueAccountDto>
}

@Serializable
data class VenueBalanceDto(
    val currency: String,
    val total: BigDecimal,
    val free: BigDecimal? = null,
    val used: BigDecimal? = null,
    @SerialName("total_decimal") val totalDecimal: String? = null,
    @SerialName("free_decimal") val freeDecimal: String? = null,
    @SerialName("used_decimal") val usedDecimal: String? = null,
    @SerialName("numeric_provenance") val numericProvenance: String? = null,
)

@Serializable
data class VenuePositionDto(
    val symbol: String, val side: String, val size: BigDecimal,
    @SerialName("entry_price") val entryPrice: BigDecimal,
    @SerialName("mark_price") val markPrice: BigDecimal,
    @SerialName("unrealized_pnl") val unrealizedPnl: BigDecimal,
    @SerialName("unrealized_funding") val unrealizedFunding: BigDecimal,
    val timestamp: String,
)

@Serializable
data class VenueDriftEpisodeDto(
    @SerialName("public_id") val publicId: String,
    val status: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("trigger_observation_id") val triggerObservationId: Long,
    @SerialName("last_observation_id") val lastObservationId: Long,
    @SerialName("details_source_observation_id") val detailsSourceObservationId: Long,
    @SerialName("latest_full_mismatch_count") val latestFullMismatchCount: Int,
)

@Serializable
data class VenueReconciliationDto(
    @SerialName("effective_status") val effectiveStatus: String,
    @SerialName("is_authoritative") val isAuthoritative: Boolean,
    val method: String? = null,
    @SerialName("evaluation_status") val evaluationStatus: String? = null,
    @SerialName("evaluated_at") val evaluatedAt: String? = null,
    @SerialName("authoritative_until") val authoritativeUntil: String? = null,
    @SerialName("last_full_outcome") val lastFullOutcome: String? = null,
    @SerialName("consecutive_full_mismatches") val consecutiveFullMismatches: Int = 0,
    @SerialName("current_observation_id") val currentObservationId: Long? = null,
    @SerialName("last_full_observation_id") val lastFullObservationId: Long? = null,
    @SerialName("detail_source_observation_id") val detailSourceObservationId: Long? = null,
    @SerialName("anchor_public_id") val anchorPublicId: String? = null,
    @SerialName("venue_account_state_public_id") val venueAccountStatePublicId: String? = null,
    @SerialName("venue_account_observation_id") val venueAccountObservationId: Long? = null,
    @SerialName("source_watermark_kind") val sourceWatermarkKind: String? = null,
    @SerialName("source_watermark") val sourceWatermark: Long? = null,
    @SerialName("reconciled_at") val reconciledAt: String? = null,
    @SerialName("open_drift_episode") val openDriftEpisode: VenueDriftEpisodeDto? = null,
    val expected: JsonObject? = null, val actual: JsonObject? = null,
    val difference: JsonObject? = null, val tolerance: JsonObject? = null,
    val error: String? = null,
)

@Serializable
data class VenueAccountDto(
    @SerialName("public_id") val publicId: String,
    @SerialName("wallet_public_id") val walletPublicId: String = "",
    val exchange: String, val mode: String = "live",
    @SerialName("sync_status") val syncStatus: String,
    @SerialName("effective_status") val effectiveStatus: String,
    @SerialName("is_authoritative") val isAuthoritative: Boolean,
    @SerialName("balance_status") val balanceStatus: String,
    @SerialName("position_status") val positionStatus: String,
    @SerialName("valuation_status") val valuationStatus: String,
    val balances: List<VenueBalanceDto>? = null,
    @SerialName("open_positions") val openPositions: List<VenuePositionDto>? = null,
    @SerialName("balance_observed_at") val balanceObservedAt: String? = null,
    @SerialName("position_observed_at") val positionObservedAt: String? = null,
    @SerialName("authoritative_until") val authoritativeUntil: String? = null,
    @SerialName("current_attempt_observation_id") val currentAttemptObservationId: Long? = null,
    @SerialName("balance_payload_source_observation_id") val balancePayloadSourceObservationId: Long? = null,
    @SerialName("position_payload_source_observation_id") val positionPayloadSourceObservationId: Long? = null,
    val error: String? = null,
    val reconciliation: VenueReconciliationDto,
)
