package ch.snapper.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.R
import ch.snapper.android.data.DisplayPreferences
import ch.snapper.android.data.MarketPairStatsDto
import ch.snapper.android.features.marketcontext.*
import ch.snapper.android.features.marketdiagnostics.*
import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarketDiagnosticsContentTest {
    @get:Rule val compose = createComposeRule()
    private val selection = MarketDiagnosticsSelection("venue", "BTC", "5m")
    private fun pair(symbol: String, r: String? = null, p: String? = null, warm: Boolean = true) = MarketPairStatsDto(
        "venue:BTC", "other:$symbol", r?.let(::BigDecimal), 25, null, p?.let(::BigDecimal), null, null, 25, warm)
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun coldChartBannerDoesNotDependOnHourlyMetricSuccessAndWarmHidesOnlyBanner() {
        val diagnostics = mutableStateOf(MarketDiagnosticsState(permitted = true, selection = selection,
            cache = ChartCacheStatus(3, 19, "derived", false)))
        val hourly = mutableStateOf(MarketContextState(selection = MarketContextSelection("venue", "BTC"), permitted = true,
            metrics = MarketMetrics(lastPrice = BigDecimal("12.25"))))
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) {
            MarketContextContent(hourly.value, MarketContextActions({}, {}, {}))
            MarketDiagnosticsContent(diagnostics.value, MarketDiagnosticsActions({}, {}, {}))
        } }
        compose.onNodeWithTag("marketDiagnostics.cache.cold").performScrollTo().assertTextContains("3 / 19", substring = true)
        compose.runOnIdle { hourly.value = hourly.value.copy(metrics = null, metricsFailed = true) }
        compose.onNodeWithTag("marketDiagnostics.cache.cold").assertExists()
        compose.onNodeWithTag("marketContext.metrics.error").assertExists()
        compose.runOnIdle { diagnostics.value = diagnostics.value.copy(cache = ChartCacheStatus(19, 19, "derived", true)) }
        compose.onNodeWithTag("marketDiagnostics.cache.cold").assertDoesNotExist()
        compose.onNodeWithTag("marketContext.metrics.error").assertExists()
    }

    @Test fun nullableStatsRemainUnavailableAndClickPassesExactPeer() {
        val state = MarketDiagnosticsState(permitted = true, selection = selection, pairs = listOf(pair("ETH/USD:PERP")), pairsLoaded = true)
        var peer: MarketPairChip? = null
        compose.setContent { MarketDiagnosticsContent(state, MarketDiagnosticsActions({}, {}, { peer = it })) }
        compose.onNodeWithText("ρ — · p —").assertExists()
        compose.onNodeWithTag("marketDiagnostics.peer.other.ETH/USD:PERP").performClick()
        compose.runOnIdle {
            assertEquals("other", peer?.exchange)
            assertEquals("ETH/USD:PERP", peer?.instrument)
            assertEquals(null, peer?.pearsonR)
            assertEquals(null, peer?.pvalue)
        }
    }

    @Test fun cacheAndPairErrorRetriesRemainIndependent() {
        val state = mutableStateOf(MarketDiagnosticsState(permitted = true, selection = selection, cacheFailed = true, pairsFailed = true))
        var pairs = 0; var cache = 0
        compose.setContent { MarketDiagnosticsContent(state.value, MarketDiagnosticsActions({ pairs++ }, { cache++ }, {})) }
        compose.onAllNodesWithTag("error.action")[0].performClick()
        compose.runOnIdle { assertEquals(1, cache); assertEquals(0, pairs); state.value = state.value.copy(cacheFailed = false) }
        compose.onNodeWithTag("error.action").performClick()
        compose.runOnIdle { assertEquals(1, cache); assertEquals(1, pairs) }
    }

    @Test fun countryFormattingAndAccessibleStatusDistinguishColdFromSignificant() {
        val locale = mutableStateOf(Locale.US)
        val state = MarketDiagnosticsState(permitted = true, selection = selection,
            pairs = listOf(pair("COLD", "-0.75", "0.001", false), pair("WARM", "0.5", "0.01", true)))
        compose.setContent {
            CompositionLocalProvider(LocalNumberLocale provides locale.value) {
                MarketDiagnosticsContent(state, MarketDiagnosticsActions({}, {}, {}))
            }
        }
        compose.onNodeWithText("ρ -0.75 · p 0.001").assertExists()
        val cold = text(R.string.market_pair_status_cold)
        val significant = text(R.string.market_pair_status_cointegrated)
        compose.onNodeWithTag("marketDiagnostics.peer.other.COLD").assert(
            hasContentDescription(cold, substring = true) and !hasContentDescription(significant, substring = true))
        compose.onNodeWithTag("marketDiagnostics.peer.other.WARM").assert(hasContentDescription(significant, substring = true))
        compose.runOnIdle { locale.value = DisplayPreferences("pl").formattingLocale(Locale.US) }
        compose.onNodeWithText("ρ -0,75 · p 0,001").assertExists()
        compose.onNodeWithText(cold).assertExists()
    }
}
