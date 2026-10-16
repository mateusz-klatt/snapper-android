package ch.snapper.android.features.pnl

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PnlChartTimeLabelsTest {
    @Test fun midnightLabelsIncludeLocalizedDatesAndTime() {
        val times = listOf(Instant.parse("2026-10-16T23:30:00Z"), Instant.parse("2026-10-17T00:30:00Z"))
        for (locale in listOf(Locale.US, Locale.GERMANY)) {
            val labels = PnlChartTimeLabels.format(times, locale, ZoneId.of("UTC"))
            times.forEachIndexed { index, instant ->
                val stamp = instant.atZone(ZoneId.of("UTC"))
                assertTrue(labels[index].contains(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(stamp)))
                assertTrue(labels[index].contains(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(stamp)))
                assertFalse(labels[index].contains("UTC"))
            }
            assertNotEquals(labels[0], labels[1])
        }
    }

    @Test fun repeatedDstHourIncludesDistinctEffectiveOffsets() {
        val times = listOf(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2026-10-25T01:30:00Z"))
        val labels = PnlChartTimeLabels.format(times, Locale.GERMANY, ZoneId.of("Europe/Warsaw"))
        assertEquals(listOf("02:30 UTC+02:00", "02:30 UTC+01:00"), labels)
    }

    @Test fun sameDayRemainsCompactAndLongRangesRetainDates() {
        val first = Instant.parse("2026-10-16T10:00:00Z")
        val zone = ZoneId.of("UTC")
        assertEquals(listOf("10:00", "11:00"), PnlChartTimeLabels.format(listOf(first, first.plusSeconds(3_600)), Locale.GERMANY, zone))
        val last = first.plusSeconds(4 * 86_400)
        val expected = listOf(first, last).map { DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.US).withZone(zone).format(it) }
        assertEquals(expected, PnlChartTimeLabels.format(listOf(first, last), Locale.US, zone))
        assertTrue(PnlChartTimeLabels.format(emptyList(), Locale.US, zone).isEmpty())
    }
}
