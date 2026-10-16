package ch.snapper.android.data

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DisplayDatesTest {
    @Test fun pythonMicrosecondsUtcAndEquivalentOffsetsRenderTheSameInstant() {
        val utc = DisplayDates.format("2026-10-16T12:34:56.123456Z", Locale.US, ZoneOffset.UTC)
        val offset = DisplayDates.format("2026-10-16T14:34:56.123456+02:00", Locale.US, ZoneOffset.UTC)
        assertEquals(utc, offset)
        assertTrue(utc.contains("12:34:56"))
        assertTrue(utc.endsWith("UTC"))
    }

    @Test fun nanosecondAndZeroFractionFormsRemainValid() {
        val expected = DisplayDates.format("2026-10-16T12:34:56Z", Locale.US, ZoneOffset.UTC)
        assertEquals(expected, DisplayDates.format("2026-10-16T12:34:56.000000+00:00", Locale.US, ZoneOffset.UTC))
        assertEquals(expected, DisplayDates.format("2026-10-16T12:34:56.123456789Z", Locale.US, ZoneOffset.UTC))
    }

    @Test fun nullInvalidMissingOffsetAndBlankAreNotInventedDates() {
        assertEquals("—", DisplayDates.format(null, Locale.US, ZoneOffset.UTC))
        for (value in listOf("", "bad", "2026-10-16T12:34:56", "2026-02-30T12:34:56Z", "2026-10-16", " 2026-10-16T12:34:56Z ")) {
            assertEquals(value, DisplayDates.format(value, Locale.US, ZoneOffset.UTC))
        }
    }

    @Test fun regionChangesDateGrammarWhileExplicitDeviceZoneControlsClock() {
        val source = "2026-10-16T12:34:56Z"
        val us = DisplayDates.format(source, DisplayPreferences("us").formattingLocale(Locale.US), ZoneOffset.UTC)
        val pl = DisplayDates.format(source, DisplayPreferences("pl").formattingLocale(Locale.US), ZoneOffset.UTC)
        assertNotEquals(us, pl)
        assertTrue(pl.contains("2026"))
        val warsaw = DisplayDates.format(source, Locale.US, ZoneId.of("Europe/Warsaw"))
        assertTrue(warsaw.endsWith("UTC+02:00"))
        assertNotEquals(us, warsaw)
    }

    @Test fun dstOverlapKeepsIdenticalLocalClockUnambiguous() {
        val zone = ZoneId.of("Europe/Warsaw")
        val first = DisplayDates.format("2026-10-25T00:30:00Z", Locale.UK, zone)
        val second = DisplayDates.format("2026-10-25T01:30:00Z", Locale.UK, zone)
        assertEquals(first.substringBefore(" UTC"), second.substringBefore(" UTC"))
        assertTrue(first.endsWith("UTC+02:00"))
        assertTrue(second.endsWith("UTC+01:00"))
        assertNotEquals(first, second)
    }

    @Test fun nonLatinLocaleStillUsesLatinNumericDigits() {
        val formatted = DisplayDates.format("2026-10-16T12:34:56Z",
            DisplayPreferences("ae").formattingLocale(Locale.US), ZoneOffset.UTC)
        assertTrue(formatted.any { it in '0'..'9' })
        assertFalse(formatted.any { it.isDigit() && it !in '0'..'9' })
    }

    @Test fun explicitZoneChangesCalendarDateAcrossMidnight() {
        val source = "2026-10-16T01:00:00Z"
        val utc = DisplayDates.format(source, Locale.US, ZoneOffset.UTC)
        val hawaii = DisplayDates.format(source, Locale.US, ZoneId.of("Pacific/Honolulu"))
        assertTrue(utc.contains("Oct 16"))
        assertTrue(hawaii.contains("Oct 15"))
        assertTrue(hawaii.endsWith("UTC-10:00"))
    }
}
