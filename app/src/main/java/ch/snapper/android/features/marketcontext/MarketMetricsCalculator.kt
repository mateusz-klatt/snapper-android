package ch.snapper.android.features.marketcontext

import ch.snapper.android.data.MarketCachedCandleDto
import java.math.BigDecimal
import java.math.MathContext

/** Twenty-four closed hourly intervals, anchored by the preceding close; cache warmth is not freshness. */
object MarketMetricsCalculator {
    private const val HOUR_MS = 3_600_000L

    fun calculate(rows: List<MarketCachedCandleDto>, nowMs: Long): MarketMetrics {
        val sorted = uniqueClosedBars(rows, nowMs)
        val latest = sorted.lastOrNull()
        val cached = MarketMetrics(lastPrice = latest?.close, asOfMillis = latest?.openAtMs?.plus(HOUR_MS))
        if (!completeWindow(sorted, nowMs)) return cached
        val baseline = sorted.first().close
        val window = sorted.drop(1)
        val change = if (baseline.signum() > 0) window.last().close.subtract(baseline)
            .multiply(BigDecimal(100)).divide(baseline, MathContext.DECIMAL128) else null
        return cached.copy(changePercent = change, high = window.maxOf { it.high }, low = window.minOf { it.low })
    }

    /** Identical duplicates collapse; conflicting versions of an hour are withheld rather than selected by response order. */
    private fun uniqueClosedBars(rows: List<MarketCachedCandleDto>, nowMs: Long): List<MarketCachedCandleDto> =
        rows.filter { valid(it, nowMs) }.groupBy { it.openAtMs }.values
            .mapNotNull { it.distinct().singleOrNull() }.sortedBy { it.openAtMs }.takeLast(25)

    private fun completeWindow(rows: List<MarketCachedCandleDto>, nowMs: Long): Boolean {
        if (rows.size != 25 || nowMs < 25 * HOUR_MS) return false
        val boundary = nowMs / HOUR_MS * HOUR_MS
        val anchor = boundary - 25 * HOUR_MS
        return rows.withIndex().all { (index, row) -> row.openAtMs == anchor + index * HOUR_MS }
    }

    private fun valid(row: MarketCachedCandleDto, nowMs: Long): Boolean {
        if (row.timeframe != "1h" || nowMs < HOUR_MS || row.openAtMs !in 0..(nowMs - HOUR_MS)) return false
        if (listOf(row.open, row.high, row.low, row.close, row.volume).any { it.precision() > 128 || it.scale() !in -128..128 }) return false
        return row.volume.signum() >= 0 && row.low <= row.open && row.low <= row.close &&
            row.high >= row.open && row.high >= row.close && row.low <= row.high
    }
}
