package ch.snapper.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OriginPolicyTest {
    @Test fun releaseRequiresHttps() {
        assertEquals("https://example.com", OriginPolicy.normalize("https://example.com/", false))
        listOf("http://localhost", "http://127.0.0.1", "http://[::1]", "http://10.0.2.2", "http://example.com").forEach {
            assertThrows(IllegalArgumentException::class.java) { OriginPolicy.validate(it, false) }
        }
    }
    @Test fun debugAllowsOnlyExactLocalHosts() {
        listOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2").forEach { OriginPolicy.validate("http://$it:8000", true) }
        listOf("localhost.evil.test", "127.0.0.2", "192.168.1.1", "0.0.0.0").forEach {
            assertThrows(IllegalArgumentException::class.java) { OriginPolicy.validate("http://$it", true) }
        }
    }
    @Test fun rejectsCredentialsPathsQueriesAndFragments() {
        listOf("https://user:password@example.com", "https://example.com/api", "https://example.com?x=1", "https://example.com#fragment", "invalid").forEach {
            assertThrows(IllegalArgumentException::class.java) { OriginPolicy.validate(it, true) }
        }
    }
}
