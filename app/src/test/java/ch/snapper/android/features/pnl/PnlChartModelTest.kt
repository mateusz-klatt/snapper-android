package ch.snapper.android.features.pnl

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PnlChartModelTest {
    private val start = Instant.parse("2026-10-16T00:00:00Z")
    private fun sample(index: Int, net: String?, realized: String? = null, unrealized: String? = null) =
        PnlChartSample(start.plusSeconds(index.toLong()), net?.let(::BigDecimal), realized?.let(::BigDecimal), unrealized?.let(::BigDecimal))

    @Test fun allSeriesShareOneDomainRatherThanIndependentlyStretching() {
        val model = PnlChartModelBuilder.build(listOf(sample(0, "1", "100", "1"), sample(1, "2", "200", "2")))
        val net = model.lines[0].segments.single().first().y
        val realized = model.lines[1].segments.single().first().y
        assertTrue(net < BigDecimal("0.02")); assertTrue(realized > BigDecimal("0.4"))
        assertEquals(net, model.lines[2].segments.single().first().y)
        assertTrue(model.domain.minimum <= BigDecimal.ONE); assertTrue(model.domain.maximum >= BigDecimal(200))
    }

    @Test fun hugeAndTinyValuesDoNotDowngradeBeforeNormalizedGeometry() {
        for (exponent in listOf(400, -400)) {
            val model = PnlChartModelBuilder.build(listOf(sample(0, "1e$exponent"), sample(1, "2e$exponent")))
            val line = model.lines.first()
            assertEquals(BigDecimal("2e$exponent"), line.latest)
            val points = line.segments.single()
            assertTrue(points[0].y < points[1].y)
            assertTrue(points.all { it.y.toFloat().isFinite() && it.y >= BigDecimal.ZERO && it.y <= BigDecimal.ONE })
        }
    }

    @Test fun tinyDifferenceOnHugeBalanceSurvivesSharedDomainArithmetic() {
        val high = BigDecimal("1000000000000000000000000000000000000000000000000.01")
        val first = PnlChartSample(start, high, null, null)
        val second = first.copy(time = start.plusSeconds(1), net = high.add(BigDecimal("0.01")))
        val points = PnlChartModelBuilder.build(listOf(first, second)).lines.first().segments.single()
        assertTrue(points.last().y > points.first().y)
        assertEquals(second.net, PnlChartModelBuilder.build(listOf(first, second)).lines.first().latest)
        val gap = second.copy(time = start.plusSeconds(2), net = null)
        val zero = second.copy(time = start.plusSeconds(3), net = BigDecimal.ZERO)
        assertEquals(listOf(2, 1), PnlChartModelBuilder.build(listOf(first, second, gap, zero)).lines.first().segments.map { it.size })
    }

    @Test fun nullsBreakEachSeriesIndependentlyAndTerminalNullIsWithheld() {
        val model = PnlChartModelBuilder.build(listOf(sample(0, "1", "10"), sample(1, null, "11"), sample(2, "3", null)))
        assertEquals(listOf(listOf(0), listOf(2)), model.lines[0].segments.map { run -> run.map { it.index } })
        assertEquals(listOf(listOf(0, 1)), model.lines[1].segments.map { run -> run.map { it.index } })
        assertNull(model.lines[1].latest)
    }

    @Test fun boundedDecimationNeverBridgesNullAndRetainsTerminalSample() {
        val source = (0 until 10_000).map { sample(it, if (it % 2 == 0) it.toString() else null) }
        val line = PnlChartModelBuilder.build(source, 128).lines.first()
        assertEquals(128, line.segments.sumOf { it.size })
        assertTrue(line.segments.all { it.size == 1 })
        assertEquals(9_998, line.segments.last().last().index)
        assertNull(line.latest)
    }

    @Test fun continuousRunBudgetPreservesBothEndpointsAndOneSampleBudgetPreservesLast() {
        val source = (0..99).map { sample(it, it.toString()) }
        val line = PnlChartModelBuilder.build(source, 10).lines.first().segments.single()
        assertEquals(10, line.size); assertEquals(0, line.first().index); assertEquals(99, line.last().index)
        assertEquals(99, PnlChartModelBuilder.build(source, 1).lines.first().segments.single().single().index)
    }

    @Test fun singletonIsCenteredAndFlatNegativeDomainRemainsNonzero() {
        val model = PnlChartModelBuilder.build(listOf(sample(0, "-5", "-5")))
        val point = model.lines.first().segments.single().single()
        assertEquals(BigDecimal("0.5"), point.x)
        assertTrue(model.domain.maximum > model.domain.minimum)
        assertEquals(BigDecimal("-5"), model.lines.first().latest)
    }

    @Test fun emptyAndAllNullKeepEmptyRunsInsteadOfFabricatingZeroValues() {
        val empty = PnlChartModelBuilder.build(emptyList())
        assertTrue(empty.times.isEmpty()); assertTrue(empty.lines.all { it.latest == null && it.segments.isEmpty() })
        val missing = PnlChartModelBuilder.build(listOf(sample(0, null), sample(1, null)))
        assertTrue(missing.lines.all { it.latest == null && it.segments.isEmpty() })
    }

    @Test fun actualTimeSpacingAndNanosecondsArePreserved() {
        val uneven = listOf(sample(0, "1"), sample(1, "2"), sample(10, "3"))
        val points = PnlChartModelBuilder.build(uneven).lines.first().segments.single()
        assertEquals(0, BigDecimal("0.1").compareTo(points[1].x))
        val nanos = listOf(sample(0, "1"), sample(0, "2").copy(time = start.plusNanos(1)), sample(0, "3").copy(time = start.plusNanos(2)))
        assertEquals(0, BigDecimal("0.5").compareTo(PnlChartModelBuilder.build(nanos).lines.first().segments.single()[1].x))
    }

    @Test fun duplicateUnorderedOrUnboundedInputIsRejectedInsteadOfDrawingMisleadingGeometry() {
        assertTrue(runCatching { PnlChartModelBuilder.build(listOf(sample(0, "1"), sample(0, "2"))) }.isFailure)
        assertTrue(runCatching { PnlChartModelBuilder.build(listOf(sample(2, "1"), sample(1, "2"))) }.isFailure)
        assertTrue(runCatching { PnlChartModelBuilder.build(listOf(sample(0, "1e999999"))) }.isFailure)
        assertTrue(runCatching { PnlChartModelBuilder.build(emptyList(), 0) }.isFailure)
    }
}
