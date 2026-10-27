package ch.snapper.android.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private val profile = """{"username":"viewer","role":"viewer","public_id":"u1","effective_permissions":["manage:self_account"]}"""
    private val preference = """{"public_id":"p1","user_public_id":"u1","alert_type":"margin_warning","enabled":false,"min_priority":"high"}"""
    private val payload = UpdateNotificationDefaultPayload("margin_warning", false, "high")
    private fun scope() = OrderScope.from(requireNotNull(repository.user.value))
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$body}""")

    @Before fun setup() = runBlocking {
        server = MockWebServer()
        server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
        server.enqueue(response("""{"user":$profile}""").addHeader("Set-Cookie", "access_token=access; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=csrf; Path=/"))
        repository.login("viewer", "password")
        server.takeRequest()
        Unit
    }

    @After fun cleanup() {
        repository.close()
        server.shutdown()
    }

    @Test fun viewerWithoutWalletOrNotificationPermissionCanReadOwnDefaults() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[$preference]"))
        assertEquals("u1", repository.notificationDefaults().single().userPublicId)
        assertEquals("/api/auth/me", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/alert_defaults", request.path)
        assertTrue(request.getHeader("Cookie").orEmpty().contains("access_token=access"))
    }

    @Test fun patchUsesFreshProfileEnvelopeAndCsrfWithoutWallet() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response(preference))
        assertEquals("p1", repository.updateNotificationDefaults(scope(), payload).publicId)
        assertEquals("/api/auth/me", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/alert_defaults", request.path)
        assertEquals("csrf", request.getHeader("X-CSRF-Token"))
        val envelope = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("update_user_alert_default_command", envelope.getValue("type").jsonPrimitive.content)
        assertTrue(envelope.containsKey("sequence_id"))
        val body = envelope.getValue("payload").jsonObject
        assertEquals(setOf("alert_type", "enabled", "min_priority"), body.keys)
        assertEquals("false", body.getValue("enabled").jsonPrimitive.content)
        assertEquals("high", body.getValue("min_priority").jsonPrimitive.content)
    }

    @Test fun freshIdentityChangeRefusesBeforePatch() = runBlocking {
        server.enqueue(response(profile.replace("u1", "u2")))
        assertTrue(runCatching { repository.updateNotificationDefaults(scope(), payload) }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun invalidPriorityIsRefusedWithoutNetwork() = runBlocking {
        assertTrue(runCatching { repository.updateNotificationDefaults(scope(), payload.copy(minPriority = "critical")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, server.requestCount)
    }

    @Test fun unauthorizedPatchExpiresSessionWithoutRefreshOrReplay() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { repository.updateNotificationDefaults(scope(), payload) }.exceptionOrNull()
        assertTrue(failure is NotificationDefaultRejectedException && failure.statusCode == 401)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals(3, server.requestCount)
        assertNull(repository.user.value)
    }

    @Test fun retryAfterZero503IsUnknownAndNeverReplayed() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(response(preference))
        assertTrue(runCatching { repository.updateNotificationDefaults(scope(), payload) }.exceptionOrNull() is NotificationDefaultOutcomeUnknownException)
        server.takeRequest()
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals(3, server.requestCount)
    }

    @Test fun validationRejectionPreservesStatusAndSession() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"invalid preference"}"""))
        val failure = runCatching { repository.updateNotificationDefaults(scope(), payload) }.exceptionOrNull()
        assertTrue(failure is NotificationDefaultRejectedException && failure.statusCode == 422)
        assertEquals("u1", repository.user.value?.publicId)
        assertEquals(3, server.requestCount)
    }

    @Test fun malformedAcceptedResponseIsUnknown() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("{}"))
        assertTrue(runCatching { repository.updateNotificationDefaults(scope(), payload) }.exceptionOrNull() is NotificationDefaultOutcomeUnknownException)
        assertEquals(3, server.requestCount)
    }

    @Test fun logoutDiscardsDelayedPatchAcceptance() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response(preference).setBodyDelay(2, TimeUnit.SECONDS))
        val expected = scope()
        val pending = async { runCatching { repository.updateNotificationDefaults(expected, payload) } }
        withContext(Dispatchers.IO) { repeat(2) { assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null) } }
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        assertFalse(pending.await().isSuccess)
        assertNull(repository.user.value)
    }
}
