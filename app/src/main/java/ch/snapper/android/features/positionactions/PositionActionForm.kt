package ch.snapper.android.features.positionactions

import ch.snapper.android.data.*
import java.math.BigDecimal

enum class PositionActionKind { CLOSE, REDUCE, BRACKET, TRAILING }
data class PositionActionDraft(val kind: PositionActionKind, val percent: String = "25", val stopLoss: String = "", val takeProfit: String = "", val distance: String = "", val minimumLock: String = "")

object PositionActionForm {
    private fun number(text: String): BigDecimal? = text.trim().replace(',', '.').takeIf { it.length in 1..128 }?.toBigDecimalOrNull()
    private fun positive(text: String): BigDecimal? = number(text)?.takeIf(OrderNumbers::isSupported)

    private val positionZeroThreshold = BigDecimal("0.000000000001")

    fun eligible(scope: OrderScope, wallet: WalletDto, target: PositionActionTarget, kind: PositionActionKind): Boolean {
        val identified = "create:orders" in scope.effectivePermissions && scope.activeWalletPublicId == wallet.publicId &&
            target.walletPublicId == wallet.publicId && target.positionPublicId.isNotBlank() &&
            target.instrumentPublicId.isNotBlank() && target.instrument.isNotBlank() && target.exchange.isNotBlank()
        if (!identified || !OrderNumbers.isSupported(target.quantity.abs()) || target.quantity.abs() <= positionZeroThreshold) return false
        return when (kind) {
            PositionActionKind.CLOSE, PositionActionKind.REDUCE -> true
            PositionActionKind.BRACKET, PositionActionKind.TRAILING -> target.cyclePublicId.isNotBlank() && target.entryPrice?.let(OrderNumbers::isSupported) == true
        }
    }

    fun build(scope: OrderScope, wallet: WalletDto, target: PositionActionTarget, draft: PositionActionDraft, key: String): PositionActionIntent? {
        if (!eligible(scope, wallet, target, draft.kind) || key.isBlank()) return null
        return when (draft.kind) {
            PositionActionKind.CLOSE, PositionActionKind.REDUCE -> order(wallet, target, draft, key)
            PositionActionKind.BRACKET -> bracket(target, draft, key)
            PositionActionKind.TRAILING -> trailing(target, draft, key)
        }
    }

    private fun order(wallet: WalletDto, target: PositionActionTarget, draft: PositionActionDraft, key: String): PositionActionIntent? {
        val percent = if (draft.kind == PositionActionKind.CLOSE) BigDecimal(100) else positive(draft.percent) ?: return null
        if (percent > BigDecimal(100)) return null
        val quantity = target.quantity.abs().multiply(percent).movePointLeft(2)
        if (!OrderNumbers.isSupported(quantity) || quantity > target.quantity.abs()) return null
        return PositionActionIntent.Order(target, CreateOrderPayload(target.instrument, target.instrumentPublicId, target.exchange,
            if (wallet.isPaper) "paper" else "live", if (target.quantity.signum() > 0) "sell" else "buy", "market", quantity,
            walletPublicId = wallet.publicId, idempotencyKey = key, reduceOnly = true))
    }

    private fun bracket(target: PositionActionTarget, draft: PositionActionDraft, key: String): PositionActionIntent? {
        if (target.entryPrice?.let(OrderNumbers::isSupported) != true) return null
        val stop = positive(draft.stopLoss)
        val take = positive(draft.takeProfit)
        if ((draft.stopLoss.isNotBlank() && stop == null) || (draft.takeProfit.isNotBlank() && take == null) || (stop == null && take == null)) return null
        return PositionActionIntent.Bracket(target, PositionBracketPayload(target.cyclePublicId, stop, take, key))
    }

    private fun trailing(target: PositionActionTarget, draft: PositionActionDraft, key: String): PositionActionIntent? {
        if (target.entryPrice?.let(OrderNumbers::isSupported) != true) return null
        val distance = positive(draft.distance) ?: return null
        val lock = if (draft.minimumLock.isBlank()) BigDecimal.ZERO else number(draft.minimumLock) ?: return null
        if (lock.signum() != 0 && !OrderNumbers.isSupported(lock)) return null
        return PositionActionIntent.Trailing(target, PositionTrailingPayload(target.cyclePublicId, distance, lock, key))
    }
}
