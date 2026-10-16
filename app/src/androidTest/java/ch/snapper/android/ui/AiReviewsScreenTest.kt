package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.data.*
import ch.snapper.android.features.aireviews.*
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiReviewsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val row = AiPendingReviewDto("review", "delegate", "wallet", 1, "pending", "2026-10-16T12:01:00Z", "2026-10-16T12:00:00Z", "BTC-USD")
    private fun state(permissions: Set<String>) = AiReviewsState(
        identity = AiReviewIdentity(OrderScope("user", "name", null, permissions), "delegate"),
        pending = listOf(row), now = Instant.parse("2026-10-16T12:00:00Z"),
    )

    @Test fun approveRequiresConfirmationAndDeadlineDisablesConfirmation() {
        val state = mutableStateOf(state(setOf("read:signals", "submit:ai_review_decision")))
        var submissions = 0
        val actions = AiReviewsActions(
            prepare = { id, decision, rationale -> state.value = state.value.copy(confirmation = AiDecisionIntent(id, AiDecisionPayload(decision, rationale))) },
            confirm = { submissions++ },
        )
        compose.setContent { AiReviewsContent(state.value, actions) }
        compose.onNodeWithTag("ai.rationale.review").performScrollTo().performTextInput("risk rationale")
        compose.onNodeWithTag("ai.approve.review").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, submissions) }
        compose.onNodeWithTag("ai.confirm").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(now = Instant.parse(row.deadline)) }
        compose.onNodeWithTag("ai.confirm").assertIsNotEnabled()
    }

    @Test fun frozenUnknownRetryCanBeConfirmedAfterPendingRowDisappears() {
        val intent = AiDecisionIntent("review", AiDecisionPayload("approve", "original rationale"))
        val screen = state(setOf("read:signals", "submit:ai_review_decision")).copy(
            pending = emptyList(), confirmation = intent, retryable = setOf("review"),
            results = mapOf("review" to AiDecisionResult(AiDecisionOutcome.UNKNOWN)),
        )
        var confirmed = 0
        compose.setContent { AiReviewsContent(screen, AiReviewsActions(confirm = { confirmed++ })) }
        compose.onNodeWithText("original rationale").assertExists()
        compose.onNodeWithTag("ai.confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun earlyReviewConfirmationAppearsWhileLaterRowsRemainUncomposed() {
        val state = mutableStateOf(state(setOf("read:signals", "submit:ai_review_decision")).copy(
            pending = (0 until 30).map { row.copy(reviewPublicId = "review-$it") },
        ))
        val actions = AiReviewsActions(prepare = { id, decision, rationale ->
            state.value = state.value.copy(confirmation = AiDecisionIntent(id, AiDecisionPayload(decision, rationale)))
        })
        compose.setContent { AiReviewsContent(state.value, actions) }
        compose.onNodeWithTag("ai.approve.review-0").performScrollTo().performClick()
        compose.onNodeWithTag("ai.approve.review-29").assertDoesNotExist()
        compose.onNodeWithTag("ai.confirm").assertIsEnabled()
        compose.runOnIdle { assertEquals("review-0", state.value.confirmation?.reviewId) }
    }

    @Test fun readOnlyDelegateHasNoMutationControlsAndUnknownRetryRequiresFreshStatus() {
        val state = mutableStateOf(state(setOf("read:signals")))
        compose.setContent { AiReviewsContent(state.value, AiReviewsActions()) }
        compose.onNodeWithText("BTC-USD").assertExists()
        compose.onNodeWithTag("ai.approve.review").assertDoesNotExist()
        compose.onNodeWithTag("ai.reject.review").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(results = mapOf("review" to AiDecisionResult(AiDecisionOutcome.UNKNOWN))) }
        compose.onNodeWithTag("ai.retry.review").performScrollTo().assertIsNotEnabled()
    }
    @Composable
    private fun AiReviewsContent(state: AiReviewsState, actions: AiReviewsActions) {
        MaterialTheme {
            LazyColumn { aiReviewsScreen(state, actions) }
            AiReviewConfirmation(state, actions)
        }
    }

}
