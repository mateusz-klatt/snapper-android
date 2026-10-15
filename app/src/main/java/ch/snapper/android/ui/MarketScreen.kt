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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.snapper.android.MarketState
import ch.snapper.android.R
import ch.snapper.android.data.CandleDto
import java.math.BigDecimal
import java.math.MathContext

internal fun LazyListScope.marketScreen(state: MarketState, load: (String, String, String) -> Unit) {
    item { MarketControls(state, load) }
    if (state.candles.isNotEmpty()) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun CandleChart(candles: List<CandleDto>) {
    val visible = candles.takeLast(100)
    val low = visible.minOf { it.low }
    val high = visible.maxOf { it.high }
    val range = (high - low).takeIf { it.signum() != 0 } ?: BigDecimal.ONE
    val description = stringResource(R.string.chart_description)
    val rising = MaterialTheme.colorScheme.primary
    val falling = Color(0xFFD94848)
    Canvas(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = description }) {
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
