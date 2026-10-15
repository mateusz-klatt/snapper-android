package ch.snapper.android

import ch.snapper.android.data.CandleDto
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.UserDto

enum class UiError {
    INVALID_ORIGIN, INVALID_CREDENTIALS, SESSION_EXPIRED, NETWORK, SERVICE, UNKNOWN
}

data class MarketState(
    val exchange: String = "kraken",
    val instrument: String = "BTC-USD",
    val timeframe: String = "1h",
    val candles: List<CandleDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
)

data class AppUiState(
    val backendUrl: String = "",
    val language: String = "",
    val user: UserDto? = null,
    val snapshot: DashboardSnapshot? = null,
    val isLoading: Boolean = false,
    val isRestoring: Boolean = true,
    val error: UiError? = null,
    val market: MarketState = MarketState(),
    val orders: OrderFlowState = OrderFlowState(),
)

data class AppActions(
    val login: (String, String) -> Unit,
    val refresh: () -> Unit,
    val selectWallet: (String?) -> Unit,
    val logout: () -> Unit,
    val saveBackend: (String) -> Unit,
    val selectLanguage: (String) -> Unit,
    val clearError: () -> Unit,
    val loadMarket: (String, String, String) -> Unit,
    val orders: OrderActions = OrderActions(),
)
