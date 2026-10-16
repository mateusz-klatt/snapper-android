package ch.snapper.android.data

import java.math.BigDecimal
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BacktestPresentationTest {
    @Test fun statusRecognizesAllBackendValuesRegardlessOfCaseAndFutureValuesStayUnknown() {
        BacktestStatus.entries.forEach { status ->
            assertEquals(status, BacktestPresentation.status(status.name.lowercase(Locale.ROOT)))
            assertEquals(status, BacktestPresentation.status(status.name))
        }
        assertEquals(BacktestStatus.UNKNOWN, BacktestPresentation.status("future-state"))
        assertEquals(BacktestStatus.UNKNOWN, BacktestPresentation.status(""))
    }

    @Test fun periodUsesUtcCalendarDateAcrossOffsetsAndDeviceTimeZones() {
        val previous = TimeZone.getDefault()
        try {
            listOf("Pacific/Kiritimati", "America/Los_Angeles").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("Oct 1, 2026", BacktestPresentation.date("2026-10-02T00:30:00+02:00", Locale.US))
                assertEquals("Oct 2, 2026", BacktestPresentation.date("2026-10-01T23:30:00-02:00", Locale.US))
                assertEquals("Oct 1, 2026", BacktestPresentation.date("2026-10-01", Locale.US))
                assertEquals("1 oct. 2026", BacktestPresentation.date("2026-10-01", Locale.FRANCE))
            }
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun malformedOrUnzonedPeriodsAreRetainedRatherThanAssignedAnAssumedZone() {
        listOf("invalid", "2026-02-30", "2026-10-01T12:00:00", "").forEach {
            assertEquals(it, BacktestPresentation.date(it, Locale.US))
        }
    }

    @Test fun cashUsesUsdAndTwoDisplayDecimalsWithoutChangingExactDomainValue() {
        val value = BigDecimal("12345678901234567890.125")
        assertEquals("$12,345,678,901,234,567,890.12", BacktestPresentation.initialCash(value, Locale.US))
        assertEquals(BigDecimal("12345678901234567890.125"), value)
        assertEquals("$1.14", BacktestPresentation.initialCash(BigDecimal("1.135"), Locale.US))
        assertEquals("$0.00", BacktestPresentation.initialCash(BigDecimal("0.00001"), Locale.US))
        assertEquals("-$1.12", BacktestPresentation.initialCash(BigDecimal("-1.125"), Locale.US))
        assertEquals("1.234,50 $", BacktestPresentation.initialCash(BigDecimal("1234.5"), Locale.GERMANY))
        assertFalse(BacktestPresentation.initialCash(BigDecimal("1E+30"), Locale.US).contains("E"))
    }
}
