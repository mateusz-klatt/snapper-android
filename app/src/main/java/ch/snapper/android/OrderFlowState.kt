package ch.snapper.android

import ch.snapper.android.data.CreateOrderPayload
import ch.snapper.android.data.InstrumentDto
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.OrderScope

enum class OrderOutcome { CREATED, CANCEL_REQUESTED, UNKNOWN }

enum class OrderIssue {
    INVALID_FORM, CATALOG, NETWORK, REJECTED, FORBIDDEN, SESSION_CHANGED,
    UNKNOWN_INSTRUMENT, OBSERVATION_ONLY, CAPS_VIOLATION
}

data class OrderDraft(
    val exchange: String = "",
    val instrumentPublicId: String = "",
    val side: String = "buy",
    val orderType: String = "limit",
    val quantity: String = "",
    val price: String = "",
    val stopPrice: String = "",
    val leverage: String = "",
    val reduceOnly: Boolean = false,
)

data class OrderReview(val body: CreateOrderPayload, val walletLabel: String, val scope: OrderScope)

data class OrderFlowState(
    val draft: OrderDraft? = null,
    val exchanges: List<String> = emptyList(),
    val instruments: List<InstrumentDto> = emptyList(),
    val loadingCatalog: Boolean = false,
    val review: OrderReview? = null,
    val pendingCancel: OrderDto? = null,
    val cancelScope: OrderScope? = null,
    val isSubmitting: Boolean = false,
    val outcome: OrderOutcome? = null,
    val issue: OrderIssue? = null,
    val snapshotRefreshFailed: Boolean = false,
    val isDismissed: Boolean = false,
) {
    val canReview: Boolean get() = !loadingCatalog && !isSubmitting && review == null &&
        outcome != OrderOutcome.UNKNOWN && draft?.let { form ->
            OrderForm.isValid(form, instruments.firstOrNull { it.instrumentPublicId == form.instrumentPublicId })
        } == true
}

data class OrderActions(
    val open: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val updateDraft: (OrderDraft) -> Unit = {},
    val selectExchange: (String) -> Unit = {},
    val review: () -> Unit = {},
    val back: () -> Unit = {},
    val confirm: () -> Unit = {},
    val retry: () -> Unit = {},
    val requestCancel: (OrderDto) -> Unit = {},
    val dismissCancel: () -> Unit = {},
    val confirmCancel: () -> Unit = {},
    val clearResult: () -> Unit = {},
    val checkStatus: () -> Unit = {},
    val retryCatalog: () -> Unit = {},
)
