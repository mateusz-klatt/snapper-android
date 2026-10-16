package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.live.LiveRefreshTarget
import ch.snapper.android.features.market.MarketCatalogState
import ch.snapper.android.features.system.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveFeatureCoordinatorTest {
    private val profile = UserDto("reader", "viewer", "u", effectivePermissions = listOf("read:signals", "read:strategies", "read:market_data", "read:processes", "read:system_status", "read:ai_reviews"))
    private fun initial() = AppUiState(user = profile, marketCatalog = MarketCatalogState(exchanges = listOf("old"), exchange = "old", instrument = "BTC", instruments = listOf(instrument("old", "BTC"))))
    private fun instrument(exchange: String, symbol: String) = InstrumentDto("$exchange-$symbol", "symbol", symbol, exchange, false, true, true)

    @Test fun signalsLiveRefreshWaitsExistingReadThenFreshRead() = runTest {
        val client = LiveFeatureClient(profile)
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        client.signalGates.addAll(listOf(first, second))
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.signalActions.refresh()
        runCurrent()
        val refresh = launch { coordinator.refreshLive(LiveRefreshTarget.SIGNALS) }
        runCurrent()
        assertEquals(1, client.signalReads)
        assertFalse(refresh.isCompleted)
        first.complete(Unit); runCurrent()
        assertEquals(2, client.signalReads)
        assertFalse(refresh.isCompleted)
        second.complete(Unit); runCurrent()
        assertTrue(refresh.isCompleted)
        coordinator.invalidate()
    }

    @Test fun strategyRefreshWaitsExistingJobAndScopeChangePreventsFreshRequest() = runTest {
        val client = LiveFeatureClient(profile)
        val first = CompletableDeferred<Unit>()
        client.strategyGate = first
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.systemActions.refresh(SystemFeed.STRATEGIES)
        runCurrent()
        val refresh = launch { coordinator.refreshLive(LiveRefreshTarget.STRATEGIES) }
        runCurrent()
        client.user.value = profile.copy(primaryOperatorPublicId = "new-desk")
        first.complete(Unit); runCurrent()
        assertTrue(refresh.isCompleted)
        assertEquals(1, client.strategyReads)
        assertNull(state.system.strategies.value)
        coordinator.invalidate()
    }

    @Test fun marketContextPreservesTimeframeChangesAndCrossVenueSelectionUsesFreshCatalog() = runTest {
        val client = LiveFeatureClient(profile)
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.selectMarketContext("old", "BTC"); runCurrent()
        coordinator.catalogActions.selectTimeframe("5m")
        coordinator.selectMarketContext("old", "BTC"); runCurrent()
        assertEquals(1, client.metricReads)
        coordinator.marketContextActions.selectRelated(client.peer)
        runCurrent()
        assertEquals("new", state.marketCatalog.exchange)
        assertEquals("ETH", state.marketCatalog.instrument)
        assertEquals("5m", state.marketCatalog.timeframe)
        assertEquals("ETH", state.marketContext.selection?.symbol)
        assertEquals(1, client.exchangeReads)
        assertEquals(1, client.instrumentReads)
        assertEquals(2, client.metricReads)
        coordinator.invalidate()
    }

    @Test fun unavailableRelatedTargetNeverFallsBackToDifferentCatalogSymbol() = runTest {
        val client = LiveFeatureClient(profile).apply { available = false }
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.selectMarketContext("old", "BTC"); runCurrent()
        coordinator.marketContextActions.selectRelated(client.peer); runCurrent()
        assertEquals("old", state.marketCatalog.exchange)
        assertEquals("BTC", state.marketCatalog.instrument)
        assertNotNull(state.marketCatalog.error)
        assertFalse(state.marketCatalog.isLoading)
        client.user.value = profile.copy(delegatePublicId = "other")
        coordinator.marketContextActions.selectRelated(client.peer); runCurrent()
        assertEquals(1, client.instrumentReads)
        coordinator.invalidate()
    }

    @Test fun marketObserverDropsLateResponseAfterDelegateScopeChange() = runTest {
        val client = LiveFeatureClient(profile)
        val gate = CompletableDeferred<Unit>()
        client.relatedGate = gate
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.selectMarketContext("old", "BTC"); runCurrent()
        assertTrue(state.marketContext.relatedLoading)
        val next = profile.copy(delegatePublicId = "next")
        client.user.value = next
        state = state.copy(user = next)
        gate.complete(Unit); runCurrent()
        assertNull(state.marketContext.related)
        coordinator.selectMarketContext("old", "BTC"); runCurrent()
        assertNotNull(state.marketContext.related)
        assertEquals(2, client.metricReads)
        coordinator.invalidate()
    }

    @Test fun actionsLiveRefreshWaitsOldAndNewProcessReads() = runTest {
        val client = LiveFeatureClient(profile)
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        client.processGates.addAll(listOf(first, second))
        var state = initial()
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.loadProcesses(); runCurrent()
        val refresh = launch { coordinator.refreshLive(LiveRefreshTarget.PROCESSES) }
        runCurrent()
        assertEquals(1, client.processReads)
        first.complete(Unit); runCurrent()
        assertEquals(2, client.processReads)
        assertFalse(refresh.isCompleted)
        second.complete(Unit); runCurrent()
        assertTrue(refresh.isCompleted)
        coordinator.invalidate()
    }

    @Test fun aiRefreshAwaitsInitialAndFreshAuditRatherThanReturningAtLaunch() = runTest {
        val client = LiveFeatureClient(profile)
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        client.auditGates.addAll(listOf(first, second))
        var state = initial()
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.loadAiReviews(); runCurrent()
        val refresh = launch { coordinator.refreshLive(LiveRefreshTarget.AI_REVIEWS) }
        runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals(2, client.auditReads)
        assertFalse(refresh.isCompleted)
        second.complete(Unit); runCurrent()
        assertTrue(refresh.isCompleted)
        coordinator.invalidate()
    }
}

