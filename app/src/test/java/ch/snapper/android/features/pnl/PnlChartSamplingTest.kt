package ch.snapper.android.features.pnl

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PnlChartSamplingTest {
    private fun samples(size: Int, value: (Int) -> BigDecimal?) = List(size) { index ->
        PnlChartSample(Instant.EPOCH.plusSeconds(index.toLong()), value(index), null, null)
    }

    @Test fun soleSpikeAndTroughSurviveDefaultBudgetBoundary() {
        for (extremum in listOf(BigDecimal("1e400"), BigDecimal("-1e-400"))) {
            val model = PnlChartModelBuilder.build(samples(2_001) { if (it == 1_999) extremum else BigDecimal.ZERO })
            val points = model.lines.first().segments.flatten()
            assertTrue(points.any { it.index == 1_999 })
            assertEquals(0, points.first().index)
            assertEquals(2_000, points.last().index)
            assertTrue(points.size <= 2_000)
        }
    }

    @Test fun bothInteriorExtremaSurviveEvenWhenVeryCloseToEachOther() {
        val points = PnlChartModelBuilder.build(samples(2_001) {
            when (it) { 1_998 -> BigDecimal.TEN; 1_999 -> BigDecimal.TEN.negate(); else -> BigDecimal.ZERO }
        }, 4).lines.first().segments.flatten()
        assertEquals(listOf(0, 1_998, 1_999, 2_000), points.map { it.index })
    }

    @Test fun everyBudgetIsBoundedAndPreservesLatestKnownSampleWithoutCrossingNulls() {
        val source = samples(33) { if (it == 12 || it == 32) null else BigDecimal(it % 7 - 3) }
        for (budget in 1..33) {
            val line = PnlChartModelBuilder.build(source, budget).lines.first()
            val points = line.segments.flatten()
            assertTrue("budget $budget", points.size <= budget)
            assertEquals(31, points.last().index)
            assertNull(line.latest)
            if (budget >= 2) assertEquals(0, points.first().index)
            assertTrue(line.segments.none { segment -> segment.first().index < 12 && segment.last().index > 12 })
        }
    }

    @Test fun smallBudgetsHaveExplicitEndpointAndDeviationBehavior() {
        val source = samples(5) { if (it == 2) BigDecimal("0.00000000000000000000000001") else BigDecimal.ZERO }
        fun selected(budget: Int) = PnlChartModelBuilder.build(source, budget).lines.first().segments.flatten().map { it.index }
        assertEquals(listOf(4), selected(1))
        assertEquals(listOf(0, 4), selected(2))
        assertEquals(listOf(0, 2, 4), selected(3))
        assertEquals((0..4).toList(), selected(5))
    }
}
