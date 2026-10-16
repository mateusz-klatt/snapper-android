package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.features.notifications.NotificationDefaultIssue
import ch.snapper.android.features.notifications.NotificationDefaultsActions
import ch.snapper.android.features.notifications.NotificationDefaultsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationDefaultsScreenUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun permissionlessDefaultsFreezeUnknownSaveUntilExplicitRefresh() {
        val initial = NotificationDefaultsState(active = true, loaded = true)
        val state = mutableStateOf(initial.copy(rows = initial.rows.mapIndexed { index, row -> if (index == 0) row.copy(dirty = true) else row }))
        var saves = 0
        var refreshes = 0
        val actions = NotificationDefaultsActions(
            refresh = {
                refreshes++
                state.value = initial
            },
            setEnabled = { _, _ -> },
            setPriority = { _, _ -> },
            save = { type ->
                saves++
                state.value = state.value.copy(rows = state.value.rows.map { if (it.alertType == type) it.copy(issue = NotificationDefaultIssue.UNKNOWN) else it })
            },
        )
        compose.setContent { LazyColumn(Modifier.testTag("notification-list")) { notificationDefaultsScreen(state.value, actions) } }
        compose.onNodeWithTag("notification.order_fill_full.save").performScrollTo().performClick()
        compose.onNodeWithTag("notification.order_fill_full.save").assertIsNotEnabled()
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("notification.order_fill_full"))).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithTag("notification-list").performScrollToIndex(0)
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(1, saves) }
        compose.onNodeWithTag("notification.order_fill_full.save").assertIsNotEnabled()
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("notification.order_fill_full"))).assertIsEnabled()
    }

    @Test fun pendingSaveAndInitialLoadDisableEditing() {
        val initial = NotificationDefaultsState(active = true, loaded = true)
        val state = mutableStateOf(initial.copy(rows = initial.rows.map { it.copy(pending = true, dirty = true) }))
        compose.setContent { LazyColumn(Modifier.testTag("notification-list")) { notificationDefaultsScreen(state.value, NotificationDefaultsActions({}, { _, _ -> }, { _, _ -> }, {})) } }
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithTag("notification.order_fill_full.save").assertIsNotEnabled()
        compose.runOnIdle { state.value = NotificationDefaultsState(active = true, loading = true) }
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("notification.order_fill_full"))).assertIsNotEnabled()
    }
}
