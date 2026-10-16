package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.market.*
import ch.snapper.android.features.system.*
import java.io.IOException
import java.math.BigDecimal
import ch.snapper.android.features.records.OrderSegment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureCoordinatorTest {
    private fun viewer(permissions: List<String> = listOf("read:market_data")) =
        UserDto(username = "viewer", role = "viewer", publicId = "viewer-id", effectivePermissions = permissions)

    private fun instrument(symbol: String, exchange: String) =
        InstrumentDto(symbol, symbol, symbol, exchange, false, true, canMarketData = true)

    @Test fun viewerWithoutWalletCanLoadCatalogButCannotLoadAdministrativeFeed() = runTest {
        val client = FeatureClient(viewer())
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.loadCatalog()
        coordinator.systemActions.refresh(SystemFeed.USERS)
        runCurrent()
        assertEquals(1, client.exchangeCalls)
        assertEquals(0, client.userCalls)
        assertEquals("BTC-USD", state.marketCatalog.instrument)
        assertTrue(state.marketCatalog.canLoad)
        assertNull(state.user?.activeWalletPublicId)
    }

    @Test fun missingMarketPermissionNeverDispatchesCatalog() = runTest {
        val client = FeatureClient(viewer(emptyList()))
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.loadCatalog()
        runCurrent()
        assertEquals(0, client.exchangeCalls)
        assertFalse(state.marketCatalog.isLoading)
    }

    @Test fun oldCatalogResponseCannotCommitAfterInvalidateEvenWhenTransportIgnoresCancellation() = runTest {
        val client = FeatureClient(viewer())
        lateinit var pending: Continuation<List<String>>
        client.exchangesAction = { suspendCoroutine { pending = it } }
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.loadCatalog()
        runCurrent()
        coordinator.invalidate()
        state = state.copy(user = null)
        client.user.value = null
        pending.resume(listOf("private-venue"))
        runCurrent()
        assertEquals(MarketCatalogState(), state.marketCatalog)
        assertEquals(SystemState(), state.system)
    }

    @Test fun replacingSessionWithIdenticalUserStillRejectsOldResponse() = runTest {
        val first = FeatureClient(viewer())
        lateinit var pending: Continuation<List<String>>
        first.exchangesAction = { suspendCoroutine { pending = it } }
        var client: SessionClient = first
        var state = AppUiState(user = first.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.loadCatalog()
        runCurrent()
        client = FeatureClient(viewer())
        pending.resume(listOf("old-session"))
        runCurrent()
        assertTrue(state.marketCatalog.exchanges.isEmpty())
    }

    @Test fun returningToSameExchangeDoesNotAcceptEarlierRequest() = runTest {
        val client = FeatureClient(viewer())
        val pending = mutableListOf<Continuation<List<InstrumentDto>>>()
        client.instrumentsAction = { suspendCoroutine { pending += it } }
        var state = AppUiState(user = client.user.value, marketCatalog = MarketCatalogState(exchanges = listOf("A", "B"), exchange = "A"))
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.catalogActions.selectExchange("A")
        runCurrent()
        coordinator.catalogActions.selectExchange("B")
        runCurrent()
        coordinator.catalogActions.selectExchange("A")
        runCurrent()
        pending[2].resume(listOf(instrument("CURRENT", "A")))
        runCurrent()
        pending[0].resume(listOf(instrument("STALE", "A")))
        pending[1].resume(listOf(instrument("OTHER", "B")))
        runCurrent()
        assertEquals("CURRENT", state.marketCatalog.instrument)
        assertEquals("A", state.marketCatalog.exchange)
    }

    @Test fun walletChangeInvalidatesCatalogResultsAndClearsFeatureState() = runTest {
        val client = FeatureClient(viewer())
        lateinit var pending: Continuation<List<String>>
        client.exchangesAction = { suspendCoroutine { pending = it } }
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.loadCatalog()
        runCurrent()
        val changed = requireNotNull(client.user.value).copy(activeWalletPublicId = "new-wallet")
        client.user.value = changed
        state = state.copy(user = changed)
        coordinator.invalidate()
        pending.resume(listOf("old-wallet"))
        runCurrent()
        assertEquals(MarketCatalogState(), state.marketCatalog)
        assertEquals(SystemState(), state.system)
    }

    @Test fun oneSystemFeedFailurePreservesOtherFeedAndPreviouslyLoadedValue() = runTest {
        val client = FeatureClient(viewer(listOf("read:system_status", "read:strategies")))
        val cached = listOf(SystemStrategyProcessDto("old", "Cached strategy", true, true, "paper"))
        client.strategiesAction = { throw IOException("offline") }
        var state = AppUiState(user = client.user.value, system = SystemState(strategies = SystemFeedState(cached)))
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.systemActions.refresh(SystemFeed.STRATEGIES)
        coordinator.systemActions.refresh(SystemFeed.STATUS)
        runCurrent()
        assertEquals(cached, state.system.strategies.value)
        assertEquals(UiError.NETWORK, state.system.strategies.error)
        assertFalse(state.system.strategies.isLoading)
        assertEquals("ready", state.system.status.value?.trader?.status)
        assertNull(state.system.status.error)
    }
    @Test fun executionsRefreshRetainsDataOnFailureAndUsesActiveWallet() = runTest {
        val client = FeatureClient(viewer(listOf("read:orders")).copy(activeWalletPublicId = "wallet"))
        val fill = ExecutionDto("fill", "BTC-USD", "kraken", "buy", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, "USD", "filled", "2026-10-16T00:00:00Z", "wallet")
        client.executionsAction = { listOf(fill) }
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.recordsActions.selectSegment(OrderSegment.FILLS)
        runCurrent()
        assertEquals("wallet", client.executionWallet)
        assertEquals(listOf(fill), state.records.executions)
        client.executionsAction = { throw IOException("offline") }
        coordinator.recordsActions.refreshExecutions()
        runCurrent()
        assertEquals(listOf(fill), state.records.executions)
        assertEquals(UiError.NETWORK, state.records.error)
        assertFalse(state.records.isLoading)
    }

    @Test fun executionsResultAfterLogoutCannotRestoreRows() = runTest {
        val client = FeatureClient(viewer(listOf("read:orders")))
        lateinit var pending: Continuation<List<ExecutionDto>>
        client.executionsAction = { suspendCoroutine { pending = it } }
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.recordsActions.refreshExecutions()
        runCurrent()
        coordinator.invalidate()
        client.user.value = null
        state = state.copy(user = null)
        pending.resume(emptyList())
        runCurrent()
        assertTrue(state.records.executions.isEmpty())
        assertFalse(state.records.isLoading)
    }

    @Test fun allSystemFeedsCommitTheirOwnTypedResults() = runTest {
        val client = FeatureClient(viewer(SystemFeed.entries.map { it.permission }.distinct()))
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        SystemFeed.entries.forEach(coordinator.systemActions::refresh)
        runCurrent()
        assertEquals("healthy", state.system.health.value?.status)
        assertEquals("ready", state.system.status.value?.trader?.status)
        assertEquals(emptyList<SystemStrategyProcessDto>(), state.system.strategies.value)
        assertEquals(emptyList<SystemBacktestRunDataDto>(), state.system.backtests.value)
        assertEquals(emptyList<SystemUserProfileDto>(), state.system.users.value)
        assertEquals(emptyList<SystemDelegateReadDto>(), state.system.delegates.value)
        assertEquals(1, client.userCalls)
    }

    @Test fun notificationActionsReachControllerAndSaveObservedPreference() = runTest {
        val client = FeatureClient(viewer())
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.notificationActions.refresh()
        runCurrent()
        assertTrue(state.notificationDefaults.loaded)
        coordinator.notificationActions.setEnabled("order_fill_full", false)
        coordinator.notificationActions.setPriority("order_fill_full", "high")
        runCurrent()
        coordinator.notificationActions.save("order_fill_full")
        runCurrent()
        val row = state.notificationDefaults.rows.first { it.alertType == "order_fill_full" }
        assertTrue(row.saved)
        assertFalse(row.enabled)
        assertEquals("high", row.minPriority)
        assertEquals(UpdateNotificationDefaultPayload("order_fill_full", false, "high"), client.savedPreference)
        coordinator.invalidate()
        runCurrent()
        assertFalse(state.notificationDefaults.active)
    }

    @Test fun deskCallbacksLoadSelectAndAttachUsingCurrentPrincipal() = runTest {
        val client = FeatureClient(viewer(listOf("manage:desk_memberships")))
        var state = AppUiState(user = client.user.value)
        val coordinator = FeatureCoordinator(this, { client }, { state }, { state = it(state) })
        coordinator.deskActions.reload()
        runCurrent()
        assertTrue(state.desks.loaded)
        coordinator.deskActions.selectDesk("desk-b")
        coordinator.deskActions.setUsername(" reader ")
        runCurrent()
        coordinator.deskActions.attach()
        runCurrent()
        assertEquals("desk-b" to "reader", client.attached)
        assertTrue(state.desks.succeeded)
        coordinator.invalidate()
        runCurrent()
        assertTrue(state.desks.desks.isEmpty())
        assertFalse(state.desks.canManage)
    }

}

private class FeatureClient(profile: UserDto) : SessionClient, MarketCatalogClient, SystemClient, RecordsClient, NotificationDefaultsClient, DeskClient {
    override val user = MutableStateFlow<UserDto?>(profile)
    var executionWallet: String? = null
    var executionsAction: suspend () -> List<ExecutionDto> = { emptyList() }
    var savedPreference: UpdateNotificationDefaultPayload? = null
    var attached: Pair<String, String>? = null
    override suspend fun executions(walletPublicId: String?): List<ExecutionDto> {
        executionWallet = walletPublicId
        return executionsAction()
    }
    override suspend fun notificationDefaults(): List<NotificationDefaultDto> = emptyList()
    override suspend fun updateNotificationDefaults(scope: OrderScope, payload: UpdateNotificationDefaultPayload): NotificationDefaultDto {
        savedPreference = payload
        return NotificationDefaultDto("preference", scope.publicId, payload.alertType, payload.enabled, payload.minPriority)
    }
    override suspend fun desks() = listOf(DeskDto("desk-a", "A"), DeskDto("desk-b", "B"))
    override suspend fun attachViewer(scope: OrderScope, operatorPublicId: String, username: String) {
        check(scope.publicId == user.value?.publicId)
        attached = operatorPublicId to username
    }
    var exchangeCalls = 0
    var userCalls = 0
    var exchangesAction: suspend () -> List<String> = { listOf("kraken") }
    var instrumentsAction: suspend (String) -> List<InstrumentDto> = { exchange ->
        listOf(InstrumentDto("btc", "btc-symbol", "BTC-USD", exchange, false, true, canMarketData = true))
    }
    var strategiesAction: suspend () -> List<SystemStrategyProcessDto> = { emptyList() }
    override suspend fun marketExchanges(): List<String> { exchangeCalls++; return exchangesAction() }
    override suspend fun marketInstruments(exchange: String) = instrumentsAction(exchange)
    override suspend fun systemStrategies() = strategiesAction()
    override suspend fun systemStatus() = SystemSystemStatusDataDto("status", SystemProcessStatusDto("ready"), emptyMap())
    override suspend fun systemUsers(): List<SystemUserProfileDto> { userCalls++; return emptyList() }
    override suspend fun systemBacktests(): List<SystemBacktestRunDataDto> = emptyList()
    override suspend fun systemDelegates(): List<SystemDelegateReadDto> = emptyList()
    override suspend fun systemHealth() = SystemHealthCheckDataDto("health", "healthy", "1", SystemConnectionStatsDto(), SystemHealthTopicsDto(0), SystemGapDetectionStatsDto(SystemGapStatsDto()))
    override suspend fun restore() = user.value
    override suspend fun login(username: String, password: String, rememberMe: Boolean): UserDto = error("Unexpected login")
    override suspend fun selectWallet(publicId: String?): UserDto = error("Unexpected wallet mutation")
    override suspend fun logout() { user.value = null }
    override suspend fun snapshot(): DashboardSnapshot = error("Unexpected snapshot")
    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> = error("Unexpected candles")
    override suspend fun orderExchanges(scope: OrderScope): List<String> = error("Market viewer must not use order catalog")
    override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = error("Market viewer must not use order catalog")
    override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = error("Unexpected mutation")
    override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = error("Unexpected mutation")
    override fun close() = Unit
}
