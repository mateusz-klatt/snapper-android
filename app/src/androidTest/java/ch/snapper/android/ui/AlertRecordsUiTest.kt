package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.AppUiState
import ch.snapper.android.R
import ch.snapper.android.data.AlertDto
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.UserDto
import org.junit.Rule
import org.junit.Test

class AlertRecordsUiTest {
    @get:Rule val compose = createComposeRule()

    private fun state(alerts: List<AlertDto> = emptyList(), loading: Boolean = false): AppUiState {
        val viewer = UserDto("viewer", "viewer", effectivePermissions = listOf("read:alerts"))
        return AppUiState(user = viewer, isRestoring = false, isLoading = loading,
            snapshot = DashboardSnapshot(viewer, emptyList(), emptyList(), emptyList(), alerts))
    }

    private fun alert(id: String, critical: Boolean = false) = AlertDto(
        id, "2026-10-16T10:00:00Z", "risk", "high", critical,
        "Alert $id", "Body $id", "thread-$id", "alerts.$id",
    )

    private fun reveal(text: String) {
        compose.onNodeWithTag("alerts").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun toggle(id: String) {
        compose.onNodeWithTag("alerts").performScrollToNode(hasTestTag("alert.details.$id"))
        compose.onNodeWithTag("alert.details.$id").performScrollTo().performClick()
    }

    @Test fun safetyAlertDetailsExpandIndependentlyAndCollapseWithoutChangingOtherRecord() {
        var safety = ""
        compose.setContent {
            safety = stringResource(R.string.safety_critical)
            MaterialTheme { LazyColumn(Modifier.testTag("alerts")) { alertsScreen(state(listOf(alert("a", true), alert("b")))) } }
        }
        reveal(safety)
        compose.onNodeWithText("thread-a").assertDoesNotExist()
        toggle("a")
        reveal("thread-a")
        reveal("alerts.a")
        toggle("b")
        reveal("thread-b")
        toggle("a")
        compose.onNodeWithText("thread-a").assertDoesNotExist()
        reveal("thread-b")
        reveal("Body b")
    }

    @Test fun missingMetadataUsesTypeAsTitleWithoutInventingThreadOrSource() {
        var thread = ""
        var source = ""
        val record = alert("missing").copy(title = "", alertType = "backend_notice", threadKey = null, sourceTopic = null)
        compose.setContent {
            thread = stringResource(R.string.records_thread)
            source = stringResource(R.string.records_source_topic)
            MaterialTheme { LazyColumn(Modifier.testTag("alerts")) { alertsScreen(state(listOf(record))) } }
        }
        reveal("backend_notice")
        toggle("missing")
        reveal("Body missing")
        compose.onNodeWithText(thread).assertDoesNotExist()
        compose.onNodeWithText(source).assertDoesNotExist()
        toggle("missing")
        reveal("Body missing")
    }

    @Test fun loadingDoesNotClaimEmptyAndLoadedRecordsReplaceEmptyState() {
        val screen = mutableStateOf(state(loading = true))
        var empty = ""
        compose.setContent {
            empty = stringResource(R.string.empty)
            MaterialTheme { LazyColumn(Modifier.testTag("alerts")) { alertsScreen(screen.value) } }
        }
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.runOnIdle { screen.value = state() }
        compose.onNodeWithText(empty).assertIsDisplayed()
        compose.runOnIdle { screen.value = state(listOf(alert("arrived"))) }
        compose.onNodeWithText(empty).assertDoesNotExist()
        reveal("Body arrived")
    }
}
