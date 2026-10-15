package ch.snapper.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.snapper.android.R

@Composable
internal fun LocalNetworkAccess() {
    if (Build.VERSION.SDK_INT >= 37) LocalNetworkPermissionAction()
}

@RequiresApi(37)
@Composable
private fun LocalNetworkPermissionAction() {
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
    val preferences = remember(activity) {
        activity.getSharedPreferences("local_network_permission", Context.MODE_PRIVATE)
    }
    var granted by remember(activity) {
        mutableStateOf(activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
    }
    var openSettings by remember(activity) { mutableStateOf(false) }
    val refresh = {
        granted = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        openSettings = !granted && preferences.getBoolean("requested", false) &&
            !activity.shouldShowRequestPermissionRationale(permission)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }
    DisposableEffect(activity, lifecycle) {
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!granted) {
        Text(stringResource(R.string.local_network_explanation), style = MaterialTheme.typography.bodySmall)
        TextButton(
            modifier = Modifier.testTag("settings.localNetwork"),
            onClick = {
                if (openSettings) {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null)))
                } else {
                    preferences.edit { putBoolean("requested", true) }
                    launcher.launch(permission)
                }
            },
        ) {
            Text(stringResource(if (openSettings) R.string.local_network_settings else R.string.local_network_allow))
        }
    }
}
