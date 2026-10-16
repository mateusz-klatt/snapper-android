package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.data.SignalDto
import ch.snapper.android.features.signals.SignalsActions
import ch.snapper.android.features.signals.SignalsState
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SignalsScreenUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun strategySelectionChangesExportableRowsAndRevocationRemovesControls() {
        val state = mutableStateOf(SignalsState(scopeKey = "session-a", allowed = true, rows = listOf(
            SignalDto("s1", "BTC", "venue", "buy", BigDecimal("0.9"), "fixture", "2026-10-16T00:00:00Z", strategyName = "Strategy A"),
            SignalDto("s2", "ETH", "venue", "sell", BigDecimal("0.5"), "fixture", "2026-10-16T00:00:00Z", strategyName = "Strategy B"),
        )))
        compose.setContent { LazyColumn { signalsScreen(state.value, SignalsActions({}, { state.value = state.value.copy(selectedStrategy = it) })) } }
        compose.onNodeWithTag("signals.export").assertIsEnabled()
        compose.onNodeWithTag("signals.filter").performClick()
        compose.onNode(hasText("Strategy A") and hasAnyAncestor(isPopup())).performClick()
        compose.runOnIdle { assertEquals(1, state.value.visibleRows.size); assertEquals("BTC", state.value.visibleRows.single().instrument) }
        compose.runOnIdle { state.value = SignalsState(scopeKey = "revoked") }
        compose.onNodeWithTag("signals.filter").assertDoesNotExist()
        compose.onNodeWithTag("signals.export").assertDoesNotExist()
    }

    @Test fun emptyAndFailedFeedCannotExport() {
        compose.setContent { LazyColumn { signalsScreen(SignalsState(allowed = true, failed = true), SignalsActions({}, {})) } }
        compose.onNodeWithTag("signals.export").assertIsNotEnabled()
    }
}
