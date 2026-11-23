package ch.snapper.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.R
import ch.snapper.android.data.*
import ch.snapper.android.features.home.HomeState
import ch.snapper.android.features.live.LiveConnectionStatus
import ch.snapper.android.features.system.SystemFeedState
import ch.snapper.android.features.system.SystemState
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenUiTest {
    @get:Rule val compose = createComposeRule()
    private val base = Instant.parse("2026-10-16T12:00:00Z")

    @Test fun glanceRendersStatusBadgePositionsOrdersAlertAndPendingCount() {
        val permissions = listOf("read:system_status", "read:positions", "read:orders", "read:notifications", "read:signals")
        val user = UserDto("trader", "operator", "u1", activeWalletPublicId = "w1", delegatePublicId = "del-1", effectivePermissions = permissions)
        val snapshot = DashboardSnapshot(
            user = user,
            wallets = listOf(WalletDto("w1", "Primary", isPaper = true)),
            positions = listOf(
                PositionDto("p1", "BTC-USD", "kraken", BigDecimal("0.25"), BigDecimal.ZERO, BigDecimal("50000"), BigDecimal("123.456789"), walletPublicId = "w1"),
                PositionDto("p2", "ETH-USD", "kraken", BigDecimal("1.5"), BigDecimal.ZERO, BigDecimal("3000"), BigDecimal("12.5"), walletPublicId = "w1"),
            ),
            orders = listOf(
                OrderDto("o1", "c1", "ETH-USD", "kraken", "buy", "open", "limit", BigDecimal("1.5"), BigDecimal.ZERO, "2026-10-16T11:59:00Z", walletPublicId = "w1"),
                OrderDto("o2", "c2", "BTC-USD", "kraken", "sell", "filled", "limit", BigDecimal("0.25"), BigDecimal("0.25"), "2026-10-16T11:58:00Z", walletPublicId = "w1"),
            ),
            alerts = listOf(AlertDto("a1", "2026-10-16T11:58:00Z", "order_fill_full", "high", true, "Order filled", "Bought ETH")),
        )
        val state = mutableStateOf(
            AppUiState(
                user = user,
                snapshot = snapshot,
                isRestoring = false,
                system = SystemState(permissions.toSet(), status = SystemFeedState(SystemSystemStatusDataDto("s1", SystemProcessStatusDto("running"), emptyMap(), listOf(SystemStrategyStatusPayloadDto("mean_rev", "running"))))),
                home = HomeState(LiveConnectionStatus.SUBSCRIBED, setOf("system.heartbeats."), base, base, base.plusSeconds(3), pendingAiReviewCount = 4),
            ),
        )
        var openedAiReviews = 0
        val actions = AppActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _, _ -> })
        var runningLabel = ""
        compose.setContent {
            runningLabel = stringResource(R.string.system_running)
            CompositionLocalProvider(LocalNumberLocale provides Locale.ROOT, LocalDisplayTimeZone provides ZoneOffset.UTC) {
                SnapperTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        HomeScreen(state.value, actions) { openedAiReviews++ }
                    }
                }
            }
        }
        compose.onNodeWithTag("home.connection").assertTextContains("Connected · 3 seconds ago")
        compose.onNodeWithTag("home.latestAlert").assertTextContains("Order filled")
        compose.onNodeWithTag("home.traderStatus").assertTextContains(runningLabel)
        compose.onNodeWithTag("home.aiReviews.pendingCount").assertTextEquals("4")
        compose.onNodeWithTag("home.aiReviews").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, openedAiReviews) }
        compose.onNodeWithTag("home.positions").assertExists()
        val positionRows = compose.onAllNodesWithTag("home.positions.row").assertCountEquals(2)
        positionRows[0].performScrollTo().assertTextContains("BTC-USD").assertTextContains("123.456789")
        positionRows[1].performScrollTo().assertTextContains("ETH-USD")
        compose.onNodeWithTag("home.orders").assertExists()
        val orderRows = compose.onAllNodesWithTag("home.orders.row").assertCountEquals(2)
        orderRows[0].performScrollTo().assertTextContains("ETH-USD")
        orderRows[1].performScrollTo().assertTextContains("BTC-USD")

        compose.runOnIdle {
            state.value = state.value.copy(home = state.value.home.copy(status = LiveConnectionStatus.STOPPED, lastHeartbeatAt = null, connectedAt = null))
        }
        compose.onNodeWithTag("home.connection").performScrollTo().assertTextEquals("Disconnected")
    }
}
