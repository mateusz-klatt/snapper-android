package ch.snapper.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.snapper.android.data.SessionRepository
import ch.snapper.android.ui.SnapperApp
import ch.snapper.android.ui.SnapperTheme
import ch.snapper.android.ui.LocalNumberLocale
import ch.snapper.android.ui.LocalFinancialColorConvention
import java.util.Locale
import java.time.ZoneId
import ch.snapper.android.ui.LocalDisplayTimeZone

class MainActivity : ComponentActivity() {
    private val displayTimeZone = mutableStateOf(ZoneId.systemDefault())
    private val model: SnapperViewModel by viewModels {
        viewModelFactory {
            initializer {
                SnapperViewModel(
                    settings = (application as SnapperApplication).settings,
                    sessionFactory = { origin -> SessionRepository.create(applicationContext, origin, BuildConfig.DEBUG) },
                    defaultOrigin = BuildConfig.DEFAULT_BACKEND_URL,
                    allowLocalHttp = BuildConfig.DEBUG,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LocalizedContent(state.language) {
                CompositionLocalProvider(
                    LocalNumberLocale provides state.displayPreferences.formattingLocale(),
                    LocalDisplayTimeZone provides displayTimeZone.value,
                    LocalFinancialColorConvention provides state.displayPreferences.convention(),
                ) {
                    SnapperTheme { SnapperApp(state, model.actions) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        displayTimeZone.value = ZoneId.systemDefault()
        model.onForeground()
    }

    override fun onStop() {
        model.onBackground()
        super.onStop()
    }
}

@Composable
private fun LocalizedContent(language: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
    val configuration = LocalConfiguration.current
    val localized = remember(context, configuration, language) {
        if (language.isEmpty()) context else {
            val copy = Configuration(configuration)
            copy.setLocale(Locale.forLanguageTag(language))
            context.createConfigurationContext(copy)
        }
    }
    val direction = if (localized.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalActivity provides activity,
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        LocalLayoutDirection provides direction,
        content = content,
    )
}
