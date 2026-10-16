package ch.snapper.android

import ch.snapper.android.data.AiDecisionPayload
import ch.snapper.android.data.AiDecisionResponse
import ch.snapper.android.features.aireviews.AiDecisionOutcome
import ch.snapper.android.features.aireviews.aiDecisionOutcome
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AiDecisionOutcomeTest {
    private val intent = AiDecisionPayload("approve")

    @Test fun nonSelectedDelegateBeforeFanoutIsKnownDenied() {
        val response = AiDecisionResponse(false, "not_selected_before_fanout", "Fanout has not started.", JsonObject(emptyMap()))
        assertEquals(AiDecisionOutcome.DENIED, aiDecisionOutcome(response, intent))
    }

    @Test fun backendStateRaceAndUnknownCodesRemainUncertain() {
        for (code in listOf("review_state_race", "future_error")) {
            val response = AiDecisionResponse(false, code, "Uncertain outcome.", JsonObject(emptyMap()))
            assertEquals(AiDecisionOutcome.UNKNOWN, aiDecisionOutcome(response, intent))
        }
    }
}
