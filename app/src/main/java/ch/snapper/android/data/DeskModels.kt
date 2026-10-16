package ch.snapper.android.data

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeskDto(
    @SerialName("public_id") val publicId: String,
    val label: String,
    val description: String? = null,
)

interface DeskClient {
    suspend fun desks(): List<DeskDto>
    suspend fun attachViewer(scope: OrderScope, operatorPublicId: String, username: String)
}

class DeskRejectedException(val statusCode: Int) : IOException("Desk membership request rejected ($statusCode).")

class DeskPreparationException(cause: Throwable) : IOException("Desk membership preparation failed before submission.", cause)
