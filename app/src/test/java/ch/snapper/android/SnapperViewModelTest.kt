package ch.snapper.android

import androidx.lifecycle.ViewModelStore
import ch.snapper.android.data.CandleDto
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.SessionClient
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.WalletDto
import ch.snapper.android.data.CreateOrderPayload
import ch.snapper.android.data.ExecutionPlanDto
import ch.snapper.android.data.InstrumentDto
import ch.snapper.android.data.OrderScope
import ch.snapper.android.data.OrderRejectedException
import ch.snapper.android.data.OrderOutcomeUnknownException
import ch.snapper.android.data.OrderScopeChangedException
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SnapperViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val settings = FakeSettingsStore()
    private val client = FakeSessionClient()
    private val origins = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    private fun model(defaultOrigin: String = "https://snapper.example"): SnapperViewModel =
        SnapperViewModel(settings, { origin -> origins.add(origin); client }, defaultOrigin, false)
            .also { models.put("snapper", it) }

    @Test
    fun blankInitialOriginDoesNotCreateSession() = runTest(dispatcher) {
        val model = model("")
        advanceUntilIdle()
        assertTrue(origins.isEmpty())
        assertEquals("", model.state.value.backendUrl)
        assertFalse(model.state.value.isRestoring)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun rejectedCredentialsDoNotExposeAuthenticatedData() = runTest(dispatcher) {
        client.loginAction = { throw HttpException(Response.error<Unit>(401, "unauthorized".toResponseBody())) }
        val model = model()
        advanceUntilIdle()
        model.login("alice", "wrong")
        advanceUntilIdle()
        assertEquals(UiError.INVALID_CREDENTIALS, model.state.value.error)
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertEquals(0, client.snapshotCalls)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun logoutImmediatelyClearsDashboardAndMarketBeforeServerResponds() = runTest(dispatcher) {
        client.restoredUser = profile()
        client.logoutAction = { delay(1_000) }
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        advanceUntilIdle()
        assertTrue(model.state.value.market.candles.isNotEmpty())
        model.logout()
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertTrue(model.state.value.market.candles.isEmpty())
        assertFalse(client.closed)
        advanceUntilIdle()
        assertTrue(client.closed)
        assertEquals(1, client.logoutCalls)
    }

    @Test
    fun loginCompletingAfterLogoutCannotRestoreTheSession() = runTest(dispatcher) {
        client.loginAction = { withContext(NonCancellable) { delay(1_000); profile() } }
        val model = model()
        advanceUntilIdle()
        model.login("alice", "password")
        runCurrent()
        model.logout()
        advanceUntilIdle()
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertEquals(0, client.snapshotCalls)
        assertTrue(client.closed)
    }

    @Test
    fun walletChangeHidesOldRowsUntilServerConfirmsSelection() = runTest(dispatcher) {
        client.restoredUser = profile()
        client.selectionAction = { wallet -> delay(1_000); profile().copy(activeWalletPublicId = wallet) }
        val model = model()
        advanceUntilIdle()
        assertEquals("wallet-a", model.state.value.snapshot?.wallets?.single()?.publicId)
        model.selectWallet("wallet-b")
        assertNull(model.state.value.snapshot)
        runCurrent()
        assertEquals(1, client.snapshotCalls)
        assertEquals("wallet-a", model.state.value.user?.activeWalletPublicId)
        advanceUntilIdle()
        assertEquals("wallet-b", model.state.value.user?.activeWalletPublicId)
        assertEquals("wallet-b", model.state.value.snapshot?.wallets?.single()?.publicId)
        assertEquals(2, client.snapshotCalls)
    }

    @Test
    fun invalidBackendPreservesSessionAndValidBackendRetiresItAndPersists() = runTest(dispatcher) {
        client.restoredUser = profile()
        val model = model()
        advanceUntilIdle()
        val snapshot = model.state.value.snapshot
        model.saveBackend("http://remote.example")
        assertEquals(UiError.INVALID_ORIGIN, model.state.value.error)
        assertEquals(snapshot, model.state.value.snapshot)
        assertEquals(profile(), model.state.value.user)
        assertEquals(0, client.logoutCalls)
        model.saveBackend("https://other.example/")
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        advanceUntilIdle()
        assertEquals("https://other.example", model.state.value.backendUrl)
        assertEquals("https://other.example", settings.preferences.value.backendUrl)
        assertEquals(1, client.logoutCalls)
        assertTrue(client.closed)
    }

    @Test
    fun lateCandlesCannotOverwriteTheCurrentQuery() = runTest(dispatcher) {
        client.restoredUser = profile()
        client.candleAction = { instrument ->
            withContext(NonCancellable) {
                delay(if (instrument == "BTC-USD") 1_000 else 100)
                listOf(candle(instrument))
            }
        }
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        runCurrent()
        model.loadMarket("kraken", "ETH-USD", "1h")
        advanceUntilIdle()
        assertEquals(listOf("BTC-USD", "ETH-USD"), client.candleRequests)
        assertEquals("ETH-USD", model.state.value.market.instrument)
        assertEquals("ETH-USD", model.state.value.market.candles.single().instrument)
        assertFalse(model.state.value.market.isLoading)
    }

    @Test
    fun missingMarketPermissionPreventsFetchEvenForAdminRole() = runTest(dispatcher) {
        client.restoredUser = profile().copy(role = "admin", effectivePermissions = emptyList())
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        advanceUntilIdle()
        assertTrue(client.candleRequests.isEmpty())
        assertFalse(model.state.value.market.isLoading)
    }

    @Test
    fun revokedPermissionsClearOldRowsEvenWhenRemainingRefreshFails() = runTest(dispatcher) {
        client.restoredUser = profile()
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        advanceUntilIdle()
        assertTrue(requireNotNull(model.state.value.snapshot).orders.isNotEmpty())
        assertTrue(model.state.value.market.candles.isNotEmpty())
        val revoked = profile().copy(effectivePermissions = emptyList())
        client.beforeSnapshot = {
            client.user.value = revoked
            delay(100)
            throw IOException("Wallet fetch failed")
        }
        model.refresh()
        runCurrent()
        assertEquals(revoked, model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertTrue(model.state.value.market.candles.isEmpty())
        advanceUntilIdle()
        assertEquals(revoked, model.state.value.user)
        assertNull(model.state.value.snapshot)
        assertEquals(UiError.NETWORK, model.state.value.error)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun sessionExpiryClearsVisibleDataAndRejectsInFlightCandles() = runTest(dispatcher) {
        client.restoredUser = profile()
        client.candleAction = { instrument ->
            withContext(NonCancellable) { delay(1_000); listOf(candle(instrument)) }
        }
        val model = model()
        advanceUntilIdle()
        model.loadMarket("kraken", "BTC-USD", "1h")
        runCurrent()
        client.user.value = null
        runCurrent()
        assertNull(model.state.value.user)
        assertNull(model.state.value.snapshot)
        advanceUntilIdle()
        assertTrue(model.state.value.market.candles.isEmpty())
    }

    private fun tradingProfile(): UserDto = profile().copy(effectivePermissions = listOf("read:market_data", "create:orders", "cancel:orders"))

    private fun readyOrder(model: SnapperViewModel) {
        model.openOrder()
        dispatcher.scheduler.advanceUntilIdle()
        val draft = requireNotNull(model.state.value.orders.draft)
        model.updateOrderDraft(draft.copy(instrumentPublicId = "instrument-a", quantity = "0.1234567890123456789", price = "100"))
        model.reviewOrder()
    }

    @Test
    fun creationRequiresKnownSelectedWalletAndExplicitPermissionsWithoutFallback() = runTest(dispatcher) {
        client.restoredUser = tradingProfile().copy(role = "admin", effectivePermissions = listOf("read:market_data"))
        val model = model()
        advanceUntilIdle()
        model.openOrder()
        advanceUntilIdle()
        assertNull(model.state.value.orders.draft)
        assertEquals(0, client.exchangeCalls)
        client.user.value = tradingProfile().copy(activeWalletPublicId = null)
        runCurrent()
        model.openOrder()
        assertNull(model.state.value.orders.draft)
        client.user.value = tradingProfile()
        runCurrent()
        model.openOrder()
        assertNull(model.state.value.orders.draft)
        model.refresh()
        advanceUntilIdle()
        client.exchangeAction = { emptyList() }
        model.openOrder()
        advanceUntilIdle()
        assertTrue(model.state.value.orders.exchanges.isEmpty())
        assertEquals("", model.state.value.orders.draft?.exchange)
        assertFalse(model.state.value.orders.canReview)
        assertTrue(client.instrumentRequests.isEmpty())
    }

    @Test
    fun duplicateSubmitSendsOneImmutableIntentAndAcceptanceSurvivesRefreshFailure() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        val review = requireNotNull(model.state.value.orders.review)
        client.createAction = { delay(100); plan() }
        client.beforeSnapshot = { throw IOException("Snapshot unavailable") }
        model.submitOrder()
        model.submitOrder()
        model.updateOrderDraft(OrderDraft(quantity = "99"))
        advanceUntilIdle()
        assertEquals(listOf(review.body), client.created)
        assertEquals(OrderOutcome.CREATED, model.state.value.orders.outcome)
        assertTrue(model.state.value.orders.snapshotRefreshFailed)
        assertNull(model.state.value.orders.review)
        assertNull(model.state.value.error)
    }

    @Test
    fun ambiguousOutcomeFreezesIntentAndOnlyExplicitRetryReusesIt() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        val review = requireNotNull(model.state.value.orders.review)
        client.createAction = { throw OrderOutcomeUnknownException(IOException("Lost response")) }
        model.submitOrder()
        advanceUntilIdle()
        assertEquals(OrderOutcome.UNKNOWN, model.state.value.orders.outcome)
        model.editOrder()
        model.updateOrderDraft(OrderDraft(quantity = "99"))
        model.openOrder()
        model.submitOrder()
        advanceUntilIdle()
        assertEquals(review, model.state.value.orders.review)
        assertEquals(1, client.created.size)
        model.actions.orders.dismiss()
        assertTrue(model.state.value.orders.isDismissed)
        assertEquals(review, model.state.value.orders.review)
        model.openOrder()
        assertFalse(model.state.value.orders.isDismissed)
        assertEquals(review, model.state.value.orders.review)
        client.createAction = { plan() }
        model.submitOrder(retry = true)
        advanceUntilIdle()
        assertEquals(listOf(review.body, review.body), client.created)
        assertEquals(OrderOutcome.CREATED, model.state.value.orders.outcome)
    }

    @Test
    fun idempotencyConflictStaysAmbiguousAndKnownRejectionCanBeEditedWithNewKey() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        val first = requireNotNull(model.state.value.orders.review)
        client.createAction = { throw OrderRejectedException(422, "caps_violation") }
        model.submitOrder()
        advanceUntilIdle()
        assertEquals(OrderIssue.CAPS_VIOLATION, model.state.value.orders.issue)
        model.editOrder()
        model.updateOrderDraft(requireNotNull(model.state.value.orders.draft).copy(quantity = "2"))
        model.reviewOrder()
        assertTrue(first.body.idempotencyKey != model.state.value.orders.review?.body?.idempotencyKey)
        client.createAction = { throw OrderRejectedException(409) }
        model.submitOrder()
        advanceUntilIdle()
        assertEquals(OrderOutcome.UNKNOWN, model.state.value.orders.outcome)
        model.editOrder()
        assertTrue(model.state.value.orders.review != null)
    }

    @Test
    fun rejectedRetriesCannotResolveAnEarlierUnknownSubmissionOrRemintItsKey() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        val review = requireNotNull(model.state.value.orders.review)
        client.createAction = { throw OrderOutcomeUnknownException(IOException("Lost first response")) }
        model.submitOrder()
        advanceUntilIdle()
        val rejections = listOf(
            OrderRejectedException(422, "instrument_market_data_only") to OrderIssue.OBSERVATION_ONLY,
            OrderRejectedException(403) to OrderIssue.FORBIDDEN,
        )
        for ((rejection, issue) in rejections) {
            client.createAction = { delay(100); throw rejection }
            model.submitOrder(retry = true)
            assertEquals(OrderOutcome.UNKNOWN, model.state.value.orders.outcome)
            assertTrue(model.state.value.orders.isSubmitting)
            model.submitOrder(retry = true)
            advanceUntilIdle()
            assertEquals(OrderOutcome.UNKNOWN, model.state.value.orders.outcome)
            assertEquals(issue, model.state.value.orders.issue)
            assertFalse(model.state.value.orders.isSubmitting)
            model.editOrder()
            model.updateOrderDraft(requireNotNull(model.state.value.orders.draft).copy(quantity = "9"))
            model.actions.orders.dismiss()
            assertTrue(model.state.value.orders.isDismissed)
            model.openOrder()
            model.reviewOrder()
            model.submitOrder()
            advanceUntilIdle()
            assertEquals(review, model.state.value.orders.review)
            assertEquals(OrderOutcome.UNKNOWN, model.state.value.orders.outcome)
            assertFalse(model.state.value.orders.isDismissed)
        }
        assertEquals(List(3) { review.body }, client.created)
        client.createAction = { plan() }
        model.submitOrder(retry = true)
        advanceUntilIdle()
        assertEquals(List(4) { review.body }, client.created)
        assertEquals(OrderOutcome.CREATED, model.state.value.orders.outcome)
        assertNull(model.state.value.orders.review)
    }

    @Test
    fun realPermissionScopeChangeStillClearsHistoricallyUnknownIntentDuringRetry() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        client.createAction = { throw OrderOutcomeUnknownException(IOException("Lost response")) }
        model.submitOrder()
        advanceUntilIdle()
        client.createAction = {
            withContext(NonCancellable) {
                delay(100)
                throw OrderRejectedException(403)
            }
        }
        model.submitOrder(retry = true)
        runCurrent()
        client.user.value = tradingProfile().copy(effectivePermissions = emptyList())
        runCurrent()
        assertEquals(OrderFlowState(), model.state.value.orders)
        advanceUntilIdle()
        assertEquals(OrderFlowState(), model.state.value.orders)
    }

    @Test
    fun scopeChangesDropLateMutationSuccessAndFailure() = runTest(dispatcher) {
        for (fail in listOf(false, true)) {
            client.restoredUser = tradingProfile()
            val model = model()
            advanceUntilIdle()
            readyOrder(model)
            client.createAction = {
                withContext(NonCancellable) {
                    delay(100)
                    if (fail) throw IOException("Late failure")
                    plan()
                }
            }
            model.submitOrder()
            runCurrent()
            client.user.value = tradingProfile().copy(effectivePermissions = emptyList())
            runCurrent()
            assertEquals(OrderFlowState(), model.state.value.orders)
            advanceUntilIdle()
            assertEquals(OrderFlowState(), model.state.value.orders)
        }
    }

    @Test
    fun walletChangeClearsReviewedIntentBeforeSelectionFinishes() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        client.selectionAction = { delay(100); tradingProfile().copy(activeWalletPublicId = it) }
        model.selectWallet("wallet-b")
        assertEquals(OrderFlowState(), model.state.value.orders)
        model.submitOrder()
        advanceUntilIdle()
        assertTrue(client.created.isEmpty())
    }

    @Test
    fun exchangeRoundTripRejectsLateSuccessAndLateFailure() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        client.exchangeAction = { listOf("kraken", "other") }
        val model = model()
        advanceUntilIdle()
        model.openOrder()
        advanceUntilIdle()
        var count = 0
        client.instrumentAction = { exchange ->
            val call = ++count
            withContext(NonCancellable) {
                delay(if (call == 3) 10 else 100)
                if (call == 2) throw IOException("Old exchange failed")
                listOf(instrument(exchange).copy(symbol = "version-$call"))
            }
        }
        model.selectOrderExchange("kraken")
        runCurrent()
        model.selectOrderExchange("other")
        runCurrent()
        model.selectOrderExchange("kraken")
        advanceUntilIdle()
        assertEquals("version-3", model.state.value.orders.instruments.single().symbol)
        assertNull(model.state.value.orders.issue)
        assertFalse(model.state.value.orders.loadingCatalog)
    }

    @Test
    fun catalogueFailureRecoversAndLogoutDropsUncooperativeLookup() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        client.exchangeAction = { throw IOException("No catalogue") }
        val model = model()
        advanceUntilIdle()
        model.openOrder()
        advanceUntilIdle()
        assertEquals(OrderIssue.CATALOG, model.state.value.orders.issue)
        client.exchangeAction = { listOf("kraken") }
        model.retryOrderCatalog()
        advanceUntilIdle()
        assertNull(model.state.value.orders.issue)
        assertEquals("BTC-USD", model.state.value.orders.instruments.single().symbol)
        client.instrumentAction = { withContext(NonCancellable) { delay(100); listOf(instrument(it)) } }
        model.selectOrderExchange("kraken")
        runCurrent()
        model.logout()
        advanceUntilIdle()
        assertEquals(OrderFlowState(), model.state.value.orders)
    }

    @Test
    fun cancelRequiresCurrentOpenRowAndConfirmationAndRejectsDoubleTap() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        val order = requireNotNull(model.state.value.snapshot).orders.single()
        model.requestOrderCancel(order.copy(planPublicId = null))
        assertNull(model.state.value.orders.pendingCancel)
        model.requestOrderCancel(order.copy(status = "filled"))
        assertNull(model.state.value.orders.pendingCancel)
        model.requestOrderCancel(order)
        assertEquals(order, model.state.value.orders.pendingCancel)
        assertTrue(client.cancelled.isEmpty())
        client.cancelAction = { delay(100); plan() }
        model.confirmOrderCancel()
        model.confirmOrderCancel()
        advanceUntilIdle()
        assertEquals(listOf(order), client.cancelled)
        assertEquals(OrderOutcome.CANCEL_REQUESTED, model.state.value.orders.outcome)
    }

    @Test
    fun cancelConfirmationRechecksRowAndScopeAndAmbiguousCancelDoesNotRetry() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        val order = requireNotNull(model.state.value.snapshot).orders.single()
        model.requestOrderCancel(order)
        client.orderStatus = "filled"
        model.refresh()
        advanceUntilIdle()
        model.confirmOrderCancel()
        assertTrue(client.cancelled.isEmpty())
        client.orderStatus = "open"
        model.refresh()
        advanceUntilIdle()
        model.requestOrderCancel(order)
        client.cancelAction = { throw OrderOutcomeUnknownException(IOException("No reply")) }
        model.confirmOrderCancel()
        advanceUntilIdle()
        assertEquals(OrderOutcome.UNKNOWN, model.state.value.orders.outcome)
        model.confirmOrderCancel()
        model.submitOrder(retry = true)
        advanceUntilIdle()
        assertEquals(1, client.cancelled.size)
    }

    @Test
    fun knownRejectionCodesRemainVisibleWithoutAutomaticRetries() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        val failures = listOf(
            OrderRejectedException(403) to OrderIssue.FORBIDDEN,
            OrderRejectedException(422, "unknown_instrument") to OrderIssue.UNKNOWN_INSTRUMENT,
            OrderRejectedException(422, "instrument_market_data_only") to OrderIssue.OBSERVATION_ONLY,
            OrderRejectedException(400) to OrderIssue.REJECTED,
            IllegalArgumentException("Invalid payload") to OrderIssue.INVALID_FORM,
        )
        for ((failure, issue) in failures) {
            client.createAction = { throw failure }
            model.submitOrder()
            advanceUntilIdle()
            assertEquals(issue, model.state.value.orders.issue)
            assertNull(model.state.value.orders.outcome)
        }
        assertEquals(failures.size, client.created.size)
        client.createAction = { throw OrderScopeChangedException() }
        model.submitOrder()
        advanceUntilIdle()
        assertNull(model.state.value.orders.review)
        assertEquals(OrderIssue.SESSION_CHANGED, model.state.value.orders.issue)
        model.actions.orders.clearResult()
        assertEquals(OrderFlowState(), model.state.value.orders)
    }

    @Test
    fun revokedScopeBeforeObserverRunsCannotSubmitCreateOrCancel() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        client.user.value = tradingProfile().copy(activeWalletPublicId = "wallet-b")
        model.submitOrder()
        assertEquals(OrderFlowState(), model.state.value.orders)
        advanceUntilIdle()
        assertTrue(client.created.isEmpty())
        client.user.value = tradingProfile()
        advanceUntilIdle()
        model.refresh()
        advanceUntilIdle()
        val order = requireNotNull(model.state.value.snapshot).orders.single()
        model.requestOrderCancel(order)
        client.user.value = tradingProfile().copy(effectivePermissions = emptyList())
        model.confirmOrderCancel()
        assertEquals(OrderFlowState(), model.state.value.orders)
        advanceUntilIdle()
        assertTrue(client.cancelled.isEmpty())
    }

    @Test
    fun reviewedDraftIsDismissibleButBusySubmissionAndLateLogoutResultCannotRestoreIt() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        model.actions.orders.dismiss()
        assertEquals(OrderFlowState(), model.state.value.orders)
        readyOrder(model)
        client.createAction = { withContext(NonCancellable) { delay(100); plan() } }
        model.submitOrder()
        runCurrent()
        model.actions.orders.dismiss()
        assertTrue(model.state.value.orders.isSubmitting)
        model.logout()
        assertEquals(OrderFlowState(), model.state.value.orders)
        advanceUntilIdle()
        assertEquals(OrderFlowState(), model.state.value.orders)
        assertNull(model.state.value.user)
    }

    @Test
    fun instrumentsFailureCanRetryWithoutKeepingOldSelection() = runTest(dispatcher) {
        client.restoredUser = tradingProfile()
        val model = model()
        advanceUntilIdle()
        readyOrder(model)
        model.editOrder()
        client.instrumentAction = { throw IOException("Unavailable") }
        model.selectOrderExchange("kraken")
        assertEquals("", model.state.value.orders.draft?.instrumentPublicId)
        assertTrue(model.state.value.orders.instruments.isEmpty())
        advanceUntilIdle()
        assertEquals(OrderIssue.CATALOG, model.state.value.orders.issue)
        client.instrumentAction = { listOf(instrument(it)) }
        model.retryOrderCatalog()
        advanceUntilIdle()
        assertNull(model.state.value.orders.issue)
        assertEquals(1, model.state.value.orders.instruments.size)
    }
}

