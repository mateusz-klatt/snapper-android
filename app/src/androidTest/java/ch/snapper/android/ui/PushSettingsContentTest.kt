package ch.snapper.android.ui

import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertNull
import com.google.firebase.FirebaseApp
import androidx.test.platform.app.InstrumentationRegistry

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.snapper.android.features.push.*
import ch.snapper.android.data.AlertDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PushSettingsContentTest {
    @get:Rule val compose = createComposeRule()
    @Test fun permissionResultControlsExplicitRegistrationWithoutLaunchingSystemUi() {
        val registry = PushPermissionRegistry()
        var enables = 0
        val config = ch.snapper.android.data.PushConfiguration("config", "fcm_fid", "test.app", "project", "app", "public", "sender")
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides registry) {
                PushSettingsContent(PushState(PushStatus.READY, config), PushActions(enable = { enables++ }))
            }
        }
        compose.onNodeWithTag("push.enable").assertIsEnabled().performClick()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            compose.runOnIdle { assertEquals(0, enables); registry.complete(false) }
            compose.runOnIdle { assertEquals(0, enables) }
            compose.onNodeWithTag("push.enable").performClick()
            compose.runOnIdle { registry.complete(true) }
        }
        compose.runOnIdle { assertEquals(1, enables) }
    }

    @Test fun lifecycleStatusesExposeRecoveryWithoutImplicitRegistration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = ch.snapper.android.data.PushConfiguration("config", "fcm_fid", "test.app", "project", "app", "public", "sender")
        val state = androidx.compose.runtime.mutableStateOf(PushState())
        var refreshes = 0; var disables = 0; var enables = 0
        compose.setContent { PushSettingsContent(state.value, PushActions(refresh = { refreshes++ }, enable = { enables++ }, disable = { disables++ })) }
        val cases = listOf(
            PushStatus.OFF to ch.snapper.android.R.string.push_off,
            PushStatus.LOADING to ch.snapper.android.R.string.push_busy,
            PushStatus.REGISTERING to ch.snapper.android.R.string.push_busy,
            PushStatus.UNAVAILABLE to ch.snapper.android.R.string.push_unavailable,
            PushStatus.PERMISSION_REQUIRED to ch.snapper.android.R.string.push_permission,
            PushStatus.READY to ch.snapper.android.R.string.push_ready,
            PushStatus.ACCEPTED to ch.snapper.android.R.string.push_accepted,
            PushStatus.REJECTED to ch.snapper.android.R.string.push_rejected,
            PushStatus.RESTART_REQUIRED to ch.snapper.android.R.string.push_restart,
            PushStatus.CLEANUP_PENDING to ch.snapper.android.R.string.push_cleanup,
        )
        for ((status, message) in cases) {
            compose.runOnIdle { state.value = PushState(status, config) }
            compose.onNodeWithTag("push.status").assertTextEquals(context.getString(message))
            if (status in listOf(PushStatus.READY, PushStatus.PERMISSION_REQUIRED, PushStatus.REJECTED)) compose.onNodeWithTag("push.enable").assertIsEnabled()
            else compose.onNodeWithTag("push.enable").assertIsNotEnabled()
            if (status in listOf(PushStatus.LOADING, PushStatus.REGISTERING)) compose.onNodeWithText(context.getString(ch.snapper.android.R.string.refresh)).assertIsNotEnabled()
            else compose.onNodeWithText(context.getString(ch.snapper.android.R.string.refresh)).assertIsEnabled()
            compose.onNodeWithTag("push.disable").assertIsEnabled()
        }
        compose.onNodeWithTag("push.disable").performClick()
        compose.onNodeWithText(context.getString(ch.snapper.android.R.string.refresh)).performClick()
        compose.runOnIdle { assertEquals(1, disables); assertEquals(1, refreshes); assertEquals(0, enables) }
    }

    @Test fun mergedManifestHasOnlyOwnedDeliveryServiceAndNoAutomaticFirebaseInitializer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = context.packageManager.queryIntentServices(
            Intent("com.google.firebase.MESSAGING_EVENT").setPackage(context.packageName),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).map { it.serviceInfo.name }
        assertEquals(listOf(SnapperMessagingService::class.java.name), services)
        assertNull(context.packageManager.resolveContentProvider(context.packageName + ".firebaseinitprovider", 0))
    }
    @Test fun sdkNotificationPayloadStopsAtServiceEntryBeforeFirebaseOrAttachedContextIsNeeded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = FirebaseApp.getApps(context).map { it.name }
        // An unattached Service has no usable application/context. Delegating these intents to the
        // SDK would enter message handling and require that context before displaying/downloading.
        val service = SnapperMessagingService()
        listOf("gcm.n.", "gcm.notification.").forEach { prefix ->
            service.handleIntent(Intent("com.google.android.c2dm.intent.RECEIVE").apply {
                putExtra(prefix + "e", "1")
                putExtra(prefix + "title", "Must never display")
                putExtra(prefix + "image", "https://invalid.example.invalid/private-image.png")
                putExtra("google.message_id", "synthetic-notification-" + prefix)
            })
        }
        assertEquals(before, FirebaseApp.getApps(context).map { it.name })
    }
    @Test fun unknownRegistrationOffersOnlyExplicitReadReconciliationAndLocalDisable() {
        var checks = 0; var enables = 0; var disables = 0
        compose.setContent { PushSettingsContent(PushState(PushStatus.UNCERTAIN), PushActions(enable = { enables++ }, disable = { disables++ }, reconcile = { checks++ })) }
        compose.onNodeWithTag("push.enable").assertIsNotEnabled()
        compose.onNodeWithTag("push.reconcile").performClick()
        compose.onNodeWithTag("push.disable").performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(1, disables); assertEquals(0, enables) }
    }
    @Test fun genericInvitationIsSeparateFromAuthorizedForegroundDetailAndDoesNotExposeContentByDefault() {
        var dismissed = 0
        compose.setContent { PushAlertContent(PushState(alert = AlertDto("alert", "2026-01-01T00:00:00Z", "drift", "low", title = "Authorized result", body = "Fresh detail")), PushActions(dismissAlert = { dismissed++ })) }
        compose.onNodeWithText("Authorized result").assertExists()
        compose.onNodeWithText("Fresh detail").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}

private class PushPermissionRegistry : androidx.activity.result.ActivityResultRegistry(), androidx.activity.result.ActivityResultRegistryOwner {
    override val activityResultRegistry: androidx.activity.result.ActivityResultRegistry get() = this
    private var pendingCode: Int? = null
    override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
        options: androidx.core.app.ActivityOptionsCompat?) {
        check(contract is androidx.activity.result.contract.ActivityResultContracts.RequestPermission)
        check(input == android.Manifest.permission.POST_NOTIFICATIONS)
        check(pendingCode == null)
        pendingCode = requestCode
    }
    fun complete(granted: Boolean) {
        val code = checkNotNull(pendingCode); pendingCode = null
        dispatchResult(code, granted)
    }
}
