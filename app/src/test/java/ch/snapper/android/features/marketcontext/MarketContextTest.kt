package ch.snapper.android.features.marketcontext

import ch.snapper.android.data.*
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class MarketContextTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val selection = MarketContextSelection("kraken", "BTC-USD")
    private fun row(price: String = "1.000000000000000001", time: Long = 1) = MarketCachedCandleDto(time, "1h",
        BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal.ZERO)
    private fun related(symbol: String = "BTC-USD") = RelatedMarketPayloadDto(RelatedMarketSelectionDto("kraken", symbol), groups = emptyList())
    private inner class Client : MarketContextClient {
        var metricsCalls = 0
        var relatedCalls = 0
        var failMetrics = false
        var gate: CompletableDeferred<Unit>? = null
        var ignoreCancel = false
        var response: RelatedMarketPayloadDto? = null
        override suspend fun cachedHourly(exchange: String, symbol: String): MarketCachedCandlesDto {
            metricsCalls++
            val call = metricsCalls
            if (ignoreCancel) withContext(NonCancellable) { gate?.await() } else gate?.await()
            check(!failMetrics)
            return MarketCachedCandlesDto(listOf(row(call.toString())), 1, false, "db")
        }
        override suspend fun related(exchange: String, symbol: String): RelatedMarketPayloadDto {
            relatedCalls++
            return response ?: this@MarketContextTest.related(symbol)
        }
    }

    @Test fun exactWireNumbersNullEmptyAndInvalidBars() {
        val wire = """{"open_at_ms":1,"timeframe":"1h","open":1.000000000000000001,"high":1.000000000000000001,"low":1.000000000000000001,"close":1.000000000000000001,"volume":0}"""
        val decoded = Json.decodeFromString<MarketCachedCandleDto>(wire)
        assertEquals(BigDecimal("1.000000000000000001"), decoded.close)
        val metrics = MarketMetricsCalculator.calculate(listOf(decoded, row("9", Long.MAX_VALUE), row().copy(timeframe = "1m")), clock.millis())
        assertEquals(decoded.close, metrics.lastPrice)
        assertNull(metrics.changePercent)
        assertNull(MarketMetricsCalculator.calculate(emptyList(), clock.millis()).lastPrice)
        assertNull(MarketMetricsCalculator.calculate(listOf(row("0")), clock.millis()).changePercent)
    }

    @Test fun deniedAndNoSelectionNeverCallBackend() = runTest {
        val client = Client()
        val controller = MarketContextController(this, client, { _, _ -> }, clock)
        controller.reset(selection, false)
        controller.actions.retryMetrics()
        controller.actions.retryRelated()
        controller.reset(null, true)
        runCurrent()
        assertEquals(0, client.metricsCalls + client.relatedCalls)
    }

    @Test fun metricsFailureDoesNotEraseRelatedAndRetryIsIndependent() = runTest {
        val client = Client().apply { failMetrics = true }
        val controller = MarketContextController(this, client, { _, _ -> }, clock)
        controller.reset(selection, true)
        runCurrent()
        assertTrue(controller.state.value.metricsFailed)
        assertNotNull(controller.state.value.related)
        client.failMetrics = false
        controller.actions.retryMetrics()
        controller.actions.retryMetrics()
        runCurrent()
        assertFalse(controller.state.value.metricsFailed)
        assertNotNull(controller.state.value.metrics?.lastPrice)
        assertEquals(2, client.metricsCalls)
        assertEquals(1, client.relatedCalls)
    }

    @Test fun scopeInvalidationDropsCancellationIgnoringOldCompletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { this.gate = gate; ignoreCancel = true }
        val controller = MarketContextController(this, client, { _, _ -> }, clock)
        controller.reset(selection, true)
        runCurrent()
        controller.invalidate()
        gate.complete(Unit)
        runCurrent()
        assertEquals(MarketContextState(), controller.state.value)
    }

    @Test fun relatedEchoMustMatchAndNavigationMustComeFromCurrentPayload() = runTest {
        val item = RelatedMarketInstrumentDto("id", "ETH-USD", "kraken", "crypto", "proxy", isSelected = false)
        val client = Client().apply { response = related("wrong") }
        var target = ""
        val controller = MarketContextController(this, client, { exchange, symbol -> target = "$exchange:$symbol" }, clock)
        controller.reset(selection, true)
        runCurrent()
        assertTrue(controller.state.value.relatedFailed)
        controller.actions.selectRelated(item)
        assertEquals("", target)
        client.response = related().copy(groups = listOf(RelatedMarketGroupDto("proxy", "Proxies", listOf(item))))
        controller.actions.retryRelated()
        runCurrent()
        controller.actions.selectRelated(item)
        assertEquals("kraken:ETH-USD", target)
        controller.invalidate()
        target = ""
        controller.actions.selectRelated(item)
        assertEquals("", target)
    }
    @Test fun selectionAToBToADropsOriginalAAndCancelsLoading() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        val client = Client().apply { gate = oldGate; ignoreCancel = true }
        val controller = MarketContextController(this, client, { _, _ -> }, clock)
        controller.reset(selection, true)
        runCurrent()
        client.gate = null
        client.ignoreCancel = false
        controller.reset(selection.copy(symbol = "ETH-USD"), true)
        runCurrent()
        controller.reset(selection, true)
        runCurrent()
        assertEquals(BigDecimal("3"), controller.state.value.metrics?.lastPrice)
        oldGate.complete(Unit)
        runCurrent()
        assertEquals(BigDecimal("3"), controller.state.value.metrics?.lastPrice)
        assertFalse(controller.state.value.metricsLoading)
    }

    @Test fun ancientAndGappySamplesRetainCachedPriceButWithholdDailyMetrics() {
        val metrics = MarketMetricsCalculator.calculate(listOf(row("1.25", 2), row("1", 1)), clock.millis())
        assertEquals(BigDecimal("1.25"), metrics.lastPrice)
        assertEquals(3_600_002L, metrics.asOfMillis)
        assertNull(metrics.changePercent)
        assertNull(metrics.high)
        assertNull(metrics.low)
    }

}
