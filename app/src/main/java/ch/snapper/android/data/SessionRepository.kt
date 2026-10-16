package ch.snapper.android.data

import android.content.Context
import android.security.NetworkSecurityPolicy
import ch.snapper.android.features.system.SystemClient
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SessionExpiredException : IOException("Your session has expired. Please sign in again.")

interface SessionClient {
    val user: StateFlow<UserDto?>
    suspend fun restore(): UserDto?
    suspend fun login(username: String, password: String, rememberMe: Boolean = true): UserDto
    suspend fun selectWallet(publicId: String?): UserDto
    suspend fun logout()
    suspend fun snapshot(): DashboardSnapshot
    suspend fun candles(exchange: String, instrument: String, timeframe: String = "1h", limit: Int = 100): List<CandleDto>
    suspend fun orderExchanges(scope: OrderScope): List<String>
    suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto>
    suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto
    suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto
    fun close()
}

/** Serializes cookie rotation and scoped reads so a snapshot belongs to one wallet. */
class SessionRepository private constructor(
    private val api: SnapperApi,
    private val mutationApi: SnapperApi,
    private val cookies: SessionCookies,
    private val client: OkHttpClient,
    private val origin: HttpUrl,
) : SessionClient, DeskClient, MarketCatalogClient, SystemClient, RecordsClient, NotificationDefaultsClient,
    SignalsClient, VenueAccountsClient, PnlClient, ProcessClient, AiReviewsClient, PositionActionClient, LiveSessionClient, LiveTransportClient, MarketContextClient, MarketDiagnosticsClient, AccountLanguageClient, PushClient {
    private val mutableUser = MutableStateFlow<UserDto?>(null)
    override val user: StateFlow<UserDto?> = mutableUser.asStateFlow()
    private val operation = Mutex()
    private val liveSockets = SessionLiveSockets()
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private val loggingOut = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val sessionId = UUID.randomUUID().toString()

    private fun ensureCurrent(generation: Long) {
        if (closed.get() || loggingOut.get() || cookies.generation() != generation) throw SessionExpiredException()
    }

    private fun commitUser(generation: Long, profile: UserDto) {
        val changed = synchronized(stateLock) {
            ensureCurrent(generation)
            val previous = mutableUser.value
            mutableUser.value = profile
            previous != null && !sameLiveScope(previous, profile)
        }
        if (changed) liveSockets.cancelAll()
    }

    private fun expire(generation: Long) {
        val expired = synchronized(stateLock) {
            if (cookies.generation() == generation) {
                mutableUser.value = null
                cookies.invalidate()
                true
            } else false
        }
        if (expired) liveSockets.cancelAll()
    }

    private fun <T> envelope(type: String, payload: T): Envelope<T> = Envelope(
        payload = payload, type = type, sequenceId = sequence.incrementAndGet(),
        publicId = UUID.randomUUID().toString(), timestamp = Instant.now().toString(), sessionId = sessionId,
    )

    override suspend fun login(username: String, password: String, rememberMe: Boolean): UserDto = operation.withLock {
        val generation = synchronized(stateLock) {
            check(!closed.get() && !loggingOut.get()) { "Session is closed" }
            mutableUser.value = null
            cookies.setPersistence(rememberMe)
            cookies.invalidate()
        }
        liveSockets.cancelAll()
        try {
            val result = api.login(envelope("login_request", LoginPayload(username, password, rememberMe))).payload.user
            commitUser(generation, result)
            result
        } catch (error: Exception) {
            if (cookies.generation() == generation) cookies.invalidate()
            throw error
        }
    }

    override suspend fun restore(): UserDto? = operation.withLock {
        val generation = cookies.generation()
        ensureCurrent(generation)
        if (!cookies.hasAuthentication()) return@withLock null
        try {
            val result = authorized(generation) { api.me().payload }
            commitUser(generation, result)
            result
        } catch (_: SessionExpiredException) {
            null
        }
    }

    private suspend fun rotate(generation: Long, payload: RefreshPayload = RefreshPayload()): UserDto {
        ensureCurrent(generation)
        val result = api.refresh(envelope("refresh_token_request", payload)).payload.user
        commitUser(generation, result)
        return result
    }

    private suspend fun <T> authorized(generation: Long, action: suspend () -> T): T {
        ensureCurrent(generation)
        try {
            return action().also { ensureCurrent(generation) }
        } catch (error: HttpException) {
            if (error.code() != 401) throw error
        }
        try {
            rotate(generation)
            return action().also { ensureCurrent(generation) }
        } catch (error: HttpException) {
            if (error.code() != 401) throw error
            expire(generation)
            throw SessionExpiredException()
        }
    }

    override suspend fun selectWallet(publicId: String?): UserDto = operation.withLock {
        val generation = cookies.generation()
        ensureCurrent(generation)
        check(mutableUser.value != null) { "Sign in first" }
        try {
            rotate(generation, RefreshPayload(publicId, (publicId == null).takeIf { it }))
        } catch (error: HttpException) {
            if (error.code() == 401 && cookies.generation() == generation) {
                expire(generation)
                throw SessionExpiredException()
            }
            throw error
        }
    }

    override suspend fun snapshot(): DashboardSnapshot = operation.withLock {
        val generation = cookies.generation()
        val profile = authorized(generation) { api.me().payload }
        commitUser(generation, profile)
        val wallets = snapshotRead(generation, profile) { api.wallets().payload }
        val positions = if (profile.hasPermission(READ_POSITIONS_PERMISSION)) snapshotRead(generation, profile) { api.positions(profile.activeWalletPublicId).payload } else emptyList()
        val orders = if (profile.hasPermission("read:orders")) snapshotRead(generation, profile) { api.orders(profile.activeWalletPublicId).payload } else emptyList()
        val alerts = if (profile.hasPermission("read:notifications")) snapshotRead(generation, profile) { api.alerts().payload } else emptyList()
        ensureCurrent(generation)
        requireLiveScope(profile)
        DashboardSnapshot(profile, wallets, positions, orders, alerts)
    }

    private suspend fun <T> snapshotRead(generation: Long, profile: UserDto, action: suspend () -> T): T =
        authorized(generation) {
            requireLiveScope(profile)
            action().also { requireLiveScope(profile) }
        }

    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> = operation.withLock {
        require(limit in 1..1000) { "Candle limit must be between 1 and 1000" }
        check(mutableUser.value?.hasPermission(READ_MARKET_DATA_PERMISSION) == true) { "Market data permission is required" }
        authorized(cookies.generation()) { api.candles(exchange, instrument, timeframe, limit).payload }
    }

    private suspend fun <T> featureRead(permission: String? = null, action: suspend () -> T): T = operation.withLock {
        val generation = cookies.generation()
        ensureCurrent(generation)
        val expectedScope = OrderScope.from(mutableUser.value ?: throw SessionExpiredException())
        val profile = authorized(generation) { api.me().payload }
        commitUser(generation, profile)
        if (permission != null && !profile.hasPermission(permission)) throw OrderRejectedException(403)
        requireFeatureScope(expectedScope, permission)
        authorized(generation) {
            requireFeatureScope(expectedScope, permission)
            action()
        }
    }

    override suspend fun processSummary(): ProcessSummaryDto = featureRead(READ_SYSTEM_STATUS_PERMISSION) { api.processSummary().payload }
    override suspend fun configuredProcesses(): List<ConfiguredProcessDto> = featureRead("read:processes") { api.configuredProcesses().payload }

    private suspend fun <T> actionRead(generation: Long, scope: OrderScope, permission: String, action: suspend () -> T): T =
        authorized(generation) {
            requireFeatureScope(scope, permission)
            action().also { requireFeatureScope(scope, permission) }
        }

    private suspend fun prepareProcess(scope: OrderScope, target: ConfiguredProcessDto, remote: Boolean): Pair<Long, ConfiguredProcessDto> {
        try {
            val generation = featureMutationPreflight(scope, "manage:processes")
            val rows = actionRead(generation, scope, "read:processes") { api.configuredProcesses().payload }
            val fresh = rows.singleOrNull { it.name == target.name } ?: throw OrderScopeChangedException()
            ActionTransport.processTarget(target, fresh, remote)
            return generation to fresh
        } catch (error: OrderRejectedException) {
            throw ProcessRejectedException(error.statusCode)
        } catch (_: IllegalArgumentException) {
            throw OrderScopeChangedException()
        }
    }

    override suspend fun startProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStartDto = operation.withLock {
        val (generation, _) = prepareProcess(scope, target, false)
        dispatchProcess(generation) {
            mutationApi.startProcess(target.name, envelope("process_start_request", ProcessStartPayload())).payload.also {
                check(it.name == target.name && it.status in setOf("success", "already_running", "error"))
            }
        }
    }

    override suspend fun stopProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStopDto = operation.withLock {
        val (generation, _) = prepareProcess(scope, target, false)
        dispatchProcess(generation) {
            mutationApi.stopProcess(target.name).payload.also {
                check(it.name == target.name && it.status in setOf("success", "not_running", "error"))
            }
        }
    }

    override suspend fun processDesiredState(scope: OrderScope, target: ConfiguredProcessDto, payload: ProcessDesiredStatePayload): ProcessDesiredStateDto = operation.withLock {
        val (generation, fresh) = prepareProcess(scope, target, true)
        ActionTransport.desiredState(payload, fresh)
        dispatchProcess(generation) {
            mutationApi.processDesiredState(target.name, envelope("process_desired_state_request", payload)).payload.also {
                check(it.name == target.name && it.action == payload.action && it.status == "success")
            }
        }
    }

    private suspend fun <T> dispatchProcess(generation: Long, action: suspend () -> T): T {
        ensureCurrent(generation)
        try {
            return action().also { ensureCurrent(generation) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            ensureCurrent(generation)
            if (error.code() == 401) expire(generation)
            if (error.code() in 400..499 && error.code() !in setOf(408, 425)) throw ProcessRejectedException(error.code())
            throw ProcessOutcomeUnknownException(error)
        } catch (error: Exception) {
            throw ProcessOutcomeUnknownException(error)
        }
    }

    override suspend fun auditReviews(): List<AiAuditReviewDto> = featureRead("read:ai_reviews") { api.auditReviews().items }

    override suspend fun pendingReviews(): List<AiPendingReviewDto> {
        val delegate = mutableUser.value?.delegatePublicId
        return featureRead(READ_SIGNALS_PERMISSION) {
            requireDelegate(delegate)
            api.pendingReviews().items.also { requireDelegate(delegate) }
        }
    }

    private fun requireDelegate(expected: String?) {
        if (expected.isNullOrBlank() || mutableUser.value?.delegatePublicId != expected) throw OrderScopeChangedException()
    }

    override suspend fun submitDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse = operation.withLock {
        val generation = prepareDecision(scope, reviewId, payload, requirePending = true)
        dispatchDecision(generation, reviewId, payload)
    }

    override suspend fun retryDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse = operation.withLock {
        val generation = prepareDecision(scope, reviewId, payload, requirePending = false)
        dispatchDecision(generation, reviewId, payload)
    }

    private suspend fun prepareDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload, requirePending: Boolean): Long {
        try {
            require(reviewId.isNotBlank() && payload.decision in setOf("approve", "reject"))
            require(payload.rationale?.let { it.codePointCount(0, it.length) <= 4096 } != false)
            val delegate = mutableUser.value?.delegatePublicId
            val generation = featureMutationPreflight(scope, "submit:ai_review_decision")
            requireDelegate(delegate)
            requireFeatureScope(scope, READ_SIGNALS_PERMISSION)
            if (requirePending) requirePendingDecision(generation, scope, reviewId, delegate)
            return generation
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw AiDecisionPreparationException(error)
        }
    }

    private suspend fun requirePendingDecision(generation: Long, scope: OrderScope, reviewId: String, delegate: String?) {
        val rows = actionRead(generation, scope, READ_SIGNALS_PERMISSION) {
            requireDelegate(delegate)
            api.pendingReviews().items
        }
        requireDelegate(delegate)
        val pending = rows.singleOrNull { it.reviewPublicId == reviewId } ?: throw OrderScopeChangedException()
        require(pending.status in setOf("pending", "fanout_dispatched"))
        require(Instant.parse(pending.deadline).isAfter(Instant.now()))
    }

    private suspend fun dispatchDecision(generation: Long, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse {
        ensureCurrent(generation)
        try {
            return mutationApi.submitDecision(reviewId, envelope("ai_review_decision_command", payload)).also { ensureCurrent(generation) }
        } catch (error: HttpException) {
            ensureCurrent(generation)
            if (error.code() == 401) {
                expire(generation)
                throw SessionExpiredException()
            }
            val response = ActionTransport.aiError(error.response()?.errorBody()?.string()) ?: throw error
            ensureCurrent(generation)
            return response
        }
    }

    override suspend fun submit(scope: OrderScope, intent: PositionActionIntent): ExecutionPlanDto = operation.withLock {
        val generation = preparePosition(scope, intent)
        dispatchPosition(generation, intent)
    }

    private suspend fun preparePosition(scope: OrderScope, intent: PositionActionIntent): Long {
        try {
            val generation = featureMutationPreflight(scope, CREATE_ORDERS_PERMISSION)
            require(intent.target.walletPublicId == scope.activeWalletPublicId)
            val wallet = actionRead(generation, scope, READ_POSITIONS_PERMISSION) { api.wallets().payload }
                .single { it.publicId == intent.target.walletPublicId }
            val position = actionRead(generation, scope, READ_POSITIONS_PERMISSION) { api.positions(scope.activeWalletPublicId).payload }
                .single { it.publicId == intent.target.positionPublicId }
            ActionTransport.positionTarget(intent.target, position, wallet)
            ActionTransport.positionIntent(intent, position, wallet)
            validatePositionInstrument(generation, scope, position)
            return generation
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw PositionPreparationException(error)
        }
    }

    private suspend fun validatePositionInstrument(generation: Long, scope: OrderScope, position: PositionDto) {
        val instrument = actionRead(generation, scope, READ_MARKET_DATA_PERMISSION) { api.instruments(position.exchange).payload }
            .single { it.instrumentPublicId == position.instrumentPublicId && it.symbol == position.instrument && it.exchange == position.exchange }
        require(instrument.instrumentResolved && instrument.canTrade)
    }

    private suspend fun dispatchPosition(generation: Long, intent: PositionActionIntent): ExecutionPlanDto {
        ensureCurrent(generation)
        try {
            val result = when (intent) {
                is PositionActionIntent.Order -> mutationApi.createOrder(envelope("create_order_command", intent.payload)).payload
                is PositionActionIntent.Bracket -> mutationApi.createBracket(envelope("create_bracket_command", intent.payload)).payload
                is PositionActionIntent.Trailing -> mutationApi.createTrailing(envelope("create_trailing_stop_command", intent.payload)).payload
            }
            ensureCurrent(generation)
            check(result.publicId.isNotBlank() && result.walletPublicId == intent.target.walletPublicId)
            return result
        } catch (error: HttpException) {
            ensureCurrent(generation)
            if (error.code() == 401) expire(generation)
            if (error.code() in 400..499 && error.code() !in setOf(408, 409, 425)) throw PositionRejectedException(error.code())
            throw OrderOutcomeUnknownException(error)
        }
    }

    /** Bind the UI identity to this precise login's cookie generation, not to the unrelated UI epoch. */
    override fun bindPush(identity: PushIdentity): PushBinding {
        val generation = cookies.generation()
        val bound = object : PushClient {
            override suspend fun pushConfiguration(): PushConfigurationInfo = pushRead(identity, generation) { api.pushConfiguration().payload }
            override suspend fun pushRegistrationState(configurationId: String, installationId: String): PushRegistrationState =
                pushRead(identity, generation) { api.pushRegistrationState(configurationId, installationId).payload }
            override suspend fun pushInventory(): List<PushRegistration> = pushRead(identity, generation) { api.pushInventory().payload }
            override suspend fun pushPreferences(registrationId: String): List<PushPreference> = pushRead(identity, generation) { api.pushPreferences(registrationId).payload }
            override suspend fun pushAlert(alertId: String): AlertDto = pushRead(identity, generation, "read:notifications") { api.pushAlert(alertId).payload }
            override suspend fun registerPush(command: PushRegistrationCommand): PushRegistration = operation.withLock {
                pushPreflight(identity, generation)
                require(command.expectedUserPublicId == identity.actor.publicId)
                dispatchPush(identity, generation) { mutationApi.registerPush(envelope("register_android_device_command", command)).payload }
            }
            override suspend fun revokePush(registrationId: String) = operation.withLock {
                pushPreflight(identity, generation)
                dispatchPush(identity, generation) {
                    val response = mutationApi.revokePush(registrationId)
                    if (!response.isSuccessful) throw HttpException(response)
                }
            }
        }
        return PushBinding(identity, bound)
    }

    private fun requirePushIdentity(identity: PushIdentity, generation: Long, permission: String? = null) {
        ensureCurrent(generation)
        if (identity.backend.toHttpUrlOrNull() != origin) throw PushScopeChangedException()
        requireFeatureScope(identity.actor, permission)
    }

    private suspend fun pushPreflight(identity: PushIdentity, generation: Long, permission: String? = null) {
        requirePushIdentity(identity, generation, permission)
        authorized(generation) {
            requirePushIdentity(identity, generation, permission)
            commitUser(generation, api.me().payload)
            requirePushIdentity(identity, generation, permission)
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun <T> pushRead(identity: PushIdentity, generation: Long, permission: String? = null, action: suspend () -> T): T = operation.withLock {
        pushPreflight(identity, generation, permission)
        authorized(generation) {
            requirePushIdentity(identity, generation, permission)
            action().also { requirePushIdentity(identity, generation, permission) }
        }
    }

    private suspend fun <T> dispatchPush(identity: PushIdentity, generation: Long, action: suspend () -> T): T {
        try {
            requirePushIdentity(identity, generation)
            return action().also { requirePushIdentity(identity, generation) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            if (error.code() == 401) expire(generation)
            val code = runCatching {
                error.response()?.errorBody()?.string()?.let { Json.parseToJsonElement(it).jsonObject["detail"]?.jsonPrimitive?.content }
            }.getOrNull()
            if ((error.code() in 400..499 && error.code() !in setOf(408, 425)) || code == "android_push_unavailable") {
                throw PushRejectedException(code, error.code())
            }
            throw PushUncertainException()
        } catch (_: Exception) {
            throw PushUncertainException()
        }
    }

    // Capability calls must use the immutable client produced by bindPush.
    override suspend fun pushConfiguration(): PushConfigurationInfo = throw PushScopeChangedException()
    override suspend fun pushRegistrationState(configurationId: String, installationId: String): PushRegistrationState = throw PushScopeChangedException()
    override suspend fun registerPush(command: PushRegistrationCommand): PushRegistration = throw PushScopeChangedException()
    override suspend fun pushInventory(): List<PushRegistration> = throw PushScopeChangedException()
    override suspend fun pushPreferences(registrationId: String): List<PushPreference> = throw PushScopeChangedException()
    override suspend fun revokePush(registrationId: String): Unit = throw PushScopeChangedException()
    override suspend fun pushAlert(alertId: String): AlertDto = throw PushScopeChangedException()

    override fun bindLanguage(identity: AccountLanguageIdentity): AccountLanguageBinding {
        val generation = cookies.generation()
        val scoped = AccountLanguageClient { supplied, language ->
            if (supplied != identity) throw AccountLanguageException(AccountLanguageFailureKind.PREPARATION)
            updateLanguageBound(identity, language, generation)
        }
        return AccountLanguageBinding(identity, scoped)
    }

    override suspend fun updateLanguage(identity: AccountLanguageIdentity, language: String): AccountLanguageReceipt =
        updateLanguageBound(identity, language, cookies.generation())

    private suspend fun updateLanguageBound(identity: AccountLanguageIdentity, language: String, generation: Long): AccountLanguageReceipt {
        prepareLanguage(identity, language, generation)
        return operation.withLock {
            preflightLanguage(identity, language, generation)
            currentCoroutineContext().ensureActive()
            dispatchLanguage(identity, language, generation)
        }
    }

    private fun requireLanguageIdentity(identity: AccountLanguageIdentity, generation: Long) {
        ensureCurrent(generation)
        require(identity.backend.toHttpUrlOrNull() == origin)
        requireFeatureScope(identity.actor, null)
    }

    private fun prepareLanguage(identity: AccountLanguageIdentity, language: String, generation: Long) {
        try {
            requireNotNull(SupportedLanguages.find(language))
            requireLanguageIdentity(identity, generation)
        } catch (error: Exception) {
            throw AccountLanguageException(AccountLanguageFailureKind.PREPARATION)
        }
    }

    private suspend fun preflightLanguage(identity: AccountLanguageIdentity, language: String, generation: Long) {
        try {
            prepareLanguage(identity, language, generation)
            authorized(generation) {
                requireLanguageIdentity(identity, generation)
                val profile = api.me().payload
                commitUser(generation, profile)
                requireLanguageIdentity(identity, generation)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw AccountLanguageException(AccountLanguageFailureKind.PREPARATION)
        }
    }

    private suspend fun dispatchLanguage(identity: AccountLanguageIdentity, language: String, generation: Long): AccountLanguageReceipt {
        try {
            val profile = mutationApi.updateAccountLanguage(envelope("update_auth_me_request", UpdateAccountLanguagePayload(language))).payload
            requireLanguageIdentity(identity, generation)
            val rawScope = OrderScope.from(profile).copy(primaryOperatorPublicId = profile.primaryOperatorPublicId ?: identity.actor.primaryOperatorPublicId)
            require(rawScope == identity.actor && profile.defaultLanguage == language)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            if (error.code() == 401) expire(generation)
            val kind = if (error.code() in 400..499 && error.code() !in setOf(408, 425))
                AccountLanguageFailureKind.REJECTED else AccountLanguageFailureKind.UNKNOWN
            throw AccountLanguageException(kind)
        } catch (error: Exception) {
            throw AccountLanguageException(AccountLanguageFailureKind.UNKNOWN)
        }
        return confirmLanguage(identity, language, generation)
    }

    /** Write projections may omit the derived primary operator; only canonical /me can confirm acceptance. */
    private suspend fun confirmLanguage(identity: AccountLanguageIdentity, language: String, generation: Long): AccountLanguageReceipt {
        try {
            val canonical = authorized(generation) {
                requireLanguageIdentity(identity, generation)
                api.me().payload.also {
                    // Canonical security changes must invalidate old live/UI scope even when this receipt is refused.
                    commitUser(generation, it)
                    requireLanguageIdentity(identity, generation)
                    require(it.defaultLanguage == language)
                }
            }
            commitUser(generation, canonical)
            return AccountLanguageReceipt(identity, language)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The POST has already succeeded. Failed confirmation cannot become a known rejection.
            throw AccountLanguageException(AccountLanguageFailureKind.UNKNOWN)
        }
    }

    override suspend fun cachedHourly(exchange: String, symbol: String): MarketCachedCandlesDto {
        require(exchange.isNotBlank() && symbol.isNotBlank())
        return featureRead(READ_MARKET_DATA_PERMISSION) { api.cachedHourly(exchange, symbol).payload }
    }

    override suspend fun configuredPairStats(): ConfiguredPairStatsDto =
        featureRead(READ_MARKET_DATA_PERMISSION) { api.configuredPairStats().payload }

    override suspend fun chartCache(exchange: String, instrument: String, timeframe: String, limit: Int): MarketCachedCandlesDto {
        require(exchange.isNotBlank() && instrument.isNotBlank())
        require(timeframe in setOf("1m", "5m", "15m", "1h", "4h", "1d") && limit in 1..1000)
        return featureRead(READ_MARKET_DATA_PERMISSION) { api.cachedHourly(exchange, instrument, timeframe, limit).payload }
    }

    override suspend fun related(exchange: String, symbol: String): RelatedMarketPayloadDto {
        require(exchange.isNotBlank() && symbol.isNotBlank())
        return featureRead(READ_MARKET_DATA_PERMISSION) { api.related(exchange, symbol).payload }
    }

    override suspend fun liveTicket(): LiveTicket = operation.withLock {
        val initial = mutableUser.value ?: throw SessionExpiredException()
        val scope = OrderScope.from(initial)
        val generation = featureMutationPreflight(scope)
        val payload = authorized(generation) {
            requireLiveScope(initial)
            mutationApi.liveTicket().payload
        }
        requireLiveScope(initial)
        require(payload.expiresIn > 0)
        LiveTicket(payload.token)
    }

    private fun sameLiveScope(current: UserDto, expected: UserDto): Boolean =
        OrderScope.from(current) == OrderScope.from(expected) && current.delegatePublicId == expected.delegatePublicId &&
            current.primaryOperatorPublicId == expected.primaryOperatorPublicId

    private fun requireLiveScope(expected: UserDto) {
        val current = mutableUser.value ?: throw SessionExpiredException()
        if (!sameLiveScope(current, expected)) throw OrderScopeChangedException()
    }

    override fun liveTransport(): LiveSocketTransport {
        val captured = synchronized(stateLock) {
            val generation = cookies.generation()
            ensureCurrent(generation)
            val profile = mutableUser.value ?: throw SessionExpiredException()
            generation to profile
        }
        val request = Request.Builder().url(origin.newBuilder().encodedPath("/api/ws").query(null).build()).build()
        return liveSockets.transport(OkHttpLiveSocketTransport(client, request)) {
            synchronized(stateLock) {
                val profile = mutableUser.value
                !closed.get() && !loggingOut.get() && cookies.generation() == captured.first &&
                    profile != null && sameLiveScope(profile, captured.second)
            }
        }
    }

    override suspend fun marketExchanges(): List<String> = featureRead(READ_MARKET_DATA_PERMISSION) { api.exchanges().payload }
    override suspend fun marketInstruments(exchange: String): List<InstrumentDto> {
        require(exchange.isNotBlank())
        return featureRead(READ_MARKET_DATA_PERMISSION) { api.instruments(exchange).payload }
    }

    override suspend fun desks(): List<DeskDto> = featureRead { api.desks().payload }

    override suspend fun attachViewer(scope: OrderScope, operatorPublicId: String, username: String) = operation.withLock {
        require(operatorPublicId.isNotBlank() && username.isNotBlank() && username == username.trim())
        val generation = prepareDeskAttachment(scope, operatorPublicId)
        dispatchDeskAttachment(generation, operatorPublicId, username)
    }

    private suspend fun prepareDeskAttachment(scope: OrderScope, operatorPublicId: String): Long {
        try {
            val generation = featureMutationPreflight(scope, "manage:desk_memberships")
            val desks = authorized(generation) { api.desks().payload }
            if (desks.none { it.publicId == operatorPublicId }) throw DeskRejectedException(403)
            requireFeatureScope(scope, "manage:desk_memberships")
            return generation
        } catch (error: CancellationException) {
            throw error
        } catch (error: DeskRejectedException) {
            throw error
        } catch (error: OrderRejectedException) {
            throw DeskRejectedException(error.statusCode)
        } catch (error: Exception) {
            throw DeskPreparationException(error)
        }
    }

    private suspend fun dispatchDeskAttachment(generation: Long, operatorPublicId: String, username: String) {
        try {
            mutationApi.attachViewer(operatorPublicId, username)
            ensureCurrent(generation)
        } catch (error: HttpException) {
            if (error.code() == 401) expire(generation)
            if (error.code() in 400..499 && error.code() !in setOf(408, 425)) throw DeskRejectedException(error.code())
            throw error
        }
    }

    private fun requireFeatureScope(scope: OrderScope, permission: String?) {
        val current = mutableUser.value ?: throw SessionExpiredException()
        if (OrderScope.from(current) != scope) throw OrderScopeChangedException()
        if (permission != null && !current.hasPermission(permission)) throw OrderRejectedException(403)
    }

    private suspend fun featureMutationPreflight(scope: OrderScope, permission: String? = null): Long {
        requireFeatureScope(scope, permission)
        val generation = cookies.generation()
        val profile = authorized(generation) { api.me().payload }
        commitUser(generation, profile)
        requireFeatureScope(scope, permission)
        return generation
    }

    override suspend fun systemHealth(): SystemHealthCheckDataDto = featureRead(READ_SYSTEM_STATUS_PERMISSION) { api.systemHealth().payload }
    override suspend fun systemStatus(): SystemSystemStatusDataDto = featureRead(READ_SYSTEM_STATUS_PERMISSION) { api.systemStatus().payload }
    override suspend fun systemStrategies(): List<SystemStrategyProcessDto> = featureRead("read:strategies") { api.systemStrategies().payload }
    override suspend fun systemBacktests(): List<SystemBacktestRunDataDto> = featureRead("read:backtests") { api.systemBacktests().payload }
    override suspend fun systemUsers(): List<SystemUserProfileDto> = featureRead("manage:users") { api.systemUsers().payload }
    override suspend fun systemDelegates(): List<SystemDelegateReadDto> = featureRead("read:ai_integration") { api.systemDelegates().payload }

    override suspend fun executions(walletPublicId: String?): List<ExecutionDto> = featureRead("read:orders") {
        if (mutableUser.value?.activeWalletPublicId != walletPublicId) throw OrderScopeChangedException()
        api.executions(walletPublicId).payload
    }

    override suspend fun notificationDefaults(): List<NotificationDefaultDto> = featureRead { api.notificationDefaults().payload }

    private fun requireSelectedWallet(walletPublicId: String?) {
        if (mutableUser.value?.activeWalletPublicId != walletPublicId) throw OrderScopeChangedException()
    }

    override suspend fun signals(walletPublicId: String?): List<SignalDto> = featureRead(READ_SIGNALS_PERMISSION) {
        requireSelectedWallet(walletPublicId)
        api.signals(walletPublicId).payload
    }

    override suspend fun venueAccounts(walletPublicId: String?): List<VenueAccountDto> = featureRead("read:account_state") {
        requireSelectedWallet(walletPublicId)
        api.venueAccounts(walletPublicId).payload
    }

    override suspend fun pnlSeries(query: PnlSeriesQuery): PnlSeriesDto = featureRead(READ_POSITIONS_PERMISSION) {
        requireSelectedWallet(query.walletPublicId)
        val wallet = api.wallets().payload.firstOrNull { it.publicId == query.walletPublicId } ?: throw OrderScopeChangedException()
        require(query.mode == if (wallet.isPaper) "paper" else "live") { "Wallet mode changed" }
        api.pnlSeries(query.walletPublicId, query.mode, query.granularity, query.from.toString(), query.to.toString(), query.valuationCcy).payload
    }

    override suspend fun updateNotificationDefaults(scope: OrderScope, payload: UpdateNotificationDefaultPayload): NotificationDefaultDto = operation.withLock {
        require(payload.alertType in setOf("order_fill_full", "order_rejected", "position_stop_loss_fired", "margin_warning", "critical_system_error", "order_unknown", "drift"))
        require(payload.minPriority in setOf("low", "medium", "high"))
        val generation = featureMutationPreflight(scope)
        try {
            val result = mutationApi.updateNotificationDefaults(envelope("update_user_alert_default_command", payload)).payload
            ensureCurrent(generation)
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            if (error.code() == 401) expire(generation)
            if (error.code() in 400..499 && error.code() !in setOf(408, 425)) throw NotificationDefaultRejectedException(error.code())
            throw NotificationDefaultOutcomeUnknownException(error)
        } catch (error: IOException) {
            throw NotificationDefaultOutcomeUnknownException(error)
        } catch (error: SerializationException) {
            throw NotificationDefaultOutcomeUnknownException(error)
        }
    }

    private fun requireOrderScope(scope: OrderScope, permission: String) {
        val current = mutableUser.value ?: throw SessionExpiredException()
        if (OrderScope.from(current) != scope) throw OrderScopeChangedException()
        if (!current.hasPermission(permission)) throw OrderRejectedException(403)
        require(!scope.activeWalletPublicId.isNullOrBlank()) { "Select a wallet first" }
    }

    private suspend fun orderPreflight(scope: OrderScope, permission: String): Long {
        requireOrderScope(scope, permission)
        val generation = cookies.generation()
        val profile = authorized(generation) { api.me().payload }
        commitUser(generation, profile)
        requireOrderScope(scope, permission)
        return generation
    }

    override suspend fun orderExchanges(scope: OrderScope): List<String> = operation.withLock {
        val generation = orderPreflight(scope, CREATE_ORDERS_PERMISSION)
        requireOrderScope(scope, READ_MARKET_DATA_PERMISSION)
        val result = authorized(generation) { api.exchanges().payload }
        requireOrderScope(scope, CREATE_ORDERS_PERMISSION)
        result
    }

    override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = operation.withLock {
        require(exchange.isNotBlank())
        val generation = orderPreflight(scope, CREATE_ORDERS_PERMISSION)
        requireOrderScope(scope, READ_MARKET_DATA_PERMISSION)
        val result = authorized(generation) { api.instruments(exchange).payload }
        requireOrderScope(scope, CREATE_ORDERS_PERMISSION)
        result
    }

    override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = operation.withLock {
        require(payload.walletPublicId == scope.activeWalletPublicId)
        require(payload.idempotencyKey.isNotBlank() && payload.instrumentPublicId.isNotBlank())
        require(payload.instrument.isNotBlank() && payload.exchange.isNotBlank())
        require(listOfNotNull(payload.quantity, payload.price, payload.stopPrice).all(OrderNumbers::isSupported))
        require(payload.leverage == null || payload.leverage > 0)
        require(payload.timeInForce == "GTC" && !payload.postOnly)
        require(payload.mode in setOf("paper", "live") && payload.side in setOf("buy", "sell"))
        require(payload.orderType in setOf("market", "limit", "stop", "stop_limit"))
        require(payload.orderType !in setOf("limit", "stop_limit") || payload.price != null)
        require(payload.orderType !in setOf("stop", "stop_limit") || payload.stopPrice != null)
        val generation = orderPreflight(scope, CREATE_ORDERS_PERMISSION)
        requireOrderScope(scope, READ_MARKET_DATA_PERMISSION)
        val instruments = authorized(generation) { api.instruments(payload.exchange).payload }
        requireOrderScope(scope, CREATE_ORDERS_PERMISSION)
        val instrument = instruments.find { it.instrumentPublicId == payload.instrumentPublicId && it.symbol == payload.instrument && it.exchange == payload.exchange }
        if (instrument == null || !instrument.instrumentResolved) throw OrderRejectedException(422, "unknown_instrument")
        if (!instrument.canTrade) throw OrderRejectedException(422, "instrument_market_data_only")
        val command = envelope("create_order_command", payload)
        dispatchOrder(generation) { mutationApi.createOrder(command).payload }
    }

    override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = operation.withLock {
        val plan = requireNotNull(order.planPublicId?.takeIf { it.isNotBlank() })
        require(order.walletPublicId == scope.activeWalletPublicId && order.walletPublicId != null)
        require(order.status in setOf("new", "submitted", "open", "partially_filled"))
        val generation = orderPreflight(scope, "cancel:orders")
        val command = envelope("cancel_order_command", CancelOrderPayload())
        dispatchOrder(generation) { mutationApi.cancelOrder(plan, command).payload }
    }

    private suspend fun dispatchOrder(generation: Long, action: suspend () -> ExecutionPlanDto): ExecutionPlanDto {
        ensureCurrent(generation)
        try {
            return action().also { ensureCurrent(generation) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            if (error.code() == 401) expire(generation)
            if (error.code() in 400..499 && error.code() !in setOf(408, 425)) {
                val code = runCatching {
                    val body = error.response()?.errorBody()?.string() ?: return@runCatching null
                    Json.parseToJsonElement(body).jsonObject["detail"]?.jsonObject?.get("error_code")?.jsonPrimitive?.content
                }.getOrNull()
                throw OrderRejectedException(error.code(), code)
            }
            throw OrderOutcomeUnknownException(error)
        } catch (error: IOException) {
            throw OrderOutcomeUnknownException(error)
        } catch (error: SerializationException) {
            throw OrderOutcomeUnknownException(error)
        }
    }

    override suspend fun logout() {
        if (!loggingOut.compareAndSet(false, true)) return
        try {
            val headers = try {
                synchronized(stateLock) {
                    val captured = cookies.authenticationHeaders()
                    mutableUser.value = null
                    cookies.pause()
                    captured
                }
            } finally {
                liveSockets.cancelAll()
                client.dispatcher.cancelAll()
            }
            operation.withLock { api.logout(headers.first, headers.second) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            // Local credentials remain cleared if the server cannot be reached.
        } finally {
            try {
                synchronized(stateLock) {
                    if (!closed.get()) {
                        try {
                            cookies.invalidate()
                        } finally {
                            cookies.resume()
                        }
                    }
                }
            } finally {
                loggingOut.set(false)
            }
        }
    }

    override fun close() {
        try {
            synchronized(stateLock) {
                closed.set(true)
                mutableUser.value = null
                cookies.pause()
            }
        } finally {
            liveSockets.cancelAll()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        private const val READ_SIGNALS_PERMISSION = "read:signals"
        private const val READ_SYSTEM_STATUS_PERMISSION = "read:system_status"
        private const val READ_POSITIONS_PERMISSION = "read:positions"
        private const val CREATE_ORDERS_PERMISSION = "create:orders"
        private const val READ_MARKET_DATA_PERMISSION = "read:market_data"

        fun create(context: Context, origin: String, allowLocalHttp: Boolean): SessionRepository {
            val url = OriginPolicy.validate(origin, allowLocalHttp)
            return create(url, EncryptedCookieStorage(context.applicationContext, url))
        }
        fun create(
            origin: String,
            allowLocalHttp: Boolean,
            cookieStorage: CookieStorage = MemoryCookieStorage(),
            isCleartextTrafficPermitted: (String) -> Boolean = { host ->
                NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
            },
        ): SessionRepository = create(OriginPolicy.validate(origin, allowLocalHttp, isCleartextTrafficPermitted), cookieStorage)

        private fun create(url: HttpUrl, cookieStorage: CookieStorage): SessionRepository {
            val cookies = SessionCookies(url, cookieStorage)
            val client = OkHttpClient.Builder().addInterceptor(cookies)
                .followRedirects(false).followSslRedirects(false)
                .callTimeout(15, TimeUnit.SECONDS).build()
            val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
            val api = Retrofit.Builder().baseUrl(url).client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(SnapperApi::class.java)
            val mutationClient = client.newBuilder().retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val body = request.body
                    if (body == null) return@addInterceptor chain.proceed(request)
                    val oneShot = object : RequestBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun isOneShot() = true
                        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                    }
                    chain.proceed(request.newBuilder().method(request.method, oneShot).build())
                }.build()
            val mutationApi = Retrofit.Builder().baseUrl(url).client(mutationClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(SnapperApi::class.java)
            return SessionRepository(api, mutationApi, cookies, client, url)
        }
    }
}
