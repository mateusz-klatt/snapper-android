package ch.snapper.android.features.accounts

import ch.snapper.android.data.VenueAccountDto
import java.time.Duration
import java.time.Instant
import java.math.BigDecimal

data class AccountTruth(val status: String, val authoritative: Boolean, val authorityExpired: Boolean = false, val pollingStalled: Boolean = false) {
    companion object {
        fun derive(account: VenueAccountDto, now: Instant, fetched: Instant?): AccountTruth {
            if (account.effectiveStatus != "observed") return AccountTruth(account.effectiveStatus, false)
            if (account.mode == "paper") return AccountTruth("stale", false)
            if (!account.isAuthoritative) return AccountTruth("stale", false)
            val deadline = parse(account.authoritativeUntil)
            val expired = deadline == null || now >= deadline || badObservation(account.balanceObservedAt, now) ||
                (account.positionStatus == "observed" && badObservation(account.positionObservedAt, now))
            val stalled = fetched == null || fetched <= Instant.EPOCH || fetched > now || Duration.between(fetched, now) > Duration.ofSeconds(15)
            return if (expired || stalled) AccountTruth("stale", false, expired, stalled) else AccountTruth("observed", true)
        }

        private fun badObservation(value: String?, now: Instant): Boolean {
            val observed = parse(value) ?: return true
            return observed > now || Duration.between(observed, now) >= Duration.ofMinutes(15)
        }

        fun parse(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }
}

/** A supplied but malformed exact value must never fall back to the rounded numeric field. */
fun venueDecimal(exact: String?, numeric: BigDecimal?): String =
    if (exact == null) numeric?.toPlainString() ?: "—" else exact.toBigDecimalOrNull()?.toPlainString() ?: "—"
