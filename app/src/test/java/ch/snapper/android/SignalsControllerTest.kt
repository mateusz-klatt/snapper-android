package ch.snapper.android

import ch.snapper.android.data.SignalDto
import ch.snapper.android.data.SignalsClient
import ch.snapper.android.data.UserDto
import ch.snapper.android.features.signals.SignalsController
import java.math.BigDecimal
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalsControllerTest {
    private val user = UserDto("viewer", "viewer", "u1", activeWalletPublicId = "w1", effectivePermissions = listOf("read:signals"))
    private class Client : SignalsClient {
        var calls = 0
        var wallet: String? = null
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun signals(walletPublicId: String?): List<SignalDto> {
            calls++; wallet = walletPublicId
            gate?.await()
            if (fail) throw IOException("offline")
            return listOf(SignalDto("s1", "BTC", "venue", "buy", BigDecimal.ONE, "fixture", "2026-10-16T00:00:00Z", strategyName = "example", walletPublicId = walletPublicId))
        }
    }

    @Test fun scopePermissionAndDuplicateLoadAreEnforced() = runTest {
        val client = Client().apply { gate = CompletableDeferred() }
        val controller = SignalsController(this, client, user)
        controller.load(); controller.load(); runCurrent()
        assertEquals(1, client.calls); assertEquals("w1", client.wallet)
        client.gate?.complete(Unit); runCurrent()
        assertEquals(1, controller.state.value.visibleRows.size)
        controller.reset(user.copy(effectivePermissions = emptyList()))
        controller.load(); runCurrent()
        assertEquals(1, client.calls); assertTrue(controller.state.value.visibleRows.isEmpty())
    }

    @Test fun resetCancelsOldFetchAndChangesExportIdentity() = runTest {
        val client = Client().apply { gate = CompletableDeferred() }
        val controller = SignalsController(this, client, user)
        val identity = controller.state.value.scopeKey
        controller.load(); runCurrent()
        controller.reset(user.copy(publicId = "u2", activeWalletPublicId = "w2"))
        client.gate?.complete(Unit); runCurrent()
        assertTrue(controller.state.value.rows.isEmpty())
        assertNotEquals(identity, controller.state.value.scopeKey)
    }

    @Test fun failedRefreshRetainsRowsAndFilterButInvalidationClearsThem() = runTest {
        val client = Client()
        val controller = SignalsController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.selectStrategy("example")
        client.fail = true; controller.load(); runCurrent()
        assertTrue(controller.state.value.failed)
        assertEquals("example", controller.state.value.selectedStrategy)
        assertEquals(1, controller.state.value.visibleRows.size)
        controller.invalidate()
        assertFalse(controller.state.value.allowed)
        assertTrue(controller.state.value.visibleRows.isEmpty())
    }
}
