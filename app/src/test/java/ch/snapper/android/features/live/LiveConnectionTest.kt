package ch.snapper.android.features.live

import ch.snapper.android.data.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveConnectionTest {
    private val epoch = Instant.parse("2026-10-16T12:00:00Z")
    private val topic = "market.venue.BTC.candles.1m"
    private class Socket(val observer: LiveSocketObserver) : LiveSocket {
        val sent = mutableListOf<JsonObject>()
        var cancelled = false
        var sequence = 0
        override fun send(text: String): Boolean { sent += Json.parseToJsonElement(text).jsonObject; return true }
        override fun cancel() { cancelled = true }
        fun control(type: String, extra: String = "") {
            observer.frame("""{"type":"$type","session_id":"server","sequence_id":${++sequence},"public_id":"event-$sequence","timestamp":"2026-10-16T12:00:00Z"$extra}""".toByteArray())
        }
        fun complete(topics: String = "\"market.\"", expiry: String = "2026-10-16T12:01:40Z", acknowledge: Boolean = true) {
            control("auth_complete", """, "available_topics":[$topics],"ws_token_exp":"$expiry"""")
            if (acknowledge) ack()
        }
        fun ack(accepted: String = "\"market.venue.BTC.candles.1m\"", denied: String = "", status: String = "subscribed") {
            control("subscription_success", """, "action":"subscribe","status":"$status","topics":[$accepted],"denied_topics":[$denied],"active_subscriptions":[$accepted]""")
        }
    }
    private class Transport : LiveSocketTransport {
        val sockets = mutableListOf<Socket>()
        override fun connect(observer: LiveSocketObserver): LiveSocket = Socket(observer).also { sockets += it }
    }

    @Test fun realProtocolSendsProvenanceAndSubscribesBeforeSnapshotCallback() = runTest {
        val transport = Transport()
        var tickets = 0
        val subscribed = mutableListOf<Set<String>>()
        val engine = LiveConnection(backgroundScope, { LiveTicket("ticket-${++tickets}") }, transport,
            { topics ->
                assertEquals("subscribe", transport.sockets.single().sent.last().getValue("type").jsonPrimitive.content)
                subscribed += topics
            }, {}, now = { epoch })
        engine.start(setOf(topic, "admin.secret")); runCurrent()
        assertEquals(1, tickets)
        val socket = transport.sockets.single()
        assertTrue(socket.sent.isEmpty())
        socket.control("auth_required"); runCurrent()
        val auth = socket.sent.single()
        assertEquals("authenticate", auth.getValue("type").jsonPrimitive.content)
        assertEquals("ticket-1", auth.getValue("ws_token").jsonPrimitive.content)
        assertTrue(auth.getValue("session_id").jsonPrimitive.content.isNotBlank())
        assertEquals(1, auth.getValue("sequence_id").jsonPrimitive.int)
        socket.control("auth_ok", """, "exp":"2026-10-16T12:01:40Z"""")
        socket.complete(); runCurrent()
        assertEquals(listOf(setOf(topic)), subscribed)
        assertEquals(2, socket.sent.last().getValue("sequence_id").jsonPrimitive.int)
        engine.stop(); runCurrent(); assertTrue(socket.cancelled)
    }

    @Test fun serverExpiryTriggersFreshTicketAtEightyPercentAndReauthAckRestoresStatus() = runTest {
        val transport = Transport(); var tickets = 0
        val statuses = mutableListOf<LiveConnectionStatus>()
        val engine = LiveConnection(backgroundScope, { LiveTicket("t${++tickets}") }, transport, {}, {}, statuses::add,
            { epoch.plusMillis(testScheduler.currentTime) })
        engine.start(setOf(topic)); runCurrent()
        val socket = transport.sockets.single()
        socket.control("auth_required"); runCurrent(); socket.complete(); runCurrent()
        advanceTimeBy(79_999); runCurrent(); assertEquals(1, tickets)
        advanceTimeBy(1); runCurrent(); assertEquals(2, tickets)
        assertEquals("reauth", socket.sent.last().getValue("type").jsonPrimitive.content)
        assertEquals("t2", socket.sent.last().getValue("ws_token").jsonPrimitive.content)
        socket.control("reauth_ok", """, "exp":"2026-10-16T12:03:00Z""""); runCurrent()
        assertEquals(LiveConnectionStatus.SUBSCRIBED, statuses.last())
        engine.stop()
    }

    @Test fun reconnectMintsNewTicketAndSupersededSocketCannotAuthenticateOrDeliver() = runTest {
        val transport = Transport(); var tickets = 0; var frames = 0
        val engine = LiveConnection(backgroundScope, { LiveTicket("t${++tickets}") }, transport, {}, { frames++ }, now = { epoch })
        engine.start(setOf(topic)); runCurrent()
        val old = transport.sockets.single()
        old.observer.closed(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(2, tickets); assertEquals(2, transport.sockets.size)
        old.control("auth_required"); old.observer.frame("""{"type":"tick","topic":"$topic"}""".toByteArray()); runCurrent()
        assertTrue(old.sent.isEmpty()); assertEquals(0, frames)
        val fresh = transport.sockets.last(); fresh.control("auth_required"); runCurrent()
        assertEquals("t2", fresh.sent.single().getValue("ws_token").jsonPrimitive.content)
        engine.stop()
    }

    @Test fun permissionReductionCancelsOldSocketAndOnlySubscribesAllowedNewTopics() = runTest {
        val transport = Transport(); val received = mutableListOf<Set<String>>()
        val engine = LiveConnection(backgroundScope, { LiveTicket("ticket") }, transport, received::add, {}, now = { epoch })
        engine.start(setOf(topic)); runCurrent()
        val old = transport.sockets.single(); old.control("auth_required"); runCurrent(); old.complete(); runCurrent()
        engine.start(emptySet()); runCurrent(); assertTrue(old.cancelled)
        val fresh = transport.sockets.last(); fresh.control("auth_required"); runCurrent(); fresh.complete("\"signals.\"", acknowledge = false); fresh.ack("", status = "no_topics"); runCurrent()
        assertTrue(fresh.cancelled)
        old.complete(); runCurrent(); assertEquals(1, received.size)
        engine.stop()
    }

    @Test fun stopDuringTicketMintNeverOpensSocket() = runTest {
        val ticket = CompletableDeferred<LiveTicket>(); val transport = Transport()
        val engine = LiveConnection(backgroundScope, { ticket.await() }, transport, {}, {}, now = { epoch })
        engine.start(setOf(topic)); runCurrent(); engine.stop()
        ticket.complete(LiveTicket("late")); runCurrent()
        assertTrue(transport.sockets.isEmpty())
    }

    @Test fun oversizedFrameClosesAttemptWithoutDelivery() = runTest {
        val transport = Transport(); var received = 0
        val engine = LiveConnection(backgroundScope, { LiveTicket("ticket") }, transport, {}, { received++ }, now = { epoch })
        engine.start(setOf(topic)); runCurrent()
        val socket = transport.sockets.single(); socket.observer.frame(ByteArray(65_537)); runCurrent()
        assertTrue(socket.cancelled); assertEquals(0, received)
        engine.stop()
    }

    @Test fun authenticationDeadlineReconnectsWithoutReusingTicket() = runTest {
        val transport = Transport(); var minted = 0
        val engine = LiveConnection(backgroundScope, { LiveTicket("t${++minted}") }, transport, {}, {}, now = { epoch })
        engine.start(setOf(topic)); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertTrue(transport.sockets.single().cancelled)
        advanceTimeBy(500); runCurrent(); assertEquals(2, minted)
        engine.stop()
    }

    @Test fun repeatedFailuresStopAfterSixBoundedAttempts() = runTest {
        var attempts = 0; val statuses = mutableListOf<LiveConnectionStatus>()
        val engine = LiveConnection(backgroundScope, { attempts++; throw IllegalStateException("unavailable") }, Transport(), {}, {}, statuses::add)
        engine.start(setOf(topic)); runCurrent()
        repeat(5) { index -> advanceTimeBy(500L shl index); runCurrent() }
        assertEquals(6, attempts); assertEquals(LiveConnectionStatus.FAILED, statuses.last())
        advanceTimeBy(100_000); runCurrent(); assertEquals(6, attempts)
        engine.stop()
    }

    @Test fun untrustedOrUnauthenticatedDataNeverReachesConsumer() = runTest {
        val transport = Transport(); var frames = 0
        val engine = LiveConnection(backgroundScope, { LiveTicket("ticket") }, transport, {}, { frames++ }, now = { epoch })
        engine.start(setOf(topic)); runCurrent()
        val socket = transport.sockets.single()
        val data = """{"type":"candle","topic":"$topic"}""".toByteArray()
        socket.observer.frame(data); runCurrent(); assertEquals(0, frames)
        socket.control("auth_required"); runCurrent(); socket.complete(); runCurrent()
        socket.observer.frame(data); socket.observer.frame("""{"type":"tick","topic":"admin.secret"}""".toByteArray()); runCurrent()
        assertEquals(1, frames)
        engine.stop(); runCurrent(); socket.observer.frame(data); runCurrent(); assertEquals(1, frames)
    }

    @Test fun serverReauthRequestUsesFreshTicketAndDeduplicatesOverlappingRequest() = runTest {
        val transport = Transport(); var minted = 0
        val engine = LiveConnection(backgroundScope, { LiveTicket("t${++minted}") }, transport, {}, {}, now = { epoch })
        engine.start(setOf(topic)); runCurrent()
        val socket = transport.sockets.single(); socket.control("auth_required"); runCurrent(); socket.complete(); runCurrent()
        socket.control("reauth_required", """, "deadline":"2026-10-16T12:01:40Z"""")
        socket.control("reauth_required", """, "deadline":"2026-10-16T12:01:40Z""""); runCurrent()
        assertEquals(2, minted); assertEquals(3, socket.sent.size)
        engine.stop()
    }

    @Test fun replayedControlProvenanceClosesConnection() = runTest {
        val transport = Transport()
        val engine = LiveConnection(backgroundScope, { LiveTicket("ticket") }, transport, {}, {}, now = { epoch })
        engine.start(setOf(topic)); runCurrent()
        val socket = transport.sockets.single(); socket.control("auth_required"); runCurrent()
        socket.sequence = 0; socket.control("auth_complete"); runCurrent()
        assertTrue(socket.cancelled)
        engine.stop()
    }

    @Test fun authFailureAndExpirationBothCloseWithoutDeliveringLaterFrames() = runTest {
        for (type in listOf("auth_failed", "auth_expired")) {
            val transport = Transport(); var received = 0
            val engine = LiveConnection(backgroundScope, { LiveTicket("ticket") }, transport, {}, { received++ }, now = { epoch })
            engine.start(setOf(topic)); runCurrent()
            val socket = transport.sockets.single(); socket.control("auth_required"); runCurrent()
            socket.control(type); runCurrent()
            assertTrue(socket.cancelled); assertEquals(0, received)
            engine.stop()
        }
    }

    @Test fun preAcknowledgementFramesFlushOnlyAfterSnapshotCallbackAndOnlyForAcceptedTopics() = runTest {
        val transport = Transport(); val order = mutableListOf<String>()
        val other = "market.venue.ETH.ticks"
        val engine = LiveConnection(backgroundScope, { LiveTicket("ticket") }, transport,
            { order += "snapshot"; assertEquals(setOf(topic), it) }, { order += "frame" }, now = { epoch })
        engine.start(setOf(topic, other)); runCurrent()
        val socket = transport.sockets.single(); socket.control("auth_required"); runCurrent()
        socket.complete(acknowledge = false); runCurrent(); assertTrue(order.isEmpty())
        socket.observer.frame("""{"type":"candle","topic":"$topic"}""".toByteArray())
        socket.observer.frame("""{"type":"tick","topic":"$other"}""".toByteArray()); runCurrent()
        assertTrue(order.isEmpty())
        socket.ack(denied = "\"$other\"", status = "partial"); runCurrent()
        assertEquals(listOf("snapshot", "frame"), order)
        engine.stop()
    }

    @Test fun bridgeErrorAndDeniedAcknowledgementNeverStartSnapshot() = runTest {
        for (error in listOf(true, false)) {
            val transport = Transport(); var snapshots = 0
            val engine = LiveConnection(backgroundScope, { LiveTicket("ticket") }, transport, { snapshots++ }, {}, now = { epoch })
            engine.start(setOf(topic)); runCurrent()
            val socket = transport.sockets.single(); socket.control("auth_required"); runCurrent()
            socket.complete(acknowledge = false); runCurrent()
            if (error) socket.control("error", """, "message":"ZMQ bridge is not available"""")
            else socket.ack("", "\"$topic\"", "denied")
            runCurrent(); assertEquals(0, snapshots); assertTrue(socket.cancelled)
            engine.stop()
        }
    }

    @Test fun subscriptionAcknowledgementDeadlineDoesNotReportLive() = runTest {
        val transport = Transport(); var snapshots = 0
        val engine = LiveConnection(backgroundScope, { LiveTicket("ticket") }, transport, { snapshots++ }, {}, now = { epoch })
        engine.start(setOf(topic)); runCurrent()
        val socket = transport.sockets.single(); socket.control("auth_required"); runCurrent()
        socket.complete(acknowledge = false); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(0, snapshots); assertTrue(socket.cancelled)
        engine.stop()
    }

    @Test fun ticketRepresentationNeverIncludesCredential() {
        assertEquals("LiveTicket(redacted)", LiveTicket("private-token").toString())
    }
}
