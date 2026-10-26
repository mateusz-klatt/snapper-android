package ch.snapper.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val profile = """{"username":"viewer","role":"viewer","public_id":"00000000-0000-4000-8000-000000000011","effective_permissions":["manage:self_account"]}"""
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

    @Test fun configurationReadUsesFreshAuthorityAndUnavailableIsOrdinaryState() = runBlocking {
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
    private val registration = """{"public_id":"registration","user_public_id":"00000000-0000-4000-8000-000000000011","installation_id":"00000000-0000-4000-8000-000000000002","binding_id":"00000000-0000-4000-8000-000000000003","configuration_id":"00000000-0000-4000-8000-000000000001","protocol":"fcm_fid","platform":"android","package_name":"test.app","status":"active"}"""

    private suspend fun loginAgain(user: String = profile) {
        server.enqueue(response("""{"user":$user}""").addHeader("Set-Cookie", "access_token=second; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=second-csrf; Path=/;"))
        repository.login("viewer", "synthetic")
        server.takeRequest()
        bound = repository.bindPush(PushIdentity(server.url("/").toString(), 99, OrderScope.from(requireNotNull(repository.user.value)))).client
    }

    private fun assertRequests(vararg paths: String) {
        paths.forEach { assertEquals(it, server.takeRequest(1, TimeUnit.SECONDS)?.path) }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun configuredMetadataAndPreparedRegistrationRoundTripExactWireContract() = runBlocking {
        val input = command()
        server.enqueue(response(profile))
        server.enqueue(response("""{"availability":"configured","configuration":{"configuration_id":"${input.configurationId}","protocol":"fcm_fid","package_name":"test.app","project_id":"synthetic-project","application_id":"1:123:android:abc","api_key":"public-api-key","messaging_sender_id":"123"}}"""))
        val config = requireNotNull(bound.pushConfiguration().configuration)
        assertEquals("synthetic-project", config.projectId)
        assertEquals("1:123:android:abc", config.applicationId)
        assertEquals("123", config.messagingSenderId)
        assertEquals("public-api-key", config.apiKey)
        assertRequests("/api/auth/me", "/api/devices/android/config")
        server.enqueue(response(profile))
        server.enqueue(response("""{"revision":"${input.expectedRevision}","current_binding_id":null}"""))
        assertEquals(PushRegistrationState(input.expectedRevision), bound.pushRegistrationState(input.configurationId, input.installationId))
        assertRequests("/api/auth/me", "/api/devices/android/registration-state?configuration_id=${input.configurationId}&installation_id=${input.installationId}")
        server.enqueue(response(profile)); server.enqueue(response(registration))
        val accepted = bound.registerPush(input)
        assertEquals("registration", accepted.publicId)
        assertEquals(input.bindingId, accepted.bindingId)
        assertEquals(input.configurationId, accepted.configurationId)
        assertEquals(input.installationId, accepted.installationId)
        assertEquals(input.expectedUserPublicId, accepted.userPublicId)
        assertEquals("android", accepted.platform)
        assertEquals("active", accepted.status)
        assertEquals("fcm_fid", accepted.protocol)
        assertEquals("test.app", accepted.packageName)
        assertEquals("/api/auth/me", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("/api/devices/android", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Cookie").orEmpty().contains("access_token=synthetic"))
        assertEquals("synthetic-csrf", request.getHeader("X-CSRF-Token"))
        val envelope = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("register_android_device_command", envelope.getValue("type").jsonPrimitive.content)
        val payload = envelope.getValue("payload").jsonObject
        val expected = mapOf("protocol" to input.protocol, "configuration_id" to input.configurationId,
            "package_name" to input.packageName, "installation_id" to input.installationId,
            "binding_id" to input.bindingId, "expected_revision" to input.expectedRevision,
            "firebase_installation_id" to input.firebaseInstallationId,
            "expected_user_public_id" to input.expectedUserPublicId, "app_version" to input.appVersion)
        assertEquals(expected.keys, payload.keys)
        expected.forEach { (key, value) -> assertEquals(value, payload.getValue(key).jsonPrimitive.content) }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun ownedInventoryPreferencesAndRevocationUseDistinctLegacyCompatibleRoutes() = runBlocking {
        server.enqueue(response(profile)); server.enqueue(response("[$registration]"))
        assertEquals("registration", bound.pushInventory().single().publicId)
        assertRequests("/api/auth/me", "/api/devices/android")
        server.enqueue(response(profile))
        server.enqueue(response("""[{"public_id":"preference","device_public_id":"registration","alert_type":"signal","enabled":false,"min_priority":"critical","operator_public_id":"operator","wallet_public_id":"wallet"}]"""))
        assertEquals(PushPreference("preference", "registration", "signal", false, "critical", "operator", "wallet"), bound.pushPreferences("registration").single())
        assertRequests("/api/auth/me", "/api/devices/registration/prefs")
        server.enqueue(response(profile)); server.enqueue(MockResponse().setResponseCode(204))
        bound.revokePush("registration")
        assertEquals("/api/auth/me", server.takeRequest().path)
        val deletion = server.takeRequest()
        assertEquals("DELETE", deletion.method)
        assertEquals("/api/devices/registration", deletion.path)
        assertEquals("synthetic-csrf", deletion.getHeader("X-CSRF-Token"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun notificationDetailRequiresFreshPermissionAndParsesPrivateContentOnlyOnForegroundRead() = runBlocking {
        val permitted = profile.replace("[]", "[\"read:notifications\"]")
        loginAgain(permitted)
        server.enqueue(response(permitted))
        server.enqueue(response("""{"public_id":"alert","timestamp":"2026-10-16T12:00:00Z","alert_type":"signal","priority":"high","is_safety_critical":true,"title":"Synthetic title","body":"Synthetic details","thread_key":"thread","source_topic":"signals.test"}"""))
        val alert = bound.pushAlert("alert")
        assertEquals("Synthetic title", alert.title)
        assertEquals("Synthetic details", alert.body)
        assertEquals("alert", alert.publicId)
        assertTrue(alert.isSafetyCritical)
        assertEquals("thread", alert.threadKey)
        assertEquals("signals.test", alert.sourceTopic)
        assertRequests("/api/auth/me", "/api/alerts/alert")
        server.enqueue(response(profile))
        assertTrue(runCatching { bound.pushAlert("alert") }.isFailure)
        assertTrue(requireNotNull(repository.user.value).effectivePermissions.orEmpty().isEmpty())
        assertRequests("/api/auth/me")
    }

    @Test fun knownMutationRefusalsAreTypedAndNeverReplayed() = runBlocking {
        for ((status, code) in listOf(403 to "forbidden", 409 to "android_push_revision_changed", 503 to "android_push_unavailable")) {
            server.enqueue(response(profile))
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody("""{"detail":"$code"}"""))
            val failure = runCatching { withTimeout(5_000) { bound.registerPush(command()) } }.exceptionOrNull()
            assertTrue(failure is PushRejectedException)
            assertEquals(status, (failure as PushRejectedException).statusCode)
            assertEquals(code, failure.code)
            assertRequests("/api/auth/me", "/api/devices/android")
        }
    }

    @Test fun malformedAcceptedReceiptAndMalformedProxyErrorsRemainUncertainWithoutReplay() = runBlocking {
        for (reply in listOf(response("{}"), MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("not JSON"))) {
            server.enqueue(response(profile)); server.enqueue(reply)
            assertTrue(runCatching { withTimeout(5_000) { bound.registerPush(command()) } }.exceptionOrNull() is PushUncertainException)
            assertRequests("/api/auth/me", "/api/devices/android")
        }
    }

    @Test fun mutationUnauthorizedExpiresSessionWithoutRefreshOrReplay() = runBlocking {
        server.enqueue(response(profile)); server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { bound.registerPush(command()) }.exceptionOrNull()
        assertEquals(401, (failure as PushRejectedException).statusCode)
        assertNull(repository.user.value)
        assertRequests("/api/auth/me", "/api/devices/android")
    }

    @Test fun failedRevocationHasTypedStatusAndNoSuccessReceipt() = runBlocking {
        server.enqueue(response(profile)); server.enqueue(MockResponse().setResponseCode(403).setBody("{\"detail\":\"forbidden\"}"))
        val failure = runCatching { bound.revokePush("registration") }.exceptionOrNull()
        assertEquals(403, (failure as PushRejectedException).statusCode)
        assertRequests("/api/auth/me", "/api/devices/registration")
    }

    @Test fun readUnauthorizedRefreshesOnceWithRotatedCookiesAndPreservesBinding() = runBlocking {
        server.enqueue(response(profile)); server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":$profile}""").addHeader("Set-Cookie", "access_token=rotated; Path=/; HttpOnly"))
        server.enqueue(response("[]"))
        assertTrue(bound.pushInventory().isEmpty())
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/devices/android", server.takeRequest().path)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        val retried = server.takeRequest()
        assertEquals("/api/devices/android", retried.path)
        assertTrue(retried.getHeader("Cookie").orEmpty().contains("access_token=rotated"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun refreshedActorChangePreventsReadReplay() = runBlocking {
        server.enqueue(response(profile)); server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile.replace("[]", "[\"read:notifications\"]")}}"""))
        assertTrue(runCatching { bound.pushInventory() }.isFailure)
        assertRequests("/api/auth/me", "/api/devices/android", "/api/auth/refresh")
    }

    @Test fun sameUserReloginInvalidatesOldCookieGenerationAndWrongOriginNeverDispatches() = runBlocking {
        val previous = bound
        loginAgain()
        assertTrue(runCatching { previous.pushInventory() }.isFailure)
        assertTrue(runCatching { previous.registerPush(command()) }.isFailure)
        val wrongOrigin = repository.bindPush(PushIdentity("https://other.invalid/", 99, OrderScope.from(requireNotNull(repository.user.value)))).client
        assertTrue(runCatching { wrongOrigin.pushConfiguration() }.exceptionOrNull() is PushScopeChangedException)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun wrongExpectedUserNeverDispatchesRegistration() = runBlocking {
        server.enqueue(response(profile))
        assertTrue(runCatching { bound.registerPush(command().copy(expectedUserPublicId = "other")) }.isFailure)
        assertRequests("/api/auth/me")
    }

    @Test fun unboundCapabilityMethodsCannotBypassCapturedIdentity() = runBlocking {
        val actions: List<suspend () -> Unit> = listOf(
            { repository.pushConfiguration() }, { repository.pushRegistrationState("config", "installation") },
            { repository.registerPush(command()) }, { repository.pushInventory() },
            { repository.pushPreferences("registration") }, { repository.revokePush("registration") },
            { repository.pushAlert("alert") },
        )
        actions.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is PushScopeChangedException) }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun logoutInvalidatesCapturedBindingBeforeAnyFurtherPushReadOrWrite() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        assertEquals("/api/auth/logout", server.takeRequest().path)
        assertNull(repository.user.value)
        assertTrue(runCatching { bound.pushRegistrationState("config", "installation") }.isFailure)
        assertTrue(runCatching { bound.revokePush("registration") }.isFailure)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun preparationUnauthorizedRefreshesAuthorityBeforeSingleRegistrationDispatch() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":$profile}""").addHeader("Set-Cookie", "csrf_token=refreshed-csrf; Path=/"))
        server.enqueue(response(profile)); server.enqueue(response(registration))
        assertEquals("registration", bound.registerPush(command()).publicId)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals("/api/auth/me", server.takeRequest().path)
        val submitted = server.takeRequest()
        assertEquals("POST", submitted.method)
        assertEquals("refreshed-csrf", submitted.getHeader("X-CSRF-Token"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun revocationServiceUnavailableNeverReplaysBodylessDeleteOrExpiresCurrentProfile() = runBlocking {
        val current = repository.user.value
        server.enqueue(response(profile))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        val failure = runCatching { withTimeout(5_000) { bound.revokePush("registration") } }.exceptionOrNull()
        assertTrue("Expected uncertain revocation outcome", failure is PushUncertainException)
        assertEquals(current, repository.user.value)
        assertEquals("/api/auth/me", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        val deletion = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("DELETE", deletion.method)
        assertEquals("/api/devices/registration", deletion.path)
        assertEquals("synthetic-csrf", deletion.getHeader("X-CSRF-Token"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        assertEquals(3, server.requestCount)
    }

}
