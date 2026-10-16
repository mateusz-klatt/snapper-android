package ch.snapper.android.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Formatting never changes the exact domain value or rounds fractional financial digits. */
object DisplayNumbers {
    fun format(value: BigDecimal?, locale: Locale): String {
        val number = value?.stripTrailingZeros() ?: return "—"
        val plain = number.toPlainString()
        if (locale == Locale.ROOT) return plain
        return runCatching {
            NumberFormat.getNumberInstance(locale).apply {
                maximumFractionDigits = number.scale().coerceAtLeast(0)
                minimumFractionDigits = 0
                roundingMode = RoundingMode.UNNECESSARY
            }.format(number)
        }.getOrDefault(plain)
    }
}
