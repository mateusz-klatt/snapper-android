package ch.snapper.android.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.R
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.snapper.android.data.WalletDto
import ch.snapper.android.features.pnl.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PnlTimelineScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun requiresWalletAndAllowsManualFineWindowSelection() {
        val state = mutableStateOf(PnlState())
        var currency = ""
        val actions = PnlActions({}, {}, {}, { currency = it })
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { PnlTimelineScreen(state.value, actions) } }
        compose.onNodeWithTag("pnl.noWallet").assertExists()
        compose.onNodeWithTag("pnl.refresh").assertDoesNotExist()
        compose.runOnIdle { state.value = PnlState(wallet = WalletDto("w", "Paper", isPaper = true), permitted = true, window = PnlWindow.QUARTER, granularity = "1h") }
        compose.onNodeWithTag("pnl.granularity.1m").assertIsEnabled()
        compose.onNodeWithTag("pnl.currency.PLN").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("PLN", currency) }
        compose.runOnIdle { state.value = PnlState() }
        compose.onNodeWithTag("pnl.currency.PLN").assertDoesNotExist()
    }
    private fun copyText(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun fullSeriesDisplaysExactValuesCorrectionsProvenanceAndWithholding() {
        val data = pnlScreenFixture()
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PnlTimelineScreen(PnlState(wallet = WalletDto("w", "Paper", isPaper = true), permitted = true, data = data), PnlActions({}, {}, {}, {}))
            }
        }
        compose.onNodeWithText(copyText(R.string.positions_timeline_chart_net) + ": 123.123456789123456789").performScrollTo().assertExists()
        compose.onNodeWithText(copyText(R.string.positions_timeline_incompleteness_reasons_mark_unavailable)).performScrollTo().assertExists()
        compose.onNodeWithText("instrument-gap", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText(copyText(R.string.positions_timeline_equity_honesty_spotonly)).performScrollTo().assertExists()
        compose.onNodeWithText(copyText(R.string.positions_timeline_equity_honesty_flowsunadjusted)).performScrollTo().assertExists()
        compose.onNodeWithText(copyText(R.string.positions_timeline_equity_honesty_drawdownconverted)).performScrollTo().assertExists()
        compose.onNodeWithText("execution-test-id", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("correction-test-id", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("calc-test-v1", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("USD → EUR · EUR/USD@fx-venue").performScrollTo().assertExists()
        compose.onNodeWithText("BTC-USD · venue · instrument-latest").performScrollTo().assertExists()
        compose.onNodeWithText(copyText(R.string.positions_timeline_contributions_fees) + ": -0.25").performScrollTo().assertExists()
        compose.onNodeWithText("manual · " + copyText(R.string.positions_timeline_attribution_nostrategy)).performScrollTo().assertExists()
        compose.onNodeWithText("plan · demo-strategy").performScrollTo().assertExists()
    }

    @Test fun deniedLoadingErrorAndEmptyStatesRemainDistinct() {
        val wallet = WalletDto("w", "Live", isPaper = false)
        val state = mutableStateOf(PnlState(wallet = wallet))
        var retries = 0
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { PnlTimelineScreen(state.value, PnlActions({ retries++ }, {}, {}, {})) } }
        compose.onNodeWithTag("pnl.denied").assertExists()
        compose.onNodeWithTag("pnl.refresh").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(permitted = true, loading = true) }
        compose.onNodeWithTag("pnl.loading").performScrollTo().assertExists()
        compose.onNodeWithTag("pnl.refresh").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false, failed = true) }
        compose.onNodeWithTag("pnl.error").performScrollTo().assertExists()
        compose.onNodeWithTag("pnl.refresh").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            val original = pnlScreenFixture()
            state.value = state.value.copy(failed = false, data = original.copy(points = emptyList(), rate_sources = emptyList(),
                execution_history = original.execution_history.copy(status = "as_recorded", corrections = emptyList()),
                equity_coverage = original.equity_coverage.copy(sampled = false, venue_scope = null, external_flows_adjusted = null, converted_from = null, conversion_rate_source = null, drawdown_withheld_reason = null)))
        }
        compose.onNodeWithText(copyText(R.string.positions_timeline_empty_message)).performScrollTo().assertExists()
        compose.onNodeWithText(copyText(R.string.positions_timeline_executionhistory_summary)).assertDoesNotExist()
    }

    @Test fun queryControlsForwardChosenWindowAndGranularity() {
        var window: PnlWindow? = null
        var granularity = ""
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) {
            PnlTimelineScreen(PnlState(wallet = WalletDto("w", "Paper", isPaper = true), permitted = true),
                PnlActions({}, { window = it }, { granularity = it }, {}))
        } }
        compose.onNodeWithTag("pnl.window.WEEK").performScrollTo().performClick()
        compose.onNodeWithTag("pnl.granularity.5m").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PnlWindow.WEEK, window); assertEquals("5m", granularity) }
    }

    @Test fun latestWithheldValueIsNotRenderedAsZero() {
        val original = pnlScreenFixture()
        val gap = original.points[1]
        val data = original.copy(points = listOf(gap, gap.copy(point_time = "2026-10-16T00:02:00Z")))
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) {
            PnlTimelineScreen(PnlState(wallet = WalletDto("w", "Paper", isPaper = true), permitted = true, data = data), PnlActions({}, {}, {}, {}))
        } }
        compose.onNodeWithText(copyText(R.string.positions_timeline_chart_net) + ": " + copyText(R.string.positions_timeline_attribution_withheld)).performScrollTo().assertExists()
        compose.onNodeWithText(copyText(R.string.positions_timeline_chart_net) + ": 0").assertDoesNotExist()
    }

}
