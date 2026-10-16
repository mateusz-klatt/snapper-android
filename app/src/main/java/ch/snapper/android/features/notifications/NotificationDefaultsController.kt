package ch.snapper.android.features.notifications

import ch.snapper.android.data.NotificationDefaultsClient
import ch.snapper.android.data.NotificationDefaultRejectedException
import ch.snapper.android.data.OrderScope
import ch.snapper.android.data.OrderScopeChangedException
import ch.snapper.android.data.UpdateNotificationDefaultPayload
import ch.snapper.android.data.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-dispatcher confined. Root invalidates on logout/origin change and resets on identity/scope changes. */
class NotificationDefaultsController(
    private val coroutineScope: CoroutineScope,
    private val client: NotificationDefaultsClient,
    initialUser: UserDto,
) {
    private var userScope = OrderScope.from(initialUser)
    private var epoch = 0L
    private val mutableState = MutableStateFlow(NotificationDefaultsState(active = true))
    val state = mutableState.asStateFlow()
    val actions = NotificationDefaultsActions(::load, ::setEnabled, ::setPriority, ::save)

    fun reset(user: UserDto) {
        epoch++
        userScope = OrderScope.from(user)
        mutableState.value = NotificationDefaultsState(active = true)
    }

    fun invalidate() {
        epoch++
        mutableState.value = NotificationDefaultsState()
    }

    fun load() {
        val current = mutableState.value
        if (!current.active || current.loading || current.rows.any { it.pending }) return
        val requestEpoch = epoch
        val scope = userScope
        mutableState.value = current.copy(loading = true, loadFailed = false)
        coroutineScope.launch {
            try {
                val values = client.notificationDefaults()
                check(values.all { it.userPublicId == scope.publicId })
                check(values.map { it.alertType }.distinct().size == values.size)
                if (epoch != requestEpoch) return@launch
                val rows = mutableState.value.rows.map { row ->
                    val server = values.find { it.alertType == row.alertType }
                    // An explicit refresh reconciles an uncertain write; never automatically repeats it.
                    if (row.dirty && row.issue != NotificationDefaultIssue.UNKNOWN) row else row.copy(
                        enabled = server?.enabled ?: true,
                        minPriority = server?.minPriority ?: "medium",
                        inherited = server == null, dirty = false, saved = false, issue = null,
                    )
                }
                mutableState.value = mutableState.value.copy(rows = rows, loading = false, loaded = true)
            } catch (error: CancellationException) {
                if (epoch == requestEpoch) mutableState.value = mutableState.value.copy(loading = false)
                throw error
            } catch (_: Exception) {
                if (epoch == requestEpoch) mutableState.value = mutableState.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    private fun edit(type: String, change: (NotificationDefaultRow) -> NotificationDefaultRow) {
        val current = mutableState.value
        if (!current.active || !current.loaded || current.loading) return
        mutableState.value = current.copy(rows = current.rows.map { row ->
            if (row.alertType == type && row.editable) change(row).copy(dirty = true, saved = false, issue = null) else row
        })
    }

    private fun setEnabled(type: String, enabled: Boolean) = edit(type) { it.copy(enabled = enabled) }
    private fun setPriority(type: String, priority: String) {
        if (priority in notificationPriorities) edit(type) { it.copy(minPriority = priority) }
    }

    private fun update(type: String, change: (NotificationDefaultRow) -> NotificationDefaultRow) {
        mutableState.value = mutableState.value.copy(rows = mutableState.value.rows.map { if (it.alertType == type) change(it) else it })
    }

    private fun save(type: String) {
        val current = mutableState.value
        val row = current.rows.find { it.alertType == type } ?: return
        if (!current.active || !current.loaded || current.loading || !row.canSave) return
        val requestEpoch = epoch
        val scope = userScope
        val payload = UpdateNotificationDefaultPayload(type, row.enabled, row.minPriority)
        update(type) { it.copy(pending = true, saved = false, issue = null) }
        coroutineScope.launch {
            try {
                val result = client.updateNotificationDefaults(scope, payload)
                check(result.userPublicId == scope.publicId && result.alertType == type)
                check(result.enabled == payload.enabled && result.minPriority == payload.minPriority)
                if (epoch == requestEpoch) update(type) { it.copy(pending = false, dirty = false, inherited = false, saved = true) }
            } catch (error: CancellationException) {
                if (epoch == requestEpoch) update(type) { it.copy(pending = false, issue = NotificationDefaultIssue.UNKNOWN) }
                throw error
            } catch (_: OrderScopeChangedException) {
                if (epoch == requestEpoch) update(type) { it.copy(pending = false, issue = NotificationDefaultIssue.SCOPE_CHANGED) }
            } catch (_: NotificationDefaultRejectedException) {
                if (epoch == requestEpoch) update(type) { it.copy(pending = false, issue = NotificationDefaultIssue.REJECTED) }
            } catch (_: Exception) {
                if (epoch == requestEpoch) update(type) { it.copy(pending = false, issue = NotificationDefaultIssue.UNKNOWN) }
            }
        }
    }
}
