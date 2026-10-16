package ch.snapper.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.data.*
import ch.snapper.android.features.pnl.*
import java.math.BigDecimal

/** Content for the owner's scrolling container; no locally retained financial state. */
@Composable
fun PnlTimelineScreen(state: PnlState, actions: PnlActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.positions_timeline_chart_title), style = MaterialTheme.typography.titleLarge)
        if (state.wallet == null) {
            Text(stringResource(R.string.positions_timeline_nowallet_message), Modifier.testTag("pnl.noWallet"))
            return@Column
        }
        if (!state.permitted) {
            Text(stringResource(R.string.order_forbidden), Modifier.testTag("pnl.denied"))
            return@Column
        }
        Text(state.wallet.label + " · " + stringResource(if (state.wallet.isPaper) R.string.paper else R.string.order_live))
        Text(stringResource(R.string.positions_timeline_controls_window))
        PnlWindow.entries.forEach { window ->
            val label = when (window) {
                PnlWindow.DAY -> R.string.positions_timeline_controls_last24hours
                PnlWindow.WEEK -> R.string.positions_timeline_controls_last7days
                PnlWindow.MONTH -> R.string.positions_timeline_controls_last30days
                PnlWindow.QUARTER -> R.string.positions_timeline_controls_last90days
            }
            FilterChip(state.window == window, { actions.window(window) }, { Text(stringResource(label)) }, modifier = Modifier.testTag("pnl.window.${window.name}"))
        }
        Text(stringResource(R.string.positions_timeline_controls_granularity))
        Row {
            PnlController.GRANULARITIES.forEach { token ->
                FilterChip(state.granularity == token, { actions.granularity(token) }, { Text(token) },
                    modifier = Modifier.testTag("pnl.granularity.$token"))
            }
        }
        Text(stringResource(R.string.positions_timeline_controls_valuationcurrency))
        Row { PnlController.CURRENCIES.forEach { currency ->
            FilterChip(state.currency == currency, { actions.currency(currency) }, { Text(currency) }, modifier = Modifier.testTag("pnl.currency.$currency"))
        } }
        if (state.loading) CircularProgressIndicator(Modifier.testTag("pnl.loading"))
        if (state.failed) Text(stringResource(R.string.positions_timeline_error_title), Modifier.testTag("pnl.error"))
        OutlinedButton(actions.refresh, enabled = !state.loading, modifier = Modifier.testTag("pnl.refresh")) { Text(stringResource(R.string.refresh)) }
        state.data?.let { PnlContent(it) }
    }
}

@Composable
private fun PnlContent(data: PnlSeriesDto) {
    if (data.points.isEmpty()) Text(stringResource(R.string.positions_timeline_empty_message))
    Text(data.valuation_ccy)
    PnlCurve(R.string.positions_timeline_chart_net, data.points.map { it.net_pnl })
    PnlCurve(R.string.positions_timeline_chart_realized, data.points.map { it.realized_pnl })
    PnlCurve(R.string.positions_timeline_chart_unrealized, data.points.map { it.unrealized_pnl })
    Text(stringResource(R.string.positions_timeline_incompleteness_title))
    Text(stringResource(R.string.positions_timeline_incompleteness_description))
    data.points.flatMap { it.incompleteness_reasons }.groupingBy { it }.eachCount().forEach { (reason, count) ->
        Text(reasonLabel(reason.reason))
        Text(listOfNotNull(reason.withholding_scope, reason.withholding_tier, reason.trigger_instrument_public_id).joinToString(" · "))
        Text(stringResource(R.string.positions_timeline_incompleteness_affectedpoints, count))
    }
    PnlCoverage(data.equity_coverage)
    PnlExecutionHistory(data.execution_history)
    PnlSources(data)
    data.points.lastOrNull()?.let { PnlContributions(it) }
}

