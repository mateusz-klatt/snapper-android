package ch.snapper.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.snapper.android.data.*
import ch.snapper.android.features.marketcontext.*
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarketContextContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactMetricsRelatedNavigationAndIndependentRetry() {
        val item = RelatedMarketInstrumentDto("peer", "ETH-USD", "kraken", "crypto", "proxy", isSelected = false)
        val selected = item.copy(instrumentPublicId = "current", nativeSymbol = "BTC-USD", isSelected = true)
        val related = RelatedMarketPayloadDto(RelatedMarketSelectionDto("kraken", "BTC-USD"),
            RelatedMarketUnderlyingDto("underlying", "BTC", "Bitcoin", "crypto", description = "Demo description"),
            listOf(RelatedMarketGroupDto("proxy", "Proxies", listOf(selected, item))))
        val state = mutableStateOf(MarketContextState(MarketContextSelection("kraken", "BTC-USD"), permitted = true,
            metrics = MarketMetrics(lastPrice = BigDecimal("1.000000000000000001")), related = related))
        var target = ""
        var retries = 0
        val actions = MarketContextActions({ retries++ }, {}, { target = it.instrumentPublicId })
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { MarketContextContent(state.value, actions) } }
        compose.onNodeWithTag("marketContext.metric.last").assertTextContains("1.000000000000000001", substring = true)
        compose.onNodeWithTag("marketContext.metric.high").assertTextContains("—", substring = true)
        compose.onNodeWithTag("marketContext.related.current").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("marketContext.related.peer").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("peer", target); state.value = state.value.copy(metrics = null, metricsFailed = true) }
        compose.onNodeWithTag("marketContext.metrics.retry").performScrollTo().performClick()
        compose.onNodeWithTag("marketContext.related.peer").assertExists()
        compose.runOnIdle { assertEquals(1, retries); state.value = MarketContextState() }
        compose.onNodeWithTag("marketContext.denied").assertExists()
        compose.onNodeWithTag("marketContext.related.peer").assertDoesNotExist()
    }

    @Test fun loadingAndEmptyRemainDifferentFromFailure() {
        val state = mutableStateOf(MarketContextState(MarketContextSelection("kraken", "BTC-USD"), permitted = true,
            metricsLoading = true, relatedLoading = true))
        compose.setContent { MarketContextContent(state.value, MarketContextActions({}, {}, {})) }
        compose.onNodeWithTag("marketContext.related.loading").assertExists()
        compose.onNodeWithTag("marketContext.related.empty").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(metricsLoading = false, relatedLoading = false,
            related = RelatedMarketPayloadDto(RelatedMarketSelectionDto("kraken", "BTC-USD"), groups = emptyList())) }
        compose.onNodeWithTag("marketContext.related.empty").assertExists()
        compose.onNodeWithTag("marketContext.related.error").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(related = null, relatedFailed = true) }
        compose.onNodeWithTag("marketContext.related.error").assertExists()
        compose.onNodeWithTag("marketContext.related.empty").assertDoesNotExist()
    }
}
