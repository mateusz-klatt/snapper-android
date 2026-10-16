package ch.snapper.android.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** The owner mints a fresh, single-use ticket after validating its captured session scope. */
fun interface LiveSessionClient {
    suspend fun liveTicket(): LiveTicket
}

/** Credentials deliberately have no generated component/copy/toString methods. */
class LiveTicket(private val token: String) {
    init { require(token.isNotBlank()) }
    internal fun credential(): String = token
    override fun toString(): String = "LiveTicket(redacted)"
}

interface LiveSocket {
    fun send(text: String): Boolean
    fun cancel()
}

interface LiveSocketObserver {
    fun frame(bytes: ByteArray)
    fun closed()
}

fun interface LiveSocketTransport {
    fun connect(observer: LiveSocketObserver): LiveSocket
}

/** Uses the session's cookie jar and cancellation ownership. The fixed request must contain no ticket. */
class OkHttpLiveSocketTransport(private val client: OkHttpClient, private val request: Request) : LiveSocketTransport {
    override fun connect(observer: LiveSocketObserver): LiveSocket {
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = observer.frame(text.toByteArray(Charsets.UTF_8))
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = observer.frame(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.cancel(); observer.closed() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = observer.closed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = observer.closed()
        })
        return object : LiveSocket {
            override fun send(text: String): Boolean = socket.send(text)
            override fun cancel() = socket.cancel()
        }
    }
}
