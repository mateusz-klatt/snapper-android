package ch.snapper.android.features.home

import ch.snapper.android.AppUiState
import ch.snapper.android.UiError
import ch.snapper.android.data.AlertDto
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.PositionDto
import ch.snapper.android.data.SystemSystemStatusDataDto
import ch.snapper.android.features.live.LiveConnectionStatus
import ch.snapper.android.features.records.OrderSegment
import ch.snapper.android.features.records.RecordFilters
import ch.snapper.android.features.system.SystemFeedState
import java.time.Duration
import java.time.Instant

enum class HomeConnectionHealth { FRESH, WARNING, DISCONNECTED }
enum class HomeConnectionLabel { CONNECTED, CONNECTING, AUTHENTICATING, DISCONNECTED }

data class HomeConnectionBadge(
    val label: HomeConnectionLabel,
    val health: HomeConnectionHealth,
    val ageSeconds: Int?,
)

data class HomeState(
    val status: LiveConnectionStatus = LiveConnectionStatus.STOPPED,
    val acceptedTopics: Set<String> = emptySet(),
    val connectedAt: Instant? = null,
    val lastHeartbeatAt: Instant? = null,
    val now: Instant = Instant.EPOCH,
    val pendingAiReviewCount: Int = 0,
)

data class HomeGlanceModel(
    val badge: HomeConnectionBadge,
    val showTraderStatus: Boolean,
    val traderStatus: SystemSystemStatusDataDto?,
    val traderLoading: Boolean,
    val traderError: UiError?,
    val showPositions: Boolean,
    val openPositions: List<PositionDto>,
    val showOrders: Boolean,
    val activeOrderCount: Int,
    val recentOrders: List<OrderDto>,
    val latestAlert: AlertDto?,
    val showAiReviews: Boolean,
    val showPendingAiCount: Boolean,
    val pendingAiReviewCount: Int,
)

object HomeGlanceReducer {
    const val HEARTBEAT_FRESH_SECONDS = 5L
    const val HEARTBEAT_STALE_SECONDS = 30L
    const val HEARTBEATS_TOPIC = "system.heartbeats."
    const val RECENT_ORDERS_LIMIT = 5

    fun connectionBadge(home: HomeState): HomeConnectionBadge = when (home.status) {
        LiveConnectionStatus.STOPPED, LiveConnectionStatus.FAILED ->
            HomeConnectionBadge(HomeConnectionLabel.DISCONNECTED, HomeConnectionHealth.DISCONNECTED, null)
        LiveConnectionStatus.CONNECTING, LiveConnectionStatus.RECONNECTING ->
            HomeConnectionBadge(HomeConnectionLabel.CONNECTING, HomeConnectionHealth.WARNING, null)
        LiveConnectionStatus.AUTHENTICATING ->
            HomeConnectionBadge(HomeConnectionLabel.AUTHENTICATING, HomeConnectionHealth.WARNING, null)
        LiveConnectionStatus.SUBSCRIBED -> {
            val reference = home.lastHeartbeatAt ?: home.connectedAt
            val age = reference?.let {
                Duration.between(it, home.now).seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            }
            val health = when {
                home.lastHeartbeatAt != null -> when {
                    age!! <= HEARTBEAT_FRESH_SECONDS -> HomeConnectionHealth.FRESH
                    age <= HEARTBEAT_STALE_SECONDS -> HomeConnectionHealth.WARNING
                    else -> HomeConnectionHealth.DISCONNECTED
                }
                HEARTBEATS_TOPIC in home.acceptedTopics ->
                    if (age != null && age > HEARTBEAT_STALE_SECONDS) HomeConnectionHealth.DISCONNECTED
                    else HomeConnectionHealth.WARNING
                else -> HomeConnectionHealth.WARNING
            }
            HomeConnectionBadge(HomeConnectionLabel.CONNECTED, health, age)
        }
    }

    fun derive(state: AppUiState): HomeGlanceModel {
        val user = state.user
        val permissions = user?.effectivePermissions.orEmpty().toSet()
        val wallet = user?.activeWalletPublicId
        val snapshot = state.snapshot
        val canPositions = "read:positions" in permissions
        val canOrders = "read:orders" in permissions
        val canAlerts = "read:notifications" in permissions
        val canSystem = "read:system_status" in permissions
        val canPendingAi = !user?.delegatePublicId.isNullOrBlank() && "read:signals" in permissions
        val canAiReviews = "read:ai_reviews" in permissions || canPendingAi
        val openPositions = if (canPositions && snapshot != null) {
            snapshot.positions.filter { RecordFilters.walletMatches(it.walletPublicId, wallet) }
        } else emptyList()
        val activeOrderCount = if (canOrders && snapshot != null) {
            RecordFilters.orders(snapshot.orders, OrderSegment.OPEN, wallet).size
        } else 0
        val recentOrders = if (canOrders && snapshot != null) {
            RecordFilters.orders(snapshot.orders, OrderSegment.RECENT, wallet).take(RECENT_ORDERS_LIMIT)
        } else emptyList()
        val latestAlert = if (canAlerts && snapshot != null) snapshot.alerts.firstOrNull() else null
        val traderFeed = if (canSystem) state.system.status else SystemFeedState()
        return HomeGlanceModel(
            badge = connectionBadge(state.home),
            showTraderStatus = canSystem,
            traderStatus = traderFeed.value,
            traderLoading = traderFeed.isLoading,
            traderError = traderFeed.error,
            showPositions = canPositions,
            openPositions = openPositions,
            showOrders = canOrders,
            activeOrderCount = activeOrderCount,
            recentOrders = recentOrders,
            latestAlert = latestAlert,
            showAiReviews = canAiReviews,
            showPendingAiCount = canPendingAi,
            pendingAiReviewCount = if (canPendingAi) state.home.pendingAiReviewCount else 0,
        )
    }
}
