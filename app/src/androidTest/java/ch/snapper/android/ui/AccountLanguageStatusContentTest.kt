package ch.snapper.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.data.AccountLanguageFailureKind
import ch.snapper.android.data.UserDto
import ch.snapper.android.features.accountlanguage.AccountLanguageState
import ch.snapper.android.features.accountlanguage.AccountLanguageStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountLanguageStatusContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun authenticatedNavigationCannotTriggerCatalogReadsUntilLanguageAttemptSettles() {
        var catalogReads = 0
        val user = UserDto("viewer", "viewer", "viewer-id", effectivePermissions = listOf("read:market_data"))
        val state = mutableStateOf(AppUiState(user = user, isRestoring = false, accountLanguageReady = false))
        val actions = AppActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, loadCatalog = { catalogReads++ })
        compose.setContent { SnapperTheme { SnapperApp(state.value, actions) } }
        compose.onNodeWithTag("accountLanguage.preparing").assertExists()
        compose.onNodeWithTag("nav.market").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, catalogReads)
            state.value = state.value.copy(accountLanguageReady = true)
        }
        compose.onNodeWithTag("nav.market").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, catalogReads)
            state.value = state.value.copy(accountLanguageReady = false)
        }
        compose.onNodeWithTag("nav.market").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, catalogReads) }
    }

    @Test fun failedSyncPreservesLocalChoiceAndRetriesOnlyOnExplicitTap() {
        var retries = 0
        val user = UserDto("viewer", "viewer", "viewer-id")
        val language = AccountLanguageState("fr", null, AccountLanguageStatus.FAILED, AccountLanguageFailureKind.UNKNOWN)
        val state = mutableStateOf(AppUiState(user = user, isRestoring = false, language = "fr", accountLanguage = language))
        val actions = AppActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, retryAccountLanguage = {
            retries++
            state.value = state.value.copy(accountLanguage = language.copy(status = AccountLanguageStatus.SYNCING, failure = null))
        })
        compose.setContent { SnapperTheme { SnapperApp(state.value, actions) } }
        compose.onNodeWithTag("nav.settings").performScrollTo().performClick()
        compose.onNodeWithTag("accountLanguage.retry").performScrollTo()
        compose.runOnIdle { assertEquals(0, retries); assertEquals("fr", state.value.language) }
        compose.onNodeWithTag("accountLanguage.retry").performClick()
        compose.onNodeWithTag("accountLanguage.retry").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals("fr", state.value.language)
            state.value = state.value.copy(accountLanguage = AccountLanguageState("fr", "fr", AccountLanguageStatus.ACCEPTED))
        }
        compose.onNodeWithTag("accountLanguage.status").assertExists()
        compose.onNodeWithTag("accountLanguage.retry").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
