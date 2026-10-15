package ch.snapper.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OriginPolicyTest {
    @Test fun releaseRequiresHttpsEvenWhenPlatformPermitsCleartext() {
        assertEquals("https://example.com", OriginPolicy.normalize(" https://example.com/ ", false))
        listOf("http://localhost", "http://127.0.0.1", "http://[::1]", "http://10.0.2.2", "http://example.com").forEach {
            assertThrows(IllegalArgumentException::class.java) { OriginPolicy.validate(it, false) { true } }
        }
    }

    @Test fun debugUsesPlatformDecisionForCanonicalHost() {
        val checkedHosts = mutableListOf<String>()
        val policy: (String) -> Boolean = { host ->
            checkedHosts.add(host)
            host == "localhost"
        }
        assertEquals("localhost", OriginPolicy.validate("http://LOCALHOST:8000", true, policy).host)
        assertThrows(IllegalArgumentException::class.java) {
            OriginPolicy.validate("http://localhost.evil.test", true, policy)
        }
        assertEquals(listOf("localhost", "localhost.evil.test"), checkedHosts)
    }

    @Test fun debugRejectsLocalHttpWhenPlatformDeniesIt() {
        assertThrows(IllegalArgumentException::class.java) {
            OriginPolicy.validate("http://localhost", true) { false }
        }
    }

    @Test fun httpsDoesNotConsultCleartextPolicy() {
        assertEquals("https://example.com/", OriginPolicy.validate("https://example.com", true) {
            throw AssertionError("HTTPS must not consult the cleartext policy")
        }.toString())
    }

    @Test fun rejectsCredentialsPathsQueriesFragmentsAndMalformedUrls() {
        listOf(
            "https://user:password@example.com",
            "https://example.com/api",
            "https://example.com?x=1",
            "https://example.com#fragment",
            "invalid",
            "http://[invalid]",
            "ftp://example.com",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { OriginPolicy.validate(it, true) { true } }
        }
    }
}
