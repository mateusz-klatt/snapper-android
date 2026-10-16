package ch.snapper.android

import androidx.lifecycle.ViewModelStore
import ch.snapper.android.data.*
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnapperViewModelLiveTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val client = Client()
    private val observed = Instant.now().minusSeconds(10)
    private val price = BigDecimal("123.123456789012345678901234567890")

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }

    private fun TestScope.start(): SnapperViewModel {
        val model = SnapperViewModel(Settings(), { client }, "https://example.test", false)
        models.put("live", model)
        runCurrent()
        model.setMarketVisible(true)
        model.selectMarket("venue", "BTC", "1m")
        model.onForeground()
        runCurrent()
        return model
    }

    @Test fun delayedAckStartsNoRestAndBufferedNewerCandleWinsHeldSnapshotExactly() = runTest(dispatcher) {
        val held = CompletableDeferred<List<CandleDto>>()
        client.candleRead = { held.await() }
        val model = start()
        assertEquals(0, client.candleReads)
        val socket = client.sockets.last()
        authenticate(socket)
        runCurrent()
        assertEquals(0, client.candleReads)
        acknowledge(socket); runCurrent()
        assertEquals(1, client.candleReads)
        socket.candle(observed, price); runCurrent()
        held.complete(listOf(row(BigDecimal.ONE))); runCurrent()
        assertFalse(model.state.value.market.isLoading)
        assertEquals(price, model.state.value.market.candles.single().close)
        assertEquals(BigDecimal("0.000000000000000001"), model.state.value.market.candles.single().volume)
        assertEquals(1, client.candleReads)
    }

    @Test fun backgroundRejectsLateRestAndSocketAndForegroundRequiresFreshAck() = runTest(dispatcher) {
        val held = CompletableDeferred<List<CandleDto>>()
        client.candleRead = { withContext(NonCancellable) { held.await() } }
        val model = start(); val socket = client.sockets.last()
        authenticate(socket); runCurrent(); acknowledge(socket); runCurrent()
        model.onBackground()
        assertEquals(ch.snapper.android.features.live.LiveConnectionStatus.STOPPED, model.state.value.market.liveStatus)
        socket.candle(observed, price)
        runCurrent()
        assertTrue(socket.cancelled)
        assertTrue(model.state.value.market.candles.isEmpty())
        held.complete(listOf(row(price))); runCurrent()
        assertTrue(model.state.value.market.candles.isEmpty())
        client.candleRead = { listOf(row(BigDecimal.ONE)) }
        model.onForeground(); runCurrent()
        assertEquals(1, client.candleReads)
        val fresh = client.sockets.last()
        authenticate(fresh); runCurrent(); acknowledge(fresh); runCurrent()
        assertEquals(2, client.candleReads)
        assertEquals(BigDecimal.ONE, model.state.value.market.candles.single().close)
    }

    @Test fun logoutRejectsNonCancellableReadAndOldSocketCallbacks() = runTest(dispatcher) {
        val held = CompletableDeferred<List<CandleDto>>()
        client.candleRead = { withContext(NonCancellable) { held.await() } }
        val model = start(); val socket = client.sockets.last()
        authenticate(socket); runCurrent(); acknowledge(socket); runCurrent()
        model.logout()
        assertNull(model.state.value.user)
        socket.candle(observed, price)
        held.complete(listOf(row(price))); runCurrent()
        assertTrue(model.state.value.market.candles.isEmpty())
        assertNull(model.state.value.snapshot)
        assertTrue(socket.cancelled)
        assertTrue(client.closed)
    }

    @Test fun permissionRevocationClearsMarketAndRejectsOldAcknowledgement() = runTest(dispatcher) {
        val model = start(); val socket = client.sockets.last()
        authenticate(socket); runCurrent()
        client.user.value = requireNotNull(client.user.value).copy(effectivePermissions = listOf("read:orders"))
        runCurrent()
        assertTrue(socket.cancelled)
        acknowledge(socket); socket.candle(observed, price); runCurrent()
        model.selectMarket("venue", "BTC", "1m"); runCurrent()
        assertEquals(0, client.candleReads)
        assertTrue(model.state.value.market.candles.isEmpty())
        assertFalse(requireNotNull(model.state.value.user).hasPermission("read:market_data"))
    }

    @Test fun orderEventsAwaitDashboardReadAndCoalesceOneFollowup() = runTest(dispatcher) {
        val model = start(); val socket = client.sockets.last()
        authenticate(socket); runCurrent(); acknowledge(socket); runCurrent()
        advanceTimeBy(200); runCurrent()
        val baseline = client.snapshotReads
        val held = CompletableDeferred<Unit>()
        client.beforeSnapshot = { held.await() }
        socket.orderEvent(observed, 1); runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(baseline + 1, client.snapshotReads)
        socket.orderEvent(observed, 2); socket.orderEvent(observed, 3)
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals(baseline + 1, client.snapshotReads)
        held.complete(Unit); runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(baseline + 2, client.snapshotReads)
        assertNotNull(model.state.value.user)
    }

    @Test fun initialLanguageBarrierBlocksLiveAndDescriptionReadsUntilSettledThenAckRecovers() = runTest(dispatcher) {
        val language = LanguageClient()
        val model = SnapperViewModel(Settings(), { language }, "https://example.test", false)
        models.put("language-live", model)
        try {
        model.onForeground()
        runCurrent()
        assertFalse(model.state.value.accountLanguageReady)
        assertEquals(0, language.ticketReads)
        assertTrue(language.sockets.isEmpty())
        assertEquals(0, language.snapshotReads)
        assertEquals(0, language.signalReads)
        assertEquals(0, language.reviewReads)
        language.gate.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.accountLanguageReady)
        assertEquals(1, language.ticketReads)
        val socket = language.sockets.single()
        socket.control("auth_required")
        socket.control("auth_complete", """, "available_topics":["orders.events.","signals.","ai_reviews."],"ws_token_exp":"${Instant.now().plusSeconds(3600)}"""")
        runCurrent()
        assertEquals(0, language.signalReads)
        assertEquals(0, language.reviewReads)
        acknowledge(socket); runCurrent(); advanceTimeBy(201); runCurrent()
        assertEquals(1, language.signalReads)
        assertEquals(2, language.reviewReads)
        } finally { language.gate.complete(Unit); models.clear(); runCurrent() }
    }

    @Test fun logoutWhileInitialLanguageIsHeldNeverStartsLiveWhenItCompletes() = runTest(dispatcher) {
        val language = LanguageClient()
        val model = SnapperViewModel(Settings(), { language }, "https://example.test", false)
        models.put("language-logout", model)
        try {
        model.onForeground(); runCurrent()
        model.logout(); runCurrent()
        language.gate.complete(Unit); runCurrent()
        assertFalse(model.state.value.accountLanguageReady)
        assertEquals(0, language.ticketReads)
        assertTrue(language.sockets.isEmpty())
        assertEquals(0, language.signalReads)
        assertEquals(0, language.reviewReads)
        } finally { language.gate.complete(Unit); models.clear(); runCurrent() }
    }

    private fun row(value: BigDecimal) = CandleDto("BTC", "venue", "1m", observed.minusSeconds(60).toString(),
        value, value, value, value, BigDecimal.ZERO, complete = false,
        timestamp = observed.minusSeconds(1).toString(), sessionId = "publisher", sequenceId = 1)

    private fun authenticate(socket: Socket) {
        socket.control("auth_required")
        socket.control("auth_complete", """, "available_topics":["market.","orders.events."],"ws_token_exp":"${Instant.now().plusSeconds(3600)}"""")
    }

    private fun acknowledge(socket: Socket) {
        val topics = socket.sent.last { it["type"]?.jsonPrimitive?.content == "subscribe" }.getValue("topics")
        socket.control("subscription_success", """, "action":"subscribe","status":"subscribed","topics":$topics,"denied_topics":[],"active_subscriptions":$topics""")
    }

    private class Socket(val observer: LiveSocketObserver) : LiveSocket {
        val sent = mutableListOf<JsonObject>()
        var cancelled = false
        private var sequence = 0
        override fun send(text: String): Boolean { sent += Json.parseToJsonElement(text).jsonObject; return true }
        override fun cancel() { cancelled = true }
        fun control(type: String, extra: String = "") {
            observer.frame("""{"type":"$type","session_id":"server","sequence_id":${++sequence},"public_id":"control-$sequence","timestamp":"${Instant.now().minusSeconds(1)}"$extra}""".toByteArray())
        }
        fun candle(time: Instant, value: BigDecimal) {
            observer.frame("""{"type":"candle","topic":"market.venue.BTC.candles.1m",
                "session_id":"publisher","sequence_id":2,"public_id":"bar-2","timestamp":"$time",
                "exchange":"venue","instrument":"BTC","timeframe":"1m","open_at":"${time.minusSeconds(60)}",
                "complete":false,"open":"$value","high":"$value","low":"$value","close":"$value",
                "volume":"0.000000000000000001"}""".toByteArray())
        }
        fun orderEvent(time: Instant, sequence: Int) {
            observer.frame("""{"type":"order_event","topic":"orders.events.venue.BTC.accepted",
                "session_id":"publisher","sequence_id":$sequence,"public_id":"order-$sequence",
                "timestamp":"$time","wallet_public_id":"wallet"}""".toByteArray())
        }
    }

    private open class Client : SessionClient, LiveSessionClient, LiveTransportClient {
        override val user = MutableStateFlow<UserDto?>(null)
        val sockets = mutableListOf<Socket>()
        var candleReads = 0
        var snapshotReads = 0
        var closed = false
        var candleRead: suspend () -> List<CandleDto> = { emptyList() }
        var beforeSnapshot: suspend () -> Unit = {}
        override suspend fun restore(): UserDto = UserDto("viewer", "viewer", publicId = "user",
            activeWalletPublicId = "wallet", effectivePermissions = listOf("read:market_data", "read:orders"))
            .also { user.value = it }
        override suspend fun login(username: String, password: String, rememberMe: Boolean) = restore()
        override suspend fun selectWallet(publicId: String?): UserDto = requireNotNull(user.value).copy(activeWalletPublicId = publicId).also { user.value = it }
        override suspend fun logout() { user.value = null }
        override suspend fun snapshot(): DashboardSnapshot {
            snapshotReads++
            val current = requireNotNull(user.value)
            beforeSnapshot()
            return DashboardSnapshot(current, emptyList(), emptyList(), emptyList(), emptyList())
        }
        override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> {
            candleReads++
            return candleRead()
        }
        override suspend fun liveTicket() = LiveTicket("fake-ticket")
        override fun liveTransport() = LiveSocketTransport { observer -> Socket(observer).also { sockets += it } }
        override suspend fun orderExchanges(scope: OrderScope): List<String> = emptyList()
        override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = emptyList()
        override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = error("Unexpected mutation")
        override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = error("Unexpected mutation")
        override fun close() { closed = true }
    }

    private class LanguageClient : Client(), AccountLanguageClient, SignalsClient, AiReviewsClient {
        val gate = CompletableDeferred<Unit>()
        var ticketReads = 0
        var signalReads = 0
        var reviewReads = 0
        override suspend fun restore(): UserDto = super.restore().copy(
            effectivePermissions = listOf("read:orders", "read:signals", "read:ai_reviews"),
        ).also { user.value = it }
        override suspend fun updateLanguage(identity: AccountLanguageIdentity, language: String): AccountLanguageReceipt {
            withContext(NonCancellable) { gate.await() }
            return AccountLanguageReceipt(identity, language)
        }
        override suspend fun liveTicket(): LiveTicket { ticketReads++; return super.liveTicket() }
        override suspend fun signals(walletPublicId: String?): List<SignalDto> { signalReads++; return emptyList() }
        override suspend fun auditReviews(): List<AiAuditReviewDto> { reviewReads++; return emptyList() }
        override suspend fun pendingReviews(): List<AiPendingReviewDto> = emptyList()
        override suspend fun submitDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse = error("Unexpected mutation")
    }

    private class Settings : SettingsStore {
        override val preferences = MutableStateFlow(ClientPreferences(null))
        override suspend fun setBackendUrl(origin: String) { preferences.value = preferences.value.copy(backendUrl = origin) }
        override suspend fun setLanguage(language: String) { preferences.value = preferences.value.copy(language = language) }
        override suspend fun updateDisplayPreferences(transform: (DisplayPreferences) -> DisplayPreferences): DisplayPreferences =
            transform(preferences.value.displayPreferences).also { preferences.value = preferences.value.copy(displayPreferences = it) }
    }
}
