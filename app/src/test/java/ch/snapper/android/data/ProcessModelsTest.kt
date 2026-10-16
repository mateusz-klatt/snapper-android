package ch.snapper.android.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessModelsTest {
    @Test(expected = SerializationException::class)
    fun missingOwnershipDoesNotDefaultToLocalControl() {
        Json.decodeFromString<ConfiguredProcessDto>("""{"name":"feed","enabled":true,"running":false,"role":"feed","kind":"instance"}""")
    }

    @Test fun remoteRestartPayloadPreservesNonceAndAction() {
        assertEquals("""{"action":"restart","restart_nonce":"stable_nonce_1"}""",
            Json.encodeToString(ProcessDesiredStatePayload("restart", "stable_nonce_1")))
    }
}
