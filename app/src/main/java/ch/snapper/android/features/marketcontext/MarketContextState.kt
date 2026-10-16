package ch.snapper.android.features.marketcontext

import ch.snapper.android.data.*
import java.math.BigDecimal

data class MarketContextSelection(val exchange: String, val symbol: String)
data class MarketMetrics(
    val lastPrice: BigDecimal? = null, val changePercent: BigDecimal? = null,
    val high: BigDecimal? = null, val low: BigDecimal? = null,
    val asOfMillis: Long? = null,
)
data class MarketContextState(
    val selection: MarketContextSelection? = null,
    val permitted: Boolean = false,
    val metricsLoading: Boolean = false, val relatedLoading: Boolean = false,
    val metricsFailed: Boolean = false, val relatedFailed: Boolean = false,
    val metrics: MarketMetrics? = null,
    val cache: MarketCachedCandlesDto? = null,
    val related: RelatedMarketPayloadDto? = null,
)
data class MarketContextActions(
    val retryMetrics: () -> Unit,
    val retryRelated: () -> Unit,
    val selectRelated: (RelatedMarketInstrumentDto) -> Unit,
)
