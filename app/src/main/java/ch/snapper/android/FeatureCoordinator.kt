package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.desks.*
import ch.snapper.android.features.market.*
import ch.snapper.android.features.system.*
import ch.snapper.android.features.records.*
import ch.snapper.android.features.notifications.*
import ch.snapper.android.features.signals.*
import ch.snapper.android.features.accounts.*
import ch.snapper.android.features.pnl.*
import ch.snapper.android.features.marketcontext.*
import ch.snapper.android.features.live.LiveRefreshTarget
import java.time.Clock
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
    private data class Context(val client: SessionClient, val scope: OrderScope, val primary: String?, val delegate: String?, val epoch: Long)
    private var marketContextController: MarketContextController? = null
    private var marketContextScope: Context? = null
    private var marketContextSelection: MarketContextSelection? = null
    private var marketContextObserver: Job? = null
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
    private var signalController: SignalsController? = null
    private var signalContext: Context? = null
    private var signalObserver: Job? = null
    private var pnlController: PnlController? = null
    private var pnlContext: Context? = null
    private var pnlWallet: WalletDto? = null
    private var pnlObserver: Job? = null
    private var accountsController: VenueAccountsController? = null
    private var accountsContext: Context? = null
    private var accountsObserver: Job? = null
    private var accountsVisible = false
    private var foreground = false

    val catalogActions = MarketCatalogActions(
        selectExchange = ::selectExchange,
        selectInstrument = ::selectInstrument,
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
    val signalActions = SignalsActions(
        refresh = { signals()?.load() },
        selectStrategy = { value -> signals()?.actions?.selectStrategy?.invoke(value) },
    )
    val pnlActions = PnlActions(
        refresh = { pnl()?.load() },
        window = { value -> pnl()?.actions?.window?.invoke(value) },
        granularity = { value -> pnl()?.actions?.granularity?.invoke(value) },
        currency = { value -> pnl()?.actions?.currency?.invoke(value) },
    )

    private fun context(): Context? {
        val client = session() ?: return null
        val user = state().user ?: return null
        val scope = OrderScope.from(user)
        if (client.user.value?.let(OrderScope::from) != scope) return null
        val current = client.user.value ?: return null
        if (current.primaryOperatorPublicId != user.primaryOperatorPublicId || current.delegatePublicId != user.delegatePublicId) return null
        return Context(client, scope, user.primaryOperatorPublicId, user.delegatePublicId, epoch)
    }

    private fun current(captured: Context): Boolean = session() === captured.client && context() == captured

    private fun catalogCurrent(context: Context, request: Long): Boolean = current(context) && request == catalogRevision

    fun invalidate() {
        epoch++
        marketContextController?.invalidate()
        marketContextObserver?.cancel()
        marketContextController = null
        marketContextScope = null
        marketContextSelection = null
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
        signalController?.invalidate()
        signalObserver?.cancel()
        signalController = null
        signalContext = null
        pnlController?.invalidate()
        pnlObserver?.cancel()
        pnlController = null
        pnlContext = null
        pnlWallet = null
        accountsController?.invalidate()
        accountsObserver?.cancel()
        accountsController = null
        accountsContext = null
        update { it.copy(marketCatalog = MarketCatalogState(), desks = DeskState(), system = SystemState(),
            records = RecordsState(), notificationDefaults = NotificationDefaultsState(), signals = SignalsState(),
            venueAccounts = VenueAccountsState(), pnl = PnlState(), marketContext = MarketContextState()) }
    }

    val marketContextActions = MarketContextActions(
        { marketContextController?.takeIf { marketContextScope?.let(::current) == true }?.actions?.retryMetrics?.invoke() },
        { marketContextController?.takeIf { marketContextScope?.let(::current) == true }?.actions?.retryRelated?.invoke() },
        { item -> marketContextController?.takeIf { marketContextScope?.let(::current) == true }?.actions?.selectRelated?.invoke(item) },
    )

    fun selectMarketContext(exchange: String, instrument: String) {
        val captured = context() ?: return
        val api = captured.client as? MarketContextClient ?: return
        val catalog = state().marketCatalog
        val selection = MarketContextSelection(exchange, instrument).takeIf {
            catalog.exchange == exchange && catalog.instrument == instrument && catalog.selectedInstrument != null
        }
        if (marketContextScope == captured && marketContextSelection == selection) return
        marketContextController?.invalidate()
        marketContextObserver?.cancel()
        val controller = MarketContextController(coroutineScope, api, { venue, symbol -> selectRelatedMarket(captured, venue, symbol) })
        marketContextController = controller
        marketContextScope = captured
        marketContextSelection = selection
        controller.reset(selection, "read:market_data" in captured.scope.effectivePermissions)
        marketContextObserver = coroutineScope.launch {
            controller.state.collect { value ->
                if (current(captured) && marketContextController === controller) update { it.copy(marketContext = value) }
            }
        }
    }

    private fun selectInstrument(symbol: String) {
        catalogJob?.cancel()
        catalogRevision++
        update { it.copy(marketCatalog = MarketSelection.instrument(it.marketCatalog, symbol).copy(isLoading = false)) }
    }

    private fun selectRelatedMarket(captured: Context, exchange: String, symbol: String) {
        if (!current(captured) || "read:market_data" !in captured.scope.effectivePermissions) return
        val api = captured.client as? MarketCatalogClient ?: return
        catalogJob?.cancel()
        val request = ++catalogRevision
        update { it.copy(marketCatalog = it.marketCatalog.copy(isLoading = true, error = null)) }
        catalogJob = coroutineScope.launch {
            try {
                val exchanges = api.marketExchanges()
                check(exchange in exchanges)
                if (!catalogCurrent(captured, request)) return@launch
                val instruments = api.marketInstruments(exchange)
                check(instruments.any { it.exchange == exchange && it.symbol == symbol && it.canMarketData })
                if (!catalogCurrent(captured, request)) return@launch
                update {
                    val target = it.marketCatalog.copy(exchanges = exchanges, exchange = exchange, instrument = symbol, query = "")
                    it.copy(marketCatalog = MarketSelection.instruments(target, exchange, instruments))
                }
                selectMarketContext(exchange, symbol)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (catalogCurrent(captured, request)) update { it.copy(marketCatalog = it.marketCatalog.copy(isLoading = false, error = classify(error))) }
            }
        }
    }

    suspend fun refreshLive(target: LiveRefreshTarget) {
        val captured = context() ?: return
        when (target) {
            LiveRefreshTarget.SIGNALS -> signals()?.takeIf { current(captured) }?.refreshAwaited { current(captured) }
            LiveRefreshTarget.STRATEGIES -> refreshSystemAwaited(captured, SystemFeed.STRATEGIES)
            LiveRefreshTarget.SYSTEM -> {
                refreshSystemAwaited(captured, SystemFeed.HEALTH)
                refreshSystemAwaited(captured, SystemFeed.STATUS)
            }
            else -> Unit
        }
    }

    private suspend fun refreshSystemAwaited(captured: Context, feed: SystemFeed) {
        systemJobs[feed]?.join()
        if (!current(captured)) return
        refreshSystem(feed)
        systemJobs[feed]?.join()
    }

    private fun signals(): SignalsController? {
        val context = context() ?: return null
        val api = context.client as? SignalsClient ?: return null
        if (signalContext != context) {
            signalController?.invalidate()
            signalObserver?.cancel()
            val controller = SignalsController(coroutineScope, api, state().user ?: return null)
            signalController = controller
            signalContext = context
            signalObserver = coroutineScope.launch {
                controller.state.collect { value -> if (current(context)) update { it.copy(signals = value) } }
            }
        }
        return signalController
    }

    private fun pnl(): PnlController? {
        val context = context() ?: return null
        val api = context.client as? PnlClient ?: return null
        val wallet = state().snapshot?.wallets?.firstOrNull { it.publicId == context.scope.activeWalletPublicId }
        if (pnlContext != context || pnlWallet != wallet) {
            pnlController?.invalidate()
            pnlObserver?.cancel()
            val controller = PnlController(coroutineScope, api)
            controller.reset(wallet, "read:positions" in context.scope.effectivePermissions)
            pnlController = controller
            pnlContext = context
            pnlWallet = wallet
            pnlObserver = coroutineScope.launch {
                controller.state.collect { value -> if (current(context)) update { it.copy(pnl = value) } }
            }
        }
        return pnlController
    }

    fun setForeground(value: Boolean) {
        foreground = value
        activateAccounts()
    }

    fun setAccountsVisible(value: Boolean) {
        accountsVisible = value
        activateAccounts()
    }

    fun refreshAccounts() {
        if (foreground && accountsVisible) accountsController?.refresh()
    }

    private fun activateAccounts() {
        val context = context()
        if (!foreground || !accountsVisible || context == null) {
            accountsController?.invalidate()
            return
        }
        val api = context.client as? VenueAccountsClient ?: return
        if (accountsContext != context) {
            accountsController?.invalidate()
            accountsObserver?.cancel()
            val controller = VenueAccountsController(coroutineScope, api, Clock.systemUTC())
            accountsController = controller
            accountsContext = context
            accountsObserver = coroutineScope.launch {
                controller.state.collect { value -> if (current(context)) update { it.copy(venueAccounts = value) } }
            }
        }
        accountsController?.activate(context.scope.activeWalletPublicId, context.scope.effectivePermissions, foreground, accountsVisible)
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
                if (!catalogCurrent(context, request)) return@launch
                update { it.copy(marketCatalog = MarketSelection.exchanges(it.marketCatalog, exchanges)) }
                val selected = state().marketCatalog.exchange
                val instruments = if (selected.isNotBlank()) api.marketInstruments(selected) else emptyList()
                if (catalogCurrent(context, request)) update {
                    it.copy(marketCatalog = MarketSelection.instruments(it.marketCatalog, selected, instruments))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (catalogCurrent(context, request)) update {
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
                if (catalogCurrent(context, request)) update {
                    it.copy(marketCatalog = MarketSelection.instruments(it.marketCatalog, exchange, instruments))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (catalogCurrent(context, request)) update {
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
