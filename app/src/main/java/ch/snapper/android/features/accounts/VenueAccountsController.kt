package ch.snapper.android.features.accounts

import ch.snapper.android.data.VenueAccountDto
import ch.snapper.android.data.VenueAccountsClient
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VenueAccountsState(
    val accounts: List<VenueAccountDto> = emptyList(), val loading: Boolean = false,
    val failed: Boolean = false, val lastSuccessfulFetch: Instant? = null, val now: Instant = Instant.EPOCH,
) {
    fun truth(account: VenueAccountDto): AccountTruth = AccountTruth.derive(account, now, lastSuccessfulFetch)
}

/** UI-scope confined. Root must invalidate on logout, backend, principal or permission changes. */
class VenueAccountsController(private val scope: CoroutineScope, private val client: VenueAccountsClient, private val clock: Clock) {
    private val mutableState = MutableStateFlow(VenueAccountsState(now = clock.instant()))
    val state = mutableState.asStateFlow()
    private var epoch = 0L
    private var poll: Job? = null
    private var ticker: Job? = null
    private var request: Job? = null
    private var wallet: String? = null
    private var active = false

    fun activate(walletPublicId: String?, permissions: Set<String>, foreground: Boolean, screenActive: Boolean) {
        invalidate()
        wallet = walletPublicId
        if (!foreground || !screenActive || "read:account_state" !in permissions) return
        active = true
        refresh()
        poll = scope.launch { while (isActive) { delay(5_000); refresh() } }
        ticker = scope.launch { while (isActive) { delay(1_000); mutableState.value = mutableState.value.copy(now = clock.instant()) } }
    }

    fun refresh() {
        if (!active || request?.isActive == true) return
        val generation = epoch
        val selected = wallet
        mutableState.value = mutableState.value.copy(loading = true, failed = false)
        request = scope.launch {
            try {
                val values = client.venueAccounts(selected)
                if (generation == epoch && active) mutableState.value = VenueAccountsState(
                    accounts = values.filter { selected == null || it.walletPublicId == selected },
                    lastSuccessfulFetch = clock.instant(), now = clock.instant(),
                )
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (generation == epoch && active) mutableState.value = mutableState.value.copy(loading = false, failed = true, now = clock.instant())
            }
        }
    }

    fun invalidate() {
        epoch++
        active = false
        poll?.cancel(); ticker?.cancel(); request?.cancel()
        poll = null; ticker = null; request = null
        mutableState.value = VenueAccountsState(now = clock.instant())
    }
}
