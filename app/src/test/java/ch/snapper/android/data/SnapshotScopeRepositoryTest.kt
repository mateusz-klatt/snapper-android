package ch.snapper.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SnapshotScopeRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private fun profile(operator: String = "o1", delegate: String = "d1", username: String = "fixture") =
        """{"username":"$username","public_id":"u1","role":"viewer","active_wallet_public_id":"w1","primary_operator_public_id":"$operator","delegate_public_id":"$delegate","effective_permissions":["read:positions","read:orders","read:notifications"]}"""
    private fun response(payload: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    @Before fun setup() {
        server = MockWebServer(); server.start()
        repository = SessionRepository.create(server.url("/").toString(), true) { true }
    }
    @After fun cleanup() { repository.close(); server.shutdown() }
    private suspend fun login() {
        server.enqueue(response("""{"user":${profile()}}""").addHeader("Set-Cookie", "access_token=fixture; Path=/; HttpOnly"))
        repository.login("fixture", "password"); server.takeRequest()
    }
    private fun readsBeforeOrdersRefresh(next: String) {
        server.enqueue(response(profile()))
        server.enqueue(response("""[{"public_id":"w1","label":"Wallet","is_paper":true}]"""))
        server.enqueue(response("""[{"public_id":"old-position","instrument":"BTC","exchange":"venue","quantity":1,"realized_pnl":0,"wallet_public_id":"w1"}]"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(response("""{"user":$next}"""))
    }

    @Test fun changedOperatorAfterPositionsPreventsOrdersReplayAndMixedAggregate() = runBlocking {
        login(); readsBeforeOrdersRefresh(profile(operator = "o2"))
        assertTrue(runCatching { repository.snapshot() }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals("o2", repository.user.value?.primaryOperatorPublicId)
        assertReadBoundary()
    }

    @Test fun changedDelegateAfterPositionsPreventsOrdersReplayAndAlertsRead() = runBlocking {
        login(); readsBeforeOrdersRefresh(profile(delegate = "d2"))
        assertTrue(runCatching { repository.snapshot() }.exceptionOrNull() is OrderScopeChangedException)
        assertEquals("d2", repository.user.value?.delegatePublicId)
        assertReadBoundary()
    }

    @Test fun changedUsernameWithSamePublicIdAlsoRejectsReadReplay() = runBlocking {
        login(); readsBeforeOrdersRefresh(profile(username = "other"))
        assertTrue(runCatching { repository.snapshot() }.exceptionOrNull() is OrderScopeChangedException)
        assertReadBoundary()
    }

    @Test fun unchangedIdentityAllowsSingleReadReplayAndCompleteAggregate() = runBlocking {
        login(); readsBeforeOrdersRefresh(profile())
        server.enqueue(response("[]")); server.enqueue(response("[]"))
        val snapshot = repository.snapshot()
        assertEquals("old-position", snapshot.positions.single().publicId)
        assertEquals("d1", snapshot.user.delegatePublicId)
        repeat(5) { server.takeRequest() }
        assertEquals("/api/orders?wallet_public_id=w1", server.takeRequest().path)
        assertEquals("/api/alerts/history?limit=100", server.takeRequest().path)
        assertEquals(8, server.requestCount)
    }

    private fun assertReadBoundary() {
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/wallets", server.takeRequest().path)
        assertEquals("/api/positions?wallet_public_id=w1", server.takeRequest().path)
        assertEquals("/api/orders?wallet_public_id=w1", server.takeRequest().path)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals(6, server.requestCount)
    }
}
