package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.R
import ch.snapper.android.data.*
import ch.snapper.android.features.aireviews.*
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiReviewsAuditOutcomeTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun identity(vararg permissions: String) = AiReviewIdentity(OrderScope("user", "reader", "wallet", permissions.toSet()), "delegate")
    private fun pending(id: String) = AiPendingReviewDto(id, "delegate", "wallet", 1, "pending",
        "2026-10-16T12:05:00Z", "2026-10-16T11:59:00Z", "instrument-$id")
    private fun deciding(vararg ids: String) = AiReviewsState(
        identity = identity("read:signals", "submit:ai_review_decision"), pending = ids.map(::pending), now = now)
    private fun audit(id: String = "audit-review") = AiAuditReviewDto(id, "strategy-visible", "user", "desk", "wallet",
        "instrument-audit", "selected-delegate", "responding-delegate", "resolved_rejected", "reject", "risk limit exceeded",
        "delegate", 1, "2026-10-16T11:58:00Z", "2026-10-16T11:59:00Z", "2026-10-16T12:05:00Z")
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ai.test.list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun scrollText(value: String) {
        compose.onNodeWithTag("ai.test.list").performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    @Test fun auditOnlyReaderSeesRationaleRespondingDelegateAndNoDecisionControlsThenRevocationHidesAudit() {
        val state = mutableStateOf(AiReviewsState(identity = identity("read:ai_reviews"), audit = listOf(audit())))
        var prepared = 0
        compose.setContent { Content(state.value, AiReviewsActions(prepare = { _, _, _ -> prepared++ })) }
        scrollText("risk limit exceeded")
        scrollText("responding-delegate")
        scrollText("strategy-visible")
        compose.onNodeWithTag("ai.approve.audit-review").assertDoesNotExist()
        compose.onNodeWithTag("ai.reject.audit-review").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(identity = identity()) }
        compose.onNodeWithText("instrument-audit").assertDoesNotExist()
        compose.onNodeWithText("responding-delegate").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, prepared) }
    }

    @Test fun failedPendingRefreshRetainsAuditAndReadOnlyRefreshCannotSubmit() {
        val row = audit().copy(respondingDelegatePublicId = null, rationale = null, resolvedAt = null,
            status = "timeout", decision = null)
        val state = AiReviewsState(identity = identity("read:ai_reviews", "read:signals"), audit = listOf(row),
            pendingFailed = true, auditFailed = true)
        var refreshes = 0
        compose.setContent { Content(state, AiReviewsActions(refresh = { refreshes++ })) }
        compose.onNodeWithText(text(R.string.refresh)).performClick()
        scrollText("selected-delegate")
        scrollText(text(R.string.ai_reviews_expired))
        compose.onNodeWithTag("ai.confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun acceptedDecisionRemainsVisibleWhenRefreshFailsAndCannotBeSubmittedAgain() {
        val state = mutableStateOf(deciding("accepted").copy(results = mapOf(
            "accepted" to AiDecisionResult(AiDecisionOutcome.ACCEPTED, decision = "approve"))))
        var refreshes = 0
        val actions = AiReviewsActions(refresh = {
            refreshes++
            state.value = state.value.copy(pendingFailed = true, auditFailed = true)
        })
        compose.setContent { Content(state.value, actions) }
        scroll("ai.approve.accepted").assertIsNotEnabled()
        scroll("ai.result.accepted").assertTextEquals(text(R.string.ai_reviews_accepted))
        compose.onNodeWithTag("ai.test.list").performScrollToIndex(0)
        compose.onNodeWithText(text(R.string.refresh)).performClick()
        scroll("ai.result.accepted").assertTextEquals(text(R.string.ai_reviews_accepted))
        compose.onNodeWithTag("ai.retry.accepted").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun terminalPeerExpiryAndMissingOutcomesExplainWhyOldPendingRowsCannotBeDecided() {
        val outcomes = linkedMapOf("peer" to AiDecisionOutcome.PEER_RESOLVED,
            "expired" to AiDecisionOutcome.EXPIRED, "missing" to AiDecisionOutcome.MISSING)
        val state = deciding(*outcomes.keys.toTypedArray()).copy(results = outcomes.mapValues { AiDecisionResult(it.value) })
        var submitted = 0
        compose.setContent { Content(state, AiReviewsActions(prepare = { _, _, _ -> submitted++ }, retry = { submitted++ })) }
        outcomes.keys.forEach { id ->
            scroll("ai.approve.$id").assertIsNotEnabled()
            scroll("ai.reject.$id").assertIsNotEnabled()
        }
        val messages = mapOf("peer" to R.string.ai_reviews_peer_resolved, "expired" to R.string.ai_reviews_expired_message,
            "missing" to R.string.ai_reviews_missing)
        messages.forEach { (id, message) ->
            scroll("ai.result.$id").assertTextEquals(text(message))
            compose.onNodeWithTag("ai.retry.$id").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(0, submitted) }
    }

    @Test fun knownDenialRejectionAndPreparationRequireFreshStateBeforeCorrectedDecision() {
        val outcomes = linkedMapOf("denied" to AiDecisionOutcome.DENIED, "rejected" to AiDecisionOutcome.REJECTED,
            "preparation" to AiDecisionOutcome.PREPARATION)
        val state = mutableStateOf(deciding(*outcomes.keys.toTypedArray()).copy(results = outcomes.mapValues { AiDecisionResult(it.value) }))
        var prepared: AiDecisionIntent? = null
        var confirmed = 0
        var refreshes = 0
        val actions = AiReviewsActions(refresh = {
            refreshes++
            state.value = state.value.copy(results = emptyMap())
        }, prepare = { id, decision, rationale ->
            prepared = AiDecisionIntent(id, AiDecisionPayload(decision, rationale))
            state.value = state.value.copy(confirmation = prepared)
        }, confirm = { confirmed++ })
        compose.setContent { Content(state.value, actions) }
        outcomes.keys.forEach { id -> scroll("ai.reject.$id").assertIsNotEnabled() }
        val messages = mapOf("denied" to R.string.ai_reviews_denied, "rejected" to R.string.ai_reviews_rejected,
            "preparation" to R.string.error_service)
        messages.forEach { (id, message) ->
            scroll("ai.result.$id").assertTextEquals(text(message))
            compose.onNodeWithTag("ai.retry.$id").assertDoesNotExist()
        }
        compose.onNodeWithTag("ai.test.list").performScrollToIndex(0)
        compose.onNodeWithText(text(R.string.refresh)).performClick()
        scroll("ai.rationale.rejected").performTextInput("corrected rationale")
        scroll("ai.reject.rejected").performClick()
        compose.onNodeWithTag("ai.confirm").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(AiDecisionIntent("rejected", AiDecisionPayload("reject", "corrected rationale")), prepared)
            assertEquals(1, confirmed)
            assertEquals(1, refreshes)
        }
    }

    @Test fun unknownRetryWaitsForStatusAndExplicitConfirmationKeepsFrozenIntent() {
        val frozen = AiDecisionIntent("uncertain", AiDecisionPayload("reject", "original risk rationale"))
        val state = mutableStateOf(deciding().copy(results = mapOf("uncertain" to AiDecisionResult(AiDecisionOutcome.UNKNOWN))))
        var checks = 0
        var retries = 0
        var sends = 0
        val actions = AiReviewsActions(refresh = {
            checks++
            state.value = state.value.copy(retryable = setOf("uncertain"))
        }, retry = { id ->
            assertEquals(frozen.reviewId, id)
            retries++
            state.value = state.value.copy(confirmation = frozen)
        }, confirm = {
            sends++
            state.value = state.value.copy(inFlight = setOf("uncertain"))
        })
        compose.setContent { Content(state.value, actions) }
        scroll("ai.retry.uncertain").assertIsNotEnabled()
        scrollText(text(R.string.ai_reviews_check_status))
        compose.onNodeWithText(text(R.string.ai_reviews_check_status)).performClick()
        scroll("ai.retry.uncertain").assertIsEnabled().performClick()
        compose.onNodeWithText("original risk rationale").assertExists()
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithTag("ai.confirm").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(1, retries); assertEquals(1, sends) }
    }

    @Composable
    private fun Content(state: AiReviewsState, actions: AiReviewsActions) {
        MaterialTheme {
            LazyColumn(Modifier.testTag("ai.test.list")) { aiReviewsScreen(state, actions) }
            AiReviewConfirmation(state, actions)
        }
    }
}
