package ch.snapper.android

import ch.snapper.android.features.accountlanguage.AccountLanguageState

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
import ch.snapper.android.data.PositionDto
import ch.snapper.android.features.operations.ProcessesActions
import ch.snapper.android.features.operations.ProcessesState
import ch.snapper.android.features.aireviews.AiReviewsActions
import ch.snapper.android.features.aireviews.AiReviewsState
import ch.snapper.android.features.positionactions.PositionActionActions
import ch.snapper.android.features.positionactions.PositionActionKind
import ch.snapper.android.features.positionactions.PositionActionState
import ch.snapper.android.data.DisplayPreferences
import ch.snapper.android.data.FinancialColorPreference
import ch.snapper.android.data.LiveMarketFrame
import ch.snapper.android.features.live.LiveConnectionStatus
import ch.snapper.android.features.marketdiagnostics.MarketDiagnosticsState
import ch.snapper.android.features.marketdiagnostics.MarketDiagnosticsActions
import ch.snapper.android.features.marketcontext.MarketContextState
import ch.snapper.android.features.marketcontext.MarketContextActions

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
    val liveStatus: LiveConnectionStatus = LiveConnectionStatus.STOPPED,
    val tick: LiveMarketFrame.Tick? = null,
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
    val processes: ProcessesState = ProcessesState(),
    val aiReviews: AiReviewsState = AiReviewsState(),
    val positionActions: PositionActionState = PositionActionState(),
    val displayPreferences: DisplayPreferences = DisplayPreferences(),
    val marketDiagnostics: MarketDiagnosticsState = MarketDiagnosticsState(),
    val marketContext: MarketContextState = MarketContextState(),
    val accountLanguage: AccountLanguageState = AccountLanguageState(),
    val accountLanguageReady: Boolean = true,
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
    val processes: ProcessesActions = ProcessesActions({}, { _, _ -> }, {}, {}),
    val aiReviews: AiReviewsActions = AiReviewsActions(),
    val positionActions: PositionActionActions = PositionActionActions({}, {}, {}, {}, {}, {}),
    val loadProcesses: () -> Unit = {},
    val loadAiReviews: () -> Unit = {},
    val openPositionAction: (PositionDto, PositionActionKind) -> Unit = { _, _ -> },
    val selectCountry: (String) -> Unit = {},
    val selectFinancialColor: (FinancialColorPreference) -> Unit = {},
    val marketVisible: (Boolean) -> Unit = {},
    val selectMarket: (String, String, String) -> Unit = { _, _, _ -> },
    val marketDiagnostics: MarketDiagnosticsActions = MarketDiagnosticsActions({}, {}, {}),
    val marketContext: MarketContextActions = MarketContextActions({}, {}, {}),
    val retryAccountLanguage: () -> Unit = {},
)
