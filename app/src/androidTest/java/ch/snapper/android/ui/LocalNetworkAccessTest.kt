package ch.snapper.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LocalNetworkAccessTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

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
        clickSystemPermissionButton(setOf("Don't allow", "Don’t allow"))
        compose.onNodeWithTag("settings.localNetwork").assertExists()
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
        compose.onNodeWithTag("settings.localNetwork").performScrollTo().performClick()
        clickSystemPermissionButton(setOf("Allow"))
        compose.waitUntil(10_000) {
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        }
        compose.onNodeWithTag("settings.localNetwork").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("login.username").assertExists()
        compose.onNodeWithTag("settings.localNetwork").assertDoesNotExist()
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
    }

    private fun clickSystemPermissionButton(labels: Set<String>) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && root.packageName?.toString()?.contains("permissioncontroller") == true) {
                val button = findButton(root, labels)
                if (button != null) {
                    assertTrue("System permission action must be clickable", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    automation.waitForIdle(200, 5_000)
                    return
                }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("System permission dialog did not expose $labels")
    }

    private fun findButton(node: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        if (node.text?.toString() in labels && node.isClickable && node.isEnabled) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findButton(child, labels)?.let { return it }
        }
        return null
    }
}
