@file:UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** GET /api/executions requires read:orders and accepts an optional wallet_public_id. */
fun interface RecordsClient {
    suspend fun executions(walletPublicId: String?): List<ExecutionDto>
}

@Serializable
data class ExecutionDto(
    @SerialName("public_id") val publicId: String,
    val instrument: String,
    val exchange: String,
    val side: String,
    val size: BigDecimal,
    val price: BigDecimal,
    val fee: BigDecimal,
    @SerialName("fee_asset") val feeAsset: String,
    val status: String,
    @SerialName("executed_at") val executedAt: String,
    @SerialName("wallet_public_id") val walletPublicId: String? = null,
    @SerialName("trade_id") val tradeId: String? = null,
    @SerialName("client_order_id") val clientOrderId: String = "",
)