private class FakeSettingsStore : SettingsStore {
    override val preferences = MutableStateFlow(ClientPreferences(null))
    override suspend fun setBackendUrl(origin: String) {
        preferences.value = preferences.value.copy(backendUrl = origin)
    }
    override suspend fun setLanguage(language: String) {
        preferences.value = preferences.value.copy(language = language)
    }
}

private class FakeSessionClient : SessionClient {
    override val user = MutableStateFlow<UserDto?>(null)
    var restoredUser: UserDto? = null
    var loginAction: suspend () -> UserDto = { profile() }
    var selectionAction: suspend (String?) -> UserDto = { profile().copy(activeWalletPublicId = it) }
    var logoutAction: suspend () -> Unit = {}
    var candleAction: suspend (String) -> List<CandleDto> = { listOf(candle(it)) }
    var beforeSnapshot: suspend () -> Unit = {}
    var exchangeAction: suspend () -> List<String> = { listOf("kraken") }
    var instrumentAction: suspend (String) -> List<InstrumentDto> = { listOf(instrument(it)) }
    var createAction: suspend () -> ExecutionPlanDto = { plan() }
    var cancelAction: suspend () -> ExecutionPlanDto = { plan() }
    var orderStatus = "open"
    var exchangeCalls = 0
    val instrumentRequests = mutableListOf<String>()
    val created = mutableListOf<CreateOrderPayload>()
    val cancelled = mutableListOf<OrderDto>()
    var snapshotCalls = 0
    var logoutCalls = 0
    var closed = false
    val candleRequests = mutableListOf<String>()

