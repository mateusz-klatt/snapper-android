package ch.snapper.android.features.push

import android.app.Notification
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.data.PushConfiguration
import ch.snapper.android.data.PushRegistration
import ch.snapper.android.data.PushRegistrationCommand
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.firebase.FirebaseApp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidPushPlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = PushConfiguration("00000000-0000-4000-8000-000000000001", "fcm_fid", "test.app", "synthetic-project", "1:123:android:abc", "synthetic-public-key", "123")
    private fun accepted() = AcceptedPushBinding("https://example.invalid", "scope", config,
        PushRegistration("registration", "user", "installation", "binding", config.configurationId, "fcm_fid", "android", "test.app", "active"), "observed-fid")
    private class Notice : PushNotice {
        var permitted = true
        override fun allowed() = permitted
        override fun show(hint: PushHint) = Unit
        override fun cancelAll() = Unit
    }
    private class Runtime : FirebaseRuntime {
        val initialized = mutableListOf<PushConfiguration>()
        var registrations = 0
        var removals = 0
        var failRemoval = false
        var id = "observed-fid"
        var onRegister: () -> Unit = {}
        override fun initialize(configuration: PushConfiguration) { initialized += configuration }
        override suspend fun register() { registrations++; onRegister() }
        override suspend fun unregister() { removals++; check(!failRemoval) }
        override suspend fun installationId() = id
    }
    private fun storage(block: (Context, AndroidPushStore) -> Unit) {
        val folder = File(context.cacheDir, "push-test-" + UUID.randomUUID()).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        try { block(isolated, AndroidPushStore(isolated)) } finally { folder.deleteRecursively() }
    }
    @Test fun releasedTaskBridgePreservesSuccessFailureAndCancellationWithoutFirebase() = runBlocking {
        assertEquals("observed", Tasks.forResult("observed").awaitCompletion())
        val failure = java.io.IOException("Synthetic task failure")
        assertSame(failure, runCatching { Tasks.forException<String>(failure).awaitCompletion() }.exceptionOrNull())
        val cancellation = CancellationTokenSource()
        val cancelled = TaskCompletionSource<String>(cancellation.token)
        cancellation.cancel()
        assertTrue(runCatching { cancelled.task.awaitCompletion() }.exceptionOrNull() is IllegalStateException)
    }
    @Test fun coroutineCancellationDoesNotDetachPendingSdkTaskAndPermitOverlappingCleanup() = runBlocking {
        val sdk = TaskCompletionSource<String>()
        val started = CompletableDeferred<Unit>()
        val operation = launch(Dispatchers.Default) {
            started.complete(Unit)
            sdk.task.awaitCompletion()
        }
        started.await()
        operation.cancel()
        assertFalse(operation.isCompleted)
        sdk.setResult("completed")
        operation.join()
        assertTrue(operation.isCompleted)
    }
    @Test fun pendingPostJournalSurvivesRealAtomicStoreReconstructionBeforeAnyReceipt() = storage { isolated, store ->
        val command = PushRegistrationCommand("fcm_fid", config.configurationId, "test.app", "installation", "binding", "a".repeat(64), "observed-fid", "user", "1")
        val pending = PendingPushBinding("https://example.invalid", "scope", config, command)
        store.pendingRegistrations = listOf(pending)
        val cold = AndroidPushStore(isolated)
        assertEquals(listOf(pending), cold.pendingRegistrations)
        assertNull(cold.accepted)
        cold.pendingRegistrations = emptyList()
        assertTrue(store.pendingRegistrations.isEmpty())
    }
    @Test fun genericNoticeContainsOnlyStaticInvitationAndImmutableExplicitAppIntent() {
        val notice = AndroidPushNotice(context).notification(PushHint("configuration", "binding", "registration", "alert", 2000))
        assertEquals("Snapper updates", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Open Snapper to view updates", notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(context.packageName, notice.contentIntent.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(notice.contentIntent.isImmutable)
        assertEquals(Notification.VISIBILITY_PUBLIC, notice.visibility)
        assertNull(notice.extras.getString("account")); assertNull(notice.extras.getString("body"))
    }
    @Test fun corruptAtomicJournalBlocksColdInitializationWithoutReadingAnySession() = storage { isolated, _ ->
        File(isolated.noBackupFilesDir, "push-public-binding.json").writeText("{broken")
        val runtime = Runtime(); val guard = MemoryPushDeliveryGuard()
        val platform = AndroidPushPlatform(AndroidPushStore(isolated), Notice(), guard, runtime)
        assertNull(platform.delivery.binding()); assertTrue(guard.blocked())
        assertTrue(runtime.initialized.isEmpty())
        platform.delivery.invalidate() // A malformed cleanup journal must not escape logout.
        assertNull(platform.delivery.binding())
    }
    @Test fun atomicJournalWriteFailureStopsRegistrationBeforeAnySdkInitialization() {
        val obstruction = File(context.cacheDir, "push-obstruction-" + UUID.randomUUID()).apply { writeText("file, not directory") }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = obstruction }
        try {
            val runtime = Runtime()
            val platform = AndroidPushPlatform(AndroidPushStore(isolated), Notice(), MemoryPushDeliveryGuard(), runtime)
            assertTrue(runCatching { runBlocking { platform.register(config) } }.isFailure)
            assertTrue(runtime.initialized.isEmpty()); assertEquals(0, runtime.registrations)
        } finally { obstruction.delete() }
    }
    @Test fun notificationIntentCarriesOnlyBoundedOpaqueHintAndRejectsMalformedExtras() {
        val hint = PushHint("configuration", "binding", "registration", "alert", 2000)
        assertEquals(hint, AndroidPushPlatform.hint(Intent().putExtra(AndroidPushNotice.EXTRA_HINT, Json.encodeToString(hint))))
        assertNull(AndroidPushPlatform.hint(null))
        assertNull(AndroidPushPlatform.hint(Intent()))
        assertNull(AndroidPushPlatform.hint(Intent().putExtra(AndroidPushNotice.EXTRA_HINT, "{broken")))
        assertNull(AndroidPushPlatform.hint(Intent().putExtra(AndroidPushNotice.EXTRA_HINT, "x".repeat(2049))))
    }
    @Test fun noConsentConstructionAndDeniedPermissionNeverInitializeFirebase() = storage { _, store ->
        val before = FirebaseApp.getApps(context).map { it.name }
        val runtime = Runtime(); val notice = Notice().apply { permitted = false }
        val platform = AndroidPushPlatform(store, notice, MemoryPushDeliveryGuard(), runtime)
        assertTrue(runCatching { runBlocking { platform.register(config) } }.isFailure)
        assertTrue(runtime.initialized.isEmpty()); assertEquals(0, runtime.registrations)
        assertEquals(before, FirebaseApp.getApps(context).map { it.name })
    }
    @Test fun everyExplicitRegistrationRequiresObservedCallbackAndDurableCleanupBeforeSdkCall() = storage { isolated, store ->
        val runtime = Runtime()
        val platform = AndroidPushPlatform(store, Notice(), MemoryPushDeliveryGuard(), runtime)
        runtime.onRegister = {
            assertEquals(config, AndroidPushStore(isolated).cleanupConfiguration)
            assertNull(store.accepted)
            platform.registered("observed-fid")
        }
        runBlocking {
            assertEquals("observed-fid", platform.register(config))
            assertEquals("observed-fid", platform.register(config))
        }
        assertEquals(2, runtime.registrations); assertEquals(1, runtime.removals)
        assertNull(platform.delivery.binding())
        assertEquals(platform.installationId, AndroidPushStore(isolated).installationId)
    }
    @Test fun staleCleanupLeaseCannotUnregisterNewerExplicitRegistration() = storage { _, store ->
        val runtime = Runtime(); val platform = AndroidPushPlatform(store, Notice(), MemoryPushDeliveryGuard(), runtime)
        runtime.onRegister = { platform.registered("observed-fid") }
        runBlocking { platform.register(config) }
        val oldLease = platform.lease
        runBlocking { platform.register(config) }
        val removalsBeforeOldCleanup = runtime.removals
        runBlocking { platform.unregister(oldLease) }
        assertEquals(removalsBeforeOldCleanup, runtime.removals)
        assertEquals(config, store.cleanupConfiguration)
    }
    @Test fun observedCallbackMustMatchCurrentInstallationAndCannotAuthorizeByItself() = storage { _, store ->
        val runtime = Runtime().apply { id = "different-fid" }
        val platform = AndroidPushPlatform(store, Notice(), MemoryPushDeliveryGuard(), runtime)
        runtime.onRegister = { platform.registered("observed-fid") }
        assertTrue(runCatching { runBlocking { platform.register(config) } }.isFailure)
        assertNull(platform.delivery.binding()); assertEquals(config, store.cleanupConfiguration)
    }
    @Test fun failedSdkCleanupSurvivesColdRestartAndDoesNotRequireAcceptedRecord() = storage { isolated, store ->
        val runtime = Runtime(); val guard = MemoryPushDeliveryGuard()
        val platform = AndroidPushPlatform(store, Notice(), guard, runtime)
        runtime.onRegister = { platform.registered("observed-fid") }
        runBlocking { platform.register(config) }
        platform.delivery.accept(accepted()); platform.delivery.invalidate()
        assertNull(AndroidPushStore(isolated).accepted)
        val coldRuntime = Runtime().apply { failRemoval = true }
        val cold = AndroidPushPlatform(AndroidPushStore(isolated), Notice(), guard, coldRuntime)
        assertTrue(coldRuntime.initialized.isEmpty())
        assertTrue(runCatching { runBlocking { cold.unregister(cold.lease) } }.isFailure)
        assertEquals(config, AndroidPushStore(isolated).cleanupConfiguration)
        coldRuntime.failRemoval = false
        runBlocking { cold.unregister(cold.lease) }
        assertEquals(2, coldRuntime.removals)
        assertNull(AndroidPushStore(isolated).cleanupConfiguration)
    }
    @Test fun acceptedColdBindingInitializesOnlyItsProjectAndFidRotationInvalidatesIt() = storage { _, store ->
        store.accepted = accepted()
        val runtime = Runtime(); val guard = MemoryPushDeliveryGuard()
        val platform = AndroidPushPlatform(store, Notice(), guard, runtime)
        assertEquals(listOf(config), runtime.initialized)
        platform.registered("observed-fid"); assertNotNull(platform.delivery.binding())
        platform.registered("rotated-fid"); assertNull(platform.delivery.binding())
        assertTrue(guard.blocked()); assertEquals(listOf(accepted()), store.pendingCleanup)
    }
    @Test fun differentProjectRequiresRestartWithoutRegisteringIt() = storage { _, store ->
        val runtime = Runtime(); val platform = AndroidPushPlatform(store, Notice(), MemoryPushDeliveryGuard(), runtime)
        runtime.onRegister = { platform.registered("observed-fid") }
        runBlocking { platform.register(config) }
        val originalLease = platform.lease
        val failure = runCatching { runBlocking { platform.register(config.copy(projectId = "other-project")) } }.exceptionOrNull()
        assertTrue(failure is PushRestartRequiredException)
        assertEquals(1, runtime.registrations); assertEquals(listOf(config), runtime.initialized)
        assertEquals(originalLease, platform.lease); assertEquals(config, store.cleanupConfiguration)
        assertEquals(0, runtime.removals)
        runBlocking { platform.unregister(originalLease) }
        assertEquals(1, runtime.removals); assertNull(store.cleanupConfiguration)
    }
    @Test fun coldOldProjectCleanupRequiresRestartWithoutJournalingUninitializedNewProject() = storage { isolated, store ->
        store.cleanupConfiguration = config
        val runtime = Runtime()
        val platform = AndroidPushPlatform(store, Notice(), MemoryPushDeliveryGuard(), runtime)
        val nextProject = config.copy(projectId = "other-project")
        assertTrue(runCatching { runBlocking { platform.register(nextProject) } }.exceptionOrNull() is PushRestartRequiredException)
        assertEquals(1, runtime.removals); assertEquals(0, runtime.registrations)
        assertNull(AndroidPushStore(isolated).cleanupConfiguration)
        val nextRuntime = Runtime()
        val restarted = AndroidPushPlatform(AndroidPushStore(isolated), Notice(), MemoryPushDeliveryGuard(), nextRuntime)
        nextRuntime.onRegister = { restarted.registered("observed-fid") }
        runBlocking { restarted.register(nextProject) }
        assertEquals(listOf(nextProject), nextRuntime.initialized)
    }
    @Test fun actualComponentFencePersistsAcrossGuardAndStoreReconstruction() = storage { isolated, store ->
        val component = ComponentName(context, SnapperMessagingService::class.java)
        val original = context.packageManager.getComponentEnabledSetting(component)
        try {
            store.accepted = accepted()
            val guard = AndroidPushDeliveryGuard(context)
            guard.block()
            val coldGuard = AndroidPushDeliveryGuard(context)
            assertTrue(coldGuard.blocked())
            val runtime = Runtime()
            val cold = AndroidPushPlatform(AndroidPushStore(isolated), Notice(), coldGuard, runtime)
            assertNull(cold.delivery.binding()); assertTrue(runtime.initialized.isEmpty())
            coldGuard.enable(); assertFalse(AndroidPushDeliveryGuard(context).blocked())
        } finally { context.packageManager.setComponentEnabledSetting(component, original, PackageManager.DONT_KILL_APP) }
    }
    @Test fun atomicStoreKeepsDeduplicationAndCleanupIndependentOfAcceptedBinding() = storage { isolated, store ->
        store.accepted = accepted(); store.pendingCleanup = listOf(accepted())
        store.remember("registration:alert")
        val cold = AndroidPushStore(isolated)
        assertTrue(cold.seen("registration:alert")); assertEquals(accepted(), cold.accepted)
        cold.accepted = null; cold.clearSeen()
        assertFalse(store.seen("registration:alert")); assertNull(store.accepted)
        assertEquals(listOf(accepted()), store.pendingCleanup)
    }
}
