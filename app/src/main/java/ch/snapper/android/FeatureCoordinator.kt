package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.desks.*
import ch.snapper.android.features.market.*
import ch.snapper.android.features.system.*
import ch.snapper.android.features.records.*
import ch.snapper.android.features.notifications.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** One UI scope; all network work remains in the existing authenticated session. */
internal class FeatureCoordinator(
    private val coroutineScope: CoroutineScope,
    private val session: () -> SessionClient?,
    private val state: () -> AppUiState,
    private val update: ((AppUiState) -> AppUiState) -> Unit,
) {
    private data class Context(val client: SessionClient, val scope: OrderScope, val epoch: Long)
    private var epoch = 0L
    private var catalogRevision = 0L
    private var catalogJob: Job? = null
    private var deskController: DeskController? = null
    private var deskContext: Context? = null
    private var deskObserver: Job? = null
    private val systemJobs = mutableMapOf<SystemFeed, Job>()
    private var recordsJob: Job? = null
    private var notificationController: NotificationDefaultsController? = null
    private var notificationContext: Context? = null
    private var notificationObserver: Job? = null

    val catalogActions = MarketCatalogActions(
        selectExchange = ::selectExchange,
        selectInstrument = { value -> update { it.copy(marketCatalog = MarketSelection.instrument(it.marketCatalog, value)) } },
        selectTimeframe = { value -> update { it.copy(marketCatalog = MarketSelection.timeframe(it.marketCatalog, value)) } },
        search = { value -> update { it.copy(marketCatalog = it.marketCatalog.copy(query = value)) } },
        retry = ::loadCatalog,
    )
    val deskActions = DeskActions(
        reload = { controller()?.load() },
        selectDesk = { value -> controller()?.actions?.selectDesk?.invoke(value) },
        setUsername = { value -> controller()?.actions?.setUsername?.invoke(value) },
        attach = { controller()?.actions?.attach?.invoke() },
    )
    val systemActions = SystemActions(::refreshSystem)
    val recordsActions = RecordsActions(
        selectSegment = { segment ->
            update { it.copy(records = it.records.copy(segment = segment)) }
            if (segment == OrderSegment.FILLS) loadExecutions()
        },
        refreshExecutions = ::loadExecutions,
    )
    val notificationActions = NotificationDefaultsActions(
        refresh = { notifications()?.load() },
        setEnabled = { type, enabled -> notifications()?.actions?.setEnabled?.invoke(type, enabled) },
        setPriority = { type, priority -> notifications()?.actions?.setPriority?.invoke(type, priority) },
        save = { type -> notifications()?.actions?.save?.invoke(type) },
    )

    private fun context(): Context? {
        val client = session() ?: return null
        val user = state().user ?: return null
        val scope = OrderScope.from(user)
        if (client.user.value?.let(OrderScope::from) != scope) return null
        return Context(client, scope, epoch)
    }

    private fun current(context: Context): Boolean = context.epoch == epoch && session() === context.client &&
        state().user?.let(OrderScope::from) == context.scope && context.client.user.value?.let(OrderScope::from) == context.scope

    fun invalidate() {
        epoch++
        catalogRevision++
        catalogJob?.cancel()
        deskController?.invalidate()
        deskObserver?.cancel()
        deskController = null
        deskContext = null
        systemJobs.values.forEach(Job::cancel)
        systemJobs.clear()
        recordsJob?.cancel()
        notificationController?.invalidate()
        notificationObserver?.cancel()
        notificationController = null
        notificationContext = null
        update { it.copy(marketCatalog = MarketCatalogState(), desks = DeskState(), system = SystemState(), records = RecordsState(), notificationDefaults = NotificationDefaultsState()) }
    }

    private fun notifications(): NotificationDefaultsController? {
        val context = context() ?: return null
        val api = context.client as? NotificationDefaultsClient ?: return null
        if (notificationContext != context) {
            notificationController?.invalidate()
            notificationObserver?.cancel()
            val controller = NotificationDefaultsController(coroutineScope, api, state().user ?: return null)
            notificationController = controller
            notificationContext = context
            notificationObserver = coroutineScope.launch {
                controller.state.collect { value -> if (current(context)) update { it.copy(notificationDefaults = value) } }
            }
        }
        return notificationController
    }

    private fun loadExecutions() {
        val context = context() ?: return
        val api = context.client as? RecordsClient ?: return
        if ("read:orders" !in context.scope.effectivePermissions || recordsJob?.isActive == true) return
        update { it.copy(records = it.records.copy(isLoading = true, error = null)) }
        recordsJob = coroutineScope.launch {
            try {
                val rows = api.executions(context.scope.activeWalletPublicId)
                if (current(context)) update { it.copy(records = RecordFilters.loaded(it.records, rows)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(context)) update { it.copy(records = RecordFilters.failed(it.records, classify(error))) }
            }
        }
    }

    private fun controller(): DeskController? {
        val context = context() ?: return null
        val api = context.client as? DeskClient ?: return null
        if (deskContext != context) {
            deskController?.invalidate()
            deskObserver?.cancel()
            val profile = state().user ?: return null
            val controller = DeskController(coroutineScope, api, profile, profile.primaryOperatorPublicId)
            deskController = controller
            deskContext = context
            deskObserver = coroutineScope.launch {
                controller.state.collect { value -> if (current(context)) update { it.copy(desks = value) } }
            }
        }
        return deskController
    }

    fun loadCatalog() {
        val context = context() ?: return
        val api = context.client as? MarketCatalogClient ?: return
        if ("read:market_data" !in context.scope.effectivePermissions) return
        catalogJob?.cancel()
        val request = ++catalogRevision
        update { it.copy(marketCatalog = it.marketCatalog.copy(isLoading = true, error = null)) }
        catalogJob = coroutineScope.launch {
            try {
                val exchanges = api.marketExchanges()
                if (!current(context) || request != catalogRevision) return@launch
                update { it.copy(marketCatalog = MarketSelection.exchanges(it.marketCatalog, exchanges)) }
                val selected = state().marketCatalog.exchange
                val instruments = if (selected.isNotBlank()) api.marketInstruments(selected) else emptyList()
                if (current(context) && request == catalogRevision) update {
                    it.copy(marketCatalog = MarketSelection.instruments(it.marketCatalog, selected, instruments))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(context) && request == catalogRevision) update {
                    it.copy(marketCatalog = it.marketCatalog.copy(isLoading = false, error = classify(error)))
                }
            }
        }
    }

    private fun selectExchange(exchange: String) {
        val context = context() ?: return
        val api = context.client as? MarketCatalogClient ?: return
        if ("read:market_data" !in context.scope.effectivePermissions || exchange !in state().marketCatalog.exchanges) return
        catalogJob?.cancel()
        val request = ++catalogRevision
        update { it.copy(marketCatalog = MarketSelection.exchange(it.marketCatalog, exchange).copy(isLoading = true)) }
        catalogJob = coroutineScope.launch {
            try {
                val instruments = api.marketInstruments(exchange)
                if (current(context) && request == catalogRevision) update {
                    it.copy(marketCatalog = MarketSelection.instruments(it.marketCatalog, exchange, instruments))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(context) && request == catalogRevision) update {
                    it.copy(marketCatalog = it.marketCatalog.copy(isLoading = false, error = classify(error)))
                }
            }
        }
    }

    private fun refreshSystem(feed: SystemFeed) {
        val context = context() ?: return
        val api = context.client as? SystemClient ?: return
        if (!feed.isAllowed(context.scope.effectivePermissions) || systemJobs[feed]?.isActive == true) return
        update { it.copy(system = it.system.copy(permissions = context.scope.effectivePermissions)) }
        systemJobs[feed] = coroutineScope.launch {
            when (feed) {
                SystemFeed.HEALTH -> loadFeed(context, state().system.health, { api.systemHealth() }) { value -> copy(health = value) }
                SystemFeed.STATUS -> loadFeed(context, state().system.status, { api.systemStatus() }) { value -> copy(status = value) }
                SystemFeed.STRATEGIES -> loadFeed(context, state().system.strategies, { api.systemStrategies() }) { value -> copy(strategies = value) }
                SystemFeed.BACKTESTS -> loadFeed(context, state().system.backtests, { api.systemBacktests() }) { value -> copy(backtests = value) }
                SystemFeed.USERS -> loadFeed(context, state().system.users, { api.systemUsers() }) { value -> copy(users = value) }
                SystemFeed.DELEGATES -> loadFeed(context, state().system.delegates, { api.systemDelegates() }) { value -> copy(delegates = value) }
            }
        }
    }

    private suspend fun <T> loadFeed(
        context: Context, previous: SystemFeedState<T>, load: suspend () -> T,
        change: SystemState.(SystemFeedState<T>) -> SystemState,
    ) {
        if (current(context)) update { it.copy(system = it.system.change(previous.copy(isLoading = true, error = null))) }
        try {
            val value = load()
            if (current(context)) update { it.copy(system = it.system.change(SystemFeedState(value))) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (current(context)) update { it.copy(system = it.system.change(previous.copy(error = classify(error)))) }
        }
    }

    private fun classify(error: Exception): UiError = when (error) {
        is SessionExpiredException -> UiError.SESSION_EXPIRED
        is HttpException -> UiError.SERVICE
        is IOException -> UiError.NETWORK
        else -> UiError.UNKNOWN
    }
}
