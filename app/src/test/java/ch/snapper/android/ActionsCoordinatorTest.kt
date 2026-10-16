package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.positionactions.PositionActionKind
import ch.snapper.android.features.operations.ProcessAction
import ch.snapper.android.features.operations.ProcessOutcome
import ch.snapper.android.features.aireviews.AiDecisionOutcome
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class ActionsCoordinatorTest {
    private val user = UserDto("operator", "operator", "u", activeWalletPublicId = "w", effectivePermissions = listOf("create:orders"))
    private val wallet = WalletDto("w", "Paper", isPaper = true)
    private val position = PositionDto("p", "BTC-USD", "venue", BigDecimal.ONE, BigDecimal.ZERO,
        averagePrice = BigDecimal.TEN, walletPublicId = "w", instrumentPublicId = "i", mode = "paper", positionCyclePublicId = "cycle")
    private fun initial() = AppUiState(user = user, snapshot = DashboardSnapshot(user, listOf(wallet), listOf(position), emptyList(), emptyList()))

    @Test fun confirmedPositionDispatchesOnceAndAcceptedCallbackDoesNotLoop() = runTest {
        val client = ActionClient(user)
        var state = initial()
        var accepted = 0
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) }, { accepted++ })
        coordinator.openPosition(position, PositionActionKind.CLOSE)
        coordinator.positionActions.review()
        coordinator.positionActions.confirm()
        coordinator.positionActions.confirm()
        runCurrent()
        assertEquals(1, client.submissions)
        assertEquals(1, accepted)
        assertEquals("plan", state.positionActions.accepted?.publicId)
        coordinator.positionActions.confirm()
        runCurrent()
        assertEquals(1, accepted)
        coordinator.invalidate()
        assertNull(state.positionActions.accepted)
    }

    @Test fun modeMismatchAndMissingPermissionDoNotOpenAction() = runTest {
        val client = ActionClient(user)
        var state = initial()
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.openPosition(position.copy(mode = "live"), PositionActionKind.CLOSE)
        runCurrent()
        assertNull(state.positionActions.draft)
        val denied = user.copy(effectivePermissions = emptyList())
        state = state.copy(user = denied)
        client.user.value = denied
        coordinator.openPosition(position, PositionActionKind.CLOSE)
        runCurrent()
        assertNull(state.positionActions.draft)
        assertEquals(0, client.submissions)
        coordinator.invalidate()
    }

    @Test fun lateAcceptanceFromOldClientCannotRefreshOrPopulateNewSession() = runTest {
        val first = ActionClient(user)
        lateinit var pending: Continuation<ExecutionPlanDto>
        first.submitAction = { suspendCoroutine { pending = it } }
        var client: SessionClient = first
        var state = initial()
        var accepted = 0
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) }, { accepted++ })
        coordinator.openPosition(position, PositionActionKind.CLOSE)
        coordinator.positionActions.review()
        coordinator.positionActions.confirm()
        runCurrent()
        coordinator.invalidate()
        client = ActionClient(user)
        pending.resume(ExecutionPlanDto("old", "active", "w"))
        runCurrent()
        assertNull(state.positionActions.accepted)
        assertEquals(0, accepted)
    }

    @Test fun delegateOrPrimaryScopeMismatchRefusesActionsUntilReconciled() = runTest {
        val client = ActionClient(user.copy(primaryOperatorPublicId = "changed"))
        var state = initial()
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.openPosition(position, PositionActionKind.CLOSE)
        runCurrent()
        assertNull(state.positionActions.draft)
        client.user.value = user.copy(delegatePublicId = "new-delegate")
        coordinator.openPosition(position, PositionActionKind.CLOSE)
        runCurrent()
        assertNull(state.positionActions.draft)
        coordinator.invalidate()
    }
    @Test fun processAndAiReadActionsPublishStateWithoutMutation() = runTest {
        val reader = user.copy(delegatePublicId = "delegate", effectivePermissions = listOf("read:system_status", "read:processes", "read:ai_reviews", "read:signals"))
        val client = ActionClient(reader)
        var state = initial().copy(user = reader)
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.loadProcesses()
        coordinator.loadAiReviews()
        runCurrent()
        assertNotNull(state.processes.summary)
        assertEquals(1, client.auditReads)
        assertEquals(1, client.pendingReads)
        assertFalse(state.aiReviews.auditLoading)
        assertFalse(state.aiReviews.pendingLoading)
        assertEquals("delegate", state.aiReviews.identity?.delegatePublicId)
        assertEquals(0, client.submissions)
        coordinator.invalidate()
        assertNull(state.aiReviews.identity)
        assertNull(state.processes.summary)
    }

    @Test fun processConfirmationCanBeDismissedAndAcceptedStopRefreshesOnce() = runTest {
        val manager = user.copy(effectivePermissions = listOf("read:system_status", "read:processes", "manage:processes"))
        val client = ActionClient(manager).apply { configured = listOf(processTarget()) }
        var state = initial().copy(user = manager)
        var accepted = 0
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) }, { accepted++ })
        coordinator.processActions.refresh(); runCurrent()
        coordinator.processActions.request("feed", ProcessAction.STOP)
        runCurrent()
        assertNotNull(state.processes.confirmation)
        assertEquals(0, client.processStops)
        coordinator.processActions.dismiss(); runCurrent()
        assertNull(state.processes.confirmation)
        coordinator.processActions.request("feed", ProcessAction.STOP)
        coordinator.processActions.confirm(); coordinator.processActions.confirm(); runCurrent()
        assertEquals(1, client.processStops)
        assertEquals(ProcessOutcome.ACCEPTED, state.processes.operations["feed"]?.outcome)
        assertEquals(1, accepted)
        coordinator.processActions.refresh(); runCurrent()
        assertEquals(1, accepted)
        coordinator.invalidate()
    }

    @Test fun remoteProcessIntentSavedRefreshesButDoesNotClaimConvergence() = runTest {
        val manager = user.copy(effectivePermissions = listOf("read:system_status", "read:processes", "manage:processes"))
        val client = ActionClient(manager).apply { configured = listOf(processTarget().copy(managedRemotely = true)) }
        var state = initial().copy(user = manager)
        var accepted = 0
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) }, { accepted++ })
        coordinator.processActions.refresh(); runCurrent()
        coordinator.processActions.request("feed", ProcessAction.RESTART); runCurrent()
        val nonce = state.processes.confirmation?.nonce
        assertFalse(nonce.isNullOrBlank())
        coordinator.processActions.confirm(); runCurrent()
        assertEquals(ProcessDesiredStatePayload("restart", nonce), client.desiredPayload)
        assertEquals(ProcessOutcome.INTENT_SAVED, state.processes.operations["feed"]?.outcome)
        assertEquals(0, client.processStops)
        assertEquals(1, accepted)
        coordinator.invalidate()
    }

    @Test fun actorReplacementBetweenReviewAndConfirmationCannotDispatchOldProcess() = runTest {
        val manager = user.copy(effectivePermissions = listOf("read:system_status", "read:processes", "manage:processes"))
        val client = ActionClient(manager).apply { configured = listOf(processTarget()) }
        var state = initial().copy(user = manager)
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) })
        coordinator.processActions.refresh(); runCurrent()
        coordinator.processActions.request("feed", ProcessAction.STOP); runCurrent()
        assertNotNull(state.processes.confirmation)
        client.user.value = manager.copy(primaryOperatorPublicId = "replacement")
        coordinator.processActions.confirm(); runCurrent()
        assertEquals(0, client.processStops)
        state = state.copy(user = client.user.value)
        coordinator.processActions.confirm(); runCurrent()
        assertNull(state.processes.confirmation)
        assertEquals(0, client.processStops)
        coordinator.invalidate()
    }

    @Test fun aiConfirmationDismissalAndAcceptancePublishOnlyOneRefresh() = runTest {
        val delegate = user.copy(delegatePublicId = "delegate", effectivePermissions = listOf("read:signals", "read:ai_reviews", "submit:ai_review_decision"))
        val client = ActionClient(delegate).apply { pendingRows = listOf(pendingReview()) }
        var state = initial().copy(user = delegate)
        var accepted = 0
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) }, { accepted++ })
        coordinator.aiActions.refresh(); runCurrent()
        coordinator.aiActions.prepare("review", "approve", " reason "); runCurrent()
        assertEquals("reason", state.aiReviews.confirmation?.payload?.rationale)
        coordinator.aiActions.dismiss(); runCurrent()
        assertNull(state.aiReviews.confirmation)
        assertTrue(client.decisions.isEmpty())
        coordinator.aiActions.prepare("review", "approve", " reason ")
        coordinator.aiActions.confirm(); coordinator.aiActions.confirm(); runCurrent()
        assertEquals(listOf(AiDecisionPayload("approve", "reason")), client.decisions)
        assertEquals(AiDecisionOutcome.ACCEPTED, state.aiReviews.results["review"]?.outcome)
        assertEquals(1, accepted)
        coordinator.aiActions.refresh(); runCurrent()
        assertEquals(1, accepted)
        coordinator.invalidate()
    }

    @Test fun aiUnknownRetryUsesFrozenDecisionOnlyAfterFreshStatus() = runTest {
        val delegate = user.copy(delegatePublicId = "delegate", effectivePermissions = listOf("read:signals", "submit:ai_review_decision"))
        val client = ActionClient(delegate).apply {
            pendingRows = listOf(pendingReview())
            decisionReply = { error("simulated lost response") }
        }
        var state = initial().copy(user = delegate)
        var accepted = 0
        val coordinator = ActionsCoordinator(backgroundScope, { client }, { state }, { state = it(state) }, { accepted++ })
        coordinator.aiActions.refresh(); runCurrent()
        coordinator.aiActions.prepare("review", "reject", " original ")
        coordinator.aiActions.confirm(); runCurrent()
        coordinator.aiActions.retry("review"); runCurrent()
        assertNull(state.aiReviews.confirmation)
        assertEquals(1, client.decisions.size)
        coordinator.aiActions.refresh(); runCurrent()
        coordinator.aiActions.retry("review"); runCurrent()
        assertEquals(AiDecisionPayload("reject", "original"), state.aiReviews.confirmation?.payload)
        client.decisionReply = { AiDecisionResponse(true, message = "accepted", details = JsonObject(emptyMap())) }
        coordinator.aiActions.confirm(); runCurrent()
        assertEquals(2, client.decisions.size)
        assertEquals(client.decisions.first(), client.decisions.last())
        assertEquals(AiDecisionOutcome.ACCEPTED, state.aiReviews.results["review"]?.outcome)
        assertEquals(1, accepted)
        coordinator.invalidate()
    }

    @Test fun missingSessionOrProfileMakesEveryActionWrapperInert() = runTest {
        var state = AppUiState()
        val coordinator = ActionsCoordinator(backgroundScope, { null }, { state }, { state = it(state) })
        coordinator.processActions.refresh()
        coordinator.processActions.request("feed", ProcessAction.STOP)
        coordinator.processActions.dismiss(); coordinator.processActions.confirm()
        coordinator.aiActions.refresh(); coordinator.aiActions.prepare("review", "approve", "")
        coordinator.aiActions.confirm(); coordinator.aiActions.dismiss(); coordinator.aiActions.retry("review")
        coordinator.positionActions.review(); coordinator.positionActions.back()
        coordinator.positionActions.confirm(); coordinator.positionActions.retry(); coordinator.positionActions.dismiss()
        coordinator.openPosition(position, PositionActionKind.CLOSE)
        runCurrent()
        assertEquals(AppUiState(), state)
        coordinator.invalidate()
    }

    private fun processTarget() = ConfiguredProcessDto("feed", true, true, "feed", "instance", false, "api")
    private fun pendingReview() = AiPendingReviewDto("review", "delegate", "w", 1, "pending", "2099-01-01T00:00:00Z", "2020-01-01T00:00:00Z")

}

