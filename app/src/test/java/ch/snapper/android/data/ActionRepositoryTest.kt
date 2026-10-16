package ch.snapper.android.data

import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ActionRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private val permissions = "\"manage:processes\",\"read:processes\",\"read:system_status\",\"read:positions\",\"create:orders\",\"read:market_data\",\"read:signals\",\"read:ai_reviews\",\"submit:ai_review_decision\""
    private fun profile(wallet: String = "w1", actor: String = "u1", delegate: String = "d1", caps: String = permissions) =
        """{"username":"fixture","public_id":"$actor","role":"viewer","active_wallet_public_id":"$wallet","delegate_public_id":"$delegate","effective_permissions":[$caps]}"""
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun envelope(payload: String) = response("""{"payload":$payload}""")
    private fun configured(remote: Boolean = false, coordinator: String = "api", kind: String = "instance") =
        """{"name":"feed","enabled":true,"running":true,"role":"feed","kind":"$kind","managed_remotely":$remote,"coordinator":"$coordinator"}"""
    private fun target(remote: Boolean = false) = ConfiguredProcessDto("feed", true, true, "feed", "instance", remote, "api")
    private val position = """{"public_id":"p1","instrument_public_id":"i1","instrument":"BTC","exchange":"venue","wallet_public_id":"w1","mode":"paper","position_cycle_public_id":"c1","quantity":2,"average_price":100,"realized_pnl":0}"""
    private val instrument = """{"instrument_public_id":"i1","symbol_public_id":"s1","symbol":"BTC","exchange":"venue","can_trade":true,"instrument_resolved":true}"""
    private val pending = """{"review_public_id":"r1","selected_delegate_public_id":"d1","wallet_public_id":"w1","dispatch_version":1,"status":"pending","deadline":"2099-01-01T00:00:00Z","fanout_after":"2020-01-01T00:00:00Z"}"""
    private val actionTarget = PositionActionTarget("p1", "i1", "BTC", "venue", "c1", BigDecimal(2), BigDecimal(100), "w1")
    private fun order() = PositionActionIntent.Order(actionTarget, CreateOrderPayload("BTC", "i1", "venue", "paper", "sell", "market", BigDecimal("1.123456789123456789"), walletPublicId = "w1", idempotencyKey = "stable-key", reduceOnly = true))
    private fun scope() = OrderScope.from(requireNotNull(repository.user.value))

    @Before fun setup() {
        server = MockWebServer(); server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
    }
    @After fun cleanup() { repository.close(); server.shutdown() }
    private suspend fun login() {
        server.enqueue(envelope("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=fixture; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=csrf; Path=/"))
        repository.login("fixture", "password"); server.takeRequest()
    }
    private fun processReads(row: String = configured()) {
        server.enqueue(envelope(profile())); server.enqueue(envelope("[$row]"))
    }
    private fun positionReads(row: String = position, capability: String? = instrument) {
        server.enqueue(envelope(profile()))
        server.enqueue(envelope("""[{"public_id":"w1","label":"Paper","is_paper":true}]"""))
        server.enqueue(envelope("[$row]"))
        if (capability != null) server.enqueue(envelope("[$capability]"))
    }
    private fun decisionReads() {
        server.enqueue(envelope(profile())); server.enqueue(response("""{"items":[$pending],"count":1}"""))
    }
    private fun drain(count: Int) { repeat(count) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) } }

    @Test fun processStartUsesCommandEnvelopeAndCsrf() = runBlocking {
        login(); processReads()
        server.enqueue(envelope("""{"name":"feed","status":"success","process_public_id":"run1"}"""))
        assertEquals("run1", repository.startProcess(scope(), target()).processPublicId)
        drain(2)
        val request = server.takeRequest()
        assertEquals("/api/processes/feed/start", request.path)
        assertEquals("POST", request.method)
        assertEquals("csrf", request.getHeader("X-CSRF-Token"))
        assertEquals("process_start_request", Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun processOwnershipChangeStopsBeforeDispatch() = runBlocking {
        login(); processReads(configured(true, "remote"))
        assertTrue(runCatching { repository.stopProcess(scope(), target()) }.isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test fun processTemplateCannotBeStarted() = runBlocking {
        login(); processReads(configured(kind = "template"))
        assertTrue(runCatching { repository.startProcess(scope(), target()) }.isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test fun processStop503WithImmediateRetryHeaderIsNeverRepeated() = runBlocking {
        login(); processReads()
        server.enqueue(response("{}").setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(envelope("""{"name":"feed","status":"success"}"""))
        assertTrue(runCatching { repository.stopProcess(scope(), target()) }.exceptionOrNull() is ProcessOutcomeUnknownException)
        drain(2); assertEquals("POST", server.takeRequest().method)
        assertEquals(4, server.requestCount)
    }

    @Test fun processStopDecodesSuccessAndOperationalFailureWithoutReplay() = runBlocking {
        login()
        for (status in listOf("success", "not_running", "error")) {
            processReads()
            server.enqueue(envelope("""{"name":"feed","status":"$status","message":"operational result"}"""))
            val result = repository.stopProcess(scope(), target())
            assertEquals(status, result.status)
            assertEquals("operational result", result.message)
            drain(2)
            val request = server.takeRequest()
            assertEquals("/api/processes/feed/stop", request.path)
            assertEquals("POST", request.method)
            assertEquals("csrf", request.getHeader("X-CSRF-Token"))
        }
        assertEquals(10, server.requestCount)
    }

    @Test fun processStopMismatchedNameAndUnsupportedStatusRemainUnknown() = runBlocking {
        login()
        val receipts = listOf("""{"name":"different","status":"success"}""", """{"name":"feed","status":"queued"}""")
        for (receipt in receipts) {
            processReads()
            server.enqueue(envelope(receipt))
            val failure = runCatching { repository.stopProcess(scope(), target()) }.exceptionOrNull()
            assertTrue(failure is ProcessOutcomeUnknownException)
            drain(3)
        }
        assertEquals(7, server.requestCount)
    }

    @Test fun processStopLostResponseNeverRetriesTheMutation() = runBlocking {
        login(); processReads()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(envelope("""{"name":"feed","status":"success"}"""))
        val failure = runCatching { repository.stopProcess(scope(), target()) }.exceptionOrNull()
        assertTrue(failure is ProcessOutcomeUnknownException)
        drain(2)
        assertEquals("/api/processes/feed/stop", server.takeRequest().path)
        assertEquals(4, server.requestCount)
    }

    @Test fun remoteRestartSendsSameNonceAndPersistedIntent() = runBlocking {
        login(); processReads(configured(true))
        server.enqueue(envelope("""{"name":"feed","status":"success","action":"restart","managed_remotely":true,"coordinator":"api"}"""))
        repository.processDesiredState(scope(), target(true), ProcessDesiredStatePayload("restart", "stable-nonce"))
        drain(2)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("process_desired_state_request", body.getValue("type").jsonPrimitive.content)
        assertEquals("stable-nonce", body.getValue("payload").jsonObject.getValue("restart_nonce").jsonPrimitive.content)
    }

    @Test fun desiredStateSuccessIsAcceptedWhenOwnershipChangedAfterCommit() = runBlocking {
        login(); processReads(configured(true))
        server.enqueue(envelope("""{"name":"feed","status":"success","action":"restart","managed_remotely":false,"coordinator":"api"}"""))
        val result = repository.processDesiredState(scope(), target(true), ProcessDesiredStatePayload("restart", "stable-nonce"))
        assertEquals("success", result.status)
        assertFalse(result.managedRemotely)
        assertEquals(4, server.requestCount)
    }

    @Test fun revokedCommandPermissionPreventsTargetRead() = runBlocking {
        login(); server.enqueue(envelope(profile(caps = "\"read:processes\"")))
        assertTrue(runCatching { repository.stopProcess(scope(), target()) }.isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test fun positionOrderPreservesExactNumericTokensAndFrozenKey() = runBlocking {
        login(); positionReads()
        server.enqueue(envelope("""{"public_id":"plan1","status":"pending","wallet_public_id":"w1"}"""))
        assertEquals("plan1", repository.submit(scope(), order()).publicId)
        drain(4)
        val request = server.takeRequest()
        assertEquals("/api/orders", request.path)
        assertEquals("csrf", request.getHeader("X-CSRF-Token"))
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("payload").jsonObject
        assertEquals("1.123456789123456789", payload.getValue("quantity").jsonPrimitive.content)
        assertFalse(payload.getValue("quantity").jsonPrimitive.isString)
        assertEquals("stable-key", payload.getValue("idempotency_key").jsonPrimitive.content)
        assertEquals("true", payload.getValue("reduce_only").jsonPrimitive.content)
    }

    @Test fun stalePositionIdentityCycleQuantityAndModeEachPreventDispatch() = runBlocking {
        login()
        val changed = listOf(position.replace("\"i1\"", "\"i2\""), position.replace("\"c1\"", "\"c2\""), position.replace("\"quantity\":2", "\"quantity\":-2"), position.replace("\"paper\"", "\"live\""))
        changed.forEach { row ->
            positionReads(row, null)
            assertTrue(runCatching { repository.submit(scope(), order()) }.exceptionOrNull() is PositionPreparationException)
            drain(3)
        }
        assertEquals(13, server.requestCount)
    }

    @Test fun positionReadOnlyInstrumentPreventsDispatch() = runBlocking {
        login(); positionReads(capability = instrument.replace("\"can_trade\":true", "\"can_trade\":false"))
        assertTrue(runCatching { repository.submit(scope(), order()) }.exceptionOrNull() is PositionPreparationException)
        assertEquals(5, server.requestCount)
    }

    @Test fun positionWrongSidePreventsCapabilityReadAndCommand() = runBlocking {
        login(); positionReads(capability = null)
        val intent = order().let { it.copy(payload = it.payload.copy(side = "buy")) }
        assertTrue(runCatching { repository.submit(scope(), intent) }.exceptionOrNull() is PositionPreparationException)
        assertEquals(4, server.requestCount)
    }

    @Test fun positionDuplicate409RemainsUnknown() = runBlocking {
        login(); positionReads(); server.enqueue(response("{}").setResponseCode(409))
        assertTrue(runCatching { repository.submit(scope(), order()) }.exceptionOrNull() is OrderOutcomeUnknownException)
        assertEquals(6, server.requestCount)
    }

    @Test fun position401ExpiresSessionWithoutMutationReplay() = runBlocking {
        login(); positionReads(); server.enqueue(response("{}").setResponseCode(401))
        assertTrue(runCatching { repository.submit(scope(), order()) }.exceptionOrNull() is PositionRejectedException)
        assertNull(repository.user.value); assertEquals(6, server.requestCount)
    }

    @Test fun bracketAndTrailingUseDistinctEnvelopesAndExactNumbers() = runBlocking {
        login()
        val intents = listOf(
            PositionActionIntent.Bracket(actionTarget, PositionBracketPayload("c1", BigDecimal("90.123456789123456789"), idempotencyKey = "bracket-key")),
            PositionActionIntent.Trailing(actionTarget, PositionTrailingPayload("c1", BigDecimal("1.123456789123456789"), BigDecimal.ZERO, "trailing-key")),
        )
        val paths = listOf("/api/execution-plans", "/api/trailing-stops")
        val types = listOf("create_bracket_command", "create_trailing_stop_command")
        intents.forEachIndexed { index, intent ->
            positionReads(); server.enqueue(envelope("""{"public_id":"plan1","status":"pending","wallet_public_id":"w1"}"""))
            repository.submit(scope(), intent); drain(4)
            val request = server.takeRequest(); assertEquals(paths[index], request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(types[index], body.getValue("type").jsonPrimitive.content)
            assertEquals("c1", body.getValue("payload").jsonObject.getValue("position_cycle_public_id").jsonPrimitive.content)
        }
    }

    @Test fun aiCanonical409IsDecodedFromDetailWithoutReplay() = runBlocking {
        login(); decisionReads()
        server.enqueue(response("""{"detail":{"success":false,"error_code":"not_selected_before_fanout","message":"Not selected","details":{}}}""").setResponseCode(409))
        assertEquals("not_selected_before_fanout", repository.submitDecision(scope(), "r1", AiDecisionPayload("approve")).errorCode)
        drain(2)
        val request = server.takeRequest()
        assertEquals("/api/ai-reviews/r1/decision", request.path)
        assertEquals("csrf", request.getHeader("X-CSRF-Token"))
        assertEquals("ai_review_decision_command", Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(4, server.requestCount)
    }

    @Test fun aiPendingReadFailureIsPreparationWithNoPostAndLaterSubmissionWorks() = runBlocking {
        login(); server.enqueue(envelope(profile())); server.enqueue(response("{}").setResponseCode(503))
        val failure = runCatching { repository.submitDecision(scope(), "r1", AiDecisionPayload("approve")) }.exceptionOrNull()
        assertTrue(failure is AiDecisionPreparationException)
        drain(2); assertEquals(3, server.requestCount)
        decisionReads(); server.enqueue(response("""{"success":true,"message":"Recorded","details":{}}"""))
        assertTrue(repository.submitDecision(scope(), "r1", AiDecisionPayload("reject", "corrected")).success)
        drain(2); assertEquals("POST", server.takeRequest().method)
        assertEquals(6, server.requestCount)
    }

    @Test fun lostAiReceiptCanRetryExactIntentAfterEmptyPendingAndRecoverAlreadyRecorded() = runBlocking {
        login(); decisionReads()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val payload = AiDecisionPayload("approve", "original rationale")
        assertTrue(runCatching { repository.submitDecision(scope(), "r1", payload) }.isFailure)
        drain(2)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("payload")
        server.enqueue(envelope(profile())); server.enqueue(response("""{"items":[],"count":0}"""))
        assertTrue(repository.pendingReviews().isEmpty()); drain(2)
        server.enqueue(envelope(profile()))
        server.enqueue(response("""{"success":true,"error_code":"decision_already_recorded","message":"Recorded","details":{"decision":"approve"}}"""))
        val result = repository.retryDecision(scope(), "r1", payload)
        assertTrue(result.success); assertEquals("decision_already_recorded", result.errorCode)
        assertEquals("/api/auth/me", server.takeRequest().path)
        val retry = server.takeRequest()
        assertEquals("/api/ai-reviews/r1/decision", retry.path)
        assertEquals(first, Json.parseToJsonElement(retry.body.readUtf8()).jsonObject.getValue("payload"))
        assertEquals(8, server.requestCount)
    }

    @Test fun aiRetryStillRequiresFreshScopeAndDelegate() = runBlocking {
        login(); server.enqueue(envelope(profile(delegate = "d2")))
        assertTrue(runCatching { repository.retryDecision(scope(), "r1", AiDecisionPayload("approve")) }.exceptionOrNull() is AiDecisionPreparationException)
        assertEquals(2, server.requestCount)
    }

    @Test fun aiDelegateChangePreventsPendingReadAndDispatch() = runBlocking {
        login(); server.enqueue(envelope(profile(delegate = "d2")))
        val failure = runCatching { repository.submitDecision(scope(), "r1", AiDecisionPayload("approve")) }.exceptionOrNull()
        assertTrue(failure is AiDecisionPreparationException)
        assertTrue(failure?.cause is OrderScopeChangedException)
        assertEquals(2, server.requestCount)
    }

    @Test fun ai503IsNotReplayedEvenWithRetryAfterZero() = runBlocking {
        login(); decisionReads()
        server.enqueue(response("""{"detail":{"success":false,"error_code":"review_state_race","message":"Race","details":{}}}""").setResponseCode(503).setHeader("Retry-After", "0"))
        assertEquals("review_state_race", repository.submitDecision(scope(), "r1", AiDecisionPayload("reject")).errorCode)
        assertEquals(4, server.requestCount)
    }

    @Test fun scopeChangedOnGetRefreshStopsBeforeReplayedTargetGet() = runBlocking {
        login(); server.enqueue(envelope(profile())); server.enqueue(response("{}").setResponseCode(401))
        server.enqueue(envelope("""{"user":${profile(actor = "other")}}"""))
        assertTrue(runCatching { repository.stopProcess(scope(), target()) }.exceptionOrNull() is OrderScopeChangedException)
        drain(2); assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals(4, server.requestCount)
    }
    @Test fun ai401ExpiresWithoutRefreshOrReplay() = runBlocking {
        login(); decisionReads(); server.enqueue(response("{}").setResponseCode(401))
        assertTrue(runCatching { repository.submitDecision(scope(), "r1", AiDecisionPayload("approve")) }.exceptionOrNull() is SessionExpiredException)
        assertNull(repository.user.value)
        assertEquals(4, server.requestCount)
    }

    @Test fun process200ErrorRemainsAnErrorStatus() = runBlocking {
        login(); processReads()
        server.enqueue(envelope("""{"name":"feed","status":"error","message":"Unable to start"}"""))
        assertEquals("error", repository.startProcess(scope(), target()).status)
        assertEquals(4, server.requestCount)
    }

    @Test fun positionDisconnectAfterDispatchDoesNotReplay() = runBlocking {
        login(); positionReads()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(runCatching { repository.submit(scope(), order()) }.isFailure)
        assertEquals(6, server.requestCount)
    }

    @Test fun logoutDuringDecisionDropsLateSuccess() = runBlocking {
        login(); decisionReads()
        server.enqueue(response("""{"success":true,"message":"Recorded","details":{}}""").setBodyDelay(2, TimeUnit.SECONDS))
        val captured = scope()
        val result = async(Dispatchers.IO) { runCatching { repository.submitDecision(captured, "r1", AiDecisionPayload("approve")) } }
        withContext(Dispatchers.IO) { drain(3) }
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        assertTrue(result.await().isFailure)
        assertNull(repository.user.value)
    }

}
