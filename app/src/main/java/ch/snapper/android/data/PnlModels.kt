@file:UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.SerialName
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
    @SerialName("withholding_tier") val withholdingTier: String,
    @SerialName("withholding_scope") val withholdingScope: String,
    @SerialName("trigger_instrument_public_id") val triggerInstrumentPublicId: String?,
)

@Serializable
data class PnlInstrumentContributionDto(
    @SerialName("instrument_public_id") val instrumentPublicId: String,
    @SerialName("native_symbol") val nativeSymbol: String?,
    val exchange: String?,
    @SerialName("realized_pnl") val realizedPnl: BigDecimal?,
    @SerialName("fee_pnl") val feePnl: BigDecimal?,
    @SerialName("accrual_pnl") val accrualPnl: BigDecimal?,
    @SerialName("unrealized_pnl") val unrealizedPnl: BigDecimal?,
)

@Serializable
data class PnlAttributionContributionDto(
    val origin: String,
    @SerialName("strategy_name") val strategyName: String?,
    @SerialName("realized_pnl") val realizedPnl: BigDecimal?,
    @SerialName("fee_pnl") val feePnl: BigDecimal?,
    @SerialName("accrual_pnl") val accrualPnl: BigDecimal?,
    @SerialName("unrealized_pnl") val unrealizedPnl: BigDecimal?,
)

@Serializable
data class PnlTimelinePointDto(
    @SerialName("point_time") val pointTime: String,
    @SerialName("realized_pnl") val realizedPnl: BigDecimal?,
    @SerialName("fee_pnl") val feePnl: BigDecimal?,
    @SerialName("accrual_pnl") val accrualPnl: BigDecimal?,
    @SerialName("unrealized_pnl") val unrealizedPnl: BigDecimal?,
    @SerialName("net_pnl") val netPnl: BigDecimal?,
    val equity: BigDecimal?,
    val cash: BigDecimal?,
    @SerialName("position_value") val positionValue: BigDecimal?,
    val drawdown: BigDecimal?,
    @SerialName("valuation_status") val valuationStatus: String,
    @SerialName("incompleteness_reasons") val incompletenessReasons: List<PnlIncompletenessReasonDto>,
    @SerialName("per_instrument") val perInstrument: List<PnlInstrumentContributionDto>,
    val attribution: List<PnlAttributionContributionDto>,
)

@Serializable
data class PnlFxRateSourceDto(
    @SerialName("source_currency") val sourceCurrency: String,
    @SerialName("valuation_currency") val valuationCurrency: String,
    @SerialName("base_currency") val baseCurrency: String,
    @SerialName("quote_currency") val quoteCurrency: String,
    val exchange: String,
)

@Serializable
data class PnlEquityCoverageDto(
    val sampled: Boolean,
    @SerialName("venue_scope") val venueScope: String?,
    @SerialName("external_flows_adjusted") val externalFlowsAdjusted: Boolean?,
    @SerialName("complete_minutes") val completeMinutes: Long,
    @SerialName("first_minute") val firstMinute: String?,
    @SerialName("last_minute") val lastMinute: String?,
    @SerialName("sample_calc_version") val sampleCalcVersion: String?,
    @SerialName("valuation_basis") val valuationBasis: String?,
    @SerialName("converted_from") val convertedFrom: String?,
    @SerialName("conversion_rate_source") val conversionRateSource: String?,
    @SerialName("conversion_withheld_minutes") val conversionWithheldMinutes: Long,
    @SerialName("drawdown_withheld_reason") val drawdownWithheldReason: String?,
)

@Serializable
data class PnlExecutionCorrectionDto(
    @SerialName("correction_public_id") val correctionPublicId: String,
    @SerialName("target_execution_public_id") val targetExecutionPublicId: String,
    val exchange: String,
    @SerialName("scope_sequence") val scopeSequence: Long,
    val reason: String,
    @SerialName("correction_time") val correctionTime: String,
)

@Serializable
data class PnlExecutionHistoryDto(
    val status: String,
    val corrections: List<PnlExecutionCorrectionDto>,
)

@Serializable
data class PnlSeriesDto(
    @SerialName("wallet_public_id") val walletPublicId: String,
    val mode: String,
    val granularity: String,
    @SerialName("valuation_ccy") val valuationCcy: String,
    @SerialName("from_time") val fromTime: String,
    @SerialName("to_time") val toTime: String,
    @SerialName("as_of") val asOf: String,
    @SerialName("mark_source") val markSource: String,
    @SerialName("rate_sources") val rateSources: List<PnlFxRateSourceDto>,
    @SerialName("calc_version") val calcVersion: String,
    @SerialName("equity_coverage") val equityCoverage: PnlEquityCoverageDto,
    @SerialName("execution_history") val executionHistory: PnlExecutionHistoryDto,
    val points: List<PnlTimelinePointDto>,
)
