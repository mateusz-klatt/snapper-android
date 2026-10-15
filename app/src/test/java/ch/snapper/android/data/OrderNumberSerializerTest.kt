package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OrderNumberSerializerTest {
    @Test fun decimalAndExponentTokensKeepEveryDigitWithoutQuotes() {
        for (input in listOf("0.123456789123456789", "123.456789123456789", "1.23456789123456789E+100", "4.9406564584124654E-324", "1.7976931348623157E+308")) {
            val value = BigDecimal(input)
            val encoded = Json.encodeToString(OrderNumberSerializer, value)
            val token = Json.parseToJsonElement(encoded).jsonPrimitive
            assertFalse(encoded, token.isString)
            assertEquals(value.toString(), token.content)
            assertEquals(value, Json.decodeFromString(OrderNumberSerializer, encoded))
        }
    }
    @Test fun invalidOrOutOfRangeNumbersCannotProduceOutboundTokens() {
        for (input in listOf("0", "-1", "1E-324", "1.7976931348623158E308", "1E-1001", "1E1001", "1." + "1".repeat(100))) {
            assertThrows(IllegalArgumentException::class.java) {
                Json.encodeToString(OrderNumberSerializer, BigDecimal(input))
            }
        }
    }
    @Test fun regularDecimalSerializerStillWritesQuotedDecimals() {
        val value = BigDecimal("0.123456789123456789")
        assertEquals("\"0.123456789123456789\"", Json.encodeToString(DecimalSerializer, value))
    }
}
