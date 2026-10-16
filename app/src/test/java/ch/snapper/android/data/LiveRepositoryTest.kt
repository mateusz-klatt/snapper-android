package ch.snapper.android.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LiveRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private fun profile(wallet: String = "w1", delegate: String = "d1", operator: String = "o1") =
        """{"username":"fixture","public_id":"u1","role":"viewer","active_wallet_public_id":"$wallet","delegate_public_id":"$delegate","primary_operator_public_id":"$operator","effective_permissions":["read:market_data"]}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    private fun ticket(value: String = "one-use") = response("""{"ws_token":"$value","ws_token_exp":"2099-01-01T00:00:00Z","expires_in":100}""")
    @Before fun setup() {
        server = MockWebServer(); server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
    }
    @After fun cleanup() { repository.close(); server.shutdown() }
    private suspend fun login() {
        server.enqueue(response("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=first; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=csrf; Path=/"))
        repository.login("fixture", "password"); server.takeRequest()
    }

    @Test fun eachTicketMintUsesExactBodylessRouteAndFreshProfile() = runBlocking {
        login()
        repeat(2) { index ->
            server.enqueue(response(profile())); server.enqueue(ticket("ticket-$index"))
            assertEquals("ticket-$index", repository.liveTicket().credential())
            assertEquals("/api/auth/me", server.takeRequest().path)
            val mint = server.takeRequest()
            assertEquals("/api/auth/ws_token", mint.path); assertEquals("POST", mint.method)
            assertEquals(0L, mint.bodySize); assertEquals("csrf", mint.getHeader("X-CSRF-Token"))
            assertTrue(mint.getHeader("Cookie").orEmpty().contains("access_token=first"))
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun mint401RefreshesOnceAndUsesRotatedCookieWithoutReusingTicket() = runBlocking {
        login(); server.enqueue(response(profile())); server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=rotated; Path=/; HttpOnly"))
        server.enqueue(ticket("new-ticket"))
        assertEquals("new-ticket", repository.liveTicket().credential())
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/auth/ws_token", server.takeRequest().path)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        val retry = server.takeRequest()
        assertEquals("/api/auth/ws_token", retry.path)
        assertTrue(retry.getHeader("Cookie").orEmpty().contains("access_token=rotated"))
    }

    @Test fun walletDelegateOrOperatorChangeInFreshMePreventsMint() = runBlocking {
        for (changed in listOf(profile(wallet = "w2"), profile(delegate = "d2"), profile(operator = "o2"))) {
            login(); server.enqueue(response(changed))
            assertTrue(runCatching { repository.liveTicket() }.exceptionOrNull() is OrderScopeChangedException)
            assertEquals("/api/auth/me", server.takeRequest().path)
            assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun operatorChangeDuring401RefreshPreventsSecondMint() = runBlocking {
        login(); server.enqueue(response(profile())); server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile(operator = "o2")}}"""))
        assertTrue(runCatching { repository.liveTicket() }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals(4, server.requestCount)
    }

    @Test fun secondUnauthorizedMintExpiresSession() = runBlocking {
        login(); server.enqueue(response(profile())); server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile()}}""")); server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { repository.liveTicket() }.exceptionOrNull() is SessionExpiredException)
        assertNull(repository.user.value); assertEquals(5, server.requestCount)
    }

    @Test fun serverFailureDoesNotTransportRetryMint() = runBlocking {
        login(); server.enqueue(response(profile()))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(ticket("must-not-consume"))
        assertTrue(runCatching { repository.liveTicket() }.isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test fun logoutDuringMintDiscardsCredential() = runBlocking {
        login(); server.enqueue(response(profile())); server.enqueue(ticket().setBodyDelay(2, TimeUnit.SECONDS))
        val result = async(Dispatchers.IO) { runCatching { repository.liveTicket() } }
        withContext(Dispatchers.IO) { repeat(2) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) } }
        server.enqueue(MockResponse().setResponseCode(204)); repository.logout()
        assertTrue(result.await().isFailure); assertNull(repository.user.value)
    }

    @Test fun concurrentReadRefreshCompletesBeforeTicketPreflightAndUsesNewCookie() = runBlocking {
        login()
        server.enqueue(response(profile()))
        server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(200, TimeUnit.MILLISECONDS))
        server.enqueue(response("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=rotated; Path=/; HttpOnly"))
        server.enqueue(response("""{"candles":[],"sample_count":0,"is_warm":false,"source":"cache"}"""))
        server.enqueue(response(profile())); server.enqueue(ticket())
        val read = async(Dispatchers.IO) { repository.cachedHourly("venue", "BTC") }
        withContext(Dispatchers.IO) { assertEquals("/api/auth/me", server.takeRequest(2, TimeUnit.SECONDS)?.path) }
        val mint = async(Dispatchers.IO) { repository.liveTicket() }
        assertEquals(0, read.await().sampleCount); assertEquals("one-use", mint.await().credential())
        assertTrue(server.takeRequest().path.orEmpty().startsWith("/api/candles/cache?"))
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertTrue(server.takeRequest().path.orEmpty().startsWith("/api/candles/cache?"))
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("access_token=rotated"))
    }

    @Test fun sessionTransportUsesCanonicalCookieAuthenticatedUpgradeAndLogoutInvalidatesIt() = runBlocking {
        login()
        val delivered = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("fixture") }
        }))
        val transport = repository.liveTransport()
        val observer = object : LiveSocketObserver {
            override fun frame(bytes: ByteArray) { delivered.complete(Unit) }
            override fun closed() = Unit
        }
        val socket = transport.connect(observer)
        withTimeout(5_000) { delivered.await() }
        val upgrade = server.takeRequest()
        assertEquals("/api/ws", upgrade.path)
        assertNull(upgrade.requestUrl?.query)
        assertTrue(upgrade.getHeader("Cookie").orEmpty().contains("access_token=first"))
        server.enqueue(MockResponse().setResponseCode(204)); repository.logout()
        assertFalse(socket.send("discard"))
        assertTrue(runCatching { transport.connect(observer) }.exceptionOrNull() is SessionExpiredException)
    }

    @Test fun upgradedSocketRegistryCancelsEverySocketAndRejectsStaleTransport() {
        val registry = SessionLiveSockets()
        var current = true; var cancelled = 0; var delivered = 0
        lateinit var listener: LiveSocketObserver
        val raw = LiveSocketTransport { observer ->
            listener = observer
            object : LiveSocket {
                override fun send(text: String): Boolean = true
                override fun cancel() { cancelled++ }
            }
        }
        val transport = registry.transport(raw) { current }
        val socket = transport.connect(object : LiveSocketObserver {
            override fun frame(bytes: ByteArray) { delivered++ }
            override fun closed() = Unit
        })
        listener.frame(byteArrayOf(1)); assertEquals(1, delivered)
        current = false; registry.cancelAll(); assertEquals(1, cancelled)
        listener.frame(byteArrayOf(2)); assertEquals(1, delivered)
        assertFalse(socket.send("discard"))
        assertTrue(runCatching { transport.connect(listener) }.exceptionOrNull() is SessionExpiredException)
    }
}
