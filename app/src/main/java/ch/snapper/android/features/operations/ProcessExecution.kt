package ch.snapper.android.features.operations

import ch.snapper.android.data.*
import kotlinx.coroutines.CancellationException

/** One confirmed intent. No command is replayed; each restart leg independently revalidates its target. */
internal class ProcessExecution(
    private val client: ProcessClient,
    private val auth: OrderScope,
    private val intent: ProcessConfirmation,
    private val current: () -> Boolean,
    private val permissions: () -> Set<String>,
) {
    private var stopped = false
    private var dispatched = false
    val cancellationOutcome: ProcessOutcome get() = if (stopped) ProcessOutcome.PARTIAL_UNKNOWN else ProcessOutcome.UNKNOWN

    suspend fun run(): ProcessOperation = try {
        val target = freshTarget(intent.target)
        if (target.managedRemotely) remote(target) else local(target)
    } catch (error: CancellationException) {
        throw error
    } catch (_: OrderScopeChangedException) {
        result(if (stopped) ProcessOutcome.PARTIAL_RESTART else ProcessOutcome.STALE_TARGET)
    } catch (error: ProcessRejectedException) {
        result(if (stopped) ProcessOutcome.PARTIAL_RESTART else ProcessOutcome.REJECTED, error.statusCode.toString())
    } catch (_: Exception) {
        result(uncertainOutcome())
    }

    private fun uncertainOutcome(): ProcessOutcome = when {
        stopped && dispatched -> ProcessOutcome.PARTIAL_UNKNOWN
        stopped -> ProcessOutcome.PARTIAL_RESTART
        dispatched -> ProcessOutcome.UNKNOWN
        else -> ProcessOutcome.STALE_TARGET
    }

    private suspend fun freshTarget(expected: ConfiguredProcessDto): ConfiguredProcessDto {
        val rows = client.configuredProcesses()
        if (!current()) throw OrderScopeChangedException()
        val target = rows.singleOrNull { it.name == expected.name } ?: throw OrderScopeChangedException()
        if (!sameProcessTarget(expected, target)) throw OrderScopeChangedException()
        if (processControlMode(target, permissions()) == ProcessControlMode.NONE) throw OrderScopeChangedException()
        return target
    }

    private suspend fun remote(target: ConfiguredProcessDto): ProcessOperation {
        val action = when (intent.action) {
            ProcessAction.START -> "enable"
            ProcessAction.STOP -> "disable"
            ProcessAction.RESTART -> "restart"
        }
        dispatched = true
        val response = client.processDesiredState(auth, target, ProcessDesiredStatePayload(action, intent.nonce))
        check(response.name == target.name && response.action == action && response.status == "success")
        return result(ProcessOutcome.INTENT_SAVED, response.message)
    }

    private suspend fun local(target: ConfiguredProcessDto): ProcessOperation {
        if (intent.action != ProcessAction.START) {
            val failure = stop(target)
            if (failure != null) return failure
        }
        if (intent.action == ProcessAction.STOP) return result(ProcessOutcome.ACCEPTED)
        // A stopped rogue local copy may now resolve to a different remote owner.
        dispatched = false
        val startTarget = if (stopped) freshTarget(target) else target
        if (!current()) throw OrderScopeChangedException()
        dispatched = true
        val response = client.startProcess(auth, startTarget)
        check(response.name == target.name)
        check(response.status in setOf("success", "already_running", "error"))
        return if (response.status == "error") result(if (stopped) ProcessOutcome.PARTIAL_RESTART else ProcessOutcome.REJECTED, response.message)
        else result(ProcessOutcome.ACCEPTED, response.message)
    }

    private suspend fun stop(target: ConfiguredProcessDto): ProcessOperation? {
        dispatched = true
        val response = client.stopProcess(auth, target)
        check(response.name == target.name)
        check(response.status in setOf("success", "not_running", "error"))
        if (response.status == "error") return result(ProcessOutcome.REJECTED, response.message)
        stopped = true
        return null
    }

    private fun result(outcome: ProcessOutcome, message: String? = null) = ProcessOperation(outcome = outcome, message = message)
}
