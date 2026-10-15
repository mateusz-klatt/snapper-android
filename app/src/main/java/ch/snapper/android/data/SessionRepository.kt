package ch.snapper.android.data

import android.content.Context
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
    fun close()
}

/** Serializes cookie rotation and scoped reads so a snapshot belongs to one wallet. */
class SessionRepository private constructor(
    private val api: SnapperApi,
    private val cookies: SessionCookies,
    private val client: OkHttpClient,
) : SessionClient {
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
            rotate(generation, RefreshPayload(publicId, if (publicId == null) true else null))
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
        check(mutableUser.value?.hasPermission("read:market_data") == true) { "Market data permission is required" }
        authorized(cookies.generation()) { api.candles(exchange, instrument, timeframe, limit).payload }
    }

    override suspend fun logout() {
        if (!loggingOut.compareAndSet(false, true)) return
        val headers = synchronized(stateLock) {
            val captured = cookies.authenticationHeaders()
            mutableUser.value = null
            cookies.pause()
            captured
        }
        client.dispatcher.cancelAll()
        try {
            operation.withLock { api.logout(headers.first, headers.second) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            // Local credentials remain cleared if the server cannot be reached.
        } finally {
            synchronized(stateLock) {
                if (!closed.get()) {
                    cookies.invalidate()
                    cookies.resume()
                }
                loggingOut.set(false)
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            closed.set(true)
            mutableUser.value = null
            cookies.pause()
        }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    companion object {
        fun create(context: Context, origin: String, allowLocalHttp: Boolean): SessionRepository {
            val url = OriginPolicy.validate(origin, allowLocalHttp)
            return create(origin, allowLocalHttp, EncryptedCookieStorage(context.applicationContext, url))
        }
        fun create(origin: String, allowLocalHttp: Boolean, cookieStorage: CookieStorage = MemoryCookieStorage()): SessionRepository {
            val url = OriginPolicy.validate(origin, allowLocalHttp)
            val cookies = SessionCookies(url, cookieStorage)
            val client = OkHttpClient.Builder().addInterceptor(cookies)
                .followRedirects(false).followSslRedirects(false)
                .callTimeout(15, TimeUnit.SECONDS).build()
            val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
            val api = Retrofit.Builder().baseUrl(url).client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(SnapperApi::class.java)
            return SessionRepository(api, cookies, client)
        }
    }
}
