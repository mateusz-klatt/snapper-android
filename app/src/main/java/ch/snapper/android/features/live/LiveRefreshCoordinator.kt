package ch.snapper.android.features.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LiveRefreshTarget { ORDERS, SIGNALS, AI_REVIEWS, PROCESSES, STRATEGIES, SYSTEM }

/** Routes already-authorized invalidations to reads only. Capture the epoch when installing a socket callback. */
class LiveRefreshCoordinator(
    private val scope: CoroutineScope,
    private val reads: Map<LiveRefreshTarget, suspend () -> Unit>,
    private val onFailure: (LiveRefreshTarget) -> Unit = {},
) {
    private class Pending(val epoch: Long) {
        var fetching = false
        var followUp = false
        var job: Job? = null
    }
    private var epoch = 0L
    private val pending = mutableMapOf<LiveRefreshTarget, Pending>()
    val currentEpoch: Long @Synchronized get() = epoch

    @Synchronized fun signal(target: LiveRefreshTarget, expectedEpoch: Long = epoch) {
        if (expectedEpoch != epoch || target !in reads) return
        val previous = pending[target]
        if (previous?.fetching == true) {
            previous.followUp = true
            return
        }
        previous?.job?.cancel()
        schedule(target)
    }

    @Synchronized fun invalidate(): Long {
        epoch++
        val obsolete = pending.values.toList()
        pending.clear()
        obsolete.forEach { it.job?.cancel() }
        return epoch
    }

    private fun schedule(target: LiveRefreshTarget) {
        val request = Pending(epoch)
        pending[target] = request
        request.job = scope.launch(start = CoroutineStart.LAZY) { refresh(target, request) }
        request.job?.start()
    }

    private suspend fun refresh(target: LiveRefreshTarget, request: Pending) {
        var failed = false
        var cancelled = false
        try {
            delay(200)
            if (!begin(target, request)) return
            reads.getValue(target)()
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            finish(target, request, failed, cancelled)
        }
    }

    @Synchronized private fun begin(target: LiveRefreshTarget, request: Pending): Boolean {
        if (!current(target, request)) return false
        request.fetching = true
        return true
    }

    @Synchronized private fun finish(target: LiveRefreshTarget, request: Pending, failed: Boolean, cancelled: Boolean) {
        if (!current(target, request)) return
        pending.remove(target)
        if (failed) runCatching { onFailure(target) }
        if (!cancelled && request.followUp && request.epoch == epoch && target !in pending) schedule(target)
    }

    private fun current(target: LiveRefreshTarget, request: Pending): Boolean =
        request.epoch == epoch && pending[target] === request
}
