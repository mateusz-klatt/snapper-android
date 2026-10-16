package ch.snapper.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.WalletDto
import ch.snapper.android.features.pnl.PnlActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PnlNavigationLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arrivingWalletCatalogueAndSameIdModeChangeReloadVisibleTimeline() {
        val user = UserDto("reader", "viewer", "u", activeWalletPublicId = "w", effectivePermissions = listOf("read:positions"))
        val state = mutableStateOf(AppUiState(user = user, isRestoring = false))
        val observedModes = mutableListOf<Boolean?>()
        val actions = AppActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _, _ -> },
            pnl = PnlActions({ observedModes += state.value.snapshot?.wallets?.singleOrNull()?.isPaper }, {}, {}, {}))
        compose.setContent { SnapperTheme { SnapperApp(state.value, actions) } }
        compose.onNodeWithTag("nav.pnl").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf<Boolean?>(null), observedModes) }
        val paper = WalletDto("w", "Wallet", isPaper = true)
        compose.runOnIdle { state.value = state.value.copy(snapshot = DashboardSnapshot(user, listOf(paper), emptyList(), emptyList(), emptyList())) }
        compose.runOnIdle { assertEquals(listOf(null, true), observedModes) }
        compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot?.copy(wallets = listOf(paper.copy(isPaper = false)))) }
        compose.runOnIdle { assertEquals(listOf(null, true, false), observedModes) }
        compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot?.copy(wallets = emptyList())) }
        compose.runOnIdle { assertEquals(listOf(null, true, false, null), observedModes) }
        compose.runOnIdle { state.value = state.value.copy(user = user.copy(primaryOperatorPublicId = "new-desk")) }
        compose.runOnIdle { assertEquals(listOf(null, true, false, null, null), observedModes) }
    }
}
