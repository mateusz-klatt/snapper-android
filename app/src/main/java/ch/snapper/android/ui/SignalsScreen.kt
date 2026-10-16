package ch.snapper.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.features.signals.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun LazyListScope.signalsScreen(state: SignalsState, actions: SignalsActions) {
    if (!state.allowed) return
    item { SignalControls(state, actions) }
    items(state.visibleRows, key = { it.publicId }) { signal ->
        RecordCard(signal.instrument) {
            Text(signal.side)
            Metric(R.string.signals_strength, signal.strength.display())
            Text(stringResource(when (signalStrengthTier(signal.strength)) {
                SignalStrengthTier.STRONG -> R.string.signals_strong
                SignalStrengthTier.MEDIUM -> R.string.signals_medium
                SignalStrengthTier.WEAK -> R.string.signals_weak
                SignalStrengthTier.VERY_WEAK -> R.string.signals_very_weak
            }))
            Metric(R.string.signals_price, signal.price.display())
            Metric(R.string.exchange, signal.exchange)
            signal.strategyName?.let { Text(it) }
            Text(stringResource(R.string.signals_reason))
            Text(signal.reason)
            Text(signal.firedAt.displayTimestamp())
        }
    }
}

@Composable
private fun SignalControls(state: SignalsState, actions: SignalsActions) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.refresh, enabled = !state.loading) { Text(stringResource(R.string.refresh)) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.failed) Text(stringResource(R.string.error_network))
        Metric(R.string.signals_total, state.summary.total.toString())
        Metric(R.string.signals_buy, state.summary.buy.toString())
        Metric(R.string.signals_sell, state.summary.sell.toString())
        Metric(R.string.signals_average_strength, state.summary.averageStrength.display())
        Text(stringResource(R.string.signals_filter))
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("signals.filter")) {
            Text(state.selectedStrategy ?: stringResource(R.string.signals_all_strategies))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.signals_all_strategies)) }, onClick = { actions.selectStrategy(null); expanded = false })
            state.strategies.forEach { strategy ->
                DropdownMenuItem(text = { Text(strategy) }, onClick = { actions.selectStrategy(strategy); expanded = false })
            }
        }
        SignalExport(state)
        if (state.visibleRows.isEmpty() && !state.loading) Text(stringResource(R.string.signals_empty))
    }
}

/** Storage Access Framework grants only the document URI chosen by the user; no provider or storage permission. */
@Composable
private fun SignalExport(state: SignalsState, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var failed by remember(state.scopeKey) { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(state)
    LaunchedEffect(state.scopeKey) { pending = null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val captured = pending
        pending = null
        if (uri != null && captured != null && current.allowed && current.scopeKey == captured.first) {
            coroutineScope.launch {
                writing = true
                try {
                    withContext(ioDispatcher) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(captured.second) }
                            ?: error("Document output stream unavailable")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failed = true
                } finally {
                    writing = false
                }
            }
        }
    }
    Button(enabled = state.allowed && state.visibleRows.isNotEmpty() && pending == null && !writing, onClick = {
        try {
            pending = state.scopeKey to SignalCsv.encode(state.visibleRows)
            failed = false
            launcher.launch(SignalCsv.filename)
        } catch (_: Exception) {
            pending = null
            failed = true
        }
    }, modifier = Modifier.testTag("signals.export")) { Text(stringResource(R.string.signals_export)) }
    if (failed) Text(stringResource(R.string.signals_export_failed))
}
