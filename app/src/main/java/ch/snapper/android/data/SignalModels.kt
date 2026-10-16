@file:UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** GET /api/signals; read:signals plus the existing session/CSRF transport. */
fun interface SignalsClient {
    suspend fun signals(walletPublicId: String?): List<SignalDto>
}

@Serializable
data class SignalDto(
    @SerialName("public_id") val publicId: String,
    val instrument: String,
    val exchange: String,
    val side: String,
    val strength: BigDecimal,
    val reason: String,
    @SerialName("fired_at") val firedAt: String,
    val price: BigDecimal? = null,
    @SerialName("strategy_name") val strategyName: String? = null,
    @SerialName("wallet_public_id") val walletPublicId: String? = null,
    @SerialName("operator_public_id") val operatorPublicId: String? = null,
    @SerialName("user_public_id") val userPublicId: String? = null,
    @SerialName("ai_review_public_id") val aiReviewPublicId: String? = null,
)
