package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MarketContextRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private fun profile(permission: String = "read:market_data", wallet: String = "w1") =
        """{"username":"fixture","public_id":"u1","role":"viewer","active_wallet_public_id":"$wallet","effective_permissions":["$permission"]}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    @Before fun setup() {
        server = MockWebServer(); server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
    }
    @After fun cleanup() { repository.close(); server.shutdown() }
    private suspend fun login() {
        server.enqueue(response("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=fixture; Path=/; HttpOnly"))
        repository.login("fixture", "password"); server.takeRequest()
    }

    @Test fun cacheQueryIsHourlyTwentyFiveAndPreservesExactDecimal() = runBlocking {
        login(); server.enqueue(response(profile()))
        server.enqueue(response("""{"candles":[{"open_at_ms":1000,"timeframe":"1h","open":1.123456789123456789,"high":2,"low":1,"close":2,"volume":0}],"sample_count":1,"is_warm":false,"source":"cache"}"""))
        val result = repository.cachedHourly("venue", "BTC USD")
        assertEquals(BigDecimal("1.123456789123456789"), result.candles.single().open)
        assertFalse(result.isWarm); assertEquals("cache", result.source)
        server.takeRequest(); val query = server.takeRequest().requestUrl!!
        assertEquals("/api/candles/cache", query.encodedPath)
        assertEquals("venue", query.queryParameter("exchange")); assertEquals("BTC USD", query.queryParameter("instrument"))
        assertEquals("1h", query.queryParameter("timeframe")); assertEquals("25", query.queryParameter("limit"))
    }

    @Test fun relatedUsesEncodedPathSegmentsAndNullableUnderlying() = runBlocking {
        login(); server.enqueue(response(profile()))
        server.enqueue(response("""{"selected":{"exchange":"venue","native_symbol":"BTC/USD"},"underlying":null,"groups":[]}"""))
        val result = repository.related("venue", "BTC/USD")
        assertEquals("BTC/USD", result.selected.nativeSymbol); assertNull(result.underlying)
        server.takeRequest(); val request = server.takeRequest()
        assertEquals("/api/instruments/venue/BTC%2FUSD/related", request.path)
        assertEquals("GET", request.method); assertNull(request.getHeader("X-CSRF-Token"))
    }

    @Test fun permissionRevokedPreventsContextRead() = runBlocking {
        login(); server.enqueue(response(profile(permission = "read:signals")))
        assertTrue(runCatching { repository.cachedHourly("venue", "BTC") }.exceptionOrNull() is OrderRejectedException)
        assertEquals(2, server.requestCount)
    }

    @Test fun walletChangeBeforeRelatedReadRejectsCapturedScope() = runBlocking {
        login(); server.enqueue(response(profile(wallet = "w2")))
        assertTrue(runCatching { repository.related("venue", "BTC") }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals(2, server.requestCount)
    }
}
