package ch.snapper.android.ui

import ch.snapper.android.data.*
import java.math.BigDecimal

internal fun pnlScreenFixture(): PnlSeriesDto {
    val exact = BigDecimal("123.123456789123456789")
    val first = PnlTimelinePointDto("2026-10-16T00:00:00Z", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.TEN, BigDecimal("11"), BigDecimal("100"), BigDecimal("70"), BigDecimal("30"), null,
        "complete", emptyList(), emptyList(), emptyList())
    val gap = first.copy(pointTime = "2026-10-16T00:01:00Z", netPnl = null, unrealizedPnl = null,
        equity = null, cash = null, positionValue = null, drawdown = null, valuationStatus = "incomplete",
        incompletenessReasons = listOf(PnlIncompletenessReasonDto("mark_unavailable", "mark_incomplete", "instrument", "instrument-gap")))
    val latest = first.copy(pointTime = "2026-10-16T00:02:00Z", netPnl = exact,
        perInstrument = listOf(PnlInstrumentContributionDto("instrument-latest", "BTC-USD", "venue", exact, BigDecimal("-0.25"), null, BigDecimal("4.5"))),
        attribution = listOf(PnlAttributionContributionDto("manual", null, BigDecimal("8.75"), null, BigDecimal.ZERO, BigDecimal.ONE),
            PnlAttributionContributionDto("plan", "demo-strategy", null, BigDecimal.ZERO, BigDecimal.ZERO, null)))
    return PnlSeriesDto("w", "paper", "1m", "EUR", "2026-10-15T00:00:00Z", "2026-10-16T00:02:00Z", "2026-10-16T00:03:00Z",
        "proven-mark-source", listOf(PnlFxRateSourceDto("USD", "EUR", "EUR", "USD", "fx-venue")), "calc-test-v1",
        PnlEquityCoverageDto(true, "spot_only", false, 2, first.pointTime, latest.pointTime, "sample-test-v1", "EUR", "USD", "EUR/USD@fx-venue", 1, "currency_basis_unsupported"),
        PnlExecutionHistoryDto("operator_corrected", listOf(PnlExecutionCorrectionDto("correction-test-id", "execution-test-id", "venue", 12, "unwitnessed_phantom", "2026-10-16T00:02:30Z"))),
        listOf(first, gap, latest))
}
