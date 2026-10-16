package ch.snapper.android.data

import java.io.IOException
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class OrderScope(
    val publicId: String,
    val username: String,
    val activeWalletPublicId: String?,
    val effectivePermissions: Set<String>,
) {
    companion object {
        fun from(user: UserDto): OrderScope = OrderScope(user.publicId, user.username, user.activeWalletPublicId, user.effectivePermissions.orEmpty().toSet())
    }
}

@Serializable
data class InstrumentDto(
    @SerialName("instrument_public_id") val instrumentPublicId: String,
    @SerialName("symbol_public_id") val symbolPublicId: String,
    val symbol: String,
    val exchange: String,
    @SerialName("can_trade") val canTrade: Boolean,
    @SerialName("instrument_resolved") val instrumentResolved: Boolean,
    @SerialName("can_market_data") val canMarketData: Boolean = false,
    @SerialName("instrument_kind") val instrumentKind: String? = null,
)

@Serializable
data class CreateOrderPayload(
    val instrument: String,
    @SerialName("instrument_public_id") val instrumentPublicId: String,
    val exchange: String,
    val mode: String,
    val side: String,
    @SerialName("order_type") val orderType: String,
    @Serializable(with = OrderNumberSerializer::class) val quantity: BigDecimal,
    @Serializable(with = OrderNumberSerializer::class) val price: BigDecimal? = null,
    @SerialName("stop_price") @Serializable(with = OrderNumberSerializer::class) val stopPrice: BigDecimal? = null,
    @SerialName("wallet_public_id") val walletPublicId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val leverage: Int? = null,
    @SerialName("reduce_only") val reduceOnly: Boolean = false,
    @SerialName("time_in_force") val timeInForce: String = "GTC",
    @SerialName("post_only") val postOnly: Boolean = false,
)

@Serializable
data class ExecutionPlanDto(
    @SerialName("public_id") val publicId: String,
    val status: String,
    @SerialName("wallet_public_id") val walletPublicId: String,
)

@Serializable
internal data class CancelOrderPayload(val reason: String? = null)

class OrderScopeChangedException : IOException("Order session scope changed. Refresh before submitting.")
class OrderRejectedException(val statusCode: Int, val errorCode: String? = null) : IOException("Order request rejected ($statusCode).")
class OrderOutcomeUnknownException(cause: Throwable) : IOException("Order outcome is unknown. Refresh before taking further action.", cause)
