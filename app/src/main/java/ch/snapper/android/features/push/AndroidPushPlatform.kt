package ch.snapper.android.features.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.util.AtomicFile
import java.io.File
import androidx.core.net.toUri
import android.content.pm.PackageManager
import android.os.Build
import ch.snapper.android.MainActivity
import ch.snapper.android.R
import ch.snapper.android.data.PushConfiguration
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Contains only public options and opaque delivery bindings; never opens the authenticated cookie store. */
internal class AndroidPushStore(context: Context) : PushStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "push-public-binding.json"))
    private fun read(): Map<String, String> = if (!file.baseFile.exists()) emptyMap() else
        Json.decodeFromString(file.openRead().bufferedReader().use { it.readText() })
    @Synchronized private fun get(key: String): String? = read()[key]
    @Synchronized private fun put(key: String, value: String?) {
        val updated = read().toMutableMap().apply { if (value == null) remove(key) else put(key, value) }
        val stream = file.startWrite()
        try {
            stream.write(Json.encodeToString(updated).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
            check(read() == updated)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
    override val installationId: String get() = get("installation") ?: UUID.randomUUID().toString().also { put("installation", it) }
    override var pendingRegistrations: List<PendingPushBinding>
        get() = get("pending-registrations")?.let { Json.decodeFromString<List<PendingPushBinding>>(it) }.orEmpty()
        set(value) { put("pending-registrations", Json.encodeToString(value)) }
    override var pendingCleanup: List<AcceptedPushBinding>
        get() = get("pending-backend-cleanup")?.let { Json.decodeFromString<List<AcceptedPushBinding>>(it) }.orEmpty()
        set(value) { put("pending-backend-cleanup", Json.encodeToString(value)) }
    override var accepted: AcceptedPushBinding?
        get() = get("accepted")?.let { Json.decodeFromString(it) }
        set(value) { put("accepted", value?.let { Json.encodeToString(it) }) }
    override fun seen(key: String): Boolean = seenKeys().contains(key)
    private fun seenKeys(): List<String> = get("seen")?.let { Json.decodeFromString<List<String>>(it) }.orEmpty()
    override fun remember(key: String) { put("seen", Json.encodeToString(seenKeys().takeLast(255) + key)) }
    var cleanupConfiguration: PushConfiguration?
        get() = get("cleanup")?.let { Json.decodeFromString(it) }
        set(value) { put("cleanup", value?.let { Json.encodeToString(it) }) }
    override fun clearSeen() { put("seen", null) }

}

internal class AndroidPushNotice(private val context: Context) : PushNotice {
    private val manager = context.getSystemService(NotificationManager::class.java)
    override fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    override fun show(hint: PushHint) {
        if (!allowed()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.push_title), NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify("snapper-update", 1, notification(hint))
    }
    internal fun notification(hint: PushHint): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "ch.snapper.android.OPEN_UPDATE"
            data = "snapper-push:${hint.registrationId}:${hint.alertId}".toUri()
            putExtra(EXTRA_HINT, Json.encodeToString(hint))
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.push_title))
            .setContentText(context.getString(R.string.push_generic))
            .setVisibility(Notification.VISIBILITY_PUBLIC).setAutoCancel(true).setContentIntent(pending).build()
    }
    override fun cancelAll() { manager.cancel("snapper-update", 1) }
    companion object {
        const val EXTRA_HINT = "ch.snapper.android.push.HINT"
        private const val CHANNEL = "snapper-generic-updates"
    }
}

