@file:UseSerializers(DecimalSerializer::class)
package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

@Serializable
data class SystemHealthCheckDataDto(
    @SerialName("public_id") val publicId: String,
    val status: String,
    val version: String,
    val connections: SystemConnectionStatsDto,
    val topics: SystemHealthTopicsDto,
    @SerialName("gap_detection") val gapDetection: SystemGapDetectionStatsDto,
)

@Serializable
data class SystemConnectionStatsDto(
    @SerialName("active_connections") val activeConnections: Long? = null,
    @SerialName("zmq_subscribers") val zmqSubscribers: Long? = null,
    @SerialName("subscriber_tasks") val subscriberTasks: Long? = null,
    @SerialName("active_topics") val activeTopics: Long? = null,
    @SerialName("active_clients") val activeClients: Long? = null,
)

@Serializable
data class SystemHealthTopicsDto(
    val active: Long,
)

@Serializable
data class SystemGapDetectionStatsDto(
    val bridge: SystemGapStatsDto,
    @SerialName("rest_clients") val restClients: Map<String, SystemGapStatsDto>? = null,
)

@Serializable
data class SystemGapStatsDto(
    @SerialName("gaps_detected") val gapsDetected: Long? = null,
    @SerialName("session_resets") val sessionResets: Long? = null,
    val duplicates: Long? = null,
    @SerialName("mid_stream_joins") val midStreamJoins: Long? = null,
    @SerialName("rejected_unstamped") val rejectedUnstamped: Long? = null,
)

@Serializable
data class SystemSystemStatusDataDto(
    @SerialName("public_id") val publicId: String,
    val trader: SystemProcessStatusDto,
    val backtests: Map<String, SystemProcessStatusDto>,
    val strategies: List<SystemStrategyStatusPayloadDto>? = null,
)

@Serializable
data class SystemProcessStatusDto(
    val status: String,
    val pid: Long? = null,
    @SerialName("started_at") val startedAt: String? = null,
    val command: String? = null,
    @SerialName("exit_code") val exitCode: Long? = null,
    val error: String? = null,
)

@Serializable
data class SystemStrategyStatusPayloadDto(
    @SerialName("strategy_name") val strategyName: String,
    val status: String,
    val details: JsonObject? = null,
    @SerialName("signals_generated") val signalsGenerated: Long? = null,
    @SerialName("trades_executed") val tradesExecuted: Long? = null,
    @SerialName("last_signal") val lastSignal: String? = null,
    @SerialName("last_signal_time") val lastSignalTime: String? = null,
    val pnl: BigDecimal? = null,
    val pid: Long? = null,
    val uptime: String? = null,
)

@Serializable
data class SystemStrategyProcessDto(
    @SerialName("public_id") val publicId: String,
    val name: String,
    val running: Boolean,
    val enabled: Boolean,
    val mode: String,
    @SerialName("strategy_class") val strategyClass: String? = null,
    val coordinator: String? = null,
    @SerialName("coordinator_label") val coordinatorLabel: String? = null,
    @SerialName("managed_remotely") val managedRemotely: Boolean? = null,
)

@Serializable
data class SystemBacktestRunDataDto(
    @SerialName("public_id") val publicId: String,
    @SerialName("wallet_public_id") val walletPublicId: String,
    @SerialName("strategy_name") val strategyName: String,
    @SerialName("strategy_params") val strategyParams: JsonObject? = null,
    @SerialName("instrument_public_id") val instrumentPublicId: String,
    val instrument: String? = null,
    val exchange: String,
    val timeframe: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("initial_cash") val initialCash: BigDecimal,
    val status: String,
    @SerialName("execution_mode") val executionMode: String? = null,
    @SerialName("fill_model") val fillModel: String? = null,
    @SerialName("slippage_bps") val slippageBps: BigDecimal? = null,
    @SerialName("commission_bps") val commissionBps: BigDecimal? = null,
    @SerialName("config_hash") val configHash: String? = null,
    @SerialName("target_execution_exchange") val targetExecutionExchange: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val error: String? = null,
)

@Serializable
data class SystemUserProfileDto(
    @SerialName("public_id") val publicId: String,
    val username: String,
    val email: String? = null,
    val role: String,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("operator_public_ids") val operatorPublicIds: List<String>? = null,
    @SerialName("primary_operator_public_id") val primaryOperatorPublicId: String? = null,
    @SerialName("active_wallet_public_id") val activeWalletPublicId: String? = null,
    @SerialName("default_language") val defaultLanguage: String? = null,
    @SerialName("effective_permissions") val effectivePermissions: List<String>? = null,
    @SerialName("delegate_public_id") val delegatePublicId: String? = null,
)

@Serializable
data class SystemDelegateReadDto(
    @SerialName("public_id") val publicId: String,
    val username: String,
    val label: String,
    @SerialName("created_by_user_public_id") val createdByUserPublicId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("is_active") val isActive: Boolean,
    val caps: SystemDelegateCapsBodyDto,
)

@Serializable
data class SystemDelegateCapsBodyDto(
    @SerialName("max_order_quantity_per_instrument") val maxOrderQuantityPerInstrument: JsonObject? = null,
    @SerialName("max_open_orders") val maxOpenOrders: Long? = null,
    @SerialName("max_daily_notional_usd") val maxDailyNotionalUsd: BigDecimal? = null,
    @SerialName("max_cancels_per_minute") val maxCancelsPerMinute: Long? = null,
)
