package ch.snapper.android.ui

import ch.snapper.android.R

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.*
import ch.snapper.android.data.*
import ch.snapper.android.features.market.*
import ch.snapper.android.features.records.*
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarketRecordsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun flatCandlesWithDifferentDecimalScalesRenderFinitePriceAxes() {
        val candle = CandleDto("BTC-USD", "kraken", "1h", "2026-10-16T10:00:00Z",
            BigDecimal("1"), BigDecimal("1.0000"), BigDecimal("1.0"), BigDecimal("1.00"), BigDecimal.ZERO)
        var description = ""
        compose.setContent {
            description = stringResource(R.string.chart_description)
            MaterialTheme { CandleChart(listOf(candle)) }
        }
        compose.onNodeWithContentDescription(description).assertExists()
        compose.onNodeWithText("1.01").assertExists()
        compose.onNodeWithText("0.99").assertExists()
    }

    private fun instrument(symbol: String, market: Boolean = true) =
        InstrumentDto(symbol, symbol, symbol, "kraken", false, true, canMarketData = market, instrumentKind = "spot")

    @Test fun catalogSearchSelectsViewOnlyInstrumentAndFiltersUnavailableMarkets() {
        val catalog = mutableStateOf(MarketCatalogState(
            exchanges = listOf("kraken"), exchange = "kraken", instrument = "BTC-USD",
            instruments = listOf(instrument("BTC-USD"), instrument("ETH-USD"), instrument("HIDDEN", false)),
        ))
        val actions = MarketCatalogActions(
            search = { catalog.value = catalog.value.copy(query = it) },
            selectInstrument = { catalog.value = MarketSelection.instrument(catalog.value, it) },
        )
        compose.setContent { MaterialTheme { MarketCatalogControls(catalog.value, actions) } }
        compose.onNodeWithTag("market.instrument").performClick()
        compose.onNodeWithText("HIDDEN").assertDoesNotExist()
        compose.onNodeWithTag("market.search").performTextInput("eth")
        compose.onNodeWithText("ETH-USD").performClick()
        compose.runOnIdle {
            assertEquals("ETH-USD", catalog.value.instrument)
            assertEquals(false, catalog.value.selectedInstrument?.canTrade)
        }
        compose.onNodeWithTag("market.search").assertDoesNotExist()
    }

    @Test fun timeframeChipsDriveExactSupportedSelectionAndInvalidCatalogDisablesLoad() {
        val catalog = mutableStateOf(MarketCatalogState(exchange = "kraken"))
        val actions = MarketCatalogActions(selectTimeframe = { catalog.value = MarketSelection.timeframe(catalog.value, it) })
        compose.setContent {
            MaterialTheme { LazyColumn { marketScreen(MarketState(), { _, _, _ -> error("Invalid catalog must not load") }, catalog.value, actions) } }
        }
        compose.onNodeWithTag("market.load").assertIsNotEnabled()
        marketTimeframes.forEach { timeframe ->
            compose.onNodeWithTag("market.timeframe.$timeframe").performScrollTo().performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(timeframe, catalog.value.timeframe) }
        }
    }

    @Test fun viewerCanSelectFillsAndRefreshWithoutOrderMutationControls() {
        val user = UserDto("viewer", "viewer", publicId = "viewer", effectivePermissions = listOf("read:orders"))
        val order = OrderDto("o", "c", "ORDER-INSTRUMENT", "kraken", "buy", "open", "market", BigDecimal.ONE,
            BigDecimal.ZERO, "2026-10-16T00:00:00Z", price = null, walletPublicId = null, planPublicId = "plan")
        val app = AppUiState(user = user, isRestoring = false, snapshot = DashboardSnapshot(user, emptyList(), emptyList(), listOf(order), emptyList()))
        val records = mutableStateOf(RecordsState(executions = listOf(ExecutionDto(
            "f", "FILL-INSTRUMENT", "kraken", "buy", BigDecimal("0.25"), BigDecimal("123.45"), BigDecimal("0.01"), "USD", "filled", "2026-10-16T01:00:00Z", walletPublicId = null,
        ))))
        var refreshes = 0
        var refreshLabel = ""
        val callbacks = RecordsActions(selectSegment = { records.value = records.value.copy(segment = it) }, refreshExecutions = { refreshes++ })
        compose.setContent {
            refreshLabel = stringResource(R.string.records_refresh)
            MaterialTheme { LazyColumn { ordersScreen(app, OrderActions(open = { error("Viewer cannot create orders") }), records.value, callbacks) } }
        }
        compose.onNodeWithTag("order.new").assertDoesNotExist()
        compose.onNodeWithTag("order.cancel.o").assertDoesNotExist()
        compose.onNodeWithText("ORDER-INSTRUMENT").assertExists()
        compose.onNodeWithTag("orders.segment.FILLS").performClick()
        compose.onNodeWithText("ORDER-INSTRUMENT").assertDoesNotExist()
        compose.onNodeWithText(refreshLabel).performScrollTo().performClick()
        compose.onNodeWithText("FILL-INSTRUMENT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("order.new").assertDoesNotExist()
        compose.onNodeWithTag("orders.segment.OPEN").performScrollTo().performClick()
        compose.onNodeWithText("ORDER-INSTRUMENT").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }
}
