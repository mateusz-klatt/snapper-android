package ch.snapper.android.data

import ch.snapper.android.features.marketdiagnostics.MarketDiagnosticsLogic
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MarketDiagnosticsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private fun profile(permission: String = "read:market_data", actor: String = "u", desk: String = "desk", delegate: String = "delegate") =
        """{"username":"viewer","role":"viewer","public_id":"$actor","active_wallet_public_id":null,"primary_operator_public_id":"$desk","delegate_public_id":"$delegate","effective_permissions":["$permission"]}"""
    private fun response(payload: String, type: String = "fixture") = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"type":"$type","public_id":"response","session_id":"server","sequence_id":1,"timestamp":"2026-10-16T12:00:00Z","payload":$payload}""")
    private val pairPayload = """{"count":1,"pairs":[{"left":"kraken:BTC/USD","right":"other:ETH/USD","pearson_r":-0.900000000000000001,"pearson_n":25,"coint_t":null,"coint_pvalue":0.049999999999999999,"coint_critical_values":null,"computed_at":null,"sample_count":25,"is_warm":true}]}"""
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
    }
    @After fun cleanup() { repository.close(); server.shutdown() }
    private suspend fun signIn() {
        server.enqueue(response("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=fixture; Path=/; HttpOnly")
            .addHeader("Set-Cookie", "csrf_token=fixture; Path=/"))
        repository.login("viewer", "fixture-password")
        server.takeRequest()
    }

    @Test fun configuredStatsAreExactNullableAndAllowedWithoutWalletOrTradingPermission() = runBlocking {
        signIn()
        server.enqueue(response(profile())); server.enqueue(response(pairPayload, "listed_cached_stats"))
        val result = repository.configuredPairStats()
        assertEquals(1, result.count)
        assertEquals(BigDecimal("-0.900000000000000001"), result.pairs.single().pearsonR)
        assertEquals(BigDecimal("0.049999999999999999"), result.pairs.single().cointPvalue)
        assertNull(result.pairs.single().cointT)
        assertNull(result.pairs.single().cointCriticalValues)
        assertNull(result.pairs.single().computedAt)
        assertEquals("/api/auth/me", server.takeRequest().path)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/market/cache/stats/configured", get.path)
        assertNull(get.getHeader("X-CSRF-Token"))
        assertNull(repository.user.value?.activeWalletPublicId)
    }

    @Test fun allSixChartQueriesPreserveEncodedInstrumentAndProbeLimits() = runBlocking {
        signIn()
        val instrument = "BTC/USD + PERP:EUR"
        for ((timeframe, limit) in listOf("1m" to 100, "5m" to 19, "15m" to 5, "1h" to 25, "4h" to 25, "1d" to 25)) {
            server.enqueue(response(profile()))
            server.enqueue(response("""{"candles":[],"sample_count":0,"is_warm":false,"source":"${MarketDiagnosticsLogic.chartSource(timeframe)}"}""", "cached_candles"))
            repository.chartCache("kraken", instrument, timeframe, limit)
            assertEquals("/api/auth/me", server.takeRequest().path)
            val get = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("/api/candles/cache", get.requestUrl?.encodedPath)
            assertEquals(instrument, get.requestUrl?.queryParameter("instrument"))
            assertEquals("kraken", get.requestUrl?.queryParameter("exchange"))
            assertEquals(timeframe, get.requestUrl?.queryParameter("timeframe"))
            assertEquals(limit.toString(), get.requestUrl?.queryParameter("limit"))
            assertNull(get.requestUrl?.queryParameter("wallet_public_id"))
            assertNull(get.getHeader("X-CSRF-Token"))
        }
    }

    @Test fun freshPermissionRevocationPreventsDiagnosticGet() = runBlocking {
        signIn(); server.enqueue(response(profile(permission = "read:positions")))
        val error = runCatching { repository.configuredPairStats() }.exceptionOrNull()
        assertTrue(error is OrderRejectedException && error.statusCode == 403)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun freshActorOrDeskOrDelegateRebindingPreventsDiagnosticGet() = runBlocking {
        for (changed in listOf(profile(actor = "other"), profile(desk = "other"), profile(delegate = "other"))) {
            signIn()
            val before = server.requestCount
            server.enqueue(response(changed))
            assertTrue(runCatching { repository.chartCache("kraken", "BTC", "1m", 100) }.exceptionOrNull() is OrderScopeChangedException)
            assertEquals("/api/auth/me", server.takeRequest().path)
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun unauthorizedReadRotatesAndRetriesOnceWithSameGet() = runBlocking {
        signIn(); server.enqueue(response(profile()))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile()}}"""))
        server.enqueue(response(pairPayload, "listed_cached_stats"))
        assertEquals(1, repository.configuredPairStats().count)
        assertEquals("/api/auth/me", server.takeRequest().path)
        val first = server.takeRequest()
        val refresh = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("/api/auth/refresh", refresh.path)
        assertEquals("POST", refresh.method)
        assertEquals(first.path, retry.path)
        assertEquals("GET", retry.method)
        assertEquals(5, server.requestCount)
    }

    @Test fun repeatedUnauthorizedReadExpiresSessionWithoutThirdGet() = runBlocking {
        signIn(); server.enqueue(response(profile()))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile()}}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { repository.configuredPairStats() }.exceptionOrNull() is SessionExpiredException)
        assertEquals(5, server.requestCount)
        assertNull(repository.user.value)
    }
}
