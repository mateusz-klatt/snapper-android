package ch.snapper.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ch.snapper.android.R
import ch.snapper.android.features.accountlanguage.AccountLanguageState
import ch.snapper.android.features.accountlanguage.AccountLanguageStatus

@Composable
internal fun AccountLanguageStatusContent(state: AccountLanguageState, retry: () -> Unit) {
    Column(Modifier.testTag("accountLanguage.status")) {
        when (state.status) {
            AccountLanguageStatus.IDLE -> Unit
            AccountLanguageStatus.SYNCING -> Text(stringResource(R.string.account_language_syncing))
            AccountLanguageStatus.ACCEPTED -> Text(stringResource(R.string.account_language_confirmed, state.accepted.orEmpty()))
            AccountLanguageStatus.FAILED -> {
                Text(stringResource(R.string.account_language_uncertain))
                OutlinedButton(onClick = retry, modifier = Modifier.testTag("accountLanguage.retry")) {
                    Text(stringResource(R.string.account_language_retry))
                }
            }
        }
    }
}
