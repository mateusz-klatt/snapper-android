package ch.snapper.android

import ch.snapper.android.data.NotificationDefaultDto
import ch.snapper.android.data.NotificationDefaultRejectedException
import ch.snapper.android.data.NotificationDefaultsClient
import ch.snapper.android.data.OrderScope
import ch.snapper.android.data.UpdateNotificationDefaultPayload
import ch.snapper.android.data.UserDto
import ch.snapper.android.features.notifications.NotificationDefaultIssue
import ch.snapper.android.features.notifications.NotificationDefaultsController
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDefaultsControllerTest {
    private val user = UserDto("viewer", "viewer", "u1", effectivePermissions = listOf("manage:self_account"))
    private val type = "order_fill_full"
    private class Client : NotificationDefaultsClient {
        var rows = emptyList<NotificationDefaultDto>()
        var writes = 0
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun notificationDefaults() = rows
        override suspend fun updateNotificationDefaults(scope: OrderScope, payload: UpdateNotificationDefaultPayload): NotificationDefaultDto {
            writes++
            gate?.await()
            failure?.let { throw it }
            return NotificationDefaultDto("p1", scope.publicId, payload.alertType, payload.enabled, payload.minPriority)
        }
    }

    @Test fun auditorCanReadDefaultsButCannotEditOrSaveThem() = runTest {
        val client = Client()
        val auditor = user.copy(role = "auditor", effectivePermissions = emptyList())
        val controller = NotificationDefaultsController(this, client, auditor)
        controller.load(); runCurrent()
        controller.actions.setEnabled(type, false)
        controller.actions.setPriority(type, "high")
        controller.actions.save(type); runCurrent()
        assertTrue(controller.state.value.loaded)
        assertFalse(controller.state.value.canManage)
        assertTrue(controller.state.value.rows.all { !it.dirty && !it.pending })
        assertEquals(0, client.writes)
    }

    @Test fun emptyDefaultsExposeFiveInheritedRowsWithoutPushOrPermissions() = runTest {
        val controller = NotificationDefaultsController(this, Client(), user)
        controller.load(); runCurrent()
        assertEquals(5, controller.state.value.rows.size)
        assertTrue(controller.state.value.loaded)
        assertTrue(controller.state.value.rows.all { it.enabled && it.minPriority == "medium" && it.inherited && !it.canSave })
    }

    @Test fun duplicateSaveIsCollapsedAndOtherRowsRemainEditable() = runTest {
        val client = Client().apply { gate = CompletableDeferred() }
        val controller = NotificationDefaultsController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.setEnabled(type, false)
        controller.actions.save(type); controller.actions.save(type); runCurrent()
        assertEquals(1, client.writes)
        assertTrue(controller.state.value.rows.first().pending)
        assertTrue(controller.state.value.rows[1].editable)
        client.gate?.complete(Unit); runCurrent()
        assertTrue(controller.state.value.rows.first().saved)
        assertFalse(controller.state.value.rows.first().dirty)
    }

    @Test fun uncertainSaveCannotRepeatUntilExplicitRefreshReconciles() = runTest {
        val client = Client().apply { failure = IOException("disconnected") }
        val controller = NotificationDefaultsController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.setEnabled(type, false); controller.actions.save(type); runCurrent()
        assertEquals(NotificationDefaultIssue.UNKNOWN, controller.state.value.rows.first().issue)
        controller.actions.setEnabled(type, true); controller.actions.save(type); runCurrent()
        assertEquals(1, client.writes)
        assertFalse(controller.state.value.rows.first().enabled)
        client.rows = listOf(NotificationDefaultDto("p1", "u1", type, false, "medium"))
        controller.load(); runCurrent()
        assertFalse(controller.state.value.rows.first().dirty)
        assertTrue(controller.state.value.rows.first().editable)
        assertFalse(controller.state.value.rows.first().enabled)
        controller.actions.save(type); runCurrent()
        assertEquals(1, client.writes)
    }

    @Test fun knownRejectionPreservesEditableDraft() = runTest {
        val client = Client().apply { failure = NotificationDefaultRejectedException(403) }
        val controller = NotificationDefaultsController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.setPriority(type, "high"); controller.actions.save(type); runCurrent()
        val row = controller.state.value.rows.first()
        assertEquals(NotificationDefaultIssue.REJECTED, row.issue)
        assertTrue(row.editable)
        assertEquals("high", row.minPriority)
    }

    @Test fun completionAfterInvalidationCannotRestoreAccountData() = runTest {
        val client = Client().apply { gate = CompletableDeferred() }
        val controller = NotificationDefaultsController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.setEnabled(type, false); controller.actions.save(type); runCurrent()
        controller.invalidate()
        client.gate?.complete(Unit); runCurrent()
        assertFalse(controller.state.value.active)
        assertFalse(controller.state.value.loaded)
        assertTrue(controller.state.value.rows.none { it.saved || it.pending })
    }

    @Test fun foreignUserReadFailsClosed() = runTest {
        val client = Client().apply { rows = listOf(NotificationDefaultDto("p", "other", type, false, "high")) }
        val controller = NotificationDefaultsController(this, client, user)
        controller.load(); runCurrent()
        assertTrue(controller.state.value.loadFailed)
        assertFalse(controller.state.value.loaded)
        controller.actions.setEnabled(type, false); controller.actions.save(type); runCurrent()
        assertEquals(0, client.writes)
    }
}
