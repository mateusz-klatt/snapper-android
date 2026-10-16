package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.live.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCoordinatorTest {
    private val epoch = Instant.parse("2026-10-16T12:00:00Z")
    private val selection = LiveSelection("venue", "BTC", "1m")
    private val permissions = setOf("read:market_data", "read:orders", "read:signals", "read:ai_reviews", "read:system_status", "read:processes", "manage:processes", "read:strategies")
    private fun identity(caps: Set<String> = permissions, generation: Long = 1) =
        LiveIdentity("https://example.test", OrderScope("u", "viewer", "w", caps), "operator", "delegate", generation)
    private fun TestScope.binding(transport: Transport, actor: LiveIdentity = identity(), tickets: LiveSessionClient = LiveSessionClient { LiveTicket("ticket") }) =
        LiveBinding(actor, tickets, transport, object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = epoch.plusMillis(testScheduler.currentTime)
        })

    @Test fun initialAckReauthAndReconnectHaveExactlyOneSnapshotPerRequiredLoad() = runTest {
        val transport = Transport(); val bound = binding(transport)
        val snapshots = mutableListOf<MarketLoadTicket>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, snapshots::add, {}, {})
        coordinator.setMarketVisible(true); coordinator.selectMarket(selection); coordinator.setForeground(true)
        assertEquals(0, snapshots.size)
        val first = establish(transport)
        assertEquals(1, snapshots.size)
        advanceTimeBy(80_000); runCurrent()
        assertEquals("reauth", first.sent.last()["type"]?.jsonPrimitive?.content)
        first.control("reauth_ok", """, "exp":"2026-10-16T12:03:00Z""""); runCurrent()
        assertEquals(1, snapshots.size)
        first.observer.closed(); runCurrent(); advanceTimeBy(500); runCurrent()
        establish(transport)
        assertEquals(3, snapshots.size)
        assertNotEquals(snapshots.first().generation, snapshots.last().generation)
        coordinator.invalidate()
    }

    @Test fun delayedAcknowledgementDoesNotStartRestAndFailedHandshakeRecoversAfterFallback() = runTest {
        val transport = Transport(); val bound = binding(transport)
        val snapshots = mutableListOf<MarketLoadTicket>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, snapshots::add, {}, {})
        coordinator.setMarketVisible(true); coordinator.selectMarket(selection); coordinator.setForeground(true)
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertTrue(snapshots.isEmpty())
        transport.sockets.single().observer.closed(); runCurrent()
        assertEquals(1, snapshots.size)
        val fallback = snapshots.single()
        advanceTimeBy(500); runCurrent(); establish(transport)
        assertEquals(2, snapshots.size)
        assertTrue(coordinator.state.acceptedTopics.contains(selection.candleTopic))
        coordinator.snapshotFailed(fallback)
        assertFalse(coordinator.state.market.snapshotFailed)
        coordinator.invalidate()
    }

    @Test fun deniedMarketTopicsUseRestAndExplicitRefreshCannotLeaveLoadingForever() = runTest {
        val transport = Transport(); val bound = binding(transport)
        val snapshots = mutableListOf<MarketLoadTicket>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, snapshots::add, {}, {})
        coordinator.setMarketVisible(true); coordinator.selectMarket(selection); coordinator.setForeground(true)
        establish(transport, denyMarket = true)
        assertEquals(1, snapshots.size)
        assertFalse(selection.candleTopic in coordinator.state.acceptedTopics)
        coordinator.snapshotCompleted(snapshots.last(), emptyList())
        assertFalse(coordinator.state.market.loading)
        coordinator.selectMarket(selection, forceRefresh = true)
        assertEquals(2, snapshots.size)
        coordinator.snapshotFailed(snapshots.last())
        assertFalse(coordinator.state.market.loading)
        assertTrue(coordinator.state.market.snapshotFailed)
        coordinator.invalidate()
    }

    @Test fun marketVisibilityRetainsSelectionWhileBackgroundStopsAllTrafficAndInvalidatesReads() = runTest {
        val transport = Transport(); val bound = binding(transport, identity(setOf("read:orders", "read:market_data")))
        val snapshots = mutableListOf<MarketLoadTicket>(); val refreshed = mutableListOf<LiveRefreshTarget>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, snapshots::add, { refreshed += it }, {})
        coordinator.selectMarket(selection); coordinator.setMarketVisible(true); coordinator.setForeground(true)
        establish(transport)
        val oldTicket = snapshots.single()
        coordinator.setMarketVisible(false)
        val eventsOnly = establish(transport)
        assertFalse(eventsOnly.subscriptions().any { it.startsWith("market.") })
        assertEquals(selection, coordinator.state.market.selection)
        eventsOnly.event("order_event", "orders.events.venue.BTC.accepted")
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(listOf(LiveRefreshTarget.ORDERS), refreshed)
        coordinator.snapshotFailed(oldTicket)
        assertFalse(coordinator.state.market.snapshotFailed)
        coordinator.setForeground(false); runCurrent()
        assertTrue(eventsOnly.cancelled)
        eventsOnly.event("order_event", "orders.events.venue.BTC.accepted", 2)
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, refreshed.size)
        coordinator.setForeground(true); establish(transport)
        assertEquals(1, snapshots.size)
        coordinator.setMarketVisible(true); establish(transport)
        assertEquals(2, snapshots.size)
        coordinator.invalidate()
    }

    @Test fun selectionAndSessionReplacementSuppressOldSnapshotsAndSocketEvents() = runTest {
        val transport = Transport(); var bound = binding(transport)
        val snapshots = mutableListOf<MarketLoadTicket>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, snapshots::add, {}, {})
        coordinator.setMarketVisible(true); coordinator.selectMarket(selection); coordinator.setForeground(true)
        val old = establish(transport); val first = snapshots.single()
        coordinator.selectMarket(selection.copy(instrument = "ETH")); establish(transport)
        coordinator.selectMarket(selection); establish(transport)
        coordinator.snapshotFailed(first)
        assertFalse(coordinator.state.market.snapshotFailed)
        assertEquals(3, snapshots.size)
        bound = binding(transport, identity(generation = 2))
        coordinator.synchronizeBinding(); runCurrent()
        old.event("order_event", "orders.events.venue.BTC.accepted")
        coordinator.snapshotFailed(snapshots.last())
        assertNull(coordinator.state.market.selection)
        assertFalse(coordinator.state.market.snapshotFailed)
        coordinator.invalidate()
    }

    @Test fun validatedEventBurstWaitsForReadCompletionAndRunsOneFollowUp() = runTest {
        val transport = Transport(); val bound = binding(transport, identity(setOf("read:orders")))
        val held = CompletableDeferred<Unit>(); var reads = 0
        val coordinator = LiveCoordinator(backgroundScope, { bound }, {}, {
            reads++
            if (reads == 1) held.await()
        }, {})
        coordinator.setForeground(true)
        val socket = establish(transport)
        socket.event("order_event", "orders.events.venue.BTC.accepted")
        runCurrent(); advanceTimeBy(200); runCurrent(); assertEquals(1, reads)
        socket.event("order_event", "orders.events.venue.BTC.accepted", 2)
        socket.event("execution", "orders.events.venue.BTC.executed", 3)
        runCurrent(); advanceTimeBy(500); runCurrent(); assertEquals(1, reads)
        held.complete(Unit); runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(2, reads)
        coordinator.invalidate()
    }

    @Test fun permissionWalletProvenanceAndDuplicateChecksPreventSpuriousReads() = runTest {
        val transport = Transport(); val bound = binding(transport, identity(setOf("read:orders")))
        val reads = mutableListOf<LiveRefreshTarget>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, {}, { reads += it }, {})
        coordinator.setForeground(true); val socket = establish(transport)
        assertEquals(setOf("orders.events."), socket.subscriptions())
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(LiveRefreshTarget.ORDERS), reads)
        reads.clear()
        socket.event("order_event", "orders.events.venue.BTC.accepted", wallet = "other")
        socket.event("signal", "orders.events.venue.BTC.accepted", 2)
        socket.observer.frame("""{"type":"order_event","topic":"orders.events.venue.BTC.accepted"}""".toByteArray())
        runCurrent(); advanceTimeBy(200); runCurrent(); assertTrue(reads.isEmpty())
        socket.event("order_event", "orders.events.venue.BTC.accepted", 3)
        socket.event("order_event", "orders.events.venue.BTC.accepted", 3)
        runCurrent(); advanceTimeBy(200); runCurrent(); assertEquals(1, reads.size)
        socket.event("order_event", "orders.events.venue.BTC.accepted", 2)
        runCurrent(); advanceTimeBy(200); runCurrent(); assertEquals(1, reads.size)
        coordinator.invalidate()
    }

    @Test fun bufferedSocketCandleWinsOlderSnapshotWithoutRoundingAndForeignFramesAreIgnored() = runTest {
        val transport = Transport(); val bound = binding(transport)
        val snapshots = mutableListOf<MarketLoadTicket>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, snapshots::add, {}, {})
        coordinator.setMarketVisible(true); coordinator.selectMarket(selection); coordinator.setForeground(true)
        val socket = establish(transport)
        val price = java.math.BigDecimal("123.123456789012345678901234567890")
        val frame = """{"type":"candle","topic":"${selection.candleTopic}",
            "session_id":"publisher","sequence_id":2,"public_id":"candle-2",
            "timestamp":"2026-10-16T12:00:00Z","exchange":"venue","instrument":"BTC",
            "timeframe":"1m","open_at":"2026-10-16T11:59:00Z","complete":false,
            "open":"$price","high":"$price","low":"$price","close":"$price","volume":"0.000000000000000001"}"""
        socket.observer.frame(frame.replace("BTC", "ETH").toByteArray())
        runCurrent(); assertTrue(coordinator.state.market.pending.isEmpty())
        socket.observer.frame(frame.toByteArray()); runCurrent()
        assertEquals(1, coordinator.state.market.pending.size)
        val old = CandleDto("BTC", "venue", "1m", "2026-10-16T11:59:00Z",
            java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
            java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, complete = false,
            timestamp = "2026-10-16T11:59:59Z", sessionId = "publisher", sequenceId = 1)
        coordinator.snapshotCompleted(snapshots.single(), listOf(old))
        assertFalse(coordinator.state.market.loading)
        assertEquals(price, coordinator.state.market.candles.single().close)
        assertEquals(java.math.BigDecimal("0.000000000000000001"), coordinator.state.market.candles.single().volume)
        assertTrue(coordinator.state.market.pending.isEmpty())
        coordinator.invalidate()
    }

    @Test fun acceptedFeedsRecoverAfterBackgroundAndReconnectWithoutEventsAndExcludeDeniedFeeds() = runTest {
        val transport = Transport(); val bound = binding(transport, identity(setOf("read:orders", "read:signals")).copy(delegatePublicId = null))
        val reads = mutableListOf<LiveRefreshTarget>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, {}, { reads += it }, {})
        coordinator.setForeground(true)
        establish(transport, deniedRoots = setOf("orders.events."))
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(LiveRefreshTarget.SIGNALS), reads)
        coordinator.setForeground(false); runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(1, reads.size)
        coordinator.setForeground(true)
        val socket = establish(transport, deniedRoots = setOf("orders.events."))
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(LiveRefreshTarget.SIGNALS, LiveRefreshTarget.SIGNALS), reads)
        socket.observer.closed(); runCurrent(); advanceTimeBy(500); runCurrent()
        establish(transport, deniedRoots = setOf("orders.events."))
        advanceTimeBy(200); runCurrent()
        assertEquals(List(3) { LiveRefreshTarget.SIGNALS }, reads)
        coordinator.invalidate()
    }

    @Test fun sameConnectionTicketRenewalDoesNotRefreshFeedsAgain() = runTest {
        val transport = Transport(); val bound = binding(transport, identity(setOf("read:signals")).copy(delegatePublicId = null))
        val reads = mutableListOf<LiveRefreshTarget>()
        val coordinator = LiveCoordinator(backgroundScope, { bound }, {}, { reads += it }, {})
        coordinator.setForeground(true); val socket = establish(transport)
        advanceTimeBy(80_000); runCurrent()
        assertEquals(listOf(LiveRefreshTarget.SIGNALS), reads)
        assertEquals("reauth", socket.sent.last()["type"]?.jsonPrimitive?.content)
        socket.control("reauth_ok", """, "exp":"2026-10-16T12:03:00Z"""")
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(listOf(LiveRefreshTarget.SIGNALS), reads)
        coordinator.invalidate()
    }

    @Test fun logoutWhileTicketMintIsSuspendedCannotOpenSocket() = runTest {
        val transport = Transport(); val ticket = CompletableDeferred<LiveTicket>()
        var bound: LiveBinding? = binding(transport, tickets = LiveSessionClient { ticket.await() })
        val coordinator = LiveCoordinator(backgroundScope, { bound }, {}, {}, {})
        coordinator.setForeground(true); runCurrent()
        bound = null; coordinator.synchronizeBinding()
        ticket.complete(LiveTicket("late")); runCurrent()
        assertTrue(transport.sockets.isEmpty())
        assertEquals(LiveConnectionStatus.STOPPED, coordinator.state.status)
        coordinator.invalidate()
    }

    private fun TestScope.establish(transport: Transport, denyMarket: Boolean = false, deniedRoots: Set<String> = emptySet()): Socket {
        runCurrent()
        val socket = transport.sockets.last()
        socket.control("auth_required")
        socket.control("auth_complete", """, "available_topics":["market.","orders.events.","signals.","ai_reviews.","processes.events.summary.","processes.events.configured.","processes.events.runs.","strategies.events.list.","system.heartbeats."],"ws_token_exp":"2026-10-16T12:01:40Z"""")
        runCurrent()
        val requested = socket.sent.last().getValue("topics").jsonArray
        val topics = JsonArray(requested.filter { (!denyMarket || !it.jsonPrimitive.content.startsWith("market.")) && it.jsonPrimitive.content !in deniedRoots })
        val denied = JsonArray(requested.filter { it !in topics })
        val status = if (denied.isNotEmpty()) "partial" else "subscribed"
        socket.control("subscription_success", """, "action":"subscribe","status":"$status","topics":$topics,"denied_topics":$denied,"active_subscriptions":$topics""")
        runCurrent()
        return socket
    }

    private class Transport : LiveSocketTransport {
        val sockets = mutableListOf<Socket>()
        override fun connect(observer: LiveSocketObserver): LiveSocket = Socket(observer).also { sockets += it }
    }
    private class Socket(val observer: LiveSocketObserver) : LiveSocket {
        val sent = mutableListOf<JsonObject>()
        var cancelled = false
        private var sequence = 0
        override fun send(text: String): Boolean { sent += Json.parseToJsonElement(text).jsonObject; return true }
        override fun cancel() { cancelled = true }
        fun subscriptions(): Set<String> = sent.last { it["type"]?.jsonPrimitive?.content == "subscribe" }
            .getValue("topics").jsonArray.map { it.jsonPrimitive.content }.toSet()
        fun control(type: String, extra: String = "") {
            observer.frame("""{"type":"$type","session_id":"server","sequence_id":${++sequence},"public_id":"control-$sequence","timestamp":"2026-10-16T12:00:00Z"$extra}""".toByteArray())
        }
        fun event(type: String, topic: String, seq: Int = 1, wallet: String = "w") {
            observer.frame("""{"type":"$type","topic":"$topic","session_id":"publisher","sequence_id":$seq,"public_id":"event-$seq","timestamp":"2026-10-16T12:00:00Z","wallet_public_id":"$wallet"}""".toByteArray())
        }
    }
}