    override suspend fun restore(): UserDto? = restoredUser.also { user.value = it }
    override suspend fun login(username: String, password: String, rememberMe: Boolean): UserDto =
        loginAction().also { user.value = it }
    override suspend fun selectWallet(publicId: String?): UserDto = selectionAction(publicId).also { user.value = it }
    override suspend fun logout() {
        logoutCalls++
        logoutAction()
        user.value = null
    }
    override suspend fun snapshot(): DashboardSnapshot {
        snapshotCalls++
        beforeSnapshot()
        val current = requireNotNull(user.value)
        val wallet = WalletDto(requireNotNull(current.activeWalletPublicId), "Selected wallet", isPaper = true)
        val order = OrderDto(
            "order-a", "client-order-a", "BTC-USD", "kraken", "buy", orderStatus, "limit",
            BigDecimal.ONE, BigDecimal.ZERO, "2026-10-15T00:00:00Z", walletPublicId = wallet.publicId, planPublicId = "plan-a",
        )
        return DashboardSnapshot(current, listOf(wallet), emptyList(), listOf(order), emptyList())
    }
    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> {
        candleRequests.add(instrument)
        return candleAction(instrument)
    }
    override fun close() {
        closed = true
    }
    override suspend fun orderExchanges(scope: OrderScope): List<String> {
        exchangeCalls++
        return exchangeAction()
    }
    override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> {
        instrumentRequests.add(exchange)
        return instrumentAction(exchange)
    }
    override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto {
        created.add(payload)
        return createAction()
    }
    override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto {
        cancelled.add(order)
        return cancelAction()
    }
}

private fun instrument(exchange: String = "kraken") = InstrumentDto("instrument-a", "symbol-a", "BTC-USD", exchange, true, true)
private fun plan() = ExecutionPlanDto("plan-a", "pending", "wallet-a")

private fun profile() = UserDto(
    "alice", "viewer", activeWalletPublicId = "wallet-a", effectivePermissions = listOf("read:market_data"),
)

private fun candle(instrument: String) = CandleDto(
    instrument, "kraken", "1h", "2026-10-15T00:00:00Z",
    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
)
