package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.live.*
import java.time.Clock
import kotlinx.coroutines.CoroutineScope

/** sessionEpoch must change even when a replacement login has the same user and permissions. */
data class LiveIdentity(
    val backend: String, val auth: OrderScope, val primaryOperatorPublicId: String?,
    val delegatePublicId: String?, val sessionEpoch: Long,
)
class LiveBinding(val identity: LiveIdentity, val tickets: LiveSessionClient, val transport: LiveSocketTransport, val clock: Clock)
data class MarketLoadTicket(val identity: LiveIdentity, val selection: LiveSelection, val generation: Long)
data class LiveViewState(
    val market: LiveMarketState = LiveMarketState(),
    val status: LiveConnectionStatus = LiveConnectionStatus.STOPPED,
    val acceptedTopics: Set<String> = emptySet(),
)

/** UI-dispatcher confined. Snapshot callback starts one read; refreshFeature must suspend until its read completes. */
class LiveCoordinator(
    private val scope: CoroutineScope,
    private val binding: () -> LiveBinding?,
    private val requestSnapshot: (MarketLoadTicket) -> Unit,
    private val refreshFeature: suspend (LiveRefreshTarget) -> Unit,
    private val publish: (LiveViewState) -> Unit,
) {
    private var captured: LiveBinding? = null
    private var foreground = false
    private var marketVisible = false
    private var connection: LiveConnection? = null
    private var connectionEpoch = 0L
    private var requestedTopics = emptySet<String>()
    private var acknowledged = false
    private var pendingSnapshot: MarketLoadTicket? = null
    private var fallbackIssued = false
    private var view = LiveViewState()
    private val decoder = LiveFrameDecoder()
    private val events = LiveEventRouting()
    private val refreshes = LiveRefreshCoordinator(scope, LiveRefreshTarget.entries.associateWith { target ->
        suspend {
            val owner = currentBinding()
            if (foreground && owner != null && events.permitted(target, owner.identity)) refreshFeature(target)
        }
    })
    val state: LiveViewState get() = view

    fun synchronizeBinding() {
        val next = binding()
        if (captured?.identity == next?.identity) return
        stopConnection()
        captured = next
        events.clear()
        view = LiveViewState(market = LiveMarketReducer.reset(view.market))
        emit()
        connectIfNeeded()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) { synchronizeBinding(); return }
        foreground = value
        synchronizeBinding()
        if (!value) {
            stopConnection()
            suspendMarket()
        } else {
            connectIfNeeded()
            if (marketVisible) beginSnapshot()
        }
    }

    fun setMarketVisible(value: Boolean) {
        if (marketVisible == value) return
        marketVisible = value
        synchronizeBinding()
        if (!value) suspendMarket()
        connectIfNeeded()
        if (value && foreground) beginSnapshot()
    }

    fun selectMarket(selection: LiveSelection, forceRefresh: Boolean = false) {
        synchronizeBinding()
        if (!LiveFrameDecoder.validSelection(selection)) return
        if (view.market.selection == selection && !forceRefresh) return
        view = view.copy(market = LiveMarketReducer.select(view.market, selection).copy(loading = false))
        emit()
        connectIfNeeded()
        if (foreground && marketVisible) beginSnapshot()
    }

    fun snapshotCompleted(ticket: MarketLoadTicket, rows: List<CandleDto>) {
        val owner = currentBinding() ?: return
        if (!validTicket(ticket, owner)) return
        view = view.copy(market = LiveMarketReducer.snapshot(view.market, ticket.generation, rows, owner.clock.instant()))
        emit()
    }

    fun snapshotFailed(ticket: MarketLoadTicket) {
        val owner = currentBinding() ?: return
        if (!validTicket(ticket, owner)) return
        view = view.copy(market = LiveMarketReducer.snapshotFailed(view.market, ticket.generation, owner.clock.instant()))
        emit()
    }

    fun invalidate() {
        stopConnection()
        captured = null
        events.clear()
        view = LiveViewState(market = LiveMarketReducer.reset(view.market))
        emit()
    }

    private fun validTicket(ticket: MarketLoadTicket, owner: LiveBinding): Boolean = foreground && marketVisible &&
        ticket.identity == owner.identity && ticket.selection == view.market.selection && ticket.generation == view.market.generation

    private fun currentBinding(): LiveBinding? = captured?.takeIf { binding()?.identity == it.identity }
    private fun emit() { publish(view) }

    private fun suspendMarket() {
        pendingSnapshot = null
        fallbackIssued = false
        view = view.copy(market = view.market.copy(generation = view.market.generation + 1, loading = false, pending = emptyMap()))
        emit()
    }

    private fun beginSnapshot() {
        val owner = currentBinding() ?: return
        val selection = view.market.selection ?: return
        if (!foreground || !marketVisible || "read:market_data" !in owner.identity.auth.effectivePermissions) return
        view = view.copy(market = LiveMarketReducer.select(view.market, selection))
        emit()
        pendingSnapshot = MarketLoadTicket(owner.identity, selection, view.market.generation)
        when {
            marketAccepted() -> dispatchSnapshot()
            acknowledged || fallbackIssued || view.status in setOf(LiveConnectionStatus.RECONNECTING, LiveConnectionStatus.FAILED) -> fallbackSnapshot()
        }
    }

    private fun marketAccepted(): Boolean {
        val selection = view.market.selection ?: return false
        return selection.candleTopic in view.acceptedTopics && selection.tickTopic in view.acceptedTopics
    }

    private fun dispatchSnapshot() {
        val ticket = pendingSnapshot ?: return
        pendingSnapshot = null
        requestSnapshot(ticket)
    }

    private fun fallbackSnapshot() {
        if (pendingSnapshot == null || !foreground || !marketVisible) return
        fallbackIssued = true
        dispatchSnapshot()
    }

    private fun connectIfNeeded() {
        val owner = currentBinding() ?: return
        if (!foreground) return
        val topics = desiredTopics(owner)
        if (connection != null && topics == requestedTopics) return
        stopConnection()
        if (topics.isEmpty()) return
        requestedTopics = topics
        val epoch = connectionEpoch
        val refreshEpoch = refreshes.currentEpoch
        connection = LiveConnection(scope, owner.tickets, owner.transport,
            { accepted -> subscribed(epoch, accepted) },
            { bytes -> receive(epoch, refreshEpoch, bytes) },
            { status -> connectionStatus(epoch, status) }, owner.clock::instant)
        connection?.start(topics)
    }

    private fun desiredTopics(owner: LiveBinding): Set<String> {
        val topics = events.topics(owner.identity).toMutableSet()
        val selection = view.market.selection
        if (marketVisible && selection != null && "read:market_data" in owner.identity.auth.effectivePermissions) {
            topics += selection.candleTopic
            topics += selection.tickTopic
        }
        return topics
    }

    private fun stopConnection() {
        connectionEpoch++
        connection?.stop(); connection = null
        requestedTopics = emptySet()
        acknowledged = false
        pendingSnapshot = null
        fallbackIssued = false
        refreshes.invalidate()
        view = view.copy(status = LiveConnectionStatus.STOPPED, acceptedTopics = emptySet())
    }

    private fun active(epoch: Long): Boolean = foreground && epoch == connectionEpoch && currentBinding() != null

    private fun subscribed(epoch: Long, topics: Set<String>) {
        if (!active(epoch)) return
        val recovering = acknowledged || fallbackIssued
        acknowledged = true
        view = view.copy(acceptedTopics = topics)
        emit()
        refreshAcceptedFeeds(topics)
        fallbackIssued = false
        if (recovering && marketVisible) beginSnapshot()
        else if (marketAccepted()) dispatchSnapshot()
        else fallbackSnapshot()
    }

    private fun refreshAcceptedFeeds(topics: Set<String>) {
        val owner = currentBinding() ?: return
        val routes = mapOf(
            "orders.events." to LiveRefreshTarget.ORDERS,
            "signals." to LiveRefreshTarget.SIGNALS,
            "ai_reviews." to LiveRefreshTarget.AI_REVIEWS,
            "processes.events.summary." to LiveRefreshTarget.PROCESSES,
            "processes.events.configured." to LiveRefreshTarget.PROCESSES,
            "processes.events.runs." to LiveRefreshTarget.PROCESSES,
            "strategies.events.list." to LiveRefreshTarget.STRATEGIES,
            "system.heartbeats." to LiveRefreshTarget.SYSTEM,
        )
        topics.mapNotNull(routes::get).distinct().filter { events.permitted(it, owner.identity) }
            .forEach { refreshes.signal(it, refreshes.currentEpoch) }
    }

    private fun connectionStatus(epoch: Long, status: LiveConnectionStatus) {
        if (!active(epoch)) return
        val unavailable = status == LiveConnectionStatus.RECONNECTING || status == LiveConnectionStatus.FAILED
        view = view.copy(status = status, acceptedTopics = if (unavailable) emptySet() else view.acceptedTopics)
        emit()
        if (unavailable && !fallbackIssued && marketVisible) {
            if (pendingSnapshot == null) beginSnapshot() else fallbackSnapshot()
        }
    }

    private fun receive(epoch: Long, refreshEpoch: Long, bytes: ByteArray) {
        if (!active(epoch)) return
        val owner = currentBinding() ?: return
        val selection = view.market.selection
        if (marketVisible && selection != null) {
            decoder.decode(bytes, selection, owner.clock.instant())?.let { frame ->
                view = view.copy(market = LiveMarketReducer.receive(view.market, view.market.generation, frame))
                emit()
                return
            }
        }
        events.route(bytes, owner.identity, owner.clock.instant())?.let { refreshes.signal(it, refreshEpoch) }
    }
}
