package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.R
import ch.snapper.android.data.*
import ch.snapper.android.features.accounts.VenueAccountsState
import java.math.BigDecimal
import java.time.Instant
import org.junit.Rule
import org.junit.Test

class VenueAccountsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun copyText(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private fun row(mode: String) = VenueAccountDto("account", "wallet", "kraken", mode, "observed", "observed", true,
        "balance-ok", "positions-ok", "native_only", balances = listOf(VenueBalanceDto("BTC", BigDecimal.ONE, totalDecimal = "1.1234567890123456789")),
        balanceObservedAt = now.minusSeconds(1).toString(), positionObservedAt = now.minusSeconds(1).toString(),
        authoritativeUntil = now.plusSeconds(60).toString(), reconciliation = VenueReconciliationDto("incomplete", false))

    @Test fun liveObservationAgesToStaleWithoutFabricatingBalances() {
        val state = mutableStateOf(VenueAccountsState(accounts = listOf(row("live")), lastSuccessfulFetch = now, now = now))
        compose.setContent { MaterialTheme { LazyColumn { venueAccountsScreen(state.value, {}) } } }
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_observed)).assertExists()
        compose.onNodeWithText("1.1234567890123456789").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(now = now.plusSeconds(16)) }
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_stale)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1.1234567890123456789").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("order.new").assertDoesNotExist()
    }

    @Test fun paperNeverClaimsObservedAuthorityAndUnknownRemainsUnknown() {
        val state = mutableStateOf(VenueAccountsState(accounts = listOf(row("paper").copy(effectiveStatus = "simulated", isAuthoritative = false)), lastSuccessfulFetch = now, now = now))
        compose.setContent { MaterialTheme { LazyColumn { venueAccountsScreen(state.value, {}) } } }
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_simulated)).assertExists()
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_observed)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(accounts = listOf(row("live").copy(effectiveStatus = "unknown", balances = null))) }
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_unknown)).assertExists()
        compose.onNodeWithText("1.1234567890123456789").assertDoesNotExist()
        compose.onNodeWithTag("order.new").assertDoesNotExist()
    }
    @Test fun accountErrorIsVisibleAlongsideExactLastKnownBalance() {
        val account = row("live").copy(effectiveStatus = "error", isAuthoritative = false, error = "Venue observation unavailable")
        val state = VenueAccountsState(accounts = listOf(account), lastSuccessfulFetch = now, now = now)
        compose.setContent { MaterialTheme { LazyColumn { venueAccountsScreen(state, {}) } } }
        compose.onNodeWithText("Venue observation unavailable").assertExists()
        compose.onNodeWithText("1.1234567890123456789").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_observed)).assertDoesNotExist()
    }

    @Test fun corruptAndUnsupportedStatesAreNotSoftened() {
        val state = mutableStateOf(VenueAccountsState(accounts = listOf(row("live").copy(
            effectiveStatus = "corrupt", isAuthoritative = false, balances = null)), lastSuccessfulFetch = now, now = now))
        compose.setContent { MaterialTheme { LazyColumn { venueAccountsScreen(state.value, {}) } } }
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_corrupt)).assertExists()
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_unverified)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(accounts = listOf(row("live").copy(
            effectiveStatus = "unsupported", isAuthoritative = false, balances = null))) }
        compose.onNodeWithText(copyText(R.string.venue_accounts_reconciliation_status_unsupported)).assertExists()
        compose.onNodeWithText(copyText(R.string.venue_accounts_status_unavailable)).assertDoesNotExist()
    }

    @Test fun incompleteReconciliationIsNotReportedAsACompletedVerdict() {
        val state = mutableStateOf(VenueAccountsState(accounts = listOf(row("live")), lastSuccessfulFetch = now, now = now))
        compose.setContent { MaterialTheme { LazyColumn { venueAccountsScreen(state.value, {}) } } }
        compose.onNodeWithText(copyText(R.string.venue_accounts_evaluation_incomplete)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(copyText(R.string.venue_accounts_reconciliation_status_reconciled)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(accounts = listOf(row("live").copy(
            reconciliation = VenueReconciliationDto("mismatched", true)))) }
        compose.onNodeWithText(copyText(R.string.venue_accounts_evaluation_mismatched)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(copyText(R.string.venue_accounts_evaluation_incomplete)).assertDoesNotExist()
    }

}
