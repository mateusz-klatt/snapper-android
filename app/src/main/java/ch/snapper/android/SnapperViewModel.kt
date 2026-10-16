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

class SnapperViewModel(
    private val settings: SettingsStore,
    private val sessionFactory: (String) -> SessionClient,
    private val defaultOrigin: String,
    private val allowLocalHttp: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
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

    val actions = AppActions(
        login = ::login,
        refresh = ::refresh,
        selectWallet = ::selectWallet,
        logout = ::logout,
        saveBackend = ::saveBackend,
        selectLanguage = ::selectLanguage,
        clearError = { mutableState.update { it.copy(error = null) } },
        loadMarket = ::loadMarket,
        loadCatalog = features::loadCatalog,
        marketCatalog = features.catalogActions,
        desks = features.deskActions,
        system = features.systemActions,
        records = features.recordsActions,
        notificationDefaults = features.notificationActions,
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
        runOperation { request ->
            val preferences = settings.preferences.first()
            if (request != revision) return@runOperation
            val origin = preferences.backendUrl ?: defaultOrigin
            mutableState.update { it.copy(backendUrl = origin, language = preferences.language) }
            if (origin.isNotBlank()) {
                val client = currentSession()
                val user = client.restore()
                if (user != null && request == revision && session === client) {
                    mutableState.update { it.copy(user = user) }
                    refreshSnapshot(client, request)
                }
            }
        }
    }

    fun onForeground() {
        if (state.value.user != null && !state.value.isLoading && !state.value.isRestoring) refresh()
    }

    private fun currentSession(): SessionClient {
        session?.let { return it }
        val client = sessionFactory(state.value.backendUrl)
        session = client
        sessionObserver = viewModelScope.launch {
            client.user.collect { profile ->
                if (session === client && profile == null && state.value.user != null) {
                    features.invalidate()
                    resetOrderFlow()
                    sessionEpoch++
                    marketRevision++
                    marketRequest?.cancel()
                    mutableState.update { it.copy(user = null, snapshot = null, market = MarketState()) }
                } else if (session === client && profile != null && state.value.user != null) {
                    if (!sameScope(profile, state.value.user)) {
                        features.invalidate()
                        resetOrderFlow()
                        sessionEpoch++
                        marketRevision++
                        marketRequest?.cancel()
                        mutableState.update { it.copy(user = profile, snapshot = null, market = MarketState()) }
                    } else {
                        mutableState.update { it.copy(user = profile) }
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
            mutableState.update { it.copy(user = user, snapshot = null) }
            refreshSnapshot(client, request)
        }
    }

    fun refresh() {
        if (state.value.isLoading || state.value.user == null) return
        runOperation { request -> refreshSnapshot(currentSession(), request) }
    }

    fun selectWallet(publicId: String?) {
        if (state.value.isLoading || state.value.user == null) return
        resetOrderFlow()
        features.invalidate()
        marketRequest?.cancel()
        marketRevision++
        sessionEpoch++
        mutableState.update { it.copy(snapshot = null, market = MarketState()) }
        runOperation { request ->
            val client = currentSession()
            val profile = client.selectWallet(publicId)
            if (request != revision || session !== client) return@runOperation
            mutableState.update { it.copy(user = profile) }
            refreshSnapshot(client, request)
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
        features.invalidate()
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
        if (language !in setOf("", "en", "pl")) return
        viewModelScope.launch {
            try {
                settings.setLanguage(language)
                mutableState.update { it.copy(language = language) }
            } catch (_: IOException) {
                mutableState.update { it.copy(error = UiError.SERVICE) }
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

    fun loadMarket(exchange: String, instrument: String, timeframe: String) {
        val client = session ?: return
        if (state.value.user?.hasPermission("read:market_data") != true) return
        marketRequest?.cancel()
        val request = ++marketRevision
        val epoch = sessionEpoch
        val query = MarketState(exchange.trim(), instrument.trim(), timeframe.trim(), isLoading = true)
        mutableState.update { it.copy(market = query) }
        marketRequest = viewModelScope.launch {
            try {
                val candles = client.candles(query.exchange, query.instrument, query.timeframe)
                if (session === client && epoch == sessionEpoch && request == marketRevision) {
                    mutableState.update { it.copy(market = query.copy(candles = candles, isLoading = false)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (session === client && epoch == sessionEpoch && request == marketRevision) {
                    mutableState.update { it.copy(market = query.copy(isLoading = false, error = classify(error))) }
                }
            }
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
}
