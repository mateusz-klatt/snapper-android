package ch.snapper.android

import androidx.lifecycle.ViewModelStore
import ch.snapper.android.data.*
import ch.snapper.android.features.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnapperViewModelPushTest {
    @Test fun coldHintWaitsForAuthenticationAndLanguageBeforeReadingPrivateAlert() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        val client = PushSession().apply { languageGate = CompletableDeferred() }
        val platform = FakePlatform()
        try {
            val model = SnapperViewModel(PushSettings(), { client }, ORIGIN, false, platform)
            store.put("vm", model)
            accept(platform, client.profile)
            model.openPushHint(hint())
            runCurrent()
            assertFalse(model.state.value.accountLanguageReady)
            assertEquals(0, client.push.details)
            client.languageGate!!.complete(Unit); advanceUntilIdle()
            assertEquals(1, client.push.details)
            assertEquals(ALERT_ID, model.state.value.push.alert?.publicId)
            assertEquals(0, platform.registrations)
        } finally { client.languageGate?.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun repeatedForegroundHintsReuseImmutableClientAndDismissOnlyLocalDetail() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = PushSession(); val platform = FakePlatform()
            val model = SnapperViewModel(PushSettings(), { client }, ORIGIN, false, platform)
            store.put("vm", model); advanceUntilIdle(); accept(platform, client.profile)
            val bindings = client.bindings.size
            model.openPushHint(hint()); advanceUntilIdle()
            assertEquals("Private detail", model.state.value.push.alert?.body)
            model.actions.push.dismissAlert(); runCurrent(); assertNull(model.state.value.push.alert)
            model.openPushHint(hint()); advanceUntilIdle()
            assertEquals(2, client.push.details)
            assertEquals(bindings, client.bindings.size)
            assertEquals(0, client.push.posts)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun heldPrivateDetailCannotReturnAfterWalletAccountOrPermissionChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            for (change in listOf<(UserDto) -> UserDto>(
                { it.copy(activeWalletPublicId = "other-wallet") },
                { it.copy(publicId = "other-user", username = "other") },
                { it.copy(effectivePermissions = emptyList()) },
            )) {
                val client = PushSession(); val platform = FakePlatform()
                val model = SnapperViewModel(PushSettings(), { client }, ORIGIN, false, platform)
                store.put("vm", model); advanceUntilIdle(); accept(platform, client.profile)
                val gate = CompletableDeferred<Unit>(); client.push.detailGate = gate
                model.openPushHint(hint()); runCurrent(); assertEquals(1, client.push.details)
                client.user.value = change(client.profile); runCurrent()
                gate.complete(Unit); advanceUntilIdle()
                assertNull(model.state.value.push.alert)
                assertNull(platform.delivery.binding())
                model.openPushHint(hint()); advanceUntilIdle()
                assertEquals(1, client.push.details)
            }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun logoutDiscardsHeldDetailAndQueuedHintBeforeAnotherLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        val client = PushSession(); val gate = CompletableDeferred<Unit>()
        try {
            val platform = FakePlatform()
            val model = SnapperViewModel(PushSettings(), { client }, ORIGIN, false, platform)
            store.put("vm", model); advanceUntilIdle(); accept(platform, client.profile)
            client.push.detailGate = gate; model.openPushHint(hint()); runCurrent()
            model.logout(); runCurrent(); gate.complete(Unit); advanceUntilIdle()
            assertNull(model.state.value.user); assertNull(model.state.value.push.alert)
            assertNull(platform.delivery.binding())
            model.login("viewer", "synthetic"); advanceUntilIdle()
            assertEquals(1, client.push.details); assertNull(model.state.value.push.alert)
        } finally { gate.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun wrongBackendBindingAndMissingReadPermissionNeverReadPrivateDetail() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val client = PushSession(); val platform = FakePlatform()
            val model = SnapperViewModel(PushSettings(), { client }, ORIGIN, false, platform)
            store.put("vm", model); advanceUntilIdle()
            accept(platform, client.profile, "https://unrelated.invalid")
            model.openPushHint(hint()); advanceUntilIdle()
            assertNull(platform.delivery.binding()); assertEquals(0, client.push.details)
            client.user.value = client.profile.copy(effectivePermissions = emptyList()); advanceUntilIdle()
            accept(platform, requireNotNull(client.user.value))
            model.openPushHint(hint()); advanceUntilIdle()
            assertEquals(0, client.push.details); assertNull(model.state.value.push.alert)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    private fun hint() = PushHint(CONFIG_ID, "binding", REG_ID, ALERT_ID, 2000)
    private fun accept(platform: FakePlatform, user: UserDto, backend: String = ORIGIN) {
        val registration = PushRegistration(REG_ID, user.publicId, INSTALL_ID, "binding", CONFIG_ID, "fcm_fid", "android", "test.app", "active")
        platform.delivery.accept(AcceptedPushBinding(backend, PushIdentity(backend, 0, OrderScope.from(user)).scopeDigest(), configuration, registration, "fid-value"))
        platform.store.accepted = platform.delivery.binding()
    }
    companion object { private const val ORIGIN = "https://example.test" }
}

private class PushSettings : SettingsStore {
    override val preferences = MutableStateFlow(ClientPreferences(null, "en"))
    override suspend fun setLanguage(language: String) { preferences.value = preferences.value.copy(language = language) }
    override suspend fun setBackendUrl(origin: String) { preferences.value = preferences.value.copy(backendUrl = origin) }
    override suspend fun updateDisplayPreferences(transform: (DisplayPreferences) -> DisplayPreferences): DisplayPreferences =
        transform(preferences.value.displayPreferences).also { preferences.value = preferences.value.copy(displayPreferences = it) }
}
private class PushSession : SessionClient, PushClient, AccountLanguageClient {
    val profile = UserDto("viewer", "viewer", "user", effectivePermissions = listOf("read:notifications"))
    override val user = MutableStateFlow<UserDto?>(profile)
    val push = FakePushClient()
    val bindings = mutableListOf<PushIdentity>()
    var languageGate: CompletableDeferred<Unit>? = null
    override fun bindPush(identity: PushIdentity): PushBinding { bindings += identity; return PushBinding(identity, push) }
    override suspend fun updateLanguage(identity: AccountLanguageIdentity, language: String): AccountLanguageReceipt { languageGate?.await(); return AccountLanguageReceipt(identity, language) }
    override suspend fun snapshot() = DashboardSnapshot(requireNotNull(user.value), emptyList(), emptyList(), emptyList(), emptyList())
    override suspend fun restore() = user.value
    override suspend fun login(username: String, password: String, rememberMe: Boolean) = profile.also { user.value = it }
    override suspend fun logout() { user.value = null }
    override fun close() = Unit
    override suspend fun candles(exchange: String, instrument: String, timeframe: String, limit: Int) = emptyList<CandleDto>()
    override suspend fun selectWallet(publicId: String?) = requireNotNull(user.value).copy(activeWalletPublicId = publicId).also { user.value = it }
    override suspend fun orderExchanges(scope: OrderScope): List<String> = error("Unexpected order catalogue")
    override suspend fun orderInstruments(scope: OrderScope, exchange: String): List<InstrumentDto> = error("Unexpected order catalogue")
    override suspend fun createOrder(scope: OrderScope, payload: CreateOrderPayload): ExecutionPlanDto = error("Unexpected trade")
    override suspend fun cancelOrder(scope: OrderScope, order: OrderDto): ExecutionPlanDto = error("Unexpected trade")
    override suspend fun pushConfiguration() = error("Unbound push call")
    override suspend fun pushRegistrationState(configurationId: String, installationId: String): PushRegistrationState = error("Unbound push call")
    override suspend fun registerPush(command: PushRegistrationCommand): PushRegistration = error("Unbound push call")
    override suspend fun pushInventory(): List<PushRegistration> = error("Unbound push call")
    override suspend fun pushPreferences(registrationId: String): List<PushPreference> = error("Unbound push call")
    override suspend fun revokePush(registrationId: String): Unit = error("Unbound push call")
    override suspend fun pushAlert(alertId: String): AlertDto = error("Unbound push call")
}
