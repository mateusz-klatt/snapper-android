package ch.snapper.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class PushRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private lateinit var bound: PushClient
    private val profile = """{"username":"viewer","role":"viewer","public_id":"00000000-0000-4000-8000-000000000011","effective_permissions":[]}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    @Before fun setup() = runBlocking {
        server = MockWebServer(); server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
        server.enqueue(response("""{"user":$profile}""").addHeader("Set-Cookie", "access_token=synthetic; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=synthetic-csrf; Path=/"))
        repository.login("viewer", "synthetic"); server.takeRequest()
        bound = repository.bindPush(PushIdentity(server.url("/").toString(), 99, OrderScope.from(repository.user.value!!))).client
    }
    @After fun cleanup() { repository.close(); server.shutdown() }
    private fun command() = PushRegistrationCommand(protocol = "fcm_fid", configurationId = "00000000-0000-4000-8000-000000000001", packageName = "test.app", installationId = "00000000-0000-4000-8000-000000000002",
        bindingId = "00000000-0000-4000-8000-000000000003", expectedRevision = "a".repeat(64), firebaseInstallationId = "fid", expectedUserPublicId = "00000000-0000-4000-8000-000000000011", appVersion = "1")

    @Test fun permissionlessConfigurationReadUsesFreshAuthorityAndUnavailableIsOrdinaryState() = runBlocking {
        server.enqueue(response(profile)); server.enqueue(response("""{"availability":"unavailable","configuration":null}"""))
        assertEquals("unavailable", bound.pushConfiguration().availability)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/devices/android/config", server.takeRequest().path)
    }
    @Test fun registrationPostsExactPreparedRevisionWithCsrfAndDoesNotReplayFiveHundred() = runBlocking {
        server.enqueue(response(profile)); server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { bound.registerPush(command()) }.exceptionOrNull() is PushUncertainException)
        assertEquals("GET", server.takeRequest().method)
        val posted = server.takeRequest(); assertEquals("POST", posted.method)
        assertEquals("synthetic-csrf", posted.getHeader("X-CSRF-Token"))
        val body = posted.body.readUtf8(); assertTrue(body.contains("\"protocol\":\"fcm_fid\"")); assertTrue(body.contains("register_android_device_command")); assertTrue(body.contains("\"expected_revision\":\"" + "a".repeat(64) + "\""))
        System.getenv("SNAPPER_PUSH_WIRE_RECEIPT")?.let { java.io.File(it).writeText(body) }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
    @Test fun changedActorDuringFreshMeBlocksPostAndCommitsRevokedAuthority() = runBlocking {
        server.enqueue(response(profile.replace("\"00000000-0000-4000-8000-000000000011\"", "\"00000000-0000-4000-8000-000000000012\"")))
        assertTrue(runCatching { bound.registerPush(command()) }.isFailure)
        assertEquals("00000000-0000-4000-8000-000000000012", repository.user.value!!.publicId); server.takeRequest()
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
    @Test fun unauthorizedAlertDetailNeverReachesDetailEndpoint() = runBlocking {
        assertTrue(runCatching { bound.pushAlert("alert") }.isFailure)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
    @Test fun retiredRepositoryBindingCannotReadOrRegisterAfterClose() = runBlocking {
        repository.close()
        assertTrue(runCatching { bound.pushConfiguration() }.isFailure)
        assertTrue(runCatching { bound.registerPush(command()) }.isFailure)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
}
