package ch.snapper.android.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AccountLanguageRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private fun profile(language: String = "en", actor: String = "desk", delegate: String = "delegate") =
        """{"username":"viewer","role":"viewer","public_id":"u1","primary_operator_public_id":"$actor","delegate_public_id":"$delegate","effective_permissions":[],"default_language":"$language"}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    private fun identity() = AccountLanguageIdentity(server.url("/").toString(), 97, OrderScope.from(requireNotNull(repository.user.value)))
    private suspend fun login() {
        server.enqueue(response("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=access; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=csrf; Path=/"))
        repository.login("viewer", "synthetic")
        server.takeRequest()
    }
    @Before fun setup() = runBlocking {
        server = MockWebServer(); server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
        login()
    }
    @After fun cleanup() { repository.close(); server.shutdown() }
    private suspend fun failure(action: suspend () -> Unit): AccountLanguageFailureKind {
        val error = runCatching { withTimeout(5_000) { action() } }.exceptionOrNull()
        assertTrue("Expected typed language failure, got ${error?.javaClass?.simpleName}", error is AccountLanguageException)
        return (error as AccountLanguageException).kind
    }

    @Test fun permissionlessViewerUsesFreshProfileExactEnvelopeCsrfAndDirectUserResponse() = runBlocking {
        val captured = identity()
        val binding = repository.bindLanguage(captured)
        server.enqueue(response(profile())); server.enqueue(response(profile("pl"))); server.enqueue(response(profile("pl")))
        assertEquals(AccountLanguageReceipt(captured, "pl"), binding.client.updateLanguage(captured, "pl"))
        assertEquals("/api/auth/me", server.takeRequest().path)
        val command = server.takeRequest()
        assertEquals("POST", command.method); assertEquals("/api/auth/me/update", command.path)
        assertEquals("csrf", command.getHeader("X-CSRF-Token"))
        assertTrue(command.getHeader("Cookie").orEmpty().contains("access_token=access"))
        val body = Json.parseToJsonElement(command.body.readUtf8()).jsonObject
        assertEquals("update_auth_me_request", body.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("default_language"), body.getValue("payload").jsonObject.keys)
        assertEquals("pl", body.getValue("payload").jsonObject.getValue("default_language").jsonPrimitive.content)
        assertEquals("pl", repository.user.value?.defaultLanguage)
        assertEquals(captured.actor, OrderScope.from(requireNotNull(repository.user.value)))
    }

    @Test fun rawWriteMayOmitDerivedPrimaryButOnlyCanonicalReadConfirmsAndCommits() = runBlocking {
        val captured = identity()
        val raw = profile("pl").replace("\"primary_operator_public_id\":\"desk\"", "\"primary_operator_public_id\":null")
        server.enqueue(response(profile())); server.enqueue(response(raw)); server.enqueue(response(profile("pl")))
        assertEquals(AccountLanguageReceipt(captured, "pl"), repository.updateLanguage(captured, "pl"))
        assertEquals(listOf("GET", "POST", "GET"), List(3) { server.takeRequest().method })
        assertEquals("desk", repository.user.value?.primaryOperatorPublicId)
        assertEquals("pl", repository.user.value?.defaultLanguage)
    }

    @Test fun nonnullWrongRawPrimaryIsUnknownWithoutCanonicalRead() = runBlocking {
        val captured = identity()
        server.enqueue(response(profile())); server.enqueue(response(profile("pl", actor = "wrong")))
        assertEquals(AccountLanguageFailureKind.UNKNOWN, failure { repository.updateLanguage(captured, "pl") })
        assertEquals(3, server.requestCount)
        assertEquals("desk", repository.user.value?.primaryOperatorPublicId)
    }

    @Test fun changedCanonicalActorOrLanguageAfterAcceptedPostRemainsUnknown() = runBlocking {
        for (canonical in listOf(profile("pl", actor = "other"), profile("fr"))) {
            login()
            val captured = identity()
            server.enqueue(response(profile())); server.enqueue(response(profile("pl"))); server.enqueue(response(canonical))
            assertEquals(AccountLanguageFailureKind.UNKNOWN, failure { repository.updateLanguage(captured, "pl") })
            assertEquals(Json.parseToJsonElement(canonical).jsonObject.getValue("default_language").jsonPrimitive.content, repository.user.value?.defaultLanguage)
            assertEquals(listOf("GET", "POST", "GET"), List(3) { server.takeRequest().method })
        }
    }

    @Test fun canonicalScopeChangeCommitsFreshAuthorityAndCancelsExistingSocketBeforeRefusingReceipt() = runBlocking {
        val closed = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) { closed.complete(Unit) }
        }))
        val socket = repository.liveTransport().connect(object : LiveSocketObserver {
            override fun frame(bytes: ByteArray) = Unit
            override fun closed() = Unit
        })
        try {
            assertEquals("/api/ws", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }?.path)
            val captured = identity()
            server.enqueue(response(profile())); server.enqueue(response(profile("pl")))
            server.enqueue(response(profile("pl", actor = "canonical-new-desk")))
            assertEquals(AccountLanguageFailureKind.UNKNOWN, failure { repository.updateLanguage(captured, "pl") })
            assertEquals("canonical-new-desk", repository.user.value?.primaryOperatorPublicId)
            assertEquals("pl", repository.user.value?.defaultLanguage)
            assertFalse(socket.send("obsolete-scope-probe"))
            withTimeout(5_000) { closed.await() }
        } finally { socket.cancel() }
    }

    @Test fun confirmation401RefreshesOnlyReadsAndNeverReplaysAcceptedPost() = runBlocking {
        val captured = identity()
        server.enqueue(response(profile())); server.enqueue(response(profile("pl")))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile("pl")}}""")); server.enqueue(response(profile("pl")))
        assertEquals("pl", repository.updateLanguage(captured, "pl").language)
        assertEquals(listOf("/api/auth/me", "/api/auth/me/update", "/api/auth/me", "/api/auth/refresh", "/api/auth/me"),
            List(5) { server.takeRequest().path })
    }

    @Test fun confirmationRefreshActorChangeBlocksReplayAndReportsUnknown() = runBlocking {
        val captured = identity()
        server.enqueue(response(profile())); server.enqueue(response(profile("pl")))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile("pl", delegate = "other")}}"""))
        assertEquals(AccountLanguageFailureKind.UNKNOWN, failure { repository.updateLanguage(captured, "pl") })
        assertEquals(5, server.requestCount)
        assertEquals(listOf("/api/auth/me", "/api/auth/me/update", "/api/auth/me", "/api/auth/refresh"),
            List(4) { server.takeRequest().path })
    }

    @Test fun failedPostSuccessConfirmationIsUnknownRatherThanRejected() = runBlocking {
        val captured = identity()
        server.enqueue(response(profile())); server.enqueue(response(profile("pl")))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(AccountLanguageFailureKind.UNKNOWN, failure { repository.updateLanguage(captured, "pl") })
        assertEquals(4, server.requestCount)
        assertEquals("en", repository.user.value?.defaultLanguage)
    }

    @Test fun invalidLanguageOriginOrBoundIdentityDispatchesNothing() = runBlocking {
        val captured = identity()
        val bound = repository.bindLanguage(captured)
        assertEquals(AccountLanguageFailureKind.PREPARATION, failure { bound.client.updateLanguage(captured, "XX") })
        assertEquals(AccountLanguageFailureKind.PREPARATION, failure { repository.updateLanguage(captured.copy(backend = "https://other.example"), "pl") })
        assertEquals(AccountLanguageFailureKind.PREPARATION, failure { bound.client.updateLanguage(captured.copy(sessionGeneration = 98), "pl") })
        assertEquals(1, server.requestCount)
    }

    @Test fun oldBoundClientCannotCrossAReplacementLoginWithIdenticalProfile() = runBlocking {
        val captured = identity()
        val bound = repository.bindLanguage(captured)
        login()
        assertEquals(AccountLanguageFailureKind.PREPARATION, failure { bound.client.updateLanguage(captured, "pl") })
        assertEquals(2, server.requestCount)
        server.enqueue(response(profile())); server.enqueue(response(profile("pl"))); server.enqueue(response(profile("pl")))
        assertEquals("pl", repository.bindLanguage(captured).client.updateLanguage(captured, "pl").language)
    }

    @Test fun freshActorOrDelegateChangeRefusesBeforeMutation() = runBlocking {
        for ((actor, delegate) in listOf("other-desk" to "delegate", "desk" to "other-delegate")) {
            login()
            val captured = identity()
            val before = server.requestCount
            server.enqueue(response(profile(actor = actor, delegate = delegate)))
            assertEquals(AccountLanguageFailureKind.PREPARATION, failure { repository.updateLanguage(captured, "pl") })
            assertEquals(before + 1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun freshUsernameWalletOrPermissionChangeAlsoRefusesBeforeMutation() = runBlocking {
        val profiles = listOf(
            profile().replace("\"username\":\"viewer\"", "\"username\":\"other\""),
            profile().replace("\"public_id\":\"u1\"", "\"public_id\":\"u2\""),
            profile().replace("\"effective_permissions\":[]", "\"effective_permissions\":[\"read:market_data\"]"),
            profile().dropLast(1) + ",\"active_wallet_public_id\":\"other-wallet\"}",
        )
        for (fresh in profiles) {
            login()
            val captured = identity()
            val before = server.requestCount
            server.enqueue(response(fresh))
            assertEquals(AccountLanguageFailureKind.PREPARATION, failure { repository.updateLanguage(captured, "pl") })
            assertEquals(before + 1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun refreshCannotReplayReadOrDispatchMutationAfterActorChange() = runBlocking {
        val captured = identity()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile(actor = "other")}}"""))
        assertEquals(AccountLanguageFailureKind.PREPARATION, failure { repository.updateLanguage(captured, "pl") })
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals(3, server.requestCount)
    }

    @Test fun refreshWithSameActorStillAllowsExactlyOnePreferenceMutation() = runBlocking {
        val captured = identity()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":${profile()}}"""))
        server.enqueue(response(profile())); server.enqueue(response(profile("fr"))); server.enqueue(response(profile("fr")))
        assertEquals("fr", repository.updateLanguage(captured, "fr").language)
        val paths = List(5) { server.takeRequest().path }
        assertEquals(listOf("/api/auth/me", "/api/auth/refresh", "/api/auth/me", "/api/auth/me/update", "/api/auth/me"), paths)
    }

    @Test fun mutation401ExpiresSessionWithoutRefreshOrReplay() = runBlocking {
        val captured = identity()
        server.enqueue(response(profile())); server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(AccountLanguageFailureKind.REJECTED, failure { repository.updateLanguage(captured, "pl") })
        assertEquals(3, server.requestCount); assertNull(repository.user.value)
    }

    @Test fun retryAfterZero503IsUnknownAndNeverAutomaticallyReplayed() = runBlocking {
        val captured = identity()
        server.enqueue(response(profile())); server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        assertEquals(AccountLanguageFailureKind.UNKNOWN, failure { repository.updateLanguage(captured, "pl") })
        assertEquals(3, server.requestCount); assertEquals("en", repository.user.value?.defaultLanguage)
    }

    @Test fun disconnectedMutationIsUnknownWithoutTransportRecovery() = runBlocking {
        val captured = identity()
        server.enqueue(response(profile())); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(AccountLanguageFailureKind.UNKNOWN, failure { repository.updateLanguage(captured, "pl") })
        assertEquals(3, server.requestCount)
    }

    @Test fun successfulButUnprovenResponseNeverCommitsPreference() = runBlocking {
        for (body in listOf(profile("fr"), profile("pl", delegate = "other"), "{}")) {
            val captured = identity()
            server.enqueue(response(profile())); server.enqueue(response(body))
            assertEquals(AccountLanguageFailureKind.UNKNOWN, failure { repository.updateLanguage(captured, "pl") })
            assertEquals("en", repository.user.value?.defaultLanguage)
        }
    }

    @Test fun explicitValidationRejectionIsKnownAndSanitized() = runBlocking {
        server.enqueue(response(profile())); server.enqueue(MockResponse().setResponseCode(422).setBody("private detail"))
        assertEquals(AccountLanguageFailureKind.REJECTED, failure { repository.updateLanguage(identity(), "pl") })
        assertEquals(3, server.requestCount)
    }

    @Test fun logoutDuringDelayedMutationCannotPublishStaleAcceptance() = runBlocking {
        val captured = identity()
        server.enqueue(response(profile()))
        server.enqueue(response(profile("pl")).setBodyDelay(2, TimeUnit.SECONDS))
        val saving = async(Dispatchers.IO) { runCatching { repository.updateLanguage(captured, "pl") } }
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS); server.takeRequest(5, TimeUnit.SECONDS) }
        server.enqueue(MockResponse().setResponseCode(200))
        repository.logout()
        assertTrue(saving.await().isFailure)
        assertNull(repository.user.value)
    }
}
