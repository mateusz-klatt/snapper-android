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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
) : SessionClient, DeskClient, MarketCatalogClient, SystemClient, RecordsClient, NotificationDefaultsClient {
    private val mutableUser = MutableStateFlow<UserDto?>(null)
    override val user: StateFlow<UserDto?> = mutableUser.asStateFlow()
    private val operation = Mutex()
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private val loggingOut = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val sessionId = UUID.randomUUID().toString()

    private fun ensureCurrent(generation: Long) {
        if (closed.get() || loggingOut.get() || cookies.generation() != generation) throw SessionExpiredException()
    }

    private fun commitUser(generation: Long, profile: UserDto) = synchronized(stateLock) {
        ensureCurrent(generation)
        mutableUser.value = profile
    }

    private fun expire(generation: Long) = synchronized(stateLock) {
        if (cookies.generation() == generation) {
            mutableUser.value = null
            cookies.invalidate()
        }
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
        val wallets = authorized(generation) { api.wallets().payload }
        val positions = if (profile.hasPermission("read:positions")) authorized(generation) { api.positions(profile.activeWalletPublicId).payload } else emptyList()
        val orders = if (profile.hasPermission("read:orders")) authorized(generation) { api.orders(profile.activeWalletPublicId).payload } else emptyList()
        val alerts = if (profile.hasPermission("read:notifications")) authorized(generation) { api.alerts().payload } else emptyList()
        ensureCurrent(generation)
        val current = mutableUser.value ?: throw SessionExpiredException()
        check(current.publicId == profile.publicId && current.activeWalletPublicId == profile.activeWalletPublicId && current.effectivePermissions == profile.effectivePermissions) { "Session scope changed while loading. Refresh again." }
        DashboardSnapshot(current, wallets, positions, orders, alerts)
    }

    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> = operation.withLock {
        require(limit in 1..1000) { "Candle limit must be between 1 and 1000" }
        check(mutableUser.value?.hasPermission(READ_MARKET_DATA_PERMISSION) == true) { "Market data permission is required" }
        authorized(cookies.generation()) { api.candles(exchange, instrument, timeframe, limit).payload }
    }

    private suspend fun <T> featureRead(permission: String? = null, action: suspend () -> T): T = operation.withLock {
        val generation = cookies.generation()
        ensureCurrent(generation)
        check(mutableUser.value != null) { "Sign in first" }
        val profile = authorized(generation) { api.me().payload }
        commitUser(generation, profile)
        if (permission != null && !profile.hasPermission(permission)) throw OrderRejectedException(403)
        authorized(generation, action)
    }

    override suspend fun marketExchanges(): List<String> = featureRead(READ_MARKET_DATA_PERMISSION) { api.exchanges().payload }
    override suspend fun marketInstruments(exchange: String): List<InstrumentDto> {
        require(exchange.isNotBlank())
        return featureRead(READ_MARKET_DATA_PERMISSION) { api.instruments(exchange).payload }
    }

    override suspend fun desks(): List<DeskDto> = featureRead { api.desks().payload }

    override suspend fun attachViewer(scope: OrderScope, operatorPublicId: String, username: String) = operation.withLock {
        require(operatorPublicId.isNotBlank() && username.isNotBlank() && username == username.trim())
        val generation: Long
        try {
            generation = featureMutationPreflight(scope, "manage:desk_memberships")
            val desks = authorized(generation) { api.desks().payload }
            if (desks.none { it.publicId == operatorPublicId }) throw DeskRejectedException(403)
            requireFeatureScope(scope, "manage:desk_memberships")
        } catch (error: CancellationException) {
            throw error
        } catch (error: DeskRejectedException) {
            throw error
        } catch (error: OrderRejectedException) {
            throw DeskRejectedException(error.statusCode)
        } catch (error: Exception) {
            throw DeskPreparationException(error)
        }
        try {
            mutationApi.attachViewer(operatorPublicId, username)
            ensureCurrent(generation)
        } catch (error: HttpException) {
            if (error.code() == 401) expire(generation)
            if (error.code() in 400..499 && error.code() !in setOf(408, 425)) throw DeskRejectedException(error.code())
            throw error
        }
        Unit
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

    override suspend fun systemHealth(): SystemHealthCheckDataDto = featureRead("read:system_status") { api.systemHealth().payload }
    override suspend fun systemStatus(): SystemSystemStatusDataDto = featureRead("read:system_status") { api.systemStatus().payload }
    override suspend fun systemStrategies(): List<SystemStrategyProcessDto> = featureRead("read:strategies") { api.systemStrategies().payload }
    override suspend fun systemBacktests(): List<SystemBacktestRunDataDto> = featureRead("read:backtests") { api.systemBacktests().payload }
    override suspend fun systemUsers(): List<SystemUserProfileDto> = featureRead("manage:users") { api.systemUsers().payload }
    override suspend fun systemDelegates(): List<SystemDelegateReadDto> = featureRead("read:ai_integration") { api.systemDelegates().payload }

    override suspend fun executions(walletPublicId: String?): List<ExecutionDto> = featureRead("read:orders") {
        if (mutableUser.value?.activeWalletPublicId != walletPublicId) throw OrderScopeChangedException()
        api.executions(walletPublicId).payload
    }

    override suspend fun notificationDefaults(): List<NotificationDefaultDto> = featureRead { api.notificationDefaults().payload }

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
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    companion object {
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
            return SessionRepository(api, mutationApi, cookies, client)
        }
    }
}
