package ch.snapper.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuditorRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private val profile = """{"username":"auditor","role":"auditor","public_id":"audit-user","effective_permissions":["read:users","read:all_scopes"]}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    private suspend fun signIn(user: String = profile) {
        server.enqueue(response("""{"user":$user}""").addHeader("Set-Cookie", "access_token=synthetic; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=synthetic-csrf; Path=/"))
        repository.login("auditor", "synthetic")
        server.takeRequest()
    }
    @Before fun setup() = runBlocking {
        server = MockWebServer(); server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
        signIn()
    }
    @After fun cleanup() { repository.close(); server.shutdown() }

    @Test fun auditorAndLegacyManagerCanReadDirectoryWithFreshAuthority() = runBlocking {
        for (user in listOf(profile, profile.replace("read:users", "manage:users"))) {
            signIn(user)
            server.enqueue(response(user)); server.enqueue(response("[]"))
            assertTrue(repository.systemUsers().isEmpty())
            assertEquals("/api/auth/me", server.takeRequest().path)
            val directory = server.takeRequest()
            assertEquals("GET", directory.method)
            assertEquals("/api/auth/users", directory.path)
        }
    }

    @Test fun revokedDirectoryCapabilityStopsBeforeDirectoryRequest() = runBlocking {
        server.enqueue(response(profile.replace("read:users", "read:orders")))
        val failure = runCatching { repository.systemUsers() }.exceptionOrNull()
        assertTrue(failure is OrderScopeChangedException)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun globalScopeAloneDoesNotAuthorizeTheUserDirectory() = runBlocking {
        val scoped = profile.replace("\"read:users\",", "")
        signIn(scoped)
        server.enqueue(response(scoped))
        val failure = runCatching { repository.systemUsers() }.exceptionOrNull()
        assertTrue(failure is OrderRejectedException && failure.statusCode == 403)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(3, server.requestCount)
    }

    @Test fun auditorCannotWriteAccountLanguagePushOrNotificationDefaults() = runBlocking {
        val actor = OrderScope.from(requireNotNull(repository.user.value))
        val origin = server.url("/").toString()
        val language = runCatching { repository.updateLanguage(AccountLanguageIdentity(origin, 1, actor), "pl") }.exceptionOrNull()
        assertTrue(language is AccountLanguageException && language.kind == AccountLanguageFailureKind.PREPARATION)
        val defaults = runCatching {
            repository.updateNotificationDefaults(actor, UpdateNotificationDefaultPayload("margin_warning", false, "high"))
        }.exceptionOrNull()
        assertTrue(defaults is OrderRejectedException && defaults.statusCode == 403)
        val bound = repository.bindPush(PushIdentity(origin, 1, actor)).client
        val command = PushRegistrationCommand(protocol = "fcm_fid", configurationId = "configuration", packageName = "test.app",
            installationId = "installation", bindingId = "binding", expectedRevision = "a".repeat(64),
            firebaseInstallationId = "fid", expectedUserPublicId = "audit-user", appVersion = "1")
        val registration = runCatching { bound.registerPush(command) }.exceptionOrNull()
        val revocation = runCatching { bound.revokePush("registration") }.exceptionOrNull()
        assertTrue(registration is OrderRejectedException && registration.statusCode == 403)
        assertTrue(revocation is OrderRejectedException && revocation.statusCode == 403)
        assertEquals(1, server.requestCount)
    }
}
