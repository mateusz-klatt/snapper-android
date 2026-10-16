@file:kotlinx.serialization.UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Payloads only; session transport must freshly authorize read:market_data, without requiring a wallet. */
interface MarketDiagnosticsClient {
    /** GET /api/market/cache/stats/configured */
    suspend fun configuredPairStats(): ConfiguredPairStatsDto
    /** GET /api/candles/cache?exchange=…&instrument=…&timeframe=…&limit=… */
    suspend fun chartCache(exchange: String, instrument: String, timeframe: String, limit: Int): MarketCachedCandlesDto
}

@Serializable
data class ConfiguredPairStatsDto(val count: Int, val pairs: List<MarketPairStatsDto>)

@Serializable
data class MarketPairStatsDto(
    val left: String, val right: String,
    @SerialName("pearson_r") val pearsonR: BigDecimal?,
    @SerialName("pearson_n") val pearsonN: Int,
    @SerialName("coint_t") val cointT: BigDecimal?,
    @SerialName("coint_pvalue") val cointPvalue: BigDecimal?,
    @SerialName("coint_critical_values") val cointCriticalValues: List<BigDecimal>?,
    @SerialName("computed_at") val computedAt: String?,
    @SerialName("sample_count") val sampleCount: Int,
    @SerialName("is_warm") val isWarm: Boolean,
)
