package ch.snapper.android.features.desks

import ch.snapper.android.data.DeskClient
import ch.snapper.android.data.DeskPreparationException
import ch.snapper.android.data.DeskRejectedException
import ch.snapper.android.data.OrderScope
import ch.snapper.android.data.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Confined to the owning UI coroutine dispatcher; replace on backend changes. */
class DeskController(
    private val coroutineScope: CoroutineScope,
    private val client: DeskClient,
    initialUser: UserDto,
    primaryOperatorPublicId: String? = null,
) {
    private var userScope = OrderScope.from(initialUser)
    private var epoch = 0L
    private val mutableState = MutableStateFlow(initialState(primaryOperatorPublicId))
    val state = mutableState.asStateFlow()
    val actions = DeskActions(::load, ::selectDesk, ::setUsername, ::attach)

    private fun initialState(primary: String?) = DeskState(
        primaryOperatorPublicId = primary,
        canManage = "manage:desk_memberships" in userScope.effectivePermissions,
    )

    fun reset(user: UserDto, primaryOperatorPublicId: String? = null) {
        epoch++
        userScope = OrderScope.from(user)
        mutableState.value = initialState(primaryOperatorPublicId)
    }

    fun invalidate() {
        epoch++
        mutableState.value = DeskState()
    }

    fun load() {
        val current = mutableState.value
        if (current.loading || current.submitting) return
        val requestEpoch = epoch
        mutableState.value = current.copy(loading = true)
        coroutineScope.launch {
            try {
                val desks = client.desks().sortedWith(compareBy({ it.publicId != current.primaryOperatorPublicId }, { it.label.lowercase() }, { it.publicId }))
                if (epoch != requestEpoch) return@launch
                val latest = mutableState.value
                val selected = latest.selectedDeskPublicId?.takeIf { id -> desks.any { it.publicId == id } } ?: desks.firstOrNull()?.publicId
                mutableState.value = latest.copy(desks = desks, selectedDeskPublicId = selected, loading = false, loaded = true,
                    issue = latest.issue.takeUnless { it == DeskIssue.LOAD })
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (epoch == requestEpoch) mutableState.value = mutableState.value.copy(loading = false, loaded = true,
                    desks = emptyList(), selectedDeskPublicId = null,
                    issue = if (mutableState.value.issue == DeskIssue.UNKNOWN) DeskIssue.UNKNOWN else DeskIssue.LOAD)
            }
        }
    }

    private fun selectDesk(id: String) {
        val current = mutableState.value
        if (current.submitting || current.issue == DeskIssue.UNKNOWN || current.desks.none { it.publicId == id }) return
        mutableState.value = current.copy(selectedDeskPublicId = id, succeeded = false, issue = null)
    }

    private fun setUsername(value: String) {
        val current = mutableState.value
        if (current.submitting || current.issue == DeskIssue.UNKNOWN) return
        mutableState.value = current.copy(username = value, succeeded = false, issue = null)
    }

    private fun attach() {
        val current = mutableState.value
        if (!current.canSubmit) return
        val username = current.username.trim()
        if (username.isEmpty()) {
            mutableState.value = current.copy(issue = DeskIssue.INVALID_USERNAME)
            return
        }
        val desk = current.selectedDeskPublicId ?: return
        val scope = userScope
        val requestEpoch = epoch
        mutableState.value = current.copy(submitting = true, succeeded = false, issue = null)
        coroutineScope.launch {
            try {
                client.attachViewer(scope, desk, username)
                if (epoch == requestEpoch) mutableState.value = mutableState.value.copy(submitting = false, username = "", succeeded = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: DeskPreparationException) {
                if (epoch == requestEpoch) mutableState.value = mutableState.value.copy(submitting = false, issue = DeskIssue.PREPARATION)
            } catch (_: DeskRejectedException) {
                if (epoch == requestEpoch) mutableState.value = mutableState.value.copy(submitting = false, issue = DeskIssue.REJECTED)
            } catch (_: Exception) {
                if (epoch == requestEpoch) mutableState.value = mutableState.value.copy(submitting = false, issue = DeskIssue.UNKNOWN)
            }
        }
    }
}
