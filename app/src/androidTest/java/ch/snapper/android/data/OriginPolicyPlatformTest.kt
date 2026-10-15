package ch.snapper.android.data

import android.security.NetworkSecurityPolicy
import ch.snapper.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OriginPolicyPlatformTest {
    @Test fun packagedPolicyAllowsOnlyDebugDevelopmentHosts() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted)
        listOf("localhost", "127.0.0.1", "::1", "10.0.2.2").forEach { host ->
            assertEquals(host, BuildConfig.DEBUG, policy.isCleartextTrafficPermitted(host))
            val authority = if (host.contains(':')) "[$host]" else host
            val origin = "http://$authority:8000"
            if (BuildConfig.DEBUG) {
                assertEquals(host, OriginPolicy.validate(origin, true).host)
            } else {
                assertThrows(IllegalArgumentException::class.java) { OriginPolicy.validate(origin, true) }
            }
            assertThrows(IllegalArgumentException::class.java) { OriginPolicy.validate(origin, false) }
        }
    }

    @Test fun packagedPolicyRejectsOtherHostsAndSubdomains() {
        val policy = NetworkSecurityPolicy.getInstance()
        listOf("localhost.evil.test", "sub.localhost", "127.0.0.2", "192.168.1.1", "0.0.0.0", "example.com").forEach { host ->
            assertFalse(host, policy.isCleartextTrafficPermitted(host))
            val origin = "http://$host"
            assertThrows(IllegalArgumentException::class.java) { OriginPolicy.validate(origin, true) }
        }
    }
}
