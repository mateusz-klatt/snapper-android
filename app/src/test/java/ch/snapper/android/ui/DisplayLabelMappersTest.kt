package ch.snapper.android.ui

import ch.snapper.android.R
import ch.snapper.android.features.market.instrumentKindLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayLabelMappersTest {
    private fun assertMappings(expected: Map<String, Int>, mapper: (String) -> Int?) {
        expected.forEach { (wireValue, resource) ->
            assertEquals(wireValue, resource, mapper(wireValue))
        }
        assertNull("future_value", mapper("future_value"))
    }

    @Test fun tradeSidesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "buy" to R.string.order_buy,
            "sell" to R.string.order_sell,
        ), ::tradeSideLabelRes)
        assertEquals(R.string.order_sell, tradeSideLabelRes("SELL"))
    }

    @Test fun orderStatusesMapEveryBackendSpellingToResources() {
        assertMappings(mapOf(
            "pending" to R.string.records_status_pending,
            "pending_new" to R.string.records_status_pending_new,
            "new" to R.string.records_status_new,
            "submitted" to R.string.records_status_submitted,
            "open" to R.string.records_status_open,
            "closed" to R.string.records_status_closed,
            "partial" to R.string.records_status_partially_filled,
            "partially_filled" to R.string.records_status_partially_filled,
            "filled" to R.string.records_status_filled,
            "canceled" to R.string.records_status_cancelled,
            "cancelled" to R.string.records_status_cancelled,
            "expired" to R.string.records_status_expired,
            "rejected" to R.string.records_status_rejected,
        ), ::orderStatusLabelRes)
        assertEquals(R.string.records_status_new, orderStatusLabelRes("NEW"))
    }

    @Test fun alertPrioritiesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "low" to R.string.notification_defaults_low,
            "medium" to R.string.notification_defaults_medium,
            "high" to R.string.notification_defaults_high,
            "critical" to R.string.records_alert_priority_critical,
        ), ::alertPriorityLabelRes)
        assertEquals(R.string.records_alert_priority_critical, alertPriorityLabelRes("CRITICAL"))
    }

    @Test fun alertTypesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "order_fill_full" to R.string.notification_defaults_fill,
            "order_rejected" to R.string.notification_defaults_rejected,
            "position_stop_loss_fired" to R.string.notification_defaults_stop,
            "margin_warning" to R.string.notification_defaults_margin,
            "critical_system_error" to R.string.records_alert_type_critical_system_error,
            "order_unknown" to R.string.records_alert_type_order_unknown,
            "drift" to R.string.records_alert_type_drift,
        ), ::alertTypeLabelRes)
    }

    @Test fun accountStatusesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "observed" to R.string.venue_accounts_status_observed,
            "unobserved" to R.string.venue_accounts_status_unobserved,
            "simulated" to R.string.venue_accounts_status_simulated,
            "stale" to R.string.venue_accounts_status_stale,
            "clock_error" to R.string.venue_accounts_status_clock_error,
            "corrupt" to R.string.venue_accounts_status_corrupt,
            "unsupported" to R.string.venue_accounts_reconciliation_status_unsupported,
            "not_applicable" to R.string.venue_accounts_status_not_applicable,
            "unknown" to R.string.venue_accounts_status_unknown,
            "error" to R.string.system_status_error,
        ), ::accountStatusLabelRes)
    }

    @Test fun valuationStatusesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "native_only" to R.string.venue_accounts_valuation_native_only,
        ), ::valuationStatusLabelRes)
    }

    @Test fun evaluationStatusesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "matched" to R.string.venue_accounts_evaluation_matched,
            "mismatched" to R.string.venue_accounts_evaluation_mismatched,
            "incomplete" to R.string.venue_accounts_evaluation_incomplete,
            "unsupported" to R.string.venue_accounts_reconciliation_status_unsupported,
            "error" to R.string.system_status_error,
        ), ::evaluationStatusLabelRes)
    }

    @Test fun numericSourcesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "venue_raw" to R.string.venue_accounts_numeric_source_venue_raw,
            "legacy_float" to R.string.venue_accounts_numeric_source_legacy_float,
        ), ::numericSourceLabelRes)
    }

    @Test fun reconciliationStatusesIncludeAccountFallbacksAndRejectUnknownValues() {
        assertMappings(mapOf(
            "matched" to R.string.venue_accounts_evaluation_matched,
            "mismatched" to R.string.venue_accounts_evaluation_mismatched,
            "incomplete" to R.string.venue_accounts_evaluation_incomplete,
            "unsupported" to R.string.venue_accounts_reconciliation_status_unsupported,
            "error" to R.string.venue_accounts_reconciliation_status_error,
            "observed" to R.string.venue_accounts_status_observed,
            "unobserved" to R.string.venue_accounts_status_unobserved,
            "simulated" to R.string.venue_accounts_status_simulated,
            "stale" to R.string.venue_accounts_status_stale,
            "clock_error" to R.string.venue_accounts_status_clock_error,
            "corrupt" to R.string.venue_accounts_status_corrupt,
            "not_applicable" to R.string.venue_accounts_status_not_applicable,
            "unknown" to R.string.venue_accounts_status_unknown,
        ), ::reconciliationStatusLabelRes)
    }

    @Test fun backtestStatusesMapEveryPresentationBranchToResources() {
        val expected = mapOf(
            "pending" to R.string.backtests_status_pending,
            "running" to R.string.backtests_status_running,
            "completed" to R.string.backtests_status_completed,
            "failed" to R.string.backtests_status_failed,
            "cancelled" to R.string.backtests_status_cancelled,
            "unknown" to R.string.backtests_status_unknown,
            "cancel_requested" to R.string.backtests_status_cancel_requested,
        )
        expected.forEach { (wireValue, resource) ->
            assertEquals(wireValue, resource, backtestStatusLabelRes(wireValue))
        }
        assertEquals(R.string.backtests_status_cancel_requested, backtestStatusLabelRes("CANCEL_REQUESTED"))
        assertEquals(R.string.backtests_status_unknown, backtestStatusLabelRes("future_value"))
    }

    @Test fun systemStatusesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "ready" to R.string.system_status_ready,
            "degraded" to R.string.system_status_degraded,
            "healthy" to R.string.system_status_healthy,
            "running" to R.string.system_running,
            "not_running" to R.string.system_status_not_running,
            "stopped" to R.string.system_stopped,
            "starting" to R.string.system_status_starting,
            "stopping" to R.string.system_status_stopping,
            "error" to R.string.system_status_error,
            "warning" to R.string.system_status_warning,
            "pending" to R.string.backtests_status_pending,
            "completed" to R.string.backtests_status_completed,
            "failed" to R.string.backtests_status_failed,
            "cancelled" to R.string.backtests_status_cancelled,
            "unknown" to R.string.backtests_status_unknown,
            "active" to R.string.system_active,
            "inactive" to R.string.system_inactive,
            "ok" to R.string.system_status_ok,
        ), ::systemStatusLabelRes)
        assertEquals(R.string.system_status_ready, systemStatusLabelRes("READY"))
    }

    @Test fun tradingModesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "live" to R.string.order_live,
            "paper" to R.string.paper,
        ), ::tradingModeLabelRes)
        assertEquals(R.string.paper, tradingModeLabelRes("PAPER"))
    }

    @Test fun processModesIncludeTradingFallbacksAndRejectUnknownValues() {
        assertMappings(mapOf(
            "thread" to R.string.process_mode_thread,
            "process" to R.string.process_mode_process,
            "live" to R.string.order_live,
            "paper" to R.string.paper,
        ), ::processModeLabelRes)
        assertEquals(R.string.process_mode_thread, processModeLabelRes("THREAD"))
    }

    @Test fun userRolesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "viewer" to R.string.settings_role_viewer,
            "operator" to R.string.settings_role_operator,
            "admin" to R.string.settings_role_admin,
            "ai_delegate" to R.string.settings_role_ai_delegate,
            "ai_reviewer" to R.string.settings_role_ai_reviewer,
            "ai_researcher" to R.string.settings_role_ai_researcher,
            "auditor" to R.string.settings_role_auditor,
        ), ::userRoleLabelRes)
        assertEquals(R.string.settings_role_admin, userRoleLabelRes("ADMIN"))
    }

    @Test fun processRolesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "core" to R.string.process_role_core,
            "task" to R.string.process_role_task,
            "strategy" to R.string.process_role_strategy,
            "backtest" to R.string.process_role_backtest,
        ), ::processRoleLabelRes)
    }

    @Test fun pendingReviewStatusesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "pending" to R.string.ai_reviews_pending,
            "fanout_dispatched" to R.string.ai_reviews_status_fanout_dispatched,
            "resolved_approved" to R.string.ai_reviews_status_approved,
            "resolved_rejected" to R.string.ai_reviews_status_rejected,
            "timeout" to R.string.ai_reviews_status_timeout,
            "superseded" to R.string.ai_reviews_superseded,
        ), ::pendingReviewStatusLabelRes)
        assertEquals(R.string.ai_reviews_pending, pendingReviewStatusLabelRes("PENDING"))
    }

    @Test fun decisionStatusesMapApprovedRejectedAndEveryFallbackToResources() {
        val expected = mapOf(
            "approve" to R.string.ai_reviews_status_approved,
            "reject" to R.string.ai_reviews_status_rejected,
            "pending" to R.string.ai_reviews_pending,
            "expired" to R.string.ai_reviews_expired,
            "superseded" to R.string.ai_reviews_superseded,
            "unknown" to R.string.ai_reviews_unknown,
            "future_value" to R.string.ai_reviews_unknown,
        )
        expected.forEach { (wireValue, resource) ->
            assertEquals(wireValue, resource, decisionStatusLabel(wireValue))
        }
    }

    @Test fun instrumentKindsMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "spot" to R.string.market_instrument_kind_spot,
            "perpetual" to R.string.market_instrument_kind_perpetual,
            "future" to R.string.market_instrument_kind_future,
            "etf" to R.string.market_instrument_kind_etf,
            "option" to R.string.market_instrument_kind_option,
        ), ::instrumentKindLabelRes)
    }

    @Test fun executionHistoryStatusesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "as_recorded" to R.string.pnl_execution_history_as_recorded,
            "operator_corrected" to R.string.pnl_execution_history_operator_corrected,
        ), ::executionHistoryStatusLabelRes)
    }

    @Test fun withholdingScopesMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "global" to R.string.positions_timeline_incompleteness_scope_global,
            "instrument" to R.string.instrument,
        ), ::withholdingScopeLabelRes)
    }

    @Test fun withholdingTiersMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "mark_incomplete" to R.string.positions_timeline_incompleteness_tier_mark_incomplete,
            "untrusted" to R.string.positions_timeline_incompleteness_tier_untrusted,
        ), ::withholdingTierLabelRes)
    }

    @Test fun attributionOriginsMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "manual" to R.string.positions_timeline_attribution_origins_manual,
            "plan" to R.string.positions_timeline_attribution_origins_plan,
            "system" to R.string.positions_timeline_attribution_origins_system,
            "unattributed" to R.string.positions_timeline_attribution_origins_unattributed,
        ), ::attributionOriginLabelRes)
    }

    @Test fun correctionReasonsMapToResourcesAndUnknownValuesFallThrough() {
        assertMappings(mapOf(
            "unwitnessed_phantom" to R.string.positions_timeline_executionhistory_reasons_unwitnessed_phantom,
            "unwitnessed_legacy_lineage" to R.string.positions_timeline_executionhistory_reasons_unwitnessed_legacy_lineage,
        ), ::correctionReasonLabelRes)
    }
}
