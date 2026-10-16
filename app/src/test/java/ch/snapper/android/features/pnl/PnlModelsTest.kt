package ch.snapper.android.features.pnl

import ch.snapper.android.data.PnlTimelinePointDto
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PnlModelsTest {
    @Test fun decodedWithheldValuesStayNullAndProvenDecimalsStayExact() {
        val point = Json.decodeFromString<PnlTimelinePointDto>("""{
          "point_time":"2026-10-16T00:00:00Z",
          "realized_pnl":0.123456789012345678901,"fee_pnl":0,"accrual_pnl":null,
          "unrealized_pnl":null,"net_pnl":null,"equity":null,"cash":null,
          "position_value":null,"drawdown":null,"valuation_status":"incomplete",
          "incompleteness_reasons":[{"reason":"mark_unavailable","withholding_tier":"mark_incomplete","withholding_scope":"instrument","trigger_instrument_public_id":"i1"}],
          "per_instrument":[],"attribution":[]
        }""")
        assertEquals(BigDecimal("0.123456789012345678901"), point.realizedPnl)
        assertEquals(BigDecimal.ZERO, point.feePnl)
        assertNull(point.netPnl)
        assertNull(point.equity)
        assertEquals("i1", point.incompletenessReasons.single().triggerInstrumentPublicId)
    }
}
