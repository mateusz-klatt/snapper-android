package ch.snapper.android.features.live

import ch.snapper.android.data.LiveSessionClient
import ch.snapper.android.data.LiveSocket
import ch.snapper.android.data.LiveSocketObserver
import ch.snapper.android.data.LiveSocketTransport
import ch.snapper.android.data.LiveTicket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

enum class LiveConnectionStatus { STOPPED, CONNECTING, AUTHENTICATING, SUBSCRIBED, RECONNECTING, FAILED }

/** Session owner must stop on logout, wallet/actor/permission changes, and background suspension. */
class LiveConnection(
    private val scope: CoroutineScope,
    private val tickets: LiveSessionClient,
    private val transport: LiveSocketTransport,
    private val onSubscribed: (Set<String>) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (LiveConnectionStatus) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {
    private val generation = AtomicLong()
    private var job: Job? = null

    @Synchronized fun start(topics: Set<String>) {
        require(topics.size <= 16 && topics.all { it.isNotBlank() && it == it.trim() && it.length <= 512 && '*' !in it })
        val epoch = generation.incrementAndGet()
        job?.cancel()
        val requested = topics.toSet()
        job = scope.launch { reconnect(epoch, requested) }
    }

    @Synchronized fun stop() {
        generation.incrementAndGet()
        job?.cancel(); job = null
        onStatus(LiveConnectionStatus.STOPPED)
    }

    private suspend fun reconnect(epoch: Long, topics: Set<String>) {
        repeat(6) { attempt ->
            if (!current(epoch)) return
            status(epoch, LiveConnectionStatus.CONNECTING)
            try {
                LiveAttempt(tickets, transport, topics, now,
                    { active -> emit(epoch) { onSubscribed(active); onStatus(LiveConnectionStatus.SUBSCRIBED) } },
                    { bytes -> emit(epoch) { onFrame(bytes) } },
                    { status(epoch, it) },
                ).run()
            } catch (_: TimeoutCancellationException) {
                // A bounded ticket request timed out; a later attempt must mint a new ticket.
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Protocol, network and ticket failures contain no publishable credential details.
            }
            if (attempt < 5 && current(epoch)) {
                status(epoch, LiveConnectionStatus.RECONNECTING)
                delay((500L shl attempt).coerceAtMost(30_000))
            }
        }
        status(epoch, LiveConnectionStatus.FAILED)
    }

    private fun current(epoch: Long): Boolean = generation.get() == epoch
    private fun status(epoch: Long, status: LiveConnectionStatus) = emit(epoch) { onStatus(status) }
    @Synchronized private fun emit(epoch: Long, action: () -> Unit) { if (current(epoch)) action() }
}

private sealed interface LiveEvent {
    data class Frame(val bytes: ByteArray) : LiveEvent
    data object Reauthenticate : LiveEvent
    data object Deadline : LiveEvent
}

/** One socket generation; nothing from it can mutate its successor. */
private class LiveAttempt(
    private val tickets: LiveSessionClient,
    private val transport: LiveSocketTransport,
    private val requested: Set<String>,
    private val now: () -> Instant,
    private val subscribed: (Set<String>) -> Unit,
    private val frame: (ByteArray) -> Unit,
    private val status: (LiveConnectionStatus) -> Unit,
) {
    private val events = Channel<LiveEvent>(64)
    private lateinit var socket: LiveSocket
    private lateinit var owner: CoroutineScope
    private var timer: Job? = null
    private var initialTicket: LiveTicket? = null
    private var ready = false
    private var authSent = false
    private var reauthPending = false
    private var active = emptySet<String>()
    private var awaitingSubscription = false
    private var tokenExpiry: String? = null
    private val buffered = ArrayDeque<Pair<String, ByteArray>>()
    private var sequence = 0L
    private val sessionId = UUID.randomUUID().toString()
    private var serverSession: String? = null
    private var serverSequence = -1L

    suspend fun run(): Unit = coroutineScope {
        owner = this
        initialTicket = withTimeout(10_000) { tickets.liveTicket() }
        owner.coroutineContext.ensureActive()
        socket = transport.connect(object : LiveSocketObserver {
            override fun frame(bytes: ByteArray) {
                if (bytes.size > LiveFrameDecoder.MAX_FRAME_BYTES || events.trySend(LiveEvent.Frame(bytes)).isFailure) {
                    events.close(IOException("Live frame limit exceeded"))
                }
            }
            override fun closed() { events.close(IOException("Live connection closed")) }
        })
        schedule(10_000, LiveEvent.Deadline)
        try {
            for (event in events) receive(event)
        } finally {
            initialTicket = null
            timer?.cancel(); events.cancel(); socket.cancel()
        }
    }

    private suspend fun receive(event: LiveEvent) {
        when (event) {
            is LiveEvent.Frame -> receiveFrame(event.bytes)
            LiveEvent.Reauthenticate -> authenticate(true)
            LiveEvent.Deadline -> throw IOException("Live authentication deadline exceeded")
        }
    }

    private suspend fun receiveFrame(bytes: ByteArray) {
        val body = controlObject(bytes)
        val type = body["type"]?.jsonPrimitive?.content ?: throw IOException("Missing live frame type")
        when (type) {
            "auth_required", "auth_ok", "auth_complete", "reauth_required", "reauth_ok", "auth_failed", "auth_expired", "subscription_success", "error" -> {
                validateProvenance(body)
                control(type, body)
            }
            else -> dataFrame(body, bytes)
        }
    }

    private suspend fun control(type: String, body: JsonObject) {
        when (type) {
            "auth_required" -> { check(!authSent); authenticate(false) }
            "auth_ok" -> check(authSent && !ready)
            "auth_complete" -> complete(body)
            "subscription_success" -> acknowledge(body)
            "reauth_required" -> {
                check(ready)
                val remaining = Duration.between(now(), Instant.parse(body.getValue("deadline").jsonPrimitive.content)).toMillis()
                require(remaining > 0)
                authenticate(true, remaining.coerceAtMost(10_000))
            }
            "reauth_ok" -> {
                check(ready && reauthPending)
                reauthPending = false
                renew(body.getValue("exp").jsonPrimitive.content)
                status(LiveConnectionStatus.SUBSCRIBED)
            }
            else -> throw IOException("Live authentication rejected")
        }
    }

    private suspend fun authenticate(reauth: Boolean, timeout: Long = 10_000) {
        if (reauthPending) return
        if (reauth) reauthPending = true else authSent = true
        status(LiveConnectionStatus.AUTHENTICATING)
        schedule(timeout, LiveEvent.Deadline)
        val ticket = if (reauth) withTimeout(timeout) { tickets.liveTicket() } else requireNotNull(initialTicket)
        initialTicket = null
        send(if (reauth) "reauth" else "authenticate") { put("ws_token", ticket.credential()) }
    }

    private fun complete(body: JsonObject) {
        check(authSent && !ready && !awaitingSubscription)
        val allowed = body.getValue("available_topics").jsonArray.map { it.jsonPrimitive.content }
        active = requested.filterTo(linkedSetOf()) { topic -> allowed.any { pattern -> permits(pattern, topic) } }
        send("subscribe") { put("topics", JsonArray(active.sorted().map(::JsonPrimitive))) }
        awaitingSubscription = true
        tokenExpiry = body.getValue("ws_token_exp").jsonPrimitive.content
        schedule(10_000, LiveEvent.Deadline)
    }

    private fun acknowledge(body: JsonObject) {
        check(awaitingSubscription && !ready)
        require(body.getValue("action").jsonPrimitive.content == "subscribe")
        val result = body.getValue("status").jsonPrimitive.content
        require(result in setOf("subscribed", "partial"))
        val accepted = topics(body, "topics")
        val denied = topics(body, "denied_topics")
        val registered = topics(body, "active_subscriptions")
        require(accepted.isNotEmpty() && accepted.intersect(denied).isEmpty())
        require(accepted + denied == active && registered == accepted)
        require((result == "partial") == denied.isNotEmpty())
        active = accepted
        awaitingSubscription = false
        ready = true
        renew(requireNotNull(tokenExpiry))
        subscribed(active)
        while (buffered.isNotEmpty()) {
            val (topic, bytes) = buffered.removeFirst()
            if (active.any { permits(it, topic) }) frame(bytes)
        }
    }

    private fun topics(body: JsonObject, key: String): Set<String> =
        body.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun dataFrame(body: JsonObject, bytes: ByteArray) {
        val topic = body["topic"]?.jsonPrimitive?.content ?: return
        if (active.none { permits(it, topic) }) return
        if (ready) frame(bytes)
        else if (awaitingSubscription) {
            check(buffered.size < 64)
            buffered.addLast(topic to bytes)
        }
    }

    private fun renew(expiry: String) {
        val remaining = Duration.between(now(), Instant.parse(expiry)).toMillis()
        require(remaining > 0)
        schedule((remaining / 5 * 4).coerceAtLeast(1), LiveEvent.Reauthenticate)
    }

    private fun schedule(milliseconds: Long, event: LiveEvent) {
        timer?.cancel()
        timer = owner.launch { delay(milliseconds); events.send(event) }
    }

    private fun send(type: String, payload: JsonObjectBuilder.() -> Unit) {
        owner.coroutineContext.ensureActive()
        val message = buildJsonObject {
            put("type", type); put("session_id", sessionId); put("sequence_id", ++sequence)
            put("public_id", UUID.randomUUID().toString()); put("timestamp", now().toString())
            payload()
        }
        if (!socket.send(message.toString())) throw IOException("Live send refused")
    }

    private fun validateProvenance(body: JsonObject) {
        val session = body.getValue("session_id").jsonPrimitive.content
        val seq = body.getValue("sequence_id").jsonPrimitive.long
        val id = body.getValue("public_id").jsonPrimitive.content
        val timestamp = Instant.parse(body.getValue("timestamp").jsonPrimitive.content)
        require(session.isNotBlank() && session.length <= 256 && id.isNotBlank() && id.length <= 256)
        require(seq > serverSequence && timestamp >= Instant.EPOCH)
        require(serverSession == null || serverSession == session)
        serverSession = session; serverSequence = seq
    }

    private fun permits(pattern: String, topic: String): Boolean =
        pattern == topic || (pattern.endsWith('.') && topic.startsWith(pattern))

    private fun controlObject(bytes: ByteArray): JsonObject {
        require(bytes.size <= LiveFrameDecoder.MAX_FRAME_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(boundedJson(text))
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun boundedJson(text: String): Boolean {
        val guard = LiveJsonDepth()
        return text.all(guard::accept) && guard.complete
    }
}

private class LiveJsonDepth {
    private var depth = 0
    private var quoted = false
    private var escaped = false
    val complete: Boolean get() = depth == 0 && !quoted
    fun accept(char: Char): Boolean {
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            !quoted -> depth += change(char)
        }
        return depth in 0..16
    }
    private fun change(char: Char): Int = when (char) {
        '{', '[' -> 1
        '}', ']' -> -1
        else -> 0
    }
}
