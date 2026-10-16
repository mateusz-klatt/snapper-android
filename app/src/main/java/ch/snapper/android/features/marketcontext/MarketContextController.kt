package ch.snapper.android.features.marketcontext

import ch.snapper.android.data.*
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-dispatcher confined. Owner must reset on session/backend/desk/delegate/permission changes. */
class MarketContextController(
    private val scope: CoroutineScope,
    private val client: MarketContextClient,
    private val onSelectMarket: (String, String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var epoch = 0L
    private var metricsJob: Job? = null
    private var relatedJob: Job? = null
    private val mutableState = MutableStateFlow(MarketContextState())
    val state = mutableState.asStateFlow()
    val actions = MarketContextActions(::loadMetrics, ::loadRelated, ::selectRelated)

    fun reset(selection: MarketContextSelection?, permitted: Boolean) {
        epoch++
        metricsJob?.cancel()
        relatedJob?.cancel()
        val valid = selection?.takeIf { validSelection(it) }
        mutableState.value = MarketContextState(selection = valid, permitted = permitted)
        loadMetrics()
        loadRelated()
    }

    fun invalidate() = reset(null, false)

    private fun loadMetrics() {
        val current = mutableState.value
        val selection = current.selection ?: return
        if (!current.permitted || current.metricsLoading) return
        val token = epoch
        mutableState.value = current.copy(metricsLoading = true, metricsFailed = false)
        metricsJob = scope.launch {
            try {
                val result = client.cachedHourly(selection.exchange, selection.symbol)
                check(result.sampleCount >= 0 && result.source in setOf("cache", "derived", "db"))
                val metrics = MarketMetricsCalculator.calculate(result.candles, clock.millis())
                if (token == epoch) mutableState.value = mutableState.value.copy(
                    metricsLoading = false, metrics = metrics, cache = result)
            } catch (cancelled: CancellationException) {
                if (token == epoch) mutableState.value = mutableState.value.copy(metricsLoading = false)
                throw cancelled
            } catch (_: Exception) {
                if (token == epoch) mutableState.value = mutableState.value.copy(
                    metricsLoading = false, metricsFailed = true, metrics = null, cache = null)
            }
        }
    }

    private fun loadRelated() {
        val current = mutableState.value
        val selection = current.selection ?: return
        if (!current.permitted || current.relatedLoading) return
        val token = epoch
        mutableState.value = current.copy(relatedLoading = true, relatedFailed = false)
        relatedJob = scope.launch {
            try {
                val result = client.related(selection.exchange, selection.symbol)
                check(result.selected.exchange == selection.exchange && result.selected.nativeSymbol == selection.symbol)
                if (token == epoch) mutableState.value = mutableState.value.copy(relatedLoading = false, related = result)
            } catch (cancelled: CancellationException) {
                if (token == epoch) mutableState.value = mutableState.value.copy(relatedLoading = false)
                throw cancelled
            } catch (_: Exception) {
                if (token == epoch) mutableState.value = mutableState.value.copy(relatedLoading = false, relatedFailed = true, related = null)
            }
        }
    }

    private fun selectRelated(item: RelatedMarketInstrumentDto) {
        val current = mutableState.value
        if (!current.permitted || current.relatedLoading || item.isSelected) return
        if (current.related?.groups?.none { item in it.items } != false) return
        val selection = MarketContextSelection(item.exchange, item.nativeSymbol)
        if (item.instrumentPublicId.isBlank() || !validSelection(selection) || selection == current.selection) return
        onSelectMarket(selection.exchange, selection.symbol)
    }

    private fun validSelection(selection: MarketContextSelection): Boolean =
        selection.exchange.isNotBlank() && selection.exchange.length <= 128 &&
            selection.symbol.isNotBlank() && selection.symbol.length <= 256
}
