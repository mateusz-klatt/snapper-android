package ch.snapper.android.data

import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OrderRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private val profile = """{"username":"trader","role":"trader","public_id":"u1","active_wallet_public_id":"w1","effective_permissions":["create:orders","cancel:orders","read:market_data"]}"""
    private val instrument = """{"instrument_public_id":"i1","symbol_public_id":"s1","symbol":"BTC/USD","exchange":"kraken","can_trade":true,"instrument_resolved":true}"""
    private val plan = """{"public_id":"p1","status":"active","wallet_public_id":"w1"}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    private fun payload() = CreateOrderPayload("BTC/USD", "i1", "kraken", "paper", "buy", "limit", BigDecimal("0.123456789123456789"), BigDecimal("123.456789123456789"), walletPublicId = "w1", idempotencyKey = "stable-key")
    private fun order() = OrderDto("o1", "c1", "BTC/USD", "kraken", "buy", "open", "limit", BigDecimal.ONE, BigDecimal.ZERO, "2026-10-15T00:00:00Z", planPublicId = "p1", walletPublicId = "w1")
    private fun scope() = OrderScope.from(requireNotNull(repository.user.value))
    private fun preflight() {
        server.enqueue(response(profile))
        server.enqueue(response("[$instrument]"))
    }
    @Before fun setup() = runBlocking {
        server = MockWebServer()
        server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
        server.enqueue(response("""{"user":$profile}""").addHeader("Set-Cookie", "access_token=access; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=csrf; Path=/"))
        repository.login("trader", "password")
        server.takeRequest()
        Unit
    }
    @After fun cleanup() {
        repository.close()
        server.shutdown()
    }
    @Test fun createUsesExactNumericTokensAndEnvelopeWithCsrf() = runBlocking {
        preflight()
        server.enqueue(response(plan))
        assertEquals("p1", repository.createOrder(scope(), payload()).publicId)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/exchanges/kraken/instruments/detail", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/orders", request.path)
        assertEquals("csrf", request.getHeader("X-CSRF-Token"))
        val body = request.body.readUtf8()
        val sent = Json.parseToJsonElement(body).jsonObject.getValue("payload").jsonObject
        assertEquals(body, "0.123456789123456789", sent.getValue("quantity").jsonPrimitive.content)
        assertEquals(body, "123.456789123456789", sent.getValue("price").jsonPrimitive.content)
        assertFalse(body, sent.getValue("quantity").jsonPrimitive.isString)
        assertFalse(body, sent.getValue("price").jsonPrimitive.isString)
        assertTrue(body.contains("\"type\":\"create_order_command\""))
        assertTrue(body.contains("\"idempotency_key\":\"stable-key\""))
        assertTrue(body.contains("\"sequence_id\":"))
    }
    @Test fun cataloguesUseAuthenticatedCapabilityEndpoint() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[\"kraken\"]"))
        assertEquals(listOf("kraken"), repository.orderExchanges(scope()))
        server.takeRequest()
        assertEquals("/api/exchanges", server.takeRequest().path)
        preflight()
        val found = repository.orderInstruments(scope(), "kraken").single()
        assertTrue(found.canTrade && found.instrumentResolved)
        assertEquals("i1", found.instrumentPublicId)
        server.takeRequest()
        assertEquals("/api/exchanges/kraken/instruments/detail", server.takeRequest().path)
    }
    @Test fun missingPermissionRefusesWithoutDispatch() = runBlocking {
        server.enqueue(response("""{"user":${profile.replace("create:orders", "read:orders")}}"""))
        repository.selectWallet("w1")
        assertTrue(runCatching { repository.createOrder(scope(), payload()) }.isFailure)
        assertEquals(2, server.requestCount)
    }
    @Test fun queuedWalletRotationInvalidatesCapturedOrderScope() = runBlocking {
        val expected = scope()
        server.enqueue(response("""{"user":${profile.replace("w1", "w2")}}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val rotation = async { repository.selectWallet("w2") }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertEquals("/api/auth/refresh", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        }
        val pending = async { runCatching { repository.cancelOrder(expected, order()) } }
        rotation.await()
        assertTrue(pending.await().exceptionOrNull() is OrderScopeChangedException)
        assertEquals(2, server.requestCount)
    }
    @Test fun cancellationConflictIsKnownRejectionWithoutReplay() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(MockResponse().setResponseCode(409))
        val failure = runCatching { repository.cancelOrder(scope(), order()) }.exceptionOrNull()
        assertTrue(failure is OrderRejectedException && failure.statusCode == 409)
        assertEquals(3, server.requestCount)
    }
    @Test fun cancelUsesPlanIdentityAndReturnsRequestedState() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response(plan.replace("active", "cancel_requested")))
        assertEquals("cancel_requested", repository.cancelOrder(scope(), order()).status)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/orders/p1/cancel", request.path)
        assertTrue(request.body.readUtf8().contains("\"type\":\"cancel_order_command\""))
    }
    @Test fun mutation401NeverRefreshesOrReplays() = runBlocking {
        preflight()
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { repository.createOrder(scope(), payload()) }.exceptionOrNull()
        assertTrue(failure is OrderRejectedException && failure.statusCode == 401)
        assertNull(repository.user.value)
        assertEquals(4, server.requestCount)
    }
    @Test fun retryAfterZero503DoesNotReplayMutation() = runBlocking {
        preflight()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(response(plan))
        assertTrue(runCatching { repository.createOrder(scope(), payload()) }.exceptionOrNull() is OrderOutcomeUnknownException)
        assertEquals(4, server.requestCount)
    }
    @Test fun disconnectAfterAcceptanceDoesNotReplayMutation() = runBlocking {
        preflight()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(response(plan))
        assertTrue(runCatching { repository.createOrder(scope(), payload()) }.exceptionOrNull() is OrderOutcomeUnknownException)
        assertEquals(4, server.requestCount)
    }
    @Test fun malformedSuccessIsUnknown() = runBlocking {
        preflight()
        server.enqueue(response("{}"))
        assertTrue(runCatching { repository.createOrder(scope(), payload()) }.exceptionOrNull() is OrderOutcomeUnknownException)
    }
    @Test fun serverRejectionPreservesStructuredCode() = runBlocking {
        preflight()
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":{"error_code":"caps_violation"}}"""))
        val failure = runCatching { repository.createOrder(scope(), payload()) }.exceptionOrNull()
        assertTrue(failure is OrderRejectedException && failure.errorCode == "caps_violation")
    }
    @Test fun changedServerScopeRefusesBeforeDispatch() = runBlocking {
        server.enqueue(response(profile.replace("w1", "w2")))
        assertTrue(runCatching { repository.createOrder(scope(), payload()) }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals(2, server.requestCount)
    }
    @Test fun unresolvedInstrumentRefusesBeforeDispatch() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[${instrument.replace("\"instrument_resolved\":true", "\"instrument_resolved\":false")}]"))
        assertTrue(runCatching { repository.createOrder(scope(), payload()) }.isFailure)
        assertEquals(3, server.requestCount)
    }
    @Test fun cancelRefusesTerminalMissingPlanAndForeignWalletWithoutNetwork() = runBlocking {
        for (candidate in listOf(order().copy(status = "filled"), order().copy(planPublicId = null), order().copy(walletPublicId = "w2"))) {
            assertTrue(runCatching { repository.cancelOrder(scope(), candidate) }.isFailure)
        }
        assertEquals(1, server.requestCount)
    }
    @Test fun logoutCancelsInFlightMutationWithoutReturningAcceptance() = runBlocking {
        preflight()
        server.enqueue(response(plan).setBodyDelay(2, TimeUnit.SECONDS))
        val expected = scope()
        val pending = async { runCatching { repository.createOrder(expected, payload()) } }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repeat(3) { assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null) }
        }
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        assertFalse(pending.await().isSuccess)
        assertNull(repository.user.value)
    }
}
