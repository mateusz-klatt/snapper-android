package ch.snapper.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snapper.android.data.OriginPolicy
import ch.snapper.android.data.SessionClient
import ch.snapper.android.data.SessionExpiredException
import ch.snapper.android.data.UserDto
import java.io.IOException
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

    val actions = AppActions(
        login = ::login,
        refresh = ::refresh,
        selectWallet = ::selectWallet,
        logout = ::logout,
        saveBackend = ::saveBackend,
        selectLanguage = ::selectLanguage,
        clearError = { mutableState.update { it.copy(error = null) } },
        loadMarket = ::loadMarket,
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
                    sessionEpoch++
                    marketRevision++
                    marketRequest?.cancel()
                    mutableState.update { it.copy(user = null, snapshot = null, market = MarketState()) }
                } else if (session === client && profile != null && state.value.user != null) {
                    if (!sameScope(profile, state.value.user)) {
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
            mutableState.update { it.copy(user = snapshot.user, snapshot = snapshot) }
        }
    }

    private fun sameScope(first: UserDto, second: UserDto?): Boolean = second != null &&
        first.username == second.username && first.publicId == second.publicId &&
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
