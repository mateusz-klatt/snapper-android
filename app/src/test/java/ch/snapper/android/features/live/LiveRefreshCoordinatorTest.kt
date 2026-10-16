package ch.snapper.android.features.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRefreshCoordinatorTest {
    @Test fun burstWaitsForTwoHundredMillisecondsOfQuietThenReadsOnce() = runTest {
        var reads = 0
        val coordinator = LiveRefreshCoordinator(backgroundScope, mapOf(LiveRefreshTarget.ORDERS to { reads++ }))
        coordinator.signal(LiveRefreshTarget.ORDERS); runCurrent()
        advanceTimeBy(150); coordinator.signal(LiveRefreshTarget.ORDERS); runCurrent()
        advanceTimeBy(199); runCurrent(); assertEquals(0, reads)
        advanceTimeBy(1); runCurrent(); assertEquals(1, reads)
        coordinator.invalidate()
    }

    @Test fun manySignalsDuringReadRetainExactlyOneFollowUp() = runTest {
        var reads = 0; val complete = CompletableDeferred<Unit>()
        val coordinator = LiveRefreshCoordinator(backgroundScope, mapOf(LiveRefreshTarget.SIGNALS to { reads++; if (reads == 1) complete.await() }))
        coordinator.signal(LiveRefreshTarget.SIGNALS); runCurrent(); advanceTimeBy(200); runCurrent()
        repeat(20) { coordinator.signal(LiveRefreshTarget.SIGNALS) }
        assertEquals(1, reads)
        complete.complete(Unit); runCurrent(); advanceTimeBy(199); runCurrent(); assertEquals(1, reads)
        advanceTimeBy(1); runCurrent(); assertEquals(2, reads)
        advanceTimeBy(1_000); runCurrent(); assertEquals(2, reads)
        coordinator.invalidate()
    }

    @Test fun failedFeedDoesNotCancelAnotherFeedOrAutomaticallyRetry() = runTest {
        var good = 0; var failures = 0
        val coordinator = LiveRefreshCoordinator(backgroundScope, mapOf(
            LiveRefreshTarget.ORDERS to { throw IllegalStateException("fixture") },
            LiveRefreshTarget.SYSTEM to { good++ },
        ), { assertEquals(LiveRefreshTarget.ORDERS, it); failures++ })
        coordinator.signal(LiveRefreshTarget.ORDERS); coordinator.signal(LiveRefreshTarget.SYSTEM)
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(1, good); assertEquals(1, failures)
        advanceTimeBy(1_000); runCurrent(); assertEquals(1, failures)
        coordinator.invalidate()
    }

    @Test fun ignoredCancellationCannotScheduleFollowUpOrReportOldFailure() = runTest {
        var reads = 0; var failures = 0; val complete = CompletableDeferred<Unit>()
        val coordinator = LiveRefreshCoordinator(backgroundScope, mapOf(LiveRefreshTarget.PROCESSES to {
            reads++
            withContext(NonCancellable) { complete.await() }
            throw IllegalStateException("obsolete")
        }), { failures++ })
        coordinator.signal(LiveRefreshTarget.PROCESSES); runCurrent(); advanceTimeBy(200); runCurrent()
        coordinator.signal(LiveRefreshTarget.PROCESSES)
        coordinator.invalidate(); complete.complete(Unit); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, reads); assertEquals(0, failures)
    }

    @Test fun oldEpochCannotTriggerWorkAfterRestartButNewEpochCan() = runTest {
        var reads = 0
        val coordinator = LiveRefreshCoordinator(backgroundScope, mapOf(LiveRefreshTarget.AI_REVIEWS to { reads++ }))
        val old = coordinator.currentEpoch
        coordinator.signal(LiveRefreshTarget.AI_REVIEWS, old); runCurrent()
        val fresh = coordinator.invalidate()
        coordinator.signal(LiveRefreshTarget.AI_REVIEWS, old)
        advanceTimeBy(500); runCurrent(); assertEquals(0, reads)
        coordinator.signal(LiveRefreshTarget.AI_REVIEWS, fresh); runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(1, reads); assertEquals(fresh, coordinator.currentEpoch)
        coordinator.invalidate()
    }
}
