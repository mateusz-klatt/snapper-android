package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Pure checks used inside the repository's session lock, immediately before single-dispatch commands. */
internal object ActionTransport {
    private val json = Json { ignoreUnknownKeys = true }
    fun processTarget(expected: ConfiguredProcessDto, fresh: ConfiguredProcessDto, remote: Boolean) {
        require(expected.name.isNotBlank() && expected.name == fresh.name)
        require(fresh.kind == "instance" && fresh.role !in setOf("strategy", "backtest"))
        require(expected.role == fresh.role && expected.kind == fresh.kind)
        require(expected.managedRemotely == fresh.managedRemotely && fresh.managedRemotely == remote)
        require(expected.coordinator == fresh.coordinator && expected.walletPublicId == fresh.walletPublicId)
    }

    fun desiredState(payload: ProcessDesiredStatePayload, target: ConfiguredProcessDto) {
        require(payload.action in setOf("enable", "disable", "restart"))
        if (payload.action == "restart") {
            require(target.enabled)
            require(payload.restartNonce?.matches(Regex("[A-Za-z0-9_-]{8,64}")) == true)
        }
    }

    fun positionTarget(target: PositionActionTarget, fresh: PositionDto, wallet: WalletDto) {
        require(target.positionPublicId == fresh.publicId && target.walletPublicId == fresh.walletPublicId)
        require(target.instrumentPublicId.isNotBlank() && target.instrumentPublicId == fresh.instrumentPublicId)
        require(target.instrument == fresh.instrument && target.exchange == fresh.exchange)
        require(target.cyclePublicId == fresh.positionCyclePublicId.orEmpty())
        require(target.quantity.compareTo(fresh.quantity) == 0 && fresh.quantity.abs() > BigDecimal("1e-12"))
        require(wallet.publicId == fresh.walletPublicId && fresh.mode == if (wallet.isPaper) "paper" else "live")
    }

    fun positionIntent(intent: PositionActionIntent, fresh: PositionDto, wallet: WalletDto) {
        when (intent) {
            is PositionActionIntent.Order -> reduceOrder(intent.payload, fresh, wallet)
            is PositionActionIntent.Bracket -> bracket(intent, fresh)
            is PositionActionIntent.Trailing -> trailing(intent, fresh)
        }
    }

    private fun reduceOrder(payload: CreateOrderPayload, fresh: PositionDto, wallet: WalletDto) {
        require(payload.walletPublicId == fresh.walletPublicId && payload.instrumentPublicId == fresh.instrumentPublicId)
        require(payload.instrument == fresh.instrument && payload.exchange == fresh.exchange)
        require(payload.mode == if (wallet.isPaper) "paper" else "live")
        require(payload.side == if (fresh.quantity.signum() > 0) "sell" else "buy")
        require(OrderNumbers.isSupported(payload.quantity) && payload.quantity <= fresh.quantity.abs())
        require(payload.reduceOnly && payload.orderType == "market" && payload.idempotencyKey.isNotBlank())
        require(payload.price == null && payload.stopPrice == null && payload.leverage == null)
        require(payload.timeInForce == "GTC" && !payload.postOnly)
    }

    private fun planTarget(target: PositionActionTarget, cycle: String, key: String, fresh: PositionDto): BigDecimal {
        require(cycle.isNotBlank() && cycle == fresh.positionCyclePublicId && key.isNotBlank())
        val entry = requireNotNull(fresh.averagePrice)
        require(OrderNumbers.isSupported(entry) && target.entryPrice?.compareTo(entry) == 0)
        return entry
    }

    private fun bracket(intent: PositionActionIntent.Bracket, fresh: PositionDto) {
        val payload = intent.payload
        val entry = planTarget(intent.target, payload.cyclePublicId, payload.idempotencyKey, fresh)
        require(payload.stopLoss != null || payload.takeProfit != null)
        payload.stopLoss?.let {
            require(OrderNumbers.isSupported(it))
            require(if (fresh.quantity.signum() > 0) it < entry else it > entry)
        }
        payload.takeProfit?.let {
            require(OrderNumbers.isSupported(it))
            require(if (fresh.quantity.signum() > 0) it > entry else it < entry)
        }
    }

    private fun trailing(intent: PositionActionIntent.Trailing, fresh: PositionDto) {
        val payload = intent.payload
        planTarget(intent.target, payload.cyclePublicId, payload.idempotencyKey, fresh)
        require(OrderNumbers.isSupported(payload.distance) && payload.distance < BigDecimal(100))
        require(payload.minimumLock >= BigDecimal.ZERO && payload.minimumLock < BigDecimal(100))
        require(payload.minimumLock.signum() == 0 || OrderNumbers.isSupported(payload.minimumLock))
    }

    fun aiError(body: String?): AiDecisionResponse? = runCatching {
        val detail = json.parseToJsonElement(requireNotNull(body)).jsonObject.getValue("detail")
        json.decodeFromJsonElement(AiDecisionResponse.serializer(), detail)
    }.getOrNull()
}
