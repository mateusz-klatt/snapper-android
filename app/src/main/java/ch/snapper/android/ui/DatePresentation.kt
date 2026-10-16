package ch.snapper.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import ch.snapper.android.data.DisplayDates
import java.time.ZoneId

/** Provide a fixed zone for previews/tests, or refresh the device zone on foreground/configuration changes. */
internal val LocalDisplayTimeZone = staticCompositionLocalOf { ZoneId.systemDefault() }

/** Does not modify the Android resources locale or translation language. */
@Composable
internal fun String?.displayTimestamp(): String = DisplayDates.format(this, LocalNumberLocale.current, LocalDisplayTimeZone.current)