@Composable
private fun PnlCoverage(coverage: PnlEquityCoverageDto) {
    Text(stringResource(R.string.positions_timeline_equity_title))
    Text(stringResource(R.string.pnl_equity_coverage, coverage.sampled.toString(), coverage.complete_minutes.toString()))
    Text(listOfNotNull(coverage.valuation_basis, coverage.sample_calc_version, coverage.first_minute, coverage.last_minute).joinToString(" · "))
    Text(stringResource(R.string.positions_timeline_equity_honesty_conversionwithheld, coverage.conversion_withheld_minutes.toString()))
    coverage.converted_from?.let { Text(stringResource(R.string.positions_timeline_equity_honesty_restatedfrom, it)) }
    coverage.conversion_rate_source?.let { Text(stringResource(R.string.positions_timeline_equity_honesty_pricedon, it)) }
    if (coverage.drawdown_withheld_reason != null) Text(stringResource(R.string.positions_timeline_equity_honesty_drawdownconverted))
    if (coverage.external_flows_adjusted == false) Text(stringResource(R.string.positions_timeline_equity_honesty_flowsunadjusted))
    if (coverage.venue_scope == "spot_only") Text(stringResource(R.string.positions_timeline_equity_honesty_spotonly))
}

@Composable
private fun PnlExecutionHistory(history: PnlExecutionHistoryDto) {
    Text(stringResource(R.string.pnl_execution_history, history.status))
    if (history.corrections.isNotEmpty()) {
        Text(stringResource(R.string.positions_timeline_executionhistory_title))
        Text(stringResource(R.string.positions_timeline_executionhistory_summary))
        history.corrections.forEach { correction ->
            Text(correction.exchange + " #" + correction.scope_sequence + " · " + correction.correction_time)
            Text(stringResource(R.string.positions_timeline_executionhistory_targetexecutionid) + ": " + correction.target_execution_public_id)
            Text(stringResource(R.string.positions_timeline_executionhistory_correctionid) + ": " + correction.correction_public_id)
            Text(correction.reason)
        }
    }
}

@Composable
private fun PnlSources(data: PnlSeriesDto) {
    Text(data.from_time + " — " + data.to_time + " · " + data.as_of)
    Text(stringResource(R.string.pnl_provenance, data.calc_version, data.mark_source, data.as_of))
    data.rate_sources.forEach { Text(it.source_currency + " → " + it.valuation_currency + " · " + it.base_currency + "/" + it.quote_currency + "@" + it.exchange) }
}

@Composable
private fun PnlContributions(point: PnlTimelinePointDto) {
    PnlStock(R.string.positions_timeline_equity_serieslabel, point.equity)
    PnlStock(R.string.positions_timeline_equity_cashposition_cash, point.cash)
    PnlStock(R.string.positions_timeline_equity_cashposition_position, point.position_value)
    PnlStock(R.string.positions_timeline_equity_drawdown_current, point.drawdown)
    Text(stringResource(R.string.positions_timeline_attribution_valuesemantics))
    Text(stringResource(R.string.positions_timeline_contributions_title))
    point.per_instrument.forEach { row ->
        Text(listOfNotNull(row.native_symbol, row.exchange, row.instrument_public_id).joinToString(" · "))
        PnlComponents(row.realized_pnl, row.fee_pnl, row.accrual_pnl, row.unrealized_pnl)
    }
    Text(stringResource(R.string.positions_timeline_attribution_title))
    point.attribution.forEach { row ->
        Text(row.origin + " · " + (row.strategy_name ?: stringResource(R.string.positions_timeline_attribution_nostrategy)))
        PnlComponents(row.realized_pnl, row.fee_pnl, row.accrual_pnl, row.unrealized_pnl)
    }
}

@Composable
private fun PnlComponents(realized: BigDecimal?, fees: BigDecimal?, accrual: BigDecimal?, unrealized: BigDecimal?) {
    val fields = listOf(R.string.positions_timeline_contributions_realized to realized, R.string.positions_timeline_contributions_fees to fees,
        R.string.positions_timeline_contributions_accrual to accrual, R.string.positions_timeline_contributions_unrealized to unrealized)
    fields.forEach { (label, value) -> Text(stringResource(label) + ": " + (value?.toPlainString() ?: stringResource(R.string.positions_timeline_attribution_withheld))) }
}

