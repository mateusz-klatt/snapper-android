package ch.snapper.android.features.accountlanguage

import ch.snapper.android.data.*
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLanguageControllerTest {
    private val identity = AccountLanguageIdentity("https://backend.example", 1,
        OrderScope("u", "viewer", null, emptySet(), "desk", "delegate"))

    @Test fun latestChoiceWaitsForNoncancellableWriteAndSkipsIntermediateChoice() = runTest {
        val held = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val events = mutableListOf<String>()
        val client = AccountLanguageClient { scope, language ->
            writes += language
            if (language == "pl") withContext(NonCancellable) { held.await() }
            AccountLanguageReceipt(scope, language)
        }
        val controller = AccountLanguageController(this, { AccountLanguageBinding(identity, client) }, { _, language -> events += language })
        controller.choose("pl"); runCurrent()
        controller.choose("fr"); controller.choose("ar"); runCurrent()
        assertEquals(listOf("pl"), writes)
        val settled = async { controller.awaitSettled() }; runCurrent(); assertFalse(settled.isCompleted)
        held.complete(Unit); runCurrent(); settled.await()
        assertEquals(listOf("pl", "ar"), writes)
        assertEquals(listOf("ar"), events)
        assertEquals("ar", controller.state.value.accepted)
    }

    @Test fun failureKeepsLocalChoiceAndRequiresExplicitRetryWithoutLeakingError() = runTest {
        var calls = 0
        val client = AccountLanguageClient { scope, language ->
            if (++calls == 1) throw java.io.IOException("private server detail")
            AccountLanguageReceipt(scope, language)
        }
        val controller = AccountLanguageController(this, { AccountLanguageBinding(identity, client) }, { _, _ -> })
        controller.choose("fr"); runCurrent()
        assertEquals("fr", controller.state.value.desired)
        assertEquals(AccountLanguageFailureKind.UNKNOWN, controller.state.value.failure)
        assertNull(controller.state.value.accepted)
        controller.choose("fr"); runCurrent(); assertEquals(1, calls)
        controller.retry(); runCurrent()
        assertEquals(2, calls); assertEquals(AccountLanguageStatus.ACCEPTED, controller.state.value.status)
        assertFalse(controller.state.value.toString().contains("private server detail"))
    }

    @Test fun actorReplacementSerializesBehindOldWriteAndSuppressesItsReceipt() = runTest {
        val held = CompletableDeferred<Unit>()
        val writes = mutableListOf<AccountLanguageIdentity>()
        val events = mutableListOf<AccountLanguageIdentity>()
        val client = AccountLanguageClient { actor, language ->
            writes += actor
            if (actor == identity) withContext(NonCancellable) { held.await() }
            AccountLanguageReceipt(actor, language)
        }
        var owner: AccountLanguageBinding? = AccountLanguageBinding(identity, client)
        val controller = AccountLanguageController(this, { owner }, { actor, _ -> events += actor })
        controller.choose("pl"); runCurrent()
        controller.invalidate()
        val next = identity.copy(sessionGeneration = 2, actor = identity.actor.copy(primaryOperatorPublicId = "other"))
        owner = AccountLanguageBinding(next, client)
        controller.choose("fr"); runCurrent(); assertEquals(1, writes.size)
        held.complete(Unit); runCurrent()
        assertEquals(listOf(identity, next), writes)
        assertEquals(listOf(next), events)
        owner = null; controller.invalidate(); controller.retry(); runCurrent()
        assertEquals(AccountLanguageState(), controller.state.value)
        assertEquals(2, writes.size)
    }

    @Test fun queuedChoiceCannotCrossBackendOrDelegateBoundaryWithoutExplicitNewChoice() = runTest {
        var owner: AccountLanguageBinding? = AccountLanguageBinding(identity, AccountLanguageClient { _, _ -> error("Must not dispatch") })
        val controller = AccountLanguageController(this, { owner }, { _, _ -> error("Must not publish") })
        controller.choose("pl")
        owner = owner!!.copy(identity = identity.copy(backend = "https://other.example", actor = identity.actor.copy(delegatePublicId = "other")))
        runCurrent()
        assertNull(controller.state.value.accepted)
        controller.invalidate()
    }

    @Test fun mismatchedReceiptFailsClosedButDescriptionRefreshFailurePreservesAcceptedPreference() = runTest {
        var mismatch = true
        val client = AccountLanguageClient { actor, language -> AccountLanguageReceipt(actor, if (mismatch) "en" else language) }
        val controller = AccountLanguageController(this, { AccountLanguageBinding(identity, client) }, { _, _ -> error("Descriptions unavailable") })
        controller.choose("pl"); runCurrent()
        assertEquals(AccountLanguageStatus.FAILED, controller.state.value.status)
        mismatch = false; controller.retry(); runCurrent()
        assertEquals(AccountLanguageStatus.ACCEPTED, controller.state.value.status)
        assertEquals("pl", controller.state.value.accepted)
    }

    @Test fun innerCancellationDrainsOnlyTheNeverDispatchedSuccessor() = runTest {
        val held = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val client = AccountLanguageClient { actor, language ->
            writes += language
            if (language == "pl") {
                held.await()
                throw CancellationException("Inner request cancelled")
            }
            AccountLanguageReceipt(actor, language)
        }
        val controller = AccountLanguageController(this, { AccountLanguageBinding(identity, client) }, { _, _ -> })
        controller.choose("pl"); runCurrent()
        controller.choose("fr"); runCurrent()
        held.complete(Unit); runCurrent(); controller.awaitSettled()
        assertEquals(listOf("pl", "fr"), writes)
        assertEquals(AccountLanguageStatus.ACCEPTED, controller.state.value.status)
        assertEquals("fr", controller.state.value.accepted)
    }

    @Test fun cancelledCurrentRequestRemainsUnknownWithoutReplay() = runTest {
        var writes = 0
        val client = AccountLanguageClient { _, _ -> writes++; throw CancellationException("Inner cancellation") }
        val controller = AccountLanguageController(this, { AccountLanguageBinding(identity, client) }, { _, _ -> })
        controller.choose("pl"); runCurrent(); controller.awaitSettled()
        controller.choose("pl"); runCurrent()
        assertEquals(1, writes)
        assertEquals(AccountLanguageStatus.FAILED, controller.state.value.status)
        assertEquals(AccountLanguageFailureKind.UNKNOWN, controller.state.value.failure)
    }

    @Test fun parentCancellationNeverDrainsTheQueuedSuccessor() = runTest {
        val ownerJob = Job(coroutineContext[Job])
        val ownerScope = CoroutineScope(coroutineContext + ownerJob)
        val held = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val client = AccountLanguageClient { actor, language ->
            writes += language
            held.await()
            AccountLanguageReceipt(actor, language)
        }
        val controller = AccountLanguageController(ownerScope, { AccountLanguageBinding(identity, client) }, { _, _ -> })
        controller.choose("pl"); runCurrent()
        controller.choose("fr"); ownerScope.cancel(); runCurrent()
        assertEquals(listOf("pl"), writes)
        assertTrue(ownerJob.isCancelled)
    }

    @Test fun acceptedLanguageSurvivesSameOwnerPendingAndRejectedChoicesButNotRebind() = runTest {
        val held = CompletableDeferred<Unit>()
        val client = AccountLanguageClient { actor, language ->
            if (language == "fr") {
                held.await()
                throw AccountLanguageException(AccountLanguageFailureKind.REJECTED)
            }
            AccountLanguageReceipt(actor, language)
        }
        var owner = AccountLanguageBinding(identity, client)
        val controller = AccountLanguageController(this, { owner }, { _, _ -> })
        controller.choose("pl"); runCurrent()
        controller.choose("fr"); runCurrent()
        assertEquals("pl", controller.state.value.accepted)
        assertEquals("fr", controller.state.value.desired)
        held.complete(Unit); runCurrent()
        assertEquals("pl", controller.state.value.accepted)
        assertEquals(AccountLanguageStatus.FAILED, controller.state.value.status)
        owner = owner.copy(identity = identity.copy(sessionGeneration = 2))
        controller.choose("de")
        assertNull(controller.state.value.accepted)
        runCurrent()
        assertEquals("de", controller.state.value.accepted)
        controller.invalidate()
        assertNull(controller.state.value.accepted)
    }

    @Test fun systemAliasesAndScriptsResolveToSupportedCatalogsInPreferenceOrder() {
        val expected = mapOf(
            "sr-Cyrl-RS" to "sr-Latn",
            "no-NO" to "nb",
            "nb-NO" to "nb",
            "tl-PH" to "fil",
            "fil-PH" to "fil",
            "zh-Hant-CN" to "zh-Hant",
            "zh-HK" to "zh-Hant",
            "zh-MO" to "zh-Hant",
            "zh-CN" to "zh-Hans",
            "fr-FR-u-nu-arab" to "fr",
        )
        expected.forEach { (deviceTag, catalog) ->
            val locales = listOf(Locale.forLanguageTag("zz"), Locale.forLanguageTag(deviceTag), Locale.ENGLISH)
            assertEquals(deviceTag, catalog, AccountLanguageResolver.resolve("", locales))
            assertEquals("Explicit choice must override $deviceTag", "pl", AccountLanguageResolver.resolve("pl", locales))
        }
    }

    @Test fun systemChoiceResolvesCanonicalCatalogWithoutDisplayCountryInput() {
        assertEquals("pl", AccountLanguageResolver.resolve("pl", listOf(Locale.FRANCE)))
        assertEquals("zh-Hant", AccountLanguageResolver.resolve("", listOf(Locale.forLanguageTag("zh-TW"))))
        assertEquals("zh-Hans", AccountLanguageResolver.resolve("", listOf(Locale.forLanguageTag("zh-Hans-TW"))))
        assertEquals("pt-BR", AccountLanguageResolver.resolve("", listOf(Locale.forLanguageTag("pt-PT"))))
        assertEquals("ar", AccountLanguageResolver.resolve("", listOf(Locale.forLanguageTag("zz"), Locale.forLanguageTag("ar-EG"))))
        assertEquals("en", AccountLanguageResolver.resolve("", emptyList()))
        assertNull(AccountLanguageResolver.resolve("FR", emptyList()))
    }
}
