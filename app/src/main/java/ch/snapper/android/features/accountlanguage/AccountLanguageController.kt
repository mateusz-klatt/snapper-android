package ch.snapper.android.features.accountlanguage

import ch.snapper.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class AccountLanguageStatus { IDLE, SYNCING, ACCEPTED, FAILED }
data class AccountLanguageState(
    val desired: String? = null, val accepted: String? = null,
    val status: AccountLanguageStatus = AccountLanguageStatus.IDLE,
    val failure: AccountLanguageFailureKind? = null,
)

/** UI dispatcher confined. One worker serializes all writes, including noncancellable old-context requests. */
class AccountLanguageController(
    private val scope: CoroutineScope,
    private val binding: () -> AccountLanguageBinding?,
    private val accepted: (AccountLanguageIdentity, String) -> Unit,
) {
    private data class Request(val binding: AccountLanguageBinding, val language: String, val revision: Long)
    private var revision = 0L
    private var requested: Request? = null
    private var worker: Job? = null
    private val mutable = MutableStateFlow(AccountLanguageState())
    val state = mutable.asStateFlow()

    fun choose(language: String) {
        if (SupportedLanguages.find(language) == null) return
        val owner = binding() ?: run { invalidate(); return }
        val old = requested
        if (old != null && sameOwner(old.binding, owner) && old.language == language) return
        requested = Request(owner, language, ++revision)
        val priorAccepted = state.value.accepted.takeIf { old != null && sameOwner(old.binding, owner) }
        mutable.value = AccountLanguageState(desired = language, accepted = priorAccepted, status = AccountLanguageStatus.SYNCING)
        start()
    }

    /** User explicitly authorizes another attempt; failures never schedule their own retry. */
    fun retry() {
        val previous = requested ?: return
        if (state.value.status != AccountLanguageStatus.FAILED || !current(previous)) return
        requested = previous.copy(revision = ++revision)
        mutable.value = state.value.copy(status = AccountLanguageStatus.SYNCING, failure = null)
        start()
    }

    fun invalidate() {
        revision++
        requested = null
        mutable.value = AccountLanguageState()
    }

    /** Login integration awaits this before the first description-bearing reads; failures remain observable. */
    suspend fun awaitSettled() {
        while (worker?.isActive == true) worker?.join()
    }

    private fun sameOwner(first: AccountLanguageBinding, second: AccountLanguageBinding): Boolean =
        first.identity == second.identity && first.client === second.client
    private fun current(request: Request): Boolean = requested?.revision == request.revision &&
        binding()?.let { sameOwner(request.binding, it) } == true

    private fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            var completed = -1L
            while (true) {
                val next = requested?.takeIf { it.revision != completed } ?: break
                if (current(next)) send(next)
                else if (requested?.revision == next.revision) invalidate()
                completed = next.revision
            }
        }
    }

    private suspend fun send(request: Request) {
        try {
            val result = request.binding.client.updateLanguage(request.binding.identity, request.language)
            if (!current(request)) return
            if (result.identity != request.binding.identity || result.language != request.language) {
                fail(AccountLanguageFailureKind.UNKNOWN)
                return
            }
            mutable.value = AccountLanguageState(request.language, result.language, AccountLanguageStatus.ACCEPTED)
            runCatching { accepted(request.binding.identity, result.language) }
        } catch (cancelled: CancellationException) {
            if (current(request)) fail(AccountLanguageFailureKind.UNKNOWN)
            // An inner timeout may cancel only this request, while our owning coroutine remains active.
            // The worker records this revision as completed and may drain a newer explicit intent.
            currentCoroutineContext().ensureActive()
        } catch (error: Exception) {
            if (current(request)) fail((error as? AccountLanguageException)?.kind ?: AccountLanguageFailureKind.UNKNOWN)
        }
    }

    private fun fail(kind: AccountLanguageFailureKind) {
        mutable.value = state.value.copy(status = AccountLanguageStatus.FAILED, failure = kind)
    }
}
