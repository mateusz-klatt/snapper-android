package ch.snapper.android.ui

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.DataStoreSettings
import ch.snapper.android.MainActivity
import ch.snapper.android.R
import ch.snapper.android.applicationLanguages
import ch.snapper.android.features.accountlanguage.AccountLanguageResolver
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ApplicationLanguageOverrideTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun platformAppLanguageIsSharedWithBackendResolverAndExplicitChoiceStillWins() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        if (Build.VERSION.SDK_INT < 33) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val manager = requireNotNull(context.getSystemService(LocaleManager::class.java))
        val originalLocales = manager.applicationLocales
        val originalConfigurationLocales = applicationLanguages(context)
        val settings = DataStoreSettings(context)
        val preferences = runBlocking { settings.preferences.first() }
        try {
            compose.onNodeWithTag("language.menu").performScrollTo().performClick()
            compose.onNodeWithTag("language.option.default").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { settings.preferences.first().language.isEmpty() } }
            manager.applicationLocales = LocaleList.forLanguageTags("fr")
            val french = "Nom d'utilisateur"
            val packagedFrench = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.FRENCH)
            }).getString(R.string.username)
            assertEquals("French must be packaged rather than falling back to English", french, packagedFrench)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(french).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("fr", AccountLanguageResolver.resolve("", applicationLanguages(context)))
            compose.onNodeWithText("Polski").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { settings.preferences.first().language == "pl" } }
            val polish = "Nazwa użytkownika"
            val packagedPolish = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("pl"))
            }).getString(R.string.username)
            assertEquals("Polish must be packaged rather than falling back to English", polish, packagedPolish)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(polish).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("pl", AccountLanguageResolver.resolve("pl", applicationLanguages(context)))
            assertEquals("fr", manager.applicationLocales[0].language)
            assertEquals(preferences.displayPreferences, runBlocking { settings.preferences.first().displayPreferences })
        } finally {
            runBlocking { settings.setLanguage(preferences.language) }
            manager.applicationLocales = originalLocales
            compose.waitUntil(10_000) {
                manager.applicationLocales == originalLocales && applicationLanguages(context) == originalConfigurationLocales
            }
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
        }
    }
}
