package ch.snapper.android.features.push

import android.content.Intent
import ch.snapper.android.SnapperApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Generic hints only. This service has no reference to a repository, cookies, or authenticated APIs. */
class SnapperMessagingService : FirebaseMessagingService() {
    // The released SDK dispatches notification payloads before onMessageReceived. Reject them at
    // its public service entrypoint so neither background display nor image fetching can occur.
    override fun handleIntent(intent: Intent) {
        if (!PushPayloadPolicy.permits(intent.extras?.keySet().orEmpty())) return
        super.handleIntent(intent)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.notification != null) return
        (application as SnapperApplication).push.delivery.receive(message.data, message.from)
    }
    @Deprecated("Legacy token callbacks are unsupported in FID mode")
    override fun onNewToken(token: String) {
        // Never store, log, or register this legacy token. Fail closed on unexpected protocol.
        (application as SnapperApplication).push.delivery.unexpectedProtocol()
    }
    override fun onRegistered(installationId: String) { (application as SnapperApplication).push.registered(installationId) }
}

internal object PushPayloadPolicy {
    fun permits(keys: Set<String>): Boolean = keys.none {
        it.startsWith("gcm.n.") || it.startsWith("gcm.notification.")
    }
}
