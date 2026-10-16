@file:kotlinx.serialization.UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport DTO for the actual flat candle/tick event, never a REST payload envelope. */
@Serializable
internal data class LiveWireFrame(
    val type: String,
    @SerialName("sequence_id") val sequenceId: Long,
    @SerialName("public_id") val publicId: String,
    @SerialName("session_id") val sessionId: String,
    val timestamp: String,
    val topic: String? = null,
    val exchange: String,
    val instrument: String,
    val timeframe: String? = null,
    @SerialName("open_at") val openAt: String? = null,
    val open: BigDecimal? = null,
    val high: BigDecimal? = null,
    val low: BigDecimal? = null,
    val close: BigDecimal? = null,
    val volume: BigDecimal,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val last: BigDecimal? = null,
    val complete: Boolean = true,
    @SerialName("is_delayed") val isDelayed: Boolean = false,
    @SerialName("is_extended_hours") val isExtendedHours: Boolean? = null,
    val origin: String = "live",
)

data class LiveSelection(val exchange: String, val instrument: String, val timeframe: String) {
    val candleTopic: String get() = "market.$exchange.$instrument.candles.$timeframe"
    val tickTopic: String get() = "market.$exchange.$instrument.ticks"
}

data class LiveStamp(val sessionId: String, val sequenceId: Long, val publicId: String, val timestamp: Instant)
sealed interface LiveMarketFrame {
    val selection: LiveSelection
    val stamp: LiveStamp
    data class Candle(override val selection: LiveSelection, override val stamp: LiveStamp,
        val value: CandleDto, val openAt: Instant, val complete: Boolean) : LiveMarketFrame
    data class Tick(override val selection: LiveSelection, override val stamp: LiveStamp,
        val last: BigDecimal?, val bid: BigDecimal?, val ask: BigDecimal?, val volume: BigDecimal,
        val isDelayed: Boolean, val isExtendedHours: Boolean?) : LiveMarketFrame
}
