package ch.snapper.android

import ch.snapper.android.features.live.LiveFrameDecoder
import ch.snapper.android.features.live.LiveRefreshTarget
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Event contents are never rendered or applied as account truth; validated headers only invalidate authorized REST reads. */
internal class LiveEventRouting {
    private companion object {
        const val ORDER_EVENTS_ROOT = "orders.events."
        const val READ_ORDERS = "read:orders"
        const val READ_SIGNALS = "read:signals"
        const val AI_REVIEWS_ROOT = "ai_reviews."
        const val READ_SYSTEM_STATUS = "read:system_status"
    }

    private data class Rule(val root: String, val target: LiveRefreshTarget, val permission: String)
    private val json = Json { ignoreUnknownKeys = true }
    private val sequence = linkedMapOf<Pair<String, String>, Long>()
    private val rules = mapOf(
        "order" to Rule(ORDER_EVENTS_ROOT, LiveRefreshTarget.ORDERS, READ_ORDERS),
        "order_event" to Rule(ORDER_EVENTS_ROOT, LiveRefreshTarget.ORDERS, READ_ORDERS),
        "order_cancel" to Rule(ORDER_EVENTS_ROOT, LiveRefreshTarget.ORDERS, READ_ORDERS),
        "execution" to Rule(ORDER_EVENTS_ROOT, LiveRefreshTarget.ORDERS, READ_ORDERS),
        "signal" to Rule("signals.", LiveRefreshTarget.SIGNALS, READ_SIGNALS),
        "ai_review.request" to Rule(AI_REVIEWS_ROOT, LiveRefreshTarget.AI_REVIEWS, ""),
        "ai_review.decision_ack" to Rule(AI_REVIEWS_ROOT, LiveRefreshTarget.AI_REVIEWS, ""),
        "ai_review.caps_violation" to Rule(AI_REVIEWS_ROOT, LiveRefreshTarget.AI_REVIEWS, ""),
        "process_summary_event" to Rule("processes.events.summary.", LiveRefreshTarget.PROCESSES, READ_SYSTEM_STATUS),
        "process_configured_event" to Rule("processes.events.configured.", LiveRefreshTarget.PROCESSES, "manage:processes"),
        "process_run_event" to Rule("processes.events.runs.", LiveRefreshTarget.PROCESSES, "manage:processes"),
        "strategy_list_event" to Rule("strategies.events.list.", LiveRefreshTarget.STRATEGIES, "read:strategies"),
        "heartbeat" to Rule("system.heartbeats.", LiveRefreshTarget.SYSTEM, READ_SYSTEM_STATUS),
    )

    fun clear() { sequence.clear() }
    fun topics(identity: LiveIdentity): Set<String> = rules.values.filter { allowed(it, identity) }.map { it.root }.toSet()

    fun permitted(target: LiveRefreshTarget, identity: LiveIdentity): Boolean = when (target) {
        LiveRefreshTarget.ORDERS -> READ_ORDERS in identity.auth.effectivePermissions
        LiveRefreshTarget.SIGNALS -> READ_SIGNALS in identity.auth.effectivePermissions
        LiveRefreshTarget.AI_REVIEWS -> "read:ai_reviews" in identity.auth.effectivePermissions ||
            (!identity.delegatePublicId.isNullOrBlank() && READ_SIGNALS in identity.auth.effectivePermissions)
        LiveRefreshTarget.PROCESSES -> identity.auth.effectivePermissions.any { it in setOf("read:processes", READ_SYSTEM_STATUS) }
        LiveRefreshTarget.STRATEGIES -> "read:strategies" in identity.auth.effectivePermissions
        LiveRefreshTarget.SYSTEM -> READ_SYSTEM_STATUS in identity.auth.effectivePermissions
    }

    private fun allowed(rule: Rule, identity: LiveIdentity): Boolean {
        if (!permitted(rule.target, identity)) return false
        if (rule.target == LiveRefreshTarget.AI_REVIEWS) {
            return identity.auth.effectivePermissions.any { it in setOf(READ_SIGNALS, "create:orders") }
        }
        return rule.permission in identity.auth.effectivePermissions
    }

    fun route(bytes: ByteArray, identity: LiveIdentity, now: Instant): LiveRefreshTarget? = runCatching {
        if (bytes.size > LiveFrameDecoder.MAX_FRAME_BYTES) return null
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val depth = EventDepth()
        if (!text.all(depth::accept) || !depth.complete) return null
        val event = json.decodeFromString<InvalidationFrame>(text)
        val rule = rules[event.type] ?: return null
        if (!valid(event, rule, identity, now)) return null
        val key = event.sessionId to event.topic
        if (event.sequenceId <= (sequence[key] ?: -1L)) return null
        sequence.remove(key)
        sequence[key] = event.sequenceId
        if (sequence.size > 128) sequence.remove(sequence.keys.first())
        rule.target
    }.getOrNull()

    private fun valid(event: InvalidationFrame, rule: Rule, identity: LiveIdentity, now: Instant): Boolean =
        allowed(rule, identity) && event.topic.startsWith(rule.root) && event.topic.length > rule.root.length &&
            event.topic.length <= 512 && event.origin == "live" && event.sequenceId >= 0 &&
            identifier(event.sessionId) && identifier(event.publicId) && validTime(event.timestamp, now) &&
            walletMatches(event.walletPublicId, identity.auth.activeWalletPublicId)

    private fun identifier(value: String): Boolean = value.isNotBlank() && value.length <= 256
    private fun validTime(value: String, now: Instant): Boolean = runCatching { Instant.parse(value) }
        .getOrNull()?.let { it >= Instant.EPOCH && it <= now } == true
    private fun walletMatches(wallet: String?, selected: String?): Boolean = wallet.isNullOrBlank() || selected == null || wallet == selected
}

@Serializable
private data class InvalidationFrame(
    val type: String, val topic: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("sequence_id") val sequenceId: Long,
    @SerialName("public_id") val publicId: String,
    val timestamp: String,
    @SerialName("wallet_public_id") val walletPublicId: String? = null,
    val origin: String = "live",
)

private class EventDepth {
    private var depth = 0
    private var quoted = false
    private var escaped = false
    val complete: Boolean get() = !quoted && depth == 0
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
        '[', '{' -> 1
        ']', '}' -> -1
        else -> 0
    }
}
