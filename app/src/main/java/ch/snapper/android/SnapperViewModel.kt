package ch.snapper.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snapper.android.data.OriginPolicy
import ch.snapper.android.data.SessionClient
import ch.snapper.android.data.SessionExpiredException
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.OrderScope
import ch.snapper.android.data.OrderScopeChangedException
import ch.snapper.android.data.OrderRejectedException
import ch.snapper.android.data.OrderOutcomeUnknownException
import ch.snapper.android.data.WalletDto
import ch.snapper.android.data.LiveSelection
import ch.snapper.android.data.LiveSessionClient
import ch.snapper.android.data.LiveTransportClient
import ch.snapper.android.features.live.LiveConnectionStatus
import ch.snapper.android.features.live.LiveRefreshTarget
import java.time.Clock
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import ch.snapper.android.data.AccountLanguageBinding
import ch.snapper.android.data.AccountLanguageIdentity
import ch.snapper.android.data.AccountLanguageClient
import ch.snapper.android.features.accountlanguage.*
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class SnapperViewModel(
    private val settings: SettingsStore,
    private val sessionFactory: (String) -> SessionClient,
    private val defaultOrigin: String,
    private val allowLocalHttp: Boolean,
    private val deviceLanguages: () -> List<Locale> = { listOf(Locale.getDefault()) },
) : ViewModel() {
    private val languagePreferenceMutex = kotlinx.coroutines.sync.Mutex()
    private val displayPreferenceMutex = kotlinx.coroutines.sync.Mutex()
    private val mutableState = MutableStateFlow(AppUiState(accountLanguageReady = false))
    val state = mutableState.asStateFlow()
    private var session: SessionClient? = null
    private var sessionObserver: Job? = null
    private var operation: Job? = null
    private var marketRequest: Job? = null
    private var revision = 0L
    private var sessionEpoch = 0L
    private var marketRevision = 0L
    private var orderRevision = 0L
    private var catalogRevision = 0L
    private var catalogRequest: Job? = null
    private var orderMutation: Job? = null
    private val features = FeatureCoordinator(viewModelScope, { session }, { state.value }, { change -> mutableState.update(change) })
    private val privileged = ActionsCoordinator(viewModelScope, { session }, { state.value },
        { change -> mutableState.update(change) }, ::refresh)
    private val live = LiveCoordinator(viewModelScope, ::liveBinding, ::requestLiveSnapshot, ::refreshLiveFeature, ::publishLive)

    private val accountLanguage = AccountLanguageController(viewModelScope, ::languageBinding, ::languageAccepted)

    private var languageDescriptionRevision = 0L
    private var languageDescriptionJob: Job? = null
    private var languageSession: SessionClient? = null
    private var cachedLanguageBinding: AccountLanguageBinding? = null

    private fun languageBinding(): AccountLanguageBinding? {
        val client = session as? AccountLanguageClient ?: return null
        val profile = session?.user?.value ?: return null
        if (!sameScope(profile, state.value.user)) return null
        val identity = AccountLanguageIdentity(state.value.backendUrl, sessionEpoch, OrderScope.from(profile))
        if (languageSession !== session || cachedLanguageBinding?.identity != identity) {
            languageSession = session
            cachedLanguageBinding = client.bindLanguage(identity)
        }
        return cachedLanguageBinding
    }

    private fun invalidateLanguage() {
        languageDescriptionRevision++
        languageDescriptionJob?.cancel()
        accountLanguage.invalidate()
        languageSession = null
        cachedLanguageBinding = null
        mutableState.update { it.copy(accountLanguageReady = false, accountLanguage = AccountLanguageState()) }
    }

    private suspend fun synchronizeLanguage(): Boolean {
        val client = session ?: return false
        val profile = state.value.user ?: return false
        val epoch = sessionEpoch
        val binding = languageBinding()
        if (binding != null) {
            AccountLanguageResolver.resolve(state.value.language, deviceLanguages())?.let(accountLanguage::choose)
            accountLanguage.awaitSettled()
            if (languageBinding() != binding) return false
        }
        if (session !== client || sessionEpoch != epoch || !sameScope(profile, state.value.user) ||
            !sameScope(profile, client.user.value)) return false
        mutableState.update { it.copy(accountLanguageReady = true) }
        live.synchronizeBinding()
        return true
    }

    private fun languageAccepted(identity: AccountLanguageIdentity, language: String) {
        if (languageBinding()?.identity != identity || !state.value.accountLanguageReady ||
            accountLanguage.state.value.desired != language) return
        queueDescriptionRefresh()
    }

    private fun queueDescriptionRefresh() {
        val capturedClient = session ?: return
        val capturedEpoch = sessionEpoch
        val captured = languageBinding()
        val desired = accountLanguage.state.value.desired
        val request = languageDescriptionRevision
        languageDescriptionJob?.cancel()
        languageDescriptionJob = viewModelScope.launch {
            state.first { !it.isLoading && !it.isRestoring }
            if (session !== capturedClient || sessionEpoch != capturedEpoch || languageBinding() != captured ||
                request != languageDescriptionRevision ||
                accountLanguage.state.value.desired != desired || !state.value.accountLanguageReady) return@launch
            features.marketContextActions.retryRelated()
            refresh()
        }
    }

    private fun chooseAccountLanguage(language: String) {
        if (accountLanguage.state.value.desired == language) return
        languageDescriptionRevision++
        languageDescriptionJob?.cancel()
        accountLanguage.choose(language)
    }

    val actions = AppActions(
        login = ::login,
        refresh = ::refresh,
        selectWallet = ::selectWallet,
        logout = ::logout,
        saveBackend = ::saveBackend,
        selectLanguage = ::selectLanguage,
        retryAccountLanguage = accountLanguage::retry,
        clearError = { mutableState.update { it.copy(error = null) } },
        loadMarket = ::loadMarket,
        loadCatalog = features::loadCatalog,
        marketCatalog = features.catalogActions,
        desks = features.deskActions,
        system = features.systemActions,
        records = features.recordsActions,
        notificationDefaults = features.notificationActions,
        signals = features.signalActions,
        pnl = features.pnlActions,
        refreshVenueAccounts = features::refreshAccounts,
        venueAccountsVisible = features::setAccountsVisible,
        processes = privileged.processActions,
        aiReviews = privileged.aiActions,
        positionActions = privileged.positionActions,
        loadProcesses = privileged::loadProcesses,
        loadAiReviews = privileged::loadAiReviews,
        openPositionAction = privileged::openPosition,
        selectCountry = ::selectCountry,
        selectFinancialColor = ::selectFinancialColor,
        marketContext = features.marketContextActions,
        marketDiagnostics = features.marketDiagnosticsActions,
        marketVisible = ::setMarketVisible,
        selectMarket = ::selectMarket,
        orders = OrderActions(
            open = ::openOrder, dismiss = ::dismissOrder, updateDraft = ::updateOrderDraft,
            selectExchange = ::selectOrderExchange, review = ::reviewOrder, back = ::editOrder,
            confirm = { submitOrder(false) }, retry = { submitOrder(true) },
            requestCancel = ::requestOrderCancel, dismissCancel = ::dismissOrder,
            confirmCancel = ::confirmOrderCancel, clearResult = ::dismissOrder,
            checkStatus = ::refresh,
            retryCatalog = ::retryOrderCatalog,
        ),
    )

    init {
        viewModelScope.launch { accountLanguage.state.collect { value -> mutableState.update { it.copy(accountLanguage = value) } } }
        runOperation { request ->
            val preferences = settings.preferences.first()
            if (request != revision) return@runOperation
            val origin = preferences.backendUrl ?: defaultOrigin
            mutableState.update { it.copy(backendUrl = origin, language = preferences.language, displayPreferences = preferences.displayPreferences) }
            if (origin.isNotBlank()) {
                val client = currentSession()
                val user = client.restore()
                if (user != null && request == revision && session === client) {
                    mutableState.update { it.copy(user = user) }
                    live.synchronizeBinding()
                    if (synchronizeLanguage() && request == revision && session === client) refreshSnapshot(client, request)
                }
            }
        }
    }

    fun onForeground() {
        if (state.value.user != null && state.value.language.isEmpty()) {
            AccountLanguageResolver.resolve("", deviceLanguages())?.let(::chooseAccountLanguage)
        }
        features.setForeground(true)
        live.setForeground(true)
        if (state.value.user != null && !state.value.isLoading && !state.value.isRestoring) refresh()
    }

    fun onBackground() {
        features.setForeground(false)
        live.setForeground(false)
        marketRequest?.cancel()
        marketRevision++
    }

    private fun currentSession(): SessionClient {
        session?.let { return it }
        val client = sessionFactory(state.value.backendUrl)
        session = client
        sessionObserver = viewModelScope.launch {
            client.user.collect { profile ->
                if (session === client && profile == null && state.value.user != null) {
                    invalidateLanguage()
                    features.invalidate()
                    privileged.invalidate()
                    live.invalidate()
                    resetOrderFlow()
                    sessionEpoch++
                    marketRevision++
                    marketRequest?.cancel()
                    mutableState.update { it.copy(user = null, snapshot = null, market = MarketState()) }
                } else if (session === client && profile != null && state.value.user != null) {
                    if (!sameScope(profile, state.value.user)) {
                        invalidateLanguage()
                        features.invalidate()
                        privileged.invalidate()
                        live.invalidate()
                        resetOrderFlow()
                        sessionEpoch++
                        marketRevision++
                        marketRequest?.cancel()
                        mutableState.update { it.copy(user = profile, snapshot = null, market = MarketState()) }
                        live.synchronizeBinding()
                        val languageEpoch = sessionEpoch
                        viewModelScope.launch {
                            val settled = synchronizeLanguage()
                            if (settled && session === client && sessionEpoch == languageEpoch && state.value.snapshot == null &&
                                state.value.accountLanguageReady && languageBinding() != null) queueDescriptionRefresh()
                        }
                    } else {
                        mutableState.update { it.copy(user = profile) }
                        live.synchronizeBinding()
                    }
                }
            }
        }
        return client
    }

    fun login(username: String, password: String) {
        if (state.value.isLoading || username.isBlank() || password.isEmpty()) return
        runOperation(isLogin = true) { request ->
            val client = currentSession()
            val epoch = sessionEpoch
            val user = client.login(username.trim(), password)
            if (request != revision || session !== client || epoch != sessionEpoch) return@runOperation
            invalidateLanguage()
            mutableState.update { it.copy(user = user, snapshot = null) }
            live.synchronizeBinding()
            if (synchronizeLanguage() && request == revision && session === client) refreshSnapshot(client, request)
        }
    }

    fun refresh() {
        if (state.value.isLoading || state.value.user == null || !state.value.accountLanguageReady) return
        runOperation { request -> refreshSnapshot(currentSession(), request) }
    }

    fun selectWallet(publicId: String?) {
        if (state.value.isLoading || state.value.user == null) return
        resetOrderFlow()
        invalidateLanguage()
        features.invalidate()
        privileged.invalidate()
        live.invalidate()
        marketRequest?.cancel()
        marketRevision++
        sessionEpoch++
        mutableState.update { it.copy(snapshot = null, market = MarketState()) }
        runOperation { request ->
            val client = currentSession()
            val profile = selectWalletProfile(client, publicId, request, sessionEpoch)
            if (request != revision || session !== client) return@runOperation
            mutableState.update { it.copy(user = profile) }
            live.synchronizeBinding()
            if (synchronizeLanguage() && request == revision && session === client) refreshSnapshot(client, request)
        }
    }

    private suspend fun selectWalletProfile(client: SessionClient, publicId: String?, request: Long, epoch: Long): UserDto {
        val previous = state.value.user
        try {
            return client.selectWallet(publicId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (request == revision && session === client && sessionEpoch == epoch && previous != null &&
                sameScope(previous, state.value.user) && sameScope(previous, client.user.value)) {
                synchronizeLanguage()
            }
            throw error
        }
    }

    private suspend fun refreshSnapshot(client: SessionClient, request: Long) {
        val snapshot = client.snapshot()
        if (session === client && request == revision && sameScope(snapshot.user, client.user.value)) {
            mutableState.update { it.copy(user = snapshot.user, snapshot = snapshot, orders = it.orders.copy(snapshotRefreshFailed = false)) }
        }
    }

    private fun sameScope(first: UserDto, second: UserDto?): Boolean = second != null &&
        first.username == second.username && first.publicId == second.publicId &&
        first.primaryOperatorPublicId == second.primaryOperatorPublicId &&
        first.delegatePublicId == second.delegatePublicId &&
        first.activeWalletPublicId == second.activeWalletPublicId &&
        first.effectivePermissions.orEmpty().toSet() == second.effectivePermissions.orEmpty().toSet()

    fun logout() {
        clearVisibleSession()
        runOperation { retireSession() }
    }

    fun saveBackend(rawOrigin: String) {
        if (state.value.isLoading) return
        val canonical = try {
            OriginPolicy.normalize(rawOrigin, allowLocalHttp)
        } catch (_: IllegalArgumentException) {
            mutableState.update { it.copy(error = UiError.INVALID_ORIGIN) }
            return
        }
        if (canonical == state.value.backendUrl) return
        clearVisibleSession()
        runOperation { request ->
            retireSession()
            settings.setBackendUrl(canonical)
            if (request == revision) mutableState.update { it.copy(backendUrl = canonical) }
        }
    }

    private fun clearVisibleSession() {
        invalidateLanguage()
        features.invalidate()
        privileged.invalidate()
        live.invalidate()
        resetOrderFlow()
        revision++
        sessionEpoch++
        marketRevision++
        operation?.cancel()
        marketRequest?.cancel()
        sessionObserver?.cancel()
        mutableState.update {
            it.copy(user = null, snapshot = null, market = MarketState(), error = null)
        }
    }

    private suspend fun retireSession() {
        val old = session
        session = null
        try {
            withTimeoutOrNull(3_000) { old?.logout() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            // Local credentials are cleared even when remote revocation is unavailable.
        } catch (_: HttpException) {
            // Server rejection must not preserve local credentials.
        } finally {
            old?.close()
        }
    }

    fun selectLanguage(language: String) {
        if (language.isNotEmpty() && ch.snapper.android.data.SupportedLanguages.find(language) == null) return
        viewModelScope.launch {
            languagePreferenceMutex.withLock {
                try {
                    settings.setLanguage(language)
                    mutableState.update { it.copy(language = language) }
                    AccountLanguageResolver.resolve(language, deviceLanguages())?.let(::chooseAccountLanguage)
                } catch (_: IOException) {
                    mutableState.update { it.copy(error = UiError.SERVICE) }
                }
            }
        }
    }

    fun selectCountry(country: String) {
        if (country.isNotEmpty() && ch.snapper.android.data.DisplayCountries.find(country) == null) return
        updateDisplayPreferences { it.copy(countryCode = country) }
    }

    fun selectFinancialColor(preference: ch.snapper.android.data.FinancialColorPreference) {
        updateDisplayPreferences { it.copy(financialColor = preference) }
    }

    private fun updateDisplayPreferences(transform: (ch.snapper.android.data.DisplayPreferences) -> ch.snapper.android.data.DisplayPreferences) {
        viewModelScope.launch {
            displayPreferenceMutex.lock()
            try {
                val committed = settings.updateDisplayPreferences(transform)
                mutableState.update { it.copy(displayPreferences = committed) }
            } catch (_: IOException) {
                mutableState.update { it.copy(error = UiError.SERVICE) }
            } finally {
                displayPreferenceMutex.unlock()
            }
        }
    }

    private fun resetOrderFlow() {
        orderRevision++
        catalogRevision++
        catalogRequest?.cancel()
        orderMutation?.cancel()
        mutableState.update { it.copy(orders = OrderFlowState()) }
    }

    private fun dismissOrder() {
        if (state.value.orders.isSubmitting) return
        if (state.value.orders.outcome == OrderOutcome.UNKNOWN) updateOrders { it.copy(isDismissed = true) }
        else resetOrderFlow()
    }

    private fun orderWallet(): WalletDto? = state.value.snapshot?.wallets
        ?.firstOrNull { it.publicId == state.value.user?.activeWalletPublicId }

    private fun orderScope(): OrderScope? = state.value.user?.let(OrderScope::from)

    private fun orderCurrent(client: SessionClient, scope: OrderScope, request: Long): Boolean =
        session === client && request == orderRevision && orderScope() == scope &&
            client.user.value?.let(OrderScope::from) == scope

    private fun updateOrders(change: (OrderFlowState) -> OrderFlowState) {
        mutableState.update { it.copy(orders = change(it.orders)) }
    }

    fun openOrder() {
        val client = session ?: return
        val scope = orderScope() ?: return
        if (orderWallet() == null || state.value.isLoading || state.value.orders.isSubmitting ||
            !scope.effectivePermissions.containsAll(listOf("create:orders", "read:market_data"))) return
        if (state.value.orders.outcome == OrderOutcome.UNKNOWN) {
            updateOrders { it.copy(isDismissed = false) }
            return
        }
        resetOrderFlow()
        val request = orderRevision
        val catalog = ++catalogRevision
        updateOrders { it.copy(draft = OrderDraft(), loadingCatalog = true) }
        catalogRequest = viewModelScope.launch {
            try {
                val exchanges = client.orderExchanges(scope)
                if (!orderCurrent(client, scope, request) || catalog != catalogRevision) return@launch
                updateOrders { it.copy(exchanges = exchanges, loadingCatalog = false, issue = null) }
                exchanges.firstOrNull()?.let(::selectOrderExchange)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (orderCurrent(client, scope, request) && catalog == catalogRevision) {
                    updateOrders { it.copy(loadingCatalog = false, issue = OrderIssue.CATALOG) }
                }
            }
        }
    }

    fun selectOrderExchange(exchange: String) {
        val client = session ?: return
        val scope = orderScope() ?: return
        val flow = state.value.orders
        val draft = flow.draft ?: return
        if (flow.isSubmitting || flow.review != null || flow.outcome == OrderOutcome.UNKNOWN || exchange !in flow.exchanges) return
        catalogRequest?.cancel()
        val catalog = ++catalogRevision
        val request = orderRevision
        updateOrders { it.copy(draft = draft.copy(exchange = exchange, instrumentPublicId = ""), instruments = emptyList(), loadingCatalog = true, issue = null) }
        catalogRequest = viewModelScope.launch {
            try {
                val instruments = client.orderInstruments(scope, exchange)
                if (orderCurrent(client, scope, request) && catalog == catalogRevision) {
                    updateOrders { it.copy(instruments = instruments, loadingCatalog = false, issue = null) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (orderCurrent(client, scope, request) && catalog == catalogRevision) {
                    updateOrders { it.copy(loadingCatalog = false, issue = OrderIssue.CATALOG) }
                }
            }
        }
    }

    fun retryOrderCatalog() {
        val draft = state.value.orders.draft ?: return
        if (draft.exchange.isEmpty()) openOrder() else selectOrderExchange(draft.exchange)
    }

    fun updateOrderDraft(draft: OrderDraft) {
        val flow = state.value.orders
        if (flow.draft == null || flow.review != null || flow.isSubmitting || flow.outcome == OrderOutcome.UNKNOWN ||
            draft.exchange != flow.draft.exchange) return
        updateOrders { it.copy(draft = draft, issue = null) }
    }

    fun reviewOrder() {
        val flow = state.value.orders
        val wallet = orderWallet() ?: return
        val scope = orderScope() ?: return
        val draft = flow.draft ?: return
        if (!flow.canReview || "create:orders" !in scope.effectivePermissions) return
        val instrument = flow.instruments.firstOrNull { it.instrumentPublicId == draft.instrumentPublicId }
        val body = OrderForm.build(draft, instrument, wallet, UUID.randomUUID().toString()) ?: return
        updateOrders { it.copy(review = OrderReview(body, wallet.label, scope), issue = null) }
    }

    fun editOrder() {
        if (state.value.orders.isSubmitting || state.value.orders.outcome == OrderOutcome.UNKNOWN) return
        updateOrders { it.copy(review = null, issue = null) }
    }

    fun submitOrder(retry: Boolean = false) {
        val client = session ?: return
        val flow = state.value.orders
        val review = flow.review ?: return
        if (flow.isSubmitting || (flow.outcome == OrderOutcome.UNKNOWN) != retry) return
        val request = orderRevision
        if (!orderCurrent(client, review.scope, request)) { resetOrderFlow(); return }
        updateOrders { it.copy(isSubmitting = true, issue = null, isDismissed = false) }
        orderMutation = viewModelScope.launch {
            try {
                client.createOrder(review.scope, review.body)
                orderAccepted(client, review.scope, request, OrderOutcome.CREATED)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                orderFailed(client, review.scope, request, error)
            }
        }
    }

    fun requestOrderCancel(order: OrderDto) {
        val flow = state.value.orders
        val scope = orderScope() ?: return
        if (flow.isSubmitting || flow.draft != null || flow.outcome == OrderOutcome.UNKNOWN || state.value.isLoading ||
            !OrderForm.canCancel(order, state.value.user) || order !in state.value.snapshot?.orders.orEmpty()) return
        resetOrderFlow()
        updateOrders { it.copy(pendingCancel = order, cancelScope = scope) }
    }

    fun confirmOrderCancel() {
        val client = session ?: return
        val flow = state.value.orders
        val order = flow.pendingCancel ?: return
        val scope = flow.cancelScope ?: return
        if (flow.isSubmitting || flow.outcome == OrderOutcome.UNKNOWN) return
        val request = orderRevision
        if (!orderCurrent(client, scope, request) || !OrderForm.canCancel(order, state.value.user) ||
            order !in state.value.snapshot?.orders.orEmpty()) { resetOrderFlow(); return }
        updateOrders { it.copy(isSubmitting = true, outcome = null, issue = null) }
        orderMutation = viewModelScope.launch {
            try {
                client.cancelOrder(scope, order)
                orderAccepted(client, scope, request, OrderOutcome.CANCEL_REQUESTED)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                orderFailed(client, scope, request, error)
            }
        }
    }

    private suspend fun orderAccepted(client: SessionClient, scope: OrderScope, request: Long, outcome: OrderOutcome) {
        if (!orderCurrent(client, scope, request)) return
        updateOrders { OrderFlowState(outcome = outcome) }
        try {
            val snapshot = client.snapshot()
            if (orderCurrent(client, scope, request) && OrderScope.from(snapshot.user) == scope) {
                mutableState.update { it.copy(snapshot = snapshot) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (orderCurrent(client, scope, request)) updateOrders { it.copy(snapshotRefreshFailed = true) }
        }
    }

    private fun orderFailed(client: SessionClient, scope: OrderScope, request: Long, error: Exception) {
        if (!orderCurrent(client, scope, request)) return
        val issue = when (error) {
            is OrderScopeChangedException, is SessionExpiredException -> OrderIssue.SESSION_CHANGED
            is OrderRejectedException -> orderRejection(error)
            is IllegalArgumentException -> OrderIssue.INVALID_FORM
            is HttpException -> if (error.code() == 403) OrderIssue.FORBIDDEN else OrderIssue.REJECTED
            else -> null
        }
        if (issue == OrderIssue.SESSION_CHANGED) {
            resetOrderFlow()
            updateOrders { it.copy(issue = issue) }
        } else {
            val unknown = error is OrderOutcomeUnknownException || issue == null
            updateOrders {
                it.copy(isSubmitting = false, issue = issue,
                    outcome = if (unknown || it.outcome == OrderOutcome.UNKNOWN) OrderOutcome.UNKNOWN else null)
            }
        }
    }

    private fun orderRejection(error: OrderRejectedException): OrderIssue? = when {
        error.statusCode == 409 -> null
        error.statusCode == 401 -> OrderIssue.SESSION_CHANGED
        error.statusCode == 403 -> OrderIssue.FORBIDDEN
        error.errorCode == "unknown_instrument" -> OrderIssue.UNKNOWN_INSTRUMENT
        error.errorCode == "instrument_market_data_only" -> OrderIssue.OBSERVATION_ONLY
        error.errorCode == "caps_violation" -> OrderIssue.CAPS_VIOLATION
        else -> OrderIssue.REJECTED
    }

    private fun liveBinding(): LiveBinding? {
        if (!state.value.accountLanguageReady) return null
        val client = session ?: return null
        val profile = state.value.user ?: return null
        val current = client.user.value ?: return null
        if (!sameScope(profile, current)) return null
        val tickets = client as? LiveSessionClient ?: return null
        val transport = client as? LiveTransportClient ?: return null
        val identity = LiveIdentity(state.value.backendUrl, OrderScope.from(profile), profile.primaryOperatorPublicId,
            profile.delegatePublicId, sessionEpoch)
        return LiveBinding(identity, tickets, transport.liveTransport(), Clock.systemUTC())
    }

    fun setMarketVisible(visible: Boolean) {
        live.setMarketVisible(visible)
        if (!visible) { marketRequest?.cancel(); marketRevision++ }
    }

    private fun publishLive(value: LiveViewState) {
        mutableState.update { previous ->
            val selection = value.market.selection
            val market = if (selection == null) previous.market.copy(liveStatus = value.status) else MarketState(
                exchange = selection.exchange, instrument = selection.instrument, timeframe = selection.timeframe,
                candles = value.market.candles, isLoading = value.market.loading,
                error = if (value.market.snapshotFailed) UiError.NETWORK else null,
                liveStatus = if (value.status == LiveConnectionStatus.SUBSCRIBED && selection.candleTopic !in value.acceptedTopics)
                    LiveConnectionStatus.STOPPED else value.status,
                tick = value.market.tick,
            )
            previous.copy(market = market)
        }
    }

    private suspend fun refreshLiveFeature(target: LiveRefreshTarget) {
        when (target) {
            LiveRefreshTarget.ORDERS -> {
                val identity = liveBinding()?.identity ?: return
                operation?.join()
                if (liveBinding()?.identity != identity) return
                refresh()
                operation?.join()
            }
            LiveRefreshTarget.AI_REVIEWS, LiveRefreshTarget.PROCESSES -> privileged.refreshLive(target)
            else -> features.refreshLive(target)
        }
    }

    fun selectMarket(exchange: String, instrument: String, timeframe: String) = selectMarket(exchange, instrument, timeframe, false)
    fun loadMarket(exchange: String, instrument: String, timeframe: String) = selectMarket(exchange, instrument, timeframe, true)

    private fun selectMarket(exchange: String, instrument: String, timeframe: String, force: Boolean) {
        if (state.value.user?.hasPermission("read:market_data") != true) return
        val selection = LiveSelection(exchange.trim(), instrument.trim(), timeframe.trim())
        features.prepareMarketDiagnostics(selection.exchange, selection.instrument, selection.timeframe)
        if (state.value.accountLanguageReady) features.selectMarketContext(selection.exchange, selection.instrument)
        if (liveBinding() != null) live.selectMarket(selection, force)
        else {
            val previous = state.value.market
            if (force || previous.exchange != selection.exchange || previous.instrument != selection.instrument ||
                previous.timeframe != selection.timeframe || previous.candles.isEmpty()) {
                loadMarketSnapshot(selection, null)
            }
        }
    }

    private fun requestLiveSnapshot(ticket: MarketLoadTicket) {
        if (liveBinding()?.identity == ticket.identity) loadMarketSnapshot(ticket.selection, ticket)
    }

    private fun loadMarketSnapshot(selection: LiveSelection, ticket: MarketLoadTicket?) {
        val client = session ?: return
        features.probeMarketDiagnostics(selection.exchange, selection.instrument, selection.timeframe)
        marketRequest?.cancel()
        val request = ++marketRevision
        val epoch = sessionEpoch
        val query = MarketState(selection.exchange, selection.instrument, selection.timeframe, isLoading = true)
        if (ticket == null) mutableState.update { it.copy(market = query) }
        marketRequest = viewModelScope.launch { fetchMarketSnapshot(client, epoch, request, query, ticket) }
    }

    private fun marketSnapshotCurrent(client: SessionClient, epoch: Long, request: Long): Boolean =
        session === client && epoch == sessionEpoch && request == marketRevision

    private suspend fun fetchMarketSnapshot(
        client: SessionClient, epoch: Long, request: Long, query: MarketState, ticket: MarketLoadTicket?,
    ) {
        try {
            val candles = client.candles(query.exchange, query.instrument, query.timeframe)
            if (!marketSnapshotCurrent(client, epoch, request)) return
            if (ticket != null) live.snapshotCompleted(ticket, candles)
            else mutableState.update { it.copy(market = query.copy(candles = candles, isLoading = false)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!marketSnapshotCurrent(client, epoch, request)) return
            if (ticket != null) live.snapshotFailed(ticket)
            else mutableState.update { it.copy(market = query.copy(isLoading = false, error = classify(error))) }
        }
    }

    private fun runOperation(isLogin: Boolean = false, action: suspend (Long) -> Unit) {
        operation?.cancel()
        val request = ++revision
        mutableState.update { it.copy(isLoading = true, error = null) }
        operation = viewModelScope.launch {
            try {
                action(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == revision) {
                    mutableState.update { it.copy(error = classify(error, isLogin)) }
                }
            } finally {
                if (request == revision) {
                    mutableState.update { it.copy(isLoading = false, isRestoring = false) }
                }
            }
        }
    }

    private fun classify(error: Exception, isLogin: Boolean = false): UiError = when {
        error is SessionExpiredException -> UiError.SESSION_EXPIRED
        error is HttpException && error.code() == 401 -> if (isLogin) UiError.INVALID_CREDENTIALS else UiError.SESSION_EXPIRED
        error is HttpException -> UiError.SERVICE
        error is IllegalArgumentException -> UiError.INVALID_ORIGIN
        error is IOException -> UiError.NETWORK
        else -> UiError.UNKNOWN
    }

    override fun onCleared() {
        live.invalidate()
        sessionObserver?.cancel()
        session?.close()
        session = null
    }
}
