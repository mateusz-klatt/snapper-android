package ch.snapper.android

import androidx.lifecycle.ViewModelStore
import ch.snapper.android.data.CandleDto
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.SessionClient
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.WalletDto
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SnapperViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val settings = FakeSettingsStore()
    private val client = FakeSessionClient()
    private val origins = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    private fun model(defaultOrigin: String = "https://snapper.example"): SnapperViewModel =
        SnapperViewModel(settings, { origin -> origins.add(origin); client }, defaultOrigin, false)
            .also { models.put("snapper", it) }

    @Test
    fun blankInitialOriginDoesNotCreateSession() = runTest(dispatcher) {
        val model = model("")
        advanceUntilIdle()
        assertTrue(origins.isEmpty())
        assertEquals("", model.state.value.backendUrl)
        assertFalse(model.state.value.isRestoring)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun rejectedCredentialsDoNotExposeAuthenticatedData() = runTest(dispatcher) {
        client.loginAction = { throw HttpException(Response.error<Unit>(401, "unauthorized".toResponseBody())) }
        val model = model()
        advanceUntilIdle()
        model.login("alice", "wrong")
        advanceUntilIdle()
        assertEquals(UiError.INVALID_CREDENTIALS, model.state.value.error)
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertEquals(0, client.snapshotCalls)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun logoutImmediatelyClearsDashboardAndMarketBeforeServerResponds() = runTest(dispatcher) {
        client.restoredUser = profile()
        client.logoutAction = { delay(1_000) }
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        advanceUntilIdle()
        assertTrue(model.state.value.market.candles.isNotEmpty())
        model.logout()
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertTrue(model.state.value.market.candles.isEmpty())
        assertFalse(client.closed)
        advanceUntilIdle()
        assertTrue(client.closed)
        assertEquals(1, client.logoutCalls)
    }

    @Test
    fun loginCompletingAfterLogoutCannotRestoreTheSession() = runTest(dispatcher) {
        client.loginAction = { withContext(NonCancellable) { delay(1_000); profile() } }
        val model = model()
        advanceUntilIdle()
        model.login("alice", "password")
        runCurrent()
        model.logout()
        advanceUntilIdle()
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertEquals(0, client.snapshotCalls)
        assertTrue(client.closed)
    }

    @Test
    fun walletChangeHidesOldRowsUntilServerConfirmsSelection() = runTest(dispatcher) {
        client.restoredUser = profile()
        client.selectionAction = { wallet -> delay(1_000); profile().copy(activeWalletPublicId = wallet) }
        val model = model()
        advanceUntilIdle()
        assertEquals("wallet-a", model.state.value.snapshot?.wallets?.single()?.publicId)
        model.selectWallet("wallet-b")
        assertNull(model.state.value.snapshot)
        runCurrent()
        assertEquals(1, client.snapshotCalls)
        assertEquals("wallet-a", model.state.value.user?.activeWalletPublicId)
        advanceUntilIdle()
        assertEquals("wallet-b", model.state.value.user?.activeWalletPublicId)
        assertEquals("wallet-b", model.state.value.snapshot?.wallets?.single()?.publicId)
        assertEquals(2, client.snapshotCalls)
    }

    @Test
    fun invalidBackendPreservesSessionAndValidBackendRetiresItAndPersists() = runTest(dispatcher) {
        client.restoredUser = profile()
        val model = model()
        advanceUntilIdle()
        val snapshot = model.state.value.snapshot
        model.saveBackend("http://remote.example")
        assertEquals(UiError.INVALID_ORIGIN, model.state.value.error)
        assertEquals(snapshot, model.state.value.snapshot)
        assertEquals(profile(), model.state.value.user)
        assertEquals(0, client.logoutCalls)
        model.saveBackend("https://other.example/")
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        advanceUntilIdle()
        assertEquals("https://other.example", model.state.value.backendUrl)
        assertEquals("https://other.example", settings.preferences.value.backendUrl)
        assertEquals(1, client.logoutCalls)
        assertTrue(client.closed)
    }

    @Test
    fun lateCandlesCannotOverwriteTheCurrentQuery() = runTest(dispatcher) {
        client.restoredUser = profile()
        client.candleAction = { instrument ->
            withContext(NonCancellable) {
                delay(if (instrument == "BTC-USD") 1_000 else 100)
                listOf(candle(instrument))
            }
        }
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        runCurrent()
        model.loadMarket("kraken", "ETH-USD", "1h")
        advanceUntilIdle()
        assertEquals(listOf("BTC-USD", "ETH-USD"), client.candleRequests)
        assertEquals("ETH-USD", model.state.value.market.instrument)
        assertEquals("ETH-USD", model.state.value.market.candles.single().instrument)
        assertFalse(model.state.value.market.isLoading)
    }

    @Test
    fun missingMarketPermissionPreventsFetchEvenForAdminRole() = runTest(dispatcher) {
        client.restoredUser = profile().copy(role = "admin", effectivePermissions = emptyList())
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        advanceUntilIdle()
        assertTrue(client.candleRequests.isEmpty())
        assertFalse(model.state.value.market.isLoading)
    }

    @Test
    fun revokedPermissionsClearOldRowsEvenWhenRemainingRefreshFails() = runTest(dispatcher) {
        client.restoredUser = profile()
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        advanceUntilIdle()
        assertTrue(requireNotNull(model.state.value.snapshot).orders.isNotEmpty())
        assertTrue(model.state.value.market.candles.isNotEmpty())
        val revoked = profile().copy(effectivePermissions = emptyList())
        client.beforeSnapshot = {
            client.user.value = revoked
            delay(100)
            throw IOException("Wallet fetch failed")
        }
        model.refresh()
        runCurrent()
        assertEquals(revoked, model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertTrue(model.state.value.market.candles.isEmpty())
        advanceUntilIdle()
        assertEquals(revoked, model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertEquals(UiError.NETWORK, model.state.value.error)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun sessionExpiryClearsVisibleDataAndRejectsInFlightCandles() = runTest(dispatcher) {
        client.restoredUser = profile()
        client.candleAction = { instrument ->
            withContext(NonCancellable) { delay(1_000); listOf(candle(instrument)) }
        }
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        runCurrent()
        client.user.value = null
        runCurrent()
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        advanceUntilIdle()
        assertTrue(model.state.value.market.candles.isEmpty())
    }
}

private class FakeSettingsStore : SettingsStore {
    override val preferences = MutableStateFlow(ClientPreferences(null))
    override suspend fun setBackendUrl(origin: String) {
        preferences.value = preferences.value.copy(backendUrl = origin)
    }
    override suspend fun setLanguage(language: String) {
        preferences.value = preferences.value.copy(language = language)
    }
}

private class FakeSessionClient : SessionClient {
    override val user = MutableStateFlow<UserDto?>(null)
    var restoredUser: UserDto? = null
    var loginAction: suspend () -> UserDto = { profile() }
    var selectionAction: suspend (String?) -> UserDto = { profile().copy(activeWalletPublicId = it) }
    var logoutAction: suspend () -> Unit = {}
    var candleAction: suspend (String) -> List<CandleDto> = { listOf(candle(it)) }
    var beforeSnapshot: suspend () -> Unit = {}
    var snapshotCalls = 0
    var logoutCalls = 0
    var closed = false
    val candleRequests = mutableListOf<String>()

    override suspend fun restore(): UserDto? = restoredUser.also { user.value = it }
    override suspend fun login(username: String, password: String, rememberMe: Boolean): UserDto =
        loginAction().also { user.value = it }
    override suspend fun selectWallet(publicId: String?): UserDto = selectionAction(publicId).also { user.value = it }
    override suspend fun logout() {
        logoutCalls++
        logoutAction()
        user.value = null
    }
    override suspend fun snapshot(): DashboardSnapshot {
        snapshotCalls++
        beforeSnapshot()
        val current = requireNotNull(user.value)
        val wallet = WalletDto(requireNotNull(current.activeWalletPublicId), "Selected wallet", isPaper = true)
        val order = OrderDto(
            "order-a", "client-order-a", "BTC-USD", "kraken", "BUY", "OPEN", "LIMIT",
            BigDecimal.ONE, BigDecimal.ZERO, "2026-10-15T00:00:00Z", walletPublicId = wallet.publicId,
        )
        return DashboardSnapshot(current, listOf(wallet), emptyList(), listOf(order), emptyList())
    }
    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> {
        candleRequests.add(instrument)
        return candleAction(instrument)
    }
    override fun close() {
        closed = true
    }
}

private fun profile() = UserDto(
    "alice", "viewer", activeWalletPublicId = "wallet-a", effectivePermissions = listOf("read:market_data"),
)

private fun candle(instrument: String) = CandleDto(
    instrument, "kraken", "1h", "2026-10-15T00:00:00Z",
    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
)
