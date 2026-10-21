package ch.snapper.android.features.push

import ch.snapper.android.data.PushConfiguration
import ch.snapper.android.data.PushRegistration
import ch.snapper.android.data.PushRegistrationCommand
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable
data class AcceptedPushBinding(
    val backend: String, val scopeDigest: String, val configuration: PushConfiguration,
    val registration: PushRegistration, val firebaseInstallationId: String,
)
@Serializable
data class PendingPushBinding(val backend: String, val scopeDigest: String, val configuration: PushConfiguration, val command: PushRegistrationCommand)
@Serializable
data class PushHint(val configurationId: String, val bindingId: String, val registrationId: String, val alertId: String, val expiresAt: Long) {
    companion object {
        private val keys = setOf("type", "schema_version", "configuration_id", "binding_id", "registration_public_id", "alert_public_id", "expires_at")
        fun parse(data: Map<String, String>, now: Long): PushHint? = runCatching {
            require(data.keys == keys && data["type"] == "snapper_alert_hint" && data["schema_version"] == "1")
            fun id(key: String): String = requireNotNull(data[key]).also { require(UUID.fromString(it).toString() == it) }
            val expiry = Instant.parse(data.getValue("expires_at")).toEpochMilli()
            require(expiry > now && expiry <= now + 86_400_000L)
            PushHint(id("configuration_id"), id("binding_id"), id("registration_public_id"), id("alert_public_id"), expiry)
        }.getOrNull()
    }
}
interface PushStore {
    val installationId: String
    var pendingRegistrations: List<PendingPushBinding>
    var pendingCleanup: List<AcceptedPushBinding>
    var accepted: AcceptedPushBinding?
    fun seen(key: String): Boolean
    fun remember(key: String)
    fun clearSeen()
}
interface PushNotice {
    fun allowed(): Boolean
    fun show(hint: PushHint)
    fun cancelAll()
}
interface PushDeliveryGuard {
    fun blocked(): Boolean
    fun block()
    fun enable()
}
class MemoryPushDeliveryGuard : PushDeliveryGuard {
    private var disabled = false
    override fun blocked() = disabled
    override fun block() { disabled = true }
    override fun enable() { disabled = false }
}
/** One process-wide monitor serializes rendering with durable invalidation. Never accesses sessions. */
class PushDelivery(private val store: PushStore, private val notice: PushNotice, private val guard: PushDeliveryGuard = MemoryPushDeliveryGuard(), private val now: () -> Long = System::currentTimeMillis) {
    private val mutableBinding = MutableStateFlow(if (guard.blocked()) null else runCatching { store.accepted }.getOrElse {
        runCatching { guard.block() }
        null
    })
    val bindings = mutableBinding.asStateFlow()
    fun unexpectedProtocol() { invalidate() }
    @Synchronized fun accept(binding: AcceptedPushBinding) {
        try {
            store.accepted = binding
            store.clearSeen()
            guard.enable()
            mutableBinding.value = binding
        } catch (failure: Exception) {
            invalidate()
            throw failure
        }
    }
    @Synchronized fun binding(): AcceptedPushBinding? = mutableBinding.value
    @Synchronized fun invalidate() {
        val old = mutableBinding.value
        mutableBinding.value = null
        runCatching { guard.block() }
        runCatching { notice.cancelAll() }
        runCatching {
            if (old != null) store.pendingCleanup = (store.pendingCleanup + old)
                .distinctBy { it.backend to it.registration.publicId }.takeLast(32)
        }
        runCatching { store.accepted = null }
        runCatching { store.clearSeen() }
    }
    @Synchronized fun dispatched(binding: PendingPushBinding) {
        store.pendingRegistrations = store.pendingRegistrations + binding
    }
    @Synchronized fun pendingRegistrations(): List<PendingPushBinding> = store.pendingRegistrations
    @Synchronized fun resolved(bindingId: String) {
        store.pendingRegistrations = store.pendingRegistrations.filterNot { it.command.bindingId == bindingId }
    }
    @Synchronized fun queueCleanup(binding: AcceptedPushBinding) {
        store.pendingCleanup = (store.pendingCleanup + binding).distinctBy { it.backend to it.registration.publicId }
    }
    @Synchronized fun pendingCleanup(): List<AcceptedPushBinding> = store.pendingCleanup
    @Synchronized fun cleaned(binding: AcceptedPushBinding) {
        store.pendingCleanup = store.pendingCleanup.filterNot { it.registration.publicId == binding.registration.publicId && it.backend == binding.backend }
    }
    @Synchronized fun matches(hint: PushHint): Boolean {
        val accepted = mutableBinding.value ?: return false
        return hint.expiresAt > now() && hint.expiresAt <= now() + 86_400_000L && hint.configurationId == accepted.configuration.configurationId &&
            hint.bindingId == accepted.registration.bindingId && hint.registrationId == accepted.registration.publicId
    }
    @Synchronized fun receive(data: Map<String, String>, sender: String?) {
        try {
            receiveAccepted(data, sender)
        } catch (_: Exception) {
            // Storage failures and permission races must not escape the background service.
            invalidate()
        }
    }
    private fun receiveAccepted(data: Map<String, String>, sender: String?) {
        val accepted = mutableBinding.value ?: return
        if (sender != accepted.configuration.messagingSenderId || !notice.allowed()) return
        val hint = PushHint.parse(data, now()) ?: return
        if (!matches(hint)) return
        val key = "${hint.registrationId}:${hint.alertId}"
        if (store.seen(key)) return
        store.remember(key)
        notice.show(hint)
    }
}
