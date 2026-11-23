package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import ch.snapper.android.data.VenueAccountDto
import androidx.compose.ui.res.stringResource
import ch.snapper.android.R
import ch.snapper.android.features.accounts.*

internal fun LazyListScope.venueAccountsScreen(state: VenueAccountsState, refresh: () -> Unit) {
    item {
        Text(stringResource(R.string.venue_accounts_title), style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = refresh, enabled = !state.loading) { Text(stringResource(R.string.venue_accounts_refresh)) }
        if (state.loading) LinearProgressIndicator()
        if (state.failed) Text(stringResource(R.string.venue_accounts_failed), color = MaterialTheme.colorScheme.error)
        if (state.accounts.isEmpty() && !state.loading) Text(stringResource(R.string.empty))
    }
    items(state.accounts, key = { it.publicId }) { account ->
        RecordCard("${account.exchange} · ${tradingModeLabel(account.mode)}") {
            val truth = state.truth(account)
            Metric(R.string.status, accountStatusLabel(truth.status))
            account.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(if (truth.authoritative) R.string.venue_accounts_authoritative else R.string.venue_accounts_not_authoritative))
            Metric(R.string.venue_accounts_valuation, valuationStatusLabel(account.valuationStatus))
            Metric(R.string.venue_accounts_balances, accountStatusLabel(account.balanceStatus))
            Metric(R.string.venue_accounts_observed, account.balanceObservedAt.displayTimestamp())
            Metric(R.string.venue_accounts_source, account.balancePayloadSourceObservationId?.toString() ?: "—")
            AccountBalances(account)
            Metric(R.string.venue_accounts_positions, accountStatusLabel(account.positionStatus))
            Metric(R.string.venue_accounts_observed, account.positionObservedAt.displayTimestamp())
            Metric(R.string.venue_accounts_source, account.positionPayloadSourceObservationId?.toString() ?: "—")
            AccountPositions(account)
            Metric(R.string.venue_accounts_reconciliation, reconciliationStatusLabel(account.reconciliation.effectiveStatus))
            Metric(R.string.venue_accounts_evaluation, account.reconciliation.evaluationStatus?.let { evaluationStatusLabel(it) } ?: "—")
            Metric(R.string.venue_accounts_deadline, account.authoritativeUntil.displayTimestamp())
        }
    }
}

@Composable
private fun AccountBalances(account: VenueAccountDto) {
    if (account.balances == null) Text("—")
    account.balances?.forEach { balance ->
        Text(balance.currency, style = MaterialTheme.typography.titleSmall)
        Metric(R.string.venue_accounts_total, venueDecimal(balance.totalDecimal, balance.total).toBigDecimalOrNull().display())
        Metric(R.string.venue_accounts_free, venueDecimal(balance.freeDecimal, balance.free).toBigDecimalOrNull().display())
        Metric(R.string.venue_accounts_used, venueDecimal(balance.usedDecimal, balance.used).toBigDecimalOrNull().display())
        Metric(R.string.venue_accounts_numeric_source, balance.numericProvenance?.let { numericSourceLabel(it) } ?: "—")
    }
}

@Composable
private fun AccountPositions(account: VenueAccountDto) {
    if (account.openPositions == null) Text("—")
    account.openPositions?.forEach { position ->
        Text("${position.symbol} · ${tradeSideLabel(position.side)}")
        Metric(R.string.size, position.size.display())
        Metric(R.string.average_price, position.entryPrice.display())
        Metric(R.string.mark_price, position.markPrice.display())
        FinancialMetric(R.string.pnl, position.unrealizedPnl)
        FinancialMetric(R.string.venue_accounts_funding, position.unrealizedFunding)
    }
}

@Composable
private fun accountStatusLabel(value: String): String = when (value) {
    "observed" -> stringResource(R.string.venue_accounts_status_observed)
    "unobserved" -> stringResource(R.string.venue_accounts_status_unobserved)
    "simulated" -> stringResource(R.string.venue_accounts_status_simulated)
    "stale" -> stringResource(R.string.venue_accounts_status_stale)
    "clock_error" -> stringResource(R.string.venue_accounts_status_clock_error)
    "corrupt" -> stringResource(R.string.venue_accounts_status_unverified)
    "unsupported" -> stringResource(R.string.venue_accounts_status_unavailable)
    "not_applicable" -> stringResource(R.string.venue_accounts_status_not_applicable)
    "unknown" -> stringResource(R.string.venue_accounts_status_unknown)
    "error" -> stringResource(R.string.system_status_error)
    else -> value
}

@Composable
private fun valuationStatusLabel(value: String): String = when (value) {
    "native_only" -> stringResource(R.string.venue_accounts_valuation_native_only)
    else -> value
}

@Composable
private fun evaluationStatusLabel(value: String): String = when (value) {
    "matched" -> stringResource(R.string.venue_accounts_evaluation_matched)
    "mismatched" -> stringResource(R.string.venue_accounts_evaluation_mismatched)
    "incomplete" -> stringResource(R.string.venue_accounts_evaluation_incomplete)
    "unsupported" -> stringResource(R.string.venue_accounts_reconciliation_status_unsupported)
    "error" -> stringResource(R.string.system_status_error)
    else -> value
}

@Composable
private fun numericSourceLabel(value: String): String = when (value) {
    "venue_raw" -> stringResource(R.string.venue_accounts_numeric_source_venue_raw)
    "legacy_float" -> stringResource(R.string.venue_accounts_numeric_source_legacy_float)
    else -> value
}

@Composable
private fun reconciliationStatusLabel(value: String): String = when (value) {
    "matched" -> stringResource(R.string.venue_accounts_reconciliation_status_reconciled)
    "mismatched" -> stringResource(R.string.venue_accounts_reconciliation_status_drift_detected)
    "incomplete" -> stringResource(R.string.venue_accounts_reconciliation_status_not_reconciled)
    "unsupported" -> stringResource(R.string.venue_accounts_reconciliation_status_unsupported)
    "error" -> stringResource(R.string.venue_accounts_reconciliation_status_error)
    else -> accountStatusLabel(value)
}
