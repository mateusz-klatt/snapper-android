package ch.snapper.android.features.operations

import ch.snapper.android.data.ConfiguredProcessDto
import ch.snapper.android.data.ProcessSummaryDto

enum class ProcessControlMode { NONE, LOCAL, REMOTE }
enum class ProcessAction { START, STOP, RESTART }
enum class ProcessOutcome { ACCEPTED, INTENT_SAVED, REJECTED, STALE_TARGET, PARTIAL_RESTART, UNKNOWN, PARTIAL_UNKNOWN }
data class ProcessOperation(val pending: Boolean = false, val outcome: ProcessOutcome? = null, val message: String? = null) {
    val uncertain: Boolean get() = outcome == ProcessOutcome.UNKNOWN || outcome == ProcessOutcome.PARTIAL_UNKNOWN
}
data class ProcessConfirmation(val target: ConfiguredProcessDto, val action: ProcessAction, val nonce: String?)
data class ProcessesState(
    val permissions: Set<String> = emptySet(), val summary: ProcessSummaryDto? = null,
    val configured: Map<String, ConfiguredProcessDto> = emptyMap(),
    val loading: Boolean = false, val summaryFailed: Boolean = false, val configuredFailed: Boolean = false,
    val operations: Map<String, ProcessOperation> = emptyMap(), val confirmation: ProcessConfirmation? = null,
) {
    fun mode(name: String): ProcessControlMode = processControlMode(configured[name], permissions)
    fun actions(name: String): List<ProcessAction> {
        val row = summary?.processes?.find { it.name == name } ?: return emptyList()
        if (mode(name) == ProcessControlMode.NONE) return emptyList()
        return buildList {
            add(if (mode(name) == ProcessControlMode.REMOTE) {
                if (configured[name]?.enabled == true) ProcessAction.STOP else ProcessAction.START
            } else if (row.running) ProcessAction.STOP else ProcessAction.START)
            if (row.running || (mode(name) == ProcessControlMode.REMOTE && configured[name]?.enabled == true)) add(ProcessAction.RESTART)
        }
    }
}
fun processControlMode(target: ConfiguredProcessDto?, permissions: Set<String>): ProcessControlMode = when {
    "manage:processes" !in permissions || target == null -> ProcessControlMode.NONE
    target.role in setOf("strategy", "backtest") || target.kind != "instance" -> ProcessControlMode.NONE
    target.managedRemotely -> ProcessControlMode.REMOTE
    else -> ProcessControlMode.LOCAL
}
fun sameProcessTarget(first: ConfiguredProcessDto, second: ConfiguredProcessDto): Boolean =
    first.name == second.name && first.role == second.role && first.kind == second.kind &&
        first.managedRemotely == second.managedRemotely && first.coordinator == second.coordinator && first.walletPublicId == second.walletPublicId

data class ProcessesActions(val refresh: () -> Unit, val request: (String, ProcessAction) -> Unit, val dismiss: () -> Unit, val confirm: () -> Unit)
