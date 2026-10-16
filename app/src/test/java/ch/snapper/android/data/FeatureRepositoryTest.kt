package ch.snapper.android.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeatureRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private val profile = """{"username":"viewer","role":"viewer","public_id":"u1","active_wallet_public_id":null,"effective_permissions":["read:market_data","manage:desk_memberships"]}"""
    private val desk = """{"public_id":"desk/a","label":"Desk A","description":null}"""
    private val instrument = """{"instrument_public_id":"i1","symbol_public_id":"s1","symbol":"BTC/USD","exchange":"kraken","can_trade":false,"instrument_resolved":true}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    private fun scope() = OrderScope.from(requireNotNull(repository.user.value))

    @Before fun setup() = runBlocking {
        server = MockWebServer()
        server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
        server.enqueue(response("""{"user":$profile}""").addHeader("Set-Cookie", "access_token=access; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=csrf; Path=/"))
        repository.login("viewer", "password")
        server.takeRequest()
        Unit
    }

    @After fun cleanup() {
        repository.close()
        server.shutdown()
    }

    @Test fun viewerBrowsesMarketWithoutWalletOrOrderPermission() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[\"kraken\"]"))
        assertEquals(listOf("kraken"), repository.marketExchanges())
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/exchanges", server.takeRequest().path)
        server.enqueue(response(profile))
        server.enqueue(response("[$instrument]"))
        val row = repository.marketInstruments("kraken").single()
        assertFalse(row.canTrade)
        assertTrue(row.instrumentResolved)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/exchanges/kraken/instruments/detail", server.takeRequest().path)
    }

    @Test fun revokedReadPermissionPreventsCatalogueDispatch() = runBlocking {
        server.enqueue(response(profile.replace("read:market_data", "read:orders")))
        assertTrue(runCatching { repository.marketExchanges() }.isFailure)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun desksRequireAuthenticationButNoManagementPermission() = runBlocking {
        server.enqueue(response(profile.replace("manage:desk_memberships", "read:orders")))
        server.enqueue(response("[$desk]"))
        assertEquals("desk/a", repository.desks().single().publicId)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/operators", server.takeRequest().path)
    }

    @Test fun attachmentUsesEncodedSegmentsAndEmptyBodyWithCsrf() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[$desk]"))
        server.enqueue(response("\"Viewer attached\""))
        repository.attachViewer(scope(), "desk/a", "viewer/name")
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/operators", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/desks/desk%2Fa/members/viewer%2Fname", request.path)
        assertEquals(0L, request.bodySize)
        assertEquals("csrf", request.getHeader("X-CSRF-Token"))
    }

    @Test fun attachment503DoesNotRefreshOrReplay() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[$desk]"))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(204))
        val failure = runCatching { repository.attachViewer(scope(), "desk/a", "viewer") }.exceptionOrNull()
        assertTrue(failure != null && failure !is DeskRejectedException)
        assertEquals(4, server.requestCount)
    }

    @Test fun attachment401IsKnownRejectionAndDoesNotReplay() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[$desk]"))
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { repository.attachViewer(scope(), "desk/a", "viewer") }.exceptionOrNull()
        assertTrue(failure is DeskRejectedException && failure.statusCode == 401)
        assertEquals(4, server.requestCount)
        assertNull(repository.user.value)
    }

    @Test fun foreignDeskFailsBeforePost() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[$desk]"))
        assertTrue(runCatching { repository.attachViewer(scope(), "foreign", "viewer") }.isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test fun changedIdentityFailsBeforeDeskLookupOrPost() = runBlocking {
        server.enqueue(response(profile.replace("u1", "u2")))
        assertTrue(runCatching { repository.attachViewer(scope(), "desk/a", "viewer") }.isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test fun systemReadChecksFreshPermissionWithoutWallet() = runBlocking {
        val allowed = profile.replace("read:market_data", "read:strategies")
        server.enqueue(response(allowed))
        server.enqueue(response("[]"))
        assertTrue(repository.systemStrategies().isEmpty())
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/strategies", server.takeRequest().path)
        server.enqueue(response(profile))
        assertTrue(runCatching { repository.systemStrategies() }.isFailure)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(4, server.requestCount)
    }

    @Test fun logoutInvalidatesPendingCatalogueRead() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[\"kraken\"]").setBodyDelay(2, TimeUnit.SECONDS))
        val pending = async { runCatching { repository.marketExchanges() } }
        withContext(Dispatchers.IO) {
            assertEquals("/api/auth/me", server.takeRequest(3, TimeUnit.SECONDS)?.path)
            assertEquals("/api/exchanges", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        }
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        assertFalse(pending.await().isSuccess)
        assertNull(repository.user.value)
    }
}
