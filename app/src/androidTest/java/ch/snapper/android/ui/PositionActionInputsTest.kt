package ch.snapper.android.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.R
import ch.snapper.android.data.*
import ch.snapper.android.features.positionactions.*
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PositionActionInputsTest {
    @get:Rule val compose = createComposeRule()
    private val scope = OrderScope("u", "user", "w", setOf("create:orders"))
    private val wallet = WalletDto("w", "Test wallet", isPaper = true)
    private val target = PositionActionTarget("p", "i", "BTC-USD", "venue", "cycle",
        BigDecimal("2.3456"), BigDecimal("100"), "w")
    private lateinit var controller: PositionActionController
    private val submitted = mutableListOf<PositionActionIntent>()
    private val result = CompletableDeferred<ExecutionPlanDto>()
    private var checks = 0
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(kind: PositionActionKind, failFirst: Boolean = false) {
        compose.setContent {
            val coroutineScope = rememberCoroutineScope()
            val current = remember {
                PositionActionController(coroutineScope, PositionActionClient { _, intent ->
                    submitted.add(intent)
                    if (failFirst && submitted.size == 1) throw IOException("Synthetic uncertain result")
                    result.await()
                }, scope) { "frozen-key" }.also { controller = it }
            }
            val state = current.state.collectAsState().value
            PositionActionDialogs(state, current.actions) { checks++ }
            PositionActionFeedback(state, current.actions) { checks++ }
        }
        compose.runOnIdle { controller.open(wallet, target, kind) }
    }

    private fun input(label: Int, value: String) {
        compose.onNodeWithTag("position.field.$label").performScrollTo().performTextReplacement(value)
    }
    private fun review() = compose.onNodeWithTag("position.confirm").performClick()
    private fun confirmHeld() {
        compose.onNodeWithTag("position.confirm").performClick()
        compose.onNodeWithTag("position.confirm").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.order_back)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.positions_reduce_cancel)).assertIsNotEnabled()
    }

    @Test fun reducePercentageAndBackEditProduceExactReviewedQuantity() {
        show(PositionActionKind.REDUCE)
        compose.onNodeWithText("50%").performScrollTo().performClick()
        review()
        compose.onNodeWithText(text(R.string.order_quantity) + ": 1.1728").assertExists()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        compose.onNodeWithText(text(R.string.order_back)).performClick()
        input(R.string.positions_reduce_sizelabel, "12.5")
        review()
        compose.onNodeWithText(text(R.string.order_quantity) + ": 0.2932").assertExists()
        confirmHeld()
        compose.runOnIdle {
            val order = (submitted.single() as PositionActionIntent.Order).payload
            assertEquals(0, BigDecimal("0.2932").compareTo(order.quantity))
            assertEquals("sell", order.side); assertEquals(true, order.reduceOnly)
            assertEquals("paper", order.mode)
        }
    }

    @Test fun bracketInputsPreserveExactPricesThroughReviewAndSubmit() {
        show(PositionActionKind.BRACKET)
        input(R.string.trading_bracket_stoplosslabel, "90.123456789012345678")
        input(R.string.trading_bracket_takeprofitlabel, "110.987654321098765432")
        review()
        compose.onNodeWithText(text(R.string.trading_bracket_stoplosslabel) + ": 90.123456789012345678").assertExists()
        compose.onNodeWithText(text(R.string.trading_bracket_takeprofitlabel) + ": 110.987654321098765432").assertExists()
        confirmHeld()
        compose.runOnIdle {
            val payload = (submitted.single() as PositionActionIntent.Bracket).payload
            assertEquals(BigDecimal("90.123456789012345678"), payload.stopLoss)
            assertEquals(BigDecimal("110.987654321098765432"), payload.takeProfit)
            assertEquals("cycle", payload.cyclePublicId)
        }
    }

    @Test fun trailingInvalidMinimumLockStaysEditableThenExactValidValuesAreReviewed() {
        show(PositionActionKind.TRAILING)
        input(R.string.trading_trailingstop_trailamountlabel, "1.234567890123456789")
        input(R.string.position_action_minimum_lock, "invalid")
        review()
        compose.onNodeWithText(text(R.string.position_action_invalid)).assertExists()
        compose.runOnIdle { assertNull(controller.state.value.review); assertTrue(submitted.isEmpty()) }
        input(R.string.position_action_minimum_lock, "0.125")
        review()
        compose.onNodeWithText(text(R.string.trading_trailingstop_trailamountlabel) + ": 1.234567890123456789%").assertExists()
        compose.onNodeWithText(text(R.string.position_action_minimum_lock) + ": 0.125").assertExists()
        confirmHeld()
        compose.runOnIdle {
            val payload = (submitted.single() as PositionActionIntent.Trailing).payload
            assertEquals(BigDecimal("1.234567890123456789"), payload.distance)
            assertEquals(BigDecimal("0.125"), payload.minimumLock)
        }
    }

    @Test fun dismissedUnknownOffersStatusWithoutReplayAndExplicitRetryKeepsIntent() {
        show(PositionActionKind.REDUCE, failFirst = true)
        review(); compose.onNodeWithTag("position.confirm").performClick()
        compose.onNodeWithText(text(R.string.order_unknown_outcome)).assertExists()
        compose.onNodeWithText(text(R.string.positions_reduce_cancel)).performClick()
        compose.onNodeWithTag("position.confirm").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.order_check_status)).performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(1, submitted.size) }
        compose.onNodeWithText(text(R.string.position_action_retry)).performClick()
        compose.onNodeWithTag("position.confirm").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(2, submitted.size); assertEquals(submitted[0], submitted[1])
            assertEquals("frozen-key", (submitted[1] as PositionActionIntent.Order).payload.idempotencyKey)
            assertFalse(controller.state.value.dismissed)
        }
    }
}
