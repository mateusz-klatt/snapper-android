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
        RecordCard("${account.exchange} · ${account.mode}") {
            val truth = state.truth(account)
            Metric(R.string.status, truth.status)
            account.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(if (truth.authoritative) R.string.venue_accounts_authoritative else R.string.venue_accounts_not_authoritative))
            Metric(R.string.venue_accounts_valuation, account.valuationStatus)
            Metric(R.string.venue_accounts_balances, account.balanceStatus)
            Metric(R.string.venue_accounts_observed, account.balanceObservedAt.displayTimestamp())
            Metric(R.string.venue_accounts_source, account.balancePayloadSourceObservationId?.toString() ?: "—")
            AccountBalances(account)
            Metric(R.string.venue_accounts_positions, account.positionStatus)
            Metric(R.string.venue_accounts_observed, account.positionObservedAt.displayTimestamp())
            Metric(R.string.venue_accounts_source, account.positionPayloadSourceObservationId?.toString() ?: "—")
            AccountPositions(account)
            Metric(R.string.venue_accounts_reconciliation, account.reconciliation.effectiveStatus)
            Metric(R.string.venue_accounts_evaluation, account.reconciliation.evaluationStatus ?: "—")
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
        Metric(R.string.venue_accounts_numeric_source, balance.numericProvenance ?: "—")
    }
}

@Composable
private fun AccountPositions(account: VenueAccountDto) {
    if (account.openPositions == null) Text("—")
    account.openPositions?.forEach { position ->
        Text("${position.symbol} · ${position.side}")
        Metric(R.string.size, position.size.display())
        Metric(R.string.average_price, position.entryPrice.display())
        Metric(R.string.mark_price, position.markPrice.display())
        FinancialMetric(R.string.pnl, position.unrealizedPnl)
        FinancialMetric(R.string.venue_accounts_funding, position.unrealizedFunding)
    }
}
