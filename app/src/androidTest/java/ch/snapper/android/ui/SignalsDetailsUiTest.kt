package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.R
import ch.snapper.android.data.SignalDto
import ch.snapper.android.features.signals.SignalsActions
import ch.snapper.android.features.signals.SignalsState
import java.math.BigDecimal
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SignalsDetailsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun everyVisibleSignalShowsItsOwnStrengthPriceReasonAndLocalizedTime() {
        val tiers = listOf("0.8" to R.string.signals_strong, "0.6" to R.string.signals_medium,
            "0.4" to R.string.signals_weak, "0.2" to R.string.signals_very_weak)
        val rows = tiers.mapIndexed { index, tier -> SignalDto("s$index", "PAIR-$index", "venue", "buy",
            BigDecimal(tier.first), "Evidence $index", "2026-10-16T12:00:00Z",
            price = if (index == 0) BigDecimal("123.45") else null,
            strategyName = if (index == 0) "Strategy A" else null) }
        compose.setContent {
            CompositionLocalProvider(LocalNumberLocale provides Locale.forLanguageTag("pl-PL"), LocalDisplayTimeZone provides ZoneOffset.UTC) {
                LazyColumn(Modifier.testTag("signals.details")) {
                    signalsScreen(SignalsState(allowed = true, rows = rows), SignalsActions({}, {}))
                }
            }
        }
        for ((index, tier) in tiers.withIndex()) {
            compose.onNodeWithTag("signals.details").performScrollToNode(hasText("Evidence $index"))
            compose.onNodeWithText(text(tier.second)).assertIsDisplayed()
            compose.onNodeWithText("Evidence $index").assertIsDisplayed()
            compose.onAllNodes(hasText("UTC", substring = true)).onFirst().assertExists()
            if (index == 0) {
                compose.onNodeWithText("123,45").assertIsDisplayed()
                compose.onNodeWithText("Strategy A").assertIsDisplayed()
            } else compose.onAllNodesWithText("—").onFirst().assertExists()
        }
    }

    @Test fun choosingAllStrategiesUpdatesRenderedRecordsAndLoadingGuardsRefresh() {
        val state = mutableStateOf(SignalsState(allowed = true, selectedStrategy = "Alpha", rows = listOf(
            SignalDto("a", "ALPHA-PAIR", "venue", "buy", BigDecimal.ONE, "Alpha reason", "2026-10-16T12:00:00Z", strategyName = "Alpha"),
            SignalDto("b", "BETA-PAIR", "venue", "sell", BigDecimal.ZERO, "Beta reason", "2026-10-16T12:00:00Z", strategyName = "Beta"),
        )))
        var refreshes = 0
        compose.setContent {
            LazyColumn(Modifier.testTag("signals.details")) {
                signalsScreen(state.value, SignalsActions({ refreshes++ }, { state.value = state.value.copy(selectedStrategy = it) }))
            }
        }
        compose.onNodeWithTag("signals.filter").performClick()
        compose.onNode(hasText(text(R.string.signals_all_strategies)) and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithTag("signals.details").performScrollToNode(hasText("Beta reason"))
        compose.onNodeWithText("Beta reason").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, state.value.selectedStrategy) }
        compose.onNodeWithTag("signals.details").performScrollToIndex(0)
        compose.onNodeWithText(text(R.string.refresh)).performClick()
        compose.runOnIdle { assertEquals(1, refreshes); state.value = state.value.copy(loading = true) }
        compose.onNodeWithText(text(R.string.refresh)).assertIsNotEnabled()
    }
}
