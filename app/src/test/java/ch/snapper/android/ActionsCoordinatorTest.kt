package ch.snapper.android

import ch.snapper.android.data.*
import ch.snapper.android.features.positionactions.PositionActionKind
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

}

private class ActionClient(profile: UserDto) : SessionClient, PositionActionClient, ProcessClient, AiReviewsClient {
    override val user = MutableStateFlow<UserDto?>(profile)
    var auditReads = 0
    var pendingReads = 0
    override suspend fun auditReviews(): List<AiAuditReviewDto> { auditReads++; return emptyList() }
    override suspend fun pendingReviews(): List<AiPendingReviewDto> { pendingReads++; return emptyList() }
    override suspend fun submitDecision(scope: OrderScope, reviewId: String, payload: AiDecisionPayload): AiDecisionResponse = error("Unexpected decision")
    override suspend fun configuredProcesses(): List<ConfiguredProcessDto> = emptyList()
    override suspend fun processSummary() = ProcessSummaryDto(ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0), ProcessCategoryDto(0, 0), emptyList())
    override suspend fun startProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStartDto = error("Unexpected process mutation")
    override suspend fun stopProcess(scope: OrderScope, target: ConfiguredProcessDto): ProcessStopDto = error("Unexpected process mutation")
    override suspend fun processDesiredState(scope: OrderScope, target: ConfiguredProcessDto, payload: ProcessDesiredStatePayload): ProcessDesiredStateDto = error("Unexpected process mutation")
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
