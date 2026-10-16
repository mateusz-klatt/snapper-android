package ch.snapper.android.features.desks

import ch.snapper.android.data.DeskDto

enum class DeskIssue { LOAD, INVALID_USERNAME, PREPARATION, REJECTED, UNKNOWN }

data class DeskState(
    val desks: List<DeskDto> = emptyList(),
    val primaryOperatorPublicId: String? = null,
    val selectedDeskPublicId: String? = null,
    val username: String = "",
    val canManage: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val submitting: Boolean = false,
    val succeeded: Boolean = false,
    val issue: DeskIssue? = null,
) {
    val canSubmit: Boolean get() = canManage && loaded && !loading && !submitting &&
        issue != DeskIssue.UNKNOWN && desks.any { it.publicId == selectedDeskPublicId }
}

data class DeskActions(
    val reload: () -> Unit,
    val selectDesk: (String) -> Unit,
    val setUsername: (String) -> Unit,
    val attach: () -> Unit,
)
