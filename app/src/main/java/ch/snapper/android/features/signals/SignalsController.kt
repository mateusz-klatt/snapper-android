package ch.snapper.android.features.signals

import ch.snapper.android.data.SignalsClient
import ch.snapper.android.data.UserDto
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Confined to the UI dispatcher; root resets on any identity, wallet, permission or origin change. */
class SignalsController(private val coroutineScope: CoroutineScope, private val client: SignalsClient, initialUser: UserDto) {
    private var epoch = 1L
    private var job: Job? = null
    private val mutableState = MutableStateFlow(initial(initialUser))
    val state = mutableState.asStateFlow()
    val actions = SignalsActions(::load, ::selectStrategy)
    private fun initial(user: UserDto) = SignalsState(scopeKey = UUID.randomUUID().toString(), allowed = user.hasPermission("read:signals"), walletPublicId = user.activeWalletPublicId)

    fun reset(user: UserDto) {
        epoch++
        job?.cancel()
        mutableState.value = initial(user)
    }

    fun invalidate() {
        epoch++
        job?.cancel()
        mutableState.value = SignalsState(scopeKey = UUID.randomUUID().toString())
    }

    private fun selectStrategy(value: String?) {
        val current = mutableState.value
        if (!current.allowed || (value != null && value !in current.strategies)) return
        mutableState.value = current.copy(selectedStrategy = value)
    }

    suspend fun refreshAwaited(stillCurrent: () -> Boolean = { true }) {
        val captured = epoch
        job?.join()
        if (captured != epoch || !stillCurrent()) return
        load()
        job?.join()
    }

    /** Root also calls this on signal events/reconnection; overlapping reloads are collapsed. */
    fun load() {
        val current = mutableState.value
        if (!current.allowed || current.loading) return
        val captured = epoch
        mutableState.value = current.copy(loading = true, failed = false)
        job = coroutineScope.launch {
            try {
                val rows = client.signals(current.walletPublicId)
                if (epoch == captured) mutableState.value = mutableState.value.copy(rows = rows, loading = false)
            } catch (error: CancellationException) {
                if (epoch == captured) mutableState.value = mutableState.value.copy(loading = false)
                throw error
            } catch (_: Exception) {
                if (epoch == captured) mutableState.value = mutableState.value.copy(loading = false, failed = true)
            }
        }
    }
}
