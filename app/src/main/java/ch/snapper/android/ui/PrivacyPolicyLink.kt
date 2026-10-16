package ch.snapper.android.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ch.snapper.android.R

@Composable
internal fun PrivacyPolicyLink() {
    val handler = LocalUriHandler.current
    TextButton(onClick = { handler.openUri("https://snapper.ch/android-privacy-policy.html") },
        modifier = Modifier.testTag("privacy.policy")) {
        Text(stringResource(R.string.privacy_policy))
    }
}
