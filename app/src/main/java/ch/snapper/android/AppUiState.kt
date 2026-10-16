package ch.snapper.android

import ch.snapper.android.data.CandleDto
import ch.snapper.android.data.DashboardSnapshot
import ch.snapper.android.data.UserDto
import ch.snapper.android.features.desks.DeskActions
import ch.snapper.android.features.desks.DeskState
import ch.snapper.android.features.market.MarketCatalogActions
import ch.snapper.android.features.market.MarketCatalogState
import ch.snapper.android.features.system.SystemActions
import ch.snapper.android.features.system.SystemState
import ch.snapper.android.features.records.RecordsActions
import ch.snapper.android.features.records.RecordsState
import ch.snapper.android.features.notifications.NotificationDefaultsActions
import ch.snapper.android.features.notifications.NotificationDefaultsState
import ch.snapper.android.features.signals.SignalsActions
import ch.snapper.android.features.signals.SignalsState
import ch.snapper.android.features.accounts.VenueAccountsState
import ch.snapper.android.features.pnl.PnlActions
import ch.snapper.android.features.pnl.PnlState

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
    val marketCatalog: MarketCatalogState = MarketCatalogState(),
    val desks: DeskState = DeskState(),
    val system: SystemState = SystemState(),
    val records: RecordsState = RecordsState(),
    val notificationDefaults: NotificationDefaultsState = NotificationDefaultsState(),
    val signals: SignalsState = SignalsState(),
    val venueAccounts: VenueAccountsState = VenueAccountsState(),
    val pnl: PnlState = PnlState(),
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
    val loadCatalog: () -> Unit = {},
    val marketCatalog: MarketCatalogActions = MarketCatalogActions(),
    val desks: DeskActions = DeskActions({}, {}, {}, {}),
    val system: SystemActions = SystemActions {},
    val records: RecordsActions = RecordsActions(),
    val notificationDefaults: NotificationDefaultsActions = NotificationDefaultsActions({}, { _, _ -> }, { _, _ -> }, {}),
    val signals: SignalsActions = SignalsActions({}, {}),
    val pnl: PnlActions = PnlActions({}, {}, {}, {}),
    val refreshVenueAccounts: () -> Unit = {},
    val venueAccountsVisible: (Boolean) -> Unit = {},
)
