package ch.snapper.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.data.DeskDto
import ch.snapper.android.data.UserDto
import ch.snapper.android.features.desks.DeskState
import ch.snapper.android.features.system.SystemState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FeatureNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val viewer = UserDto("viewer", "viewer", "viewer-id", effectivePermissions = emptyList())
    private fun actions(logout: () -> Unit = {}) = AppActions({ _, _ -> }, {}, {}, logout, {}, {}, {}, { _, _, _ -> })

    @Test fun logoutRequiresExplicitConfirmationAndScopeChangeClearsIt() {
        var logouts = 0
        val state = mutableStateOf(AppUiState(user = viewer, isRestoring = false))
        compose.setContent { SnapperTheme { SnapperApp(state.value, actions { logouts++ }) } }
        compose.onNodeWithTag("nav.settings").performScrollTo().performClick()
        compose.onNodeWithTag("settings.logout").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, logouts) }
        compose.onNodeWithTag("settings.dismiss").performClick()
        compose.onNodeWithTag("settings.confirm").assertDoesNotExist()
        compose.onNodeWithTag("settings.logout").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(user = viewer.copy(publicId = "other")) }
        compose.onNodeWithTag("settings.confirm").assertDoesNotExist()
        compose.onNodeWithTag("settings.logout").performScrollTo().performClick()
        compose.onNodeWithTag("settings.confirm").performClick()
        compose.runOnIdle { assertEquals(1, logouts) }
    }

    @Test fun viewerCannotSeeAdministrativeToolsOrAttachDeskMember() {
        val state = mutableStateOf(AppUiState(user = viewer, isRestoring = false,
            desks = DeskState(desks = listOf(DeskDto("desk", "Desk")), loaded = true)))
        compose.setContent { SnapperTheme { SnapperApp(state.value, actions()) } }
        compose.onNodeWithTag("nav.system").assertDoesNotExist()
        compose.onNodeWithTag("nav.desks").performScrollTo().performClick()
        compose.onNodeWithTag("desk.attach").assertDoesNotExist()
        compose.onNodeWithTag("desk.username").assertDoesNotExist()
        compose.runOnIdle {
            val permissions = setOf("read:system_status")
            state.value = state.value.copy(user = viewer.copy(effectivePermissions = permissions.toList()), system = SystemState(permissions = permissions))
        }
        compose.onNodeWithTag("nav.system").performScrollTo().performClick()
        compose.onNodeWithTag("system.health").assertExists()
        compose.onNodeWithTag("system.users").assertDoesNotExist()
        compose.runOnIdle {
            val permissions = setOf("manage:users")
            state.value = state.value.copy(user = viewer.copy(role = "admin", effectivePermissions = permissions.toList()), system = SystemState(permissions = permissions))
        }
        compose.onNodeWithTag("system.users").assertExists()
        compose.onNodeWithTag("system.health").assertDoesNotExist()
    }
}
