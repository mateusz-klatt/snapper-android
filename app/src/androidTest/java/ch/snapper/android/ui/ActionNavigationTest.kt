package ch.snapper.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.PositionDto
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.WalletDto
import ch.snapper.android.features.positionactions.PositionActionKind
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActionNavigationTest {
    @get:Rule val compose = createComposeRule()

    private fun actions() = AppActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _, _ -> })

    @Test fun administrativeRoleDoesNotExposeUnauthorizedCommands() {
        val user = UserDto("admin", "admin", "u", effectivePermissions = listOf("read:positions"))
        compose.setContent { SnapperTheme { SnapperApp(AppUiState(user = user, isRestoring = false), actions()) } }
        compose.onNodeWithTag("nav.processes").assertDoesNotExist()
        compose.onNodeWithTag("nav.ai_reviews").assertDoesNotExist()
    }

    @Test fun delegateCanOpenPendingReviewsWithoutAuditPermission() {
        val user = UserDto("delegate", "delegate", "u", effectivePermissions = listOf("read:signals"), delegatePublicId = "d")
        var reads = 0
        compose.setContent { SnapperTheme {
            SnapperApp(AppUiState(user = user, isRestoring = false), actions().copy(loadAiReviews = { reads++ }))
        } }
        compose.onNodeWithTag("nav.ai_reviews").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun reductionWithoutCycleIsAvailableOnlyInMatchingWalletMode() {
        val user = UserDto("trader", "viewer", "u", activeWalletPublicId = "w", effectivePermissions = listOf("read:positions", "read:market_data", "create:orders"))
        val wallet = WalletDto("w", "Paper", isPaper = true)
        val position = PositionDto("p", "BTC-USD", "kraken", BigDecimal("2"), BigDecimal.ZERO,
            walletPublicId = "w", instrumentPublicId = "i", mode = "paper")
        val state = mutableStateOf(AppUiState(user = user, isRestoring = false,
            snapshot = DashboardSnapshot(user, listOf(wallet), listOf(position), emptyList(), emptyList())))
        val selected = mutableListOf<PositionActionKind>()
        compose.setContent { SnapperTheme { SnapperApp(state.value, actions().copy(openPositionAction = { _, kind -> selected += kind })) } }
        compose.onNodeWithTag("nav.positions").performScrollTo().performClick()
        compose.onNodeWithTag("position.p.close").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(PositionActionKind.CLOSE), selected) }
        compose.onNodeWithTag("position.p.reduce").assertExists()
        compose.onNodeWithTag("position.p.bracket").assertDoesNotExist()
        compose.onNodeWithTag("position.p.trailing").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(snapshot = state.value.snapshot?.copy(wallets = listOf(wallet.copy(isPaper = false)))) }
        compose.onNodeWithTag("position.p.close").assertDoesNotExist()
    }
}
