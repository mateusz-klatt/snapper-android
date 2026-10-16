package ch.snapper.android.features.marketdiagnostics

import ch.snapper.android.data.*
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketDiagnosticsTest {
    private val context = MarketDiagnosticsContext("https://demo.example", 1,
        OrderScope("u", "reader", null, setOf("read:market_data")), "desk", null)
    private val selection = MarketDiagnosticsSelection("venue", "BTC", "1m")
    private fun pair(peer: String, p: String? = null, r: String? = null, warm: Boolean = true) =
        MarketPairStatsDto("venue:BTC", peer, r?.let(::BigDecimal), 25, null, p?.let(::BigDecimal), null, null, 25, warm)
    private class Client : MarketDiagnosticsClient {
        var pairCalls = 0
        val cacheCalls = mutableListOf<Pair<String, Int>>()
        var pairs: suspend () -> ConfiguredPairStatsDto = { ConfiguredPairStatsDto(0, emptyList()) }
        var cache: suspend (String, String) -> MarketCachedCandlesDto = { _, timeframe -> MarketCachedCandlesDto(emptyList(), 0, false, MarketDiagnosticsLogic.chartSource(timeframe)) }
        override suspend fun configuredPairStats(): ConfiguredPairStatsDto { pairCalls++; return pairs() }
        override suspend fun chartCache(exchange: String, instrument: String, timeframe: String, limit: Int): MarketCachedCandlesDto {
            cacheCalls += instrument to limit
            return cache(instrument, timeframe)
        }
    }

    @Test fun exactWireDecimalsAndNullsPreserveStatisticalBoundary() {
        val row = Json.decodeFromString<MarketPairStatsDto>("""{"left":"venue:BTC","right":"other:ETH","pearson_r":-0.900000000000000001,"pearson_n":25,"coint_t":null,"coint_pvalue":0.050000000000000001,"coint_critical_values":null,"computed_at":null,"sample_count":25,"is_warm":true}""")
        assertEquals(BigDecimal("-0.900000000000000001"), row.pearsonR)
        assertEquals(PairStatus.WARM, MarketDiagnosticsLogic.chips(listOf(row), "venue", "BTC").single().status)
        assertTrue(MarketDiagnosticsLogic.valid(row))
        assertFalse(MarketDiagnosticsLogic.valid(row.copy(cointPvalue = BigDecimal("-0.1"))))
        assertFalse(MarketDiagnosticsLogic.valid(row.copy(cointCriticalValues = listOf(BigDecimal.ONE))))
    }

    @Test fun peerMatchingSortingNullsAndColdDominanceUseActualContract() {
        val rows = listOf(pair("other:NULL"), pair("other:WEAK", "0.01", "0.2"), pair("other:STRONG", "0.01", "-0.9"),
            pair("other:COLD", "0.001", "1", false), pair("bad"), pair("empty:"), pair("venue:BTC"),
            pair("other:UNRELATED").copy(left = "other:LEFT"))
        val chips = MarketDiagnosticsLogic.chips(rows, "venue", "BTC")
        assertEquals(listOf("COLD", "STRONG", "WEAK", "NULL"), chips.map { it.instrument })
        assertEquals(PairStatus.COLD, chips.first().status)
        assertEquals(PairStatus.COINTEGRATED, chips[1].status)
        assertNull(chips.last().pvalue)
        assertEquals(PairStatus.WARM, MarketDiagnosticsLogic.chips(listOf(pair("other:X", "0.05")), "venue", "BTC").single().status)
    }

    @Test fun timeframeChangesReusePairsAndFetchExactCacheProbeLimits() = runTest {
        val client = Client()
        val controller = MarketDiagnosticsController(this, client) { _, _ -> }
        controller.reset(context)
        val sources = mutableListOf<String?>()
        for (timeframe in listOf("1m", "5m", "15m", "1h", "4h", "1d")) {
            controller.select(selection.copy(timeframe = timeframe)); runCurrent()
            sources += controller.state.value.cache?.source
        }
        assertEquals(1, client.pairCalls)
        assertEquals(listOf(100, 19, 5, 25, 25, 25), client.cacheCalls.map { it.second })
        assertEquals(listOf("cache", "derived", "derived", "db", "db", "db"), sources)
        assertEquals(25, controller.state.value.cache?.expected)
        assertEquals("db", controller.state.value.cache?.source)
        assertFalse(controller.state.value.cacheFailed)
        controller.actions.retryPairs(); runCurrent()
        assertEquals(2, client.pairCalls)
    }

    @Test fun failedPairsAreIndependentAndDoNotRetryUntilExplicitRequest() = runTest {
        val client = Client().apply { pairs = { error("offline") } }
        val controller = MarketDiagnosticsController(this, client) { _, _ -> }
        controller.reset(context); controller.select(selection); runCurrent()
        assertTrue(controller.state.value.pairsFailed)
        assertNotNull(controller.state.value.cache)
        controller.select(selection.copy(timeframe = "5m")); runCurrent()
        assertEquals(1, client.pairCalls)
        client.pairs = { ConfiguredPairStatsDto(0, emptyList()) }
        controller.actions.retryPairs(); runCurrent()
        assertTrue(controller.state.value.pairsLoaded)
        assertFalse(controller.state.value.pairsFailed)
    }

    @Test fun selectionAToBToARejectsUncancellableOldCacheResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val client = Client().apply { cache = { _, _ ->
            val first = ++calls == 1
            if (first) withContext(NonCancellable) { gate.await() }
            val count = if (first) 99 else 7
            val candles = List(count) { MarketCachedCandleDto(it.toLong(), "1m", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO) }
            MarketCachedCandlesDto(candles, count, false, "cache")
        } }
        val controller = MarketDiagnosticsController(this, client) { _, _ -> }
        controller.reset(context); controller.select(selection); runCurrent()
        controller.select(selection.copy(instrument = "ETH")); runCurrent()
        controller.select(selection); runCurrent()
        assertEquals(7, controller.state.value.cache?.sampleCount)
        gate.complete(Unit); runCurrent()
        assertEquals(7, controller.state.value.cache?.sampleCount)
        assertFalse(controller.state.value.cacheLoading)
    }

    @Test fun fullSessionResetRejectsOldPairsAndDeniedNeverDispatchesOrNavigates() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { pairs = {
            withContext(NonCancellable) { gate.await() }
            ConfiguredPairStatsDto(1, listOf(pair("other:ETH")))
        } }
        var selected = ""
        val controller = MarketDiagnosticsController(this, client) { venue, symbol -> selected = "$venue:$symbol" }
        controller.reset(context); controller.select(selection); runCurrent()
        controller.reset(context.copy(sessionGeneration = 2, scope = context.scope.copy(effectivePermissions = emptySet())))
        controller.select(selection)
        controller.actions.retryPairs(); controller.actions.retryCache()
        gate.complete(Unit); runCurrent()
        assertTrue(controller.state.value.pairs.isEmpty())
        assertEquals(1, client.pairCalls)
        assertEquals(1, client.cacheCalls.size)
        controller.actions.selectPeer(MarketPairChip("other", "ETH", null, null, true))
        assertEquals("", selected)
    }

    @Test fun navigationUsesOnlyCurrentSelectedPairAndCacheFailureDoesNotHidePairs() = runTest {
        val client = Client().apply {
            pairs = { ConfiguredPairStatsDto(1, listOf(pair("other:ETH"))) }
            cache = { _, _ -> error("unavailable") }
        }
        var selected = ""
        val controller = MarketDiagnosticsController(this, client) { venue, symbol -> selected = "$venue:$symbol" }
        controller.reset(context); controller.select(selection); runCurrent()
        assertTrue(controller.state.value.cacheFailed)
        val chip = controller.state.value.chips.single()
        controller.actions.selectPeer(chip)
        assertEquals("other:ETH", selected)
        selected = ""
        controller.select(selection.copy(instrument = "DIFFERENT")); runCurrent()
        controller.actions.selectPeer(chip)
        assertEquals("", selected)
    }
    @Test fun rejectsImpossibleWarmthOversizedCountsAndWrongTimeframeSources() = runTest {
        fun response(count: Int, warm: Boolean, source: String): MarketCachedCandlesDto {
            val rows = List(count) { MarketCachedCandleDto(it.toLong(), "1m", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO) }
            return MarketCachedCandlesDto(rows, count, warm, source)
        }
        val client = Client()
        val controller = MarketDiagnosticsController(this, client) { _, _ -> }
        controller.reset(context)
        controller.select(selection); runCurrent()
        val invalid = listOf(response(0, true, "cache"), response(100, false, "cache"),
            response(101, true, "cache"), response(1, false, "cache").copy(sampleCount = 2), response(1, false, "db"), response(1, false, "derived"))
        for (result in invalid) {
            client.cache = { _, _ -> result }
            controller.refreshChartCache(); runCurrent()
            assertTrue(controller.state.value.cacheFailed)
            assertNull(controller.state.value.cache)
        }
        client.cache = { _, _ -> response(100, true, "cache") }
        controller.refreshChartCache(); runCurrent()
        assertTrue(controller.state.value.cache?.isWarm == true)
        assertEquals(1, client.pairCalls)
    }

    @Test fun unchangedSelectionRefreshCancelsAndRejectsLateOldProbeWithoutPairRefetch() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val client = Client().apply { cache = { _, _ ->
            val old = ++calls == 1
            if (old) withContext(NonCancellable) { gate.await() }
            val count = if (old) 1 else 2
            val rows = List(count) { MarketCachedCandleDto(it.toLong(), "1m", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO) }
            MarketCachedCandlesDto(rows, count, false, "cache")
        } }
        val controller = MarketDiagnosticsController(this, client) { _, _ -> }
        controller.reset(context); controller.select(selection); runCurrent()
        controller.refreshChartCache(); runCurrent()
        assertEquals(2, controller.state.value.cache?.sampleCount)
        assertFalse(controller.state.value.cacheLoading)
        gate.complete(Unit); runCurrent()
        assertEquals(2, controller.state.value.cache?.sampleCount)
        assertEquals(1, client.pairCalls)
        controller.refreshChartCache(); runCurrent()
        assertEquals(3, client.cacheCalls.size)
        assertEquals(1, client.pairCalls)
    }

}
