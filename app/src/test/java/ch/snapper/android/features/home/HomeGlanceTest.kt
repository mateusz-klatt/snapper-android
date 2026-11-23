package ch.snapper.android.features.home

import ch.snapper.android.AppUiState
import ch.snapper.android.UiError
import ch.snapper.android.data.*
import ch.snapper.android.features.live.LiveConnectionStatus
import ch.snapper.android.features.system.SystemFeedState
import ch.snapper.android.features.system.SystemState
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HomeGlanceTest {
    private val base = Instant.parse("2026-10-16T12:00:00Z")

    @Test fun connectionBadgeFollowsSocketAndHeartbeatFreshnessThresholds() {
        val stopped = HomeGlanceReducer.connectionBadge(HomeState(status = LiveConnectionStatus.STOPPED, lastHeartbeatAt = base, now = base))
        assertEquals(HomeConnectionLabel.DISCONNECTED, stopped.label)
        assertEquals(HomeConnectionHealth.DISCONNECTED, stopped.health)
        assertNull(stopped.ageSeconds)

        val failed = HomeGlanceReducer.connectionBadge(HomeState(status = LiveConnectionStatus.FAILED, now = base))
        assertEquals(HomeConnectionLabel.DISCONNECTED, failed.label)
        assertEquals(HomeConnectionHealth.DISCONNECTED, failed.health)

        val connecting = HomeGlanceReducer.connectionBadge(HomeState(status = LiveConnectionStatus.RECONNECTING, now = base))
        assertEquals(HomeConnectionLabel.CONNECTING, connecting.label)
        assertEquals(HomeConnectionHealth.WARNING, connecting.health)
        assertNull(connecting.ageSeconds)

        val auth = HomeGlanceReducer.connectionBadge(HomeState(status = LiveConnectionStatus.AUTHENTICATING, now = base))
        assertEquals(HomeConnectionLabel.AUTHENTICATING, auth.label)
        assertEquals(HomeConnectionHealth.WARNING, auth.health)

        val topics = setOf("system.heartbeats.")
        val fresh = HomeGlanceReducer.connectionBadge(HomeState(LiveConnectionStatus.SUBSCRIBED, topics, base, base, base.plusSeconds(5)))
        assertEquals(HomeConnectionHealth.FRESH, fresh.health)
        assertEquals(5, fresh.ageSeconds)

        val warning = HomeGlanceReducer.connectionBadge(HomeState(LiveConnectionStatus.SUBSCRIBED, topics, base, base, base.plusSeconds(6)))
        assertEquals(HomeConnectionHealth.WARNING, warning.health)
        assertEquals(6, warning.ageSeconds)

        val stale = HomeGlanceReducer.connectionBadge(HomeState(LiveConnectionStatus.SUBSCRIBED, topics, base, base, base.plusSeconds(31)))
        assertEquals(HomeConnectionHealth.DISCONNECTED, stale.health)
        assertEquals(31, stale.ageSeconds)

        val awaitingHeartbeat = HomeGlanceReducer.connectionBadge(HomeState(LiveConnectionStatus.SUBSCRIBED, topics, base, null, base.plusSeconds(4)))
        assertEquals(HomeConnectionHealth.WARNING, awaitingHeartbeat.health)
        assertEquals(4, awaitingHeartbeat.ageSeconds)

        val missingHeartbeatStale = HomeGlanceReducer.connectionBadge(HomeState(LiveConnectionStatus.SUBSCRIBED, topics, base, null, base.plusSeconds(31)))
        assertEquals(HomeConnectionHealth.DISCONNECTED, missingHeartbeatStale.health)

        val socketOnly = HomeGlanceReducer.connectionBadge(HomeState(LiveConnectionStatus.SUBSCRIBED, setOf("orders.events."), base, null, base.plusSeconds(45)))
        assertEquals(HomeConnectionLabel.CONNECTED, socketOnly.label)
        assertEquals(HomeConnectionHealth.FRESH, socketOnly.health)
        assertEquals(45, socketOnly.ageSeconds)
    }

    @Test fun reducerFiltersPositionsOrdersAlertsAndPreservesExactDecimals() {
        val perms = listOf("read:positions", "read:orders", "read:notifications", "read:system_status", "read:signals")
        val user = UserDto("op", "operator", "u1", activeWalletPublicId = "wallet-a", delegatePublicId = "del-1", effectivePermissions = perms)
        val exactQty = BigDecimal("0.000000000000000001")
        val exactPnl = BigDecimal("-123.456789012345678901")
        val positions = listOf(
            PositionDto("p1", "BTC-USD", "kraken", exactQty, BigDecimal.ZERO, BigDecimal("50000.123456"), exactPnl, walletPublicId = "wallet-a"),
            PositionDto("p2", "ETH-USD", "kraken", BigDecimal.ONE, BigDecimal.ZERO, walletPublicId = null),
            PositionDto("p3", "SOL-USD", "kraken", BigDecimal.ONE, BigDecimal.ZERO, walletPublicId = "wallet-b"),
        )
        val orders = (1..6).map { i ->
            OrderDto("o$i", "c$i", "BTC-USD", "kraken", "buy", if (i <= 2) "open" else "filled", "limit",
                BigDecimal("1.25"), BigDecimal.ZERO, "2026-10-16T12:00:0${i}Z", walletPublicId = "wallet-a")
        } + listOf(
            OrderDto("o-part", "cp", "ETH-USD", "kraken", "sell", "partially_filled", "limit", BigDecimal.ONE, BigDecimal.ZERO, "2026-10-16T11:59:00Z", walletPublicId = null),
            OrderDto("o-other", "co", "SOL-USD", "kraken", "buy", "open", "limit", BigDecimal.ONE, BigDecimal.ZERO, "2026-10-16T12:05:00Z", walletPublicId = "wallet-b"),
        )
        val alerts = listOf(
            AlertDto("a-new", "2026-10-16T12:01:00Z", "order_filled", "high", true, "Filled", "Order filled"),
            AlertDto("a-old", "2026-10-16T11:00:00Z", "info", "low", false, "Old", "Old body"),
        )
        val status = SystemSystemStatusDataDto("s1", SystemProcessStatusDto("running"), emptyMap(), listOf(SystemStrategyStatusPayloadDto("strat", "running")))
        val state = AppUiState(
            user = user,
            snapshot = DashboardSnapshot(user, emptyList(), positions, orders, alerts),
            system = SystemState(permissions = perms.toSet(), status = SystemFeedState(status)),
            home = HomeState(LiveConnectionStatus.SUBSCRIBED, setOf("system.heartbeats."), base, base, base, pendingAiReviewCount = 3),
        )
        val model = HomeGlanceReducer.derive(state)
        assertEquals(listOf("p1", "p2"), model.openPositions.map { it.publicId })
        assertEquals(exactQty, model.openPositions.first().quantity)
        assertEquals(exactPnl, model.openPositions.first().unrealizedPnl)
        assertEquals(3, model.activeOrderCount)
        assertEquals(listOf("o6", "o5", "o4", "o3", "o2"), model.recentOrders.map { it.publicId })
        assertEquals("a-new", model.latestAlert?.publicId)
        assertEquals("running", model.traderStatus?.trader?.status)
        assertTrue(model.showAiReviews)
        assertTrue(model.showPendingAiCount)
        assertEquals(3, model.pendingAiReviewCount)
    }

    @Test fun missingPermissionsHideSectionsAndNonDelegateHidesPendingCount() {
        val user = UserDto("viewer", "viewer", "u2", delegatePublicId = null, effectivePermissions = listOf("read:ai_reviews"))
        val state = AppUiState(
            user = user,
            snapshot = DashboardSnapshot(
                user, emptyList(),
                listOf(PositionDto("p1", "BTC-USD", "kraken", BigDecimal.ONE, BigDecimal.ZERO)),
                listOf(OrderDto("o1", "c1", "BTC-USD", "kraken", "buy", "open", "limit", BigDecimal.ONE, BigDecimal.ZERO, "2026-10-16T12:00:00Z")),
                listOf(AlertDto("a1", "2026-10-16T12:00:00Z", "info", "low", false, "Title", "Body")),
            ),
            system = SystemState(status = SystemFeedState(SystemSystemStatusDataDto("s1", SystemProcessStatusDto("running"), emptyMap()))),
            home = HomeState(pendingAiReviewCount = 7),
        )
        val model = HomeGlanceReducer.derive(state)
        assertFalse(model.showTraderStatus)
        assertNull(model.traderStatus)
        assertFalse(model.showPositions)
        assertTrue(model.openPositions.isEmpty())
        assertFalse(model.showOrders)
        assertTrue(model.recentOrders.isEmpty())
        assertNull(model.latestAlert)
        assertTrue(model.showAiReviews)
        assertFalse(model.showPendingAiCount)
        assertEquals(0, model.pendingAiReviewCount)
    }
}
