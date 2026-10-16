package ch.snapper.android.features.pnl

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

/** Series colors identify these categories; their identity never depends on the sign of a value. */
enum class PnlChartSeries { NET, REALIZED, UNREALIZED }
data class PnlChartSample(val time: Instant, val net: BigDecimal?, val realized: BigDecimal?, val unrealized: BigDecimal?) {
    fun value(series: PnlChartSeries): BigDecimal? = when (series) {
        PnlChartSeries.NET -> net
        PnlChartSeries.REALIZED -> realized
        PnlChartSeries.UNREALIZED -> unrealized
    }
}
data class PnlChartPoint(val index: Int, val x: BigDecimal, val y: BigDecimal)
data class PnlChartTimeTick(val time: Instant, val x: BigDecimal)
data class PnlChartDomain(val minimum: BigDecimal, val maximum: BigDecimal, val ticks: List<BigDecimal>) {
    fun fraction(value: BigDecimal): BigDecimal = value.subtract(minimum).divide(maximum.subtract(minimum), MathContext.DECIMAL128)
}
data class PnlChartLine(val series: PnlChartSeries, val segments: List<List<PnlChartPoint>>, val latest: BigDecimal?)
data class PnlChartModel(val domain: PnlChartDomain, val lines: List<PnlChartLine>, val times: List<PnlChartTimeTick>)

object PnlChartModelBuilder {
    private val middle = BigDecimal("0.5")
    private val defaultDomain = PnlChartDomain(BigDecimal.ONE.negate(), BigDecimal.ONE, listOf(BigDecimal.ONE.negate(), BigDecimal.ZERO, BigDecimal.ONE))

    /** No source values are rounded. A hard drawing budget may omit samples, but never joins across an omitted null. */
    fun build(samples: List<PnlChartSample>, maxSamples: Int = 2_000): PnlChartModel {
        require(maxSamples in 1..2_000)
        require(samples.zipWithNext().all { (a, b) -> a.time < b.time })
        val known = samples.flatMap { listOfNotNull(it.net, it.realized, it.unrealized) }
        require(known.all { it.precision() <= 256 && it.scale() in -2_048..2_048 })
        val domain = domain(known)
        val lines = PnlChartSeries.entries.map { series ->
            val values = samples.map { it.value(series) }
            PnlChartLine(series, segments(samples, values, domain, maxSamples), values.lastOrNull())
        }
        val indices = timeIndices(samples.size)
        return PnlChartModel(domain, lines, indices.map { PnlChartTimeTick(samples[it].time, x(samples, it)) })
    }

    private fun domain(values: List<BigDecimal>): PnlChartDomain {
        if (values.isEmpty()) return defaultDomain
        var minimum = requireNotNull(values.minOrNull())
        var maximum = requireNotNull(values.maxOrNull())
        if (minimum.compareTo(maximum) == 0) {
            val padding = minimum.abs().multiply(BigDecimal("0.05")).takeIf { it.signum() != 0 } ?: BigDecimal.ONE
            minimum = minimum.subtract(padding); maximum = maximum.add(padding)
        }
        val rawStep = maximum.subtract(minimum).divide(BigDecimal(4), MathContext.DECIMAL128)
        val unit = BigDecimal.ONE.scaleByPowerOfTen(rawStep.precision() - rawStep.scale() - 1)
        val multiple = listOf(1, 2, 5, 10).first { unit.multiply(BigDecimal(it)) >= rawStep }
        val step = unit.multiply(BigDecimal(multiple))
        val lower = minimum.divide(step, 0, RoundingMode.FLOOR).multiply(step)
        val upper = maximum.divide(step, 0, RoundingMode.CEILING).multiply(step)
        val count = upper.subtract(lower).divide(step).intValueExact()
        return PnlChartDomain(lower, upper, (0..count).map { lower.add(step.multiply(BigDecimal(it))) })
    }

    private fun segments(samples: List<PnlChartSample>, values: List<BigDecimal?>, domain: PnlChartDomain, budget: Int): List<List<PnlChartPoint>> {
        val known = values.indices.filter { values[it] != null }
        val picked = select(known, values, budget)
        val missing = IntArray(values.size + 1)
        values.forEachIndexed { index, value -> missing[index + 1] = missing[index] + if (value == null) 1 else 0 }
        val runs = mutableListOf<MutableList<PnlChartPoint>>()
        var previous: Int? = null
        for (index in picked) {
            if (previous == null || missing[index] > missing[previous + 1]) runs.add(mutableListOf())
            runs.last().add(PnlChartPoint(index, x(samples, index), domain.fraction(requireNotNull(values[index]))))
            previous = index
        }
        return runs
    }

    /** Endpoints plus each bucket's exact extrema. Odd budgets may leave one slot unused.
     * One slot keeps the latest known sample; two keep endpoints; three add the greatest
     * deviation from the endpoint line. These small budgets cannot preserve every extremum.
     */
    private fun select(indices: List<Int>, values: List<BigDecimal?>, budget: Int): List<Int> {
        if (indices.size <= budget) return indices
        if (budget == 1) return listOf(indices.last())
        val endpoints = listOf(indices.first(), indices.last())
        if (budget == 2) return endpoints
        val interior = indices.subList(1, indices.lastIndex)
        if (budget == 3) return (endpoints + greatestDeviation(indices, values)).sorted()
        val buckets = (budget - 2) / 2
        val selected = endpoints.toMutableSet()
        repeat(buckets) { bucket ->
            val from = (bucket.toLong() * interior.size / buckets).toInt()
            val to = ((bucket + 1L) * interior.size / buckets).toInt()
            val entries = interior.subList(from, to)
            selected.add(entries.minBy { requireNotNull(values[it]) })
            // Last maximum also retains both ends of a constant bucket.
            selected.add(entries.asReversed().maxBy { requireNotNull(values[it]) })
        }
        return selected.sorted()
    }

    private fun greatestDeviation(indices: List<Int>, values: List<BigDecimal?>): Int {
        val first = indices.first()
        val span = BigDecimal(indices.last() - first)
        val baseline = requireNotNull(values[first])
        val rise = requireNotNull(values[indices.last()]).subtract(baseline)
        return indices.subList(1, indices.lastIndex).maxBy { index ->
            requireNotNull(values[index]).subtract(baseline).multiply(span)
                .subtract(rise.multiply(BigDecimal(index - first))).abs()
        }
    }

    private fun x(samples: List<PnlChartSample>, index: Int): BigDecimal {
        if (samples.size <= 1) return middle
        val first = seconds(samples.first().time)
        val range = seconds(samples.last().time).subtract(first)
        return seconds(samples[index].time).subtract(first).divide(range, MathContext.DECIMAL128)
    }

    private fun seconds(time: Instant): BigDecimal = BigDecimal.valueOf(time.epochSecond).add(BigDecimal.valueOf(time.nano.toLong(), 9))
    private fun timeIndices(count: Int): List<Int> = when {
        count <= 4 -> (0 until count).toList()
        else -> listOf(0, count / 3, 2 * (count / 3), count - 1).distinct()
    }
}
