package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.accounts.*
import java.time.*
import java.math.BigDecimal
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class VenueAccountsTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private fun row() = VenueAccountDto("id", "wallet", "kraken", "live", "observed", "observed", true,
        "observed", "observed", "native_only", balanceObservedAt = now.minusSeconds(10).toString(),
        positionObservedAt = now.minusSeconds(10).toString(), authoritativeUntil = now.plusSeconds(60).toString(),
        reconciliation = VenueReconciliationDto("unknown", false))

    @Test fun pollingDeadlineAndObservationBoundariesFailClosed() {
        assertTrue(AccountTruth.derive(row(), now, now.minusSeconds(15)).authoritative)
        assertTrue(AccountTruth.derive(row(), now, now.minusMillis(15_001)).pollingStalled)
        assertTrue(AccountTruth.derive(row().copy(authoritativeUntil = now.toString()), now, now).authorityExpired)
        assertTrue(AccountTruth.derive(row().copy(balanceObservedAt = now.minusSeconds(900).toString()), now, now).authorityExpired)
        assertTrue(AccountTruth.derive(row().copy(balanceObservedAt = now.minusSeconds(899).toString()), now, now).authoritative)
    }

    @Test fun malformedMissingAndFutureMetadataNeverGainsAuthority() {
        listOf(null, "bad", now.plusSeconds(1).toString()).forEach { observed ->
            assertFalse(AccountTruth.derive(row().copy(balanceObservedAt = observed), now, now).authoritative)
            assertFalse(AccountTruth.derive(row().copy(positionObservedAt = observed), now, now).authoritative)
        }
        listOf(null, "bad").forEach { deadline -> assertFalse(AccountTruth.derive(row().copy(authoritativeUntil = deadline), now, now).authoritative) }
        listOf(null, now.plusSeconds(1), Instant.EPOCH).forEach { fetched -> assertFalse(AccountTruth.derive(row(), now, fetched).authoritative) }
        assertFalse(AccountTruth.derive(row().copy(isAuthoritative = false), now, now).authoritative)
    }

    @Test fun paperAndIndependentUnsupportedPositionsStayTruthful() {
        assertEquals("simulated", AccountTruth.derive(row().copy(mode = "paper", effectiveStatus = "simulated"), now, now).status)
        assertFalse(AccountTruth.derive(row().copy(mode = "paper"), now, now).authoritative)
        assertTrue(AccountTruth.derive(row().copy(positionStatus = "unsupported", positionObservedAt = null), now, now).authoritative)
        assertEquals("corrupt", AccountTruth.derive(row().copy(effectiveStatus = "corrupt"), now, now).status)
    }

    @Test fun exactDecimalWinsWithoutRoundedFallbackForCorruption() {
        assertEquals("123.456789012345678901", venueDecimal("123.456789012345678901", BigDecimal("123.45")))
        assertEquals("—", venueDecimal("bad", BigDecimal.ONE))
        assertEquals("—", venueDecimal(null, null))
        assertEquals("0", venueDecimal(null, BigDecimal.ZERO))
    }

    @Test fun pollingRunsOnlyForVisibleForegroundAuthorizedScreen() = runTest {
        var calls = 0
        val client = object : VenueAccountsClient { override suspend fun venueAccounts(walletPublicId: String?): List<VenueAccountDto> { calls++; return listOf(row()) } }
        val controller = VenueAccountsController(backgroundScope, client, Clock.fixed(now, ZoneOffset.UTC))
        controller.activate("wallet", emptySet(), true, true)
        runCurrent(); assertEquals(0, calls)
        controller.activate("wallet", setOf("read:account_state"), false, true)
        runCurrent(); assertEquals(0, calls)
        controller.activate("wallet", setOf("read:account_state"), true, false)
        runCurrent(); assertEquals(0, calls)
        controller.activate("wallet", setOf("read:account_state"), true, true)
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(5_000); runCurrent(); assertEquals(2, calls)
        controller.invalidate()
        advanceTimeBy(10_000); runCurrent(); assertEquals(2, calls)
        assertTrue(controller.state.value.accounts.isEmpty())
    }

    @Test fun cancelledTransportCannotRestoreOldWalletOrLogoutData() = runTest {
        val pending = mutableListOf<Continuation<List<VenueAccountDto>>>()
        val client = object : VenueAccountsClient { override suspend fun venueAccounts(walletPublicId: String?) = suspendCoroutine<List<VenueAccountDto>> { pending += it } }
        val controller = VenueAccountsController(backgroundScope, client, Clock.fixed(now, ZoneOffset.UTC))
        controller.activate("wallet", setOf("read:account_state"), true, true)
        runCurrent()
        controller.activate("second", setOf("read:account_state"), true, true)
        runCurrent()
        pending[0].resume(listOf(row()))
        runCurrent(); assertTrue(controller.state.value.accounts.isEmpty())
        controller.invalidate()
        pending[1].resume(listOf(row().copy(walletPublicId = "second")))
        runCurrent(); assertTrue(controller.state.value.accounts.isEmpty())
    }
    @Test fun failedRefreshRetainsRowsWhileIndependentClockDemotesAuthority() = runTest {
        var currentTime = now
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = currentTime
        }
        var fail = false
        val client = object : VenueAccountsClient {
            override suspend fun venueAccounts(walletPublicId: String?): List<VenueAccountDto> {
                if (fail) throw java.io.IOException("offline")
                return listOf(row())
            }
        }
        val controller = VenueAccountsController(backgroundScope, client, clock)
        controller.activate("wallet", setOf("read:account_state"), true, true)
        runCurrent()
        assertTrue(controller.state.value.truth(row()).authoritative)
        fail = true
        currentTime = now.plusSeconds(16)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(controller.state.value.failed)
        assertEquals(listOf(row()), controller.state.value.accounts)
        assertEquals(now, controller.state.value.lastSuccessfulFetch)
        assertTrue(controller.state.value.truth(row()).pollingStalled)
        controller.invalidate()
    }

    @Test fun backendFixturePreservesNullableBalancesAndDecimalProvenance() {
        val payload = """{"public_id":"a","wallet_public_id":"w","exchange":"kraken","mode":"live",
            "sync_status":"observed","effective_status":"stale","is_authoritative":false,
            "balance_status":"observed","position_status":"unsupported","valuation_status":"native_only",
            "balances":[{"currency":"BTC","total":0.1234567890123456789,"free":null,"used":null,
            "total_decimal":"0.123456789012345678901","numeric_provenance":"venue_decimal"}],
            "open_positions":null,"reconciliation":{"effective_status":"incomplete","is_authoritative":false}}"""
        val decoded = Json.decodeFromString<VenueAccountDto>(payload)
        val balance = requireNotNull(decoded.balances).single()
        assertEquals(BigDecimal("0.1234567890123456789"), balance.total)
        assertEquals("0.123456789012345678901", venueDecimal(balance.totalDecimal, balance.total))
        assertNull(balance.free)
        assertNull(decoded.openPositions)
        assertEquals("venue_decimal", balance.numericProvenance)
    }

    @Test fun paperPreservesBackendFailureStatusesAndRejectsObservedAuthority() {
        listOf("corrupt", "error", "clock_error", "stale", "unsupported", "not_applicable", "unknown").forEach { status ->
            val account = row().copy(mode = "paper", effectiveStatus = status)
            val truth = AccountTruth.derive(account, now, now)
            assertEquals(status, truth.status)
            assertFalse(truth.authoritative)
        }
        listOf(null, "bad", now.plusSeconds(1).toString()).forEach { timestamp ->
            val invalidPaper = row().copy(mode = "paper", balanceObservedAt = timestamp)
            assertEquals("stale", AccountTruth.derive(invalidPaper, now, now).status)
            assertFalse(AccountTruth.derive(invalidPaper, now, now).authoritative)
        }
        assertEquals("stale", AccountTruth.derive(row().copy(mode = "paper"), now, now).status)
    }

}
