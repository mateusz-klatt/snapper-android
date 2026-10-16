package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.live.LiveRefreshTarget
import ch.snapper.android.features.system.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSystemRefreshCoordinatorTest {
    private val profile = UserDto("reader", "viewer", "u", effectivePermissions = listOf("read:system_status", "read:strategies"))

    @Test fun systemRefreshJoinsExistingHealthThenAwaitsFreshHealthAndStatus() = runTest(StandardTestDispatcher()) {
        val client = SystemRefreshClient(profile)
        val old = CompletableDeferred<Unit>(); val fresh = CompletableDeferred<Unit>(); val status = CompletableDeferred<Unit>()
        client.healthGates.addAll(listOf(old, fresh)); client.statusGate = status
        var state = AppUiState(user = profile)
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.systemActions.refresh(SystemFeed.HEALTH); runCurrent()
        val refresh = launch { coordinator.refreshLive(LiveRefreshTarget.SYSTEM) }; runCurrent()
        assertEquals(1, client.healthReads); assertEquals(0, client.statusReads)
        old.complete(Unit); runCurrent()
        assertEquals(2, client.healthReads); assertFalse(refresh.isCompleted)
        fresh.complete(Unit); runCurrent()
        assertEquals(1, client.statusReads); assertFalse(refresh.isCompleted)
        status.complete(Unit); runCurrent()
        assertTrue(refresh.isCompleted); assertEquals("health-2", state.system.health.value?.publicId)
        assertEquals("status", state.system.status.value?.publicId)
        coordinator.invalidate()
    }

    @Test fun strategiesRefreshWaitsForBothOldAndFreshRead() = runTest(StandardTestDispatcher()) {
        val client = SystemRefreshClient(profile)
        val old = CompletableDeferred<Unit>(); val fresh = CompletableDeferred<Unit>()
        client.strategyGates.addAll(listOf(old, fresh))
        var state = AppUiState(user = profile)
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.systemActions.refresh(SystemFeed.STRATEGIES); runCurrent()
        val refresh = launch { coordinator.refreshLive(LiveRefreshTarget.STRATEGIES) }; runCurrent()
        assertEquals(1, client.strategyReads)
        old.complete(Unit); runCurrent()
        assertEquals(2, client.strategyReads); assertFalse(refresh.isCompleted)
        fresh.complete(Unit); runCurrent()
        assertTrue(refresh.isCompleted); assertEquals(emptyList<SystemStrategyProcessDto>(), state.system.strategies.value)
        assertEquals(0, client.healthReads); assertEquals(0, client.statusReads)
        coordinator.invalidate()
    }

    @Test fun healthFailureDoesNotPreventStatusAndLaterStatusFailurePreservesHealth() = runTest(StandardTestDispatcher()) {
        val client = SystemRefreshClient(profile)
        client.healthFails = true
        var state = AppUiState(user = profile)
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        val first = launch { coordinator.refreshLive(LiveRefreshTarget.SYSTEM) }; runCurrent()
        assertTrue(first.isCompleted); assertEquals(UiError.NETWORK, state.system.health.error)
        assertNotNull(state.system.status.value); assertNull(state.system.status.error)
        client.healthFails = false; client.statusFails = true
        val next = launch { coordinator.refreshLive(LiveRefreshTarget.SYSTEM) }; runCurrent()
        assertTrue(next.isCompleted); assertNotNull(state.system.health.value); assertNull(state.system.health.error)
        assertEquals(UiError.NETWORK, state.system.status.error)
        assertEquals("status", state.system.status.value?.publicId)
        coordinator.invalidate()
    }

    @Test fun actorRebindDropsHeldHealthAndPreventsSubsequentStatusRead() = runTest(StandardTestDispatcher()) {
        val client = SystemRefreshClient(profile)
        val held = CompletableDeferred<Unit>(); client.healthGates.add(held)
        var state = AppUiState(user = profile)
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        val refresh = launch { coordinator.refreshLive(LiveRefreshTarget.SYSTEM) }; runCurrent()
        val next = profile.copy(delegatePublicId = "other")
        client.user.value = next; state = state.copy(user = next)
        held.complete(Unit); runCurrent()
        assertTrue(refresh.isCompleted); assertNull(state.system.health.value)
        assertEquals(0, client.statusReads)
        coordinator.invalidate()
    }
}

private class SystemRefreshClient(profile: UserDto) : SessionClient, SystemClient {
    override val user = MutableStateFlow<UserDto?>(profile)
    var healthReads = 0; var statusReads = 0; var strategyReads = 0
    val healthGates = mutableListOf<CompletableDeferred<Unit>>()
    val strategyGates = mutableListOf<CompletableDeferred<Unit>>()
    var statusGate: CompletableDeferred<Unit>? = null
    var healthFails = false; var statusFails = false
    override suspend fun systemHealth(): SystemHealthCheckDataDto {
        val index = healthReads++; healthGates.getOrNull(index)?.await()
        if (healthFails) throw IOException("Synthetic health failure")
        return SystemHealthCheckDataDto("health-${index + 1}", "ok", "test", SystemConnectionStatsDto(),
            SystemHealthTopicsDto(0), SystemGapDetectionStatsDto(SystemGapStatsDto()))
    }
    override suspend fun systemStatus(): SystemSystemStatusDataDto {
        statusReads++; statusGate?.await()
        if (statusFails) throw IOException("Synthetic status failure")
        return SystemSystemStatusDataDto("status", SystemProcessStatusDto("stopped"), emptyMap())
    }
    override suspend fun systemStrategies(): List<SystemStrategyProcessDto> {
        val index = strategyReads++; strategyGates.getOrNull(index)?.await(); return emptyList()
    }
    override suspend fun systemBacktests(): List<SystemBacktestRunDataDto> = error("Unexpected backtests")
    override suspend fun systemUsers(): List<SystemUserProfileDto> = error("Unexpected users")
    override suspend fun systemDelegates(): List<SystemDelegateReadDto> = error("Unexpected delegates")
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