@Composable
private fun PnlCurve(label: Int, values: List<BigDecimal?>) {
    val title = stringResource(label)
    val withheld = stringResource(R.string.positions_timeline_attribution_withheld)
    Text(title + ": " + (values.lastOrNull()?.toPlainString() ?: withheld))
    val segments = pnlSegments(values)
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = title }) {
        segments.forEach { segment ->
            segment.zipWithNext().forEach { (a, b) ->
                drawLine(color, Offset(a.first * size.width, (1 - a.second) * size.height), Offset(b.first * size.width, (1 - b.second) * size.height), 3.dp.toPx())
            }
            if (segment.size == 1) { val p = segment.single(); drawCircle(color, 3.dp.toPx(), Offset(p.first * size.width, (1 - p.second) * size.height)) }
        }
    }
}

@Composable
private fun PnlStock(label: Int, value: BigDecimal?) {
    Text(stringResource(label) + ": " + (value?.toPlainString() ?: stringResource(R.string.positions_timeline_attribution_withheld)))
}

@Composable
private fun reasonLabel(reason: String): String = when (reason) {
    "scope_order_regression" -> stringResource(R.string.positions_timeline_incompleteness_reasons_scope_order_regression)
    "before_activation" -> stringResource(R.string.positions_timeline_incompleteness_reasons_before_activation)
    "activation_baseline_non_finite" -> stringResource(R.string.positions_timeline_incompleteness_reasons_activation_baseline_non_finite)
    "fill_evidence_gap" -> stringResource(R.string.positions_timeline_incompleteness_reasons_fill_evidence_gap)
    "seed_quantity_non_finite" -> stringResource(R.string.positions_timeline_incompleteness_reasons_seed_quantity_non_finite)
    "cost_basis_unavailable" -> stringResource(R.string.positions_timeline_incompleteness_reasons_cost_basis_unavailable)
    "execution_price_provenance_unproven" -> stringResource(R.string.positions_timeline_incompleteness_reasons_execution_price_provenance_unproven)
    "execution_size_invalid" -> stringResource(R.string.positions_timeline_incompleteness_reasons_execution_size_invalid)
    "execution_price_invalid" -> stringResource(R.string.positions_timeline_incompleteness_reasons_execution_price_invalid)
    "fx_conversion_unproven" -> stringResource(R.string.positions_timeline_incompleteness_reasons_fx_conversion_unproven)
    "mark_unavailable" -> stringResource(R.string.positions_timeline_incompleteness_reasons_mark_unavailable)
    "cumulative_non_finite" -> stringResource(R.string.positions_timeline_incompleteness_reasons_cumulative_non_finite)
    "unrealized_non_finite" -> stringResource(R.string.positions_timeline_incompleteness_reasons_unrealized_non_finite)
    "net_non_finite" -> stringResource(R.string.positions_timeline_incompleteness_reasons_net_non_finite)
    "attribution_value_non_finite" -> stringResource(R.string.positions_timeline_incompleteness_reasons_attribution_value_non_finite)
    "attribution_reconciliation_failed" -> stringResource(R.string.positions_timeline_incompleteness_reasons_attribution_reconciliation_failed)
    "instrument_reconciliation_failed" -> stringResource(R.string.positions_timeline_incompleteness_reasons_instrument_reconciliation_failed)
    "late_pre_activation_execution" -> stringResource(R.string.positions_timeline_incompleteness_reasons_late_pre_activation_execution)
    "attribution_sum_unrepresentable" -> stringResource(R.string.positions_timeline_incompleteness_reasons_attribution_sum_unrepresentable)
    "instrument_sum_unrepresentable" -> stringResource(R.string.positions_timeline_incompleteness_reasons_instrument_sum_unrepresentable)
    else -> reason
}
