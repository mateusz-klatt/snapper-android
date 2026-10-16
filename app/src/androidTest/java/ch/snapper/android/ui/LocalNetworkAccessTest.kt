package ch.snapper.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.MainActivity
import ch.snapper.android.DataStoreSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LocalNetworkAccessTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalLanguage = ""
    private var originalRequested = false

    @Before fun isolatePreferences() {
        originalLanguage = runBlocking { DataStoreSettings(context).preferences.first().language }
        val preferences = context.getSharedPreferences("local_network_permission", Context.MODE_PRIVATE)
        originalRequested = preferences.getBoolean("requested", false)
        assertTrue(preferences.edit().putBoolean("requested", false).commit())
        runBlocking { DataStoreSettings(context).setLanguage("") }
        compose.activityRule.scenario.recreate()
    }

    @After fun restorePreferences() {
        runBlocking { DataStoreSettings(context).setLanguage(originalLanguage) }
        assertTrue(context.getSharedPreferences("local_network_permission", Context.MODE_PRIVATE)
            .edit().putBoolean("requested", originalRequested).commit())
    }

    @Test
    fun denialKeepsOptionalActionAndGrantSurvivesActivityRecreation() {
        if (Build.VERSION.SDK_INT < 37) {
            assumeTrue("Local network runtime permission requires Android 17", false)
            return
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings.localNetwork").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Polski").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings.localNetwork").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings.localNetwork").performScrollTo().performClick()
        clickSystemPermissionButton("permission_deny_button")
        compose.onNodeWithTag("settings.localNetwork").assertExists()
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
        compose.onNodeWithTag("settings.localNetwork").performScrollTo().performClick()
        clickSystemPermissionButton("permission_allow_button")
        compose.waitUntil(10_000) {
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        }
        compose.onNodeWithTag("settings.localNetwork").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("login.username").assertExists()
        compose.onNodeWithTag("settings.localNetwork").assertDoesNotExist()
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
    }

    private fun clickSystemPermissionButton(resourceName: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = originalFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            var clicked = false
            compose.waitUntil(10_000) {
                val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                val button = roots.firstNotNullOfOrNull { root ->
                    val owner = root.packageName?.toString()
                    if (owner in setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")) {
                        findButton(root, requireNotNull(owner), resourceName)
                    } else null
                }
                if (button != null) {
                    assertTrue("System permission action must be clickable", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    clicked = true
                }
                clicked
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun findButton(node: AccessibilityNodeInfo, controllerPackage: String, resourceName: String): AccessibilityNodeInfo? {
        if (node.packageName?.toString() == controllerPackage &&
            node.viewIdResourceName?.endsWith(":id/$resourceName") == true && node.isClickable && node.isEnabled) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findButton(child, controllerPackage, resourceName)?.let { return it }
        }
        return null
    }
}
