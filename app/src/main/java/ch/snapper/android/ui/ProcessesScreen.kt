package ch.snapper.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.data.ProcessSummaryRowDto
import ch.snapper.android.features.operations.*

internal fun LazyListScope.processesScreen(state: ProcessesState, actions: ProcessesActions) {
    item { ProcessesHeader(state, actions) }
    items(state.summary?.processes.orEmpty().sortedBy { it.name }, key = { it.name }) { row -> ProcessRow(row, state, actions) }
}

@Composable
private fun ProcessesHeader(state: ProcessesState, actions: ProcessesActions) {
    Text(stringResource(R.string.process_title), style = MaterialTheme.typography.titleLarge)
    TextButton(onClick = actions.refresh, enabled = !state.loading && state.operations.values.none { it.pending }, modifier = Modifier.testTag("process.refresh")) { Text(stringResource(R.string.refresh)) }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.summaryFailed) Text(stringResource(R.string.process_load_failed))
    if (state.configuredFailed) Text(stringResource(R.string.process_config_failed))
    state.summary?.let { summary ->
        Metric(R.string.process_feeds, "${summary.feeds.running}/${summary.feeds.total}")
        Metric(R.string.process_strategies, "${summary.strategies.running}/${summary.strategies.total}")
        Metric(R.string.process_executors, "${summary.executors.running}/${summary.executors.total}")
        Metric(R.string.process_brokers, "${summary.brokers.running}/${summary.brokers.total}")
        if (summary.processes.isEmpty()) Text(stringResource(R.string.process_empty))
    }
}

@Composable
private fun ProcessRow(row: ProcessSummaryRowDto, state: ProcessesState, actions: ProcessesActions) {
    RecordCard(row.name) {
        Metric(R.string.status, stringResource(if (row.running) R.string.process_running else R.string.process_stopped))
        Text(row.role)
        Metric(R.string.process_memory, row.rssBytes?.let { "$it B" } ?: "—")
        Metric(R.string.process_cpu, row.cpuPercent?.let { "${it.display()}%" } ?: "—")
        (state.configured[row.name]?.coordinatorLabel ?: state.configured[row.name]?.coordinator)?.let { Text(it) }
        val operation = state.operations[row.name]
        if (operation?.pending == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        operation?.outcome?.let { Text(stringResource(outcomeLabel(it)), modifier = Modifier.testTag("process.${row.name}.outcome")) }
        operation?.message?.let { Text(it) }
        ProcessControls(row.name, state, actions)
        if (state.confirmation?.target?.name == row.name) ProcessConfirmationDialog(state, actions)
    }
}

@Composable
private fun ProcessControls(name: String, state: ProcessesState, actions: ProcessesActions) {
    val operation = state.operations[name]
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.actions(name).forEach { action ->
            OutlinedButton(onClick = { actions.request(name, action) }, enabled = !state.loading && operation?.pending != true && operation?.uncertain != true,
                modifier = Modifier.testTag("process.$name.${action.name.lowercase()}")) {
                Text(stringResource(actionLabel(action, state.mode(name))))
            }
        }
    }
}

@Composable
private fun ProcessConfirmationDialog(state: ProcessesState, actions: ProcessesActions) {
    val intent = state.confirmation ?: return
    AlertDialog(onDismissRequest = actions.dismiss,
        title = { Text(stringResource(R.string.process_confirm)) },
        text = { Column { Text(intent.target.name); Text(stringResource(actionLabel(intent.action, state.mode(intent.target.name)))); Text(stringResource(R.string.process_confirm_message)) } },
        confirmButton = { TextButton(onClick = actions.confirm, modifier = Modifier.testTag("process.confirm")) { Text(stringResource(R.string.process_confirm)) } },
        dismissButton = { TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.process_cancel)) } })
}

private fun actionLabel(action: ProcessAction, mode: ProcessControlMode): Int = when (action) {
    ProcessAction.START -> if (mode == ProcessControlMode.REMOTE) R.string.process_enable else R.string.process_start
    ProcessAction.STOP -> if (mode == ProcessControlMode.REMOTE) R.string.process_disable else R.string.process_stop
    ProcessAction.RESTART -> R.string.process_restart
}

private fun outcomeLabel(outcome: ProcessOutcome): Int = when (outcome) {
    ProcessOutcome.ACCEPTED -> R.string.process_accepted
    ProcessOutcome.INTENT_SAVED -> R.string.process_intent_saved
    ProcessOutcome.REJECTED -> R.string.process_rejected
    ProcessOutcome.STALE_TARGET -> R.string.process_stale
    ProcessOutcome.PARTIAL_RESTART -> R.string.process_partial
    ProcessOutcome.UNKNOWN -> R.string.process_unknown
    ProcessOutcome.PARTIAL_UNKNOWN -> R.string.process_partial_unknown
}
