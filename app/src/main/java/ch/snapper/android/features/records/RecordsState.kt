package ch.snapper.android.features.records

import ch.snapper.android.UiError
import ch.snapper.android.data.ExecutionDto
import ch.snapper.android.data.OrderDto
import java.time.Instant

enum class OrderSegment { OPEN, RECENT, FILLS }

data class RecordsState(
    val segment: OrderSegment = OrderSegment.RECENT,
    val executions: List<ExecutionDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
)

data class RecordsActions(
    val selectSegment: (OrderSegment) -> Unit = {},
    val refreshExecutions: () -> Unit = {},
)

object RecordFilters {
    private val openStatuses = setOf("new", "submitted", "open", "partially_filled")

    fun walletMatches(row: String?, selected: String?): Boolean = selected == null || row == null || row == selected

    fun orders(rows: List<OrderDto>, segment: OrderSegment, wallet: String?): List<OrderDto> {
        val scoped = rows.filter { walletMatches(it.walletPublicId, wallet) }
        return when (segment) {
            OrderSegment.OPEN -> scoped.filter { it.status in openStatuses }
            OrderSegment.RECENT -> scoped.sortedByDescending { timestamp(it.createdAt) }.take(50)
            OrderSegment.FILLS -> emptyList()
        }
    }

    fun fills(rows: List<ExecutionDto>, wallet: String?): List<ExecutionDto> =
        rows.filter { walletMatches(it.walletPublicId, wallet) }

    fun failed(state: RecordsState, error: UiError): RecordsState = state.copy(isLoading = false, error = error)

    fun loaded(state: RecordsState, rows: List<ExecutionDto>): RecordsState =
        state.copy(executions = rows, isLoading = false, error = null)

    private fun timestamp(value: String): Instant = runCatching { Instant.parse(value) }.getOrDefault(Instant.MIN)
}
