package ch.snapper.android

import ch.snapper.android.data.CreateOrderPayload
import ch.snapper.android.data.InstrumentDto
import ch.snapper.android.data.OrderDto
import ch.snapper.android.data.OrderNumbers
import ch.snapper.android.data.UserDto
import ch.snapper.android.data.WalletDto
import java.math.BigDecimal

object OrderForm {
    val orderTypes = listOf("market", "limit", "stop", "stop_limit")

    fun needsPrice(type: String): Boolean = type == "limit" || type == "stop_limit"
    fun needsStopPrice(type: String): Boolean = type == "stop" || type == "stop_limit"

    fun positiveNumber(text: String): BigDecimal? {
        val normalized = text.trim().replace(',', '.')
        if (normalized.length > 100) return null
        val value = normalized.toBigDecimalOrNull() ?: return null
        return value.takeIf { OrderNumbers.isSupported(it) }
    }

    fun isValid(draft: OrderDraft, instrument: InstrumentDto?): Boolean {
        if (instrument == null || !instrument.canTrade || !instrument.instrumentResolved ||
            instrument.exchange != draft.exchange || instrument.instrumentPublicId != draft.instrumentPublicId ||
            draft.side !in setOf("buy", "sell") || draft.orderType !in orderTypes) return false
        return positiveNumber(draft.quantity) != null &&
            (!needsPrice(draft.orderType) || positiveNumber(draft.price) != null) &&
            (!needsStopPrice(draft.orderType) || positiveNumber(draft.stopPrice) != null) &&
            (draft.leverage.isBlank() || draft.leverage.trim().toIntOrNull()?.let { it > 0 } == true)
    }

    fun build(draft: OrderDraft, instrument: InstrumentDto?, wallet: WalletDto, key: String): CreateOrderPayload? {
        if (!isValid(draft, instrument) || instrument == null) return null
        val quantity = positiveNumber(draft.quantity) ?: return null
        val price = if (needsPrice(draft.orderType)) positiveNumber(draft.price) ?: return null else null
        val stop = if (needsStopPrice(draft.orderType)) positiveNumber(draft.stopPrice) ?: return null else null
        val leverage = if (draft.leverage.isBlank()) null else draft.leverage.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        return CreateOrderPayload(
            instrument = instrument.symbol, instrumentPublicId = instrument.instrumentPublicId,
            exchange = instrument.exchange, mode = if (wallet.isPaper) "paper" else "live",
            side = draft.side, orderType = draft.orderType, quantity = quantity,
            price = price, stopPrice = stop, walletPublicId = wallet.publicId,
            idempotencyKey = key, leverage = leverage, reduceOnly = draft.reduceOnly,
        )
    }

    fun canCancel(order: OrderDto, user: UserDto?): Boolean =
        user?.hasPermission("cancel:orders") == true && !order.planPublicId.isNullOrBlank() &&
            order.status in setOf("new", "submitted", "open", "partially_filled") &&
            user.activeWalletPublicId != null && order.walletPublicId == user.activeWalletPublicId
}
