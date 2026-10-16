package ch.snapper.android.data

import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PortfolioFeatureRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private val signal = """{"public_id":"s1","instrument":"BTC","exchange":"venue","side":"buy","strength":0.123456789123456789,"reason":"fixture","fired_at":"2026-10-16T00:00:00Z"}"""
    private val account = """{"public_id":"a1","wallet_public_id":"w1","exchange":"venue","mode":"live","sync_status":"observed","effective_status":"stale","is_authoritative":false,"balance_status":"observed","position_status":"unsupported","valuation_status":"native_only","balances":[{"currency":"USD","total":1.2,"total_decimal":"1.200000000000000001"}],"reconciliation":{"effective_status":"incomplete","is_authoritative":false}}"""
    private fun profile(wallet: String? = "w1", permissions: String = "\"read:signals\",\"read:account_state\",\"read:positions\"", actor: String = "u1") =
        """{"username":"viewer","role":"viewer","public_id":"$actor","active_wallet_public_id":${wallet?.let { "\"$it\"" } ?: "null"},"effective_permissions":[$permissions]}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    private val query = PnlSeriesQuery("w1", "paper", "5m", Instant.parse("2026-10-15T01:02:03.123456Z"), Instant.parse("2026-10-16T01:02:03.123456Z"), "PLN")
    private val pnl = """{
      "wallet_public_id":"w1","mode":"paper","granularity":"5m","valuation_ccy":"PLN",
      "from_time":"2026-10-15T01:02:03.123456Z","to_time":"2026-10-16T01:02:03.123456Z","as_of":"2026-10-16T01:02:04Z",
      "mark_source":"fixture","rate_sources":[],"calc_version":"test",
      "equity_coverage":{"sampled":false,"venue_scope":null,"external_flows_adjusted":null,"complete_minutes":0,"first_minute":null,"last_minute":null,"sample_calc_version":null,"valuation_basis":null,"converted_from":null,"conversion_rate_source":null,"conversion_withheld_minutes":0,"drawdown_withheld_reason":null},
      "execution_history":{"status":"complete","corrections":[]},"points":[]
    }"""

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
    }
    private suspend fun signIn(userProfile: String = profile()) {
        server.enqueue(response("""{"user":$userProfile}""").addHeader("Set-Cookie", "access_token=access; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=csrf; Path=/"))
        repository.login("viewer", "password")
        server.takeRequest()
    }
    @After fun cleanup() { repository.close(); server.shutdown() }

    @Test fun signalViewerWithoutWalletUsesFreshPermissionAndUnscopedEndpoint() = runBlocking {
        val user = profile(null, "\"read:signals\"")
        signIn(user)
        server.enqueue(response(user)); server.enqueue(response("[$signal]"))
        assertEquals(BigDecimal("0.123456789123456789"), repository.signals(null).single().strength)
        assertEquals("/api/auth/me", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("/api/signals", request.path)
        assertEquals("GET", request.method)
        assertNull(request.getHeader("X-CSRF-Token"))
    }

    @Test fun venueAccountsPreserveExactBalanceProvenanceAndWalletQuery() = runBlocking {
        signIn()
        server.enqueue(response(profile())); server.enqueue(response("[$account]"))
        val result = repository.venueAccounts("w1").single()
        assertFalse(result.isAuthoritative)
        assertEquals("1.200000000000000001", result.balances?.single()?.totalDecimal)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/portfolio/accounts", request.requestUrl?.encodedPath)
        assertEquals("w1", request.requestUrl?.queryParameter("wallet_public_id"))
    }

    @Test fun revokedSignalsPermissionStopsBeforeFeatureGet() = runBlocking {
        signIn()
        server.enqueue(response(profile(permissions = "\"read:market_data\"")))
        val failure = runCatching { repository.signals("w1") }.exceptionOrNull()
        assertTrue(failure is OrderRejectedException && failure.statusCode == 403)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun accountReadRequiresAccountCapabilityNotPositionPermission() = runBlocking {
        val user = profile(permissions = "\"read:positions\"")
        signIn(user); server.enqueue(response(user))
        val failure = runCatching { repository.venueAccounts("w1") }.exceptionOrNull()
        assertTrue(failure is OrderRejectedException && failure.statusCode == 403)
        assertEquals(2, server.requestCount)
    }

    @Test fun walletChangedByFreshProfileRefusesCapturedQuery() = runBlocking {
        signIn(); server.enqueue(response(profile(wallet = "w2")))
        assertTrue(runCatching { repository.pnlSeries(query) }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun actorChangedBeforeReadDoesNotDispatchAnotherActorsQuery() = runBlocking {
        signIn(); server.enqueue(response(profile(actor = "u2")))
        server.enqueue(response("[$signal]"))
        assertTrue(runCatching { repository.signals("w1") }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals(2, server.requestCount)
    }

    @Test fun pnlValidatesWalletModeThenPreservesExactWindowAndValuationParameters() = runBlocking {
        signIn(); server.enqueue(response(profile()))
        server.enqueue(response("""[{"public_id":"w1","label":"Paper","is_paper":true}]"""))
        server.enqueue(response(pnl))
        assertEquals("PLN", repository.pnlSeries(query).valuationCcy)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/wallets", server.takeRequest().path)
        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("/api/portfolio/pnl/series", url.encodedPath)
        assertEquals("w1", url.queryParameter("wallet_public_id"))
        assertEquals("paper", url.queryParameter("mode"))
        assertEquals("5m", url.queryParameter("granularity"))
        assertEquals("PLN", url.queryParameter("valuation_ccy"))
        assertEquals(query.from.toString(), url.queryParameter("from"))
        assertEquals(query.to.toString(), url.queryParameter("to"))
        assertNull(request.getHeader("X-CSRF-Token"))
    }

    @Test fun changedWalletModeStopsBeforePnlRequest() = runBlocking {
        signIn(); server.enqueue(response(profile()))
        server.enqueue(response("""[{"public_id":"w1","label":"Live","is_paper":false}]"""))
        assertTrue(runCatching { repository.pnlSeries(query) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/wallets", server.takeRequest().path)
        assertEquals(3, server.requestCount)
    }

    @Test fun malformedAccountDoesNotBecomeEmptyOrAuthoritative() = runBlocking {
        signIn(); server.enqueue(response(profile())); server.enqueue(response("[{\"public_id\":\"a1\"}]"))
        assertTrue(runCatching { repository.venueAccounts("w1") }.exceptionOrNull() is SerializationException)
        assertEquals(3, server.requestCount)
    }

    @Test fun logoutDiscardsDelayedSignalRead() = runBlocking {
        signIn(); server.enqueue(response(profile()))
        server.enqueue(response("[$signal]").setBodyDelay(2, TimeUnit.SECONDS))
        val pending = async { runCatching { repository.signals("w1") } }
        withContext(Dispatchers.IO) { repeat(2) { assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null) } }
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        assertFalse(pending.await().isSuccess)
        assertNull(repository.user.value)
    }
}
