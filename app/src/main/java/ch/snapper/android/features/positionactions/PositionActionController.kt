package ch.snapper.android.features.positionactions

import ch.snapper.android.data.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PositionActionIssue { INVALID, PREPARATION, REJECTED, UNKNOWN }
data class PositionActionReview(val scope: OrderScope, val wallet: WalletDto, val intent: PositionActionIntent)
data class PositionActionState(
    val target: PositionActionTarget? = null,
    val wallet: WalletDto? = null,
    val draft: PositionActionDraft? = null,
    val review: PositionActionReview? = null,
    val submitting: Boolean = false,
    val dismissed: Boolean = false,
    val issue: PositionActionIssue? = null,
    val accepted: ExecutionPlanDto? = null,
)
data class PositionActionActions(
    val edit: (PositionActionDraft) -> Unit, val review: () -> Unit, val back: () -> Unit,
    val confirm: () -> Unit, val retry: () -> Unit, val dismiss: () -> Unit,
)

/** Main-dispatcher owner; reset whenever backend, account, wallet or permissions change. */
class PositionActionController(private val coroutineScope: CoroutineScope, private val client: PositionActionClient, initialScope: OrderScope,
    private val newKey: () -> String = { UUID.randomUUID().toString() }) {
    private var scope = initialScope
    private var epoch = 0L
    private val mutable = MutableStateFlow(PositionActionState())
    val state = mutable.asStateFlow()
    val actions = PositionActionActions(::edit, ::review, ::back, { submit(false) }, { submit(true) }, ::dismiss)

    fun reset(value: OrderScope) {
        epoch++
        scope = value
        mutable.value = PositionActionState()
    }

    fun invalidate() {
        epoch++
        mutable.value = PositionActionState()
        scope = scope.copy(effectivePermissions = emptySet())
    }

    fun open(wallet: WalletDto, target: PositionActionTarget, kind: PositionActionKind) {
        if (state.value.submitting) return
        if (state.value.issue == PositionActionIssue.UNKNOWN) {
            mutable.value = state.value.copy(dismissed = false)
            return
        }
        if (!PositionActionForm.eligible(scope, wallet, target, kind)) return
        mutable.value = PositionActionState(target = target, wallet = wallet, draft = PositionActionDraft(kind))
    }

    private fun edit(draft: PositionActionDraft) {
        val current = state.value
        if (current.submitting || current.issue == PositionActionIssue.UNKNOWN || current.review != null || current.draft?.kind != draft.kind) return
        mutable.value = current.copy(draft = draft, issue = null)
    }

    private fun review() {
        val current = state.value
        if (current.submitting || current.issue == PositionActionIssue.UNKNOWN || current.review != null) return
        val draft = current.draft ?: return
        val wallet = current.wallet ?: return
        val target = current.target ?: return
        val intent = PositionActionForm.build(scope, wallet, target, draft, newKey())
        mutable.value = if (intent == null) current.copy(issue = PositionActionIssue.INVALID)
        else current.copy(review = PositionActionReview(scope, wallet, intent), issue = null)
    }

    private fun back() {
        if (!state.value.submitting && state.value.issue != PositionActionIssue.UNKNOWN) mutable.value = state.value.copy(review = null, issue = null)
    }

    private fun dismiss() {
        if (state.value.submitting) return
        mutable.value = if (state.value.issue == PositionActionIssue.UNKNOWN) state.value.copy(dismissed = true) else PositionActionState()
    }

    private fun submit(retry: Boolean) {
        val current = state.value
        val review = current.review ?: return
        if (current.submitting || review.scope != scope || (current.issue == PositionActionIssue.UNKNOWN && !retry)) return
        val request = epoch
        val wasUnknown = current.issue == PositionActionIssue.UNKNOWN
        mutable.value = current.copy(submitting = true, dismissed = false)
        coroutineScope.launch { dispatch(review, request, wasUnknown) }
    }

    private suspend fun dispatch(review: PositionActionReview, request: Long, wasUnknown: Boolean) {
        try {
            val result = client.submit(review.scope, review.intent)
            if (epoch == request) mutable.value = PositionActionState(accepted = result)
        } catch (error: CancellationException) {
            if (epoch == request) mutable.value = mutable.value.copy(submitting = false, issue = PositionActionIssue.UNKNOWN)
            throw error
        } catch (error: Exception) {
            val issue = when {
                wasUnknown -> PositionActionIssue.UNKNOWN
                error is PositionPreparationException -> PositionActionIssue.PREPARATION
                error is PositionRejectedException -> PositionActionIssue.REJECTED
                else -> PositionActionIssue.UNKNOWN
            }
            if (epoch == request) mutable.value = mutable.value.copy(submitting = false, issue = issue)
        }
    }
}
