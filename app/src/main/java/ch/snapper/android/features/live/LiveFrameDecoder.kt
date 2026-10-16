package ch.snapper.android.features.live

import ch.snapper.android.data.*
import ch.snapper.android.features.market.marketTimeframes
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import kotlinx.serialization.json.Json

/** Limits apply before JSON parsing. Invalid, replay and mismatched events never enter market state. */
class LiveFrameDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(bytes: ByteArray, selection: LiveSelection, now: Instant): LiveMarketFrame? {
        if (bytes.size > MAX_FRAME_BYTES || !validSelection(selection)) return null
        return runCatching {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            if (!boundedNesting(text)) return null
            val wire = json.decodeFromString<LiveWireFrame>(text)
            if (!validHeader(wire, selection, now)) return null
            val stamp = LiveStamp(wire.sessionId, wire.sequenceId, wire.publicId, Instant.parse(wire.timestamp))
            when (wire.type) {
                "candle" -> candle(wire, selection, stamp, now)
                "tick" -> tick(wire, selection, stamp)
                else -> null
            }
        }.getOrNull()
    }

    private fun validHeader(wire: LiveWireFrame, selection: LiveSelection, now: Instant): Boolean =
        wire.exchange == selection.exchange && wire.instrument == selection.instrument && wire.origin == "live" &&
            wire.sequenceId >= 0 && validIdentifier(wire.publicId) && validIdentifier(wire.sessionId) &&
            validTime(wire.timestamp, now) != null && boundedDecimal(wire.volume) && wire.volume.signum() >= 0

    private fun candle(wire: LiveWireFrame, selection: LiveSelection, stamp: LiveStamp, now: Instant): LiveMarketFrame.Candle? {
        if (wire.topic != selection.candleTopic || wire.timeframe != selection.timeframe) return null
        val openAt = wire.openAt?.let { validTime(it, now) } ?: return null
        if (openAt > stamp.timestamp) return null
        val open = wire.open ?: return null
        val high = wire.high ?: return null
        val low = wire.low ?: return null
        val close = wire.close ?: return null
        val value = CandleDto(wire.instrument, wire.exchange, selection.timeframe, openAt.toString(), open, high, low, close, wire.volume, wire.publicId)
        if (!validCandle(value)) return null
        return LiveMarketFrame.Candle(selection, stamp, value, openAt, wire.complete)
    }

    private fun tick(wire: LiveWireFrame, selection: LiveSelection, stamp: LiveStamp): LiveMarketFrame.Tick? {
        if (wire.topic != selection.tickTopic) return null
        if (listOf(wire.last, wire.bid, wire.ask).filterNotNull().any { !boundedDecimal(it) }) return null
        return LiveMarketFrame.Tick(selection, stamp, wire.last, wire.bid, wire.ask, wire.volume, wire.isDelayed, wire.isExtendedHours)
    }

    companion object {
        const val MAX_FRAME_BYTES = 65_536
        fun validSelection(value: LiveSelection): Boolean = value.exchange.isNotBlank() && value.instrument.isNotBlank() &&
            value.exchange.length <= 128 && value.instrument.length <= 256 && value.timeframe in marketTimeframes
        private fun validIdentifier(value: String): Boolean = value.isNotBlank() && value.length <= 256
        internal fun boundedDecimal(value: BigDecimal): Boolean = value.precision() <= 128 && value.scale() in -128..128
        internal fun validTime(value: String, now: Instant): Instant? = runCatching { Instant.parse(value) }
            .getOrNull()?.takeIf { it >= Instant.EPOCH && it <= now }
        internal fun validCandle(value: CandleDto): Boolean =
            listOf(value.open, value.high, value.low, value.close, value.volume).all(::boundedDecimal) &&
                value.volume.signum() >= 0 && value.low <= value.open && value.low <= value.close &&
                value.high >= value.open && value.high >= value.close && value.low <= value.high

        private fun boundedNesting(text: String): Boolean {
            val guard = JsonDepthGuard()
            return text.all { guard.accept(it) } && guard.complete
        }
    }
}

private class JsonDepthGuard {
    private var depth = 0
    private var quoted = false
    private var escaped = false
    val complete: Boolean get() = !quoted && depth == 0
    fun accept(char: Char): Boolean {
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            !quoted -> depth += depthChange(char)
        }
        return depth in 0..16
    }
    private fun depthChange(char: Char): Int = when (char) {
        '{', '[' -> 1
        '}', ']' -> -1
        else -> 0
    }
}
