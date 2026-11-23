package ch.snapper.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.R
import ch.snapper.android.data.AlertDto
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.PositionDto
import ch.snapper.android.data.SystemSystemStatusDataDto
import ch.snapper.android.features.home.*
import java.util.Locale

private val StatusGreen = Color(0xFF16A34A)
private val StatusAmber = Color(0xFFD97706)
private val StatusRed = Color(0xFFDC2626)

@Composable
internal fun HomeScreen(state: AppUiState, actions: AppActions, onOpenAiReviews: () -> Unit = {}) {
    val glance = HomeGlanceReducer.derive(state)
    WalletSelector(state, actions)
    ConnectionStatusRow(glance.badge)
    glance.latestAlert?.let { LatestAlertCard(it) }
    if (glance.showTraderStatus) TraderStatusSection(glance, actions.loadHome)
    if (glance.showAiReviews) AiReviewsSummaryCard(glance.showPendingAiCount, glance.pendingAiReviewCount, onOpenAiReviews)
    if (glance.showPositions && glance.openPositions.isNotEmpty()) HomePositionsCard(glance.openPositions)
    if (glance.showOrders && glance.recentOrders.isNotEmpty()) HomeRecentOrdersCard(glance.recentOrders)
    Text(stringResource(R.string.snapshot_note), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.snapshot_limit), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun WalletSelector(state: AppUiState, actions: AppActions) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.snapshot?.wallets?.firstOrNull { it.publicId == state.user?.activeWalletPublicId }
    Text(stringResource(R.string.wallet))
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("home.wallet")) {
            Text(selected?.label ?: stringResource(if (state.user?.activeWalletPublicId == null) R.string.all_wallets else R.string.wallet))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(modifier = Modifier.testTag("wallet.all"), text = { Text(stringResource(R.string.all_wallets)) }, onClick = { expanded = false; actions.selectWallet(null) })
            state.snapshot?.wallets?.forEach { wallet ->
                DropdownMenuItem(modifier = Modifier.testTag("wallet.${wallet.publicId}"), text = { Text(wallet.label + if (wallet.isPaper) " · ${stringResource(R.string.paper)}" else "") },
                    onClick = { expanded = false; actions.selectWallet(wallet.publicId) })
            }
        }
    }
}

@Composable
private fun ConnectionStatusRow(badge: HomeConnectionBadge) {
    val dotColor = when (badge.health) {
        HomeConnectionHealth.FRESH -> StatusGreen
        HomeConnectionHealth.WARNING -> StatusAmber
        HomeConnectionHealth.DISCONNECTED -> StatusRed
    }
    val base = stringResource(when (badge.label) {
        HomeConnectionLabel.CONNECTED -> R.string.home_connection_connected
        HomeConnectionLabel.CONNECTING -> R.string.home_connection_connecting
        HomeConnectionLabel.AUTHENTICATING -> R.string.home_connection_authenticating
        HomeConnectionLabel.DISCONNECTED -> R.string.home_connection_disconnected
    })
    val text = if (badge.label == HomeConnectionLabel.CONNECTED && badge.ageSeconds != null) {
        "$base · ${pluralStringResource(R.plurals.home_heartbeat_age_seconds, badge.ageSeconds, badge.ageSeconds)}"
    } else base
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("home.connection")) {
        Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LatestAlertCard(alert: AlertDto) {
    val border = if (alert.isSafetyCritical) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null
    Card(Modifier.fillMaxWidth().testTag("home.latestAlert"), border = border) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_section_latest_alert), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(alert.timestamp.displayTimestamp(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (alert.isSafetyCritical) {
                Text(stringResource(R.string.safety_critical), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(alert.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (alert.body.isNotBlank()) {
                Text(alert.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TraderStatusSection(glance: HomeGlanceModel, retry: () -> Unit) {
    when {
        glance.traderStatus != null -> TraderStatusCard(glance.traderStatus, glance.showPositions, glance.openPositions.size, glance.showOrders, glance.activeOrderCount)
        glance.traderLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        glance.traderError != null -> ErrorCard(glance.traderError, retry)
    }
}

@Composable
private fun TraderStatusCard(
    status: SystemSystemStatusDataDto,
    showPositions: Boolean,
    openPositionCount: Int,
    showOrders: Boolean,
    activeOrderCount: Int,
) {
    val running = status.trader.status == "running"
    Card(Modifier.fillMaxWidth().testTag("home.traderStatus")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.system_trader), style = MaterialTheme.typography.titleMedium)
                StatusPill(status.trader.status, if (running) StatusGreen else StatusRed)
            }
            if (!status.strategies.isNullOrEmpty()) {
                Metric(R.string.home_active_strategies, status.strategies.size.toString())
            }
            if (showPositions) Metric(R.string.venue_accounts_positions, openPositionCount.toString())
            if (showOrders) Metric(R.string.home_active_orders, activeOrderCount.toString())
        }
    }
}

@Composable
private fun AiReviewsSummaryCard(showPendingCount: Boolean, pendingCount: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("home.aiReviews")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ai_reviews_decisions_title), style = MaterialTheme.typography.titleMedium)
                if (showPendingCount) {
                    Text(stringResource(R.string.ai_reviews_pending_title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showPendingCount) {
                Surface(
                    shape = CircleShape,
                    color = if (pendingCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("home.aiReviews.pendingCount"),
                ) {
                    Text(
                        pendingCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (pendingCount > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePositionsCard(positions: List<PositionDto>) {
    Card(Modifier.fillMaxWidth().testTag("home.positions")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.venue_accounts_positions), style = MaterialTheme.typography.titleMedium)
            positions.forEachIndexed { index, position ->
                if (index > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(position.instrument, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.home_position_qty_at_price, position.quantity.display(), position.averagePrice.display()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(position.unrealizedPnl.display(), style = MaterialTheme.typography.titleSmall, color = financialColor(position.unrealizedPnl))
                        Text(stringResource(R.string.positions_timeline_chart_unrealized), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRecentOrdersCard(orders: List<OrderDto>) {
    Card(Modifier.fillMaxWidth().testTag("home.orders")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_section_recent_orders), style = MaterialTheme.typography.titleMedium)
            orders.forEachIndexed { index, order ->
                if (index > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(order.instrument, style = MaterialTheme.typography.titleSmall)
                        Text("${order.side.uppercase(Locale.ROOT)} ${order.size.display()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(order.status, orderStatusColor(order.status))
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, tint: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = tint.copy(alpha = 0.16f)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun orderStatusColor(status: String): Color = when (status.lowercase(Locale.ROOT)) {
    "filled" -> StatusGreen
    "partially_filled" -> StatusAmber
    "cancelled", "rejected" -> StatusRed
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