private class LiveFeatureClient(profile: UserDto) : SessionClient, SignalsClient, MarketContextClient, MarketCatalogClient, SystemClient, ProcessClient, AiReviewsClient {
    override val user = MutableStateFlow<UserDto?>(profile)
    var signalReads = 0
    val signalGates = mutableListOf<CompletableDeferred<Unit>>()
    override suspend fun signals(walletPublicId: String?): List<SignalDto> {
        val index = signalReads++; signalGates.getOrNull(index)?.await(); return emptyList()
    }
    var strategyReads = 0
    var strategyGate: CompletableDeferred<Unit>? = null
    override suspend fun systemStrategies(): List<SystemStrategyProcessDto> { strategyReads++; strategyGate?.await(); return emptyList() }
    override suspend fun systemHealth(): SystemHealthCheckDataDto = error("Unexpected health")
    override suspend fun systemStatus(): SystemSystemStatusDataDto = error("Unexpected status")
    override suspend fun systemBacktests(): List<SystemBacktestRunDataDto> = error("Unexpected backtests")
    override suspend fun systemUsers(): List<SystemUserProfileDto> = error("Unexpected users")
    override suspend fun systemDelegates(): List<SystemDelegateReadDto> = error("Unexpected delegates")
    var metricReads = 0
    var exchangeReads = 0
    var instrumentReads = 0
    var available = true
    var relatedGate: CompletableDeferred<Unit>? = null
    val peer = RelatedMarketInstrumentDto("peer", "ETH", "new", "crypto", "proxy", isSelected = false)
    override suspend fun cachedHourly(exchange: String, symbol: String): MarketCachedCandlesDto {
        metricReads++; return MarketCachedCandlesDto(emptyList(), 0, false, "db")
    }
    override suspend fun related(exchange: String, symbol: String): RelatedMarketPayloadDto {
        relatedGate?.await()
        return RelatedMarketPayloadDto(RelatedMarketSelectionDto(exchange, symbol),
            groups = listOf(RelatedMarketGroupDto("proxy", "Proxies", listOf(peer))))
    }
    override suspend fun marketExchanges(): List<String> { exchangeReads++; return listOf("old", "new") }
    override suspend fun marketInstruments(exchange: String): List<InstrumentDto> {
        instrumentReads++
        return listOf(InstrumentDto("id", "symbol", if (available) "ETH" else "DIFFERENT", exchange, false, true, true))
    }
    var processReads = 0
    val processGates = mutableListOf<CompletableDeferred<Unit>>()
    override suspend fun configuredProcesses(): List<ConfiguredProcessDto> { val index = processReads++; processGates.getOrNull(index)?.await(); return emptyList() }
    override suspend fun processSummary() = ProcessSummaryDto(ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0), emptyList())
    override suspend fun startProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStartDto = error("Unexpected mutation")
    override suspend fun stopProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStopDto = error("Unexpected mutation")
    override suspend fun processDesiredState(scope: OrderScope, target: ConfiguredProcessDto, payload: ProcessDesiredStatePayload): ProcessDesiredStateDto = error("Unexpected mutation")
    var auditReads = 0
    val auditGates = mutableListOf<CompletableDeferred<Unit>>()
    override suspend fun auditReviews(): List<AiAuditReviewDto> { val index = auditReads++; auditGates.getOrNull(index)?.await(); return emptyList() }
    override suspend fun pendingReviews(): List<AiPendingReviewDto> = emptyList()
    override suspend fun submitDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse = error("Unexpected mutation")
    override suspend fun restore() = user.value
    override suspend fun login(username: String, password: String, rememberMe: Boolean): UserDto = error("Unexpected login")
    override suspend fun selectWallet(publicId: String?): UserDto = error("Unexpected wallet mutation")
    override suspend fun logout() { user.value = null }
    override suspend fun snapshot(): DashboardSnapshot = error("Unexpected snapshot")
    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> = error("Unexpected candles")
    override suspend fun orderExchanges(scope: OrderScope): List<String> = error("Unexpected catalogue")
    override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = error("Unexpected catalogue")
    override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = error("Unexpected mutation")
    override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = error("Unexpected mutation")
    override fun close() = Unit
}
