package ch.snapper.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import java.util.Locale
import java.time.ZoneId
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.UiError
import ch.snapper.android.data.*
import ch.snapper.android.features.system.*
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SystemScreenUiTest {
    @get:Rule val compose = createComposeRule()

    private fun populated() = SystemState(
        permissions = SystemFeed.entries.map { it.permission }.toSet(),
        status = SystemFeedState(SystemSystemStatusDataDto("status", SystemProcessStatusDto("trader-ready", error = "Synthetic warning"),
            mapOf("Replay fixture" to SystemProcessStatusDto("completed")), listOf(SystemStrategyStatusPayloadDto("Live fixture", "watching")))),
        health = SystemFeedState(SystemHealthCheckDataDto("health", "degraded", "fixture-version", SystemConnectionStatsDto(11, 12, 13, 14, 15),
            SystemHealthTopicsDto(16), SystemGapDetectionStatsDto(SystemGapStatsDto(21, 22, 23, 24, 25), mapOf("fixture" to SystemGapStatsDto())))),
        strategies = SystemFeedState(listOf(
            SystemStrategyProcessDto("s1", "Strategy A", true, true, "paper", "ExampleClass", "owner", "Fixture coordinator", true),
            SystemStrategyProcessDto("s2", "Strategy B", false, false, "live"),
        )),
        backtests = SystemFeedState(listOf(SystemBacktestRunDataDto(publicId = "backtest-fixture", walletPublicId = "wallet", strategyName = "Backtest fixture",
            instrumentPublicId = "instrument-fallback", exchange = "source-venue", timeframe = "1h", startDate = "2026-10-01", endDate = "2026-10-02",
            initialCash = BigDecimal("123.123456789"), status = "failed", targetExecutionExchange = "target-venue", error = "Fixture execution error"))),
        users = SystemFeedState(listOf(
            SystemUserProfileDto("u1", "Fixture user", "fixture@example.invalid", "viewer", true, "2026-10-01"),
            SystemUserProfileDto("u2", "Unknown active user", role = "viewer", createdAt = "2026-10-02"),
            SystemUserProfileDto("u3", "Inactive user", role = "viewer", isActive = false, createdAt = "2026-10-03"),
        )),
        delegates = SystemFeedState(listOf(
            SystemDelegateReadDto("d1", "fixture-agent", "Fixture delegate", "u1", "2026-10-01", true, SystemDelegateCapsBodyDto(maxOpenOrders = 7, maxDailyNotionalUsd = BigDecimal("987.123456789"))),
            SystemDelegateReadDto("d2", "revoked-agent", "Revoked fixture", "u1", "2026-10-02", false, SystemDelegateCapsBodyDto()),
        )),
    )

    @Test fun populatedSixFeedsRenderTypedValuesAndNullableFallbacks() {
        compose.setContent { LazyColumn(Modifier.testTag("system-list")) { systemScreen(populated(), SystemActions {}) } }
        compose.onNodeWithText("trader-ready").assertExists()
        compose.onNodeWithText("Synthetic warning").assertExists()
        compose.onNodeWithText("watching").assertExists()
        compose.onNodeWithTag("system-list").performScrollToIndex(1)
        compose.onNodeWithText("fixture-version").assertExists()
        compose.onNodeWithText("21").assertExists()
        compose.onNodeWithText("25").assertExists()
        compose.onNodeWithTag("system-list").performScrollToIndex(2)
        compose.onNodeWithText("Strategy A").assertExists()
        compose.onNodeWithText("Strategy B").assertExists()
        compose.onNodeWithText("Fixture coordinator").assertExists()
        compose.onNodeWithTag("system-list").performScrollToIndex(3)
        compose.onNodeWithText("instrument-fallback").assertExists()
        compose.onNodeWithText("source-venue").assertExists()
        compose.onNodeWithText("target-venue").assertExists()
        compose.onNodeWithText(BacktestPresentation.initialCash(BigDecimal("123.123456789"), Locale.ROOT)).assertExists()
        compose.onNodeWithText("Fixture execution error").assertExists()
        compose.onNodeWithTag("system-list").performScrollToIndex(4)
        compose.onNodeWithText("fixture@example.invalid").assertExists()
        compose.onNodeWithText("Unknown active user").assertExists()
        compose.onNodeWithTag("system-list").performScrollToIndex(5)
        compose.onNodeWithText("987.123456789").assertExists()
        compose.onNodeWithText("Revoked fixture").assertExists()
    }

    @Test fun backtestCountryFormattingKeepsUtcDatesAndUsdWhileStatusUsesInterfaceLanguage() {
        val locale = mutableStateOf(Locale.US)
        val zone = mutableStateOf(ZoneId.of("Pacific/Kiritimati"))
        val row = populated().backtests.value!!.single().copy(
            status = "COMPLETED", startDate = "2026-10-02T00:30:00+02:00", endDate = "2026-10-02T23:30:00-02:00",
            initialCash = BigDecimal("1234.125"),
        )
        val state = mutableStateOf(SystemState(permissions = setOf("read:backtests"), backtests = SystemFeedState(listOf(row))))
        compose.setContent {
            CompositionLocalProvider(LocalNumberLocale provides locale.value, LocalDisplayTimeZone provides zone.value) {
                LazyColumn { systemScreen(state.value, SystemActions {}) }
            }
        }
        compose.onNodeWithText("Completed").assertExists()
        compose.onNodeWithText("Oct 1, 2026 – Oct 3, 2026").assertExists()
        compose.onNodeWithText("$1,234.12").assertExists()
        compose.runOnIdle { locale.value = Locale.GERMANY; zone.value = ZoneId.of("America/Los_Angeles") }
        compose.onNodeWithText("Completed").assertExists()
        compose.onNodeWithText("01.10.2026 – 03.10.2026").assertExists()
        compose.onNodeWithText(BacktestPresentation.initialCash(row.initialCash, Locale.GERMANY)).assertExists()
        compose.runOnIdle { state.value = state.value.copy(backtests = SystemFeedState(listOf(row.copy(status = "future-status", startDate = "invalid", endDate = "unknown")))) }
        compose.onNodeWithText("Unknown").assertExists()
        compose.onNodeWithText("invalid – unknown").assertExists()
    }

    @Test fun revokedDirectoryAndDelegatePermissionsRemovePreviouslyRenderedRows() {
        val state = mutableStateOf(populated().copy(permissions = setOf("manage:users", "read:ai_integration")))
        compose.setContent { LazyColumn(Modifier.testTag("system-list")) { systemScreen(state.value, SystemActions {}) } }
        compose.onNodeWithText("Fixture user").assertExists()
        compose.onNodeWithTag("system-list").performScrollToIndex(1)
        compose.onNodeWithText("Fixture delegate").assertExists()
        compose.runOnIdle { state.value = state.value.copy(permissions = emptySet()) }
        compose.onNodeWithTag("system.users").assertDoesNotExist()
        compose.onNodeWithTag("system.delegates").assertDoesNotExist()
        compose.onNodeWithText("Fixture user").assertDoesNotExist()
    }

    @Test fun failedFeedRetriesIndependentlyWhileOtherFeedRetainsRows() {
        val requests = mutableListOf<SystemFeed>()
        val state = populated().copy(permissions = setOf("read:strategies", "read:backtests"), strategies = SystemFeedState(error = UiError.NETWORK))
        compose.setContent { LazyColumn(Modifier.testTag("system-list")) { systemScreen(state, SystemActions { requests.add(it) }) } }
        compose.onNodeWithTag("error.action").performClick()
        compose.runOnIdle { assertEquals(listOf(SystemFeed.STRATEGIES), requests) }
        compose.onNodeWithTag("system-list").performScrollToIndex(1)
        compose.onNodeWithText("Backtest fixture").assertExists()
        compose.onNodeWithText(BacktestPresentation.initialCash(BigDecimal("123.123456789"), Locale.ROOT)).assertExists()
    }

    @Test fun missingHealthCountersAndEmptyFeedRemainVisibleDuringIndependentLoad() {
        val state = populated().copy(permissions = setOf("read:system_status", "read:strategies"),
            health = SystemFeedState(SystemHealthCheckDataDto("h", "ok", "empty-counters", SystemConnectionStatsDto(), SystemHealthTopicsDto(0), SystemGapDetectionStatsDto(SystemGapStatsDto()))),
            strategies = SystemFeedState(emptyList(), isLoading = true))
        compose.setContent { LazyColumn(Modifier.testTag("system-list")) { systemScreen(state, SystemActions {}) } }
        compose.onNodeWithTag("system-list").performScrollToIndex(1)
        compose.onNodeWithText("empty-counters").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(10)
        compose.onNodeWithTag("system-list").performScrollToIndex(2)
        compose.onNode(hasText("Refresh") and hasAnyAncestor(hasTestTag("system.strategies"))).assertIsNotEnabled()
    }
}
