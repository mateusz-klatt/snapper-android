package ch.snapper.android.data

import ch.snapper.android.features.live.LiveConnection
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LiveSessionClientTest {
    @Test fun actualUpgradeAndWireHandshakeKeepTicketOutOfUrlAndSubscribeBeforeSnapshot() = runBlocking {
        val server = MockWebServer()
        val messages = LinkedBlockingQueue<JsonObject>()
        val subscribed = CompletableDeferred<Set<String>>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient()
        val instant = Instant.now()
        fun control(type: String, sequence: Int, fields: JsonObjectBuilder.() -> Unit = {}): String = buildJsonObject {
            put("type", type); put("session_id", "server"); put("public_id", "event-$sequence")
            put("sequence_id", sequence); put("timestamp", instant.toString()); fields()
        }.toString()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(control("auth_required", 1) { put("timeout", 10) })
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonObject
                messages.add(message)
                if (message.getValue("type").jsonPrimitive.content == "authenticate") {
                    webSocket.send(control("auth_ok", 2) { put("exp", instant.plusSeconds(100).toString()) })
                    webSocket.send(control("auth_complete", 3) {
                        put("available_topics", JsonArray(listOf(JsonPrimitive("market."))))
                        put("user_role", "viewer"); put("ws_token_exp", instant.plusSeconds(100).toString())
                    })
                } else if (message.getValue("type").jsonPrimitive.content == "subscribe") {
                    webSocket.send(control("subscription_success", 4) {
                        put("action", "subscribe"); put("status", "subscribed")
                        put("topics", message.getValue("topics")); put("active_subscriptions", message.getValue("topics"))
                        put("denied_topics", JsonArray(emptyList()))
                    })
                }
            }
        }))
        server.start()
        val transport = OkHttpLiveSocketTransport(http, Request.Builder().url(server.url("/ws")).build())
        val engine = LiveConnection(owner, { LiveTicket("single-use-fixture") }, transport, { subscribed.complete(it) }, {})
        try {
            engine.start(setOf("market.venue.BTC.ticks"))
            assertEquals(setOf("market.venue.BTC.ticks"), withTimeout(5_000) { subscribed.await() })
            val auth = requireNotNull(messages.poll(2, TimeUnit.SECONDS))
            assertEquals("authenticate", auth.getValue("type").jsonPrimitive.content)
            assertEquals("single-use-fixture", auth.getValue("ws_token").jsonPrimitive.content)
            val subscribe = requireNotNull(messages.poll(2, TimeUnit.SECONDS))
            assertEquals("subscribe", subscribe.getValue("type").jsonPrimitive.content)
            val upgrade = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/ws", upgrade.path)
            assertNull(upgrade.requestUrl?.query)
            assertNull(upgrade.getHeader("Authorization"))
        } finally {
            engine.stop(); owner.cancel(); http.dispatcher.cancelAll()
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown(); server.shutdown()
        }
    }
}
