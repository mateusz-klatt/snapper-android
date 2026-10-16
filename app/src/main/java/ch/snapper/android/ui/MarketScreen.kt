package ch.snapper.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.snapper.android.MarketState
import ch.snapper.android.R
import ch.snapper.android.data.CandleDto
import ch.snapper.android.features.market.MarketCatalogState
import ch.snapper.android.features.market.MarketCatalogActions
import ch.snapper.android.features.market.MarketCatalogControls
import ch.snapper.android.features.live.LiveConnectionStatus
import java.math.BigDecimal
import java.math.MathContext

internal fun LazyListScope.marketScreen(
    state: MarketState,
    load: (String, String, String) -> Unit,
    catalog: MarketCatalogState? = null,
    catalogActions: MarketCatalogActions = MarketCatalogActions(),
) {
    item {
        if (catalog == null) MarketControls(state, load) else {
            MarketCatalogControls(catalog, catalogActions)
            val request = { load(catalog.exchange, catalog.instrument, catalog.timeframe) }
            Button(onClick = request, enabled = catalog.canLoad && !state.isLoading, modifier = Modifier.testTag("market.load")) {
                Text(stringResource(R.string.load_market))
            }
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorCard(it, request) }
            if (state.candles.isEmpty() && !state.isLoading) Text(stringResource(R.string.no_candles))
        }
        MarketLiveStatus(state)
    }
    if (state.candles.isNotEmpty()) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${state.exchange} · ${state.instrument} · ${state.timeframe}", style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.chart_window), style = MaterialTheme.typography.bodySmall)
                CandleChart(state.candles)
            }
        }
        items(state.candles.asReversed()) { candle ->
            RecordCard(candle.openAt) {
                Metric(R.string.open, candle.open.display())
                Metric(R.string.high, candle.high.display())
                Metric(R.string.low, candle.low.display())
                Metric(R.string.close, candle.close.display())
                Metric(R.string.volume, candle.volume.display())
            }
        }
    }
}

@Composable
private fun MarketLiveStatus(state: MarketState) {
    val label = when (state.liveStatus) {
        LiveConnectionStatus.SUBSCRIBED -> R.string.market_live_connected
        LiveConnectionStatus.CONNECTING, LiveConnectionStatus.AUTHENTICATING, LiveConnectionStatus.RECONNECTING -> R.string.market_live_connecting
        LiveConnectionStatus.FAILED -> R.string.market_live_unavailable
        LiveConnectionStatus.STOPPED -> R.string.market_snapshot_mode
    }
    Text(stringResource(label), Modifier.testTag("market.connection"), style = MaterialTheme.typography.bodySmall)
    state.tick?.last?.let { Metric(R.string.market_context_last, it.display()) }
}

@Composable
private fun MarketControls(state: MarketState, load: (String, String, String) -> Unit) {
    var exchange by rememberSaveable { mutableStateOf(state.exchange) }
    var instrument by rememberSaveable { mutableStateOf(state.instrument) }
    var timeframe by rememberSaveable { mutableStateOf(state.timeframe) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(exchange, { exchange = it }, label = { Text(stringResource(R.string.exchange)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(instrument, { instrument = it }, label = { Text(stringResource(R.string.instrument)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(timeframe, { timeframe = it }, label = { Text(stringResource(R.string.timeframe)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val request = { load(exchange.trim(), instrument.trim(), timeframe.trim()) }
        Button(onClick = request, enabled = !state.isLoading && exchange.isNotBlank() && instrument.isNotBlank() && timeframe.isNotBlank(),
            modifier = Modifier.testTag("market.load")) { Text(stringResource(R.string.load_market)) }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ErrorCard(it, request) }
        Text(stringResource(R.string.snapshot_note), style = MaterialTheme.typography.bodySmall)
        if (state.candles.isEmpty() && !state.isLoading) Text(stringResource(R.string.no_candles))
    }
}

@Composable
internal fun CandleChart(candles: List<CandleDto>) {
    val visible = candles.takeLast(100)
    val minimum = visible.minOf { it.low }
    val maximum = visible.maxOf { it.high }
    val padding = if (minimum.compareTo(maximum) == 0) maxOf(minimum.abs().multiply(BigDecimal("0.01")), BigDecimal("0.00000001")) else BigDecimal.ZERO
    val low = minimum - padding
    val high = maximum + padding
    val range = high - low
    val description = stringResource(R.string.chart_description)
    val rising = financialColor(BigDecimal.ONE)
    val falling = financialColor(BigDecimal.ONE.negate())
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.semantics { contentDescription = description }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.widthIn(max = 96.dp).height(220.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(high.display(), style = MaterialTheme.typography.labelSmall)
                Text((low + range.divide(BigDecimal(2))).display(), style = MaterialTheme.typography.labelSmall)
                Text(low.display(), style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).height(220.dp)) {
                for (step in 0..4) {
                    val fraction = step / 4f
                    drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                    drawLine(grid, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height))
                }
                val stride = size.width / visible.size
                fun y(value: BigDecimal): Float = size.height * (1f - (value - low).divide(range, MathContext.DECIMAL64).toFloat())
                visible.forEachIndexed { index, candle ->
                    val x = (index + 0.5f) * stride
                    val color = if (candle.close >= candle.open) rising else falling
                    drawLine(color, Offset(x, y(candle.high)), Offset(x, y(candle.low)), strokeWidth = 2f)
                    val top = minOf(y(candle.open), y(candle.close))
                    val height = kotlin.math.abs(y(candle.open) - y(candle.close)).coerceAtLeast(2f)
                    drawRect(color, Offset(x - stride * 0.3f, top), Size(stride * 0.6f, height))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(visible.first().openAt, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(visible.last().openAt, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
