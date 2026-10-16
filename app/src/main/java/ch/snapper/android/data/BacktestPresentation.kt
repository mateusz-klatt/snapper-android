package ch.snapper.android.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DateTimeException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

enum class BacktestStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, UNKNOWN }

/** Display-only conventions shared with iOS backtest rows; domain values remain exact. */
object BacktestPresentation {
    fun status(raw: String): BacktestStatus = BacktestStatus.entries.firstOrNull {
        it.name.equals(raw, ignoreCase = true)
    } ?: BacktestStatus.UNKNOWN

    /** Backtest periods are UTC calendar dates, independent of the device's current zone. */
    fun date(source: String, locale: Locale): String = try {
        val date = if (source.length == 10) LocalDate.parse(source)
            else OffsetDateTime.parse(source).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .withDecimalStyle(DecimalStyle.STANDARD).format(date)
    } catch (_: DateTimeException) {
        source
    }

    /** USD with two displayed decimals, matching iOS; never converts through floating point. */
    fun initialCash(value: BigDecimal, locale: Locale): String = NumberFormat.getCurrencyInstance(locale).apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_EVEN
    }.format(value)
}