internal class AndroidPushDeliveryGuard(context: Context) : PushDeliveryGuard {
    private val manager = context.packageManager
    private val component = ComponentName(context, SnapperMessagingService::class.java)
    override fun blocked(): Boolean = manager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    private fun set(state: Int) {
        manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        check(manager.getComponentEnabledSetting(component) == state)
    }
    override fun block() { set(PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
    override fun enable() { set(PackageManager.COMPONENT_ENABLED_STATE_ENABLED) }
}

internal interface FirebaseRuntime {
    fun initialize(configuration: PushConfiguration)
    suspend fun register()
    suspend fun unregister()
    suspend fun installationId(): String
}
internal class ReleasedFirebaseRuntime(private val context: Context) : FirebaseRuntime {
    override fun initialize(configuration: PushConfiguration) {
        val options = FirebaseOptions.Builder().setApplicationId(configuration.applicationId).setProjectId(configuration.projectId)
            .setApiKey(configuration.apiKey).setGcmSenderId(configuration.messagingSenderId).build()
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null && existing.options != options) throw PushRestartRequiredException()
        if (existing == null) FirebaseApp.initializeApp(context, options)
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
    }
    override suspend fun register() { FirebaseMessaging.getInstance().register().awaitCompletion() }
    override suspend fun unregister() { FirebaseMessaging.getInstance().unregister().awaitCompletion() }
    override suspend fun installationId(): String = FirebaseInstallations.getInstance().id.awaitCompletion()
}

/** A single instance lives on Application. Default Firebase options are never hot-replaced. */
class AndroidPushPlatform internal constructor(
    private val store: AndroidPushStore, private val notice: PushNotice,
    private val guard: PushDeliveryGuard, private val runtime: FirebaseRuntime,
) : PushPlatform {
    constructor(context: Context) : this(AndroidPushStore(context), AndroidPushNotice(context),
        AndroidPushDeliveryGuard(context), ReleasedFirebaseRuntime(context))
    override val delivery = PushDelivery(store, notice, guard)
    override val installationId: String get() = store.installationId
    private var configured: PushConfiguration? = null
    private val sdkOperations = Mutex()
    @Volatile private var sdkLease = 0L
    override val lease: Long get() = sdkLease
    @Volatile private var registrationCallback: CompletableDeferred<String>? = null
    init { delivery.binding()?.let { runCatching { initialize(it.configuration) }.onFailure { delivery.invalidate() } } }
    override fun permissionGranted(): Boolean = notice.allowed()

    private fun requireCompatible(config: PushConfiguration) {
        if (configured != null && configured != config) throw PushRestartRequiredException()
    }

    @Synchronized private fun initialize(config: PushConfiguration) {
        if (configured != null) {
            if (configured != config) throw PushRestartRequiredException()
            return
        }
        runtime.initialize(config)
        configured = config
    }

    override suspend fun register(configuration: PushConfiguration): String = sdkOperations.withLock {
        check(permissionGranted())
        requireCompatible(configuration)
        sdkLease++
        store.cleanupConfiguration?.let { old ->
            initialize(old)
            runtime.unregister()
            store.cleanupConfiguration = null
        }
        requireCompatible(configuration)
        store.cleanupConfiguration = configuration
        initialize(configuration)
        store.accepted = null
        guard.enable()
        val callback = CompletableDeferred<String>()
        registrationCallback = callback
        try {
            runtime.register()
            val registered = withTimeout(30_000) { callback.await() }
            check(registered == runtime.installationId())
            registered
        } finally { registrationCallback = null }
    }
    override suspend fun unregister(expectedLease: Long) = sdkOperations.withLock {
        if (expectedLease != sdkLease) return@withLock
        val old = configured ?: store.cleanupConfiguration
        if (old != null) {
            store.cleanupConfiguration = old
            initialize(old)
            runtime.unregister()
            store.cleanupConfiguration = null
        }
    }
    fun registered(installationId: String) {
        registrationCallback?.complete(installationId)
        // Callback alone cannot authorize any backend registration.
        val saved = delivery.binding() ?: return
        if (saved.firebaseInstallationId != installationId) delivery.invalidate()
    }
    companion object {
        fun hint(intent: Intent?): PushHint? = intent?.getStringExtra(AndroidPushNotice.EXTRA_HINT)?.takeIf { it.length <= 2048 }
            ?.let { runCatching { Json.decodeFromString<PushHint>(it) }.getOrNull() }
    }
}

/** Waiting remains attached to the actual SDK task, preventing overlapping cleanup after cancellation. */
private suspend fun <T> Task<T>.awaitCompletion(): T = suspendCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Push operation failed"))
    }
}
