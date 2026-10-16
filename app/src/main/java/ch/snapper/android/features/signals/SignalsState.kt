package ch.snapper.android.features.signals

import ch.snapper.android.data.SignalDto
import java.math.BigDecimal
import java.math.MathContext

/** A new value on every controller reset prevents exporting a previous account's selection. */
data class SignalsState(
    val scopeKey: String = "",
    val allowed: Boolean = false,
    val walletPublicId: String? = null,
    val rows: List<SignalDto> = emptyList(),
    val selectedStrategy: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val scopedRows: List<SignalDto> get() = rows.filter { walletPublicId == null || it.walletPublicId == null || it.walletPublicId == walletPublicId }
    val strategies: List<String> get() = scopedRows.mapNotNull { it.strategyName?.takeIf(String::isNotBlank) }.distinct().sorted()
    val visibleRows: List<SignalDto> get() = if (!allowed) emptyList() else scopedRows.filter { selectedStrategy == null || it.strategyName == selectedStrategy }
    val summary: SignalSummary get() = SignalSummary.from(visibleRows)
}

data class SignalSummary(val total: Int, val buy: Int, val sell: Int, val averageStrength: BigDecimal) {
    companion object {
        fun from(rows: List<SignalDto>) = SignalSummary(rows.size, rows.count { it.side.equals("buy", true) }, rows.count { it.side.equals("sell", true) },
            if (rows.isEmpty()) BigDecimal.ZERO else rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.strength }.divide(rows.size.toBigDecimal(), MathContext.DECIMAL128))
    }
}

enum class SignalStrengthTier { STRONG, MEDIUM, WEAK, VERY_WEAK }
fun signalStrengthTier(value: BigDecimal): SignalStrengthTier = when {
    value >= BigDecimal("0.8") -> SignalStrengthTier.STRONG
    value >= BigDecimal("0.6") -> SignalStrengthTier.MEDIUM
    value >= BigDecimal("0.4") -> SignalStrengthTier.WEAK
    else -> SignalStrengthTier.VERY_WEAK
}

data class SignalsActions(val refresh: () -> Unit, val selectStrategy: (String?) -> Unit)
