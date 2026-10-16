package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalModelsTest {
    @Test fun envelopePreservesNumericAndQuotedFinancialValues() {
        val json = Json { ignoreUnknownKeys = true }
        val row = json.decodeFromString<Envelope<List<SignalDto>>>("""{"payload":[{"public_id":"s1","instrument":"BTC","exchange":"venue","side":"buy","strength":0.123456789123456789,"price":"1234.123456789123456789","reason":"fixture","fired_at":"2026-10-16T00:00:00Z","origin":"strategy"}]}""").payload.single()
        assertEquals(BigDecimal("0.123456789123456789"), row.strength)
        assertEquals(BigDecimal("1234.123456789123456789"), row.price)
        assertNull(row.walletPublicId)
        assertNull(row.strategyName)
    }
}
