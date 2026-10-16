package ch.snapper.android.features.system

import ch.snapper.android.UiError
import ch.snapper.android.data.*

/** Payloads only: the shared session transport unwraps envelopes and owns authentication. */
interface SystemClient {
    suspend fun systemHealth(): SystemHealthCheckDataDto
    suspend fun systemStatus(): SystemSystemStatusDataDto
    suspend fun systemStrategies(): List<SystemStrategyProcessDto>
    suspend fun systemBacktests(): List<SystemBacktestRunDataDto>
    suspend fun systemUsers(): List<SystemUserProfileDto>
    suspend fun systemDelegates(): List<SystemDelegateReadDto>
}

enum class SystemFeed(val permission: String) {
    HEALTH("read:system_status"), STATUS("read:system_status"),
    STRATEGIES("read:strategies"), BACKTESTS("read:backtests"),
    USERS("manage:users"), DELEGATES("read:ai_integration");

    fun isAllowed(permissions: Set<String>): Boolean = permission in permissions
}

data class SystemFeedState<T>(val value: T? = null, val isLoading: Boolean = false, val error: UiError? = null)

data class SystemState(
    val permissions: Set<String> = emptySet(),
    val health: SystemFeedState<SystemHealthCheckDataDto> = SystemFeedState(),
    val status: SystemFeedState<SystemSystemStatusDataDto> = SystemFeedState(),
    val strategies: SystemFeedState<List<SystemStrategyProcessDto>> = SystemFeedState(),
    val backtests: SystemFeedState<List<SystemBacktestRunDataDto>> = SystemFeedState(),
    val users: SystemFeedState<List<SystemUserProfileDto>> = SystemFeedState(),
    val delegates: SystemFeedState<List<SystemDelegateReadDto>> = SystemFeedState(),
)

fun interface SystemActions {
    fun refresh(feed: SystemFeed)
}
