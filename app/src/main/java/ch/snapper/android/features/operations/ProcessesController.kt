package ch.snapper.android.features.operations

import ch.snapper.android.data.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI dispatcher confined; root resets on identity, wallet, permission or backend changes. */
class ProcessesController(private val scope: CoroutineScope, private val client: ProcessClient, initialUser: UserDto) {
    private var userScope = OrderScope.from(initialUser)
    private var epoch = 0L
    private var readRevision = 0L
    private val mutable = MutableStateFlow(ProcessesState(permissions = userScope.effectivePermissions))
    val state = mutable.asStateFlow()
    val actions = ProcessesActions(::load, ::request, { mutable.value = state.value.copy(confirmation = null) }, ::confirm)

    fun reset(user: UserDto) {
        epoch++; readRevision++
        userScope = OrderScope.from(user)
        mutable.value = ProcessesState(permissions = userScope.effectivePermissions)
    }
    fun invalidate() {
        epoch++; readRevision++
        mutable.value = ProcessesState()
    }
    fun load() {
        if (state.value.loading || state.value.operations.values.any { it.pending }) return
        val session = epoch
        mutable.value = state.value.copy(loading = true)
        scope.launch { refresh(session) }
    }

    suspend fun refreshAwaited(stillCurrent: () -> Boolean = { true }) {
        val captured = epoch
        state.first { !it.loading && it.operations.values.none { operation -> operation.pending } }
        if (captured != epoch || !stillCurrent()) return
        load()
        state.first { !it.loading }
    }

    private suspend fun refresh(session: Long) {
        if (session != epoch) return
        val revision = ++readRevision
        mutable.value = state.value.copy(loading = true)
        val (summary, summaryFailed) = readSummary()
        val (configs, configuredFailed) = readConfigured()
        if (session != epoch || revision != readRevision) return
        // Read snapshots contain no command acknowledgement/nonce, so they cannot resolve an uncertain mutation.
        mutable.value = state.value.copy(summary = summary, configured = configs, loading = false,
            summaryFailed = summaryFailed, configuredFailed = configuredFailed)
    }

    private suspend fun readSummary(): Pair<ProcessSummaryDto?, Boolean> {
        if ("read:system_status" !in state.value.permissions) return null to false
        return try { client.processSummary() to false }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { state.value.summary to true }
    }

    private suspend fun readConfigured(): Pair<Map<String, ConfiguredProcessDto>, Boolean> {
        if ("read:processes" !in state.value.permissions) return emptyMap<String, ConfiguredProcessDto>() to false
        return try {
            val rows = client.configuredProcesses()
            check(rows.map { it.name }.distinct().size == rows.size)
            rows.associateBy { it.name } to false
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { emptyMap<String, ConfiguredProcessDto>() to true }
    }

    private fun request(name: String, action: ProcessAction) {
        val current = state.value
        if (current.loading || current.operations[name]?.let { it.pending || it.uncertain } == true || action !in current.actions(name)) return
        val target = current.configured[name] ?: return
        mutable.value = current.copy(confirmation = ProcessConfirmation(target, action, if (action == ProcessAction.RESTART && target.managedRemotely) UUID.randomUUID().toString() else null))
    }

    private fun operation(name: String, value: ProcessOperation) {
        mutable.value = state.value.copy(operations = state.value.operations + (name to value))
    }

    private fun confirm() {
        val intent = state.value.confirmation ?: return
        val name = intent.target.name
        if (state.value.operations[name]?.let { it.pending || it.uncertain } == true) return
        mutable.value = state.value.copy(confirmation = null)
        if (state.value.mode(name) == ProcessControlMode.NONE) return
        val session = epoch
        val execution = ProcessExecution(client, userScope, intent, { session == epoch }, { state.value.permissions })
        operation(name, ProcessOperation(pending = true))
        scope.launch { execute(name, session, execution) }
    }

    private suspend fun execute(name: String, session: Long, execution: ProcessExecution) {
        try {
            val result = execution.run()
            if (session != epoch) return
            operation(name, result)
            if (result.outcome in setOf(ProcessOutcome.ACCEPTED, ProcessOutcome.INTENT_SAVED, ProcessOutcome.PARTIAL_RESTART)) refresh(session)
        } catch (error: CancellationException) {
            if (session == epoch && state.value.operations[name]?.pending == true) {
                operation(name, ProcessOperation(outcome = execution.cancellationOutcome))
            }
            throw error
        }
    }
}
