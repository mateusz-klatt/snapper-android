package ch.snapper.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.data.CandleDto
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class CandleTimestampAxisTest {
    @get:Rule val compose = createComposeRule()

    @Test fun daylightSavingOverlapRetainsDistinctOffsetsAndDeviceZoneChangesReformatAxes() {
        val zone = mutableStateOf(ZoneId.of("Europe/Budapest"))
        val candles = listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z").map {
            CandleDto("BTC", "venue", "1h", it, BigDecimal.TEN, BigDecimal("12"),
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE)
        }
        compose.setContent {
            CompositionLocalProvider(LocalDisplayTimeZone provides zone.value, LocalNumberLocale provides Locale.US) {
                MaterialTheme { CandleChart(candles) }
            }
        }
        compose.onNodeWithTag("market.axis.start").assertTextContains("UTC+02:00", substring = true)
        compose.onNodeWithTag("market.axis.end").assertTextContains("UTC+01:00", substring = true)
        candles.forEach { compose.onNodeWithText(it.openAt).assertDoesNotExist() }
        compose.runOnIdle { zone.value = ZoneId.of("UTC") }
        compose.onNodeWithTag("market.axis.start").assertTextContains("UTC", substring = true)
            .assert(!hasText("UTC+02:00", substring = true))
        compose.onNodeWithTag("market.axis.end").assertTextContains("UTC", substring = true)
            .assert(!hasText("UTC+01:00", substring = true))
    }
}
