package ch.snapper.android

import android.app.Application

class SnapperApplication : Application() {
    val settings: SettingsStore by lazy { DataStoreSettings(this) }
}
