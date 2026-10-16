package ch.snapper.android

import ch.snapper.android.data.SignalDto
import ch.snapper.android.features.signals.SignalCsv
import ch.snapper.android.features.signals.SignalsState
import ch.snapper.android.features.signals.SignalStrengthTier
import ch.snapper.android.features.signals.signalStrengthTier
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class SignalCsvTest {
    private fun row() = SignalDto("s1", "BTC", "venue", "BUY", BigDecimal("0.8123456789123456789"), "reason", "2026-10-16T01:02:03.123456Z", BigDecimal("12345.123456789123456789"), "strategy", "wallet")

    @Test fun exactDecimalUtcMillisecondsAndCrlfArePreserved() {
        val csv = SignalCsv.encode(listOf(row())).toString(Charsets.UTF_8)
        assertEquals("instrument,exchange,side,strength,strategy,price,reason,fired_at\r\nBTC,venue,BUY,0.8123456789123456789,strategy,12345.123456789123456789,reason,2026-10-16T01:02:03.123Z", csv)
    }

    @Test fun quotedUtf8TextAndFormulaPrefixesAreSafe() {
        val csv = SignalCsv.encode(listOf(row().copy(instrument = "=SUM(A1)", exchange = " @cmd", reason = "żółć,\"quoted\"\nline", price = null, strategyName = null))).toString(Charsets.UTF_8)
        assertTrue(csv.contains("'=SUM(A1),' @cmd"))
        assertTrue(csv.contains("\"żółć,\"\"quoted\"\"\nline\""))
        assertTrue(csv.contains(",,,\"żółć"))
    }

    @Test fun filteringAndStatsRespectWalletAndUnknownSide() {
        val state = SignalsState(allowed = true, walletPublicId = "wallet", rows = listOf(row(), row().copy(publicId = "s2", side = "other", walletPublicId = "foreign"), row().copy(publicId = "s3", side = "sell", strategyName = "second")))
        assertEquals(2, state.summary.total)
        assertEquals(1, state.summary.buy)
        assertEquals(1, state.summary.sell)
        assertEquals(1, state.copy(selectedStrategy = "strategy").visibleRows.size)
        assertEquals(0, state.copy(allowed = false).visibleRows.size)
        assertEquals(listOf("second", "strategy"), state.strategies)
    }

    @Test fun strengthBoundariesMatchIos() {
        assertEquals(SignalStrengthTier.STRONG, signalStrengthTier(BigDecimal("0.8")))
        assertEquals(SignalStrengthTier.MEDIUM, signalStrengthTier(BigDecimal("0.6")))
        assertEquals(SignalStrengthTier.WEAK, signalStrengthTier(BigDecimal("0.4")))
        assertEquals(SignalStrengthTier.VERY_WEAK, signalStrengthTier(BigDecimal("0.399")))
    }
}
