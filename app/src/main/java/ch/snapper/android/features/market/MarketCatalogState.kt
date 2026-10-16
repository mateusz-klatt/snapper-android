package ch.snapper.android.features.market

import ch.snapper.android.UiError
import ch.snapper.android.data.InstrumentDto

val marketTimeframes: List<String> = listOf("1m", "5m", "15m", "1h", "4h", "1d")

data class MarketCatalogState(
    val exchanges: List<String> = emptyList(),
    val instruments: List<InstrumentDto> = emptyList(),
    val exchange: String = "",
    val instrument: String = "",
    val timeframe: String = "1h",
    val query: String = "",
    val isLoading: Boolean = false,
    val error: UiError? = null,
) {
    val visibleInstruments: List<InstrumentDto>
        get() = instruments.filter {
            it.exchange == exchange && it.canMarketData && it.symbol.contains(query.trim(), ignoreCase = true)
        }
    val selectedInstrument: InstrumentDto?
        get() = instruments.firstOrNull { it.exchange == exchange && it.symbol == instrument && it.canMarketData }
    val canLoad: Boolean get() = !isLoading && selectedInstrument != null && timeframe in marketTimeframes
}

data class MarketCatalogActions(
    val selectExchange: (String) -> Unit = {},
    val selectInstrument: (String) -> Unit = {},
    val selectTimeframe: (String) -> Unit = {},
    val search: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

/** Pure commits; callers must discard responses from old sessions or request generations. */
object MarketSelection {
    fun exchanges(state: MarketCatalogState, values: List<String>): MarketCatalogState {
        val catalog = values.filter(String::isNotBlank).distinct()
        val selected = state.exchange.takeIf { it in catalog }
            ?: catalog.firstOrNull { it == "kraken" } ?: catalog.firstOrNull().orEmpty()
        return state.copy(exchanges = catalog).let {
            if (selected == state.exchange) it else it.copy(exchange = selected, instrument = "", instruments = emptyList(), query = "", error = null)
        }
    }

    fun exchange(state: MarketCatalogState, value: String): MarketCatalogState {
        if (value !in state.exchanges || value == state.exchange) return state
        return state.copy(exchange = value, instrument = "", instruments = emptyList(), query = "", error = null)
    }

    fun instruments(state: MarketCatalogState, exchange: String, values: List<InstrumentDto>): MarketCatalogState {
        if (exchange != state.exchange) return state
        val catalog = values.filter { it.exchange == exchange && it.canMarketData }.distinctBy { it.instrumentPublicId }
        val selected = state.instrument.takeIf { symbol -> catalog.any { it.symbol == symbol } }
            ?: listOf("BTC-USD", "BTC-USD-PERP").firstOrNull { symbol -> catalog.any { it.symbol == symbol } }
            ?: catalog.firstOrNull()?.symbol.orEmpty()
        return state.copy(instruments = catalog, instrument = selected, isLoading = false, error = null)
    }

    fun instrument(state: MarketCatalogState, symbol: String): MarketCatalogState =
        if (state.instruments.any { it.exchange == state.exchange && it.symbol == symbol && it.canMarketData }) {
            state.copy(instrument = symbol)
        } else state

    fun timeframe(state: MarketCatalogState, value: String): MarketCatalogState =
        if (value in marketTimeframes) state.copy(timeframe = value) else state
}
