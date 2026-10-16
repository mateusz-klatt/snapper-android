package ch.snapper.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.R
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.data.FinancialColorConvention
import ch.snapper.android.data.PnlTimelinePointDto
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PnlLineChartTest {
    @get:Rule val compose = createComposeRule()
    private fun points(): List<PnlTimelinePointDto> = Json.decodeFromString("""[
      {"point_time":"2026-10-16T10:00:00Z","net_pnl":1.25,"realized_pnl":100,"unrealized_pnl":-2,"fee_pnl":0,"accrual_pnl":0,"equity":null,"cash":null,"position_value":null,"drawdown":null,"valuation_status":"complete","incompleteness_reasons":[],"per_instrument":[],"attribution":[]},
      {"point_time":"2026-10-16T11:00:00Z","net_pnl":2.75,"realized_pnl":200,"unrealized_pnl":null,"fee_pnl":0,"accrual_pnl":0,"equity":null,"cash":null,"position_value":null,"drawdown":null,"valuation_status":"partial","incompleteness_reasons":[],"per_instrument":[],"attribution":[]}
    ]""")

    @Test fun oneSharedPlotHasThreeExactLocalizedLegendValuesCurrencyAndWithholding() {
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalNumberLocale provides Locale.GERMANY) {
                PnlLineChart(points(), "EUR", zone = ZoneOffset.UTC)
            } }
        }
        compose.onAllNodesWithTag("pnl.chart.canvas").assertCountEquals(1)
        compose.onNodeWithTag("pnl.legend.NET").assertTextContains("2,75", substring = true)
        compose.onNodeWithTag("pnl.legend.REALIZED").assertTextContains("200", substring = true)
        compose.onNodeWithTag("pnl.legend.UNREALIZED").assertTextContains(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.positions_timeline_attribution_withheld), substring = true)
        compose.onNodeWithTag("pnl.chart.currency").assertTextContains("EUR", substring = true)
        compose.onNodeWithTag("pnl.chart.canvas").assertContentDescriptionContains("2026-10-16T10:00:00Z", substring = true)
        compose.onNodeWithTag("pnl.chart.canvas").assertContentDescriptionContains("2,75", substring = true)
    }

    @Test fun seriesColorsAndGeometryDoNotFlipWithFinancialSignConvention() {
        val convention = mutableStateOf(FinancialColorConvention.RISING_GREEN)
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalFinancialColorConvention provides convention.value) {
                PnlLineChart(points(), "USD", zone = ZoneOffset.UTC)
            } }
        }
        val before = compose.onNodeWithTag("pnl.chart.canvas").captureToImage().toPixelMap()
        compose.runOnIdle { convention.value = FinancialColorConvention.RISING_RED }
        val after = compose.onNodeWithTag("pnl.chart.canvas").captureToImage().toPixelMap()
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        for (x in 0 until before.width step 3) {
            for (y in 0 until before.height step 3) assertEquals(before[x, y], after[x, y])
        }
    }
}
