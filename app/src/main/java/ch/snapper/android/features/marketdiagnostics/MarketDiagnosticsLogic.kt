package ch.snapper.android.features.marketdiagnostics

import ch.snapper.android.data.MarketPairStatsDto
import java.math.BigDecimal

object MarketDiagnosticsLogic {
    fun chartLimit(timeframe: String): Int = when (timeframe) {
        "1m" -> 100
        "5m" -> 19
        "15m" -> 5
        "1h", "4h", "1d" -> 25
        else -> throw IllegalArgumentException("Unsupported timeframe")
    }

    fun chartSource(timeframe: String): String = when (timeframe) {
        "1m" -> "cache"
        "5m", "15m" -> "derived"
        "1h", "4h", "1d" -> "db"
        else -> throw IllegalArgumentException("Unsupported timeframe")
    }

    fun chips(rows: List<MarketPairStatsDto>, exchange: String, instrument: String): List<MarketPairChip> {
        val key = "$exchange:$instrument"
        return rows.mapNotNull { row -> chip(row, key) }.sortedWith(
            compareBy<MarketPairChip, BigDecimal?>(nullsLast()) { it.pvalue }
                .thenByDescending { it.pearsonR?.abs() ?: BigDecimal.ZERO })
    }

    private fun chip(row: MarketPairStatsDto, self: String): MarketPairChip? {
        val other = when (self) { row.left -> row.right; row.right -> row.left; else -> return null }
        if (other == self) return null
        val separator = other.indexOf(':')
        if (separator <= 0 || separator == other.lastIndex) return null
        val exchange = other.substring(0, separator)
        val instrument = other.substring(separator + 1)
        if (exchange.isBlank() || instrument.isBlank() || exchange.length > 128 || instrument.length > 256) return null
        return MarketPairChip(exchange, instrument, row.pearsonR, row.cointPvalue, row.isWarm)
    }

    fun valid(row: MarketPairStatsDto): Boolean {
        if (row.pearsonN < 0 || row.sampleCount < 0) return false
        val decimals = listOfNotNull(row.pearsonR, row.cointT, row.cointPvalue) + row.cointCriticalValues.orEmpty()
        if (decimals.any { it.precision() > 128 || it.scale() !in -128..128 }) return false
        if (row.pearsonR != null && row.pearsonR.abs() > BigDecimal.ONE) return false
        if (row.cointPvalue != null && (row.cointPvalue < BigDecimal.ZERO || row.cointPvalue > BigDecimal.ONE)) return false
        return row.cointCriticalValues == null || row.cointCriticalValues.size == 3
    }
}
