package ch.snapper.android.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class SessionRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SessionRepository
    private val storage = MemoryCookieStorage()
    private val profile = """{"username":"reader","role":"viewer","public_id":"user-1","effective_permissions":[]}"""
    private fun response(payload: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"payload":$payload}""")
    private fun auth(): MockResponse = response("""{"user":$profile}""")

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        storage.write(listOf("refresh_token=saved; Path=/; Max-Age=3600"))
        repository = SessionRepository.create(server.url("/").toString(), true, storage)
    }
    @After fun cleanup() {
        repository.close()
        server.shutdown()
    }
    @Test fun freshInstallDoesNotRequestUnavailableBackend() = runBlocking {
        val fresh = SessionRepository.create(server.url("/").toString(), true)
        try {
            assertNull(fresh.restore())
            assertEquals(0, server.requestCount)
        } finally {
            fresh.close()
        }
    }
    @Test fun loginUsesEnvelopeAndCookieAuthentication() = runBlocking {
        server.enqueue(auth().addHeader("Set-Cookie", "access_token=secret; Path=/; HttpOnly").addHeader("Set-Cookie", "csrf_token=csrf; Path=/"))
        server.enqueue(response(profile))
        repository.login("reader", "password", true)
        repository.restore()
        val login = server.takeRequest()
        assertEquals("/api/auth/login", login.path)
        assertTrue(login.body.readUtf8().contains("\"type\":\"login_request\""))
        assertEquals("access_token=secret; csrf_token=csrf", server.takeRequest().getHeader("Cookie"))
    }
    @Test fun unrememberedSessionAndRotationsStayInMemory() = runBlocking {
        server.enqueue(auth().addHeader("Set-Cookie", "refresh_token=initial; Path=/; Max-Age=3600"))
        repository.login("reader", "password", rememberMe = false)
        server.takeRequest()
        assertTrue(storage.read().isEmpty())
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(auth().addHeader("Set-Cookie", "refresh_token=rotated; Path=/; Max-Age=3600"))
        server.enqueue(response(profile))
        assertEquals("reader", repository.restore()?.username)
        assertEquals("refresh_token=initial", server.takeRequest().getHeader("Cookie"))
        assertEquals("refresh_token=initial", server.takeRequest().getHeader("Cookie"))
        assertEquals("refresh_token=rotated", server.takeRequest().getHeader("Cookie"))
        assertTrue(storage.read().isEmpty())
    }
    @Test fun rememberedSessionPersistsServerPersistentCookies() = runBlocking {
        server.enqueue(auth().addHeader("Set-Cookie", "refresh_token=remembered; Path=/; Max-Age=3600"))
        repository.login("reader", "password", rememberMe = true)
        assertTrue(storage.read().single().startsWith("refresh_token=remembered;"))
    }
    @Test fun retries401OnlyOnceAndExpiresTerminalFailure() = runBlocking {
        server.enqueue(response(profile))
        repository.restore()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(auth())
        server.enqueue(MockResponse().setResponseCode(401))
        assertNull(repository.restore())
        assertNull(repository.user.value)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(4, server.requestCount)
    }
    @Test fun concurrentReadsShareOneNecessaryRotation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(auth())
        server.enqueue(response(profile))
        server.enqueue(response(profile))
        val first = async { repository.restore() }
        val second = async { repository.restore() }
        assertEquals("reader", first.await()?.username)
        assertEquals("reader", second.await()?.username)
        val paths = (1..4).map { server.takeRequest().path }
        assertEquals(1, paths.count { it == "/api/auth/refresh" })
    }
    @Test fun permissionFilteredSnapshotSkipsRestrictedResources() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(response("[]"))
        val result = repository.snapshot()
        assertTrue(result.orders.isEmpty())
        assertTrue(result.positions.isEmpty())
        assertTrue(result.alerts.isEmpty())
        assertEquals(2, server.requestCount)
    }
    @Test fun selectedWalletScopesPositionAndOrderRequests() = runBlocking {
        val walletId = "019bc601-0000-7000-8000-000000000001"
        val scoped = """{"username":"reader","role":"viewer","public_id":"user-1","active_wallet_public_id":"$walletId","effective_permissions":["read:positions","read:orders"]}"""
        server.enqueue(response(scoped))
        repeat(3) { server.enqueue(response("[]")) }
        assertEquals(walletId, repository.snapshot().user.activeWalletPublicId)
        server.takeRequest()
        server.takeRequest()
        assertEquals("/api/positions?wallet_public_id=$walletId", server.takeRequest().path)
        assertEquals("/api/orders?wallet_public_id=$walletId", server.takeRequest().path)
    }
    @Test fun allWalletScopeOmitsPositionAndOrderQuery() = runBlocking {
        val allWallets = """{"username":"reader","role":"viewer","public_id":"user-1","active_wallet_public_id":null,"effective_permissions":["read:positions","read:orders"]}"""
        server.enqueue(response(allWallets))
        repeat(3) { server.enqueue(response("[]")) }
        assertNull(repository.snapshot().user.activeWalletPublicId)
        server.takeRequest()
        server.takeRequest()
        assertEquals("/api/positions", server.takeRequest().path)
        assertEquals("/api/orders", server.takeRequest().path)
    }
    @Test fun networkResourceFailureDoesNotBecomeEmptyDashboard() = runBlocking {
        server.enqueue(response(profile))
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            repository.snapshot()
            throw AssertionError("Expected visible HTTP error")
        } catch (error: HttpException) {
            assertEquals(503, error.code())
        }
    }
    @Test fun walletRotationUsesMatchingCsrfAndLogoutRevokesCapturedCookies() = runBlocking {
        server.enqueue(auth().addHeader("Set-Cookie", "session=secret; Path=/api/auth; HttpOnly")
            .addHeader("Set-Cookie", "csrf_token=csrf; Path=/api/auth"))
        repository.login("reader", "password")
        server.takeRequest()
        server.enqueue(auth())
        repository.selectWallet(null)
        val rotate = server.takeRequest()
        assertEquals("csrf", rotate.getHeader("X-CSRF-Token"))
        assertTrue(rotate.body.readUtf8().contains("\"clear_active_wallet\":true"))
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        val logout = server.takeRequest()
        assertEquals("/api/auth/logout", logout.path)
        assertTrue(logout.getHeader("Cookie")!!.contains("session=secret"))
        assertEquals("csrf", logout.getHeader("X-CSRF-Token"))
        assertTrue(storage.read().isEmpty())
        assertNull(repository.user.value)
    }
    @Test fun closePreventsLateLoginFromRestoringSession() = runBlocking {
        server.enqueue(auth().setBodyDelay(1, TimeUnit.SECONDS).addHeader("Set-Cookie", "session=secret; Path=/"))
        val pending = async {
            runCatching { repository.login("reader", "password") }
        }
        while (server.requestCount == 0) kotlinx.coroutines.yield()
        repository.close()
        assertTrue(pending.await().isFailure)
        assertNull(repository.user.value)
        assertTrue(storage.read().isEmpty())
    }
    @Test fun redirectIsNotFollowedWithCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com"))
        val result = runCatching { repository.restore() }
        assertTrue(result.exceptionOrNull() is HttpException)
        assertEquals(1, server.requestCount)
        assertFalse(repository.user.value != null)
    }
}
