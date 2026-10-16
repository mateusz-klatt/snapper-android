package ch.snapper.android.features.push

import ch.snapper.android.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

internal const val CONFIG_ID = "00000000-0000-4000-8000-000000000001"
internal const val INSTALL_ID = "00000000-0000-4000-8000-000000000002"
internal const val REG_ID = "00000000-0000-4000-8000-000000000003"
internal const val ALERT_ID = "00000000-0000-4000-8000-000000000004"
internal val configuration = PushConfiguration(CONFIG_ID, "fcm_fid", "test.app", "project", "1:123:android:abc", "public-key", "123")
internal val identity = PushIdentity("https://example.test/", 1, OrderScope("user", "viewer", null, setOf("read:notifications")))
internal class MemoryPushStore : PushStore {
    override val installationId = INSTALL_ID
    override var pendingRegistrations = emptyList<PendingPushBinding>()
    override var pendingCleanup = emptyList<AcceptedPushBinding>()
    override var accepted: AcceptedPushBinding? = null
    private val keys = mutableSetOf<String>()
    override fun seen(key: String) = key in keys
    override fun remember(key: String) { keys += key }
    override fun clearSeen() { keys.clear() }
}
internal class FakeNotice : PushNotice {
    var permission = true
    val shown = mutableListOf<PushHint>()
    override fun allowed() = permission
    override fun show(hint: PushHint) { shown += hint }
    override fun cancelAll() { shown.clear() }
}
internal class FakePlatform : PushPlatform {
    val store = MemoryPushStore()
    val notice = FakeNotice()
    override val installationId = INSTALL_ID
    override val delivery = PushDelivery(store, notice) { 1000 }
    override var lease = 0L
    var registrations = 0
    var unregisters = 0
    var gate: CompletableDeferred<Unit>? = null
    var restart = false
    override fun permissionGranted() = notice.permission
    override suspend fun register(configuration: PushConfiguration): String {
        registrations++; lease++
        if (restart) throw PushRestartRequiredException()
        withContext(NonCancellable) { gate?.await() }
        return "fid-value"
    }
    override suspend fun unregister(expectedLease: Long) { if (expectedLease == lease) unregisters++ }
}
internal class FakePushClient : PushClient {
    var posts = 0
    var reads = 0
    var details = 0
    var unavailable = false
    var unknown = false
    var rejected = false
    var inventoryFailure = false
    var revokeFailure = false
    var revokeGate: CompletableDeferred<Unit>? = null
    var detailGate: CompletableDeferred<Unit>? = null
    var transform: (PushRegistration) -> PushRegistration = { it }
    var config = configuration
    var command: PushRegistrationCommand? = null
    var gate: CompletableDeferred<Unit>? = null
    var rows = emptyList<PushRegistration>()
    override suspend fun pushConfiguration() = PushConfigurationInfo(if (unavailable) "unavailable" else "configured", if (unavailable) null else config)
    override suspend fun pushRegistrationState(configurationId: String, installationId: String): PushRegistrationState { reads++; return PushRegistrationState("opaque-revision") }
    override suspend fun registerPush(command: PushRegistrationCommand): PushRegistration {
        posts++; this.command = command
        withContext(NonCancellable) { gate?.await() }
        if (rejected) throw PushRejectedException("android_push_revision_changed")
        val row = transform(receipt(command))
        rows = listOf(row)
        if (unknown) throw PushUncertainException()
        return row
    }
    fun receipt(command: PushRegistrationCommand) = PushRegistration(REG_ID, command.expectedUserPublicId, command.installationId,
        command.bindingId, command.configurationId, "fcm_fid", "android", "test.app", "active")
    override suspend fun pushInventory(): List<PushRegistration> { check(!inventoryFailure); return rows }
    override suspend fun pushPreferences(registrationId: String) = emptyList<PushPreference>()
    override suspend fun revokePush(registrationId: String) { check(!revokeFailure); withContext(NonCancellable) { revokeGate?.await() }; rows = rows.filterNot { it.publicId == registrationId } }
    override suspend fun pushAlert(alertId: String): AlertDto { details++; withContext(NonCancellable) { detailGate?.await() }; return AlertDto(alertId, "2026-01-01T00:00:00Z", "drift", "low", title = "Authorized alert", body = "Private detail") }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PushControllerTest {
    @Test fun heldCleanupBlocksSameControllerAndCannotUnregisterNewerControllerLease() = runTest {
        val client = FakePushClient(); val platform = FakePlatform()
        val first = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        first.refresh(); runCurrent(); first.enable(); runCurrent()
        client.revokeGate = CompletableDeferred()
        first.disable(); runCurrent(); first.refresh(); first.enable(); runCurrent()
        assertEquals(1, client.posts)
        val next = PushController(backgroundScope, { PushBinding(identity.copy(epoch = 2), client) }, platform, "test.app", "1")
        client.transform = { it.copy(publicId = ALERT_ID) }
        next.refresh(); runCurrent(); next.enable(); runCurrent()
        assertEquals(2, client.posts); assertEquals(PushStatus.ACCEPTED, next.state.value.status)
        client.revokeGate!!.complete(Unit); runCurrent()
        assertEquals(0, platform.unregisters)
        assertEquals(ALERT_ID, platform.delivery.binding()!!.registration.publicId)
        assertEquals(ALERT_ID, client.rows.single().publicId)
    }

    @Test fun malformedOrMismatchedConfigurationsNeverInitializeRuntime() = runTest {
        val invalid = listOf(configuration.copy(protocol = "legacy"), configuration.copy(packageName = "other.app"),
            configuration.copy(configurationId = ""), configuration.copy(projectId = ""), configuration.copy(applicationId = ""),
            configuration.copy(apiKey = " "), configuration.copy(messagingSenderId = "x".repeat(257)))
        invalid.forEach { config ->
            val client = FakePushClient().apply { this.config = config }; val platform = FakePlatform()
            val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
            controller.refresh(); runCurrent(); controller.enable(); runCurrent()
            assertEquals(PushStatus.UNAVAILABLE, controller.state.value.status); assertEquals(0, platform.registrations)
        }
    }

    @Test fun contradictoryReceiptNeverAuthorizesDeliveryOrAllowsAutomaticReplay() = runTest {
        val changes: List<(PushRegistration) -> PushRegistration> = listOf(
            { it.copy(userPublicId = "other") }, { it.copy(bindingId = "other") }, { it.copy(installationId = "other") },
            { it.copy(configurationId = "other") }, { it.copy(packageName = "other") }, { it.copy(status = "retired") },
            { it.copy(protocol = "legacy") }, { it.copy(platform = "ios") },
        )
        changes.forEach { change ->
            val client = FakePushClient().apply { transform = change }; val platform = FakePlatform()
            val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
            controller.refresh(); runCurrent(); controller.enable(); runCurrent()
            assertEquals(PushStatus.UNCERTAIN, controller.state.value.status); assertNull(platform.delivery.binding())
            controller.enable(); runCurrent(); assertEquals(1, client.posts)
        }
    }

    @Test fun authoritativeInventoryRevocationAndConfigurationReplacementClearLocalAcceptance() = runTest {
        val client = FakePushClient(); val platform = FakePlatform()
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        controller.refresh(); runCurrent(); assertEquals(PushStatus.ACCEPTED, controller.state.value.status)
        client.rows = emptyList(); controller.refresh(); runCurrent()
        assertNull(platform.delivery.binding()); assertEquals(PushStatus.READY, controller.state.value.status)
        controller.enable(); runCurrent(); client.config = configuration.copy(configurationId = ALERT_ID)
        controller.refresh(); runCurrent(); assertNull(platform.delivery.binding())
        assertEquals(ALERT_ID, controller.state.value.configuration!!.configurationId)
    }

    @Test fun failedReconcileKeepsUnknownAndFailedBackendCleanupDoesNotSkipSdkCleanup() = runTest {
        val client = FakePushClient().apply { unknown = true }; val platform = FakePlatform()
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        client.inventoryFailure = true; controller.reconcile(); runCurrent()
        assertEquals(PushStatus.UNCERTAIN, controller.state.value.status)
        controller.disable(); runCurrent()
        assertEquals(1, platform.unregisters); assertEquals(PushStatus.CLEANUP_PENDING, controller.state.value.status)
        assertEquals(1, platform.delivery.pendingRegistrations().size)
        client.inventoryFailure = false; client.revokeFailure = true; controller.disable(); runCurrent()
        assertEquals(2, platform.unregisters); assertEquals(1, platform.delivery.pendingCleanup().size)
    }

    @Test fun delayedAuthorizedDetailIsDiscardedAfterPermissionScopeChanges() = runTest {
        val client = FakePushClient(); val platform = FakePlatform(); var owner = PushBinding(identity, client)
        val controller = PushController(backgroundScope, { owner }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        val receipt = platform.delivery.binding()!!.registration
        client.detailGate = CompletableDeferred()
        controller.open(PushHint(CONFIG_ID, receipt.bindingId, REG_ID, ALERT_ID, 2000)); runCurrent()
        owner = PushBinding(identity.copy(actor = identity.actor.copy(effectivePermissions = emptySet())), client)
        client.detailGate!!.complete(Unit); runCurrent()
        assertNull(controller.state.value.alert)
    }

    @Test fun permissionRevokedDuringPostRetainsReceiptForCleanupAndLeavesRegistering() = runTest {
        val client = FakePushClient(); client.gate = CompletableDeferred()
        val platform = FakePlatform()
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        assertEquals(1, platform.delivery.pendingRegistrations().size)
        platform.notice.permission = false
        client.gate!!.complete(Unit); runCurrent()
        assertNull(platform.delivery.binding())
        assertEquals(PushStatus.PERMISSION_REQUIRED, controller.state.value.status)
        assertEquals(REG_ID, platform.delivery.pendingCleanup().single().registration.publicId)
        controller.disable(); runCurrent()
        assertTrue(client.rows.isEmpty()); assertTrue(platform.delivery.pendingCleanup().isEmpty())
    }

    @Test fun uncertainPostSurvivesControllerLossAndExplicitDisableReconcilesWithoutReplay() = runTest {
        val client = FakePushClient(); client.unknown = true
        val platform = FakePlatform()
        val first = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        first.refresh(); runCurrent(); first.enable(); runCurrent()
        assertEquals(PushStatus.UNCERTAIN, first.state.value.status)
        assertEquals(1, platform.store.pendingRegistrations.size)
        val cold = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        cold.disable(); runCurrent()
        assertEquals(1, client.posts)
        assertTrue(client.rows.isEmpty())
        assertTrue(platform.store.pendingRegistrations.isEmpty())
    }

    @Test fun deniedPermissionAndUnavailableBackendNeverInitializeSdkOrPost() = runTest {
        val client = FakePushClient(); val platform = FakePlatform(); platform.notice.permission = false
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        assertEquals(PushStatus.PERMISSION_REQUIRED, controller.state.value.status)
        assertEquals(0, platform.registrations); assertEquals(0, client.posts)
        client.unavailable = true; controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        assertEquals(PushStatus.UNAVAILABLE, controller.state.value.status); assertEquals(0, platform.registrations)
    }

    @Test fun explicitEnablePreparesRevisionAndDoubleTapSendsExactlyOnce() = runTest {
        val client = FakePushClient(); val platform = FakePlatform(); platform.gate = CompletableDeferred()
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); controller.enable(); runCurrent()
        assertEquals(1, platform.registrations); assertEquals(0, client.posts)
        platform.gate!!.complete(Unit); runCurrent()
        assertEquals(1, client.posts); assertEquals("opaque-revision", client.command!!.expectedRevision)
        assertEquals("fid-value", platform.delivery.binding()!!.firebaseInstallationId)
        assertEquals(PushStatus.ACCEPTED, controller.state.value.status)
    }

    @Test fun ambiguousPostCanOnlyReconcileAndNeverReplaysOrRemints() = runTest {
        val client = FakePushClient(); client.unknown = true; val platform = FakePlatform()
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        val captured = client.command
        assertEquals(PushStatus.UNCERTAIN, controller.state.value.status); assertNull(platform.delivery.binding())
        controller.enable(); controller.refresh(); runCurrent(); assertSame(captured, client.command); assertEquals(1, client.posts)
        controller.reconcile(); runCurrent()
        assertEquals(PushStatus.ACCEPTED, controller.state.value.status); assertEquals(1, client.posts)
    }

    @Test fun absentReconciliationDoesNotResolveUnknownOrCreateNewBinding() = runTest {
        val client = FakePushClient(); client.unknown = true; val platform = FakePlatform()
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        client.rows = emptyList(); controller.reconcile(); runCurrent(); controller.enable(); runCurrent()
        assertEquals(PushStatus.UNCERTAIN, controller.state.value.status); assertEquals(1, client.posts)
    }

    @Test fun noncancellableOldPostCannotSurviveABASessionReplacement() = runTest {
        val client = FakePushClient(); val platform = FakePlatform(); var owner = PushBinding(identity, client)
        val controller = PushController(backgroundScope, { owner }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); client.gate = CompletableDeferred(); controller.enable(); runCurrent()
        owner = PushBinding(identity.copy(epoch = 2, backend = "https://other.test/"), client); controller.disable()
        owner = PushBinding(identity.copy(epoch = 3), client); controller.invalidate()
        client.gate!!.complete(Unit); runCurrent()
        assertNull(platform.delivery.binding()); assertEquals(PushStatus.OFF, controller.state.value.status)
        assertEquals(1, client.posts)
    }

    @Test fun permissionRevocationDuringSdkTaskPreventsBackendRegistration() = runTest {
        val client = FakePushClient(); val platform = FakePlatform(); platform.gate = CompletableDeferred()
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent(); platform.notice.permission = false
        platform.gate!!.complete(Unit); runCurrent()
        assertEquals(0, client.posts); assertNull(platform.delivery.binding())
        assertEquals(PushStatus.PERMISSION_REQUIRED, controller.state.value.status)
    }

    @Test fun differentProjectRequiresRestartAndDoesNotPost() = runTest {
        val client = FakePushClient(); val platform = FakePlatform(); platform.restart = true
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        assertEquals(PushStatus.RESTART_REQUIRED, controller.state.value.status); assertEquals(0, client.posts)
    }

    @Test fun knownRefusalDoesNotCreateAcceptedDeliveryBinding() = runTest {
        val client = FakePushClient(); client.rejected = true; val platform = FakePlatform()
        val controller = PushController(backgroundScope, { PushBinding(identity, client) }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        assertEquals(PushStatus.REJECTED, controller.state.value.status); assertNull(platform.delivery.binding())
    }

    @Test fun tapRequiresMatchingCurrentScopeAndFreshAuthorizedAlert() = runTest {
        val client = FakePushClient(); val platform = FakePlatform(); var owner = PushBinding(identity, client)
        val controller = PushController(backgroundScope, { owner }, platform, "test.app", "1")
        controller.refresh(); runCurrent(); controller.enable(); runCurrent()
        val receipt = platform.delivery.binding()!!.registration
        val hint = PushHint(CONFIG_ID, receipt.bindingId, REG_ID, ALERT_ID, 2000)
        controller.open(hint); runCurrent(); assertEquals("Private detail", controller.state.value.alert!!.body)
        controller.actions.dismissAlert(); owner = PushBinding(identity.copy(actor = identity.actor.copy(effectivePermissions = emptySet())), client)
        controller.open(hint); runCurrent(); assertNull(controller.state.value.alert); assertEquals(1, client.details)
        controller.disable(); runCurrent(); controller.open(hint); runCurrent(); assertEquals(1, client.details)
    }
}
