package ch.snapper.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import ch.snapper.android.data.*
import ch.snapper.android.features.positionactions.*
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PositionActionDialogsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reviewAndConfirmAreSeparateAndScopeClearRemovesDialog() {
        val scope = OrderScope("u", "user", "w", setOf("create:orders"))
        val wallet = WalletDto("w", "Paper", isPaper = true)
        val target = PositionActionTarget("p", "i", "BTC-USD", "venue", "cycle", BigDecimal.ONE, BigDecimal.TEN, "w")
        val draft = PositionActionDraft(PositionActionKind.CLOSE)
        val state = mutableStateOf(PositionActionState(target = target, wallet = wallet, draft = draft))
        var submits = 0
        val actions = PositionActionActions({}, {
            state.value = state.value.copy(review = PositionActionReview(scope, wallet, requireNotNull(PositionActionForm.build(scope, wallet, target, draft, "k"))))
        }, {}, { submits++; state.value = state.value.copy(submitting = true) }, {}, {})
        compose.setContent { PositionActionDialogs(state.value, actions, {}) }
        compose.onNodeWithTag("position.confirm").performClick()
        compose.runOnIdle { assertEquals(0, submits) }
        compose.onNodeWithTag("position.confirm").performClick()
        compose.onNodeWithTag("position.confirm").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, submits); state.value = PositionActionState() }
        compose.onNodeWithTag("position.confirm").assertDoesNotExist()
    }
}
