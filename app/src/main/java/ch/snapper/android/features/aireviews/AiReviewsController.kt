package ch.snapper.android.features.aireviews

import ch.snapper.android.data.*
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-dispatcher confined; root owns fresh authorization preflight and non-retrying transport. */
class AiReviewsController(
    private val scope: CoroutineScope, private val client: AiReviewsClient,
    private val identity: () -> AiReviewIdentity?, private val clock: Clock,
) {
    private val mutable = MutableStateFlow(AiReviewsState(now = clock.instant()))
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var active = false
    private var ticker: Job? = null
    private var auditJob: Job? = null
    private var pendingJob: Job? = null
    private val intents = mutableMapOf<String, AiDecisionIntent>()
    private val retryable = mutableSetOf<String>()
    private val outcomeRevisions = mutableMapOf<String, Long>()

    fun activate() {
        invalidate()
        active = true
        mutable.value = mutable.value.copy(identity = identity())
        ticker = scope.launch { while (isActive) { delay(1_000); mutable.value = mutable.value.copy(now = clock.instant()) } }
        refresh()
    }

    fun invalidate() {
        epoch++; active = false
        ticker?.cancel(); auditJob?.cancel(); pendingJob?.cancel()
        ticker = null; auditJob = null; pendingJob = null
        intents.clear(); retryable.clear(); outcomeRevisions.clear()
        mutable.value = AiReviewsState(now = clock.instant())
    }

    private fun current(generation: Long, person: AiReviewIdentity): Boolean = active && generation == epoch && identity() == person

    fun refresh() {
        val person = identity() ?: return
        if (!active || person != mutable.value.identity) return
        if (person.canAudit && auditJob?.isActive != true) loadAudit(person, epoch)
        if (person.canPending && pendingJob?.isActive != true) loadPending(person, epoch)
    }

    suspend fun refreshAwaited(stillCurrent: () -> Boolean = { true }) {
        val captured = epoch
        listOfNotNull(auditJob, pendingJob).forEach { it.join() }
        if (captured != epoch || !stillCurrent()) return
        refresh()
        listOfNotNull(auditJob, pendingJob).forEach { it.join() }
    }

    private fun loadAudit(person: AiReviewIdentity, generation: Long) {
        mutable.value = mutable.value.copy(auditLoading = true, auditFailed = false)
        auditJob = scope.launch {
            try {
                val rows = client.auditReviews()
                if (current(generation, person)) mutable.value = mutable.value.copy(audit = rows, auditLoading = false)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (current(generation, person)) mutable.value = mutable.value.copy(auditLoading = false, auditFailed = true)
            }
        }
    }

    private fun loadPending(person: AiReviewIdentity, generation: Long) {
        retryable.clear()
        mutable.value = mutable.value.copy(pendingLoading = true, pendingFailed = false, retryable = emptySet())
        val outcomesAtStart = outcomeRevisions.toMap()
        pendingJob = scope.launch {
            try {
                val rows = client.pendingReviews()
                if (current(generation, person)) acceptPending(rows, outcomesAtStart)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (current(generation, person)) mutable.value = mutable.value.copy(pendingLoading = false, pendingFailed = true)
            }
        }
    }

    private fun acceptPending(rows: List<AiPendingReviewDto>, outcomesAtStart: Map<String, Long>) {
        val freshIds = outcomesAtStart.filter { (id, revision) ->
            revision == outcomeRevisions[id] && id !in mutable.value.inFlight
        }.keys
        freshIds.filter { mutable.value.results[it]?.outcome == AiDecisionOutcome.UNKNOWN }.forEach { retryable += it }
        val freshRows = rows.filter { it.reviewPublicId in freshIds && retryAllowed(it) }
        freshRows.forEach { row -> reconcilePendingOutcome(row.reviewPublicId) }
        mutable.value = mutable.value.copy(
            pending = rows.filter { mutable.value.results[it.reviewPublicId]?.outcome !in terminalOutcomes },
            pendingLoading = false, retryable = retryable.toSet(),
        )
    }

    private fun reconcilePendingOutcome(id: String) {
        when (mutable.value.results[id]?.outcome) {
            AiDecisionOutcome.UNKNOWN -> retryable += id
            AiDecisionOutcome.DENIED, AiDecisionOutcome.REJECTED, AiDecisionOutcome.PREPARATION -> {
                intents.remove(id)
                mutable.value = mutable.value.copy(results = mutable.value.results - id)
            }
            else -> Unit
        }
    }

    private fun retryAllowed(row: AiPendingReviewDto): Boolean =
        row.reviewPublicId !in mutable.value.inFlight &&
            row.status in setOf("pending", "fanout_dispatched") && !aiReviewExpired(row.deadline, clock.instant())

    fun prepare(reviewId: String, decision: String, rationale: String) {
        val row = mutable.value.pending.find { it.reviewPublicId == reviewId } ?: return
        if (identity() != mutable.value.identity || !mutable.value.copy(now = clock.instant()).canDecide(row)) return
        if (decision !in setOf("approve", "reject") || rationale.codePointCount(0, rationale.length) > 4096) return
        val intent = AiDecisionIntent(reviewId, AiDecisionPayload(decision, rationale.trim().ifBlank { null }))
        mutable.value = mutable.value.copy(confirmation = intent)
    }

    fun dismissConfirmation() { mutable.value = mutable.value.copy(confirmation = null) }

    fun confirm() {
        val intent = mutable.value.confirmation ?: return
        val person = identity() ?: return
        if (person != mutable.value.identity || !mutable.value.copy(now = clock.instant()).canConfirm(intent)) {
            mutable.value = mutable.value.copy(confirmation = null)
            return
        }
        dispatch(person, intent)
    }

    /** Only a fresh pending read enables an explicit retry of the exact original decision. */
    fun retry(reviewId: String) {
        if (reviewId !in retryable) return
        val intent = intents[reviewId] ?: return
        val person = identity() ?: return
        if (person != mutable.value.identity || !person.canDecide) return
        mutable.value = mutable.value.copy(confirmation = intent)
    }

    private fun dispatch(person: AiReviewIdentity, intent: AiDecisionIntent) {
        if (intent.reviewId in mutable.value.inFlight || !active) return
        val previous = mutable.value.results[intent.reviewId]
        if (previous != null && intent.reviewId !in retryable) return
        val generation = epoch
        intents[intent.reviewId] = intent
        retryable.remove(intent.reviewId)
        mutable.value = mutable.value.copy(confirmation = null, inFlight = mutable.value.inFlight + intent.reviewId, retryable = retryable.toSet())
        scope.launch {
            val result = executeDecision(person, intent, previous?.outcome == AiDecisionOutcome.UNKNOWN, generation)
            if (current(generation, person)) finish(intent.reviewId, result)
        }
    }

    private suspend fun executeDecision(person: AiReviewIdentity, intent: AiDecisionIntent, retry: Boolean, generation: Long): AiDecisionResult =
        try {
            sendDecision(person, intent, retry)
        } catch (error: CancellationException) {
            if (current(generation, person)) finish(intent.reviewId, AiDecisionResult(AiDecisionOutcome.UNKNOWN))
            throw error
        } catch (_: AiDecisionPreparationException) {
            AiDecisionResult(AiDecisionOutcome.PREPARATION)
        } catch (_: Exception) {
            AiDecisionResult(AiDecisionOutcome.UNKNOWN)
        }

    private suspend fun sendDecision(person: AiReviewIdentity, intent: AiDecisionIntent, retry: Boolean): AiDecisionResult {
        val response = if (retry) client.retryDecision(person.scope, intent.reviewId, intent.payload)
            else client.submitDecision(person.scope, intent.reviewId, intent.payload)
        return AiDecisionResult(aiDecisionOutcome(response, intent.payload), response.message, intent.payload.decision)
    }

    private fun finish(id: String, result: AiDecisionResult) {
        outcomeRevisions[id] = (outcomeRevisions[id] ?: 0L) + 1L
        val effective = if (mutable.value.results[id]?.outcome == AiDecisionOutcome.UNKNOWN && result.outcome !in terminalOutcomes) {
            result.copy(outcome = AiDecisionOutcome.UNKNOWN)
        } else result
        mutable.value = mutable.value.copy(inFlight = mutable.value.inFlight - id,
            results = mutable.value.results + (id to effective),
            pending = if (result.outcome in terminalOutcomes) mutable.value.pending.filterNot { it.reviewPublicId == id } else mutable.value.pending)
        if (result.outcome in terminalOutcomes) refresh()
    }

    companion object {
        private val terminalOutcomes = setOf(AiDecisionOutcome.ACCEPTED, AiDecisionOutcome.PEER_RESOLVED, AiDecisionOutcome.EXPIRED, AiDecisionOutcome.MISSING)
    }
}
