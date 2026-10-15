package ch.snapper.android.data

import java.math.BigDecimal

/** Decimal-only bounds keep command values inside the backend's finite numeric range. */
object OrderNumbers {
    private val minimum = BigDecimal("4.9406564584124654E-324")
    private val maximum = BigDecimal("1.7976931348623157E308")

    fun isSupported(value: BigDecimal): Boolean =
        value.signum() > 0 && value.precision() <= 100 && value.scale() in -1000..1000 &&
            value >= minimum && value <= maximum
}
