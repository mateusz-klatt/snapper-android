package ch.snapper.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.data.DisplayNumbers
import ch.snapper.android.data.PnlTimelinePointDto
import ch.snapper.android.features.pnl.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A shared scale, identity-colored series, and actual elapsed-time X coordinates. */
@Composable
fun PnlLineChart(points: List<PnlTimelinePointDto>, valuationCcy: String, modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()) {
    val model = remember(points) {
        runCatching { PnlChartModelBuilder.build(points.map {
            PnlChartSample(Instant.parse(it.pointTime), it.netPnl, it.realizedPnl, it.unrealizedPnl)
        }) }.getOrNull()
    }
    if (model == null) {
        Text(stringResource(R.string.positions_timeline_error_title), Modifier.testTag("pnl.chart.invalid"))
        return
    }
    val names = listOf(stringResource(R.string.positions_timeline_chart_net), stringResource(R.string.positions_timeline_chart_realized),
        stringResource(R.string.positions_timeline_chart_unrealized))
    val colors = listOf(MaterialTheme.colorScheme.primary, Color(0xFFBC6800), Color(0xFF008B91))
    val withheld = stringResource(R.string.positions_timeline_attribution_withheld)
    val locale = LocalNumberLocale.current
    val currency = stringResource(R.string.positions_timeline_chart_valuationcurrency, valuationCcy)
    val summaries = model.lines.mapIndexed { index, line -> names[index] + ": " + (line.latest?.let { DisplayNumbers.format(it, locale) } ?: withheld) }
    val range = DisplayNumbers.format(model.domain.minimum, locale) + " — " + DisplayNumbers.format(model.domain.maximum, locale)
    val interval = model.times.firstOrNull()?.let { it.time.toString() + " — " + model.times.last().time.toString() }.orEmpty()
    Column(modifier.testTag("pnl.sharedChart"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PnlPlot(model, colors, locale, zone, (listOf(currency, interval, range) + summaries).joinToString(". "))
        model.lines.forEachIndexed { index, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.size(24.dp, 12.dp)) {
                    drawLine(colors[index], Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = seriesDash(index))
                }
                Text(summaries[index], Modifier.testTag("pnl.legend.${line.series.name}"))
            }
        }
        Text(currency, Modifier.testTag("pnl.chart.currency"))
        Text(zone.id, Modifier.testTag("pnl.chart.timeZone"), style = MaterialTheme.typography.labelSmall)
    }
}

private data class PnlPlotLabels(val values: List<String>, val times: List<String>)

@Composable
private fun PnlPlot(model: PnlChartModel, colors: List<Color>, locale: Locale, zone: ZoneId, description: String) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axisLeading = LocalLayoutDirection.current == LayoutDirection.Rtl || locale.country.uppercase(Locale.ROOT) in setOf("AE", "IL", "IR")
    val labels = remember(model, locale, zone) { PnlPlotLabels(model.domain.ticks.map { DisplayNumbers.format(it, locale) },
        PnlChartTimeLabels.format(model.times.map { it.time }, locale, zone)) }
    Canvas(Modifier.fillMaxWidth().height(300.dp).testTag("pnl.chart.canvas").semantics { contentDescription = description }) {
        val left = (if (axisLeading) 82.dp else 8.dp).toPx()
        val right = size.width - (if (axisLeading) 8.dp else 82.dp).toPx()
        val bottom = size.height - 30.dp.toPx()
        val top = 10.dp.toPx()
        if (right <= left || bottom <= top) return@Canvas
        model.domain.ticks.forEachIndexed { index, value ->
            val y = bottom - model.domain.fraction(value).toFloat() * (bottom - top)
            drawLine(grid, Offset(left, y), Offset(right, y), 0.5.dp.toPx())
            val label = measurer.measure(labels.values[index], style, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = 72.dp.toPx().toInt().coerceAtLeast(1)))
            val labelX = if (axisLeading) left - label.size.width - 6.dp.toPx() else right + 6.dp.toPx()
            drawText(label, topLeft = Offset(labelX, y - label.size.height / 2))
        }
        clipRect(left, top, right, bottom) { drawPnlLines(model, colors, left, right, top, bottom) }
        drawTimeLabels(model, labels, measurer, style, left, right, bottom)
    }
}

private fun DrawScope.drawPnlLines(model: PnlChartModel, colors: List<Color>, left: Float, right: Float, top: Float, bottom: Float) {
    model.lines.forEachIndexed { index, line ->
        line.segments.forEach { segment ->
            val points = segment.map { Offset(left + it.x.toFloat() * (right - left), bottom - it.y.toFloat() * (bottom - top)) }
            if (points.size == 1) drawCircle(colors[index], 2.5.dp.toPx(), points.single())
            else {
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, colors[index], style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), pathEffect = seriesDash(index)))
            }
        }
    }
}

private fun DrawScope.drawTimeLabels(model: PnlChartModel, labels: PnlPlotLabels, measurer: TextMeasurer, style: TextStyle,
    left: Float, right: Float, bottom: Float) {
    var previousEnd = Float.NEGATIVE_INFINITY
    model.times.forEachIndexed { index, tick ->
        val text = measurer.measure(labels.times[index], style)
        val x = (left + tick.x.toFloat() * (right - left) - text.size.width / 2).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f))
        if (x >= previousEnd + 4.dp.toPx()) {
            drawText(text, topLeft = Offset(x, bottom + 6.dp.toPx()))
            previousEnd = x + text.size.width
        }
    }
}

private fun seriesDash(index: Int): PathEffect? = when (index) {
    1 -> PathEffect.dashPathEffect(floatArrayOf(10f, 5f))
    2 -> PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
    else -> null
}

