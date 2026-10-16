package ch.snapper.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ch.snapper.android.R
import ch.snapper.android.data.UserDto

@Composable
fun AccountSettingsContent(user: UserDto, version: String, build: String) {
    Text(user.username)
    user.email?.let { Text(stringResource(R.string.settings_account_email) + ": " + it) }
    Text(stringResource(R.string.settings_account_role, user.role))
    Text(stringResource(R.string.settings_app_info_version) + ": " + version)
    Text(stringResource(R.string.settings_app_info_build) + ": " + build)
}

enum class AccountConfirmation { LOGOUT, CHANGE_BACKEND }

/** The owner clears confirmation when the authenticated scope changes. */
@Composable
fun AccountSettingsConfirmation(kind: AccountConfirmation, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val logout = kind == AccountConfirmation.LOGOUT
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (logout) R.string.settings_logout_confirm_title else R.string.settings_connection_change_backend_title)) },
        text = { Text(stringResource(if (logout) R.string.settings_logout_confirm_message else R.string.settings_connection_change_backend_message)) },
        confirmButton = { TextButton(onConfirm, enabled = !busy, modifier = Modifier.testTag("settings.confirm")) { Text(stringResource(if (logout) R.string.logout else R.string.common_continue)) } },
        dismissButton = { TextButton(onDismiss, enabled = !busy, modifier = Modifier.testTag("settings.dismiss")) { Text(stringResource(R.string.backend_url_cancel)) } },
    )
}
