package ch.snapper.android

import androidx.lifecycle.ViewModelStore
import ch.snapper.android.data.*
import ch.snapper.android.features.home.HomeConnectionHealth
import ch.snapper.android.features.home.HomeConnectionLabel
import ch.snapper.android.features.home.HomeGlanceReducer
import ch.snapper.android.features.live.LiveConnectionStatus
import ch.snapper.android.features.system.SystemClient
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnapperViewModelHomeTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val epoch = Instant.parse("2026-10-16T12:00:00Z")

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }

    private fun TestScope.clock() = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = epoch.plusMillis(testScheduler.currentTime)
    }

    @Test fun heartbeatAgesLiveAndBackgroundResumeResetsConnectedBadgeUntilFreshSubscribe() = runTest(dispatcher, timeout = 10.seconds) {
        try {
            val client = HomeClient()
            val model = SnapperViewModel(HomeSettings(), { client }, "https://example.test", false, clock = clock())
            models.put("home-live", model)
            runCurrent()
            model.setHomeVisible(true)
            model.onForeground()
            runCurrent()
            val firstSocket = client.sockets.single()
            authenticateAndSubscribe(firstSocket, clock().instant())
            runCurrent()

            assertEquals(LiveConnectionStatus.SUBSCRIBED, model.state.value.home.status)
            assertEquals(HomeConnectionHealth.WARNING, HomeGlanceReducer.connectionBadge(model.state.value.home).health)

            advanceTimeBy(2_000); runCurrent()
            assertEquals(2, HomeGlanceReducer.connectionBadge(model.state.value.home).ageSeconds)

            firstSocket.heartbeat(clock().instant(), 1)
            runCurrent()
            assertEquals(HomeConnectionHealth.FRESH, HomeGlanceReducer.connectionBadge(model.state.value.home).health)
            assertEquals(0, HomeGlanceReducer.connectionBadge(model.state.value.home).ageSeconds)

            advanceTimeBy(6_000); runCurrent()
            assertEquals(HomeConnectionHealth.WARNING, HomeGlanceReducer.connectionBadge(model.state.value.home).health)
            assertEquals(6, HomeGlanceReducer.connectionBadge(model.state.value.home).ageSeconds)

            advanceTimeBy(25_000); runCurrent()
            assertEquals(HomeConnectionHealth.DISCONNECTED, HomeGlanceReducer.connectionBadge(model.state.value.home).health)
            assertEquals(31, HomeGlanceReducer.connectionBadge(model.state.value.home).ageSeconds)

            val ticketGate = CompletableDeferred<LiveTicket>()
            client.ticketAction = { ticketGate.await() }
            model.onBackground()
            runCurrent()
            assertTrue(firstSocket.cancelled)
            assertEquals(LiveConnectionStatus.STOPPED, model.state.value.home.status)
            assertNull(model.state.value.home.lastHeartbeatAt)
            assertEquals(HomeConnectionLabel.DISCONNECTED, HomeGlanceReducer.connectionBadge(model.state.value.home).label)

            model.onForeground()
            runCurrent()
            val resumedBadge = HomeGlanceReducer.connectionBadge(model.state.value.home)
            assertEquals(HomeConnectionLabel.CONNECTING, resumedBadge.label)
            assertEquals(HomeConnectionHealth.WARNING, resumedBadge.health)
            assertNull(resumedBadge.ageSeconds)
            assertNull(model.state.value.home.lastHeartbeatAt)

            ticketGate.complete(LiveTicket("resumed-ticket"))
            runCurrent()
            val secondSocket = client.sockets.last()
            assertNotSame(firstSocket, secondSocket)
            authenticateAndSubscribe(secondSocket, clock().instant())
            runCurrent()
            assertEquals(LiveConnectionStatus.SUBSCRIBED, model.state.value.home.status)
            assertNull(model.state.value.home.lastHeartbeatAt)
            model.setHomeVisible(false)
        } finally {
            models.clear()
            runCurrent()
        }
    }

    @Test fun repeatedLiveEmitsKeepSameHomeTickerJob() = runTest(dispatcher, timeout = 10.seconds) {
        try {
            val client = HomeClient()
            val model = SnapperViewModel(HomeSettings(), { client }, "https://example.test", false, clock = clock())
            models.put("home-ticker", model)
            runCurrent()
            model.setHomeVisible(true)
            model.onForeground()
            runCurrent()
            val socket = client.sockets.single()
            authenticateAndSubscribe(socket, clock().instant())
            runCurrent()
            val ticker = model.homeTickerJob
            assertNotNull(ticker)
            assertTrue(ticker?.isActive == true)

            socket.heartbeat(clock().instant(), 1)
            runCurrent()
            assertSame(ticker, model.homeTickerJob)
            advanceTimeBy(2_000)
            runCurrent()
            assertSame(ticker, model.homeTickerJob)
            socket.heartbeat(clock().instant(), 2)
            runCurrent()
            assertSame(ticker, model.homeTickerJob)
        } finally {
            models.clear()
            runCurrent()
        }
    }

    @Test fun walletSwitchClearsOldGlanceRowsAndLoadsStatusPendingReviewsAndAlertsForNewWallet() = runTest(dispatcher, timeout = 10.seconds) {
        try {
            val client = HomeClient()
            val model = SnapperViewModel(HomeSettings(), { client }, "https://example.test", false, clock = clock())
            models.put("home-wallet", model)
            runCurrent()
            model.setHomeVisible(true)
            model.actions.loadHome()
            runCurrent()

            val initial = HomeGlanceReducer.derive(model.state.value)
            assertEquals("running", initial.traderStatus?.trader?.status)
            assertEquals(listOf("pos-wallet-a"), initial.openPositions.map { it.publicId })
            assertEquals(listOf("ord-wallet-a"), initial.recentOrders.map { it.publicId })
            assertEquals("alert-wallet-a", initial.latestAlert?.publicId)
            assertEquals(2, initial.pendingAiReviewCount)

            val gate = CompletableDeferred<UserDto>()
            client.walletGate = { gate.await() }
            model.selectWallet("wallet-b")
            assertNull(model.state.value.snapshot)
            assertNull(model.state.value.system.status.value)
            assertEquals(0, model.state.value.home.pendingAiReviewCount)

            client.pendingCount = 1
            gate.complete(client.baseUser.copy(activeWalletPublicId = "wallet-b"))
            runCurrent()

            val switched = HomeGlanceReducer.derive(model.state.value)
            assertEquals(listOf("pos-wallet-b"), switched.openPositions.map { it.publicId })
            assertEquals(listOf("ord-wallet-b"), switched.recentOrders.map { it.publicId })
            assertEquals("alert-wallet-b", switched.latestAlert?.publicId)
            assertEquals(1, switched.pendingAiReviewCount)
            assertEquals("running", switched.traderStatus?.trader?.status)

            client.failPending = true
            model.refresh()
            runCurrent()
            assertEquals(0, model.state.value.home.pendingAiReviewCount)
            assertNull(model.state.value.error)
            model.setHomeVisible(false)
        } finally {
            models.clear()
            runCurrent()
        }
    }

    @Test fun nonDelegateSkipsPendingReviewsCallWithoutError() = runTest(dispatcher, timeout = 10.seconds) {
        try {
            val client = HomeClient().apply {
                user.value = baseUser.copy(delegatePublicId = null, effectivePermissions = listOf("read:system_status", "read:ai_reviews"))
            }
            val model = SnapperViewModel(HomeSettings(), { client }, "https://example.test", false, clock = clock())
            models.put("home-non-delegate", model)
            runCurrent()
            model.setHomeVisible(true)
            model.actions.loadHome()
            runCurrent()
            assertEquals(0, client.pendingReads)
            assertEquals(0, model.state.value.home.pendingAiReviewCount)
            model.setHomeVisible(false)
        } finally {
            models.clear()
            runCurrent()
        }
    }

    @Test fun socketOnlySessionShowsSocketAgeAndResetsAcrossBackgroundResume() = runTest(dispatcher, timeout = 10.seconds) {
        try {
            val client = HomeClient().apply {
                user.value = baseUser.copy(delegatePublicId = null, effectivePermissions = listOf("read:orders"))
            }
            val model = SnapperViewModel(HomeSettings(), { client }, "https://example.test", false, clock = clock())
            models.put("home-socket-only", model)
            runCurrent()
            model.setHomeVisible(true)
            model.onForeground()
            runCurrent()
            val socket = client.sockets.single()
            authenticateAndSubscribe(socket, clock().instant(), """"orders.events."""")
            runCurrent()

            advanceTimeBy(12_000); runCurrent()
            val badge = HomeGlanceReducer.connectionBadge(model.state.value.home)
            assertEquals(HomeConnectionLabel.CONNECTED, badge.label)
            assertEquals(HomeConnectionHealth.WARNING, badge.health)
            assertEquals(12, badge.ageSeconds)

            model.onBackground()
            runCurrent()
            assertEquals(HomeConnectionLabel.DISCONNECTED, HomeGlanceReducer.connectionBadge(model.state.value.home).label)
            assertNull(model.state.value.home.connectedAt)
            model.setHomeVisible(false)
        } finally {
            models.clear()
            runCurrent()
        }
    }

    private fun TestScope.authenticateAndSubscribe(
        socket: Socket,
        now: Instant,
        availableTopics: String = """"system.heartbeats.","orders.events.","ai_reviews."""",
    ) {
        socket.control("auth_required", now)
        socket.control("auth_complete", now, """, "available_topics":[$availableTopics],"ws_token_exp":"${now.plusSeconds(3600)}"""")
        runCurrent()
        val topics = socket.sent.last { it["type"]?.jsonPrimitive?.content == "subscribe" }.getValue("topics")
        socket.control("subscription_success", now, """, "action":"subscribe","status":"subscribed","topics":$topics,"denied_topics":[],"active_subscriptions":$topics""")
    }

    private class Socket(val observer: LiveSocketObserver) : LiveSocket {
        val sent = mutableListOf<JsonObject>()
        var cancelled = false
        private var sequence = 0
        override fun send(text: String): Boolean { sent += Json.parseToJsonElement(text).jsonObject; return true }
        override fun cancel() { cancelled = true }
        fun control(type: String, now: Instant, extra: String = "") {
            observer.frame("""{"type":"$type","session_id":"server","sequence_id":${++sequence},"public_id":"ctrl-$sequence","timestamp":"$now"$extra}""".toByteArray())
        }
        fun heartbeat(now: Instant, seq: Int) {
            observer.frame("""{"type":"heartbeat","topic":"system.heartbeats.trader","session_id":"pub","sequence_id":$seq,"public_id":"hb-$seq","timestamp":"$now"}""".toByteArray())
        }
    }

    private class HomeClient : SessionClient, SystemClient, AiReviewsClient, LiveSessionClient, LiveTransportClient {
        val baseUser = UserDto(
            "trader", "operator", "u1", activeWalletPublicId = "wallet-a", delegatePublicId = "del-1",
            effectivePermissions = listOf("read:system_status", "read:positions", "read:orders", "read:notifications", "read:signals", "read:ai_reviews"),
        )
        override val user = MutableStateFlow<UserDto?>(baseUser)
        val sockets = mutableListOf<Socket>()
        var ticketAction: suspend () -> LiveTicket = { LiveTicket("ws-ticket") }
        var walletGate: suspend (String?) -> UserDto = { w -> baseUser.copy(activeWalletPublicId = w) }
        var pendingCount = 2
        var failPending = false
        var pendingReads = 0

        override suspend fun restore(): UserDto? = user.value
        override suspend fun login(username: String, password: String, rememberMe: Boolean) = requireNotNull(user.value)
        override suspend fun selectWallet(publicId: String?): UserDto = walletGate(publicId).also { user.value = it }
        override suspend fun logout() { user.value = null }
        override suspend fun snapshot(): DashboardSnapshot {
            val current = requireNotNull(user.value)
            val wallet = current.activeWalletPublicId ?: "wallet-a"
            return DashboardSnapshot(
                current,
                listOf(WalletDto("wallet-a", "A", isPaper = true), WalletDto("wallet-b", "B", isPaper = false)),
                listOf(PositionDto("pos-$wallet", "BTC-USD", "kraken", BigDecimal("0.5"), BigDecimal.ZERO, BigDecimal("60000"), BigDecimal("250.25"), walletPublicId = wallet)),
                listOf(OrderDto("ord-$wallet", "cli-$wallet", "BTC-USD", "kraken", "buy", "open", "limit", BigDecimal("0.5"), BigDecimal.ZERO, "2026-10-16T11:59:00Z", walletPublicId = wallet)),
                listOf(AlertDto("alert-$wallet", "2026-10-16T11:58:00Z", "order_fill_full", "high", false, "Filled $wallet", "Details")),
            )
        }
        override suspend fun systemStatus() = SystemSystemStatusDataDto("status", SystemProcessStatusDto("running"), emptyMap(), listOf(SystemStrategyStatusPayloadDto("strat", "running")))
        override suspend fun systemHealth() = SystemHealthCheckDataDto("health", "ok", "1", SystemConnectionStatsDto(), SystemHealthTopicsDto(0), SystemGapDetectionStatsDto(SystemGapStatsDto()))
        override suspend fun systemStrategies() = emptyList<SystemStrategyProcessDto>()
        override suspend fun systemBacktests() = emptyList<SystemBacktestRunDataDto>()
        override suspend fun systemUsers() = emptyList<SystemUserProfileDto>()
        override suspend fun systemDelegates() = emptyList<SystemDelegateReadDto>()
        override suspend fun auditReviews() = emptyList<AiAuditReviewDto>()
        override suspend fun pendingReviews(): List<AiPendingReviewDto> {
            pendingReads++
            if (failPending) throw IOException("offline")
            return (1..pendingCount).map { AiPendingReviewDto("rev-$it", "del-1", "wallet-a", 1, "pending", "2099-01-01T00:00:00Z", "2026-10-16T00:00:00Z") }
        }
        override suspend fun submitDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload) = error("unused")
        override suspend fun liveTicket(): LiveTicket = ticketAction()
        override fun liveTransport() = LiveSocketTransport { observer -> Socket(observer).also { sockets += it } }
        override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int) = emptyList<CandleDto>()
        override suspend fun orderExchanges(scope: OrderScope) = emptyList<String>()
        override suspend fun orderInstruments(scope: OrderScope, exchange: String) = emptyList<InstrumentDto>()
        override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload) = error("unused")
        override suspend fun cancelOrder(scope: OrderScope, order: OrderDto) = error("unused")
        override fun close() = Unit
    }

    private class HomeSettings : SettingsStore {
        override val preferences = MutableStateFlow(ClientPreferences(null))
        override suspend fun setBackendUrl(origin: String) = Unit
        override suspend fun setLanguage(language: String) = Unit
        override suspend fun updateDisplayPreferences(transform: (DisplayPreferences) -> DisplayPreferences) = transform(preferences.value.displayPreferences)
    }
}
