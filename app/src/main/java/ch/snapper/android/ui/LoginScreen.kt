package ch.snapper.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.R

@Composable
internal fun LoginScreen(state: AppUiState, actions: AppActions) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LanguagePicker(state.language, actions.selectLanguage)
        Image(painterResource(R.drawable.snapper_logo), contentDescription = null, modifier = Modifier.size(80.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.subtitle))
        OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("login.username"))
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("login.password"))
        state.error?.let { ErrorCard(it, actions.clearError, R.string.dismiss) }
        Button(onClick = { actions.login(username.trim(), password) },
            enabled = !state.isLoading && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("login.signIn")) {
            Text(stringResource(if (state.isLoading) R.string.loading else R.string.sign_in))
        }
        BackendEditor(state.backendUrl, actions.saveBackend)
    }
}

@Composable
internal fun LanguagePicker(language: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.language))
        FilterChip(selected = language == "en", onClick = { onSelect("en") }, label = { Text(stringResource(R.string.english)) })
        FilterChip(selected = language == "pl", onClick = { onSelect("pl") }, label = { Text(stringResource(R.string.polish)) })
    }
}

@Composable
internal fun BackendEditor(backend: String, save: (String) -> Unit) {
    var draft by rememberSaveable(backend) { mutableStateOf(backend) }
    OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.backend)) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("settings.backend"))
    TextButton(onClick = { save(draft.trim()) }, enabled = draft.trim() != backend) {
        Text(stringResource(R.string.save))
    }
}
