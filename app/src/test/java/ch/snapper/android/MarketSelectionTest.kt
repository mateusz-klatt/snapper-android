package ch.snapper.android

import ch.snapper.android.data.InstrumentDto
import ch.snapper.android.features.market.MarketCatalogState
import ch.snapper.android.features.market.MarketSelection
import ch.snapper.android.features.market.marketTimeframes
import org.junit.Assert.*
import org.junit.Test

class MarketSelectionTest {
    private fun instrument(symbol: String, exchange: String = "kraken", market: Boolean = true, trade: Boolean = false) =
        InstrumentDto(symbol, symbol, symbol, exchange, trade, true, canMarketData = market, instrumentKind = "spot")

    @Test fun viewOnlyMarketIsSelectableAndSearchIsCaseInsensitive() {
        val rows = listOf(instrument("BTC-USD"), instrument("ETH-USD"), instrument("HIDDEN", market = false))
        val state = MarketSelection.instruments(MarketCatalogState(exchange = "kraken"), "kraken", rows)
        assertEquals("BTC-USD", state.instrument)
        assertTrue(state.canLoad)
        assertFalse(requireNotNull(state.selectedInstrument).canTrade)
        assertEquals(listOf("ETH-USD"), state.copy(query = " eth ").visibleInstruments.map { it.symbol })
    }

    @Test fun restoredSelectionSurvivesCatalogRefreshButMissingSelectionFallsBack() {
        val state = MarketCatalogState(exchange = "kraken", instrument = "ETH-USD")
        val rows = listOf(instrument("BTC-USD"), instrument("ETH-USD"))
        assertEquals("ETH-USD", MarketSelection.instruments(state, "kraken", rows).instrument)
        assertEquals("BTC-USD", MarketSelection.instruments(state, "kraken", rows.take(1)).instrument)
    }

    @Test fun oldExchangeResponseCannotReplaceCurrentCatalog() {
        val state = MarketCatalogState(exchange = "new", instrument = "NEW")
        assertEquals(state, MarketSelection.instruments(state, "kraken", listOf(instrument("BTC-USD"))))
    }

    @Test fun changingVenueClearsStaleInstrumentAndSearch() {
        val state = MarketCatalogState(exchanges = listOf("kraken", "other"), exchange = "kraken", instrument = "BTC-USD", instruments = listOf(instrument("BTC-USD")), query = "btc")
        val changed = MarketSelection.exchange(state, "other")
        assertEquals("other", changed.exchange)
        assertEquals("", changed.instrument)
        assertEquals("", changed.query)
        assertFalse(changed.canLoad)
        assertEquals(state, MarketSelection.exchange(state, "unknown"))
    }

    @Test fun emptyExchangeCatalogRemovesOldSelection() {
        val state = MarketCatalogState(exchange = "kraken", instrument = "BTC-USD", instruments = listOf(instrument("BTC-USD")))
        val cleared = MarketSelection.exchanges(state, emptyList())
        assertEquals("", cleared.exchange)
        assertTrue(cleared.instruments.isEmpty())
        assertFalse(cleared.canLoad)
    }

    @Test fun exchangeCatalogPrefersRestoredThenKrakenThenFirst() {
        assertEquals("other", MarketSelection.exchanges(MarketCatalogState(exchange = "other"), listOf("kraken", "other")).exchange)
        assertEquals("kraken", MarketSelection.exchanges(MarketCatalogState(), listOf("other", "kraken")).exchange)
        assertEquals(listOf("other"), MarketSelection.exchanges(MarketCatalogState(), listOf("", "other", "other")).exchanges)
    }

    @Test fun foreignAndDisabledInstrumentsCannotBeSelected() {
        val state = MarketSelection.instruments(MarketCatalogState(exchange = "kraken"), "kraken", listOf(instrument("FOREIGN", "other"), instrument("HIDDEN", market = false)))
        assertFalse(state.canLoad)
        assertEquals(state, MarketSelection.instrument(state, "FOREIGN"))
    }

    @Test fun onlySupportedTimeframesAreAcceptedAndLoadingBlocksLoad() {
        assertEquals(listOf("1m", "5m", "15m", "1h", "4h", "1d"), marketTimeframes)
        val state = MarketSelection.instruments(MarketCatalogState(exchange = "kraken"), "kraken", listOf(instrument("BTC-USD")))
        marketTimeframes.forEach { assertEquals(it, MarketSelection.timeframe(state, it).timeframe) }
        assertEquals(state, MarketSelection.timeframe(state, "30m"))
        assertFalse(state.copy(isLoading = true).canLoad)
        assertFalse(state.copy(timeframe = "bad").canLoad)
    }
}
