package ch.snapper.android.features.signals

import ch.snapper.android.data.SignalDto
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

object SignalCsv {
    const val filename = "signals.csv"
    private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    private val header = listOf("instrument", "exchange", "side", "strength", "strategy", "price", "reason", "fired_at")

    /** Protect textual cells from spreadsheet formula execution before RFC-4180 quoting. */
    private fun text(value: String): String {
        val candidate = value.trimStart()
        val guarded = if (candidate.firstOrNull() in setOf('=', '+', '-', '@') || value.firstOrNull() in setOf('\t', '\r', '\n')) "'$value" else value
        return quote(guarded)
    }

    private fun quote(value: String): String = if (value.any { it in ",\"\r\n" }) "\"${value.replace("\"", "\"\"")}\"" else value

    fun encode(rows: List<SignalDto>): ByteArray {
        val records = rows.map { row ->
            listOf(text(row.instrument), text(row.exchange), text(row.side), row.strength.toPlainString(), text(row.strategyName.orEmpty()),
                row.price?.toPlainString().orEmpty(), text(row.reason), timestamp.format(Instant.parse(row.firedAt))).joinToString(",")
        }
        return (listOf(header.joinToString(",")) + records).joinToString("\r\n").toByteArray(Charsets.UTF_8)
    }
}
