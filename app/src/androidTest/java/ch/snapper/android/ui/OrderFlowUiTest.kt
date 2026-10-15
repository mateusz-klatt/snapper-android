package ch.snapper.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.OrderActions
import ch.snapper.android.OrderDraft
import ch.snapper.android.OrderFlowState
import ch.snapper.android.OrderForm
import ch.snapper.android.OrderOutcome
import ch.snapper.android.OrderReview
import ch.snapper.android.data.CreateOrderPayload
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.InstrumentDto
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.OrderScope
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.WalletDto
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OrderFlowUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val wallet = WalletDto("wallet-paper", "Practice", isPaper = true)
    private val user = UserDto("trader", "operator", publicId = "person-one", activeWalletPublicId = wallet.publicId,
        effectivePermissions = listOf("read:orders", "read:market_data", "create:orders", "cancel:orders"))
    private val instrument = InstrumentDto("instrument-one", "symbol-one", "BTC-USD", "kraken", true, true)
    private val order = OrderDto("order-one", "client-one", "BTC-USD", "kraken", "buy", "open", "limit",
        BigDecimal("0.25"), BigDecimal.ZERO, "2026-10-15T12:00:00Z", price = BigDecimal("50000"),
        walletPublicId = wallet.publicId, planPublicId = "plan-one")

    private fun state(flow: OrderFlowState = OrderFlowState(), currentUser: UserDto = user,
        records: List<OrderDto> = listOf(order)): AppUiState = AppUiState(user = currentUser, isRestoring = false,
        snapshot = DashboardSnapshot(currentUser, listOf(wallet), emptyList(), records, emptyList()), orders = flow)

    private fun actions(orders: OrderActions = OrderActions()): AppActions =
        AppActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, orders)

    private fun draftFlow(): OrderFlowState = OrderFlowState(draft = OrderDraft(exchange = "kraken"),
        exchanges = listOf("kraken"), instruments = listOf(instrument))

    private fun review(): OrderReview {
        val draft = OrderDraft("kraken", instrument.instrumentPublicId, quantity = "0.25", price = "50000")
        return OrderReview(requireNotNull(OrderForm.build(draft, instrument, wallet, "intent-one")), wallet.label, OrderScope.from(user))
    }

    @Test
    fun enterReviewAndConfirmPreservesPayloadAndDisablesDuplicateConfirmation() {
        val current = mutableStateOf(state())
        val submissions = mutableListOf<CreateOrderPayload>()
        val callbacks = OrderActions(
            open = { current.value = current.value.copy(orders = draftFlow()) },
            updateDraft = { draft -> current.value = current.value.copy(orders = current.value.orders.copy(draft = draft)) },
            review = {
                val flow = current.value.orders
                val body = requireNotNull(OrderForm.build(requireNotNull(flow.draft), instrument, wallet, "intent-one"))
                current.value = current.value.copy(orders = flow.copy(review = OrderReview(body, wallet.label, OrderScope.from(user))))
            },
            confirm = {
                submissions.add(requireNotNull(current.value.orders.review).body)
                current.value = current.value.copy(orders = current.value.orders.copy(isSubmitting = true))
            },
        )
        compose.setContent { SnapperTheme { SnapperApp(current.value, actions(callbacks)) } }
        compose.onNodeWithTag("nav.orders").performClick()
        compose.onNodeWithTag("order.new").performClick()
        compose.onNodeWithTag("order.review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("order.instrument").performScrollTo().performClick()
        compose.onNodeWithTag("order.instrument.instrument-one").performClick()
        compose.onNodeWithTag("order.quantity").performScrollTo().performTextInput("0.25")
        compose.onNodeWithTag("order.price").performScrollTo().performTextInput("50000")
        compose.onNodeWithTag("order.review").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Practice").assertExists()
        compose.onNodeWithText("Paper").assertExists()
        compose.onNodeWithTag("order.confirm").performScrollTo().performClick()
        compose.onNodeWithTag("order.confirm").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            assertEquals(review().body, submissions.single())
        }
    }

    @Test
    fun cancellationRequiresConfirmationAndShowsWalletAndOrderIdentity() {
        val current = mutableStateOf(state())
        var calls = 0
        val callbacks = OrderActions(requestCancel = { selected ->
            current.value = current.value.copy(orders = OrderFlowState(pendingCancel = selected, cancelScope = OrderScope.from(user)))
        }, confirmCancel = {
            calls++
            current.value = current.value.copy(orders = current.value.orders.copy(isSubmitting = true))
        })
        compose.setContent { SnapperTheme { SnapperApp(current.value, actions(callbacks)) } }
        compose.onNodeWithTag("nav.orders").performClick()
        compose.onNodeWithTag("order.cancel.order-one").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Practice").assertExists()
        compose.onNodeWithTag("order.cancel.confirm").performScrollTo().performClick()
        compose.onNodeWithTag("order.cancel.confirm").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun deniedPermissionsMissingWalletAndTerminalOrdersCannotMutate() {
        val current = mutableStateOf(state(currentUser = user.copy(effectivePermissions = listOf("read:orders"))))
        compose.setContent { SnapperTheme { SnapperApp(current.value, actions()) } }
        compose.onNodeWithTag("nav.orders").performClick()
        compose.onNodeWithTag("order.new").assertDoesNotExist()
        compose.onNodeWithTag("order.cancel.order-one").assertDoesNotExist()
        compose.runOnIdle { current.value = state(currentUser = user.copy(effectivePermissions = listOf("read:orders", "create:orders"))) }
        compose.onNodeWithTag("order.new").assertIsNotEnabled()
        compose.runOnIdle { current.value = state(currentUser = user.copy(activeWalletPublicId = null), records = listOf(order.copy(status = "filled"))) }
        compose.onNodeWithTag("order.new").assertIsNotEnabled()
        compose.onNodeWithTag("order.cancel.order-one").assertDoesNotExist()
        compose.runOnIdle { current.value = state(records = listOf(order.copy(planPublicId = " "))) }
        compose.onNodeWithTag("order.cancel.order-one").assertDoesNotExist()
    }

    @Test
    fun scopeRerenderHidesOldConfirmationEvenBeforeStateReset() {
        val current = mutableStateOf(state(draftFlow().copy(review = review())))
        compose.setContent { SnapperTheme { SnapperApp(current.value, actions()) } }
        compose.onNodeWithTag("order.confirm").assertExists()
        compose.runOnIdle { current.value = current.value.copy(user = user.copy(publicId = "person-two")) }
        compose.onNodeWithTag("order.confirm").assertDoesNotExist()
        compose.runOnIdle { current.value = state(OrderFlowState(pendingCancel = order, cancelScope = OrderScope.from(user))) }
        compose.onNodeWithTag("order.cancel.confirm").assertExists()
        compose.runOnIdle { current.value = current.value.copy(user = user.copy(activeWalletPublicId = "another-wallet")) }
        compose.onNodeWithTag("order.cancel.confirm").assertDoesNotExist()
    }

    @Test
    fun observationOnlyAndMismatchedInstrumentsAreDisabled() {
        val flow = draftFlow().copy(instruments = listOf(instrument.copy(canTrade = false),
            instrument.copy(instrumentPublicId = "wrong-exchange", exchange = "other"),
            instrument.copy(instrumentPublicId = "unresolved", instrumentResolved = false)))
        compose.setContent { SnapperTheme { SnapperApp(state(flow), actions()) } }
        compose.onNodeWithTag("order.instrument").performScrollTo().performClick()
        compose.onNodeWithTag("order.instrument.instrument-one").assertIsNotEnabled()
        compose.onNodeWithTag("order.instrument.wrong-exchange").assertIsNotEnabled()
        compose.onNodeWithTag("order.instrument.unresolved").assertIsNotEnabled()
    }

    @Test
    fun unknownOutcomeOffersStatusAndSameIntentRetryWithoutFreshConfirmation() {
        var checks = 0
        var retries = 0
        val flow = draftFlow().copy(review = review(), outcome = OrderOutcome.UNKNOWN)
        compose.setContent { SnapperTheme { SnapperApp(state(flow), actions(OrderActions(checkStatus = { checks++ }, retry = { retries++ }))) } }
        compose.onNodeWithTag("order.confirm").assertDoesNotExist()
        compose.onNodeWithTag("order.back").assertDoesNotExist()
        compose.onNodeWithTag("order.checkStatus").performScrollTo().performClick()
        compose.onNodeWithTag("order.retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(1, retries) }
    }

    @Test
    fun acceptedCancellationRemainsSuccessfulWhenSnapshotRefreshFailed() {
        compose.setContent { SnapperTheme { SnapperApp(state(OrderFlowState(outcome = OrderOutcome.CANCEL_REQUESTED,
            snapshotRefreshFailed = true)), actions()) } }
        compose.onNodeWithTag("nav.orders").performClick()
        compose.onNodeWithTag("order.outcome").assertExists()
        compose.onNodeWithText("Cancellation requested. Refresh to check the order status.").assertExists()
        compose.onNodeWithTag("order.cancel.confirm").assertDoesNotExist()
    }

    @Test
    fun dismissedUnknownOrderReopensTheFrozenIntent() {
        val frozenReview = review()
        val current = mutableStateOf(state(draftFlow().copy(review = frozenReview, outcome = OrderOutcome.UNKNOWN)))
        val callbacks = OrderActions(
            dismiss = { current.value = current.value.copy(orders = current.value.orders.copy(isDismissed = true)) },
            open = { current.value = current.value.copy(orders = current.value.orders.copy(isDismissed = false)) },
        )
        compose.setContent { SnapperTheme { SnapperApp(current.value, actions(callbacks)) } }
        compose.onNodeWithTag("order.dismiss").performScrollTo().performClick()
        compose.onNodeWithTag("order.dismiss").assertDoesNotExist()
        compose.onNodeWithTag("nav.orders").performClick()
        compose.onNodeWithTag("order.outcome").assertExists()
        compose.onNodeWithTag("order.new").performScrollTo().performClick()
        compose.onNodeWithTag("order.dismiss").assertExists()
        compose.onNodeWithTag("order.confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(frozenReview, current.value.orders.review) }
    }

    @Test
    fun cancelControlsDisableWhileLoadingOrAnotherIntentIsRetained() {
        val current = mutableStateOf(state().copy(isLoading = true))
        compose.setContent { SnapperTheme { SnapperApp(current.value, actions()) } }
        compose.onNodeWithTag("nav.orders").performClick()
        compose.onNodeWithTag("order.cancel.order-one").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { current.value = state(OrderFlowState(outcome = OrderOutcome.UNKNOWN, isDismissed = true)) }
        compose.onNodeWithTag("order.cancel.order-one").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("order.checkStatus").performScrollTo().assertIsEnabled()
        compose.runOnIdle { current.value = state(draftFlow().copy(isDismissed = true)) }
        compose.onNodeWithTag("order.cancel.order-one").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { current.value = state(OrderFlowState(isSubmitting = true)) }
        compose.onNodeWithTag("order.cancel.order-one").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { current.value = state() }
        compose.onNodeWithTag("order.cancel.order-one").performScrollTo().assertIsEnabled()
    }
}
