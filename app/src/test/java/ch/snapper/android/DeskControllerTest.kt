package ch.snapper.android

import ch.snapper.android.data.DeskClient
import ch.snapper.android.data.DeskRejectedException
import ch.snapper.android.data.DeskPreparationException
import ch.snapper.android.data.DeskDto
import ch.snapper.android.data.OrderScope
import ch.snapper.android.data.UserDto
import ch.snapper.android.features.desks.DeskController
import ch.snapper.android.features.desks.DeskIssue
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeskControllerTest {
    private val user = UserDto("operator", "operator", "user-a", effectivePermissions = listOf("manage:desk_memberships"))

    private class Client : DeskClient {
        var calls = 0
        var sentUsername = ""
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var loadGate: CompletableDeferred<Unit>? = null
        override suspend fun desks(): List<DeskDto> {
            loadGate?.await()
            return listOf(DeskDto("desk-a", "A"))
        }
        override suspend fun attachViewer(scope: OrderScope, operatorPublicId: String, username: String) {
            calls++
            sentUsername = username
            gate?.await()
            failure?.let { throw it }
        }
    }

    @Test
    fun duplicateSubmitIsCollapsedAndSuccessClearsUsername() = runTest {
        val client = Client()
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        controller.actions.setUsername(" viewer ")
        controller.actions.attach()
        controller.actions.attach()
        runCurrent()
        assertEquals(1, client.calls)
        assertEquals("viewer", client.sentUsername)
        assertTrue(controller.state.value.succeeded)
        assertEquals("", controller.state.value.username)
    }

    @Test
    fun unknownOutcomeSurvivesRefreshAndPreventsReplay() = runTest {
        val client = Client().apply { failure = IOException("Disconnected") }
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        controller.actions.setUsername("viewer")
        controller.actions.attach()
        runCurrent()
        controller.load()
        runCurrent()
        controller.actions.setUsername("different")
        controller.actions.attach()
        runCurrent()
        assertEquals(DeskIssue.UNKNOWN, controller.state.value.issue)
        assertEquals("viewer", controller.state.value.username)
        assertEquals(1, client.calls)
    }

    @Test
    fun preparationFailurePreservesDraftAndAllowsExplicitRetry() = runTest {
        val client = Client().apply { failure = DeskPreparationException(IOException("Unavailable")) }
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        controller.actions.setUsername("viewer")
        controller.actions.attach()
        runCurrent()
        assertEquals(DeskIssue.PREPARATION, controller.state.value.issue)
        assertTrue(controller.state.value.canSubmit)
        assertEquals("viewer", controller.state.value.username)
        client.failure = null
        controller.actions.attach()
        runCurrent()
        assertEquals(2, client.calls)
        assertTrue(controller.state.value.succeeded)
    }

    @Test
    fun scopeResetDropsLateAcceptanceAndRevokesForm() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { this.gate = gate }
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        controller.actions.setUsername("viewer")
        controller.actions.attach()
        runCurrent()
        controller.reset(user.copy(publicId = "other", effectivePermissions = emptyList()))
        gate.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.succeeded)
        assertFalse(controller.state.value.canManage)
        assertFalse(controller.state.value.canSubmit)
        assertEquals("", controller.state.value.username)
    }
    @Test
    fun invalidUsernameAndMissingCapabilityNeverDispatch() = runTest {
        val client = Client()
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        controller.actions.setUsername("   ")
        controller.actions.attach()
        runCurrent()
        assertEquals(DeskIssue.INVALID_USERNAME, controller.state.value.issue)
        assertEquals(0, client.calls)
        controller.reset(user.copy(effectivePermissions = emptyList()))
        controller.load()
        runCurrent()
        controller.actions.setUsername("viewer")
        controller.actions.attach()
        runCurrent()
        assertFalse(controller.state.value.canSubmit)
        assertEquals(0, client.calls)
    }

    @Test
    fun knownRejectionPreservesInputAndAllowsCorrectedRetry() = runTest {
        val client = Client().apply { failure = DeskRejectedException(422) }
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        controller.actions.setUsername("missing-viewer")
        controller.actions.attach()
        runCurrent()
        assertEquals(DeskIssue.REJECTED, controller.state.value.issue)
        assertEquals("missing-viewer", controller.state.value.username)
        assertTrue(controller.state.value.canSubmit)
        controller.actions.setUsername("existing-viewer")
        client.failure = null
        controller.actions.attach()
        runCurrent()
        assertEquals(2, client.calls)
        assertEquals("existing-viewer", client.sentUsername)
        assertTrue(controller.state.value.succeeded)
    }

    @Test
    fun lateDeskCatalogueCannotRestoreInvalidatedSession() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { loadGate = gate }
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        assertTrue(controller.state.value.loading)
        controller.invalidate()
        gate.complete(Unit)
        runCurrent()
        assertTrue(controller.state.value.desks.isEmpty())
        assertFalse(controller.state.value.loaded)
        assertFalse(controller.state.value.canManage)
    }

    @Test
    fun lateDeskCatalogueCannotReplaceNewAccountCatalogueState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { loadGate = gate }
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        controller.reset(user.copy(publicId = "other", effectivePermissions = emptyList()), "other-desk")
        gate.complete(Unit)
        runCurrent()
        assertTrue(controller.state.value.desks.isEmpty())
        assertEquals("other-desk", controller.state.value.primaryOperatorPublicId)
        assertFalse(controller.state.value.loaded)
    }

    @Test
    fun lateSubmissionFailureCannotRestoreInvalidatedDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = Client().apply { this.gate = gate; failure = IOException("Disconnected") }
        val controller = DeskController(this, client, user)
        controller.load()
        runCurrent()
        controller.actions.setUsername("viewer")
        controller.actions.attach()
        runCurrent()
        controller.invalidate()
        gate.complete(Unit)
        runCurrent()
        assertEquals(null, controller.state.value.issue)
        assertEquals("", controller.state.value.username)
        assertFalse(controller.state.value.submitting)
        assertFalse(controller.state.value.succeeded)
        assertFalse(controller.state.value.canSubmit)
    }

}
