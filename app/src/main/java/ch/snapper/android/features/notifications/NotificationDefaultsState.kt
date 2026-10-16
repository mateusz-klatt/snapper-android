package ch.snapper.android.features.notifications

enum class NotificationDefaultIssue { LOAD, REJECTED, SCOPE_CHANGED, UNKNOWN }

/** The five notification categories currently exposed by the iOS preferences UI. */
val notificationDefaultTypes = listOf("order_fill_full", "order_rejected", "position_stop_loss_fired", "margin_warning", "critical_system_error")
val notificationPriorities = listOf("low", "medium", "high")

data class NotificationDefaultRow(
    val alertType: String,
    val enabled: Boolean = true,
    val minPriority: String = "medium",
    val inherited: Boolean = true,
    val dirty: Boolean = false,
    val pending: Boolean = false,
    val saved: Boolean = false,
    val issue: NotificationDefaultIssue? = null,
) {
    val editable: Boolean get() = !pending && issue != NotificationDefaultIssue.UNKNOWN
    val canSave: Boolean get() = editable && dirty && minPriority in notificationPriorities
}

data class NotificationDefaultsState(
    val rows: List<NotificationDefaultRow> = notificationDefaultTypes.map(::NotificationDefaultRow),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val active: Boolean = false,
    val loadFailed: Boolean = false,
)

data class NotificationDefaultsActions(
    val refresh: () -> Unit,
    val setEnabled: (String, Boolean) -> Unit,
    val setPriority: (String, String) -> Unit,
    val save: (String) -> Unit,
)
