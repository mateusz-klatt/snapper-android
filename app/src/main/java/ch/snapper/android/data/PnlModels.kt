@file:UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

fun interface PnlClient {
    suspend fun pnlSeries(query: PnlSeriesQuery): PnlSeriesDto
}

data class PnlSeriesQuery(
    val walletPublicId: String,
    val mode: String,
    val granularity: String,
    val from: Instant,
    val to: Instant,
    val valuationCcy: String,
) {
    init {
        require(walletPublicId.isNotBlank())
        require(mode in setOf("paper", "live"))
        require(granularity in setOf("1m", "5m", "1h", "1d"))
        require(valuationCcy in setOf("USD", "PLN", "EUR"))
        require(from < to)
    }
}

@Serializable
data class PnlIncompletenessReasonDto(
    val reason: String,
    val withholding_tier: String,
    val withholding_scope: String,
    val trigger_instrument_public_id: String?,
)

@Serializable
data class PnlInstrumentContributionDto(
    val instrument_public_id: String,
    val native_symbol: String?,
    val exchange: String?,
    val realized_pnl: BigDecimal?,
    val fee_pnl: BigDecimal?,
    val accrual_pnl: BigDecimal?,
    val unrealized_pnl: BigDecimal?,
)

@Serializable
data class PnlAttributionContributionDto(
    val origin: String,
    val strategy_name: String?,
    val realized_pnl: BigDecimal?,
    val fee_pnl: BigDecimal?,
    val accrual_pnl: BigDecimal?,
    val unrealized_pnl: BigDecimal?,
)

@Serializable
data class PnlTimelinePointDto(
    val point_time: String,
    val realized_pnl: BigDecimal?,
    val fee_pnl: BigDecimal?,
    val accrual_pnl: BigDecimal?,
    val unrealized_pnl: BigDecimal?,
    val net_pnl: BigDecimal?,
    val equity: BigDecimal?,
    val cash: BigDecimal?,
    val position_value: BigDecimal?,
    val drawdown: BigDecimal?,
    val valuation_status: String,
    val incompleteness_reasons: List<PnlIncompletenessReasonDto>,
    val per_instrument: List<PnlInstrumentContributionDto>,
    val attribution: List<PnlAttributionContributionDto>,
)

@Serializable
data class PnlFxRateSourceDto(
    val source_currency: String,
    val valuation_currency: String,
    val base_currency: String,
    val quote_currency: String,
    val exchange: String,
)

@Serializable
data class PnlEquityCoverageDto(
    val sampled: Boolean,
    val venue_scope: String?,
    val external_flows_adjusted: Boolean?,
    val complete_minutes: Long,
    val first_minute: String?,
    val last_minute: String?,
    val sample_calc_version: String?,
    val valuation_basis: String?,
    val converted_from: String?,
    val conversion_rate_source: String?,
    val conversion_withheld_minutes: Long,
    val drawdown_withheld_reason: String?,
)

@Serializable
data class PnlExecutionCorrectionDto(
    val correction_public_id: String,
    val target_execution_public_id: String,
    val exchange: String,
    val scope_sequence: Long,
    val reason: String,
    val correction_time: String,
)

@Serializable
data class PnlExecutionHistoryDto(
    val status: String,
    val corrections: List<PnlExecutionCorrectionDto>,
)

@Serializable
data class PnlSeriesDto(
    val wallet_public_id: String,
    val mode: String,
    val granularity: String,
    val valuation_ccy: String,
    val from_time: String,
    val to_time: String,
    val as_of: String,
    val mark_source: String,
    val rate_sources: List<PnlFxRateSourceDto>,
    val calc_version: String,
    val equity_coverage: PnlEquityCoverageDto,
    val execution_history: PnlExecutionHistoryDto,
    val points: List<PnlTimelinePointDto>,
)
