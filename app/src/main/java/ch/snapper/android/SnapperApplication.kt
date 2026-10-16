package ch.snapper.android

import android.app.Application

class SnapperApplication : Application() {
    val push by lazy { ch.snapper.android.features.push.AndroidPushPlatform(this) }
    override fun onCreate() { super.onCreate(); push }
    val settings: SettingsStore by lazy { DataStoreSettings(this) }
}
