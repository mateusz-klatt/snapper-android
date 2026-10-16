package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.data.*
import ch.snapper.android.features.operations.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProcessesScreenUiTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture(): ProcessesState {
        val count = ProcessCategoryDto(1, 1)
        val target = ConfiguredProcessDto("feed", true, true, "feed", "instance", false)
        return ProcessesState(permissions = setOf("manage:processes"),
            summary = ProcessSummaryDto(count, count, count, count, listOf(ProcessSummaryRowDto("feed", true, true, "feed", "long_running"))), configured = mapOf("feed" to target))
    }

    @Test fun confirmationIsRequiredAndUnknownOutcomeDisablesControls() {
        val state = mutableStateOf(fixture())
        var submissions = 0
        val actions = ProcessesActions({}, { name, action -> state.value = state.value.copy(confirmation = ProcessConfirmation(state.value.configured.getValue(name), action, null)) },
            { state.value = state.value.copy(confirmation = null) }, {
                submissions++
                state.value = state.value.copy(confirmation = null, operations = mapOf("feed" to ProcessOperation(outcome = ProcessOutcome.UNKNOWN)))
            })
        compose.setContent { LazyColumn(Modifier.testTag("process-list")) { processesScreen(state.value, actions) } }
        compose.onNodeWithTag("process-list").performScrollToIndex(1)
        compose.onNodeWithTag("process.feed.restart").performClick()
        compose.runOnIdle { assertEquals(0, submissions) }
        compose.onNodeWithTag("process.confirm").performClick()
        compose.runOnIdle { assertEquals(1, submissions) }
        compose.onNodeWithTag("process.feed.restart").assertIsNotEnabled()
        compose.onNodeWithTag("process.feed.stop").assertIsNotEnabled()
    }

    @Test fun revokedPermissionHidesControlsAndPartialOutcomeRemainsVisible() {
        val state = mutableStateOf(fixture().copy(operations = mapOf("feed" to ProcessOperation(outcome = ProcessOutcome.PARTIAL_RESTART))))
        compose.setContent { LazyColumn(Modifier.testTag("process-list")) { processesScreen(state.value, ProcessesActions({}, { _, _ -> }, {}, {})) } }
        compose.onNodeWithTag("process-list").performScrollToIndex(1)
        compose.onNodeWithTag("process.feed.outcome").assertExists()
        compose.runOnIdle { state.value = state.value.copy(permissions = emptySet()) }
        compose.onNodeWithTag("process.feed.restart").assertDoesNotExist()
        compose.onNodeWithTag("process.feed.stop").assertDoesNotExist()
    }
}
