package ch.snapper.android

import ch.snapper.android.data.ExecutionDto
import ch.snapper.android.data.OrderDto
import ch.snapper.android.features.records.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class RecordsFiltersTest {
    private fun order(id: String, status: String, date: String, wallet: String? = "a") = OrderDto(
        publicId = id, clientOrderId = id, instrument = "BTC-USD", exchange = "kraken", side = "buy",
        status = status, orderType = "market", size = BigDecimal.ONE, filledSize = BigDecimal.ZERO,
        createdAt = date, walletPublicId = wallet,
    )
    private fun fill(wallet: String?) = ExecutionDto("fill", "BTC-USD", "kraken", "buy", BigDecimal("0.0000000000001"), BigDecimal("123.4567890123456789"), BigDecimal("0.01"), "USD", "filled", "2026-10-16T01:00:00Z", wallet)

    @Test fun openIncludesAllNonterminalStatusesOnly() {
        val rows = listOf("new", "submitted", "open", "partially_filled", "filled", "cancelled", "rejected").map { order(it, it, "2026-10-16T01:00:00Z") }
        assertEquals(listOf("new", "submitted", "open", "partially_filled"), RecordFilters.orders(rows, OrderSegment.OPEN, "a").map { it.status })
    }

    @Test fun recentSortUsesInstantsAcrossOffsetsAndLimitsAfterWalletFilter() {
        val rows = (1..60).map { order("$it", "filled", "2026-10-16T01:00:00Z") } +
            order("newest", "filled", "2026-10-16T03:00:00+01:00") + order("foreign", "filled", "2027-10-16T01:00:00Z", "b")
        val filtered = RecordFilters.orders(rows, OrderSegment.RECENT, "a")
        assertEquals(50, filtered.size)
        assertEquals("newest", filtered.first().publicId)
        assertFalse(filtered.any { it.publicId == "foreign" })
    }

    @Test fun nullWalletLegacyRowsRemainVisibleButForeignRowsDoNot() {
        assertEquals(2, RecordFilters.fills(listOf(fill("a"), fill(null), fill("b")), "a").size)
        assertEquals(3, RecordFilters.fills(listOf(fill("a"), fill(null), fill("b")), null).size)
        assertTrue(RecordFilters.orders(emptyList(), OrderSegment.FILLS, null).isEmpty())
    }

    @Test fun failedFillRefreshPreservesCachedRowsAndSegment() {
        val state = RecordsState(segment = OrderSegment.FILLS, executions = listOf(fill("a")), isLoading = true)
        val failed = RecordFilters.failed(state, UiError.NETWORK)
        assertEquals(state.executions, failed.executions)
        assertEquals(OrderSegment.FILLS, failed.segment)
        assertFalse(failed.isLoading)
        assertEquals(UiError.NETWORK, failed.error)
        val recovered = RecordFilters.loaded(failed, emptyList())
        assertTrue(recovered.executions.isEmpty())
        assertNull(recovered.error)
    }

    @Test fun malformedDatesSortLastAndFinancialDecimalsRemainExact() {
        val rows = listOf(order("invalid", "filled", "bad"), order("valid", "filled", "2026-01-01T00:00:00Z"))
        assertEquals("valid", RecordFilters.orders(rows, OrderSegment.RECENT, null).first().publicId)
        assertEquals("123.4567890123456789", fill(null).price.toPlainString())
    }
}
