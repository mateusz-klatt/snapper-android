package ch.snapper.android.features.market

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snapper.android.R

@Composable
fun MarketCatalogControls(state: MarketCatalogState, actions: MarketCatalogActions) {
    var exchangeMenu by remember { mutableStateOf(false) }
    var instrumentPicker by remember(state.exchange) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { exchangeMenu = true }, enabled = !state.isLoading && state.exchanges.isNotEmpty(), modifier = Modifier.testTag("market.exchange")) {
                Text(state.exchange.ifBlank { stringResource(R.string.exchange) })
            }
            DropdownMenu(expanded = exchangeMenu, onDismissRequest = { exchangeMenu = false }) {
                state.exchanges.forEach { exchange ->
                    DropdownMenuItem(text = { Text(exchange) }, onClick = { exchangeMenu = false; actions.selectExchange(exchange) })
                }
            }
        }
        OutlinedButton(onClick = { instrumentPicker = true }, enabled = !state.isLoading && state.instruments.isNotEmpty(), modifier = Modifier.testTag("market.instrument")) {
            Text(state.instrument.ifBlank { stringResource(R.string.instrument) })
        }
        Text(stringResource(R.string.timeframe), style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            marketTimeframes.forEach { timeframe ->
                FilterChip(selected = state.timeframe == timeframe, onClick = { actions.selectTimeframe(timeframe) }, label = { Text(timeframe) }, modifier = Modifier.testTag("market.timeframe.$timeframe"))
            }
        }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error != null) {
            Text(stringResource(R.string.market_catalog_error), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions.retry) { Text(stringResource(R.string.market_catalog_retry)) }
        }
    }
    if (instrumentPicker) {
        AlertDialog(onDismissRequest = { instrumentPicker = false }, title = { Text(stringResource(R.string.instrument)) },
            text = {
                Column {
                    OutlinedTextField(value = state.query, onValueChange = actions.search, singleLine = true,
                        label = { Text(stringResource(R.string.market_search)) }, modifier = Modifier.testTag("market.search"))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        if (state.visibleInstruments.isEmpty()) item { Text(stringResource(R.string.market_no_matches)) }
                        items(state.visibleInstruments, key = { it.instrumentPublicId }) { instrument ->
                            TextButton(onClick = { actions.selectInstrument(instrument.symbol); instrumentPicker = false }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(instrument.symbol)
                                    instrument.instrumentKind?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                    if (!instrument.canTrade) Text(stringResource(R.string.market_view_only), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { instrumentPicker = false }) { Text(stringResource(R.string.market_picker_close)) } })
    }
}
