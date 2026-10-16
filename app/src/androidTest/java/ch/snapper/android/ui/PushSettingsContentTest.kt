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
