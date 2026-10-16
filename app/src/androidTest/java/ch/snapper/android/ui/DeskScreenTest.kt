package ch.snapper.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import ch.snapper.android.data.DeskDto
import ch.snapper.android.features.desks.DeskActions
import ch.snapper.android.features.desks.DeskIssue
import ch.snapper.android.features.desks.DeskState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeskScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun submittingAndUnknownDisableAttachmentAndRevocationHidesForm() {
        val state = mutableStateOf(DeskState(desks = listOf(DeskDto("a", "Desk")), selectedDeskPublicId = "a", canManage = true, loaded = true))
        var submissions = 0
        val actions = DeskActions({}, {}, {}, {
            submissions++
            state.value = state.value.copy(submitting = true)
        })
        compose.setContent { DeskScreen(state.value, actions) }
        compose.onNodeWithTag("desk.attach").performClick()
        compose.onNodeWithTag("desk.attach").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, submissions); state.value = state.value.copy(submitting = false, issue = DeskIssue.UNKNOWN) }
        compose.onNodeWithTag("desk.attach").assertIsNotEnabled()
        compose.onNodeWithTag("desk.username").assertIsNotEnabled()
        compose.runOnIdle { state.value = DeskState() }
        compose.onNodeWithTag("desk.attach").assertDoesNotExist()
    }
}
