package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun financialCapsPreserveNumericAndQuotedDecimals() {
        for (number in listOf("123456789.123456789", "\"123456789.123456789\"")) {
            val caps = json.decodeFromString<SystemDelegateCapsBodyDto>("""{"max_daily_notional_usd":$number,"max_open_orders":0}""")
            assertEquals(BigDecimal("123456789.123456789"), caps.maxDailyNotionalUsd)
            assertEquals(0L, caps.maxOpenOrders)
            assertNull(caps.maxCancelsPerMinute)
        }
    }

    @Test fun healthUnknownCountersRemainUnknown() {
        val health = json.decodeFromString<Envelope<SystemHealthCheckDataDto>>("""{"payload":{"public_id":"h1","status":"degraded","version":"1","connections":{},"topics":{"active":0},"gap_detection":{"bridge":{}}}}""").payload
        assertEquals("degraded", health.status)
        assertEquals(0L, health.topics.active)
        assertNull(health.connections.activeClients)
        assertNull(health.gapDetection.bridge.gapsDetected)
        assertNull(health.gapDetection.restClients)
    }

    @Test(expected = SerializationException::class)
    fun strategyMissingRunningStateDoesNotInventStoppedState() {
        json.decodeFromString<SystemStrategyProcessDto>("""{"public_id":"s1","name":"strategy","enabled":true,"mode":"paper"}""")
    }

    @Test fun directoryMissingActiveFlagRemainsUnknown() {
        val user = json.decodeFromString<SystemUserProfileDto>("""{"public_id":"u1","username":"user","role":"viewer","created_at":"2026-10-16T00:00:00Z"}""")
        assertNull(user.isActive)
    }

    @Test fun backtestEnvelopeKeepsSourceAndTargetVenuesDistinct() {
        val run = json.decodeFromString<Envelope<List<SystemBacktestRunDataDto>>>("""{"payload":[{"public_id":"b1","wallet_public_id":"w1","strategy_name":"example","instrument_public_id":"i1","exchange":"source","target_execution_exchange":"target","timeframe":"1h","start_date":"2026-10-01T00:00:00Z","end_date":"2026-10-02T00:00:00Z","initial_cash":123.123456789123456789,"status":"failed","error":"Unavailable"}]}""").payload.single()
        assertEquals("source", run.exchange)
        assertEquals("target", run.targetExecutionExchange)
        assertEquals(BigDecimal("123.123456789123456789"), run.initialCash)
        assertEquals("Unavailable", run.error)
    }
}
