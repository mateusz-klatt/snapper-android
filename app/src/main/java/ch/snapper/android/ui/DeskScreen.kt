package ch.snapper.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.features.desks.DeskActions
import ch.snapper.android.features.desks.DeskIssue
import ch.snapper.android.features.desks.DeskState

@Composable
fun DeskScreen(state: DeskState, actions: DeskActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.desk_nav_title), style = MaterialTheme.typography.titleLarge)
        if (state.loading) CircularProgressIndicator(Modifier.testTag("desk.loading"))
        if (state.loaded && state.desks.isEmpty() && state.issue == null) Text(stringResource(R.string.desk_empty_message))
        state.desks.forEach { desk ->
            Column {
                Text(desk.label)
                if (desk.publicId == state.primaryOperatorPublicId) Text(stringResource(R.string.desk_primary_badge))
                Text(desk.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.desk_description_unavailable))
                if (state.canManage) OutlinedButton(
                    onClick = { actions.selectDesk(desk.publicId) },
                    enabled = !state.loading && !state.submitting && state.issue != DeskIssue.UNKNOWN && desk.publicId != state.selectedDeskPublicId,
                    modifier = Modifier.testTag("desk.select.${desk.publicId}"),
                ) { Text(stringResource(R.string.desk_attach_desk_label) + ": " + desk.label) }
            }
        }
        if (state.canManage) {
            Text(stringResource(R.string.desk_attach_help))
            OutlinedTextField(state.username, actions.setUsername, label = { Text(stringResource(R.string.desk_attach_username_placeholder)) },
                enabled = !state.loading && !state.submitting && state.issue != DeskIssue.UNKNOWN,
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("desk.username"))
            Button(actions.attach, enabled = state.canSubmit, modifier = Modifier.testTag("desk.attach")) {
                Text(stringResource(R.string.desk_attach_button))
            }
        }
        if (state.succeeded) Text(stringResource(R.string.desk_attach_success_message), modifier = Modifier.testTag("desk.success"))
        state.issue?.let {
            Text(stringResource(when (it) {
                DeskIssue.LOAD -> R.string.desk_load_error
                DeskIssue.INVALID_USERNAME -> R.string.desk_attach_validation_username
                DeskIssue.PREPARATION, DeskIssue.REJECTED -> R.string.error_unknown
                DeskIssue.UNKNOWN -> R.string.order_unknown_outcome
            }), modifier = Modifier.testTag("desk.error"))
        }
        OutlinedButton(actions.reload, enabled = !state.loading && !state.submitting, modifier = Modifier.testTag("desk.reload")) {
            Text(stringResource(R.string.refresh))
        }
    }
}
