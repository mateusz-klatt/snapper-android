package ch.snapper.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PushConfiguration(
    @SerialName("configuration_id") val configurationId: String,
    val protocol: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("application_id") val applicationId: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("messaging_sender_id") val messagingSenderId: String,
)
@Serializable
data class PushConfigurationInfo(val availability: String, val configuration: PushConfiguration? = null)
@Serializable
data class PushRegistrationState(val revision: String, @SerialName("current_binding_id") val currentBindingId: String? = null)
@Serializable
data class PushRegistrationCommand(
    val protocol: String,
    @SerialName("configuration_id") val configurationId: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("binding_id") val bindingId: String,
    @SerialName("expected_revision") val expectedRevision: String,
    @SerialName("firebase_installation_id") val firebaseInstallationId: String,
    @SerialName("expected_user_public_id") val expectedUserPublicId: String,
    @SerialName("app_version") val appVersion: String?,
)
@Serializable
data class PushRegistration(
    @SerialName("public_id") val publicId: String,
    @SerialName("user_public_id") val userPublicId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("binding_id") val bindingId: String,
    @SerialName("configuration_id") val configurationId: String,
    val protocol: String,
    val platform: String,
    @SerialName("package_name") val packageName: String,
    val status: String,
)
@Serializable
data class PushPreference(
    @SerialName("public_id") val publicId: String,
    @SerialName("device_public_id") val devicePublicId: String,
    @SerialName("alert_type") val alertType: String,
    val enabled: Boolean,
    @SerialName("min_priority") val minPriority: String,
    @SerialName("operator_public_id") val operatorPublicId: String? = null,
    @SerialName("wallet_public_id") val walletPublicId: String? = null,
)
data class PushIdentity(val backend: String, val epoch: Long, val actor: OrderScope)
data class PushBinding(val identity: PushIdentity, val client: PushClient)
interface PushClient {
    fun bindPush(identity: PushIdentity): PushBinding = PushBinding(identity, this)
    suspend fun pushConfiguration(): PushConfigurationInfo
    suspend fun pushRegistrationState(configurationId: String, installationId: String): PushRegistrationState
    suspend fun registerPush(command: PushRegistrationCommand): PushRegistration
    suspend fun pushInventory(): List<PushRegistration>
    suspend fun pushPreferences(registrationId: String): List<PushPreference>
    suspend fun revokePush(registrationId: String)
    suspend fun pushAlert(alertId: String): AlertDto
}
class PushRejectedException(val code: String? = null, val statusCode: Int? = null) : Exception()
class PushUncertainException : Exception()
class PushScopeChangedException : Exception()
