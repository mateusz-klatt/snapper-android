package ch.snapper.android.features.marketdiagnostics

import ch.snapper.android.data.*
import java.math.BigDecimal

/** Session generation must change for a new login/client, even at the same origin and user. */
data class MarketDiagnosticsContext(val origin: String, val sessionGeneration: Long, val scope: OrderScope, val primary: String?, val delegate: String?)
data class MarketDiagnosticsSelection(val exchange: String, val instrument: String, val timeframe: String)
data class ChartCacheStatus(val sampleCount: Int, val expected: Int, val source: String, val isWarm: Boolean)
enum class PairStatus { COLD, COINTEGRATED, WARM }
data class MarketPairChip(val exchange: String, val instrument: String, val pearsonR: BigDecimal?, val pvalue: BigDecimal?, val isWarm: Boolean) {
    val status: PairStatus get() = when {
        !isWarm -> PairStatus.COLD
        pvalue != null && pvalue < BigDecimal("0.05") -> PairStatus.COINTEGRATED
        else -> PairStatus.WARM
    }
}
data class MarketDiagnosticsState(
    val permitted: Boolean = false,
    val selection: MarketDiagnosticsSelection? = null,
    val pairs: List<MarketPairStatsDto> = emptyList(),
    val pairsLoading: Boolean = false, val pairsLoaded: Boolean = false, val pairsFailed: Boolean = false,
    val cache: ChartCacheStatus? = null, val cacheLoading: Boolean = false, val cacheFailed: Boolean = false,
) {
    val chips: List<MarketPairChip> get() = selection?.let { MarketDiagnosticsLogic.chips(pairs, it.exchange, it.instrument) }.orEmpty()
}
data class MarketDiagnosticsActions(val retryPairs: () -> Unit, val retryCache: () -> Unit, val selectPeer: (MarketPairChip) -> Unit)
