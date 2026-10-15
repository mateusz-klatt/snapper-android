package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiModelsTest {
    @Test fun decimalPreservesPrecisionAcrossWireShapes() {
        val expected = BigDecimal("12345678901234567890.1234567890123456789")
        assertEquals(expected, Json.decodeFromString(DecimalSerializer, expected.toPlainString()))
        assertEquals(expected, Json.decodeFromString(DecimalSerializer, "\"$expected\""))
        assertEquals("\"${expected.toPlainString()}\"", Json.encodeToString(DecimalSerializer, expected))
        assertEquals("\"100000000000000000000\"", Json.encodeToString(DecimalSerializer, BigDecimal("1E20")))
    }
    @Test fun invalidDecimalFailsInsteadOfRoundingOrDefaulting() {
        listOf("true", "null", "[]", "\"NaN\"").forEach {
            assertThrows(SerializationException::class.java) { Json.decodeFromString(DecimalSerializer, it) }
        }
    }
    @Test fun listEnvelopeAndNullablePermissionsMatchBackend() {
        val envelope = Json.decodeFromString<Envelope<List<UserDto>>>("""{"payload":[{"username":"reader","role":"viewer","effective_permissions":null}],"count":1}""")
        assertEquals("reader", envelope.payload.single().username)
        assertFalse(envelope.payload.single().hasPermission("read:orders"))
        assertEquals(1, envelope.count)
    }
}
