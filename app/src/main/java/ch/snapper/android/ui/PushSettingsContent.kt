package ch.snapper.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ch.snapper.android.R
import ch.snapper.android.features.push.*

@Composable
internal fun PushSettingsContent(state: PushState, actions: PushActions) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) actions.enable() }
    Column(Modifier.testTag("push.settings")) {
        Text(stringResource(R.string.push_title))
        Text(stringResource(statusResource(state.status)), Modifier.testTag("push.status"))
        Text(stringResource(R.string.push_privacy))
        TextButton(onClick = actions.refresh, enabled = state.status !in setOf(PushStatus.LOADING, PushStatus.REGISTERING, PushStatus.UNCERTAIN)) { Text(stringResource(R.string.refresh)) }
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else actions.enable()
        }, enabled = state.configuration != null && state.status in setOf(PushStatus.READY, PushStatus.PERMISSION_REQUIRED, PushStatus.REJECTED), modifier = Modifier.testTag("push.enable")) {
            Text(stringResource(R.string.push_enable))
        }
        if (state.status == PushStatus.UNCERTAIN) TextButton(onClick = actions.reconcile, modifier = Modifier.testTag("push.reconcile")) { Text(stringResource(R.string.push_check)) }
        TextButton(onClick = actions.disable, modifier = Modifier.testTag("push.disable")) { Text(stringResource(R.string.push_disable)) }
    }
}
private fun statusResource(status: PushStatus): Int = when (status) {
    PushStatus.CLEANUP_PENDING -> R.string.push_cleanup
    PushStatus.OFF -> R.string.push_off
    PushStatus.LOADING, PushStatus.REGISTERING -> R.string.push_busy
    PushStatus.UNAVAILABLE -> R.string.push_unavailable
    PushStatus.PERMISSION_REQUIRED -> R.string.push_permission
    PushStatus.READY -> R.string.push_ready
    PushStatus.ACCEPTED -> R.string.push_accepted
    PushStatus.REJECTED -> R.string.push_rejected
    PushStatus.UNCERTAIN -> R.string.push_uncertain
    PushStatus.RESTART_REQUIRED -> R.string.push_restart
}
@Composable
internal fun PushAlertContent(state: PushState, actions: PushActions) {
    state.alert?.let { alert ->
        AlertDialog(onDismissRequest = actions.dismissAlert,
            title = { Text(alert.displayTitle) }, text = { Text(alert.body) },
            confirmButton = { TextButton(onClick = actions.dismissAlert) { Text(stringResource(R.string.push_close)) } })
    }
}
