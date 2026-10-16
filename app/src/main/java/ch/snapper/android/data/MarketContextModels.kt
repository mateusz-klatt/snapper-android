@file:kotlinx.serialization.UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Both methods return the API envelope payload, after authenticated read:market_data preflight. */
interface MarketContextClient {
    /** GET /api/candles/cache?exchange=…&instrument=…&timeframe=1h&limit=25 */
    suspend fun cachedHourly(exchange: String, symbol: String): MarketCachedCandlesDto
    /** GET /api/instruments/{exchange}/{native_symbol}/related, encoded path segments. */
    suspend fun related(exchange: String, symbol: String): RelatedMarketPayloadDto
}

@Serializable
data class MarketCachedCandleDto(
    @SerialName("open_at_ms") val openAtMs: Long,
    val timeframe: String,
    val open: BigDecimal, val high: BigDecimal, val low: BigDecimal,
    val close: BigDecimal, val volume: BigDecimal,
)

@Serializable
data class MarketCachedCandlesDto(
    val candles: List<MarketCachedCandleDto>,
    @SerialName("sample_count") val sampleCount: Int,
    @SerialName("is_warm") val isWarm: Boolean,
    val source: String,
)

@Serializable
data class RelatedMarketSelectionDto(val exchange: String, @SerialName("native_symbol") val nativeSymbol: String)

@Serializable
data class RelatedMarketUnderlyingDto(
    @SerialName("public_id") val publicId: String,
    val ticker: String, val name: String,
    @SerialName("asset_class") val assetClass: String,
    val sector: String? = null, val description: String? = null,
)

@Serializable
data class RelatedMarketInstrumentDto(
    @SerialName("instrument_public_id") val instrumentPublicId: String,
    @SerialName("native_symbol") val nativeSymbol: String,
    val exchange: String,
    @SerialName("asset_type") val assetType: String,
    @SerialName("relationship_type") val relationshipType: String,
    @SerialName("contract_family") val contractFamily: String? = null,
    @SerialName("is_selected") val isSelected: Boolean,
)

@Serializable
data class RelatedMarketGroupDto(
    @SerialName("relationship_type") val relationshipType: String,
    val label: String, val items: List<RelatedMarketInstrumentDto>,
)

@Serializable
data class RelatedMarketPayloadDto(
    val selected: RelatedMarketSelectionDto,
    val underlying: RelatedMarketUnderlyingDto? = null,
    val groups: List<RelatedMarketGroupDto>,
)
