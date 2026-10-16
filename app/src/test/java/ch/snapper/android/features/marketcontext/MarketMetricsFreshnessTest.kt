package ch.snapper.android.features.marketcontext

import ch.snapper.android.data.MarketCachedCandleDto
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MarketMetricsFreshnessTest {
    private val hour = 3_600_000L
    private val boundary = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private val now = boundary + hour / 2
    private fun rows(): List<MarketCachedCandleDto> = (0..24).map { index ->
        val price = BigDecimal(100 + index)
        MarketCachedCandleDto(boundary - (25 - index) * hour, "1h", price, price, price, price, BigDecimal.ZERO)
    }
    private fun unavailable(result: MarketMetrics) {
        assertNull(result.changePercent); assertNull(result.high); assertNull(result.low)
    }

    @Test fun recentAlignedCoverageUsesPreviousCloseAndOnlyFollowingTwentyFourExtrema() {
        val bars = rows().toMutableList()
        bars[0] = bars[0].copy(open = BigDecimal(50), low = BigDecimal.ONE, high = BigDecimal(999))
        val result = MarketMetricsCalculator.calculate(bars, now)
        assertEquals(0, BigDecimal(24).compareTo(requireNotNull(result.changePercent)))
        assertEquals(BigDecimal(124), result.high); assertEquals(BigDecimal(101), result.low)
        assertEquals(BigDecimal(124), result.lastPrice); assertEquals(boundary, result.asOfMillis)
    }

    @Test fun ancientFullCacheAndMissingMiddleHourCannotClaimDailyWindow() {
        val ancient = rows().map { it.copy(openAtMs = it.openAtMs - 30 * 24 * hour) }
        val ancientResult = MarketMetricsCalculator.calculate(ancient, now)
        unavailable(ancientResult); assertEquals(boundary - 30 * 24 * hour, ancientResult.asOfMillis)
        assertEquals(BigDecimal(124), ancientResult.lastPrice)
        val gappy = rows().filterIndexed { index, _ -> index != 12 }
        unavailable(MarketMetricsCalculator.calculate(gappy, now))
    }

    @Test fun PartialAndUnalignedCoverageAreWithheld() {
        unavailable(MarketMetricsCalculator.calculate(rows().takeLast(24), now))
        unavailable(MarketMetricsCalculator.calculate(rows().map { it.copy(openAtMs = it.openAtMs - 1) }, now))
        unavailable(MarketMetricsCalculator.calculate(rows().map { it.copy(openAtMs = it.openAtMs - hour) }, now))
    }

    @Test fun identicalDuplicatesAndInputOrderDoNotChangeWindowButCannotFillMissingHour() {
        val ordered = rows()
        val expected = MarketMetricsCalculator.calculate(ordered, now)
        assertEquals(expected, MarketMetricsCalculator.calculate(ordered.reversed() + ordered[12], now))
        unavailable(MarketMetricsCalculator.calculate(ordered.drop(1) + ordered[12], now))
    }

    @Test fun conflictingDuplicateIsWithheldRatherThanSelectedByResponseOrder() {
        val original = rows()
        val conflicting = original[12].copy(volume = BigDecimal.ONE)
        unavailable(MarketMetricsCalculator.calculate(original + conflicting, now))
        unavailable(MarketMetricsCalculator.calculate(listOf(conflicting) + original, now))
    }

    @Test fun olderExtraBarsTrimAwayAndUnclosedCurrentBarCannotMoveAsOfIntoFuture() {
        val bars = rows()
        val old = bars.first().copy(openAtMs = bars.first().openAtMs - hour)
        val current = bars.last().copy(openAtMs = boundary, close = BigDecimal(999), high = BigDecimal(999))
        assertEquals(MarketMetricsCalculator.calculate(bars, now), MarketMetricsCalculator.calculate(listOf(old) + bars + current, now))
    }

    @Test fun zeroBaselinePreservesExtremaButCannotProducePercentage() {
        val bars = rows().toMutableList()
        bars[0] = bars[0].copy(open = BigDecimal.ZERO, close = BigDecimal.ZERO, low = BigDecimal.ZERO)
        val result = MarketMetricsCalculator.calculate(bars, now)
        assertNull(result.changePercent); assertEquals(BigDecimal(124), result.high)
    }
}
