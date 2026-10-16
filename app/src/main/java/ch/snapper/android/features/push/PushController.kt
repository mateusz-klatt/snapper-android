package ch.snapper.android.features.push

import ch.snapper.android.data.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface PushPlatform {
    val installationId: String
    fun permissionGranted(): Boolean
    suspend fun register(configuration: PushConfiguration): String
    val lease: Long
    suspend fun unregister(expectedLease: Long)
    val delivery: PushDelivery
}
class PushRestartRequiredException : Exception()
enum class PushStatus { OFF, LOADING, UNAVAILABLE, PERMISSION_REQUIRED, READY, REGISTERING, ACCEPTED, REJECTED, UNCERTAIN, RESTART_REQUIRED, CLEANUP_PENDING }
data class PushState(
    val status: PushStatus = PushStatus.OFF,
    val configuration: PushConfiguration? = null,
    val inventory: List<PushRegistration> = emptyList(),
    val preferences: List<PushPreference> = emptyList(),
    val alert: AlertDto? = null,
)
data class PushActions(
    val refresh: () -> Unit = {}, val enable: () -> Unit = {}, val disable: () -> Unit = {},
    val reconcile: () -> Unit = {}, val dismissAlert: () -> Unit = {},
)
fun PushIdentity.scopeDigest(): String {
    val values = listOf(backend, actor.publicId, actor.username, actor.activeWalletPublicId.orEmpty(),
        actor.primaryOperatorPublicId.orEmpty(), actor.delegatePublicId.orEmpty()) + actor.effectivePermissions.sorted()
    return MessageDigest.getInstance("SHA-256").digest(values.joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
/** All requests retain an immutable owner. Mutations never use a generic dashboard retry. */
class PushController(
    private val scope: CoroutineScope, private val binding: () -> PushBinding?, private val platform: PushPlatform,
    private val packageName: String, private val version: String,
) {
    private data class Intent(val owner: PushBinding, val configuration: PushConfiguration, val command: PushRegistrationCommand)
    private var intent: Intent? = null
    private var revision = 0L
    private var busy = false
    private var cleanupsInFlight = 0
    private val sdkMutex = Mutex()
    private val mutable = MutableStateFlow(PushState())
    val state = mutable.asStateFlow()
    init {
        scope.launch {
            platform.delivery.bindings.collect { accepted ->
                if (accepted == null && state.value.status == PushStatus.ACCEPTED) mutable.value = state.value.copy(status = PushStatus.READY)
            }
        }
    }
    val actions = PushActions(::refresh, ::enable, ::disable, ::reconcile) { mutable.value = state.value.copy(alert = null) }
    private fun current(owner: PushBinding, request: Long): Boolean = request == revision && binding()?.let {
        it.identity == owner.identity && it.client === owner.client
    } == true

    fun invalidate() {
        revision++
        intent = null
        mutable.value = PushState()
        platform.delivery.invalidate()
        // Do not cancel a dispatched SDK operation and overlap it with its successor.
    }

    fun refresh() {
        if (busy || cleanupsInFlight > 0 || state.value.status == PushStatus.UNCERTAIN) return
        val owner = binding() ?: return
        val request = revision
        busy = true
        mutable.value = state.value.copy(status = PushStatus.LOADING, alert = null)
        scope.launch {
            try {
                refreshConfiguration(owner, request)
            } catch (_: Exception) {
                if (current(owner, request)) mutable.value = state.value.copy(status = PushStatus.UNAVAILABLE)
            } finally { busy = false }
        }
    }

    private suspend fun refreshConfiguration(owner: PushBinding, request: Long) {
        val info = owner.client.pushConfiguration()
        if (!current(owner, request)) return
        val configuration = info.configuration?.takeIf { info.availability == "configured" && valid(it) }
        if (configuration == null) {
            platform.delivery.invalidate()
            mutable.value = PushState(PushStatus.UNAVAILABLE)
            return
        }
        val saved = platform.delivery.binding()
        if (saved != null && (saved.configuration != configuration || saved.scopeDigest != owner.identity.scopeDigest())) platform.delivery.invalidate()
        mutable.value = state.value.copy(status = PushStatus.READY, configuration = configuration)
        refreshInventory(owner, request)
    }

    private suspend fun refreshInventory(owner: PushBinding, request: Long) {
        val inventory = owner.client.pushInventory()
        if (!current(owner, request)) return
        val active = platform.delivery.binding()
        if (active != null && inventory.none { it == active.registration }) platform.delivery.invalidate()
        mutable.value = state.value.copy(inventory = inventory, status = if (platform.delivery.binding() != null) PushStatus.ACCEPTED else PushStatus.READY)
        if (active != null && platform.delivery.binding() != null) {
            val prefs = owner.client.pushPreferences(active.registration.publicId)
            if (current(owner, request)) mutable.value = state.value.copy(preferences = prefs)
        }
    }

    private fun valid(config: PushConfiguration): Boolean = config.protocol == "fcm_fid" && config.packageName == packageName &&
        listOf(config.configurationId, config.projectId, config.applicationId, config.apiKey, config.messagingSenderId).all { it.isNotBlank() && it.length <= 256 }

    fun enable() {
        if (busy || cleanupsInFlight > 0 || intent != null) return
        val owner = binding() ?: return
        val config = state.value.configuration ?: return
        if (!platform.permissionGranted()) { mutable.value = state.value.copy(status = PushStatus.PERMISSION_REQUIRED); return }
        val request = revision
        busy = true
        mutable.value = state.value.copy(status = PushStatus.REGISTERING)
        scope.launch {
            try {
                register(owner, request, config)
            } catch (_: PushRestartRequiredException) {
                if (current(owner, request)) mutable.value = state.value.copy(status = PushStatus.RESTART_REQUIRED)
            } catch (_: PushRejectedException) {
                if (current(owner, request)) {
                    val cleared = runCatching { intent?.let { platform.delivery.resolved(it.command.bindingId) } }.isSuccess
                    intent = null
                    mutable.value = state.value.copy(status = if (cleared) PushStatus.REJECTED else PushStatus.CLEANUP_PENDING)
                }
            } catch (_: Exception) {
                if (current(owner, request)) mutable.value = state.value.copy(status = if (intent != null) PushStatus.UNCERTAIN else PushStatus.REJECTED)
            } finally { busy = false }
        }
    }

    private suspend fun register(owner: PushBinding, request: Long, config: PushConfiguration) {
        val fid = sdkMutex.withLock { platform.register(config) }
        if (!current(owner, request)) return
        if (!platform.permissionGranted()) { mutable.value = state.value.copy(status = PushStatus.PERMISSION_REQUIRED); return }
        require(fid.isNotBlank() && fid.length <= 256 && fid.all { it.code in 33..126 })
        val prepared = owner.client.pushRegistrationState(config.configurationId, platform.installationId)
        if (!current(owner, request)) return
        val command = PushRegistrationCommand(protocol = "fcm_fid", configurationId = config.configurationId, packageName = packageName,
            installationId = platform.installationId, bindingId = UUID.randomUUID().toString(), expectedRevision = prepared.revision,
            firebaseInstallationId = fid, expectedUserPublicId = owner.identity.actor.publicId, appVersion = version)
        val captured = Intent(owner, config, command)
        platform.delivery.dispatched(PendingPushBinding(owner.identity.backend, owner.identity.scopeDigest(), config, command))
        intent = captured
        val receipt = owner.client.registerPush(command)
        if (current(owner, request)) accept(captured, receipt)
        else platform.delivery.queueCleanup(acceptedBinding(captured, receipt))
    }

    private fun acceptedBinding(captured: Intent, receipt: PushRegistration): AcceptedPushBinding {
        val command = captured.command
        require(receipt.userPublicId == command.expectedUserPublicId && receipt.bindingId == command.bindingId &&
            receipt.installationId == command.installationId && receipt.configurationId == command.configurationId &&
            receipt.packageName == packageName && receipt.status == "active" && receipt.protocol == "fcm_fid" && receipt.platform == "android")
        return AcceptedPushBinding(captured.owner.identity.backend, captured.owner.identity.scopeDigest(), captured.configuration, receipt, command.firebaseInstallationId)
    }

    private fun accept(captured: Intent, receipt: PushRegistration) {
        val accepted = acceptedBinding(captured, receipt)
        if (!platform.permissionGranted()) {
            platform.delivery.invalidate()
            platform.delivery.queueCleanup(accepted)
            platform.delivery.resolved(captured.command.bindingId)
            intent = null
            mutable.value = state.value.copy(status = PushStatus.PERMISSION_REQUIRED)
            return
        }
        platform.delivery.accept(accepted)
        platform.delivery.resolved(captured.command.bindingId)
        intent = null
        mutable.value = state.value.copy(status = PushStatus.ACCEPTED, inventory = listOf(receipt))
    }

    /** Pure read reconciliation: uncertainty never triggers another POST or a fresh binding automatically. */
    fun reconcile() {
        if (busy || cleanupsInFlight > 0) return
        val captured = intent ?: return
        val request = revision
        if (!current(captured.owner, request)) return
        busy = true
        scope.launch {
            try {
                val rows = captured.owner.client.pushInventory()
                if (!current(captured.owner, request)) return@launch
                rows.firstOrNull { it.bindingId == captured.command.bindingId }?.let { accept(captured, it) }
            } catch (_: Exception) { /* Preserve uncertainty, including failed reads. */ }
            finally { busy = false }
        }
    }

    fun disable() {
        val owner = binding()
        val lease = platform.lease
        invalidate()
        val request = revision
        cleanupsInFlight++
        mutable.value = state.value.copy(status = PushStatus.CLEANUP_PENDING)
        scope.launch {
            try {
                val backendCleaned = runCatching { if (owner != null) cleanupBackend(owner) }.isSuccess
                val sdkCleaned = sdkMutex.withLock { runCatching { platform.unregister(lease) }.isSuccess }
                if (request == revision) {
                    val pending = runCatching { platform.delivery.pendingCleanup().isNotEmpty() || platform.delivery.pendingRegistrations().isNotEmpty() }.getOrDefault(true)
                    mutable.value = state.value.copy(status = if (!sdkCleaned || !backendCleaned || pending) PushStatus.CLEANUP_PENDING else PushStatus.OFF)
                }
            } finally { cleanupsInFlight-- }
        }
    }

    private suspend fun cleanupBackend(owner: PushBinding) {
        val pending = platform.delivery.pendingRegistrations().filter { it.backend == owner.identity.backend && it.scopeDigest == owner.identity.scopeDigest() }
        if (pending.isNotEmpty()) {
            val inventory = owner.client.pushInventory()
            pending.forEach { dispatched ->
                inventory.firstOrNull { it.bindingId == dispatched.command.bindingId }?.let { receipt ->
                    val captured = Intent(owner, dispatched.configuration, dispatched.command)
                    platform.delivery.queueCleanup(acceptedBinding(captured, receipt))
                    platform.delivery.resolved(dispatched.command.bindingId)
                }
            }
        }

        platform.delivery.pendingCleanup().filter { it.backend == owner.identity.backend && it.scopeDigest == owner.identity.scopeDigest() }.forEach { old ->
            try {
                owner.client.revokePush(old.registration.publicId)
                platform.delivery.cleaned(old)
            } catch (error: PushRejectedException) {
                if (error.statusCode == 404) platform.delivery.cleaned(old)
            } catch (_: Exception) {
                // Durable pending record survives offline/expired-auth cleanup. No credential is persisted here.
            }
        }
    }

    fun open(hint: PushHint) {
        val owner = binding() ?: return
        val request = revision
        val saved = platform.delivery.binding() ?: return
        if (!platform.delivery.matches(hint) || saved.scopeDigest != owner.identity.scopeDigest() || "read:notifications" !in owner.identity.actor.effectivePermissions) return
        scope.launch {
            try {
                val alert = owner.client.pushAlert(hint.alertId)
                if (current(owner, request) && platform.delivery.matches(hint) && alert.publicId == hint.alertId) mutable.value = state.value.copy(alert = alert)
            } catch (_: Exception) { /* No private content or navigation without a fresh authorized receipt. */ }
        }
    }
}
