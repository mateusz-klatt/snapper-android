package ch.snapper.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.UiError
import ch.snapper.android.features.marketdiagnostics.ChartCacheStatus
import ch.snapper.android.features.marketdiagnostics.MarketDiagnosticsActions
import ch.snapper.android.features.marketdiagnostics.MarketDiagnosticsState
import ch.snapper.android.features.marketdiagnostics.MarketPairChip
import ch.snapper.android.features.marketdiagnostics.PairStatus
import java.math.BigDecimal

@Composable
fun MarketDiagnosticsContent(state: MarketDiagnosticsState, actions: MarketDiagnosticsActions) {
    if (!state.permitted || state.selection == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("marketDiagnostics")) {
        if (state.cacheLoading) Text(stringResource(R.string.loading), Modifier.testTag("marketDiagnostics.cache.loading"))
        if (state.cacheFailed) ErrorCard(UiError.SERVICE, actions.retryCache)
        state.cache?.takeUnless { it.isWarm }?.let { CacheWarmingStatus(it) }
        if (state.pairsLoading) Text(stringResource(R.string.loading), Modifier.testTag("marketDiagnostics.pairs.loading"))
        if (state.pairsFailed) ErrorCard(UiError.SERVICE, actions.retryPairs)
        if (state.chips.isNotEmpty()) {
            Text(stringResource(R.string.market_pairstats_label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.chips.forEach { chip -> PairStatisticsChip(chip) { actions.selectPeer(chip) } }
            }
        }
    }
}

@Composable
private fun CacheWarmingStatus(cache: ChartCacheStatus) {
    val source = when (cache.source) {
        "derived" -> R.string.market_cachebanner_sources_derived
        "db" -> R.string.market_cachebanner_sources_db
        else -> R.string.market_cachebanner_sources_cache
    }
    Text(stringResource(R.string.market_cachebanner_message, BigDecimal(cache.sampleCount).display(),
        BigDecimal(cache.expected).display(), stringResource(source)), Modifier.testTag("marketDiagnostics.cache.cold"))
}

@Composable
private fun PairStatisticsChip(chip: MarketPairChip, select: () -> Unit) {
    val correlation = chip.pearsonR.display()
    val pvalue = chip.pvalue.display()
    val description = stringResource(R.string.market_pairstats_chiparialabel, chip.instrument, chip.exchange, correlation, pvalue)
    val status = stringResource(when (chip.status) {
        PairStatus.COLD -> R.string.market_pair_status_cold
        PairStatus.COINTEGRATED -> R.string.market_pair_status_cointegrated
        PairStatus.WARM -> R.string.market_pair_status_warm
    })
    AssistChip(onClick = select, modifier = Modifier.testTag("marketDiagnostics.peer.${chip.exchange}.${chip.instrument}")
        .semantics { contentDescription = "$description · $status" }, label = {
        Column {
            Text("${chip.instrument} · ${chip.exchange}")
            Text(stringResource(R.string.market_pairstats_metric, correlation, pvalue))
            Text(status, style = MaterialTheme.typography.labelSmall)
        }
    })
}
