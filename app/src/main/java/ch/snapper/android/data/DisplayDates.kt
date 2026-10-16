package ch.snapper.android.data

import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/** Presentation only. Never apply to API queries, sorting, matching, persistence or CSV exports. */
object DisplayDates {
    /**
     * Requires an ISO timestamp with an explicit offset (including Z). Local timestamps are
     * deliberately not interpreted using an assumed zone. Invalid input is retained verbatim.
     * Date/time grammar follows locale; Latin digits match the financial display convention.
     * The UTC offset at that instant remains visible, including across a DST overlap.
     * Fractional seconds are parsed exactly but ordinary display is at second precision.
     */
    fun format(source: String?, locale: Locale, zone: ZoneId): String {
        if (source == null) return "—"
        return try {
            val instant = OffsetDateTime.parse(source, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            val zoned = instant.atZone(zone)
            val label = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(locale).withDecimalStyle(DecimalStyle.STANDARD).format(zoned)
            "$label UTC${zoned.offset.id.takeUnless { it == "Z" } ?: ""}"
        } catch (_: DateTimeException) {
            source
        }
    }
}
