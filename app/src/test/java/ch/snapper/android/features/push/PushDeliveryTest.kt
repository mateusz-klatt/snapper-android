package ch.snapper.android.features.push

import ch.snapper.android.data.PushRegistration
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PushDeliveryTest {
    private val bindingId = "00000000-0000-4000-8000-000000000005"
    private fun accepted() = AcceptedPushBinding(identity.backend, identity.scopeDigest(), configuration,
        PushRegistration(REG_ID, "user", INSTALL_ID, bindingId, CONFIG_ID, "fcm_fid", "android", "test.app", "active"), "fid")
    private fun payload() = mapOf("type" to "snapper_alert_hint", "schema_version" to "1", "configuration_id" to CONFIG_ID,
        "binding_id" to bindingId, "registration_public_id" to REG_ID, "alert_public_id" to ALERT_ID,
        "expires_at" to Instant.ofEpochMilli(2000).toString())

    @Test fun notificationPayloadIsRejectedBeforeSdkDispatchIncludingLegacyAliases() {
        assertFalse(PushPayloadPolicy.permits(setOf("gcm.n.e", "body")))
        assertFalse(PushPayloadPolicy.permits(setOf("gcm.notification.e")))
        assertFalse(PushPayloadPolicy.permits(setOf("gcm.n.image")))
        assertTrue(PushPayloadPolicy.permits(payload().keys + "from"))
    }
    @Test fun coldServiceUsesOnlyDurableBindingAndDeduplicatesWithoutAnySessionDependency() {
        val store = MemoryPushStore(); store.accepted = accepted(); val notice = FakeNotice()
        val firstProcess = PushDelivery(store, notice) { 1000 }
        firstProcess.receive(payload(), "123")
        val nextProcess = PushDelivery(store, notice) { 1000 }
        nextProcess.receive(payload(), "123")
        assertEquals(1, notice.shown.size)
        assertEquals(ALERT_ID, notice.shown.single().alertId)
    }
    @Test fun logoutRevocationRemovesConsentAndPreventsOldAndPendingDelivery() {
        val store = MemoryPushStore(); val notice = FakeNotice(); val delivery = PushDelivery(store, notice) { 1000 }
        delivery.accept(accepted()); delivery.receive(payload(), "123"); assertEquals(1, notice.shown.size)
        delivery.invalidate(); delivery.receive(payload(), "123")
        assertNull(store.accepted); assertTrue(notice.shown.isEmpty())
    }
    @Test fun malformedExpiredWrongProjectAndExtraContentAreRejected() {
        val store = MemoryPushStore(); val notice = FakeNotice(); val delivery = PushDelivery(store, notice) { 1000 }
        delivery.accept(accepted())
        val rejected = listOf(payload() + ("schema_version" to "2"), payload() + ("expires_at" to "bad"),
            payload() + ("expires_at" to Instant.ofEpochMilli(1000).toString()),
            payload() + ("expires_at" to Instant.ofEpochMilli(90_000_000).toString()), payload() + ("alert_public_id" to "not-an-id"),
            payload() + ("body" to "Never render this private content"), payload() - "type", payload() + ("binding_id" to ALERT_ID))
        rejected.forEach { delivery.receive(it, "123") }
        delivery.receive(payload(), "other-project"); assertTrue(notice.shown.isEmpty())
        notice.permission = false; delivery.receive(payload(), "123"); assertTrue(notice.shown.isEmpty())
    }
    @Test fun unexpectedLegacyProtocolFailsClosedAndRetainsScopedCleanupWithoutTokenStorage() {
        val store = MemoryPushStore(); val notice = FakeNotice(); val delivery = PushDelivery(store, notice) { 1000 }
        delivery.accept(accepted()); delivery.receive(payload(), "123")
        delivery.unexpectedProtocol()
        assertNull(store.accepted); assertTrue(notice.shown.isEmpty())
        assertEquals(listOf(accepted()), delivery.pendingCleanup())
        delivery.cleaned(accepted()); assertTrue(delivery.pendingCleanup().isEmpty())
    }
    @Test fun failedPersistentClearCannotRestoreAcceptanceOnColdRestart() {
        val durable = MemoryPushStore().apply { accepted = accepted() }
        val broken = object : PushStore by durable {
            override var accepted: AcceptedPushBinding?
                get() = durable.accepted
                set(value) { throw java.io.IOException("Storage unavailable") }
        }
        val guard = MemoryPushDeliveryGuard()
        val notice = FakeNotice()
        val original = PushDelivery(broken, notice, guard) { 1000 }
        original.receive(payload(), "123")
        original.invalidate()
        assertNull(original.binding())
        assertNotNull(durable.accepted)
        assertTrue(guard.blocked())
        val cold = PushDelivery(broken, notice, guard) { 1000 }
        assertNull(cold.binding())
        cold.receive(payload(), "123")
        assertTrue(notice.shown.isEmpty())
    }
    @Test fun replacementBindingCannotReceiveOldHintEvenWhenAccountReturnsToOriginal() {
        val store = MemoryPushStore(); val notice = FakeNotice(); val delivery = PushDelivery(store, notice) { 1000 }
        delivery.accept(accepted()); delivery.invalidate()
        delivery.accept(accepted().copy(registration = accepted().registration.copy(bindingId = ALERT_ID)))
        delivery.receive(payload(), "123"); assertTrue(notice.shown.isEmpty())
    }
}
