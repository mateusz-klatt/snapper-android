package ch.snapper.android

import ch.snapper.android.data.InstrumentDto
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.WalletDto
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderFormTest {
    private val instrument = InstrumentDto("instrument-a", "symbol-a", "BTC-USD", "venue", true, true)
    private val wallet = WalletDto("wallet-a", "Paper wallet", isPaper = true)
    private val draft = OrderDraft("venue", "instrument-a", quantity = "1.1234567890123456789", price = "2.50")

    @Test
    fun builderPreservesDecimalsAndDerivesWalletModeAndModifiers() {
        val body = requireNotNull(OrderForm.build(draft.copy(leverage = "2147483647", reduceOnly = true), instrument, wallet, "stable-key"))
        assertEquals(BigDecimal("1.1234567890123456789"), body.quantity)
        assertEquals(BigDecimal("2.50"), body.price)
        assertEquals("paper", body.mode)
        assertEquals(wallet.publicId, body.walletPublicId)
        assertEquals("stable-key", body.idempotencyKey)
        assertEquals(Int.MAX_VALUE, body.leverage)
        assertTrue(body.reduceOnly)
        assertEquals("GTC", body.timeInForce)
        assertFalse(body.postOnly)
        val live = requireNotNull(OrderForm.build(draft.copy(side = "sell", orderType = "market", price = "invalid"), instrument, wallet.copy(isPaper = false), "new-key"))
        assertEquals("live", live.mode)
        assertEquals("sell", live.side)
        assertNull(live.price)
        assertNull(live.stopPrice)
        assertNull(live.leverage)
    }

    @Test
    fun rejectsInvalidDecimalValuesAndBackendFloatOverflowOrUnderflow() {
        for (value in listOf("", " ", "0", "-1", "NaN", "Infinity", "1e309", "1e-400", "1,2.3", "1".repeat(101), "1e2147483647", "1e-2147483647")) {
            assertNull(value, OrderForm.positiveNumber(value))
            assertFalse(OrderForm.isValid(draft.copy(quantity = value), instrument))
        }
        assertEquals(BigDecimal("1.25"), OrderForm.positiveNumber(" 1,25 "))
        assertEquals(BigDecimal("1e300"), OrderForm.positiveNumber("1e300"))
        assertEquals(BigDecimal("1e-300"), OrderForm.positiveNumber("1e-300"))
    }

    @Test
    fun conditionalPricesAndOptionalLeverageMatchOrderType() {
        assertFalse(OrderForm.isValid(draft.copy(price = ""), instrument))
        assertTrue(OrderForm.isValid(draft.copy(orderType = "market", price = ""), instrument))
        assertFalse(OrderForm.isValid(draft.copy(orderType = "stop", price = ""), instrument))
        val stop = requireNotNull(OrderForm.build(draft.copy(orderType = "stop", price = "", stopPrice = "3"), instrument, wallet, "key"))
        assertNull(stop.price)
        assertEquals(BigDecimal("3"), stop.stopPrice)
        assertFalse(OrderForm.isValid(draft.copy(orderType = "stop_limit", stopPrice = "0"), instrument))
        assertFalse(OrderForm.isValid(draft.copy(orderType = "stop_limit", price = "", stopPrice = "3"), instrument))
        assertTrue(OrderForm.isValid(draft.copy(orderType = "stop_limit", stopPrice = "3"), instrument))
        for (leverage in listOf("0", "-1", "1.5", "2147483648", "not-a-number")) {
            assertFalse(OrderForm.isValid(draft.copy(leverage = leverage), instrument))
        }
        assertTrue(OrderForm.isValid(draft.copy(leverage = " "), instrument))
    }

    @Test
    fun unavailableOrMismatchedInstrumentAndUnsupportedChoicesCannotBeReviewed() {
        for (candidate in listOf(null, instrument.copy(canTrade = false), instrument.copy(instrumentResolved = false), instrument.copy(exchange = "other"), instrument.copy(instrumentPublicId = "other"))) {
            assertNull(OrderForm.build(draft, candidate, wallet, "key"))
        }
        assertNull(OrderForm.build(draft.copy(side = "unknown"), instrument, wallet, "key"))
        assertNull(OrderForm.build(draft.copy(orderType = "unknown"), instrument, wallet, "key"))
        val flow = OrderFlowState(draft = draft, instruments = listOf(instrument))
        assertTrue(flow.canReview)
        assertFalse(flow.copy(loadingCatalog = true).canReview)
        assertFalse(flow.copy(isSubmitting = true).canReview)
        assertFalse(flow.copy(outcome = OrderOutcome.UNKNOWN).canReview)
        assertFalse(flow.copy(draft = null).canReview)
    }

    @Test
    fun cancellationRequiresExplicitPermissionPlanOpenStatusAndExactSelectedWallet() {
        val user = UserDto("alice", "admin", activeWalletPublicId = wallet.publicId, effectivePermissions = listOf("cancel:orders"))
        val order = OrderDto("order-a", "client-a", "BTC-USD", "venue", "buy", "open", "limit", BigDecimal.ONE, BigDecimal.ZERO, "now", walletPublicId = wallet.publicId, planPublicId = "plan-a")
        for (status in listOf("new", "submitted", "open", "partially_filled")) assertTrue(OrderForm.canCancel(order.copy(status = status), user))
        for (status in listOf("filled", "cancelled", "rejected", "unknown")) assertFalse(OrderForm.canCancel(order.copy(status = status), user))
        assertFalse(OrderForm.canCancel(order.copy(planPublicId = null), user))
        assertFalse(OrderForm.canCancel(order.copy(planPublicId = ""), user))
        assertFalse(OrderForm.canCancel(order.copy(walletPublicId = "other"), user))
        assertFalse(OrderForm.canCancel(order.copy(walletPublicId = null), user))
        assertFalse(OrderForm.canCancel(order, user.copy(activeWalletPublicId = null)))
        assertFalse(OrderForm.canCancel(order, user.copy(effectivePermissions = emptyList())))
        assertFalse(OrderForm.canCancel(order, null))
    }
}
