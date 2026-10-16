package ch.snapper.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import ch.snapper.android.AppActions
import ch.snapper.android.AppUiState
import ch.snapper.android.UiError
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.WalletDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SnapperAppTest {
    @get:Rule
    val compose = createComposeRule()

    private fun actions(
        login: (String, String) -> Unit = { _, _ -> },
        refresh: () -> Unit = {},
        selectWallet: (String?) -> Unit = {},
        clearError: () -> Unit = {},
    ) = AppActions(login, refresh, selectWallet, {}, {}, {}, clearError, { _, _, _ -> })

    @Test
    fun loginMasksPasswordAndSubmitsOnlyAfterBothFieldsAreEntered() {
        var credentials: Pair<String, String>? = null
        compose.setContent {
            SnapperTheme {
                SnapperApp(AppUiState(isRestoring = false), actions(login = { username, password ->
                    credentials = username to password
                }))
            }
        }
        compose.onNodeWithTag("login.signIn").assertIsNotEnabled()
        compose.onNodeWithTag("login.username").performTextInput(" trader ")
        compose.onNodeWithTag("login.signIn").assertIsNotEnabled()
        compose.onNodeWithTag("login.password").performTextInput("test-secret")
        compose.onNodeWithTag("login.password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("login.signIn").assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals("trader" to "test-secret", credentials) }
    }

    @Test
    fun loginErrorDismissalClearsErrorWithoutSubmittingCredentials() {
        val state = mutableStateOf(AppUiState(isRestoring = false, error = UiError.INVALID_CREDENTIALS))
        var loginCalls = 0
        compose.setContent {
            SnapperTheme {
                SnapperApp(state.value, actions(login = { _, _ -> loginCalls++ },
                    clearError = { state.value = state.value.copy(error = null) }))
            }
        }
        compose.onNodeWithTag("error.action").performScrollTo().performClick()
        compose.onNodeWithTag("error.action").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, loginCalls) }
    }

    @Test
    fun dashboardErrorRetriesTheRequest() {
        var refreshCalls = 0
        val user = UserDto(username = "reader", role = "viewer")
        compose.setContent {
            SnapperTheme {
                SnapperApp(AppUiState(user = user, isRestoring = false, error = UiError.NETWORK),
                    actions(refresh = { refreshCalls++ }))
            }
        }
        compose.onNodeWithTag("error.action").performClick()
        compose.runOnIdle { assertEquals(1, refreshCalls) }
    }

    @Test
    fun walletSelectionPassesThePublicIdAndAllWalletsClearsIt() {
        val user = UserDto(username = "reader", role = "viewer")
        val wallet = WalletDto(publicId = "wallet-one", label = "Practice", isPaper = true)
        val snapshot = DashboardSnapshot(user, listOf(wallet), emptyList(), emptyList(), emptyList())
        val selected = mutableListOf<String?>()
        compose.setContent {
            SnapperTheme {
                SnapperApp(AppUiState(user = user, snapshot = snapshot, isRestoring = false),
                    actions(selectWallet = { selected.add(it) }))
            }
        }
        compose.onNodeWithTag("home.wallet").performClick()
        compose.onNodeWithTag("wallet.wallet-one").performClick()
        compose.onNodeWithTag("home.wallet").performClick()
        compose.onNodeWithTag("wallet.all").performClick()
        compose.runOnIdle { assertEquals(listOf("wallet-one", null), selected) }
    }

    @Test
    fun permissionRevocationRemovesProtectedNavigation() {
        val user = UserDto(username = "reader", role = "viewer", effectivePermissions = listOf("read:positions"))
        val state = mutableStateOf(AppUiState(user = user, isRestoring = false))
        compose.setContent { SnapperTheme { SnapperApp(state.value, actions()) } }
        compose.onNodeWithTag("nav.positions").assertExists().performClick()
        compose.onNodeWithTag("nav.orders").assertDoesNotExist()
        compose.onNodeWithTag("nav.alerts").assertDoesNotExist()
        compose.onNodeWithTag("nav.market").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(user = user.copy(effectivePermissions = emptyList())) }
        compose.onNodeWithTag("nav.positions").assertDoesNotExist()
        compose.onNodeWithTag("home.wallet").assertExists()
    }

    @Test
    fun privacyPolicyOpensFromLoginAndAuthenticatedSettings() {
        val state = mutableStateOf(AppUiState(isRestoring = false))
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened.add(uri) }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                SnapperTheme { SnapperApp(state.value, actions()) }
            }
        }
        compose.onNodeWithTag("privacy.policy").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(user = UserDto("reader", "viewer")) }
        compose.onNodeWithTag("nav.settings").performClick()
        compose.onNodeWithTag("privacy.policy").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(List(2) { "https://snapper.ch/android-privacy-policy.html" }, opened)
        }
    }
}
