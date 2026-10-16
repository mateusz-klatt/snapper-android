package ch.snapper.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.data.SignalDto
import ch.snapper.android.features.signals.SignalsActions
import ch.snapper.android.features.signals.SignalsState
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SignalExportInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val row = SignalDto("one", "BTC", "venue", "buy", BigDecimal("0.8500"), "price, moved",
        "2026-10-16T12:00:00Z", BigDecimal("12.3400"), "chosen")
    private fun state() = mutableStateOf(SignalsState(scopeKey = "actor-a", allowed = true,
        rows = listOf(row, row.copy(publicId = "two", strategyName = "excluded")), selectedStrategy = "chosen"))
    private fun destination() = File.createTempFile("signal-export-", ".csv",
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    private fun render(state: MutableState<SignalsState>, registry: DocumentRegistry) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                LazyColumn { signalsScreen(state.value, SignalsActions({}, {})) }
            }
        }
    }

    @Test fun selectedRowsWriteExactCsvToChosenDocument() {
        val file = destination()
        try {
            val registry = DocumentRegistry(); render(state(), registry)
            compose.onNodeWithTag("signals.export").performScrollTo().performClick()
            compose.runOnIdle { assertEquals("signals.csv", registry.filename); registry.complete(Uri.fromFile(file)) }
            val expected = "instrument,exchange,side,strength,strategy,price,reason,fired_at\r\n" +
                "BTC,venue,buy,0.8500,chosen,12.3400,\"price, moved\",2026-10-16T12:00:00.000Z"
            compose.waitUntil(5_000) { file.readBytes().contentEquals(expected.toByteArray(Charsets.UTF_8)) }
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), file.readBytes())
            compose.onNodeWithTag("signals.export").assertIsEnabled()
        } finally { file.delete() }
    }

    @Test fun cancelledPickerWritesNothingAndAllowsAnotherExport() {
        val file = destination()
        try {
            val registry = DocumentRegistry(); render(state(), registry)
            compose.onNodeWithTag("signals.export").performScrollTo().performClick()
            compose.runOnIdle { registry.complete(null) }
            compose.onNodeWithTag("signals.export").assertIsEnabled()
            assertEquals(0L, file.length())
            compose.onNodeWithTag("signals.export").performClick()
            compose.runOnIdle { registry.complete(Uri.fromFile(file)) }
            compose.waitUntil(5_000) { file.length() > 0 }
        } finally { file.delete() }
    }

    @Test fun changedScopeDiscardsBytesCapturedBeforePickerResult() {
        verifyStaleResult { it.copy(scopeKey = "actor-b") }
    }

    @Test fun revokedPermissionDiscardsBytesCapturedBeforePickerResult() {
        verifyStaleResult { it.copy(allowed = false) }
    }

    private fun verifyStaleResult(change: (SignalsState) -> SignalsState) {
        val file = destination()
        try {
            val registry = DocumentRegistry(); val state = state(); render(state, registry)
            compose.onNodeWithTag("signals.export").performScrollTo().performClick()
            compose.runOnIdle { state.value = change(state.value) }
            compose.waitForIdle()
            compose.runOnIdle { registry.complete(Uri.fromFile(file)) }
            compose.waitForIdle()
            assertEquals(0L, file.length())
            compose.runOnIdle { state.value = state.value.copy(scopeKey = "actor-c", allowed = true) }
            compose.onNodeWithTag("signals.export").performScrollTo().assertIsEnabled()
            assertEquals(0L, file.length())
        } finally { file.delete() }
    }
}

private class DocumentRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry get() = this
    private var pendingCode: Int? = null
    var filename: String? = null
        private set

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
        options: ActivityOptionsCompat?) {
        check(contract is ActivityResultContracts.CreateDocument)
        check(pendingCode == null)
        filename = input as? String
        pendingCode = requestCode
    }

    fun complete(uri: Uri?) {
        val code = checkNotNull(pendingCode)
        pendingCode = null
        dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(it) })
    }
}
