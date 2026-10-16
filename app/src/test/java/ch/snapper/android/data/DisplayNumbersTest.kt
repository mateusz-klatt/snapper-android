package ch.snapper.android.data

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNumbersTest {
    @Test fun countryFormattingKeepsEveryFractionalDigit() {
        val value = BigDecimal("1234.1234567890123456789")
        assertEquals("1,234.1234567890123456789", DisplayNumbers.format(value, Locale.US))
        assertEquals("1.234,1234567890123456789", DisplayNumbers.format(value, Locale.GERMANY))
        assertEquals("1234.1234567890123456789", DisplayNumbers.format(value, Locale.ROOT))
    }

    @Test fun tinyValuesAndUnavailableValuesRemainDistinctFromZero() {
        assertEquals("0.00000000000000000001", DisplayNumbers.format(BigDecimal("1e-20"), Locale.US))
        assertEquals("0", DisplayNumbers.format(BigDecimal("0.000"), Locale.US))
        assertEquals("—", DisplayNumbers.format(null, Locale.US))
    }
}
