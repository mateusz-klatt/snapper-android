package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.operations.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessesControllerTest {
    private val user = UserDto("operator", "operator", "u1", effectivePermissions = listOf("read:system_status", "read:processes", "manage:processes"))
    private class Client : ProcessClient {
        var target = ConfiguredProcessDto("feed", true, true, "feed", "instance", false, "api")
        var starts = 0; var stops = 0; var remote = 0
        var startStatus = "success"; var stopStatus = "success"
        var failRead = false; var failAfterStart = false; var unknown = false; var moveAfterStop = false
        var gate: CompletableDeferred<Unit>? = null
        var nonce: String? = null
        override suspend fun processSummary(): ProcessSummaryDto {
            if (failRead) throw IOException("offline")
            val count = ProcessCategoryDto(1, 1)
            return ProcessSummaryDto(count, count, count, count, listOf(ProcessSummaryRowDto("feed", target.running, target.enabled, "feed", "long_running")))
        }
        override suspend fun configuredProcesses(): List<ConfiguredProcessDto> {
            if (failRead) throw IOException("offline")
            return listOf(target)
        }
        override suspend fun startProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStartDto {
            starts++; gate?.await()
            if (unknown) throw ProcessOutcomeUnknownException(IOException("lost"))
            if (failAfterStart) failRead = true
            return ProcessStartDto(target.name, startStatus)
        }
        override suspend fun stopProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStopDto {
            stops++
            if (moveAfterStop) this.target = this.target.copy(managedRemotely = true, coordinator = "remote", running = false)
            return ProcessStopDto(target.name, stopStatus)
        }
        override suspend fun processDesiredState(scope: OrderScope, target: ConfiguredProcessDto, payload: ProcessDesiredStatePayload): ProcessDesiredStateDto {
            remote++; nonce = payload.restartNonce
            if (unknown) throw ProcessOutcomeUnknownException(IOException("lost remote acknowledgement"))
            return ProcessDesiredStateDto(target.name, "success", payload.action, true)
        }
    }

    @Test fun operationalStopErrorDoesNotStartRestartSecondLeg() = runTest {
        val client = Client().apply { stopStatus = "error" }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.RESTART); controller.actions.confirm(); runCurrent()
        assertEquals(1, client.stops); assertEquals(0, client.starts)
        assertEquals(ProcessOutcome.REJECTED, controller.state.value.operations["feed"]?.outcome)
    }

    @Test fun ownershipChangeAfterStopReportsPartialAndNeverStartsRemoteLocally() = runTest {
        val client = Client().apply { moveAfterStop = true }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.RESTART); controller.actions.confirm(); runCurrent()
        assertEquals(1, client.stops); assertEquals(0, client.starts); assertEquals(0, client.remote)
        assertEquals(ProcessOutcome.PARTIAL_RESTART, controller.state.value.operations["feed"]?.outcome)
    }

    @Test fun remoteRestartHasStableNonceAndNeverUsesLocalEndpoints() = runTest {
        val client = Client().apply { target = target.copy(managedRemotely = true) }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.RESTART)
        val captured = controller.state.value.confirmation?.nonce
        assertTrue(captured?.matches(Regex("[A-Za-z0-9_-]{8,64}")) == true)
        controller.actions.confirm(); controller.actions.confirm(); runCurrent()
        assertEquals(captured, client.nonce); assertEquals(1, client.remote)
        assertEquals(0, client.starts + client.stops)
        assertEquals(ProcessOutcome.INTENT_SAVED, controller.state.value.operations["feed"]?.outcome)
    }

    @Test fun unknownWriteFreezesFurtherSubmission() = runTest {
        val client = Client().apply { unknown = true; target = target.copy(running = false) }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.START); controller.actions.confirm(); runCurrent()
        assertEquals(ProcessOutcome.UNKNOWN, controller.state.value.operations["feed"]?.outcome)
        controller.actions.request("feed", ProcessAction.START); controller.actions.confirm(); runCurrent()
        assertEquals(1, client.starts)
    }

    @Test fun permissionRevocationAndInvalidationPreventStaleAcceptance() = runTest {
        val client = Client().apply { target = target.copy(running = false); gate = CompletableDeferred() }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.START); controller.actions.confirm(); runCurrent()
        controller.invalidate(); client.gate?.complete(Unit); runCurrent()
        assertTrue(controller.state.value.operations.isEmpty())
        controller.reset(user.copy(effectivePermissions = listOf("read:system_status", "read:processes")))
        controller.load(); runCurrent()
        assertTrue(controller.state.value.actions("feed").isEmpty())
    }

    @Test fun startOperationalErrorAfterSuccessfulStopIsPartialRestart() = runTest {
        val client = Client().apply { startStatus = "error" }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.RESTART); controller.actions.confirm(); runCurrent()
        assertEquals(ProcessOutcome.PARTIAL_RESTART, controller.state.value.operations["feed"]?.outcome)
    }

    @Test fun missingConfigurationAndTemplatesNeverGetControls() {
        val target = Client().target
        assertEquals(ProcessControlMode.NONE, processControlMode(null, user.effectivePermissions.orEmpty().toSet()))
        for (candidate in listOf(target.copy(kind = "template"), target.copy(role = "strategy"), target.copy(role = "backtest"))) {
            assertEquals(ProcessControlMode.NONE, processControlMode(candidate, user.effectivePermissions.orEmpty().toSet()))
        }
    }
    @Test fun acceptedStartSurvivesSubsequentRefreshFailure() = runTest {
        val client = Client().apply { target = target.copy(running = false); failAfterStart = true }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.START); controller.actions.confirm(); runCurrent()
        assertEquals(ProcessOutcome.ACCEPTED, controller.state.value.operations["feed"]?.outcome)
        assertTrue(controller.state.value.summaryFailed)
        assertTrue(controller.state.value.configuredFailed)
        assertTrue(controller.state.value.configured.isEmpty())
    }

    @Test fun changedOwnerWhileConfirmationOpenPreventsAnyCommand() = runTest {
        val client = Client()
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.RESTART)
        client.target = client.target.copy(managedRemotely = true, coordinator = "remote")
        controller.actions.confirm(); runCurrent()
        assertEquals(0, client.starts + client.stops + client.remote)
        assertEquals(ProcessOutcome.STALE_TARGET, controller.state.value.operations["feed"]?.outcome)
    }

    @Test fun refreshedSnapshotsCannotResolveLostRemoteRestartOrMintAnotherNonce() = runTest {
        val client = Client().apply { target = target.copy(managedRemotely = true); unknown = true }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.RESTART)
        val nonce = controller.state.value.confirmation?.nonce
        controller.actions.confirm(); runCurrent()
        assertEquals(ProcessOutcome.UNKNOWN, controller.state.value.operations["feed"]?.outcome)
        controller.load(); runCurrent()
        assertFalse(controller.state.value.summaryFailed)
        assertFalse(controller.state.value.configuredFailed)
        assertEquals(ProcessOutcome.UNKNOWN, controller.state.value.operations["feed"]?.outcome)
        controller.actions.request("feed", ProcessAction.RESTART)
        assertNull(controller.state.value.confirmation)
        controller.actions.confirm(); runCurrent()
        assertEquals(1, client.remote)
        assertEquals(nonce, client.nonce)
    }

    @Test fun partialLocalRestartStaysUnknownAfterSuccessfulReadSnapshots() = runTest {
        val client = Client().apply { unknown = true }
        val controller = ProcessesController(this, client, user)
        controller.load(); runCurrent()
        controller.actions.request("feed", ProcessAction.RESTART); controller.actions.confirm(); runCurrent()
        assertEquals(ProcessOutcome.PARTIAL_UNKNOWN, controller.state.value.operations["feed"]?.outcome)
        controller.load(); runCurrent()
        assertEquals(ProcessOutcome.PARTIAL_UNKNOWN, controller.state.value.operations["feed"]?.outcome)
        controller.actions.request("feed", ProcessAction.RESTART); controller.actions.confirm(); runCurrent()
        assertEquals(1, client.stops)
        assertEquals(1, client.starts)
    }

}
