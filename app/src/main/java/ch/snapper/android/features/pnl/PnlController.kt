package ch.snapper.android.features.pnl

import ch.snapper.android.data.PnlClient
import ch.snapper.android.data.PnlSeriesDto
import ch.snapper.android.data.PnlSeriesQuery
import ch.snapper.android.data.WalletDto
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PnlWindow(val days: Long, val minimumGranularity: Int) {
    DAY(1, 0), WEEK(7, 1), MONTH(30, 2), QUARTER(90, 2)
}

data class PnlState(
    val wallet: WalletDto? = null,
    val permitted: Boolean = false,
    val window: PnlWindow = PnlWindow.DAY,
    val granularity: String = "1m",
    val currency: String = "USD",
    val data: PnlSeriesDto? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

data class PnlActions(val refresh: () -> Unit, val window: (PnlWindow) -> Unit, val granularity: (String) -> Unit, val currency: (String) -> Unit)

/** UI-dispatcher confined. The owner resets this feature on every session/backend change. */
class PnlController(private val scope: CoroutineScope, private val client: PnlClient, private val now: () -> Instant = Instant::now) {
    private val mutable = MutableStateFlow(PnlState())
    val state = mutable.asStateFlow()
    private var revision = 0L
    private var requestJob: Job? = null
    private var anchor = now().truncatedTo(ChronoUnit.MICROS)
    val actions = PnlActions(::load, ::window, ::granularity, ::currency)

    fun reset(wallet: WalletDto?, permitted: Boolean) {
        revision++
        requestJob?.cancel()
        anchor = now().truncatedTo(ChronoUnit.MICROS)
        mutable.value = PnlState(wallet = wallet, permitted = permitted)
    }

    fun invalidate() = reset(null, false)

    private fun window(value: PnlWindow) {
        if (value == state.value.window) return
        anchor = now().truncatedTo(ChronoUnit.MICROS)
        val coarse = maxOf(GRANULARITIES.indexOf(state.value.granularity), value.minimumGranularity)
        change(state.value.copy(window = value, granularity = GRANULARITIES[coarse]))
    }

    private fun granularity(value: String) {
        if (value !in GRANULARITIES) return
        change(state.value.copy(granularity = value))
    }

    private fun currency(value: String) {
        if (value in CURRENCIES) change(state.value.copy(currency = value))
    }

    private fun change(value: PnlState) {
        revision++
        mutable.value = value.copy(data = null, failed = false, loading = false)
        load()
    }

    fun load() {
        val current = state.value
        val wallet = current.wallet ?: return
        if (!current.permitted) return
        val request = ++revision
        requestJob?.cancel()
        val query = PnlSeriesQuery(wallet.publicId, if (wallet.isPaper) "paper" else "live", current.granularity,
            anchor.minus(Duration.ofDays(current.window.days)), anchor, current.currency)
        mutable.value = current.copy(loading = true, failed = false)
        requestJob = scope.launch {
            try {
                val data = client.pnlSeries(query)
                require(matches(query, data))
                if (request == revision) mutable.value = mutable.value.copy(data = data, loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (request == revision) mutable.value = mutable.value.copy(loading = false, failed = true)
            }
        }
    }

    companion object {
        val GRANULARITIES = listOf("1m", "5m", "1h", "1d")
        val CURRENCIES = listOf("USD", "PLN", "EUR")
        fun matches(query: PnlSeriesQuery, data: PnlSeriesDto): Boolean =
            query.walletPublicId == data.walletPublicId && query.mode == data.mode &&
                query.granularity == data.granularity && query.valuationCcy == data.valuationCcy &&
                query.from == Instant.parse(data.fromTime) && query.to == Instant.parse(data.toTime) &&
                query.to <= Instant.parse(data.asOf)
    }
}
