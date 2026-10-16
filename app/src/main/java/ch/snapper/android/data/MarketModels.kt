package ch.snapper.android.data

/** Market browsing is independent of trading permission and active wallet selection. */
interface MarketCatalogClient {
    suspend fun marketExchanges(): List<String>
    suspend fun marketInstruments(exchange: String): List<InstrumentDto>
}
