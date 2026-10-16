package ch.snapper.android.data

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Authentication only; preferences belong to the principal, independently of wallet and push registration. */
interface NotificationDefaultsClient {
    suspend fun notificationDefaults(): List<NotificationDefaultDto>
    suspend fun updateNotificationDefaults(scope: OrderScope, payload: UpdateNotificationDefaultPayload): NotificationDefaultDto
}

@Serializable
data class NotificationDefaultDto(
    @SerialName("public_id") val publicId: String,
    @SerialName("user_public_id") val userPublicId: String,
    @SerialName("alert_type") val alertType: String,
    val enabled: Boolean,
    @SerialName("min_priority") val minPriority: String,
)

/** Wrapped by the shared transport in update_user_alert_default_command. */
@Serializable
data class UpdateNotificationDefaultPayload(
    @SerialName("alert_type") val alertType: String,
    val enabled: Boolean,
    @SerialName("min_priority") val minPriority: String,
)

class NotificationDefaultRejectedException(val statusCode: Int) : IOException("Notification default rejected ($statusCode).")
class NotificationDefaultOutcomeUnknownException(cause: Throwable) : IOException("Notification default outcome unknown.", cause)
