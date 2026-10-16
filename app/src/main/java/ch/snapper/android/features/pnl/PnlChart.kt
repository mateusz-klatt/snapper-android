package ch.snapper.android.features.pnl

import java.math.BigDecimal
import java.math.MathContext

/** Nulls split paths; normalization remains decimal until the drawing boundary. */
fun pnlSegments(values: List<BigDecimal?>): List<List<Pair<Float, Float>>> {
    val known = values.filterNotNull()
    if (known.isEmpty()) return emptyList()
    val minimum = known.minOrNull() ?: return emptyList()
    val maximum = known.maxOrNull() ?: return emptyList()
    val range = maximum.subtract(minimum)
    val segments = mutableListOf<List<Pair<Float, Float>>>()
    var current = mutableListOf<Pair<Float, Float>>()
    values.forEachIndexed { index, value ->
        if (value == null) {
            if (current.isNotEmpty()) segments.add(current)
            current = mutableListOf()
        } else {
            val height = if (range.signum() == 0) 0.5f else value.subtract(minimum).divide(range, MathContext.DECIMAL128).toFloat()
            current.add(index.toFloat() / (values.size - 1).coerceAtLeast(1) to height)
        }
    }
    if (current.isNotEmpty()) segments.add(current)
    return segments
}
