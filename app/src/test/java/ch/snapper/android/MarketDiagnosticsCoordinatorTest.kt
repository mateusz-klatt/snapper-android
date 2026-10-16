package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.market.*
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketDiagnosticsCoordinatorTest {
    private fun catalog(symbol: String = "BTC", exchange: String = "venue") = MarketCatalogState(
        exchanges = listOf(exchange), exchange = exchange, instrument = symbol, timeframe = "1m",
        instruments = listOf(InstrumentDto("instrument", "symbol", symbol, exchange, true, true, canMarketData = true)))

    @Test fun exactCatalogValidationPreventsReadsAndSameSelectionSnapshotsProbeWithoutRefetchingPairs() = runTest {
        val client = Client()
        var state = AppUiState(user = client.user.value, marketCatalog = catalog())
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.prepareMarketDiagnostics("venue", "BTC", "5m")
        coordinator.probeMarketDiagnostics("foreign", "BTC", "1m")
        runCurrent()
        assertEquals(0, client.pairReads); assertEquals(0, client.cacheReads)
        coordinator.prepareMarketDiagnostics("venue", "BTC", "1m"); runCurrent()
        assertEquals(1, client.pairReads); assertEquals(0, client.cacheReads)
        repeat(2) { coordinator.probeMarketDiagnostics("venue", "BTC", "1m"); runCurrent() }
        assertEquals(1, client.pairReads); assertEquals(2, client.cacheReads)
        state = state.copy(marketCatalog = catalog("ETH"))
        coordinator.prepareMarketDiagnostics("venue", "ETH", "1m")
        assertNull(state.marketDiagnostics.cache)
        runCurrent(); assertEquals(1, client.pairReads)
        coordinator.invalidate()
    }

    @Test fun actorReplacementRejectsHeldOldPairsAndLoadsNewContextOnce() = runTest {
        val held = CompletableDeferred<Unit>()
        val client = Client()
        var first = true
        client.pairs = {
            if (first) { first = false; withContext(NonCancellable) { held.await() }; configured("OLD") }
            else configured("NEW")
        }
        var state = AppUiState(user = client.user.value, marketCatalog = catalog())
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.prepareMarketDiagnostics("venue", "BTC", "1m"); runCurrent()
        client.user.value = requireNotNull(client.user.value).copy(primaryOperatorPublicId = "desk-b")
        state = state.copy(user = client.user.value)
        coordinator.prepareMarketDiagnostics("venue", "BTC", "1m"); runCurrent()
        assertEquals("NEW", state.marketDiagnostics.chips.single().instrument)
        held.complete(Unit); runCurrent()
        assertEquals("NEW", state.marketDiagnostics.chips.single().instrument)
        assertEquals(2, client.pairReads)
        coordinator.invalidate()
        state = state.copy(user = null)
        client.user.value = null
        coordinator.marketDiagnosticsActions.retryPairs()
        coordinator.marketDiagnosticsActions.retryCache()
        runCurrent(); assertEquals(2, client.pairReads)
        assertTrue(state.marketDiagnostics.pairs.isEmpty())
    }

    @Test fun peerNavigationRequeriesCatalogAndRejectsUnavailablePeerThenAllowsAuthorizedPeer() = runTest {
        val client = Client().apply { pairs = { configured("ETH") } }
        var state = AppUiState(user = client.user.value, marketCatalog = catalog())
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.prepareMarketDiagnostics("venue", "BTC", "1m"); runCurrent()
        val chip = state.marketDiagnostics.chips.single()
        coordinator.marketDiagnosticsActions.selectPeer(chip); runCurrent()
        assertEquals("BTC", state.marketCatalog.instrument)
        assertEquals(1, client.catalogReads)
        client.peerAvailable = true
        coordinator.marketDiagnosticsActions.selectPeer(chip); runCurrent()
        assertEquals("ETH", state.marketCatalog.instrument)
        assertEquals(2, client.catalogReads)
        coordinator.invalidate()
    }

    @Test fun deniedPermissionsAndUnresolvedCatalogNeverDispatchDiagnostics() = runTest {
        val client = Client()
        var state = AppUiState(user = client.user.value, marketCatalog = catalog().copy(instruments = emptyList()))
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.probeMarketDiagnostics("venue", "BTC", "1m"); runCurrent()
        state = state.copy(marketCatalog = catalog())
        client.user.value = requireNotNull(client.user.value).copy(effectivePermissions = emptyList())
        state = state.copy(user = client.user.value)
        coordinator.probeMarketDiagnostics("venue", "BTC", "1m"); runCurrent()
        assertEquals(0, client.pairReads); assertEquals(0, client.cacheReads)
        coordinator.invalidate()
    }

    @Test fun actualViewModelSnapshotHookProbesOncePerManualReadAndReusesPairs() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val client = Client().apply { peerAvailable = true }
        val settings = object : SettingsStore {
            override val preferences = MutableStateFlow(ClientPreferences(null))
            override suspend fun setBackendUrl(origin: String) = Unit
            override suspend fun setLanguage(language: String) = Unit
            override suspend fun updateDisplayPreferences(transform: (DisplayPreferences) -> DisplayPreferences) = transform(preferences.value.displayPreferences)
        }
        try {
            val model = SnapperViewModel(settings, { client }, "https://example.test", false)
            models.put("diagnostics", model); runCurrent()
            model.actions.loadCatalog(); runCurrent()
            assertEquals("ETH", model.state.value.marketCatalog.instrument)
            assertTrue(model.state.value.marketCatalog.selectedInstrument?.canMarketData == true)
            model.actions.marketCatalog.selectTimeframe("1m")
            repeat(2) { model.loadMarket("venue", "ETH", "1m"); runCurrent() }
            assertEquals(2, client.cacheReads)
            assertEquals(1, client.pairReads)
            assertNotNull(model.state.value.marketDiagnostics.cache)
            model.logout(); runCurrent()
            assertNull(model.state.value.marketDiagnostics.selection)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    private class Client : SessionClient, MarketDiagnosticsClient, MarketCatalogClient {
        override val user = MutableStateFlow<UserDto?>(UserDto("reader", "viewer", "user", primaryOperatorPublicId = "desk-a", effectivePermissions = listOf("read:market_data")))
        var pairReads = 0
        var cacheReads = 0
        var catalogReads = 0
        var peerAvailable = false
        var pairs: suspend () -> ConfiguredPairStatsDto = { ConfiguredPairStatsDto(0, emptyList()) }
        override suspend fun configuredPairStats(): ConfiguredPairStatsDto { pairReads++; return pairs() }
        override suspend fun chartCache(exchange: String, instrument: String, timeframe: String, limit: Int): MarketCachedCandlesDto {
            cacheReads++
            return MarketCachedCandlesDto(emptyList(), 0, false, "cache")
        }
        override suspend fun marketExchanges(): List<String> { catalogReads++; return listOf("venue", "peer") }
        override suspend fun marketInstruments(exchange: String) = if (peerAvailable) listOf(InstrumentDto("peer-id", "peer-symbol", "ETH", exchange, true, true, canMarketData = true)) else emptyList()
        override suspend fun restore() = user.value
        override suspend fun login(username: String, password: String, rememberMe: Boolean) = requireNotNull(user.value)
        override suspend fun selectWallet(publicId: String?) = requireNotNull(user.value)
        override suspend fun logout() { user.value = null }
        override suspend fun snapshot() = DashboardSnapshot(requireNotNull(user.value), emptyList(), emptyList(), emptyList(), emptyList())
        override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> = emptyList()
        override suspend fun orderExchanges(scope: OrderScope): List<String> = error("Unexpected order access")
        override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = error("Unexpected order access")
        override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = error("Unexpected mutation")
        override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = error("Unexpected mutation")
        override fun close() = Unit
    }

    companion object {
        private fun configured(peer: String) = ConfiguredPairStatsDto(1, listOf(MarketPairStatsDto(
            "venue:BTC", "peer:$peer", null, 0, null, null, null, null, 0, false)))
    }
}