private class ActionClient(profile: UserDto) : SessionClient, PositionActionClient, ProcessClient, AiReviewsClient {
    override val user = MutableStateFlow<UserDto?>(profile)
    var auditReads = 0
    var pendingReads = 0
    var configured = emptyList<ConfiguredProcessDto>()
    var pendingRows = emptyList<AiPendingReviewDto>()
    val decisions = mutableListOf<AiDecisionPayload>()
    var decisionReply: () -> AiDecisionResponse = { AiDecisionResponse(true, message = "accepted", details = JsonObject(emptyMap())) }
    var processStops = 0
    var desiredPayload: ProcessDesiredStatePayload? = null
    override suspend fun auditReviews(): List<AiAuditReviewDto> { auditReads++; return emptyList() }
    override suspend fun pendingReviews(): List<AiPendingReviewDto> { pendingReads++; return pendingRows }
    override suspend fun submitDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse { decisions += payload; return decisionReply() }
    override suspend fun configuredProcesses(): List<ConfiguredProcessDto> = configured
    override suspend fun processSummary() = ProcessSummaryDto(ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0),
        configured.map { ProcessSummaryRowDto(it.name, it.running, it.enabled, it.role, "running") })
    override suspend fun startProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStartDto = error("Unexpected process mutation")
    override suspend fun stopProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStopDto { processStops++; return ProcessStopDto(target.name, "success") }
    override suspend fun processDesiredState(scope: OrderScope, target: ConfiguredProcessDto, payload: ProcessDesiredStatePayload): ProcessDesiredStateDto {
        desiredPayload = payload
        return ProcessDesiredStateDto(target.name, "success", payload.action, target.managedRemotely)
    }
    var submissions = 0
    var submitAction: suspend () -> ExecutionPlanDto = { ExecutionPlanDto("plan", "active", "w") }
    override suspend fun submit(scope: OrderScope, intent: PositionActionIntent): ExecutionPlanDto { submissions++; return submitAction() }
    override suspend fun restore() = user.value
    override suspend fun login(username: String, password: String, rememberMe: Boolean): UserDto = error("Unexpected login")
    override suspend fun selectWallet(publicId: String?): UserDto = error("Unexpected wallet mutation")
    override suspend fun logout() { user.value = null }
    override suspend fun snapshot(): DashboardSnapshot = error("Unexpected snapshot")
    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> = error("Unexpected candles")
    override suspend fun orderExchanges(scope: OrderScope): List<String> = error("Unexpected order catalogue")
    override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = error("Unexpected order catalogue")
    override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = error("Unexpected mutation")
    override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = error("Unexpected mutation")
    override fun close() = Unit
}
