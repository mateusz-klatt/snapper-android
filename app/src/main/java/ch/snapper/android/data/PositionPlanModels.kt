package ch.snapper.android.data

import java.io.IOException
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class PositionActionTarget(
    val positionPublicId: String,
    val instrumentPublicId: String,
    val instrument: String,
    val exchange: String,
    val cyclePublicId: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal?,
    val walletPublicId: String,
)

@Serializable
data class PositionBracketPayload(
    @SerialName("position_cycle_public_id") val cyclePublicId: String,
    @SerialName("sl_price") @Serializable(with = OrderNumberSerializer::class) val stopLoss: BigDecimal? = null,
    @SerialName("tp_price") @Serializable(with = OrderNumberSerializer::class) val takeProfit: BigDecimal? = null,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
data class PositionTrailingPayload(
    @SerialName("position_cycle_public_id") val cyclePublicId: String,
    @SerialName("trailing_pct") @Serializable(with = OrderNumberSerializer::class) val distance: BigDecimal,
    @SerialName("min_lock_pct") @Serializable(with = PositionLockSerializer::class) val minimumLock: BigDecimal = BigDecimal.ZERO,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

sealed interface PositionActionIntent {
    val target: PositionActionTarget
    data class Order(override val target: PositionActionTarget, val payload: CreateOrderPayload) : PositionActionIntent
    data class Bracket(override val target: PositionActionTarget, val payload: PositionBracketPayload) : PositionActionIntent
    data class Trailing(override val target: PositionActionTarget, val payload: PositionTrailingPayload) : PositionActionIntent
}

fun interface PositionActionClient {
    suspend fun submit(scope: OrderScope, intent: PositionActionIntent): ExecutionPlanDto
}

class PositionPreparationException(cause: Throwable) : IOException("Position action preparation failed.", cause)
class PositionRejectedException(val statusCode: Int) : IOException("Position action rejected ($statusCode).")

object PositionLockSerializer : kotlinx.serialization.KSerializer<BigDecimal> {
    override val descriptor = OrderNumberSerializer.descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: BigDecimal) {
        if (value.signum() == 0) encoder.encodeInt(0) else OrderNumberSerializer.serialize(encoder, value)
    }
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): BigDecimal = DecimalSerializer.deserialize(decoder)
}
