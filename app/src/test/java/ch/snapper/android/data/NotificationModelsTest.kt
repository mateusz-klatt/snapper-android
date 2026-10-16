package ch.snapper.android.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationModelsTest {
    @Test fun updateBodyCannotChooseAnotherUserOrWallet() {
        val body = Json.encodeToString(UpdateNotificationDefaultPayload("margin_warning", false, "high"))
        assertEquals("""{"alert_type":"margin_warning","enabled":false,"min_priority":"high"}""", body)
        assertFalse(body.contains("user_public_id"))
        assertFalse(body.contains("wallet_public_id"))
    }

    @Test fun backendExtraAlertTypesDoNotBreakListDecoding() {
        val rows = Json.decodeFromString<Envelope<List<NotificationDefaultDto>>>("""{"payload":[{"public_id":"p1","user_public_id":"u1","alert_type":"drift","enabled":false,"min_priority":"high"}]}""").payload
        assertEquals("drift", rows.single().alertType)
    }
}
