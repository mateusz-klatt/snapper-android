package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.aireviews.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class AiReviewsControllerTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private fun identity(permissions: Set<String> = setOf("read:signals", "read:ai_reviews", "submit:ai_review_decision")) =
        AiReviewIdentity(OrderScope("user", "delegate", null, permissions), "delegate-id")
    private fun row() = AiPendingReviewDto("review", "delegate-id", "wallet", 1, "pending", "2026-10-16T12:01:00Z", now.toString())
    private fun response(code: String? = null, success: Boolean = code == null) = AiDecisionResponse(success, code, "message", JsonObject(emptyMap()))

    @Test fun feedsFailIndependentlyAndDelegateDoesNotNeedAuditPermission() = runTest {
        val client = Fake(row())
        client.audit = { error("audit unavailable") }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent()
        assertTrue(controller.state.value.auditFailed)
        assertEquals(1, controller.state.value.pending.size)
        client.audit = { emptyList() }; client.pending = { error("pending unavailable") }
        controller.refresh(); runCurrent()
        assertFalse(controller.state.value.auditFailed)
        assertTrue(controller.state.value.pendingFailed)
        controller.invalidate()
        val delegate = AiReviewsController(this, client, { identity(setOf("read:signals")) }, clock)
        val auditCalls = client.auditCalls
        delegate.activate(); runCurrent()
        assertEquals(auditCalls, client.auditCalls)
        assertFalse(delegate.state.value.identity!!.canDecide)
        delegate.invalidate()
    }

    @Test fun confirmationAndSynchronousDuplicateLockRetainAcceptanceWhenRefreshFails() = runTest {
        val client = Fake(row())
        lateinit var reply: Continuation<AiDecisionResponse>
        client.submit = { suspendCoroutine { reply = it } }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent()
        controller.prepare("review", "approve", " rationale ")
        assertEquals(0, client.posts.size)
        controller.confirm(); controller.confirm()
        assertTrue("review" in controller.state.value.inFlight)
        runCurrent(); assertEquals(1, client.posts.size)
        client.pending = { error("offline") }; client.audit = { error("offline") }
        reply.resume(response()); runCurrent()
        assertEquals(AiDecisionOutcome.ACCEPTED, controller.state.value.results["review"]?.outcome)
        assertTrue(controller.state.value.pendingFailed)
        assertEquals("approve", controller.state.value.results["review"]?.decision)
        assertTrue(controller.state.value.pending.isEmpty())
        controller.invalidate()
    }

    @Test fun unknownRequiresFreshStatusAndExplicitSameIntentRetry() = runTest {
        val client = Fake(row()); client.submit = { error("connection lost") }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent()
        controller.prepare("review", "reject", "reason"); controller.confirm(); runCurrent()
        assertEquals(AiDecisionOutcome.UNKNOWN, controller.state.value.results["review"]?.outcome)
        controller.retry("review"); assertNull(controller.state.value.confirmation)
        controller.refresh(); runCurrent(); controller.retry("review")
        assertEquals(AiDecisionPayload("reject", "reason"), controller.state.value.confirmation?.payload)
        assertEquals(1, client.posts.size)
        client.submit = { response("review_already_resolved_by_peer", false) }
        controller.confirm(); runCurrent()
        assertEquals(client.posts[0], client.posts[1])
        assertEquals(AiDecisionOutcome.PEER_RESOLVED, controller.state.value.results["review"]?.outcome)
        controller.invalidate()
    }

    @Test fun expiryMalformedDeadlineAndRevocationPreventDispatch() = runTest {
        val client = Fake(row().copy(deadline = now.toString()))
        var person: AiReviewIdentity? = identity()
        val controller = AiReviewsController(this, client, { person }, clock)
        controller.activate(); runCurrent(); controller.prepare("review", "approve", "")
        assertNull(controller.state.value.confirmation)
        assertTrue(aiReviewExpired("invalid", now))
        client.pending = { listOf(row()) }; controller.refresh(); runCurrent()
        controller.prepare("review", "approve", ""); person = identity(emptySet()); controller.confirm(); runCurrent()
        assertTrue(client.posts.isEmpty())
        controller.invalidate(); assertNull(controller.state.value.identity)
    }

    @Test fun staleWriteCannotCrossLogoutAndReplacement() = runTest {
        val client = Fake(row()); lateinit var reply: Continuation<AiDecisionResponse>
        client.submit = { suspendCoroutine { reply = it } }
        var person = identity()
        val controller = AiReviewsController(this, client, { person }, clock)
        controller.activate(); runCurrent(); controller.prepare("review", "approve", ""); controller.confirm(); runCurrent()
        controller.invalidate(); person = person.copy(scope = person.scope.copy(publicId = "replacement"))
        controller.activate(); runCurrent(); reply.resume(response()); runCurrent()
        assertTrue(controller.state.value.results.isEmpty())
        assertEquals(1, controller.state.value.pending.size)
        controller.invalidate()
    }

    @Test fun pendingReadStartedBeforeUnknownOutcomeCannotAuthorizeRetry() = runTest {
        val client = Fake(row())
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent()
        lateinit var pendingReply: Continuation<List<AiPendingReviewDto>>
        client.pending = { suspendCoroutine { pendingReply = it } }
        controller.refresh(); runCurrent()
        client.submit = { error("lost reply") }
        controller.prepare("review", "approve", ""); controller.confirm(); runCurrent()
        pendingReply.resume(listOf(row())); runCurrent()
        assertTrue(controller.state.value.retryable.isEmpty())
        controller.invalidate()
    }

    @Test fun cancelledPendingResponseCannotRestoreLoggedOutData() = runTest {
        val client = Fake(row())
        lateinit var pendingReply: Continuation<List<AiPendingReviewDto>>
        client.pending = { suspendCoroutine { pendingReply = it } }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent(); controller.invalidate()
        pendingReply.resume(listOf(row())); runCurrent()
        assertTrue(controller.state.value.pending.isEmpty())
        assertNull(controller.state.value.identity)
    }

    @Test fun unknownRemainsUnknownAfterDeniedOrRejectedRetryAndKeepsExactIntent() = runTest {
        val client = Fake(row()); client.submit = { error("timeout") }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent()
        controller.prepare("review", "reject", "original reason"); controller.confirm(); runCurrent()
        for (code in listOf("invalid_rationale", "not_authorized")) {
            controller.refresh(); runCurrent(); controller.retry("review")
            client.submit = { response(code) }
            controller.confirm(); runCurrent()
            assertEquals(AiDecisionOutcome.UNKNOWN, controller.state.value.results["review"]?.outcome)
            assertTrue(controller.state.value.retryable.isEmpty())
            controller.prepare("review", "approve", "changed")
            assertNull(controller.state.value.confirmation)
            controller.refresh(); runCurrent(); controller.retry("review")
            assertEquals(AiDecisionPayload("reject", "original reason"), controller.state.value.confirmation?.payload)
            controller.dismissConfirmation()
        }
        assertTrue(client.posts.all { it == AiDecisionPayload("reject", "original reason") })
        controller.invalidate()
    }

    @Test fun initialRejectionAllowsCorrectionOnlyAfterReadStartedAfterRejection() = runTest {
        val client = Fake(row()); client.submit = { response("invalid_rationale") }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent()
        lateinit var pendingReply: Continuation<List<AiPendingReviewDto>>
        client.pending = { suspendCoroutine { pendingReply = it } }
        controller.refresh(); runCurrent()
        controller.prepare("review", "approve", "invalid"); controller.confirm(); runCurrent()
        pendingReply.resume(listOf(row())); runCurrent()
        controller.prepare("review", "reject", "corrected")
        assertNull(controller.state.value.confirmation)
        assertEquals(AiDecisionOutcome.REJECTED, controller.state.value.results["review"]?.outcome)
        client.pending = { listOf(row()) }; controller.refresh(); runCurrent()
        controller.prepare("review", "reject", "corrected")
        assertEquals(AiDecisionPayload("reject", "corrected"), controller.state.value.confirmation?.payload)
        client.submit = { response() }; controller.confirm(); runCurrent()
        assertEquals(AiDecisionPayload("reject", "corrected"), client.posts.last())
        controller.invalidate()
    }

    @Test fun readStartedBeforeRetryRejectionCannotUnlockHistoricalUnknown() = runTest {
        val client = Fake(row()); client.submit = { error("timeout") }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent(); controller.prepare("review", "approve", "")
        controller.confirm(); runCurrent(); controller.refresh(); runCurrent(); controller.retry("review")
        lateinit var submitReply: Continuation<AiDecisionResponse>
        client.submit = { suspendCoroutine { submitReply = it } }
        controller.confirm(); runCurrent()
        lateinit var pendingReply: Continuation<List<AiPendingReviewDto>>
        client.pending = { suspendCoroutine { pendingReply = it } }
        controller.refresh(); runCurrent()
        submitReply.resume(response("not_authorized")); runCurrent()
        pendingReply.resume(listOf(row())); runCurrent()
        assertEquals(AiDecisionOutcome.UNKNOWN, controller.state.value.results["review"]?.outcome)
        assertTrue(controller.state.value.retryable.isEmpty())
        controller.invalidate()
    }

    @Test fun unknownMissingFromFreshPendingCanExplicitlyRetryFrozenIntentAndRecoverReceipt() = runTest {
        val client = Fake(row()); client.submit = { error("lost response") }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent(); controller.prepare("review", "approve", "original")
        controller.confirm(); runCurrent()
        client.pending = { emptyList() }; controller.refresh(); runCurrent()
        assertTrue(controller.state.value.pending.isEmpty())
        assertTrue("review" in controller.state.value.retryable)
        assertEquals(1, client.posts.size)
        controller.retry("review")
        val intent = requireNotNull(controller.state.value.confirmation)
        assertEquals(AiDecisionPayload("approve", "original"), intent.payload)
        assertTrue(controller.state.value.canConfirm(intent))
        client.submit = { response("decision_already_recorded", true).copy(details = buildJsonObject { put("decision", "approve") }) }
        controller.confirm(); runCurrent()
        assertEquals(1, client.retries)
        assertEquals(client.posts.first(), client.posts.last())
        assertEquals(AiDecisionOutcome.ACCEPTED, controller.state.value.results["review"]?.outcome)
        controller.invalidate()
    }

    @Test fun initialPreparationFailureCanBeCorrectedAfterFreshReadButRetryPreparationKeepsUnknown() = runTest {
        val client = Fake(row()); client.submit = { throw AiDecisionPreparationException(IllegalStateException("read failed")) }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent(); controller.prepare("review", "approve", "first")
        controller.confirm(); runCurrent()
        assertEquals(AiDecisionOutcome.PREPARATION, controller.state.value.results["review"]?.outcome)
        controller.prepare("review", "reject", "corrected"); assertNull(controller.state.value.confirmation)
        controller.refresh(); runCurrent(); controller.prepare("review", "reject", "corrected")
        assertEquals("reject", controller.state.value.confirmation?.payload?.decision)
        client.submit = { error("lost response") }; controller.confirm(); runCurrent()
        controller.refresh(); runCurrent(); controller.retry("review")
        client.submit = { throw AiDecisionPreparationException(IllegalStateException("read failed")) }
        controller.confirm(); runCurrent()
        assertEquals(AiDecisionOutcome.UNKNOWN, controller.state.value.results["review"]?.outcome)
        controller.refresh(); runCurrent(); controller.retry("review")
        assertEquals(AiDecisionPayload("reject", "corrected"), controller.state.value.confirmation?.payload)
        controller.invalidate()
    }

    @Test fun expiredUnknownIntentCanAskBackendForTerminalOutcomeWithoutChangingDecision() = runTest {
        val client = Fake(row()); client.submit = { error("lost response") }
        val controller = AiReviewsController(this, client, { identity() }, clock)
        controller.activate(); runCurrent(); controller.prepare("review", "approve", "")
        controller.confirm(); runCurrent()
        client.pending = { listOf(row().copy(deadline = now.toString())) }
        controller.refresh(); runCurrent(); controller.retry("review")
        assertNotNull(controller.state.value.confirmation)
        client.submit = { response("review_id_expired") }; controller.confirm(); runCurrent()
        assertEquals(AiDecisionOutcome.EXPIRED, controller.state.value.results["review"]?.outcome)
        controller.invalidate()
    }

    @Test fun typedOutcomesAndAlreadyRecordedRequireMatchingDecision() {
        val intent = AiDecisionPayload("approve")
        val same = response("decision_already_recorded", true).copy(details = buildJsonObject { put("decision", "approve") })
        assertEquals(AiDecisionOutcome.ACCEPTED, aiDecisionOutcome(same, intent))
        assertEquals(AiDecisionOutcome.UNKNOWN, aiDecisionOutcome(same, AiDecisionPayload("reject")))
        assertEquals(AiDecisionOutcome.UNKNOWN, aiDecisionOutcome(response("decision_already_recorded", true), intent))
        mapOf("review_id_expired" to AiDecisionOutcome.EXPIRED, "review_not_found" to AiDecisionOutcome.MISSING,
            "not_authorized" to AiDecisionOutcome.DENIED, "invalid_rationale" to AiDecisionOutcome.REJECTED,
            "new_code" to AiDecisionOutcome.UNKNOWN).forEach { (code, expected) ->
            assertEquals(expected, aiDecisionOutcome(response(code), intent))
        }
    }

    @Test fun backendPendingEnvelopeAndNonSuccessDetailDecodeWithoutInventedDefaults() {
        val pending = Json.decodeFromString<AiReviewListResponse<AiPendingReviewDto>>("""
            {"items":[{"review_public_id":"review","selected_delegate_public_id":"delegate",
            "wallet_public_id":"wallet","dispatch_version":2,"status":"pending",
            "deadline":"2026-10-16T12:01:00Z","fanout_after":"2026-10-16T12:00:30Z",
            "instrument":null,"signal_envelope":null}],"count":1}
        """)
        assertEquals(2, pending.items.single().dispatchVersion)
        assertNull(pending.items.single().instrument)
        val failure = Json.parseToJsonElement("""
            {"detail":{"success":false,"error_code":"review_id_expired","message":"expired","details":{}}}
        """).jsonObject.getValue("detail")
        val decoded = Json.decodeFromJsonElement<AiDecisionResponse>(failure)
        assertEquals(AiDecisionOutcome.EXPIRED, aiDecisionOutcome(decoded, AiDecisionPayload("approve")))
    }

    private class Fake(row: AiPendingReviewDto) : AiReviewsClient {
        var auditCalls = 0
        var retries = 0
        val posts = mutableListOf<AiDecisionPayload>()
        var audit: suspend () -> List<AiAuditReviewDto> = { emptyList() }
        var pending: suspend () -> List<AiPendingReviewDto> = { listOf(row) }
        var submit: suspend () -> AiDecisionResponse = { AiDecisionResponse(true, null, "accepted", JsonObject(emptyMap())) }
        override suspend fun auditReviews(): List<AiAuditReviewDto> { auditCalls++; return audit() }
        override suspend fun pendingReviews() = pending()
        override suspend fun retryDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse {
            retries++
            return submitDecision(scope, reviewId, payload)
        }
        override suspend fun submitDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse {
            posts += payload
            return submit()
        }
    }
}
