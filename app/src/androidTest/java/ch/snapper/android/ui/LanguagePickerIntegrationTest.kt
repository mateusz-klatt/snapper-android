package ch.snapper.android.ui

import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.DataStoreSettings
import ch.snapper.android.MainActivity
import ch.snapper.android.R
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LanguagePickerIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var originalLanguage = ""

    @Before fun isolateLanguage() {
        originalLanguage = runBlocking { DataStoreSettings(context).preferences.first().language }
        runBlocking { DataStoreSettings(context).setLanguage("") }
        compose.activityRule.scenario.recreate()
    }

    @After fun restoreLanguage() {
        runBlocking { DataStoreSettings(context).setLanguage(originalLanguage) }
    }

    @Test fun frenchAndScriptLocaleSurviveRecreationAndSystemDefaultRemainsAvailable() {
        choose("fr")
        assertLocalizedUsername("fr")
        compose.activityRule.scenario.recreate()
        assertLocalizedUsername("fr")
        assertLanguage("fr")
        choose("zh-Hant")
        assertLocalizedUsername("zh-Hant")
        compose.activityRule.scenario.recreate()
        assertLocalizedUsername("zh-Hant")
        assertLanguage("zh-Hant")
        compose.onNodeWithTag("language.menu").performScrollTo().performClick()
        compose.onNodeWithTag("language.option.default").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { DataStoreSettings(context).preferences.first().language.isEmpty() } }
    }

    @Test fun arabicUsesReviewedResourcesAndMirrorsLanguageRowWithoutChangingNativeShortcuts() {
        choose("ar")
        assertLocalizedUsername("ar")
        val english = compose.onNodeWithText("English").fetchSemanticsNode().boundsInRoot
        val polish = compose.onNodeWithText("Polski").fetchSemanticsNode().boundsInRoot
        assertTrue("English precedes Polish from right to left", english.center.x > polish.center.x)
        compose.activityRule.scenario.recreate()
        assertLocalizedUsername("ar")
        assertLanguage("ar")
        compose.onNodeWithText("English").performScrollTo().performClick()
        assertLocalizedUsername("en")
    }

    private fun choose(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("language.menu").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("language.menu").performScrollTo().performClick()
        compose.onNodeWithTag("language.search").performTextInput(tag)
        compose.onNodeWithTag("language.option.$tag").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { DataStoreSettings(context).preferences.first().language == tag } }
    }

    private fun assertLocalizedUsername(tag: String) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val label = context.createConfigurationContext(config).getString(R.string.username)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login.username").assertExists()
    }

    private fun assertLanguage(tag: String) {
        assertEquals(tag, runBlocking { DataStoreSettings(context).preferences.first().language })
    }
}
