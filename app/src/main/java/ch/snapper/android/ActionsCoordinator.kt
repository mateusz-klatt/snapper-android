package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.aireviews.*
import ch.snapper.android.features.operations.*
import ch.snapper.android.features.positionactions.*
import ch.snapper.android.features.live.LiveRefreshTarget
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** UI-dispatcher bridge. The owner invalidates on backend, identity and permission changes. */
internal class ActionsCoordinator(
    private val scope: CoroutineScope,
    private val session: () -> SessionClient?,
    private val state: () -> AppUiState,
    private val update: ((AppUiState) -> AppUiState) -> Unit,
    private val onAccepted: () -> Unit = {},
) {
    private data class Context(val client: SessionClient, val auth: OrderScope, val primary: String?, val delegate: String?, val epoch: Long)
    private var epoch = 0L
    private val submittedProcesses = mutableSetOf<String>()
    private var processContext: Context? = null
    private var aiContext: Context? = null
    private var positionContext: Context? = null
    private var processes: ProcessesController? = null
    private var reviews: AiReviewsController? = null
    private var positions: PositionActionController? = null
    private var processObserver: Job? = null
    private var aiObserver: Job? = null
    private var positionObserver: Job? = null

    val processActions = ProcessesActions(::loadProcesses, { name, action -> processController()?.actions?.request?.invoke(name, action) },
        { processController()?.actions?.dismiss?.invoke() }, ::confirmProcess)
    val aiActions = AiReviewsActions(::loadAiReviews, { id, decision, reason -> aiController()?.prepare(id, decision, reason) },
        { aiController()?.confirm() }, { aiController()?.dismissConfirmation() }, { id -> aiController()?.retry(id) })
    val positionActions = PositionActionActions({ draft -> positionController()?.actions?.edit?.invoke(draft) },
        { positionController()?.actions?.review?.invoke() }, { positionController()?.actions?.back?.invoke() },
        { positionController()?.actions?.confirm?.invoke() }, { positionController()?.actions?.retry?.invoke() },
        { positionController()?.actions?.dismiss?.invoke() })

    private fun context(): Context? {
        val client = session() ?: return null
        val profile = state().user ?: return null
        val current = client.user.value ?: return null
        if (OrderScope.from(profile) != OrderScope.from(current) || profile.primaryOperatorPublicId != current.primaryOperatorPublicId || profile.delegatePublicId != current.delegatePublicId) return null
        return Context(client, OrderScope.from(profile), profile.primaryOperatorPublicId, profile.delegatePublicId, epoch)
    }

    private fun current(captured: Context): Boolean = context() == captured

    fun invalidate() {
        epoch++
        submittedProcesses.clear()
        processes?.invalidate(); reviews?.invalidate(); positions?.invalidate()
        processObserver?.cancel(); aiObserver?.cancel(); positionObserver?.cancel()
        processes = null; reviews = null; positions = null
        processContext = null; aiContext = null; positionContext = null
        update { it.copy(processes = ProcessesState(), aiReviews = AiReviewsState(), positionActions = PositionActionState()) }
    }

    fun loadProcesses() { processController()?.load() }
    fun loadAiReviews() { aiController()?.refresh() }

    suspend fun refreshLive(target: LiveRefreshTarget) {
        val captured = context() ?: return
        when (target) {
            LiveRefreshTarget.AI_REVIEWS -> aiController()?.takeIf { current(captured) }?.refreshAwaited { current(captured) }
            LiveRefreshTarget.PROCESSES -> processController()?.takeIf { current(captured) }?.refreshAwaited { current(captured) }
            else -> Unit
        }
    }

    private fun confirmProcess() {
        val controller = processController() ?: return
        val name = controller.state.value.confirmation?.target?.name ?: return
        submittedProcesses.add(name)
        controller.actions.confirm()
    }

    private fun processController(): ProcessesController? {
        val captured = context() ?: return null
        val api = captured.client as? ProcessClient ?: return null
        if (captured != processContext) bindProcesses(captured, api)
        return processes
    }

    private fun bindProcesses(captured: Context, api: ProcessClient) {
        submittedProcesses.clear()
        processes?.invalidate(); processObserver?.cancel()
        val controller = ProcessesController(scope, api, state().user ?: return)
        processes = controller; processContext = captured
        processObserver = scope.launch {
            controller.state.collect { value ->
                if (current(captured)) {
                    val accepted = value.operations.any { (name, operation) -> operation.outcome in acceptedProcessOutcomes && submittedProcesses.remove(name) }
                    update { it.copy(processes = value) }
                    if (accepted) onAccepted()
                }
            }
        }
    }

    private fun aiController(): AiReviewsController? {
        val captured = context() ?: return null
        val api = captured.client as? AiReviewsClient ?: return null
        if (captured != aiContext) bindReviews(captured, api)
        return reviews
    }

    private fun bindReviews(captured: Context, api: AiReviewsClient) {
        reviews?.invalidate(); aiObserver?.cancel()
        val controller = AiReviewsController(scope, api, { context()?.takeIf { it == captured }?.let { AiReviewIdentity(it.auth, it.delegate) } }, Clock.systemUTC())
        reviews = controller; aiContext = captured
        aiObserver = scope.launch {
            val notified = mutableSetOf<String>()
            controller.state.collect { value ->
                if (current(captured)) {
                    val accepted = value.results.filter { (id, result) -> result.outcome == AiDecisionOutcome.ACCEPTED && notified.add(id) }.isNotEmpty()
                    update { it.copy(aiReviews = value) }
                    if (accepted) onAccepted()
                }
            }
        }
        controller.activate()
    }

    private fun positionController(): PositionActionController? {
        val captured = context() ?: return null
        val api = captured.client as? PositionActionClient ?: return null
        if (captured != positionContext) bindPositions(captured, api)
        return positions
    }

    private fun bindPositions(captured: Context, api: PositionActionClient) {
        positions?.invalidate(); positionObserver?.cancel()
        val controller = PositionActionController(scope, api, captured.auth)
        positions = controller; positionContext = captured
        positionObserver = scope.launch {
            val notified = mutableSetOf<String>()
            controller.state.collect { value ->
                if (current(captured)) {
                    val accepted = value.accepted?.publicId?.let { notified.add(it) } == true
                    update { it.copy(positionActions = value) }
                    if (accepted) onAccepted()
                }
            }
        }
    }

    fun openPosition(position: PositionDto, kind: PositionActionKind) {
        val captured = context() ?: return
        val wallet = state().snapshot?.wallets?.firstOrNull { it.publicId == captured.auth.activeWalletPublicId } ?: return
        if (position.walletPublicId != wallet.publicId || position.mode != if (wallet.isPaper) "paper" else "live") return
        val target = positionActionTarget(position)
        positionController()?.open(wallet, target, kind)
    }

    companion object {
        private val acceptedProcessOutcomes = setOf(ProcessOutcome.ACCEPTED, ProcessOutcome.INTENT_SAVED, ProcessOutcome.PARTIAL_RESTART)
    }
}

internal fun positionActionTarget(position: PositionDto): PositionActionTarget = PositionActionTarget(
    position.publicId, position.instrumentPublicId, position.instrument, position.exchange,
    position.positionCyclePublicId.orEmpty(), position.quantity, position.averagePrice, position.walletPublicId.orEmpty(),
)
