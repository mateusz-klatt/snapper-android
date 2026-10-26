package ch.snapper.android

import androidx.lifecycle.ViewModelStore
import ch.snapper.android.data.*
import ch.snapper.android.features.accountlanguage.AccountLanguageStatus
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLanguageViewModelTest {
    @Test fun auditorKeepsLanguageLocalWithoutAccountSyncOrErrors() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession().apply {
                user.value = requireNotNull(user.value).copy(role = "auditor", effectivePermissions = listOf("read:users", "read:all_scopes"))
            }
            val model = SnapperViewModel(LanguageSettings(), { client }, "https://example.test", false)
            store.put("auditor", model); advanceUntilIdle()
            assertTrue(model.state.value.accountLanguageReady)
            assertTrue(client.languages.isEmpty())
            assertEquals(1, client.snapshots)
            model.selectLanguage("fr"); advanceUntilIdle()
            assertEquals("fr", model.state.value.language)
            assertTrue(client.languages.isEmpty())
            assertEquals(AccountLanguageStatus.IDLE, model.state.value.accountLanguage.status)
            assertNull(model.state.value.error)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun restoreWaitsForLanguageBeforeInitialDescriptionSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession(); val settings = LanguageSettings()
            client.gate = CompletableDeferred()
            val model = SnapperViewModel(settings, { client }, "https://example.test", false) { listOf(Locale.JAPAN) }
            store.put("vm", model); runCurrent()
            assertEquals(listOf("pl"), client.languages); assertEquals(0, client.snapshots)
            assertFalse(model.state.value.accountLanguageReady)
            client.gate!!.complete(Unit); advanceUntilIdle()
            assertTrue(model.state.value.accountLanguageReady); assertEquals(1, client.snapshots)
            assertEquals(AccountLanguageStatus.ACCEPTED, model.state.value.accountLanguage.status)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun canonicalActorRebindingHoldsAllSnapshotsUntilNewLanguageBarrierCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession().apply { normalizePrimaryOnce = true; gate = CompletableDeferred() }
            val model = SnapperViewModel(LanguageSettings(), { client }, "https://example.test", false)
            store.put("vm", model); runCurrent()
            assertEquals(listOf("pl", "pl"), client.languages)
            assertEquals("canonical-desk", model.state.value.user?.primaryOperatorPublicId)
            assertFalse(model.state.value.accountLanguageReady)
            assertEquals(0, client.snapshots)
            assertTrue(client.snapshotLanguages.isEmpty())
            client.gate!!.complete(Unit); advanceUntilIdle()
            assertTrue(model.state.value.accountLanguageReady)
            assertEquals(1, client.snapshots)
            assertEquals(listOf("pl"), client.snapshotLanguages)
            assertEquals("canonical-desk", model.state.value.snapshot?.user?.primaryOperatorPublicId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun failedWalletSelectionRestoresPlainClientBarrierAndVisibleErrorWithoutReads() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            for (failure in listOf(java.io.IOException("Offline"), retrofit2.HttpException(retrofit2.Response.error<Unit>(403, "{}".toResponseBody())))) {
                val underlying = LanguageSession().apply { walletFailure = failure }
                val plain = object : SessionClient by underlying {}
                val model = SnapperViewModel(LanguageSettings(), { plain }, "https://example.test", false)
                store.put("plain-wallet", model); advanceUntilIdle()
                model.selectWallet("other"); advanceUntilIdle()
                assertTrue(model.state.value.accountLanguageReady)
                assertFalse(model.state.value.isLoading)
                assertNotNull(model.state.value.error)
                assertNotNull(model.state.value.user)
                assertEquals(1, underlying.walletCalls)
                assertEquals(1, underlying.snapshots)
                assertTrue(underlying.languages.isEmpty())
            }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun failedWalletSelectionSettlesSurvivingLanguageScopeWithoutReplayingWallet() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession().apply { walletFailure = java.io.IOException("Offline") }
            val model = SnapperViewModel(LanguageSettings(), { client }, "https://example.test", false)
            store.put("language-wallet", model); advanceUntilIdle()
            client.gate = CompletableDeferred()
            model.selectWallet("other"); runCurrent()
            assertFalse(model.state.value.accountLanguageReady)
            assertEquals(listOf("pl", "pl"), client.languages)
            assertEquals(1, client.walletCalls)
            client.gate!!.complete(Unit); advanceUntilIdle()
            assertTrue(model.state.value.accountLanguageReady)
            assertEquals(UiError.NETWORK, model.state.value.error)
            assertFalse(model.state.value.isLoading)
            assertNotNull(model.state.value.user)
            assertEquals(1, client.walletCalls)
            assertEquals(1, client.snapshots)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun loginWaitsForAccountLanguageBeforeReadingAlerts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession().apply { user.value = null; gate = CompletableDeferred() }
            val model = SnapperViewModel(LanguageSettings(), { client }, "https://example.test", false)
            store.put("vm", model); advanceUntilIdle()
            model.login("reader", "synthetic"); runCurrent()
            assertEquals(listOf("pl"), client.languages); assertEquals(0, client.snapshots)
            assertFalse(model.state.value.accountLanguageReady)
            client.gate!!.complete(Unit); advanceUntilIdle()
            assertEquals(1, client.snapshots); assertTrue(model.state.value.accountLanguageReady)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun localPersistencePrecedesMutationAndCountryDoesNotSynchronizeLanguage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession(); val settings = LanguageSettings()
            val model = SnapperViewModel(settings, { client }, "https://example.test", false)
            store.put("vm", model); advanceUntilIdle()
            settings.gate = CompletableDeferred()
            model.selectLanguage("fr"); runCurrent()
            assertEquals(listOf("pl"), client.languages); assertEquals("pl", model.state.value.language)
            settings.gate!!.complete(Unit); advanceUntilIdle()
            assertEquals(listOf("pl", "fr"), client.languages); assertEquals("fr", model.state.value.language)
            model.selectCountry("jp"); advanceUntilIdle()
            assertEquals(listOf("pl", "fr"), client.languages); assertEquals(0, client.candleReads)
            assertEquals(2, client.snapshots)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun failedLanguageStillOpensInitialBarrierAndOnlyExplicitRetrySendsAgain() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession().apply { fail = true }
            val model = SnapperViewModel(LanguageSettings(), { client }, "https://example.test", false)
            store.put("vm", model); advanceUntilIdle()
            assertTrue(model.state.value.accountLanguageReady); assertEquals(1, client.snapshots)
            assertEquals(AccountLanguageStatus.FAILED, model.state.value.accountLanguage.status)
            assertEquals(listOf("pl"), client.languages)
            client.fail = false; model.actions.retryAccountLanguage(); advanceUntilIdle()
            assertEquals(listOf("pl", "pl"), client.languages)
            assertEquals(AccountLanguageStatus.ACCEPTED, model.state.value.accountLanguage.status)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun acceptedLanguageQueuesFreshDescriptionReadBehindOldSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession()
            val model = SnapperViewModel(LanguageSettings(), { client }, "https://example.test", false)
            store.put("vm", model); advanceUntilIdle()
            val held = CompletableDeferred<Unit>(); client.snapshotGates.addAll(listOf(null, held))
            model.refresh(); runCurrent()
            model.selectLanguage("fr"); runCurrent()
            assertEquals(listOf("pl", "pl"), client.snapshotLanguages)
            assertEquals(AccountLanguageStatus.ACCEPTED, model.state.value.accountLanguage.status)
            held.complete(Unit); advanceUntilIdle()
            assertEquals(listOf("pl", "pl", "fr"), client.snapshotLanguages)
            assertEquals(0, client.candleReads)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun logoutDiscardsQueuedDescriptionRefreshBehindHeldSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession()
            val model = SnapperViewModel(LanguageSettings(), { client }, "https://example.test", false)
            store.put("vm", model); advanceUntilIdle()
            val held = CompletableDeferred<Unit>(); client.snapshotGates.addAll(listOf(null, held))
            model.refresh(); runCurrent(); model.selectLanguage("fr"); runCurrent()
            model.logout(); runCurrent(); held.complete(Unit); advanceUntilIdle()
            assertEquals(2, client.snapshots); assertNull(model.state.value.snapshot)
            assertNull(model.state.value.user)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun actorRebindAwaitsLanguageThenRestoresDescriptionSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession()
            val model = SnapperViewModel(LanguageSettings(), { client }, "https://example.test", false)
            store.put("vm", model); advanceUntilIdle()
            client.gate = CompletableDeferred()
            client.user.value = client.user.value!!.copy(delegatePublicId = "new-delegate")
            runCurrent()
            assertFalse(model.state.value.accountLanguageReady); assertNull(model.state.value.snapshot)
            assertEquals(1, client.snapshots)
            model.onBackground()
            model.onForeground(); runCurrent()
            model.refresh(); runCurrent()
            assertFalse(model.state.value.accountLanguageReady)
            assertNull(model.state.value.snapshot)
            assertEquals(1, client.snapshots)
            client.gate!!.complete(Unit); advanceUntilIdle()
            assertTrue(model.state.value.accountLanguageReady); assertEquals(2, client.snapshots)
            assertEquals("new-delegate", model.state.value.snapshot?.user?.delegatePublicId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun optionalLanguageCapabilityAbsentPreservesManualSnapshotLifecycleOnActorChange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val delegate = LanguageSession()
            val plain = object : SessionClient by delegate {}
            val model = SnapperViewModel(LanguageSettings(), { plain }, "https://example.test", false)
            store.put("vm", model); advanceUntilIdle()
            assertEquals(1, delegate.snapshots); assertTrue(delegate.languages.isEmpty())
            delegate.user.value = delegate.user.value!!.copy(delegatePublicId = "changed")
            advanceUntilIdle()
            assertEquals(1, delegate.snapshots); assertNull(model.state.value.snapshot)
            assertTrue(model.state.value.accountLanguageReady)
            model.refresh(); advanceUntilIdle()
            assertEquals(2, delegate.snapshots)
            assertEquals("changed", model.state.value.snapshot?.user?.delegatePublicId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun logoutDropsHeldLanguageReceiptAndSystemChoiceUsesDeviceLanguageNotCountry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = LanguageSession().apply { gate = CompletableDeferred() }
            val settings = LanguageSettings().apply { preferences.value = ClientPreferences(null, "", DisplayPreferences("pl")) }
            val model = SnapperViewModel(settings, { client }, "https://example.test", false) { listOf(Locale.JAPAN) }
            store.put("vm", model); runCurrent()
            assertEquals(listOf("ja"), client.languages)
            model.logout(); runCurrent(); client.gate!!.complete(Unit); advanceUntilIdle()
            assertNull(model.state.value.user); assertEquals(0, client.snapshots)
            assertEquals(AccountLanguageStatus.IDLE, model.state.value.accountLanguage.status)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}

private class LanguageSettings : SettingsStore {
    override val preferences = MutableStateFlow(ClientPreferences(null, "pl"))
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun setLanguage(language: String) { gate?.await(); preferences.value = preferences.value.copy(language = language) }
    override suspend fun setBackendUrl(origin: String) { preferences.value = preferences.value.copy(backendUrl = origin) }
    override suspend fun updateDisplayPreferences(transform: (DisplayPreferences) -> DisplayPreferences): DisplayPreferences {
        val next = transform(preferences.value.displayPreferences)
        preferences.value = preferences.value.copy(displayPreferences = next); return next
    }
}

private class LanguageSession : SessionClient, AccountLanguageClient {
    override val user = MutableStateFlow<UserDto?>(UserDto("reader", "viewer", "u", effectivePermissions = listOf("manage:self_account")))
    val languages = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var fail = false
    var normalizePrimaryOnce = false
    var walletFailure: Exception? = null
    var walletCalls = 0
    var backendLanguage: String? = null
    val snapshotLanguages = mutableListOf<String?>()
    val snapshotGates = mutableListOf<CompletableDeferred<Unit>?>()
    var snapshots = 0
    var candleReads = 0
    override suspend fun updateLanguage(identity: AccountLanguageIdentity, language: String): AccountLanguageReceipt {
        languages.add(language)
        if (normalizePrimaryOnce) {
            normalizePrimaryOnce = false
            user.value = requireNotNull(user.value).copy(primaryOperatorPublicId = "canonical-desk")
            throw AccountLanguageException(AccountLanguageFailureKind.PREPARATION)
        }
        gate?.await()
        if (fail) throw AccountLanguageException(AccountLanguageFailureKind.UNKNOWN)
        backendLanguage = language
        return AccountLanguageReceipt(identity, language)
    }
    override suspend fun snapshot(): DashboardSnapshot {
        val index = snapshots++
        val profile = requireNotNull(user.value)
        snapshotLanguages.add(backendLanguage)
        snapshotGates.getOrNull(index)?.await()
        return DashboardSnapshot(profile, emptyList(), emptyList(), emptyList(), emptyList())
    }
    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int): List<CandleDto> { candleReads++; return emptyList() }
    override suspend fun restore() = user.value
    override suspend fun login(username: String, password: String, rememberMe: Boolean): UserDto =
        UserDto(username, "viewer", "u", effectivePermissions = listOf("manage:self_account")).also { user.value = it }
    override suspend fun selectWallet(publicId: String?): UserDto {
        walletCalls++
        throw (walletFailure ?: IllegalStateException("Unexpected wallet mutation"))
    }
    override suspend fun logout() { user.value = null }
    override suspend fun orderExchanges(scope: OrderScope): List<String> = error("Unexpected catalogue")
    override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = error("Unexpected catalogue")
    override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = error("Unexpected mutation")
    override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = error("Unexpected mutation")
    override fun close() = Unit
}
