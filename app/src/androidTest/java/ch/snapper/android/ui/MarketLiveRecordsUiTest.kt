package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.MarketState
import ch.snapper.android.R
import ch.snapper.android.UiError
import ch.snapper.android.data.*
import ch.snapper.android.features.live.LiveConnectionStatus
import ch.snapper.android.features.market.MarketCatalogState
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarketLiveRecordsUiTest {
    @get:Rule val compose = createComposeRule()

    private val catalog = MarketCatalogState(
        exchanges = listOf("kraken"), exchange = "kraken", instrument = "ETH-USD", timeframe = "5m",
        instruments = listOf(InstrumentDto("instrument", "symbol", "ETH-USD", "kraken", false, true,
            canMarketData = true, instrumentKind = "spot")),
    )
    private val candle = CandleDto("ETH-USD", "kraken", "5m", "2026-10-16T10:00:00Z",
        BigDecimal("12.123456789012345678"), BigDecimal("14"), BigDecimal("10"),
        BigDecimal("13.987654321098765432"), BigDecimal("0.000000123456789123"))

    private fun populated() = MarketState("kraken", "ETH-USD", "5m", listOf(candle))

    private fun reveal(text: String) {
        compose.onNodeWithTag("market.records").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test fun catalogBackedCandlesPreserveExactPricesAndVolumeForViewOnlyInstrument() {
        compose.setContent {
            CompositionLocalProvider(LocalNumberLocale provides Locale.ROOT) {
                MaterialTheme { LazyColumn(Modifier.testTag("market.records")) {
                    marketScreen(populated(), { _, _, _ -> error("Rendering must not load") }, catalog)
                } }
            }
        }
        compose.onNodeWithTag("market.load").assertIsEnabled()
        reveal("kraken · ETH-USD · 5m")
        reveal("12.123456789012345678")
        reveal("13.987654321098765432")
        reveal("0.000000123456789123")
        compose.onNodeWithTag("order.new").assertDoesNotExist()
    }

    @Test fun liveTickAndConnectionTransitionsRetainSnapshotRecords() {
        val tick = LiveMarketFrame.Tick(LiveSelection("kraken", "ETH-USD", "5m"),
            LiveStamp("session", 7, "tick", Instant.parse("2026-10-16T10:01:00Z")),
            BigDecimal("13.111111111111111111"), null, null, BigDecimal.ONE, false, null)
        val screen = mutableStateOf(populated().copy(liveStatus = LiveConnectionStatus.SUBSCRIBED, tick = tick))
        val labels = mutableMapOf<LiveConnectionStatus, String>()
        compose.setContent {
            labels[LiveConnectionStatus.SUBSCRIBED] = stringResource(R.string.market_live_connected)
            labels[LiveConnectionStatus.RECONNECTING] = stringResource(R.string.market_live_connecting)
            labels[LiveConnectionStatus.FAILED] = stringResource(R.string.market_live_unavailable)
            labels[LiveConnectionStatus.STOPPED] = stringResource(R.string.market_snapshot_mode)
            CompositionLocalProvider(LocalNumberLocale provides Locale.ROOT) {
                MaterialTheme { LazyColumn(Modifier.testTag("market.records")) {
                    marketScreen(screen.value, { _, _, _ -> error("Connection presentation cannot request REST") }, catalog)
                } }
            }
        }
        reveal("13.111111111111111111")
        listOf(LiveConnectionStatus.SUBSCRIBED, LiveConnectionStatus.RECONNECTING,
            LiveConnectionStatus.FAILED, LiveConnectionStatus.STOPPED).forEach { status ->
            compose.runOnIdle { screen.value = screen.value.copy(liveStatus = status) }
            compose.onNodeWithTag("market.records").performScrollToNode(hasTestTag("market.connection"))
            compose.onNodeWithTag("market.connection").assertTextEquals(labels.getValue(status))
            reveal("0.000000123456789123")
        }
    }

    @Test fun failedRefreshRetainsCandlesAndExplicitRetryUsesSelectedTupleOnce() {
        val screen = mutableStateOf(populated().copy(error = UiError.NETWORK, liveStatus = LiveConnectionStatus.FAILED))
        val requests = mutableListOf<Triple<String, String, String>>()
        compose.setContent {
            CompositionLocalProvider(LocalNumberLocale provides Locale.ROOT) {
                MaterialTheme { LazyColumn(Modifier.testTag("market.records")) {
                    marketScreen(screen.value, { exchange, instrument, timeframe ->
                        requests += Triple(exchange, instrument, timeframe)
                        screen.value = screen.value.copy(error = null, isLoading = true)
                    }, catalog)
                } }
            }
        }
        reveal("13.987654321098765432")
        compose.runOnIdle { assertEquals(emptyList<Triple<String, String, String>>(), requests) }
        compose.onNodeWithTag("market.records").performScrollToNode(hasTestTag("error.action"))
        compose.onNodeWithTag("error.action").performScrollTo().performClick()
        compose.onNodeWithTag("error.action").assertDoesNotExist()
        compose.onNodeWithTag("market.records").performScrollToNode(hasTestTag("market.load"))
        compose.onNodeWithTag("market.load").assertIsNotEnabled()
        reveal("13.987654321098765432")
        compose.runOnIdle { assertEquals(listOf(Triple("kraken", "ETH-USD", "5m")), requests) }
    }
}
