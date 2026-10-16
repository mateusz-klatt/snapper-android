package ch.snapper.android

import ch.snapper.android.data.*
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioFeatureCoordinatorTest {
    private val profile = UserDto("reader", "viewer", "u", activeWalletPublicId = "w", effectivePermissions = listOf("read:account_state", "read:positions", "read:signals"))
    private fun initial() = AppUiState(user = profile, snapshot = DashboardSnapshot(profile, listOf(WalletDto("w", "Paper", isPaper = true)), emptyList(), emptyList(), emptyList()))

    @Test fun accountsPollOnlyWhileVisibleAndForegroundAndStopAfterLogout() = runTest {
        val client = PortfolioClient(profile)
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.setAccountsVisible(true)
        runCurrent()
        assertEquals(0, client.accountCalls)
        coordinator.setForeground(true)
        runCurrent()
        assertEquals(1, client.accountCalls)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(2, client.accountCalls)
        coordinator.setForeground(false)
        advanceTimeBy(10000)
        runCurrent()
        assertEquals(2, client.accountCalls)
        coordinator.setForeground(true)
        runCurrent()
        assertEquals(3, client.accountCalls)
        coordinator.setAccountsVisible(false)
        advanceTimeBy(10000)
        runCurrent()
        assertEquals(3, client.accountCalls)
        coordinator.setAccountsVisible(true)
        runCurrent()
        assertEquals(4, client.accountCalls)
        coordinator.invalidate()
        client.user.value = null
        state = state.copy(user = null)
        advanceTimeBy(10000)
        runCurrent()
        assertEquals(4, client.accountCalls)
        assertTrue(state.venueAccounts.accounts.isEmpty())
    }

    @Test fun signalAndPnlActionsUpdateObservedFeatureState() = runTest {
        val client = PortfolioClient(profile)
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.signalActions.refresh()
        coordinator.pnlActions.refresh()
        runCurrent()
        assertEquals("w", client.signalWallet)
        assertEquals("signal", state.signals.rows.single().publicId)
        coordinator.signalActions.selectStrategy("demo")
        coordinator.pnlActions.currency("EUR")
        runCurrent()
        assertEquals("demo", state.signals.selectedStrategy)
        assertEquals("EUR", state.pnl.data?.valuation_ccy)
        assertEquals("paper", client.pnlQueries.last().mode)
        assertFalse(state.pnl.loading)
        coordinator.invalidate()
    }

    @Test fun latePortfolioResponsesCannotRepopulateAfterScopeInvalidation() = runTest {
        val client = PortfolioClient(profile)
        lateinit var signals: Continuation<List<SignalDto>>
        lateinit var pnl: Continuation<PnlSeriesDto>
        client.signalsAction = { suspendCoroutine { signals = it } }
        client.pnlAction = { suspendCoroutine { pnl = it } }
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.signalActions.refresh()
        coordinator.pnlActions.refresh()
        runCurrent()
        val query = client.pnlQueries.single()
        coordinator.invalidate()
        val next = profile.copy(activeWalletPublicId = "other")
        client.user.value = next
        state = state.copy(user = next)
        signals.resume(listOf(client.signal))
        pnl.resume(client.series(query))
        runCurrent()
        assertTrue(state.signals.rows.isEmpty())
        assertNull(state.pnl.data)
        assertFalse(state.pnl.loading)
    }
    @Test fun lateAccountReadCannotRestoreFreshnessAfterBackgrounding() = runTest {
        val client = PortfolioClient(profile)
        lateinit var pending: Continuation<List<VenueAccountDto>>
        client.accountsAction = { suspendCoroutine { pending = it } }
        var state = initial()
        val coordinator = FeatureCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.setForeground(true)
        coordinator.setAccountsVisible(true)
        runCurrent()
        assertTrue(state.venueAccounts.loading)
        coordinator.setForeground(false)
        pending.resume(emptyList())
        runCurrent()
        assertNull(state.venueAccounts.lastSuccessfulFetch)
        assertFalse(state.venueAccounts.loading)
        coordinator.invalidate()
    }

}

private class PortfolioClient(profile: UserDto) : SessionClient, SignalsClient, PnlClient, VenueAccountsClient {
    override val user = MutableStateFlow<UserDto?>(profile)
    var accountsAction: suspend () -> List<VenueAccountDto> = { emptyList() }
    var accountCalls = 0
    var signalWallet: String? = null
    val pnlQueries = mutableListOf<PnlSeriesQuery>()
    val signal = SignalDto("signal", "BTC-USD", "venue", "buy", BigDecimal.ONE, "demo", "2026-10-16T00:00:00Z", strategyName = "demo", walletPublicId = "w")
    var signalsAction: suspend () -> List<SignalDto> = { listOf(signal) }
    var pnlAction: suspend (PnlSeriesQuery) -> PnlSeriesDto = { series(it) }
    override suspend fun signals(walletPublicId: String?): List<SignalDto> { signalWallet = walletPublicId; return signalsAction() }
    override suspend fun venueAccounts(walletPublicId: String?): List<VenueAccountDto> { accountCalls++; return accountsAction() }
    override suspend fun pnlSeries(query: PnlSeriesQuery): PnlSeriesDto { pnlQueries += query; return pnlAction(query) }
    fun series(query: PnlSeriesQuery) = PnlSeriesDto(query.walletPublicId, query.mode, query.granularity, query.valuationCcy,
        query.from.toString(), query.to.toString(), query.to.toString(), "mark", emptyList(), "v1",
        PnlEquityCoverageDto(false, null, null, 0, null, null, null, null, null, null, 0, null),
        PnlExecutionHistoryDto("as_recorded", emptyList()), emptyList())
    override suspend fun restore() = user.value
    override suspend fun login(username: String, password: String, rememberMe: Boolean): UserDto = error("Unexpected login")
    override suspend fun selectWallet(publicId: String?): UserDto = error("Unexpected wallet mutation")
    override suspend fun logout() { user.value = null }
    override suspend fun snapshot(): DashboardSnapshot = error("Unexpected snapshot")
    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> = error("Unexpected candles")
    override suspend fun orderExchanges(scope: OrderScope): List<String> = error("Unexpected order catalogue")
    override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = error("Unexpected order catalogue")
    override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = error("Unexpected mutation")
    override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = error("Unexpected mutation")
    override fun close() = Unit
}
