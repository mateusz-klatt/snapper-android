package ch.snapper.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ch.snapper.android.R
import ch.snapper.android.data.RelatedMarketPayloadDto
import ch.snapper.android.features.marketcontext.*
import java.math.BigDecimal
import java.time.Instant

/** Embed within the market screen's existing scroll container. */
@Composable
fun MarketContextContent(state: MarketContextState, actions: MarketContextActions) {
    if (!state.permitted) {
        Text(stringResource(R.string.order_forbidden), Modifier.testTag("marketContext.denied"))
        return
    }
    if (state.selection == null) return
    Column(Modifier.testTag("marketContext")) {
        MarketMetricsContent(state, actions)
        RelatedMarketContent(state, actions)
    }
}

@Composable
private fun MarketMetricsContent(state: MarketContextState, actions: MarketContextActions) {
    if (state.metricsLoading) Text(stringResource(R.string.loading), Modifier.testTag("marketContext.metrics.loading"))
    if (state.metricsFailed) MarketContextError("metrics", actions.retryMetrics)
    state.metrics?.let { metrics ->
        MarketMetric(stringResource(R.string.market_context_last), metrics.lastPrice, "last")
        metrics.asOfMillis?.let { timestamp ->
            Text(stringResource(R.string.market_context_as_of, Instant.ofEpochMilli(timestamp).toString().displayTimestamp()),
                Modifier.testTag("marketContext.metrics.asOf"))
        }
        MarketMetric(stringResource(R.string.market_context_change), metrics.changePercent, "change", "%")
        MarketMetric(stringResource(R.string.market_context_high), metrics.high, "high")
        MarketMetric(stringResource(R.string.market_context_low), metrics.low, "low")
    }
}

@Composable
private fun MarketMetric(label: String, value: BigDecimal?, tag: String, suffix: String = "") {
    val rendered = if (value == null) "—" else value.display() + suffix
    Text("$label: $rendered", Modifier.testTag("marketContext.metric.$tag"))
}

@Composable
private fun RelatedMarketContent(state: MarketContextState, actions: MarketContextActions) {
    if (state.relatedLoading) Text(stringResource(R.string.loading), Modifier.testTag("marketContext.related.loading"))
    if (state.relatedFailed) MarketContextError("related", actions.retryRelated)
    state.related?.let { related ->
        RelatedUnderlying(related)
        if (related.groups.isEmpty()) Text(stringResource(R.string.market_context_related_empty,
            related.selected.nativeSymbol, related.selected.exchange), Modifier.testTag("marketContext.related.empty"))
        RelatedGroups(related, actions)
    }
}

@Composable
private fun RelatedUnderlying(related: RelatedMarketPayloadDto) {
    related.underlying?.let { underlying ->
        Text("${underlying.ticker} · ${underlying.name}", Modifier.testTag("marketContext.underlying"))
        underlying.sector?.let { Text(it) }
        underlying.description?.let { Text(it) }
    }
}

@Composable
private fun RelatedGroups(related: RelatedMarketPayloadDto, actions: MarketContextActions) {
    related.groups.forEach { group ->
        Text(group.label)
        group.items.forEach { item ->
            val selected = item.isSelected || (item.exchange == related.selected.exchange && item.nativeSymbol == related.selected.nativeSymbol)
            Button(onClick = { actions.selectRelated(item) },
                enabled = !selected && item.instrumentPublicId.isNotBlank() && item.exchange.isNotBlank() && item.nativeSymbol.isNotBlank() &&
                    item.exchange.length <= 128 && item.nativeSymbol.length <= 256,
                modifier = Modifier.testTag("marketContext.related.${item.instrumentPublicId}")) {
                Text("${item.nativeSymbol} · ${item.exchange}")
            }
        }
    }
}

@Composable
private fun MarketContextError(feed: String, retry: () -> Unit) {
    Text(stringResource(R.string.error_service), Modifier.testTag("marketContext.$feed.error"))
    Button(onClick = retry, modifier = Modifier.testTag("marketContext.$feed.retry")) { Text(stringResource(R.string.retry)) }
}
