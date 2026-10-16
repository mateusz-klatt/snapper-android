package ch.snapper.android.features.marketdiagnostics

import ch.snapper.android.data.MarketDiagnosticsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI dispatcher confined. Reset for full identity/backend changes; select independently for chart navigation. */
class MarketDiagnosticsController(
    private val scope: CoroutineScope,
    private val client: MarketDiagnosticsClient,
    private val onPeer: (String, String) -> Unit,
) {
    private var epoch = 0L
    private var selectionRevision = 0L
    private var pairsAttempted = false
    private var pairsJob: Job? = null
    private var cacheJob: Job? = null
    private val mutable = MutableStateFlow(MarketDiagnosticsState())
    val state = mutable.asStateFlow()
    val actions = MarketDiagnosticsActions(::loadPairs, ::refreshChartCache, ::selectPeer)

    fun reset(value: MarketDiagnosticsContext?) {
        epoch++; selectionRevision++
        pairsJob?.cancel(); cacheJob?.cancel()
        pairsAttempted = false
        mutable.value = MarketDiagnosticsState(permitted = value?.scope?.effectivePermissions?.contains("read:market_data") == true)
    }

    fun invalidate() = reset(null)

    fun select(value: MarketDiagnosticsSelection?, probeCache: Boolean = true) {
        if (value == state.value.selection) return
        selectionRevision++
        cacheJob?.cancel()
        val valid = value?.takeIf { it.exchange.isNotBlank() && it.instrument.isNotBlank() &&
            it.exchange.length <= 128 && it.instrument.length <= 256 &&
            it.timeframe in setOf("1m", "5m", "15m", "1h", "4h", "1d") }
        mutable.value = state.value.copy(selection = valid, cache = null, cacheLoading = false, cacheFailed = false)
        if (valid == null) return
        if (!pairsAttempted) loadPairs()
        if (probeCache) loadCache()
    }

    private fun loadPairs() {
        if (!state.value.permitted || state.value.selection == null || state.value.pairsLoading) return
        pairsAttempted = true
        val token = epoch
        mutable.value = state.value.copy(pairsLoading = true, pairsFailed = false)
        pairsJob = scope.launch {
            try {
                val result = client.configuredPairStats()
                check(result.count == result.pairs.size && result.pairs.all(MarketDiagnosticsLogic::valid))
                if (token == epoch) mutable.value = state.value.copy(pairs = result.pairs, pairsLoading = false, pairsLoaded = true)
            } catch (cancelled: CancellationException) {
                if (token == epoch) mutable.value = state.value.copy(pairsLoading = false)
                throw cancelled
            } catch (_: Exception) {
                if (token == epoch) mutable.value = state.value.copy(pairs = emptyList(), pairsLoading = false, pairsLoaded = false, pairsFailed = true)
            }
        }
    }

    /** Manual refresh/reconnection must probe again even when the selected chart is unchanged. */
    fun refreshChartCache() {
        if (!state.value.permitted || state.value.selection == null) return
        selectionRevision++
        cacheJob?.cancel()
        mutable.value = state.value.copy(cacheLoading = false)
        loadCache()
    }

    private fun loadCache() {
        val selected = state.value.selection ?: return
        if (!state.value.permitted || state.value.cacheLoading) return
        val token = epoch
        val revision = selectionRevision
        val limit = MarketDiagnosticsLogic.chartLimit(selected.timeframe)
        mutable.value = state.value.copy(cacheLoading = true, cacheFailed = false)
        cacheJob = scope.launch {
            try {
                val result = client.chartCache(selected.exchange, selected.instrument, selected.timeframe, limit)
                check(result.sampleCount == result.candles.size && result.sampleCount <= limit)
                check(result.isWarm == (result.sampleCount >= limit))
                check(result.source == MarketDiagnosticsLogic.chartSource(selected.timeframe))
                check(result.candles.all { it.timeframe == selected.timeframe })
                val cache = ChartCacheStatus(result.sampleCount, limit, result.source, result.isWarm)
                if (token == epoch && revision == selectionRevision) mutable.value = state.value.copy(cache = cache, cacheLoading = false)
            } catch (cancelled: CancellationException) {
                if (token == epoch && revision == selectionRevision) mutable.value = state.value.copy(cacheLoading = false)
                throw cancelled
            } catch (_: Exception) {
                if (token == epoch && revision == selectionRevision) mutable.value = state.value.copy(cache = null, cacheLoading = false, cacheFailed = true)
            }
        }
    }

    private fun selectPeer(chip: MarketPairChip) {
        if (!state.value.permitted || state.value.pairsLoading || chip !in state.value.chips) return
        onPeer(chip.exchange, chip.instrument)
    }
}
