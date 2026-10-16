@file:UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Envelope<T>(
    val payload: T,
    val type: String? = null,
    @SerialName("sequence_id") val sequenceId: Long = 0,
    @SerialName("public_id") val publicId: String = "",
    val timestamp: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val topic: String? = null,
    val count: Int? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class UserDto(
    val username: String,
    val role: String,
    @SerialName("public_id") val publicId: String = "",
    val email: String? = null,
    @SerialName("primary_operator_public_id") val primaryOperatorPublicId: String? = null,
    @SerialName("active_wallet_public_id") val activeWalletPublicId: String? = null,
    @SerialName("effective_permissions") val effectivePermissions: List<String>? = null,
    @SerialName("delegate_public_id") val delegatePublicId: String? = null,
) {
    fun hasPermission(permission: String): Boolean = effectivePermissions?.contains(permission) == true
}

@Serializable
data class WalletDto(
    @SerialName("public_id") val publicId: String,
    val label: String,
    val description: String? = null,
    @SerialName("is_paper") val isPaper: Boolean,
)

@Serializable
data class PositionDto(
    @SerialName("public_id") val publicId: String,
    val instrument: String,
    val exchange: String,
    val quantity: BigDecimal,
    @SerialName("realized_pnl") val realizedPnl: BigDecimal,
    @SerialName("average_price") val averagePrice: BigDecimal? = null,
    @SerialName("unrealized_pnl") val unrealizedPnl: BigDecimal? = null,
    @SerialName("mark_price") val markPrice: BigDecimal? = null,
    @SerialName("wallet_public_id") val walletPublicId: String? = null,
    @SerialName("instrument_public_id") val instrumentPublicId: String = "",
    val mode: String = "",
    @SerialName("position_cycle_public_id") val positionCyclePublicId: String? = null,
)

@Serializable
data class OrderDto(
    @SerialName("public_id") val publicId: String,
    @SerialName("client_order_id") val clientOrderId: String,
    val instrument: String,
    val exchange: String,
    val side: String,
    val status: String,
    @SerialName("order_type") val orderType: String,
    val size: BigDecimal,
    @SerialName("filled_size") val filledSize: BigDecimal,
    @SerialName("created_at") val createdAt: String,
    val price: BigDecimal? = null,
    @SerialName("average_price") val averagePrice: BigDecimal? = null,
    @SerialName("plan_public_id") val planPublicId: String? = null,
    @SerialName("wallet_public_id") val walletPublicId: String? = null,
)

@Serializable
data class AlertDto(
    @SerialName("public_id") val publicId: String,
    val timestamp: String,
    @SerialName("alert_type") val alertType: String,
    val priority: String,
    @SerialName("is_safety_critical") val isSafetyCritical: Boolean = false,
    val title: String = "",
    val body: String = "",
    @SerialName("thread_key") val threadKey: String? = null,
    @SerialName("source_topic") val sourceTopic: String? = null,
) {
    val displayTitle: String get() = title.ifBlank { alertType }
}

@Serializable
data class CandleDto(
    val instrument: String,
    val exchange: String,
    val timeframe: String,
    @SerialName("open_at") val openAt: String,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    @SerialName("public_id") val publicId: String = "",
    val complete: Boolean? = null,
    val timestamp: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("sequence_id") val sequenceId: Long? = null,
)

data class DashboardSnapshot(
    val user: UserDto,
    val wallets: List<WalletDto>,
    val positions: List<PositionDto>,
    val orders: List<OrderDto>,
    val alerts: List<AlertDto>,
)

@Serializable
internal data class LoginPayload(val username: String, val password: String, @SerialName("remember_me") val rememberMe: Boolean)
@Serializable
internal data class RefreshPayload(
    @SerialName("active_wallet_public_id") val activeWalletPublicId: String? = null,
    @SerialName("clear_active_wallet") val clearActiveWallet: Boolean? = null,
)
@Serializable
internal data class AuthPayload(val user: UserDto)


@Serializable
internal class LiveTicketPayload(
    @SerialName("ws_token") val token: String,
    @SerialName("ws_token_exp") val expiresAt: String,
    @SerialName("expires_in") val expiresIn: Int,
) {
    override fun toString(): String = "LiveTicketPayload(redacted)"
}
