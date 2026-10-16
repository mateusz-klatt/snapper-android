package ch.snapper.android.features.positionactions

import ch.snapper.android.data.*
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PositionActionsTest {
    private val explicitJson = Json { encodeDefaults = true }
    private val scope = OrderScope("u", "trader", "w", setOf("create:orders"))
    private val wallet = WalletDto("w", "Paper", isPaper = true)
    private val target = PositionActionTarget("p", "i", "BTC-USD", "venue", "cycle", BigDecimal("-1.123456789123456789"), BigDecimal("100"), "w")
    private class Client : PositionActionClient {
        val intents = mutableListOf<PositionActionIntent>()
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun submit(scope: OrderScope, intent: PositionActionIntent): ExecutionPlanDto {
            intents.add(intent)
            gate?.await()
            failure?.let { throw it }
            return ExecutionPlanDto("plan", "active", "w")
        }
    }

    @Test fun reductionPreservesExactQuantityAndOppositeSide() {
        val intent = PositionActionForm.build(scope, wallet, target, PositionActionDraft(PositionActionKind.REDUCE, percent = "25"), "key") as PositionActionIntent.Order
        assertEquals(BigDecimal("0.28086419728086419725"), intent.payload.quantity)
        assertEquals("buy", intent.payload.side)
        assertEquals("paper", intent.payload.mode)
        assertTrue(intent.payload.reduceOnly)
        assertEquals("market", intent.payload.orderType)
    }

    @Test fun invalidOptionalValuesAreRejectedRatherThanDropped() {
        val bracket = PositionActionDraft(PositionActionKind.BRACKET, stopLoss = "90", takeProfit = "invalid")
        assertNull(PositionActionForm.build(scope, wallet, target, bracket, "key"))
        val trailing = PositionActionDraft(PositionActionKind.TRAILING, distance = "2", minimumLock = "invalid")
        assertNull(PositionActionForm.build(scope, wallet, target, trailing, "key"))
        assertNull(PositionActionForm.build(scope, wallet, target, trailing.copy(minimumLock = "-1"), "key"))
        assertNotNull(PositionActionForm.build(scope, wallet, target, trailing.copy(minimumLock = "0"), "key"))
        assertNull(PositionActionForm.build(scope, wallet, target, PositionActionDraft(PositionActionKind.REDUCE, percent = "101"), "key"))
    }

    @Test fun deniedWrongWalletMissingCycleAndEntryFailClosed() {
        val draft = PositionActionDraft(PositionActionKind.BRACKET, stopLoss = "90")
        assertNull(PositionActionForm.build(scope.copy(effectivePermissions = setOf("manage:positions")), wallet, target, draft, "k"))
        assertNull(PositionActionForm.build(scope, wallet, target.copy(walletPublicId = "other"), draft, "k"))
        assertNull(PositionActionForm.build(scope, wallet, target.copy(cyclePublicId = ""), draft, "k"))
        assertNull(PositionActionForm.build(scope, wallet, target.copy(entryPrice = null), draft, "k"))
    }

    @Test fun duplicateConfirmationIsLockedSynchronously() = runTest {
        val client = Client()
        val controller = PositionActionController(this, client, scope) { "key" }
        controller.open(wallet, target, PositionActionKind.CLOSE)
        controller.actions.review()
        controller.actions.confirm()
        controller.actions.confirm()
        runCurrent()
        assertEquals(1, client.intents.size)
        assertEquals("plan", controller.state.value.accepted?.publicId)
        assertNull(controller.state.value.draft)
    }

    @Test fun unknownSurvivesDismissReopenRejectedRetryAndCannotRemint() = runTest {
        val client = Client().apply { failure = IOException("Lost response") }
        var keys = 0
        val controller = PositionActionController(this, client, scope) { "key-${++keys}" }
        controller.open(wallet, target, PositionActionKind.CLOSE)
        controller.actions.review()
        controller.actions.confirm()
        runCurrent()
        val frozen = controller.state.value.review
        controller.actions.dismiss()
        controller.open(wallet, target.copy(positionPublicId = "different"), PositionActionKind.TRAILING)
        controller.actions.edit(PositionActionDraft(PositionActionKind.TRAILING, distance = "2"))
        controller.actions.confirm()
        runCurrent()
        assertEquals(1, client.intents.size)
        assertEquals(frozen, controller.state.value.review)
        client.failure = PositionRejectedException(422)
        controller.actions.retry()
        runCurrent()
        assertEquals(PositionActionIssue.UNKNOWN, controller.state.value.issue)
        assertEquals(client.intents.first(), client.intents.last())
        assertEquals(1, keys)
    }

    @Test fun scopeResetDropsLateAcceptance() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { this.gate = gate }
        val controller = PositionActionController(this, client, scope)
        controller.open(wallet, target, PositionActionKind.CLOSE)
        controller.actions.review()
        controller.actions.confirm()
        runCurrent()
        controller.reset(scope.copy(activeWalletPublicId = "other"))
        gate.complete(Unit)
        runCurrent()
        assertNull(controller.state.value.accepted)
        assertNull(controller.state.value.review)
    }

    @Test fun trailingZeroLockIsNumericAndPositiveDecimalsRemainExact() {
        val payload = PositionTrailingPayload("cycle", BigDecimal("1.123456789123456789"), BigDecimal.ZERO, "key")
        val text = Json.encodeToString(PositionTrailingPayload.serializer(), payload)
        assertTrue(text.contains("\"trailing_pct\":1.123456789123456789"))
        val explicit = explicitJson.encodeToString(PositionTrailingPayload.serializer(), payload)
        assertTrue(explicit.contains("\"min_lock_pct\":0"))
    }
    @Test fun cancellationAfterDispatchRetainsFrozenIntentAndUnlocksStatusControls() = runTest {
        val client = Client().apply { failure = CancellationException("Cancelled after dispatch") }
        val controller = PositionActionController(this, client, scope) { "stable-key" }
        controller.open(wallet, target, PositionActionKind.CLOSE)
        controller.actions.review()
        val frozen = controller.state.value.review
        controller.actions.confirm()
        runCurrent()
        assertEquals(1, client.intents.size)
        assertFalse(controller.state.value.submitting)
        assertEquals(PositionActionIssue.UNKNOWN, controller.state.value.issue)
        assertEquals(frozen, controller.state.value.review)
        controller.actions.dismiss()
        assertTrue(controller.state.value.dismissed)
        controller.open(wallet, target, PositionActionKind.REDUCE)
        assertEquals(frozen, controller.state.value.review)
        controller.actions.confirm()
        runCurrent()
        assertEquals(1, client.intents.size)
    }

    @Test fun cancellationFromOldScopeCannotRepopulateResetState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { this.gate = gate; failure = CancellationException("Old request cancelled") }
        val controller = PositionActionController(this, client, scope)
        controller.open(wallet, target, PositionActionKind.CLOSE)
        controller.actions.review()
        controller.actions.confirm()
        runCurrent()
        controller.reset(scope.copy(activeWalletPublicId = "other"))
        gate.complete(Unit)
        runCurrent()
        assertEquals(PositionActionState(), controller.state.value)
        assertEquals(1, client.intents.size)
    }

    @Test fun cycleIsRequiredOnlyForProtectivePlans() {
        val withoutCycle = target.copy(cyclePublicId = "", entryPrice = null)
        for (kind in listOf(PositionActionKind.CLOSE, PositionActionKind.REDUCE)) {
            assertNotNull(PositionActionForm.build(scope, wallet, withoutCycle, PositionActionDraft(kind), "key"))
        }
        assertNull(PositionActionForm.build(scope, wallet, withoutCycle, PositionActionDraft(PositionActionKind.BRACKET, stopLoss = "90"), "key"))
        assertNull(PositionActionForm.build(scope, wallet, withoutCycle, PositionActionDraft(PositionActionKind.TRAILING, distance = "2"), "key"))
    }

    @Test fun positionZeroSnapBoundaryUsesExactDecimalComparison() {
        val draft = PositionActionDraft(PositionActionKind.CLOSE)
        for (quantity in listOf("0", "0.000000000001", "-0.000000000001")) {
            assertNull(PositionActionForm.build(scope, wallet, target.copy(quantity = BigDecimal(quantity)), draft, "key"))
        }
        assertNotNull(PositionActionForm.build(scope, wallet, target.copy(quantity = BigDecimal("0.0000000000010000000001")), draft, "key"))
    }

}
