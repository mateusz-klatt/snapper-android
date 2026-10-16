package ch.snapper.android.features.pnl

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Dates disambiguate midnight; explicit offsets disambiguate repeated daylight-saving hours. */
object PnlChartTimeLabels {
    fun format(times: List<Instant>, locale: Locale, zone: ZoneId): List<String> {
        if (times.isEmpty()) return emptyList()
        val local = times.map { it.atZone(zone) }
        val showDate = local.map { it.toLocalDate() }.distinct().size > 1
        val showOffset = local.map { it.offset }.distinct().size > 1
        val showTime = Duration.between(times.first(), times.last()) <= Duration.ofDays(2) || showOffset
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return local.map { stamp -> label(stamp, date, time, showDate, showTime, showOffset) }
    }

    private fun label(stamp: ZonedDateTime, date: DateTimeFormatter, time: DateTimeFormatter,
        showDate: Boolean, showTime: Boolean, showOffset: Boolean): String = buildList {
        if (showDate) add(date.format(stamp))
        if (showTime || !showDate) add(time.format(stamp))
        if (showOffset) add("UTC" + stamp.offset.id.replace("Z", "+00:00"))
    }.joinToString(" ")
}
