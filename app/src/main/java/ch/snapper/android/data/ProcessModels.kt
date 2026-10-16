@file:UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.io.IOException
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

interface ProcessClient {
    suspend fun processSummary(): ProcessSummaryDto
    suspend fun configuredProcesses(): List<ConfiguredProcessDto>
    suspend fun startProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStartDto
    suspend fun stopProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStopDto
    suspend fun processDesiredState(scope: OrderScope, target: ConfiguredProcessDto, payload: ProcessDesiredStatePayload): ProcessDesiredStateDto
}

@Serializable data class ProcessCategoryDto(val running: Int, val total: Int)
@Serializable data class ProcessSummaryDto(
    val feeds: ProcessCategoryDto, val strategies: ProcessCategoryDto,
    val executors: ProcessCategoryDto, val brokers: ProcessCategoryDto,
    val processes: List<ProcessSummaryRowDto>,
    val coordinator: String? = null,
    @SerialName("coordinator_label") val coordinatorLabel: String? = null,
)
@Serializable data class ProcessSummaryRowDto(
    val name: String, val running: Boolean, val enabled: Boolean, val role: String, val lifecycle: String,
    @SerialName("active_public_id") val activePublicId: String? = null,
    @SerialName("rss_bytes") val rssBytes: Long? = null,
    @SerialName("cpu_percent") val cpuPercent: BigDecimal? = null,
)
/** Required routing discriminators fail closed if a backend omits them. No process parameters are sent by this client. */
@Serializable data class ConfiguredProcessDto(
    val name: String, val enabled: Boolean, val running: Boolean, val role: String, val kind: String,
    @SerialName("managed_remotely") val managedRemotely: Boolean,
    val coordinator: String? = null,
    @SerialName("coordinator_label") val coordinatorLabel: String? = null,
    @SerialName("wallet_public_id") val walletPublicId: String? = null,
    @SerialName("active_public_id") val activePublicId: String? = null,
)
@Serializable data class ProcessStartPayload(val mode: String? = null, val parameters: kotlinx.serialization.json.JsonObject? = null)
@Serializable data class ProcessStartDto(val name: String, val status: String, @SerialName("process_public_id") val processPublicId: String? = null, val message: String? = null)
@Serializable data class ProcessStopDto(val name: String, val status: String, val message: String? = null)
@Serializable data class ProcessDesiredStatePayload(val action: String, @SerialName("restart_nonce") val restartNonce: String? = null)
@Serializable data class ProcessDesiredStateDto(val name: String, val status: String, val action: String,
    @SerialName("managed_remotely") val managedRemotely: Boolean, val coordinator: String? = null, val message: String? = null)
class ProcessRejectedException(val statusCode: Int) : IOException("Process command rejected ($statusCode).")
class ProcessOutcomeUnknownException(cause: Throwable) : IOException("Process command outcome unknown.", cause)
