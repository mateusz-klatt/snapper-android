package ch.snapper.android.data

import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ActorScopeRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: SessionRepository
    private val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
    @Volatile private var reply: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(418) }
    private fun profile(primary: String = "desk-a", delegate: String = "delegate-a") =
        """{"username":"reader","role":"trader","public_id":"user","active_wallet_public_id":"wallet","primary_operator_public_id":"$primary","delegate_public_id":"$delegate","effective_permissions":["cancel:orders","create:orders","read:market_data"]}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    private val instrument = """[{"instrument_public_id":"instrument","symbol_public_id":"symbol","symbol":"BTC","exchange":"venue","instrument_resolved":true,"can_trade":true}]"""
    private fun order() = OrderDto("order", "client-order", "BTC", "venue", "buy", "open", "limit",
        BigDecimal.ONE, BigDecimal.ZERO, "2026-10-16T00:00:00Z", planPublicId = "plan", walletPublicId = "wallet")

    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.method to request.path
                return reply(request)
            }
        }
        server.start()
        repository = SessionRepository.create(server.url("/").toString(), true, MemoryCookieStorage()) { it == server.hostName }
    }
    @After fun cleanup() { repository.close(); server.shutdown() }

    private suspend fun login(): OrderScope {
        reply = { response("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=fake; Path=/") }
        val user = repository.login("reader", "fake")
        requests.clear()
        return OrderScope.from(user)
    }

    @Test fun cancellationRejectsChangedPrimaryWithIdenticalUserWalletPermissionsAndTarget() = runBlocking {
        rejectCancellation(profile(primary = "desk-b"))
    }

    @Test fun cancellationRejectsChangedDelegateWithIdenticalUserWalletPermissionsAndTarget() = runBlocking {
        rejectCancellation(profile(delegate = "delegate-b"))
    }

    private suspend fun rejectCancellation(changed: String) {
        val original = login()
        reply = { response(changed) }
        assertTrue(runCatching { repository.cancelOrder(original, order()) }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals(listOf("GET" to "/api/auth/me"), requests.toList())
        assertNotEquals(original, OrderScope.from(requireNotNull(repository.user.value)))
    }

    @Test fun instrumentRead401RefreshCannotDispatchConfirmedOrderUnderNewDelegate() = runBlocking {
        val original = login()
        var instrumentReads = 0
        reply = { request -> when (request.path) {
            "/api/auth/me" -> response(profile())
            "/api/auth/refresh" -> response("""{"user":${profile(delegate = "delegate-b")}}""")
            "/api/exchanges/venue/instruments/detail" -> if (++instrumentReads == 1) MockResponse().setResponseCode(401) else response(instrument)
            else -> MockResponse().setResponseCode(418)
        } }
        val payload = CreateOrderPayload("BTC", "instrument", "venue", "paper", "buy", "market", BigDecimal.ONE,
            walletPublicId = "wallet", idempotencyKey = "frozen-intent")
        assertTrue(runCatching { repository.createOrder(original, payload) }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals(listOf("GET" to "/api/auth/me", "GET" to "/api/exchanges/venue/instruments/detail",
            "POST" to "/api/auth/refresh", "GET" to "/api/exchanges/venue/instruments/detail"), requests.toList())
        assertEquals("delegate-b", repository.user.value?.delegatePublicId)
    }

    @Test fun queuedOldScopeIsRejectedAfterNewPrimaryHasCommitted() = runBlocking {
        val original = login()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        reply = {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            response(profile(primary = "desk-b"))
        }
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { repository.restore() }
        withTimeout(5_000) { entered.await() }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { repository.cancelOrder(original, order()) } }
        try {
            release.countDown()
            assertEquals("desk-b", refresh.await()?.primaryOperatorPublicId)
            assertTrue(queued.await().exceptionOrNull() is OrderScopeChangedException)
            assertEquals(listOf("GET" to "/api/auth/me"), requests.toList())
        } finally {
            release.countDown()
        }
    }
}
