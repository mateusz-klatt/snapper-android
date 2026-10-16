package ch.snapper.android.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface LiveTransportClient {
    fun liveTransport(): LiveSocketTransport
}

/** Upgraded WebSockets outlive OkHttp dispatcher calls, so session shutdown owns them explicitly. */
internal class SessionLiveSockets {
    private val sockets = mutableSetOf<LiveSocket>()

    fun transport(delegate: LiveSocketTransport, current: () -> Boolean): LiveSocketTransport = LiveSocketTransport { observer ->
        if (!current()) throw SessionExpiredException()
        val reference = AtomicReference<LiveSocket?>()
        val ended = AtomicBoolean(false)
        val socket = delegate.connect(object : LiveSocketObserver {
            override fun frame(bytes: ByteArray) {
                if (current() && !ended.get()) observer.frame(bytes) else reference.get()?.cancel()
            }
            override fun closed() {
                ended.set(true)
                reference.get()?.let(::remove)
                if (current()) observer.closed()
            }
        })
        reference.set(socket)
        synchronized(sockets) { if (!ended.get()) sockets.add(socket) }
        if (!current()) { remove(socket); socket.cancel(); throw SessionExpiredException() }
        object : LiveSocket {
            override fun send(text: String): Boolean = current() && !ended.get() && socket.send(text)
            override fun cancel() { ended.set(true); remove(socket); socket.cancel() }
        }
    }

    private fun remove(socket: LiveSocket) { synchronized(sockets) { sockets.remove(socket) } }

    fun cancelAll() {
        val obsolete = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        obsolete.forEach(LiveSocket::cancel)
    }
}
