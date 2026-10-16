package ch.snapper.android.features.pnl

import ch.snapper.android.data.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PnlControllerTest {
    private val anchor = Instant.parse("2026-10-16T00:00:00Z")
    private val wallet = WalletDto("wallet", "Paper", isPaper = true)
    private class Client : PnlClient {
        val queries = mutableListOf<PnlSeriesQuery>()
        var gate: CompletableDeferred<Unit>? = null
        var wrongWallet = false
        var ignoreCancellation = false
        override suspend fun pnlSeries(query: PnlSeriesQuery): PnlSeriesDto {
            queries.add(query)
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            return PnlSeriesDto(if (wrongWallet) "foreign" else query.walletPublicId, query.mode, query.granularity, query.valuationCcy,
                query.from.toString(), query.to.toString(), query.to.toString(), "mark-source", emptyList(), "v1",
                PnlEquityCoverageDto(false, null, null, 0, null, null, null, null, null, null, 0, null),
                PnlExecutionHistoryDto("as_recorded", emptyList()), emptyList())
        }
    }

    @Test fun noWalletAndMissingPermissionNeverFetch() = runTest {
        val client = Client()
        val controller = PnlController(this, client) { anchor }
        controller.load()
        controller.reset(wallet, false)
        controller.load()
        runCurrent()
        assertTrue(client.queries.isEmpty())
    }

    @Test fun windowsCoarsenAndModeComesFromWallet() = runTest {
        val client = Client()
        val controller = PnlController(this, client) { anchor }
        controller.reset(wallet, true)
        controller.actions.window(PnlWindow.QUARTER)
        runCurrent()
        val query = client.queries.single()
        assertEquals("paper", query.mode)
        assertEquals("1h", query.granularity)
        assertEquals(anchor.minusSeconds(90L * 86400), query.from)
        assertEquals(anchor, query.to)
        controller.actions.granularity("1m")
        runCurrent()
        assertEquals(2, client.queries.size)
        assertEquals("1m", client.queries.last().granularity)
        controller.actions.currency("PLN")
        runCurrent()
        assertEquals("PLN", client.queries.last().valuationCcy)
    }

    @Test fun resetRejectsLateResponseFromPreviousWallet() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { this.gate = gate }
        val controller = PnlController(this, client) { anchor }
        controller.reset(wallet, true)
        controller.load()
        runCurrent()
        controller.reset(wallet.copy(publicId = "new", isPaper = false), true)
        gate.complete(Unit)
        runCurrent()
        assertNull(controller.state.value.data)
        assertFalse(controller.state.value.loading)
    }

    @Test fun wrongEchoedScopeIsNeverDisplayed() = runTest {
        val client = Client().apply { wrongWallet = true }
        val controller = PnlController(this, client) { anchor }
        controller.reset(wallet, true)
        controller.load()
        runCurrent()
        assertNull(controller.state.value.data)
        assertTrue(controller.state.value.failed)
    }

    @Test fun nanosecondClockProducesMicrosecondRoundTrip() = runTest {
        val client = Client()
        val controller = PnlController(this, client) { Instant.parse("2026-10-16T00:00:00.123456789Z") }
        controller.reset(wallet, true)
        controller.load()
        runCurrent()
        val query = client.queries.single()
        assertEquals(Instant.parse("2026-10-16T00:00:00.123456Z"), query.to)
        assertEquals(123456000, query.from.nano)
        val result = requireNotNull(controller.state.value.data)
        assertTrue(PnlController.matches(query, result.copy(toTime = "2026-10-16T02:00:00.123456+02:00")))
    }

    @Test fun newerQueryCommitsWhileCancelledIgnoringOldQueryCannotOverwriteIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { this.gate = gate; ignoreCancellation = true }
        val controller = PnlController(this, client) { anchor }
        controller.reset(wallet, true)
        controller.load()
        runCurrent()
        client.gate = null
        controller.actions.currency("EUR")
        runCurrent()
        assertEquals("EUR", controller.state.value.data?.valuationCcy)
        assertFalse(controller.state.value.loading)
        gate.complete(Unit)
        runCurrent()
        assertEquals("EUR", controller.state.value.data?.valuationCcy)
        assertFalse(controller.state.value.loading)
    }

    @Test fun rapidChangesCancelQueuedQueriesAndLatestRequestCompletes() = runTest {
        val client = Client()
        val controller = PnlController(this, client) { anchor }
        controller.reset(wallet, true)
        controller.load()
        controller.actions.currency("PLN")
        controller.actions.currency("EUR")
        runCurrent()
        assertEquals(1, client.queries.size)
        assertEquals("EUR", client.queries.single().valuationCcy)
        assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.failed)
    }

    @Test fun weekAutoCoarsensButExplicitMinuteSelectionIsAccepted() = runTest {
        val client = Client()
        val controller = PnlController(this, client) { anchor }
        controller.reset(wallet, true)
        controller.actions.window(PnlWindow.WEEK)
        runCurrent()
        assertEquals("5m", client.queries.last().granularity)
        controller.actions.granularity("1m")
        runCurrent()
        assertEquals("1m", client.queries.last().granularity)
        assertFalse(controller.state.value.failed)
    }

}
