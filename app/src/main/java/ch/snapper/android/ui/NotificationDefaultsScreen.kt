package ch.snapper.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.features.notifications.NotificationDefaultRow
import ch.snapper.android.features.notifications.NotificationDefaultIssue
import ch.snapper.android.features.notifications.NotificationDefaultsActions
import ch.snapper.android.features.notifications.NotificationDefaultsState
import ch.snapper.android.features.notifications.notificationPriorities

internal fun LazyListScope.notificationDefaultsScreen(state: NotificationDefaultsState, actions: NotificationDefaultsActions) {
    item {
        Text(stringResource(R.string.notification_defaults_title))
        Text(stringResource(R.string.notification_defaults_footer))
        TextButton(onClick = actions.refresh, enabled = state.active && !state.loading && state.rows.none { it.pending }) { Text(stringResource(R.string.refresh)) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.loadFailed) Text(stringResource(R.string.notification_defaults_load_failed))
    }
    items(state.rows, key = { it.alertType }) { row ->
        NotificationDefaultCard(state, row, actions)
    }
}

@Composable
private fun NotificationDefaultCard(state: NotificationDefaultsState, row: NotificationDefaultRow, actions: NotificationDefaultsActions) {
    val editable = state.active && state.canManage && state.loaded && !state.loading && row.editable
    RecordCard(stringResource(notificationTypeLabel(row.alertType))) {
        Column(Modifier.testTag("notification.${row.alertType}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notification_defaults_enabled))
                Switch(checked = row.enabled, onCheckedChange = { actions.setEnabled(row.alertType, it) }, enabled = editable)
            }
            Text(stringResource(R.string.notification_defaults_priority))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                notificationPriorities.forEach { priority ->
                    FilterChip(selected = row.minPriority == priority, onClick = { actions.setPriority(row.alertType, priority) }, enabled = editable,
                        label = { Text(stringResource(notificationPriorityLabel(priority))) })
                }
            }
            NotificationDefaultStatus(row)
            Button(onClick = { actions.save(row.alertType) }, enabled = editable && row.canSave,
                modifier = Modifier.testTag("notification.${row.alertType}.save")) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun NotificationDefaultStatus(row: NotificationDefaultRow) {
    if (row.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
    row.issue?.let { issue ->
        Text(stringResource(when (issue) {
            NotificationDefaultIssue.UNKNOWN -> R.string.order_unknown_outcome
            NotificationDefaultIssue.LOAD -> R.string.notification_defaults_load_failed
            NotificationDefaultIssue.REJECTED, NotificationDefaultIssue.SCOPE_CHANGED -> R.string.notification_defaults_save_failed
        }))
    }
    if (row.saved) Text(stringResource(R.string.notification_defaults_saved))
}

private fun notificationTypeLabel(type: String): Int = when (type) {
    "order_fill_full" -> R.string.notification_defaults_fill
    "order_rejected" -> R.string.notification_defaults_rejected
    "position_stop_loss_fired" -> R.string.notification_defaults_stop
    "margin_warning" -> R.string.notification_defaults_margin
    else -> R.string.notification_defaults_system
}

private fun notificationPriorityLabel(priority: String): Int = when (priority) {
    "low" -> R.string.notification_defaults_low
    "medium" -> R.string.notification_defaults_medium
    else -> R.string.notification_defaults_high
}
